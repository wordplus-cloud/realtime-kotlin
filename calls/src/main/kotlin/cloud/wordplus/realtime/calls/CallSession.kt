package cloud.wordplus.realtime.calls

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A room on the mesh the relay hands a call to: its server (none means the relay's own) and a token for it. */
internal data class CallRoomTicket(val token: String, val server: String?) {
    companion object {
        fun from(value: Any?): CallRoomTicket? {
            val map = value as? JSONObject ?: return null
            val token = map.optString("token").takeIf { it.isNotEmpty() } ?: return null
            return CallRoomTicket(token, map.optString("server").takeIf { it.isNotEmpty() })
        }
    }

    /**
     * The same room, at [server] when the relay named none: Better Messages' relay hands over a room's token alone, as its
     * pages know their media server from the site, where the SDK's own names the mesh in it.
     */
    fun at(server: String?): CallRoomTicket = if (this.server == null && !server.isNullOrEmpty()) copy(server = server) else this
}

/**
 * One side of a call between two people, on the relay: the port of the web's `calls/session.ts` that browsers run,
 * driving `CallPeer` (the port of `peer.ts`).
 *
 * It joins the call (as its caller, that makes it ring), builds the direct connection once both sides are there,
 * carries the sealed signals both ways, keeps the TURN credentials and the room token fresh, pulses the relay while the
 * direct path runs, and hands the call to a room on the mesh when the path can't be had. Everything on the main thread.
 */
internal class CallSession(
    private val context: Context,
    private val transport: CallTransport,
    val callId: String,
    val video: Boolean,
    signalKey: String,
) : CallPeerListener {
    companion object {
        private const val KEEPALIVE_MS = 60_000L
        private const val STATE_RESEND_TICKS = 8
        private const val RENEW_MARGIN_MS = 600_000L
        private const val RENEW_MIN_GAP_MS = 60_000L
        private const val ICE_SERVERS_BUDGET_MS = 2_000L

        fun iceServers(value: Any?): List<PeerConnection.IceServer> {
            val list = value as? JSONArray ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val entry = list.optJSONObject(i) ?: return@mapNotNull null
                val urls = when (val raw = entry.opt("urls")) {
                    is JSONArray -> (0 until raw.length()).map { raw.optString(it) }.filter { it.isNotEmpty() }
                    is String -> listOf(raw)
                    else -> emptyList()
                }
                if (urls.isEmpty()) return@mapNotNull null
                val builder = PeerConnection.IceServer.builder(urls)
                if (entry.has("username") && entry.has("credential")) builder.setUsername(entry.optString("username")).setPassword(entry.optString("credential"))
                builder.createIceServer()
            }
        }

        /** Stream ids are `<ms>-<seq>`; the later one is the greater. */
        fun compareStreamIds(a: String, b: String): Int {
            val left = a.split('-').map { it.toLongOrNull() ?: 0 }
            val right = b.split('-').map { it.toLongOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val x = left.getOrElse(i) { 0 }
                val y = right.getOrElse(i) { 0 }
                if (x != y) return if (x < y) -1 else 1
            }
            return 0
        }

        /** Whether a room token will still be taken a moment from now, by the expiry it carries. */
        fun roomTokenUsable(token: String): Boolean {
            val parts = token.split('.')
            if (parts.size < 2) return false
            val claims = runCatching { JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))) }.getOrNull() ?: return false
            if (!claims.has("exp")) return true
            return claims.optDouble("exp") - System.currentTimeMillis() / 1000.0 > 60
        }
    }

    // What the call hears: the other side arrived, the connection's state, what it sends changed, the numbers, the
    // relay moved the call to a room, the call ended, and the other side asking for a device to be opened again.
    var onPeerJoined: (() -> Unit)? = null
    var onConnection: ((CallConnection) -> Unit)? = null
    var onRemote: (() -> Unit)? = null
    var onStats: ((CallPeerStats) -> Unit)? = null
    var onFallback: ((CallRoomTicket) -> Unit)? = null
    var onEnded: ((String) -> Unit)? = null
    var onReacquire: ((CallSource) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val media = CallMedia.get(context)
    private val signaling = CallSignaling(transport, callId) { serverMessage(it) }
    private val cipher = CallCipher.create(signalKey)
    private var peer: CallPeer? = null
    private var polite = true
    private var generation = -1
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var lastId: String? = null
    private val pendingSignals = ArrayList<JSONObject>()
    var remoteState: Map<String, Boolean> = emptyMap()
        private set
    var remoteStateKnown = false
        private set
    val remoteTracks = HashMap<CallSource, MediaStreamTrack>()
    private val localTracks = HashMap<CallSource, Pair<MediaStreamTrack, Int>>()
    private var peerCaps: CallCodecCaps? = null
    private var remoteWanted: Map<String, Int>? = null
    private var sentView: Map<String, Int>? = null

    /** How tall this app draws the other side's camera and screen, which sets what they send. */
    var viewHeights: Map<String, Int> = mapOf("camera" to 720, "screen_share" to 1080)
        set(value) {
            field = value
            sendView()
        }
    private var standby: CallRoomTicket? = null
    private var joinedSocket = false
    private var peerLost = false
    private var iceExpiresAt = 0L
    private var renewWork: Runnable? = null
    private var keepalive: Runnable? = null
    private var pulseTicks = 0

    /**
     * How the call is going here, told on a pulse every [qualityEvery] ms as the relay's reply asks ([CallQualityWatch]);
     * the device's own part from the system's callbacks, watched only once readings are asked.
     */
    private val health by lazy { CallHealthWatch(context) }
    private var qualityEvery = 0L
    private var qualityAt = 0L
    private var reportedAt = 0L
    private val knownCodecs = arrayOfNulls<String>(4)
    private var bytesSent = 0L
    private var bytesRecv = 0L
    private val traceLines = ArrayList<String>()
    private var traceWork: Runnable? = null
    private val traceStart = System.currentTimeMillis()
    private var stopReconnect: (() -> Unit)? = null
    private var started = false
    var ended = false
        private set
    var handedOff = false
        private set
    var peerUserId: String? = null
        private set
    private var peerPresent = false
    var stats = CallPeerStats()
        private set

    val isConnected: Boolean get() = peer?.isUp == true

    // Joining.

    /** Joins the call on the relay; false when it could not. */
    fun start(done: (Boolean) -> Unit) {
        if (started || ended) return done(false)
        started = true
        CallLog.setSink { line -> main.post { trace(line) } }
        // A socket that has just come back is not joined until the rejoin says so. On the main thread, whichever the
        // transport tells it on.
        stopReconnect = transport.onReconnect {
            main.post {
                if (ended) return@post
                joinedSocket = false
                reviewHold()
                if (transport.connected) join(true) {}
            }
        }
        join(false, done)
    }

    private fun join(resume: Boolean, done: (Boolean) -> Unit) {
        if (ended) return done(false)
        signaling.join(0, lastId, resume) { reply ->
            if (ended) return@join done(false)
            if (reply == null) {
                if (!resume) finish("signaling_unavailable")
                return@join done(false)
            }
            if (!reply.optBoolean("ok", true)) {
                val error = reply.optString("error", "join_failed")
                when {
                    resume && error == "call_ended" -> finish(reply.optString("reason", "ended"))
                    resume && error in setOf("answered_elsewhere", "no_call", "not_participant") -> finish(error)
                    !resume -> finish(error)
                }
                return@join done(false)
            }
            if (reply.optString("mode") == "sfu") {
                CallRoomTicket.from(reply.opt("sfu"))?.let {
                    handoff(it, "the join says the call is on the room")
                    return@join done(true)
                }
            }

            val kept = peer
            polite = reply.optString("role", "polite") == "polite"
            standby = CallRoomTicket.from(reply.opt("sfu"))
            val peerInfo = reply.optJSONObject("peer")
            if (peerInfo != null) {
                if (!peerInfo.isNull("user_id")) peerUserId = peerInfo.opt("user_id").toString()
                peerLost = peerInfo.optBoolean("gone", false)
            }
            joinedSocket = true
            val servers = iceServers(reply.opt("ice_servers"))
            iceServers = servers
            val renewed = setIceExpiry(reply.opt("ice_expires_at"), 1_000)
            trace("${if (resume) "rejoin" else "join"} role=${if (polite) "polite" else "impolite"} gen=$generation peer=$peerUserId present=${peerInfo?.optBoolean("present") == true}")

            if (reply.has("generation")) {
                val next = reply.optInt("generation")
                if (next != generation && peer != null) {
                    generation = next
                    rebuildPeer()
                } else {
                    generation = next
                }
            }
            if (peerInfo?.optBoolean("present") == true) markPeerPresent()
            sendCaps()
            val replay = reply.optJSONArray("replay")
            if (replay != null) for (i in 0 until replay.length()) replay.optJSONObject(i)?.let { signalArrived(it) }

            val wasHeld = peer?.held == true
            reviewHold()
            val now = peer
            if (resume && renewed && now != null && now === kept) now.renewRelay(servers)
            if (resume && !wasHeld && now != null && !polite && !now.isUp) now.restart(false, "rejoined")
            startKeepalive()
            done(true)
        }
    }

    private fun markPeerPresent() {
        if (peerPresent) return
        peerPresent = true
        onPeerJoined?.invoke()
    }

    private fun startKeepalive() {
        if (keepalive != null || ended) return
        val tick = object : Runnable {
            override fun run() {
                if (ended) return
                alive()
                main.postDelayed(this, KEEPALIVE_MS)
            }
        }
        keepalive = tick
        main.postDelayed(tick, KEEPALIVE_MS)
        // From the join on, ringing included: a caller whose connection moved while the other side was being rung
        // would hear its `ready` where it no longer listens ([setupAliveEveryMs]).
        val setup = object : Runnable {
            override fun run() {
                if (ended) return
                if (!handedOff && peer?.isUp != true && joinedSocket && signaling.connected &&
                    System.currentTimeMillis() - aliveAt >= setupAliveEveryMs) alive()
                main.postDelayed(this, setupAliveEveryMs)
            }
        }
        setupAlive = setup
        main.postDelayed(setup, setupAliveEveryMs)
    }

    /**
     * While the direct path is not up, the session also checks in this often ([startKeepalive]): the relay answers
     * `not_joined` to a connection other than the one it joined on, and the session joins again, the relay replaying
     * what it missed. A connection that moved or came back without this side hearing of it left the relay sending the
     * call's signals where nobody read them, with nothing but the keepalive, once a minute, to say so (the web engine,
     * 31 Oct 2026). A connected call pulses every couple of seconds, which asks the same.
     */
    private val setupAliveEveryMs = 5_000L
    private var aliveAt = 0L
    private var setupAlive: Runnable? = null

    /** The reply says whether the relay lost this session, the call ended meanwhile, or moved to a room. */
    private fun alive() {
        if (ended || !signaling.connected) return
        aliveAt = System.currentTimeMillis()
        signaling.alive { reply ->
            if (ended || reply == null) return@alive
            if (reply.optBoolean("ok", true)) {
                if (reply.optString("mode") == "sfu") CallRoomTicket.from(reply.opt("sfu"))?.let { handoff(it, "the alive says the call is on the room") }
                return@alive
            }
            when (reply.optString("error")) {
                "call_ended" -> finish(reply.optString("reason", "ended"))
                "not_joined" -> join(true) {}
            }
        }
    }

    // The relay's events.

    private fun serverMessage(message: JSONObject) {
        // After the hand-off the call runs in the room, but the relay still owns it: its end is heard here.
        if (handedOff) {
            if (message.optString("op") == "ended") {
                signaling.close()
                onEnded?.invoke(message.optString("reason", "ended"))
            }
            return
        }
        if (ended) return

        when (message.optString("op")) {
            "ready" -> {
                val next = message.optInt("generation", 0)
                message.optJSONObject("peer")?.let { if (!it.isNull("user_id")) peerUserId = it.opt("user_id").toString() }
                markPeerPresent()
                peerLost = false
                if (peer != null && next <= generation) {
                    reviewHold()
                    return
                }
                generation = next
                rebuildPeer()
            }
            "signal" -> {
                if (peerLost) {
                    peerLost = false
                    reviewHold()
                }
                signalArrived(message)
            }
            "peer_lost" -> {
                peerLost = true
                reviewHold()
                onRemote?.invoke()
            }
            "peer_back" -> {
                val wasHeld = peer?.held == true
                peerLost = false
                reviewHold()
                val now = peer
                if (!wasHeld && now != null && !polite && !now.isUp) now.restart(false, "the other side is back")
            }
            "fallback" -> (CallRoomTicket.from(message.opt("sfu")) ?: standby)?.let { handoff(it, "fallback from the relay") }
            "ended" -> finish(message.optString("reason", "ended"))
        }
    }

    // The connection.

    private fun rebuildPeer() {
        if (ended || handedOff) return
        peer?.close()
        peer = null
        remoteState = emptyMap()
        remoteStateKnown = false
        remoteTracks.clear()

        val built = CallPeer(polite, media.factory, iceServers, false, media.codecCaps, null)
        built.listener = this
        peer = built
        sentView = null
        pulseTicks = 0
        built.start()
        peerCaps?.let { built.setPeerCaps(it) }
        for ((source, local) in localTracks) built.setLocalTrack(source, local.first, local.second)
        remoteWanted?.let { built.setWanted(it) }
        sendState()
        sendCaps()

        // Parked for a peer or a generation this side didn't have yet: handed back in, their ids already taken.
        val pending = pendingSignals.toList()
        pendingSignals.clear()
        for (envelope in pending) handleSignal(envelope, true)

        reviewHold()
        onRemote?.invoke()
    }

    /** What this side sends from a source: a live track, or null to stop sending it. */
    fun setLocalTrack(source: CallSource, track: MediaStreamTrack?, height: Int = 0) {
        if (ended) return
        if (track != null) localTracks[source] = track to height else localTracks.remove(source)
        peer?.setLocalTrack(source, track, height)
        sendState()
    }

    private fun sendState() {
        if (ended || peer == null) return
        val state = JSONObject()
        for (source in CallSource.ORDER) state.put(source.wire, localTracks[source]?.first?.enabled() == true)
        sendSignal(JSONObject().put("kind", "state").put("state", state))
    }

    private fun sendCaps() {
        if (ended) return
        sendSignal(JSONObject().put("kind", "caps").put("caps", media.codecCaps.json()), needsPeer = false)
    }

    /** How tall this side shows each of the other side's video sources it has on; a source they have off is left out. */
    private fun sendView() {
        val now = peer ?: return
        if (ended || !now.isUp || !remoteStateKnown) return
        val view = HashMap<String, Int>()
        for (source in listOf("camera", "screen_share")) if (remoteState[source] == true) view[source] = viewHeights[source] ?: 720
        if (view == sentView) return
        sentView = view
        updateExpectInbound()
        trace("view sent " + view.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" })
        sendSignal(JSONObject().put("kind", "view").put("view", JSONObject(view as Map<*, *>)))
    }

    /** Media is expected from the other side for a source it has on that this side hasn't asked it to stop sending. */
    private fun updateExpectInbound() {
        val now = peer ?: return
        val video = HashSet<CallSource>()
        for (source in listOf(CallSource.CAMERA, CallSource.SCREEN_SHARE)) {
            if (remoteState[source.wire] != true) continue
            val wanted = sentView?.get(source.wire)
            if (wanted != null && wanted <= 0) continue
            video.add(source)
        }
        val audio = remoteState["microphone"] == true || remoteState["screen_share_audio"] == true
        now.setExpectInbound(audio || video.isNotEmpty())
        now.setExpectedVideo(video)
        now.setExpectedAudio(listOf(CallSource.MICROPHONE, CallSource.SCREEN_SHARE_AUDIO).filter { remoteState[it.wire] == true }.toSet())
    }

    private fun sendSignal(body: JSONObject, needsPeer: Boolean = true) {
        if (ended || (needsPeer && peer == null)) return
        val sentGeneration = generation
        val wire = cipher?.seal(body) ?: body
        val kind = body.optString("kind", "signal")
        val sentFor = peer
        signaling.signal(sentGeneration, wire) { reply ->
            if (ended) return@signal
            if (reply == null) return@signal trace("$kind sent gen=$sentGeneration -> failed")
            if (!reply.optBoolean("ok", true)) {
                trace("$kind sent gen=$sentGeneration -> refused ${reply.optString("error", "?")}")
                if (reply.optString("error") == "stale_generation" && reply.has("generation")) {
                    val newer = reply.optInt("generation")
                    if (newer > generation && sentFor != null && peer === sentFor) {
                        generation = newer
                        rebuildPeer()
                    }
                }
            }
        }
    }

    /** A relayed signal: opened with the call's key, then handled once, in order. */
    private fun signalArrived(envelope: JSONObject) {
        if (ended) return
        var body = envelope.optJSONObject("body") ?: return
        if (CallCipher.isSealed(body)) {
            body = cipher?.open(body) ?: return trace("a signal could not be opened")
        }
        handleSignal(JSONObject(envelope.toString()).put("body", body), false)
    }

    private fun handleSignal(envelope: JSONObject, replayed: Boolean) {
        if (ended) return
        val body = envelope.optJSONObject("body") ?: return
        if (!replayed) {
            val id = envelope.optString("id")
            if (id.isNotEmpty()) {
                val last = lastId
                if (last != null && compareStreamIds(id, last) <= 0) return
                lastId = id
            }
        }
        val signalGeneration = envelope.optInt("generation", 0)
        if (signalGeneration < generation) return
        val now = peer
        if (now == null || signalGeneration > generation) {
            pendingSignals.add(envelope)
            return
        }

        // Any word from the other side, whatever it says, is the other side still at work on the call: a negotiation it
        // is making is not given up on while it keeps speaking (CallPeer.heardFromPeer).
        now.heardFromPeer()

        when (body.optString("kind")) {
            "state" -> {
                val state = body.optJSONObject("state") ?: JSONObject()
                remoteState = state.keys().asSequence().associateWith { state.optBoolean(it, false) }
                remoteStateKnown = true
                updateExpectInbound()
                onRemote?.invoke()
                sendView()
            }
            "view" -> {
                val view = body.optJSONObject("view") ?: JSONObject()
                val wanted = view.keys().asSequence().associateWith { view.optInt(it, 0) }
                remoteWanted = wanted
                now.setWanted(wanted)
            }
            "caps" -> CallCodecCaps.from(body.optJSONObject("caps"))?.let {
                peerCaps = it
                now.setPeerCaps(it)
            }
            "reacquire" -> CallSource.of(body.optString("source"))?.let {
                trace("the other side says this side's ${it.wire} sends nothing, opening it again")
                onReacquire?.invoke(it)
            }
            else -> now.handleSignal(body)
        }
    }

    // Holding off, renewing, pulsing.

    /**
     * Whether a restart could reach the other side now: joined on the connection this side has, that connection
     * answering, and the relay not having said the other side's is gone. The peer holds its escalation while it
     * couldn't. A connection that says it is connected and answers nothing is as good as down for an offer, which waits
     * in it: a restart made into one arrived half a minute late, over a connection that had come back by itself
     * meanwhile (the web engine, 31 Oct 2026).
     */
    private fun reviewHold() {
        val now = peer ?: return
        if (ended) return
        if (!signaling.connected) joinedSocket = false
        now.setSignalRoundTrip(signaling.roundTripMs)
        val stalled = if (joinedSocket) signaling.stalledForMs() else 0L
        when {
            !joinedSocket -> now.setSignalingHeld(true, "this side's realtime connection is down")
            stalled > 0L -> now.setSignalingHeld(true, "this side's realtime connection has answered nothing for ${String.format(java.util.Locale.US, "%.1f", stalled / 1000.0)}s")
            peerLost -> now.setSignalingHeld(true, "the relay says the other side's connection is down")
            else -> now.setSignalingHeld(false, "both sides can be reached")
        }
    }

    /** Whether the expiry moved; the renewal is scheduled from it, not sooner than `soonestMs`. */
    private fun setIceExpiry(value: Any?, soonestMs: Long): Boolean {
        val seconds = (value as? Number)?.toDouble() ?: 0.0
        if (seconds <= 0) return false
        val expires = (seconds * 1000).toLong()
        val moved = expires != iceExpiresAt
        iceExpiresAt = expires
        scheduleRenewal(soonestMs)
        return moved
    }

    private fun scheduleRenewal(soonestMs: Long) {
        renewWork?.let { main.removeCallbacks(it) }
        renewWork = null
        if (ended || iceExpiresAt == 0L) return
        val left = iceExpiresAt - System.currentTimeMillis()
        val margin = minOf(RENEW_MARGIN_MS, maxOf(5_000L, left / 4))
        val work = Runnable {
            renewWork = null
            renewRelay()
        }
        renewWork = work
        main.postDelayed(work, maxOf(soonestMs, left - margin))
    }

    private fun renewRelay() {
        if (ended || peer == null) return
        fetchIceServers { servers ->
            val now = peer
            if (ended || now == null) return@fetchIceServers
            if (servers.isEmpty()) {
                trace("the relay credentials could not be renewed, asking again in a minute")
                return@fetchIceServers scheduleRenewal(RENEW_MIN_GAP_MS)
            }
            now.renewRelay(servers)
        }
    }

    /** Fresh credentials, and the room token with them; empty when none came. */
    private fun fetchIceServers(done: (List<PeerConnection.IceServer>) -> Unit) {
        signaling.iceServers { reply ->
            if (ended || reply == null || !reply.optBoolean("ok", true)) return@iceServers done(emptyList())
            CallRoomTicket.from(reply.opt("sfu"))?.let { standby = it }
            val servers = iceServers(reply.opt("ice_servers"))
            if (servers.isNotEmpty()) {
                iceServers = servers
                setIceExpiry(reply.opt("ice_expires_at"), RENEW_MIN_GAP_MS)
            }
            done(servers)
        }
    }

    /** This side's counters to the relay with each stats pass while the direct path runs, and its reading back. */
    private fun pulse() {
        val now = peer ?: return
        if (ended || !now.isUp || !signaling.connected) return
        pulseTicks++
        if (pulseTicks % STATE_RESEND_TICKS == 0) sendState()
        // A quality reading when one is due: the window since the last one, which the peer keeps from its stats passes.
        val at = System.currentTimeMillis()
        var quality: JSONObject? = null
        if (qualityEvery > 0 && at - qualityAt >= qualityEvery) {
            qualityAt = at
            quality = now.qualityReading(health.reading(at.toDouble()))
        }
        signaling.pulse(now.pulseBody(), quality) { reply ->
            if (ended || peer !== now || reply == null) return@pulse
            // How often the relay wants the readings, every reply: one before them says nothing and gets none.
            val every = reply.optLong("qms", 0L).coerceAtLeast(0L)
            if (reply.optBoolean("ok", true) && every != qualityEvery) {
                qualityEvery = every
                qualityAt = at
                if (every > 0) health.start() else health.stop()
            }
            if (!reply.optBoolean("ok", true)) {
                if (reply.optString("error") == "not_joined") join(true) {}
                return@pulse
            }
            now.setPeerPulse(CallPeerPulse.from(reply.optJSONObject("peer")))
            CallRoomTicket.from(reply.opt("sfu"))?.let { handoff(it, "the relay judged the direct path dead from both sides' counters") }
        }
    }

    /** What this side ran the call on, for the call's record: every twenty seconds, and at the end. */
    private fun report(stats: CallPeerStats, force: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        if (!force && nowMs - reportedAt < 20_000) return
        reportedAt = nowMs
        stats.videoSend?.let { knownCodecs[0] = it }
        stats.videoRecv?.let { knownCodecs[1] = it }
        stats.audioSend?.let { knownCodecs[2] = it }
        stats.audioRecv?.let { knownCodecs[3] = it }
        bytesSent = maxOf(bytesSent, stats.bytesSent)
        bytesRecv = maxOf(bytesRecv, stats.bytesReceived)
        val relay = stats.candidateLocal == "relay" || stats.candidateRemote == "relay"
        signaling.report(
            JSONObject()
                .put("browser", "app").put("platform", "android").put("client", "android")
                .also { CallClientInfo.putInto(it, context, media) }
                .put("path", if (relay) "relay" else "direct")
                .put("candidate", JSONObject().put("local", stats.candidateLocal ?: "").put("remote", stats.candidateRemote ?: "").put("protocol", stats.candidateProtocol ?: ""))
                .put("restarts", stats.restarts)
                .put("video", JSONObject().put("send", knownCodecs[0] ?: "").put("recv", knownCodecs[1] ?: ""))
                // `red`: whether redundant audio leads the microphone's codecs each way, which its codec name never says
                .put("audio", JSONObject().put("send", knownCodecs[2] ?: "").put("recv", knownCodecs[3] ?: "").apply { peer?.redState()?.let { put("red", it) } })
                .put("impl", JSONObject().put("enc", stats.videoEncoder ?: "").put("dec", stats.videoDecoder ?: ""))
                .put("bytes", JSONObject().put("sent", bytesSent).put("recv", bytesRecv)),
        )
    }

    // The trace.

    /** One line of this side's account, batched to the call's trace every two seconds; never SDP, addresses or names. */
    fun trace(line: String) {
        CallLog.debug(line)
        if ((ended && !handedOff) || signaling.isClosed || traceLines.size >= 200) return
        traceLines.add(String.format(Locale.US, "+%.3f ", (System.currentTimeMillis() - traceStart) / 1000.0) + line)
        if (traceWork != null) return
        val work = Runnable {
            traceWork = null
            flushTrace()
        }
        traceWork = work
        main.postDelayed(work, 2_000)
    }

    private fun flushTrace() {
        if (traceLines.isEmpty() || signaling.isClosed) return
        val lines = traceLines.toList()
        traceLines.clear()
        signaling.trace(lines) {}
    }

    // The end.

    /** The direct path is left for a room on the mesh, with its token in hand. */
    private fun handoff(ticket: CallRoomTicket, why: String) {
        if (ended) return
        trace("handing off to the room: $why")
        flushTrace()
        handedOff = true
        teardown(keepListening = true)
        onFallback?.invoke(ticket)
    }

    /** A call handed off to a room ends through the room, which the relay doesn't see: it is told. */
    fun leaveAfterHandoff(reason: String) {
        trace("room: leaving ($reason)")
        flushTrace()
        signaling.leaveNow(reason)
        signaling.close()
        CallLog.setSink(null)
    }

    /** Hangs up here: the last numbers and lines, then the leave. */
    fun end(reason: String) {
        if (ended) return
        peer?.let { report(it.stats, force = true) }
        trace("ended: $reason")
        flushTrace()
        signaling.leave(reason)
        teardown()
    }

    private fun finish(reason: String) {
        if (ended) return
        trace("ended: $reason")
        flushTrace()
        teardown()
        onEnded?.invoke(reason)
    }

    private fun teardown(keepListening: Boolean = false) {
        ended = true
        health.stop()
        stopReconnect?.invoke()
        stopReconnect = null
        renewWork?.let { main.removeCallbacks(it) }
        renewWork = null
        keepalive?.let { main.removeCallbacks(it) }
        keepalive = null
        setupAlive?.let { main.removeCallbacks(it) }
        setupAlive = null
        peer?.close()
        peer = null
        if (!keepListening) {
            // The leave and the last trace are already on their way: the socket keeps listening until they are.
            main.postDelayed({ signaling.close() }, 3_000)
            CallLog.setSink(null)
        }
    }

    // The peer's listener.

    override fun onPeerSend(body: JSONObject) {
        main.post { sendSignal(body) }
    }

    override fun onPeerTrack(source: CallSource, track: MediaStreamTrack?) {
        main.post {
            if (track != null) remoteTracks[source] = track else remoteTracks.remove(source)
            onRemote?.invoke()
        }
    }

    override fun onPeerConnection(state: CallConnection) {
        main.post {
            if (ended) return@post
            trace("connection ${state.wire}")
            if (state == CallConnection.CONNECTED) {
                // A rebuilt or restarted connection starts from full size on the other side: it is told again.
                sentView = null
                sendView()
            }
            onConnection?.invoke(state)
        }
    }

    override fun onPeerStats(stats: CallPeerStats) {
        main.post {
            if (ended) return@post
            this.stats = stats
            reviewHold()
            pulse()
            report(stats)
            onStats?.invoke(stats)
        }
    }

    /**
     * The direct path won't happen: the relay is told, and moves both sides to a room, at once with the token held from
     * the join, or with the one its answer brings.
     */
    override fun onPeerFailed(reason: String) {
        main.post {
            if (ended) return@post
            trace("direct path given up: $reason")
            val held = standby?.takeIf { roomTokenUsable(it.token) }
            signaling.failed(reason) { reply ->
                if (ended || held != null) return@failed
                val ticket = if (reply != null && reply.optBoolean("ok", true)) CallRoomTicket.from(reply.opt("sfu")) else null
                if (ticket != null) handoff(ticket, "$reason, with the room token from the answer") else finish("connection_failed")
            }
            if (held != null) handoff(held, "$reason, with the room token held from the join")
        }
    }

    override fun onPeerRefreshIceServers(done: (List<PeerConnection.IceServer>) -> Unit) {
        main.post {
            // A restart goes on with what it has after a short wait.
            var settled = false
            main.postDelayed({
                if (!settled) {
                    settled = true
                    done(emptyList())
                }
            }, ICE_SERVERS_BUDGET_MS)
            fetchIceServers { servers ->
                if (!settled) {
                    settled = true
                    done(servers)
                }
            }
        }
    }

    override fun onPeerCodecBroken(family: String, direction: String) {
        main.post {
            if (!CallCodecs.markBroken(context, family, direction)) return@post
            val caps = media.codecCaps
            sendSignal(JSONObject().put("kind", "caps").put("caps", caps.json()))
            peer?.setOwnCaps(caps)
        }
    }

    override fun onPeerSilent(source: CallSource) {
        main.post {
            if (ended || peer == null) return@post
            trace("telling the other side its ${source.wire} sends nothing")
            sendSignal(JSONObject().put("kind", "reacquire").put("source", source.wire))
        }
    }
}
