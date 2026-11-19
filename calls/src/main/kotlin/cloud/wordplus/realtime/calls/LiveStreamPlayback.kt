package cloud.wordplus.realtime.calls

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// How a live stream plays here, to debug it, and the viewer's choice of picture, as the browser's stream has them
// (realtime-client sdk-calls/stream.ts:268-289, streams/playback.ts); the Swift package's LiveStreamPlayback twin. The room
// client keeps no list of a sender's layers open to this module, so they are worked out from the picture's size: the
// largest as it is, then half and a quarter.

/** A viewer's choice of picture: by the size of the view showing it (`AUTO`, the default), or one of its layers. */
enum class LiveStreamQuality(val wire: String) { AUTO("auto"), HIGH("high"), MEDIUM("medium"), LOW("low") }

/** A layer of a picture, as `LiveStream.qualities()` offers it: `HIGH`, `MEDIUM` or `LOW`, with its size. */
data class LiveStreamLayer(val quality: LiveStreamQuality, val width: Int, val height: Int)

/**
 * How a live stream plays here, as `LiveStream.stats()` answers: measured on this device once a second while the room is
 * joined, and sent nowhere. Rates come from two readings, so the first says 0. Times are in milliseconds.
 */
data class LiveStreamStats(
    val at: Long,
    val server: Server?,
    val video: Video?,
    val audio: Audio?,
    val transport: Transport?,
    val session: Session,
    val events: List<Event>,
) {
    /** The media server: the address joined, and the node and region that answered, where it says. */
    data class Server(val url: String?, val node: String?, val region: String?, val version: String?)

    /** The main picture as it arrives. `layer` is the layer arriving, by its height, null where the layers say no sizes. */
    data class Video(
        val codec: String?,
        val width: Int,
        val height: Int,
        val fps: Double,
        val kbps: Double,
        val layer: LiveStreamQuality?,
        val layers: List<LiveStreamLayer>,
        val lossPct: Double,
        val jitterMs: Double,
        /** How long frames wait to play, on average since the last reading. */
        val bufferMs: Double,
        val dropped: Int,
        val freezes: Int,
        val frozenMs: Double,
        val nacks: Int,
        val plis: Int,
        val decoder: String?,
        val hardware: Boolean?,
    )

    /** The sound as it arrives. `concealedPct`, the share of it made up for what didn't come, since the last reading. */
    data class Audio(
        val codec: String?,
        val kbps: Double,
        val lossPct: Double,
        val jitterMs: Double,
        val bufferMs: Double,
        val concealedPct: Double,
    )

    /** The way it comes: `udp` or `tcp`, and whether through a relay, over which protocol. */
    data class Transport(val protocol: String?, val relayed: Boolean?, val relayProtocol: String?, val rttMs: Double?)

    /** The session so far. `startupMs` is from the first join to the first frame, null until one came. */
    data class Session(
        val startupMs: Double? = null,
        val watchedMs: Double = 0.0,
        val stalls: Int = 0,
        val stalledMs: Double = 0.0,
        val reconnects: Int = 0,
        val rejoins: Int = 0,
        val errors: Int = 0,
    )

    /**
     * Something that happened: `joining`, `joined`, `first-frame`, `stalled`, `resumed`, `reconnecting`, `reconnected`,
     * `rejoined`, `quality`, `error`, `left`.
     */
    data class Event(val at: Long, val kind: String, val detail: String? = null)
}

/** A sender's layers, and which of them a viewer may choose. */
internal object LiveStreamPicture {
    /**
     * None above this on its shorter side may be chosen: Full HD comes only on `AUTO`, to a view that needs it, as the
     * protocol has it for the browser's player (CHOSEN_MAX_SIDE).
     */
    const val CHOSEN_MAX_SIDE = 720

    /** The layers of a picture this size, the best first. */
    fun layers(width: Int, height: Int, simulcast: Boolean): List<LiveStreamLayer> {
        if (!simulcast || width <= 0 || height <= 0) return listOf(LiveStreamLayer(LiveStreamQuality.HIGH, width, height))
        return listOf(
            LiveStreamLayer(LiveStreamQuality.HIGH, width, height),
            LiveStreamLayer(LiveStreamQuality.MEDIUM, width / 2, height / 2),
            LiveStreamLayer(LiveStreamQuality.LOW, width / 4, height / 4),
        )
    }

    /** Those a viewer may choose. A layer whose size the sender doesn't say (an encoder over WHIP) stays. */
    fun choosable(layers: List<LiveStreamLayer>): List<LiveStreamLayer> =
        layers.filter { it.height == 0 || min(it.width, it.height) <= CHOSEN_MAX_SIDE }

    /**
     * The layer that goes to a viewer who chose `choice`: that one, or where it is above the limit the largest below it.
     * Null on `AUTO`, and where the picture has no such layer.
     */
    fun chosen(choice: LiveStreamQuality, layers: List<LiveStreamLayer>): LiveStreamLayer? {
        if (choice == LiveStreamQuality.AUTO) return null
        val asked = layers.firstOrNull { it.quality == choice } ?: return null
        val allowed = choosable(layers)
        return if (asked in allowed) asked else allowed.firstOrNull() ?: asked
    }

    /** The layer a picture this tall is: the nearest by height. */
    fun layerAt(height: Int, layers: List<LiveStreamLayer>): LiveStreamQuality? {
        if (height <= 0) return null
        return layers.filter { it.height > 0 }.minByOrNull { abs(it.height - height) }?.quality
    }
}

/** One reading of a track's counters as the room client reports them. */
internal data class LiveStreamRtp(
    val bytes: Long = 0,
    val frames: Long = 0,
    val lost: Long = 0,
    val received: Long = 0,
    val bufferDelay: Double = 0.0,
    val emitted: Long = 0,
    val concealed: Long = 0,
    val samples: Long = 0,
    val freezes: Long = 0,
    val frozen: Double = 0.0,
    val nacks: Long = 0,
    val plis: Long = 0,
    val dropped: Long = 0,
    val width: Long = 0,
    val height: Long = 0,
    val fps: Double = 0.0,
    val jitter: Double = 0.0,
    val codec: String? = null,
    val decoder: String? = null,
    val hardware: Boolean? = null,
)

/** A track's counters as the room client reports them, in the names of WebRTC's stats. */
internal class LiveStreamReport(private val entries: List<Entry>) {
    class Entry(val id: String, val type: String, val members: Map<String, Any?>)

    private fun first(type: String) = entries.firstOrNull { it.type == type }

    private fun Map<String, Any?>.number(name: String): Number? = this[name] as? Number

    fun inbound(): LiveStreamRtp? {
        val m = first("inbound-rtp")?.members ?: return null
        val codecId = m["codecId"] as? String
        return LiveStreamRtp(
            bytes = m.number("bytesReceived")?.toLong() ?: 0,
            frames = m.number("framesDecoded")?.toLong() ?: 0,
            lost = m.number("packetsLost")?.toLong() ?: 0,
            received = m.number("packetsReceived")?.toLong() ?: 0,
            bufferDelay = m.number("jitterBufferDelay")?.toDouble() ?: 0.0,
            emitted = m.number("jitterBufferEmittedCount")?.toLong() ?: 0,
            concealed = m.number("concealedSamples")?.toLong() ?: 0,
            samples = m.number("totalSamplesReceived")?.toLong() ?: 0,
            freezes = m.number("freezeCount")?.toLong() ?: 0,
            frozen = m.number("totalFreezesDuration")?.toDouble() ?: 0.0,
            nacks = m.number("nackCount")?.toLong() ?: 0,
            plis = m.number("pliCount")?.toLong() ?: 0,
            dropped = m.number("framesDropped")?.toLong() ?: 0,
            width = m.number("frameWidth")?.toLong() ?: 0,
            height = m.number("frameHeight")?.toLong() ?: 0,
            fps = m.number("framesPerSecond")?.toDouble() ?: 0.0,
            jitter = m.number("jitter")?.toDouble() ?: 0.0,
            codec = entries.firstOrNull { it.type == "codec" && it.id == codecId }?.members?.get("mimeType") as? String,
            decoder = m["decoderImplementation"] as? String,
            hardware = m["powerEfficientDecoder"] as? Boolean,
        )
    }

    fun transport(): LiveStreamStats.Transport {
        val selected = first("transport")?.members?.get("selectedCandidatePairId") as? String
        val pair = entries.firstOrNull { it.type == "candidate-pair" && it.id == selected }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true }
        val localId = pair?.members?.get("localCandidateId") as? String
        val local = entries.firstOrNull { it.type == "local-candidate" && it.id == localId }?.members
        return LiveStreamStats.Transport(
            protocol = local?.get("protocol") as? String,
            relayed = (local?.get("candidateType") as? String)?.let { it == "relay" },
            relayProtocol = local?.get("relayProtocol") as? String,
            rttMs = pair?.members?.number("currentRoundTripTime")?.toDouble()?.let { it * 1000 },
        )
    }
}

/** The session's account, kept from what the stream tells it and one reading a second. Times in milliseconds. */
internal class LiveStreamPlayback {
    companion object {
        const val EVENTS_KEPT = 50

        /** A picture that shows no new frame this long while it should is stalled. */
        const val STALL_AFTER_MS = 2000L
    }

    var session = LiveStreamStats.Session()
        private set
    var events: List<LiveStreamStats.Event> = emptyList()
        private set
    var server: LiveStreamStats.Server? = null
    private var video: LiveStreamStats.Video? = null
    private var audio: LiveStreamStats.Audio? = null
    private var transport: LiveStreamStats.Transport? = null
    private var before: Triple<LiveStreamRtp, LiveStreamRtp, Long>? = null
    private var joiningAt: Long? = null
    private var joinedAt: Long? = null
    private var joinedOnce = false
    private var lastFrames = 0L
    private var lastFrameAt: Long? = null
    private var stalledSince: Long? = null

    fun note(kind: String, detail: String? = null, at: Long = System.currentTimeMillis()) {
        events = (events + LiveStreamStats.Event(at, kind, detail)).takeLast(EVENTS_KEPT)
    }

    fun joining(at: Long = System.currentTimeMillis()) {
        if (joiningAt == null) joiningAt = at
        note("joining", at = at)
    }

    fun joined(server: LiveStreamStats.Server?, at: Long = System.currentTimeMillis()) {
        this.server = server
        joinedAt = at
        lastFrames = 0
        lastFrameAt = at
        before = null
        if (joinedOnce) {
            session = session.copy(rejoins = session.rejoins + 1)
            note("rejoined", at = at)
        } else {
            note("joined", at = at)
        }
        joinedOnce = true
    }

    fun reconnecting(at: Long = System.currentTimeMillis()) {
        session = session.copy(reconnects = session.reconnects + 1)
        note("reconnecting", at = at)
    }

    fun reconnected(at: Long = System.currentTimeMillis()) = note("reconnected", at = at)

    fun failed(code: String, at: Long = System.currentTimeMillis()) {
        session = session.copy(errors = session.errors + 1)
        note("error", code, at)
    }

    fun left(at: Long = System.currentTimeMillis()) {
        settle(at)
        val since = joinedAt ?: return
        session = session.copy(watchedMs = session.watchedMs + (at - since))
        joinedAt = null
        note("left", at = at)
    }

    /**
     * One reading: the main picture and its sound, null for what isn't arriving. `pictured`, whether a picture should be
     * showing new frames now.
     */
    fun read(
        video: LiveStreamRtp?,
        audio: LiveStreamRtp?,
        layers: List<LiveStreamLayer>,
        transport: LiveStreamStats.Transport?,
        pictured: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val was = before
        val seconds = was?.let { max(at - it.third, 1L) / 1000.0 }
        this.video = video?.let { video(it, was?.first, seconds, layers) }
        this.audio = audio?.let { audio(it, was?.second, seconds) }
        this.transport = transport
        before = Triple(video ?: LiveStreamRtp(), audio ?: LiveStreamRtp(), at)
        watchFrames(video?.frames ?: 0, pictured, at)
    }

    private fun watchFrames(frames: Long, pictured: Boolean, at: Long) {
        val lastAt = lastFrameAt
        if (frames > lastFrames) {
            val joining = joiningAt
            if (lastFrames == 0L && session.startupMs == null && joining != null) {
                session = session.copy(startupMs = (at - joining).toDouble())
                note("first-frame", at = at)
            }
            lastFrames = frames
            lastFrameAt = at
            settle(at, resumed = true)
        } else if (!pictured) {
            lastFrameAt = at
            settle(at)
        } else if (lastFrames > 0 && stalledSince == null && lastAt != null && at - lastAt >= STALL_AFTER_MS) {
            stalledSince = lastAt
            session = session.copy(stalls = session.stalls + 1)
            note("stalled", at = at)
        }
    }

    private fun settle(at: Long, resumed: Boolean = false) {
        val since = stalledSince ?: return
        session = session.copy(stalledMs = session.stalledMs + (at - since))
        stalledSince = null
        if (resumed) note("resumed", at = at)
    }

    fun snapshot(at: Long = System.currentTimeMillis()): LiveStreamStats {
        var now = session
        joinedAt?.let { now = now.copy(watchedMs = now.watchedMs + (at - it)) }
        stalledSince?.let { now = now.copy(stalledMs = now.stalledMs + (at - it)) }
        return LiveStreamStats(at, server, video, audio, transport, now, events)
    }

    private fun kbps(bytes: Long, before: Long?, seconds: Double?): Double {
        if (before == null || seconds == null || bytes < before) return 0.0
        return (bytes - before) * 8 / seconds / 1000
    }

    /** The share lost since the last reading, as a percentage. */
    private fun loss(now: LiveStreamRtp, before: LiveStreamRtp?): Double {
        before ?: return 0.0
        val lost = max(0, now.lost - before.lost)
        val all = lost + max(0, now.received - before.received)
        return if (all > 0) lost.toDouble() / all * 100 else 0.0
    }

    private fun buffer(now: LiveStreamRtp, before: LiveStreamRtp?): Double {
        if (before == null || now.emitted <= before.emitted) return 0.0
        return (now.bufferDelay - before.bufferDelay) / (now.emitted - before.emitted) * 1000
    }

    private fun video(now: LiveStreamRtp, before: LiveStreamRtp?, seconds: Double?, layers: List<LiveStreamLayer>) = LiveStreamStats.Video(
        codec = now.codec,
        width = now.width.toInt(),
        height = now.height.toInt(),
        fps = now.fps,
        kbps = kbps(now.bytes, before?.bytes, seconds),
        layer = LiveStreamPicture.layerAt(now.height.toInt(), layers),
        layers = layers,
        lossPct = loss(now, before),
        jitterMs = now.jitter * 1000,
        bufferMs = buffer(now, before),
        dropped = now.dropped.toInt(),
        freezes = now.freezes.toInt(),
        frozenMs = now.frozen * 1000,
        nacks = now.nacks.toInt(),
        plis = now.plis.toInt(),
        decoder = now.decoder,
        hardware = now.hardware,
    )

    private fun audio(now: LiveStreamRtp, before: LiveStreamRtp?, seconds: Double?): LiveStreamStats.Audio {
        val concealedPct = if (before != null && now.samples > before.samples && now.concealed >= before.concealed) {
            (now.concealed - before.concealed).toDouble() / (now.samples - before.samples) * 100
        } else {
            0.0
        }
        return LiveStreamStats.Audio(
            codec = now.codec,
            kbps = kbps(now.bytes, before?.bytes, seconds),
            lossPct = loss(now, before),
            jitterMs = now.jitter * 1000,
            bufferMs = buffer(now, before),
            concealedPct = concealedPct,
        )
    }
}
