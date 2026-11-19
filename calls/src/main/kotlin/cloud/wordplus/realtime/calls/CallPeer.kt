package cloud.wordplus.realtime.calls

import android.os.Handler
import android.os.HandlerThread
import livekit.org.webrtc.CandidatePairChangeEvent
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import org.json.JSONObject
import kotlin.math.max

internal enum class CallConnection(val wire: String) {
    CONNECTING("connecting"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting"),
    CLOSED("closed")
}

internal enum class CallQuality(val wire: String) {
    UNKNOWN("unknown"),
    EXCELLENT("excellent"),
    GOOD("good"),
    POOR("poor"),
    LOST("lost")
}

/**
 * The cumulative packet counts this side has taken in and sent out, by kind,
 * and what the other side says it has sent — its RTCP sender reports,
 * `remote-outbound-rtp` — with the time (milliseconds since the epoch) the
 * last of those arrived. The last two are what tell a path that has died
 * from a peer that has stopped sending: both leave the inbound flat, only the
 * first leaves the peer's own count climbing.
 */
internal data class CallPeerCounters(
    var inAudio: Long = 0,
    var inVideo: Long = 0,
    var outAudio: Long = 0,
    var outVideo: Long = 0,
    var remoteAudio: Long = 0,
    var remoteVideo: Long = 0,
    var remoteAt: Double = 0.0
)

/**
 * The other side's counters as the relay handed them back with the reply to
 * this side's pulse: what it had taken in and sent out, its ICE state, and
 * when the relay took them (milliseconds since the epoch).
 */
internal data class CallPeerPulse(
    val at: Double,
    val ice: String,
    val inAudio: Long,
    val inVideo: Long,
    val outAudio: Long,
    val outVideo: Long,
    /**
     * When this side first saw it, by its own clock. A silence is judged on
     * this, never on [at]: that is the relay's clock, and a phone whose clock
     * ran behind the relay's counted the peer's pulses from before a silence
     * as sent during it.
     */
    val seenAt: Double = 0.0
) {
    companion object {
        fun from(value: JSONObject?): CallPeerPulse? {
            if (value == null || !value.has("at")) return null

            val inbound = value.optJSONObject("in")
            val outbound = value.optJSONObject("out")

            return CallPeerPulse(
                at = value.optDouble("at", 0.0),
                ice = value.optString("ice"),
                inAudio = inbound?.optLong("a", 0L) ?: 0L,
                inVideo = inbound?.optLong("v", 0L) ?: 0L,
                outAudio = outbound?.optLong("a", 0L) ?: 0L,
                outVideo = outbound?.optLong("v", 0L) ?: 0L
            )
        }
    }
}

internal data class CallPeerStats(
    var bytesSent: Long = 0,
    var bytesReceived: Long = 0,
    var rtt: Double? = null,
    var packetLoss: Double = 0.0,
    var quality: CallQuality = CallQuality.UNKNOWN,
    var candidateLocal: String? = null,
    var candidateRemote: String? = null,
    var candidateProtocol: String? = null,
    var restarts: Int = 0,
    var videoSend: String? = null,
    var videoRecv: String? = null,
    var audioSend: String? = null,
    var audioRecv: String? = null,
    // Which encoder and decoder are actually running the video. The codec
    // alone does not say whether it cost the CPU, and on this phone that is
    // the whole question: `OMX.qcom.video.encoder.avc` and `c2.android.*`
    // are the same H264 by name and nothing alike to run.
    var videoEncoder: String? = null,
    var videoDecoder: String? = null,
    var counters: CallPeerCounters = CallPeerCounters()
)

internal interface CallPeerListener {
    fun onPeerSend(body: JSONObject)
    fun onPeerTrack(source: CallSource, track: MediaStreamTrack?)
    fun onPeerConnection(state: CallConnection)
    fun onPeerStats(stats: CallPeerStats)
    fun onPeerFailed(reason: String)
    fun onPeerRefreshIceServers(done: (List<PeerConnection.IceServer>) -> Unit)
    fun onPeerCodecBroken(family: String, direction: String)

    /** The other side's capture of this kind has stopped while the path is
     *  fine: nothing arrives, and its own reports say it sent nothing. */
    fun onPeerSilent(source: CallSource)
}

/**
 * One direct connection to the other person.
 *
 * A port of the web's `peer.ts`, kept close to it on purpose: both ends of a
 * call have to agree about who offers, which section is which camera, when a
 * candidate is worth trying and how big a picture to send. Anywhere the two
 * could drift, this follows the web rather than what would read better here.
 *
 * The shape of it: the caller is **impolite** and offers first with a warm
 * section per source; the callee is **polite**, creates nothing of its own and
 * adopts the sections it is offered. An offer arriving while the impolite side
 * is making one is ignored, which is what keeps a glare from turning into two
 * half-negotiated connections.
 */
internal class CallPeer(
    val polite: Boolean,
    private val factory: PeerConnectionFactory,
    iceServers: List<PeerConnection.IceServer>,
    private var relayOnly: Boolean,
    private var caps: CallCodecCaps,
    private val forcedFamilies: List<String>?
) : PeerConnection.Observer {
    /**
     * How a direct path that has died is left, and how fast.
     *
     * The measure of 19 to 21 Sep 2026 was that every failure took the same
     * long road: three silences of eight seconds each, a plain ICE restart
     * after the first two that changed nothing, and only then the room — 44
     * to 46 seconds of dead call, every time, and a hand-off that still had
     * to fetch its token. Half of those silences were not a dead path at all
     * but a peer whose microphone had stopped, which no restart could mend.
     *
     * Now a silence is judged at [STALL_TIMEOUT_MS] by what the other side's
     * own RTCP sender reports say it sent meanwhile (`remote-outbound-rtp`,
     * arriving over the same transport), backed by the relay's pulse: the
     * peer sent and nothing arrived is a dead path, the peer sent nothing is
     * a dead capture. A dead capture gets a `reacquire` to the sender and no
     * restart.
     *
     * The room is for a call that cannot connect directly or through TURN at
     * all, and a call handed to it never comes back, so the path is given up
     * on only once TURN has had a fair trial: [MAX_RESTARTS] restarts, each
     * with fresh credentials and [RESTART_DEADLINE_MS] to bring the media
     * back. A dead path is restarted over the relay alone, since ICE still
     * thinks the path it has is fine; a lost connection first on every
     * candidate, which is what finds a new direct path after a move from
     * Wi-Fi to cellular, and then over the relay alone. libwebrtc says
     * `disconnected` a few seconds after its checks go unanswered, so the
     * grace on top is one second. Until 22 Sep 2026 each road had one attempt
     * of four or five seconds, and a network change of ten seconds on one
     * side put a call in the room for good.
     *
     * None of it runs while a restart could not be negotiated: this side's
     * socket down or answering nothing, or the relay having said the other
     * side's is gone. A deadline that runs out then proves nothing about the
     * path, so escalation waits, for up to [SIGNALING_HOLD_MAX_MS], and
     * starts over with a fresh budget once both sides can be reached — at
     * most [HOLD_REFRESHES] times until the connection has been healthy
     * again, so a socket that keeps stalling and coming back cannot hold a
     * dead call out of the room for ever. A hold that runs out is spent until
     * then, and the ladder runs as though there had been none. A connection
     * that has carried media for [HEALTHY_RESET_MS] since its last trouble
     * has its restarts back, so every incident in a long call gets the whole
     * trial.
     *
     * After a first connection both sides see the same trouble at about the
     * same moment, and the impolite one restarts first. The polite one waits
     * [POLITE_RESTART_DELAY_MS], and twice its own latest round trip to the
     * relay on top ([POLITE_RESTART_MAX_MS] in all), for that restart to
     * arrive, takes it as its own attempt when it does, and at an attempt's
     * deadline waits as long for the other side's next. It waited a second
     * and a half, so on a slow socket the two restarted into each other and
     * the polite side spent an attempt on an offer it then rolled back
     * (31 Oct 2026). The numbers are the web's.
     */
    companion object {
        private const val MAX_RESTARTS = 2

        // Refused answers rolled back and restarted over before an answer
        // applies again (`recoverRefusedAnswer`).
        private const val ANSWER_RECOVERIES = 2

        // A restart whose negotiation has not begun this long after
        // `restartIce()`, with nothing in the way, is offered by the peer
        // itself: until it is, every offer from the other side counts as a
        // collision (`restartPending`).
        private const val RESTART_PENDING_MAX_MS = 2_000L

        // How long an offer of the other side's counts as being answered, a
        // restart waiting for it (`requestIceRestart`), when libwebrtc never
        // says the answer failed.
        private const val ANSWERING_MAX_MS = 10_000L
        private const val RESTART_DEADLINE_MS = 8_000L
        private const val DISCONNECT_GRACE_MS = 1_000L
        private const val POLITE_RESTART_DELAY_MS = 3_000L
        private const val POLITE_RESTART_MAX_MS = 6_000L
        private const val POLITE_UNREACHABLE_DELAY_MS = 1_500L
        private const val HEALTHY_RESET_MS = 30_000L
        private const val SIGNALING_HOLD_MAX_MS = 60_000L
        private const val HOLD_REFRESHES = 2
        private const val RENEW_POLITE_DELAY_MS = 10_000L
        private const val STATS_INTERVAL_MS = 2_000L

        /** How often a stats pass reads whether RED leads the microphone's codecs. */
        private const val RED_READ_MS = 15_000L

        /** While the send codec could still be the wrong one — see [statsLoop]. */
        private const val STATS_SETTLING_MS = 300L
        private const val PRIVATE_HOLD_MS = 2_000L
        private const val STALL_TIMEOUT_MS = 4_000L

        /** How long the other side's RTCP is trusted to be current: a sender
         *  report older than this says nothing about now. */
        private const val REMOTE_REPORT_AGE_MS = 5_000L

        /** A pulse the relay hands back was read at the other side's stats
         *  tick, up to this long before this side first saw it (a pulse
         *  interval here, and the two trips): a sample counts as taken after
         *  a silence began only when it was first seen that much later. */
        private const val PULSE_LAG_MS = 2_500L
        /** What the peer must have sent during a silence for the silence to
         *  be the path's fault rather than its capture's. libwebrtc keeps
         *  probing the bandwidth with a trickle of padding on a video section
         *  that carries no picture — about a packet a second on an audio
         *  call, measured 21 Sep 2026 — and one such packet used to be "the
         *  peer kept sending", which made a stopped microphone read as a dead
         *  path. A live microphone is fifty packets a second. */
        private const val SILENCE_SENT_MIN = 25L

        /** How many samples of the peer's counts are kept for judging a silence. */
        private const val SAMPLE_HISTORY = 8

        /** How often the other side is told its capture has stopped. */
        private const val SILENT_NOTICE_MIN_MS = 30_000L

        /** How long a restart waits for fresh credentials before going with
         *  the ones it has: a restart is the quick way out and must not sit
         *  on a slow reply. */
        private const val ICE_SERVERS_BUDGET_MS = 2_000L

        /**
         * A negotiation that never completes has nothing to notice it: ICE
         * fails only once it has checked something, and the stall watchdog
         * only counts on a live connection. A path that is going to come up
         * does so inside a few seconds; one that has not by
         * [CONNECT_RELAY_AT_MS] is tried over the relay alone, with the
         * restarts a lost connection gets, and one that has not by
         * [CONNECT_DEADLINE_MS] — both of them run out — is given up for the
         * room. It was fourteen seconds, which left a relay allocation over
         * TCP on 443 through a slow network six to come up in.
         *
         * Both clocks start once this side holds both descriptions, not when
         * the peer is made: before that ICE has nothing to check. On a call
         * whose caller heard the relay five seconds late (ppl.travel, 30 Oct
         * 2026) the callee's eight seconds ran out a moment after its answer
         * went, and the two sides restarted over each other until the call
         * went to the room. A negotiation that never completes is given
         * [NEGOTIATE_DEADLINE_MS] from the peer's birth, and once the other
         * side has spoken, [NEGOTIATE_IDLE_MS] from the last word it said, up
         * to [NEGOTIATE_MAX_MS] from the birth: a side still at work on the
         * call is a slow one, not a gone one (31 Oct 2026, a
         * callee gave up on a caller whose offer came nine seconds after the
         * call had gone to the room). Before a first connection the impolite
         * side alone restarts when its clock runs out; either side restarts
         * when its own ICE fails, and a restart the other side made is this
         * side's attempt too.
         */
        private const val CONNECT_RELAY_AT_MS = 8_000L
        private const val CONNECT_DEADLINE_MS = CONNECT_RELAY_AT_MS + MAX_RESTARTS * RESTART_DEADLINE_MS + 2_000L
        private const val NEGOTIATE_DEADLINE_MS = CONNECT_DEADLINE_MS
        private const val NEGOTIATE_IDLE_MS = NEGOTIATE_DEADLINE_MS
        private const val NEGOTIATE_MAX_MS = 2 * NEGOTIATE_DEADLINE_MS

        /** The polite side's wait on a socket whose latest round trips took
         *  [roundTripMs] at the longest ([POLITE_RESTART_DELAY_MS]). */
        internal fun politeWait(roundTripMs: Long): Long =
            minOf(POLITE_RESTART_MAX_MS, POLITE_RESTART_DELAY_MS + 2 * maxOf(0L, roundTripMs))

        /** How much longer a negotiation that has not completed is waited
         *  for, its peer made [sinceBirthMs] ago and the other side last heard
         *  [sinceHeardMs] ago; null when it is given up. */
        internal fun negotiationGrace(sinceBirthMs: Long, sinceHeardMs: Long?): Long? {
            if (sinceHeardMs == null) return null

            val more = minOf(NEGOTIATE_IDLE_MS - sinceHeardMs, NEGOTIATE_MAX_MS - sinceBirthMs)

            return if (more > 250) more else null
        }

        /**
         * An attempt's deadline counts from its own answer, so a restart over
         * a slow trip gets its whole [RESTART_DEADLINE_MS] of checks; an
         * answer that has not come by [RESTART_DEADLINE_MS] + this is the
         * attempt failed.
         */
        private const val ANSWER_WAIT_MS = 8_000L

        /** A description's ICE username fragment (the first section's: the
         *  call is bundled), which an ICE restart changes. */
        internal fun iceUfrag(sdp: String): String? =
            sdp.lineSequence().map { it.trimEnd('\r') }.firstOrNull { it.startsWith("a=ice-ufrag:") }
                ?.removePrefix("a=ice-ufrag:")?.trim()?.ifEmpty { null }

        /**
         * When a negotiated codec is declared dead, and how fast.
         *
         * The receiving half is judged on assembled frames, not packets: a
         * frame is only counted once it is whole, and a working decoder turns
         * the first one it is given into a picture. Three of them with nothing
         * decoded is already conclusive, and waiting on packet counts only
         * bought seconds of black.
         *
         * libwebrtc also says it outright — it names the decoder it ended up
         * with, and a decoder that has given up on the stream is named as
         * such. That needs no grace at all.
         *
         * The sending half keeps a longer one: an encoder is entitled to a
         * moment before its first frame, and the camera it is fed from starts
         * in its own time.
         */
        private const val CODEC_DECODE_GRACE_MS = 1_200L
        private const val CODEC_DECODE_FRAMES = 3L
        private const val CODEC_ENCODE_GRACE_MS = 3_000L
        private const val CODEC_ENCODE_FRAMES = 20L

        /**
         * An encoder that encodes nothing because it has nothing to spend is
         * starved, not broken: libwebrtc holds every frame back from an
         * encoder whose target has fallen to zero — the path down, or its send
         * queue grown too long — and an encoder given a few kbps skips what it
         * cannot afford. A target under 30 kbps counts as nothing to spend.
         * Writing a starved encoder's codec off renegotiated a working call
         * onto a worse one over what was the path's problem (production call
         * 50038, 22 Sep 2026: `45c 0f 0p bandwidth 0k` on a relay with 2.7%
         * loss and 400 ms round trips, and H264 dropped). A skipped write-off
         * is written down at most every [STARVED_NOTE_EVERY_MS] a codec.
         */
        private const val ENCODER_STARVED_TARGET = 30_000L
        private const val STARVED_NOTE_EVERY_MS = 30_000L

        /** Whether an encoder is being starved rather than failing: it has been given next to nothing to
         *  spend, a target under [ENCODER_STARVED_TARGET]. A limitation of `bandwidth` is not that on
         *  its own: an encoder scaled down once keeps naming it, and one that died after that, with a
         *  target of hundreds of kbps, was never written off. */
        private fun encoderStarved(limit: String, target: Long?): Boolean {
            val spend = spendable(limit, target) ?: return false

            return spend < ENCODER_STARVED_TARGET
        }

        /** The target an encoder has to spend: the one it reports, or the zero a missing one stands
         *  for. libwebrtc leaves the target out of the report while it is zero, and an encoder cut off
         *  by a path that went down names no limitation, so a missing target counts as zero only beside
         *  a reported limitation; with neither there is nothing to judge but the frames. */
        private fun spendable(limit: String, target: Long?): Long? = target ?: if (limit != "?") 0L else null

        private fun encoderLimit(limit: String, target: Long?): String =
            "limited by $limit, target ${kbps(spendable(limit, target))}"

        private val DEAD_DECODER = Regex("null|fallback", RegexOption.IGNORE_CASE)

        /**
         * A video source the other side says is on, that this side has asked
         * to be sent, and that has decoded nothing for this long is asked for
         * again: the sender starts that source's encoder over, which puts a
         * keyframe on the wire. Only when that changes nothing does the
         * silence count as a stall and take the aggregate watchdog's way out.
         * Audio keeps the aggregate counters moving, so a dead picture beside
         * a working microphone was invisible for the whole call.
         */
        private const val VIDEO_STALL_TIMEOUT_MS = 6_000L

        /** How often a sender lets one of its sources be started over, and how long the encoding is held off when it is. */
        private const val REFRESH_MIN_INTERVAL_MS = 2_000L
        private const val REFRESH_HOLD_MS = 500L

        /** How long a sender keeps writing what a restarted source puts out. */
        private const val REFRESH_WATCH_MS = 12_000L

        /** Frames decoded of frames assembled, packets, and the keyframe and retransmission requests made of a stream. */
        private fun describe(stream: InboundVideo?): String =
            if (stream == null) "no stream" else "${stream.decoded}/${stream.received}f ${stream.packets}p pli${stream.pli} nack${stream.nack}"

        /** Frames the capture produced, frames encoded, packets sent, what the encoder says is holding it back, and the bitrate it is aiming at.
         *  A capture that has stopped and an encoder that turns out nothing read the same in every count but the first. */
        private fun describe(stream: OutboundVideo?): String =
            if (stream == null) "no stream" else "${stream.produced ?: "?"}c ${stream.encoded}f ${stream.packets}p ${stream.limit} ${kbps(spendable(stream.limit, stream.target))}"

        private fun kbps(bps: Long?): String = if (bps == null) "?" else "${bps / 1000}k"

        private fun seconds(ms: Long): String = "%.1f".format(ms / 1000.0)
    }

    /** Decoded-frame progress of one expected source, whether the sender has been asked already,
     *  and what the stream had delivered when it was asked. */
    private class VideoProgress(var frames: Long, var since: Long, var refreshed: Boolean, var receivedAtAsk: Long = 0, var packetsAtAsk: Long = 0)

    /** What one of the other side's video streams has delivered, and the family it runs on —
     *  for the write-off when frames keep arriving and none comes out. */
    private class InboundVideo(val decoded: Long, val received: Long, val packets: Long, val pli: Long, val nack: Long, val family: String?)

    /** What one of this side's video senders has put out, and what is limiting it: the reason it
     *  reports (`?` for none) and its target, null where the report leaves it out. */
    private class OutboundVideo(val produced: Long?, val encoded: Long, val packets: Long, val limit: String, val target: Long?, val family: String?)

    /** What a sender's video source had put out when it was asked to start over, so the frames the
     *  capture produces after the restart can be held against the frames the encoder turns them into. */
    private class RefreshWatch(val at: Long, val encoded: Long, val produced: Long?, val family: String?)

    /** A sender report of the other side's, as it arrived: what it had sent by then. */
    private class RemoteSample(val audio: Long, val video: Long, val reportAt: Double)

    var listener: CallPeerListener? = null

    private val thread = HandlerThread("calls-peer").apply { start() }
    private val handler = Handler(thread.looper)

    private var pc: PeerConnection? = null

    /**
     * Which sender belongs to which source, by id — not the transceiver.
     *
     * `PeerConnection.getTransceivers()` in libwebrtc's Java bindings disposes
     * every wrapper it handed out before and returns a fresh set. A
     * transceiver kept in a map here therefore stops working the moment
     * anything else reads that list, and touching one throws
     * `IllegalStateException: RtpTransceiver has been disposed` — on the
     * `calls-peer` thread, which takes the process with it.
     *
     * That is what killed the app a second or two into every direct call:
     * `onAddTrack` looks a receiver's mid up through the list, which disposed
     * the four warm transceivers, and the mid map of the next offer then read
     * a `mid` off one of them. A private call connected and the app died,
     * which is most of "private calls does not works".
     *
     * A sender id is a string and cannot be disposed. The transceiver itself
     * is fetched from the live list at the moment it is wanted and never held
     * past that — see [withTransceiver].
     */
    private val transceiverSenders = mutableMapOf<CallSource, String>()
    private val sourceOfMid = mutableMapOf<String, CallSource>()

    /** What the last stats line said, so the next one only repeats on news. */
    private var loggedIce: PeerConnection.IceConnectionState? = null
    private var loggedStatsAt = 0L
    private var remoteMids = mapOf<String, CallSource>()
    private val remoteTracks = mutableMapOf<CallSource, MediaStreamTrack>()
    private val localTracks = mutableMapOf<CallSource, MediaStreamTrack>()
    private val captureHeights = mutableMapOf<CallSource, Int>()

    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val heldPrivateCandidates = mutableListOf<IceCandidate>()
    private var privateHoldPending = false
    private val localPublicIps = mutableSetOf<String>()
    private val remotePublicIps = mutableSetOf<String>()
    private var privateDecision: String? = null

    private var makingOffer = false
    private var ignoreOffer = false

    // From this side's `restartIce()` until the offer it calls for is being
    // made (`requestIceRestart`): an offer from the other side meanwhile is a
    // collision, because libwebrtc answers it with the new credentials.
    private var restartPending = false
    private var restartPendingAt = 0L

    // An offer of the other side's being applied and answered, from when it
    // is taken until the answer is set: a restart asked for meanwhile waits
    // for the answer (`restartAfterAnswer`), which it would otherwise change.
    private var answeringSince = 0L
    private var restartAfterAnswer = false

    // Refused answers rolled back and restarted over since the last answer
    // that applied (`recoverRefusedAnswer`).
    private var answerRecoveries = 0
    private var restartCount = 0
    private var restartsTotal = 0
    private var restartWork: Runnable? = null
    private var closed = false
    private var started = false
    private var everConnected = false

    /** The servers the connection was built with, or the fresh ones a restart fetched. */
    private var iceServersHeld: List<PeerConnection.IceServer> = iceServers

    /**
     * The other side's own counts of what it sent, as a short history: its
     * RTCP sender reports as they arrive here, and its pulses as the relay
     * hands them back. A silence is judged on the samples taken after it
     * began — a count that arrives with a couple of seconds' lag still holds
     * what was sent before the silence, and read against a baseline it says
     * "the peer kept sending" about a microphone that had just stopped.
     */
    private val peerPulses = mutableListOf<CallPeerPulse>()
    private val remoteSamples = mutableListOf<RemoteSample>()
    private var silenceNoted = 0L

    /** When the connection last had trouble — a restart, a dead path — so its
     *  restarts can be given back once it has carried media for
     *  [HEALTHY_RESET_MS] since. */
    private var troubleAt = 0L

    /** Since when escalation has waited for a restart to be negotiable again,
     *  and why; [held] mirrors it for the engine's thread. */
    private var heldSince = 0L
    private var heldWhy = ""
    private var holdWork: Runnable? = null

    /** Whether this episode's hold ran its [SIGNALING_HOLD_MAX_MS] out. A
     *  spent hold is not taken again until both sides can be reached: the
     *  engine asks on every stats tick, and the next tick after the release
     *  used to hold again and cancel the restart the release had started. */
    private var holdSpent = false

    /** How many times a release has given the restarts back since the
     *  connection was last healthy ([HOLD_REFRESHES]), and when the last was. */
    private var holdRefreshCount = 0
    private var lastReleaseAt = 0L

    /** When the other side last said anything, of any kind ([heardFromPeer]). */
    private var peerHeardAt = 0L

    /** The latest round trips to the relay and back, the longest, as the
     *  engine last read it from the signaling ([setSignalRoundTrip]). */
    private var signalRoundTripMs = 0L

    /** What a polite wait for the other side's restart does when it ends, so
     *  a restart that arrives in it can end it early ([endRestartWait]). */
    private var restartWaitRun: (() -> Unit)? = null

    @Volatile
    private var heldFlag = false
    private val connectStartedAt = System.currentTimeMillis()

    /** When this side first held both descriptions (the connect clocks start
     *  then), and when it last did, which an attempt's deadline counts from. */
    private var negotiatedAt = 0L
    private var stableAt = 0L

    /** The call's own transport policy, which a restart that is not over the
     *  relay alone goes back to. */
    private val baseRelayOnly = relayOnly

    /** One restart in flight at a time: two each counted and each armed a
     *  deadline, and the second undid the first. */
    private var restarting = false
    private var restartDeadline: Runnable? = null
    private var lastOfferAt = 0L

    /** When the other side last offered new ICE credentials: a restart of its. */
    private var lastRestartOfferAt = 0L
    private val lastSilentNotice = mutableMapOf<CallSource, Long>()
    private var failed = false
    private val refreshWatch = mutableMapOf<CallSource, RefreshWatch>()
    private var lastOutbound = mapOf<CallSource, OutboundVideo>()
    private val micCeiling = CallMicCeiling()

    private val previousInbound = mutableMapOf<String, Pair<Long, Long>>()
    private val codecWatch = mutableMapOf<String, Long>()
    private val brokenCodecs = mutableSetOf<String>()
    private val starvedNotedAt = mutableMapOf<String, Long>()

    /** Whether this call has already been put back onto its ranked codec once. */
    private var codecCorrected = false
    private var inboundPackets = 0L
    private var inboundChangedAt = 0L
    private var connectedAt = 0L
    private var expectInbound = false

    /**
     * Which of the other side's video sources a picture is expected from:
     * announced on, and not paused by a wish of this side's own.
     */
    private var expectedVideo: Set<CallSource> = emptySet()

    /**
     * Which of the other side's sounds are on, for what a quality reading says
     * this side plays; and the readings, from the stats passes this peer makes
     * anyway ([CallQualityWatch]).
     */
    private var expectedAudio: Set<CallSource> = emptySet()
    private val quality = CallQualityWatch()
    private val videoProgress = mutableMapOf<CallSource, VideoProgress>()
    private val refreshedAt = mutableMapOf<CallSource, Long>()

    /** What this side last told the other it may receive, for the silence lines. */
    private var lastBweIn: Long? = null

    private val wanted = mutableMapOf<CallSource, Int>()
    private val shapedFrom = mutableMapOf<CallSource, Int>()
    private var peerCaps: CallCodecCaps? = null

    var connection: CallConnection = CallConnection.CONNECTING
        private set

    var stats = CallPeerStats()
        private set

    // Whether the pair in use goes through a relay on either side, from the
    // stats passes; null until one names a pair. It picks the camera's ceiling.
    private var relayed: Boolean? = null

    /**
     * A connection that never happens has nothing to notice it.
     *
     * Every other way out of a broken call runs through ICE: a restart, a
     * disconnection, a stall. All of them need the connection to have started
     * — ICE reaches FAILED only after it has checked something, and the stall
     * watchdog only counts once media is expected on a live connection. A
     * negotiation that never completes reaches none of that, so without this
     * the call sits on screen showing nothing, for as long as the person is
     * willing to look at it, and the server is never told to move it.
     *
     * A connection that has not come up in [CONNECT_RELAY_AT_MS] is tried
     * over the relay alone before it is given up on: a path that ICE cannot
     * find in eight seconds is not going to be found, and a relay allocation
     * takes one or two.
     */
    private val connectDeadline: Runnable = object : Runnable {
        override fun run() {
            if (closed || everConnected || heldSince != 0L) return

            // An attempt in flight is judged by its own deadline, which counts
            // from its answer: the restarts are few, so this ends.
            if (restarting || restartDeadline != null) {
                handler.postDelayed(this, RESTART_DEADLINE_MS)
                return
            }

            val now = System.currentTimeMillis()

            // No offer and answer yet, from a side that has spoken lately: it
            // is slow, not gone, and the room would wait for it as well.
            if (negotiatedAt == 0L && peerHeardAt != 0L) {
                val more = negotiationGrace(now - connectStartedAt, now - peerHeardAt)

                if (more != null) {
                    trace("the offer and answer were not exchanged in ${now - connectStartedAt}ms, but the other side spoke ${now - peerHeardAt}ms ago: waiting up to ${more}ms more")
                    handler.postDelayed(this, more)
                    return
                }
            }

            if (negotiatedAt != 0L) {
                fail("ice_failed", "no connection ${now - negotiatedAt}ms after the negotiation, ${now - connectStartedAt}ms after the peer was made")
            } else {
                fail("ice_failed", "the offer and answer were not exchanged in ${now - connectStartedAt}ms")
            }
        }
    }

    // A hold lets both of these go by: the ladder starts over when it lifts
    // ([releaseHold]). Started by [noteStable], once this side holds both
    // descriptions. The impolite side restarts; the polite one keeps every
    // candidate and answers it, and restarts when its own ICE fails.
    private val connectRelay = Runnable {
        if (closed || everConnected || heldSince != 0L || restarting || restartDeadline != null) return@Runnable

        if (polite) {
            trace("no connection ${CONNECT_RELAY_AT_MS}ms after the negotiation: the other side restarts over the relay alone, this side answers")
            return@Runnable
        }

        trace("no connection ${CONNECT_RELAY_AT_MS}ms after the negotiation, trying the relay alone")
        restart(force = true, why = "no connection", relay = true)
    }

    /** One line for the call's trace: never SDP, addresses or names. */
    private fun trace(line: String) {
        CallLog.write(line)
    }

    /**
     * The end of the direct path here, once. The reason goes to the trace
     * before the engine hears it, because the engine's hand-off tears this
     * down and the line would otherwise never be written.
     */
    private fun fail(reason: String, why: String) {
        if (closed || failed) return

        failed = true

        trace("giving the direct path up: $reason ($why)")

        listener?.onPeerFailed(reason)
    }

    /**
     * The relay's copy of the other side's latest counters, from the reply
     * to this side's pulse: a second witness beside the RTCP sender reports
     * for the question of whether the peer is sending.
     */
    fun setPeerPulse(pulse: CallPeerPulse?) {
        if (pulse == null) return

        handler.post {
            if (peerPulses.lastOrNull()?.at == pulse.at) return@post

            peerPulses.add(pulse.copy(seenAt = System.currentTimeMillis().toDouble()))

            while (peerPulses.size > SAMPLE_HISTORY) peerPulses.removeAt(0)
        }
    }

    /** This side's counters for the relay, as the last stats pass read them. */
    fun pulseBody(): JSONObject {
        val counters = stats.counters

        return JSONObject()
            .put("ice", iceName(pc?.iceConnectionState()))
            .put("in", JSONObject().put("a", counters.inAudio).put("v", counters.inVideo))
            .put("out", JSONObject().put("a", counters.outAudio).put("v", counters.outVideo))
    }

    /** Whether the connection is up, by ICE's own word. */
    val isUp: Boolean
        get() = isConnected()

    private fun iceName(state: PeerConnection.IceConnectionState?): String =
        state?.name?.lowercase() ?: "unknown"

    private val statsLoop = object : Runnable {
        override fun run() {
            if (closed) return

            collectStats()

            // Quickly while the send codec could still be wrong, then at the
            // rate the rest of this is for.
            //
            // The repair below reads the codec out of the statistics, so it
            // can only run when a sample does, and a sample every two seconds
            // meant a call spent its first two seconds sending the wrong one —
            // sometimes VP8 or VP9, which on a phone is a software encoder.
            // That settling is visible: the picture changes under you a couple
            // of seconds in, which is seen as the picture jumping.
            //
            // Fast until a video send codec has actually been seen, because
            // that is the point at which the repair has had its chance: it
            // either moved the call or decided it did not need to. Connected
            // is not the signal — that is true a second before any frame is
            // encoded, and polling would slow down again before it could help.
            // A call with no camera on never reports one, so it is settled
            // from the start rather than polling quickly for something that is
            // not coming.
            val settled = stats.videoSend != null || localTracks[CallSource.CAMERA] == null

            handler.postDelayed(this, if (settled) STATS_INTERVAL_MS else STATS_SETTLING_MS)
        }
    }

    init {
        pc = factory.createPeerConnection(configuration(iceServers), this)
    }

    /**
     * The connection's settings, the same at creation and at every restart:
     * the restart used to build its own and lose the relay-only policy on the
     * way.
     *
     * The polite side gives way in a glare by rolling its own offer back and
     * taking the other side's. A browser does that by itself; libwebrtc only
     * does it when told, and left untold it refused the incoming offer with a
     * wrong-state error — after a restart this side had begun, a codec written
     * off, anything that made it offer — and the call sat through three
     * restart deadlines before it moved to a room. Harmless on the impolite
     * side, which ignores a colliding offer before it gets this far.
     */
    private fun configuration(servers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            enableImplicitRollback = true
        }

    /**
     * Nothing starts until the owner has finished wiring itself up.
     *
     * Warm transceivers make libwebrtc ask for a negotiation of its own
     * accord, and the offer that answer produces is handed to `listener` —
     * which the caller of a constructor cannot have set yet, because the
     * constructor has not returned. The offer was made, the local description
     * applied, and the description given to nobody, so the other side sat
     * waiting for an offer that had already happened and the call fell back
     * to a room twenty seconds later.
     */
    fun start() {
        if (started || closed) return

        started = true

        // On the peer's own thread, not the engine's, so that making the
        // sections and ordering them is one thing rather than two.
        //
        // `addTransceiver` fires `onRenegotiationNeeded`, which posts
        // `negotiate()` here. Run from the engine's thread, that offer was
        // built and set while this thread was still inside the creation loop —
        // before the ordering pass below it had a chance to run. The offer went
        // out in libwebrtc's own order, the peer answered its first entry, and
        // the call started on whatever that happened to be: VP8 against an
        // iPhone, VP9 against another, H264 against Safari. Two of those three
        // are software encoders on a phone.
        //
        // Posted, the creation and the ordering are a single task on this
        // queue, and every `negotiate()` the creation triggers lands behind it.
        if (!polite) handler.post { addWarmTransceivers() }

        handler.postDelayed(statsLoop, STATS_SETTLING_MS)

        // The connect clocks start with the negotiation ([noteStable]); until
        // then only a negotiation that never completes is given up.
        handler.postDelayed(connectDeadline, NEGOTIATE_DEADLINE_MS)
    }


    /**
     * The caller's sections, made before anything is negotiated so the offer
     * carries all four whether or not a camera is on yet. The callee makes
     * none: libwebrtc only matches offered sections against transceivers it
     * created for sending, so a set made here would be answered with four
     * fresh receive-only ones and offered back — eight sections a side.
     */
    private fun addWarmTransceivers() {
        val connection = pc ?: return

        for (source in CallSource.ORDER) {
            val encoding = RtpParameters.Encoding(null, true, 1.0).apply {
                maxBitrateBps = if (source.isVideo) {
                    if (source == CallSource.SCREEN_SHARE) CallShapes.screenCeiling(1080) else CallShapes.CAMERA_RELAY_MAX_BITRATE
                } else {
                    CallShapes.audioCeiling(source)
                }
            }

            val init = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                emptyList(),
                listOf(encoding)
            )

            val type = if (source.isVideo) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            } else {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            }

            val transceiver = connection.addTransceiver(type, init) ?: continue

            transceiver.sender?.id()?.let { transceiverSenders[source] = it }

            transceiver.mid?.takeIf { it.isNotEmpty() }?.let { sourceOfMid[it] = source }

        }

        // The order goes on afterwards, in its own pass, each section through
        // a fresh lookup.
        //
        // Not during the loop above, for two reasons that cost a whole
        // afternoon between them. Using the wrapper `addTransceiver` returned
        // throws `IllegalStateException: RtpTransceiver has been disposed` on
        // the camera section of every call this side placed — the rule at the
        // top of this file applies here too. And looking one up instead reads
        // the transceiver list, which disposes the wrappers the loop is still
        // adding to, so the camera section was skipped silently instead.
        //
        // Either way that section kept libwebrtc's own order, which starts at
        // VP8: the peer answers with the first thing offered, and a pair that
        // does H265 in hardware both ways ran the camera on VP8 in software.
        for (source in CallSource.ORDER.filter { it.isVideo }) {
            if (transceiverSenders[source] == null) continue

            withTransceiver(source) { live ->
                CallCodecs.apply(
                    CallCodecs.offerOrder(caps, source == CallSource.SCREEN_SHARE, forcedFamilies),
                    live,
                    factory
                )
            }
        }

        // The microphone offers redundant audio first (CallCodecs.applyRed).
        if (transceiverSenders[CallSource.MICROPHONE] != null) {
            withTransceiver(CallSource.MICROPHONE) { live ->
                if (CallCodecs.applyRed(live, factory)) trace("microphone offers RED first")
            }
        }

        // Anything this side already had goes on its section now.
        //
        // `setLocalTrack` attaches through `withTransceiver`, so a track handed
        // over before these existed found nothing to attach to and was left in
        // `localTracks` alone — stored, never sent. The microphone is the one
        // that shows: a call placed from this phone reached the other side with
        // the control reading "on" and no sound at all, and came right only
        // when the microphone was toggled, because that calls `setLocalTrack`
        // again on a section that exists by then.
        //
        // The side that answers has never had the fault — `adoptTransceivers`
        // does this on the sections it adopts — so it was the caller's alone.
        for (source in CallSource.ORDER) {
            val track = localTracks[source] ?: continue

            withTransceiver(source) { live ->
                if (live.sender.track() == null) {
                    live.sender.setTrack(track, false)

                    CallLog.write("warm section ${source.wire} took the track it was already holding")
                }
            }
        }
    }

    // What this side sends

    fun setLocalTrack(source: CallSource, track: MediaStreamTrack?, captureHeight: Int) {
        handler.post {
            if (closed) return@post

            if (track != null) {
                localTracks[source] = track
                captureHeights[source] = captureHeight

                // A wish of zero was the other side saying "I am not drawing
                // this" — true until this moment, because nothing was being
                // sent. Kept, it would switch the sender off the instant the
                // track arrived, and the other side would never see a picture
                // to raise the wish for. It goes, and their next wish shapes
                // the sender properly. iOS has had this since the screen share.
                if (wanted[source] == 0) wanted.remove(source)
            } else {
                localTracks.remove(source)
                captureHeights.remove(source)
            }

            var attached = false

            withTransceiver(source) {
                it.sender.setTrack(track, false)
                attached = true
            }

            CallLog.write(
                "local track ${source.wire} track=${track != null} " +
                    "sender=${transceiverSenders[source] != null} attached=$attached"
            )

            // Shaped whether the track came or went, audio as well as video: a
            // track taken away makes the encoding inactive for the pause, and
            // its coming back is then a change libwebrtc acts on.
            applyWanted(source)
        }
    }

    /**
     * The other side says how big it draws each of our video sources, in
     * device pixels; 0 means it is not showing it at all.
     */
    fun setWanted(view: Map<String, Int>) {
        handler.post {
            if (closed) return@post

            for (source in listOf(CallSource.CAMERA, CallSource.SCREEN_SHARE)) {
                val raw = view[source.wire] ?: continue
                val height = max(0, raw)

                if (wanted[source] == height) continue

                wanted[source] = height
                applyWanted(source)
            }
        }
    }

    /**
     * What the other side can encode and decode. Every video sender picks its
     * codec from that and this side's own abilities.
     */
    fun setPeerCaps(value: CallCodecCaps) {
        handler.post {
            if (closed) return@post

            peerCaps = value

            CallSource.ORDER.filter { it.isVideo }.forEach { applyWanted(it) }
        }
    }

    /**
     * This side's own abilities, narrowed because a codec was caught carrying
     * nothing. Every video section is ordered again without it and the call is
     * offered again: a sender takes its codec from the order in the
     * description it was given, and neither this side nor a browser peer can
     * be moved any other way once a call is running. A peer that *can* switch
     * a live sender has already been told by the caps that went out with this.
     */
    fun setOwnCaps(value: CallCodecCaps) {
        handler.post {
            if (closed) return@post

            caps = value

            CallSource.ORDER.filter { it.isVideo }.forEach { source ->
                withTransceiver(source) { transceiver ->
                    val order = if (polite) {
                        CallCodecs.answerOrder(caps, peerCaps, source == CallSource.SCREEN_SHARE, forcedFamilies)
                    } else {
                        CallCodecs.offerOrder(caps, source == CallSource.SCREEN_SHARE, forcedFamilies)
                    }

                    CallCodecs.apply(order, transceiver, factory)
                }

                applyWanted(source)
            }

            negotiate()
        }
    }

    /**
     * Whether media is expected from the other side right now. The stall
     * watchdog must not fire on a silence this side asked for.
     */
    fun setExpectInbound(expected: Boolean) {
        handler.post {
            if (expectInbound != expected) {
                inboundChangedAt = System.currentTimeMillis()
                trace("expect inbound=$expected")
            }

            expectInbound = expected
        }
    }

    /**
     * Which video sources a picture is expected from right now. A source that
     * stops being expected drops its clock, and one that starts being expected
     * starts it from now, so a camera just turned on gets the whole grace
     * before it is asked for again.
     */
    fun setExpectedVideo(sources: Set<CallSource>) {
        handler.post {
            videoProgress.keys.retainAll(sources)

            expectedVideo = sources
        }
    }

    /** Which of the other side's sounds are on, for the quality readings. */
    fun setExpectedAudio(sources: Set<CallSource>) {
        handler.post { expectedAudio = sources }
    }

    /**
     * What changed here since the last quality reading, with the device's own
     * ([CallHealthWatch]); null before the connection has had two stats
     * passes. The watch holds its lock against the pass that feeds it.
     */
    fun qualityReading(health: JSONObject?): JSONObject? = quality.reading(health)

    /**
     * Whether redundant audio (RED, RFC 2198) leads the microphone's codecs
     * each way. What a sender sends is the first of its negotiated codecs;
     * the stats name a RED stream after the Opus inside it, so the parameters
     * are what say.
     */
    fun redState(): JSONObject? = red

    /** The last word on RED, read on the peer's thread now and then (see [readStats]). */
    @Volatile
    private var red: JSONObject? = null
    private var redReadAt = 0L

    private fun readRed() {
        withTransceiver(CallSource.MICROPHONE) { transceiver ->
            val leads = { codecs: List<RtpParameters.Codec>? -> codecs?.firstOrNull()?.name.equals("red", ignoreCase = true) }

            red = try {
                JSONObject().put("send", leads(transceiver.sender.parameters.codecs)).put("recv", leads(transceiver.receiver.parameters.codecs))
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Which sources a quality reading speaks of at this stats pass: this
     * side's that are live, switched on and not paused for the other side
     * drawing none of them, and the other side's that are on and shown here;
     * each stats entry's source as the pass itself finds it.
     */
    private fun qualityScope(entries: Map<String, RTCStats>): CallQualityScope {
        val sending = CallSource.ORDER.filter { source ->
            val track = localTracks[source]

            track != null && track.enabled() && track.state() == MediaStreamTrack.State.LIVE && !(source.isVideo && wanted[source] == 0)
        }.toSet()
        val mids = sourceOfMid.toMap()
        val local = localTracks.toMap()
        val remote = remoteTracks.toMap()

        return CallQualityScope(
            sending = sending,
            playing = expectedVideo + expectedAudio,
            outbound = { values ->
                val trackId = (values["mediaSourceId"] as? String)?.let { entries[it]?.members?.get("trackIdentifier") as? String }

                mids[values["mid"] as? String ?: ""] ?: local.entries.firstOrNull { trackId != null && it.value.id() == trackId }?.key
            },
            inbound = { values ->
                val trackId = values["trackIdentifier"] as? String

                mids[values["mid"] as? String ?: ""] ?: remote.entries.firstOrNull { trackId != null && it.value.id() == trackId }?.key
            }
        )
    }

    private fun isConnected(): Boolean {
        val state = pc?.iceConnectionState() ?: return false

        return state == PeerConnection.IceConnectionState.CONNECTED ||
            state == PeerConnection.IceConnectionState.COMPLETED
    }

    private fun negotiated(): Boolean {
        val connection = pc ?: return false

        return connection.localDescription != null && connection.remoteDescription != null
    }

    /**
     * The microphone's ceiling on a slow upload ([CallMicCeiling]), from this tick's estimate and whether any video went
     * out since the last.
     */
    private fun reviewMicCeiling(now: Long, estimate: Long?, outbound: Map<CallSource, OutboundVideo>) {
        val videoSending = outbound.any { (source, stream) -> stream.encoded > (lastOutbound[source]?.encoded ?: 0L) }

        if (!micCeiling.review(now, estimate, videoSending)) return

        trace("microphone ceiling ${micCeiling.bitrate / 1000}k: bwe out=${kbps(estimate)}${if (videoSending) "" else ", no video going out"}")
        applyWanted(CallSource.MICROPHONE)
    }

    private fun applyWanted(source: CallSource) {
        if (closed || !negotiated()) return

        withTransceiver(source) { transceiver ->
            val sender = transceiver.sender
            val params = sender.parameters ?: return@withTransceiver
            val encoding = params.encodings.firstOrNull() ?: return@withTransceiver

            if (!source.isVideo) {
                // An audio source with no track is an inactive encoding too:
                // it sends nothing either way, but an active one still weighs
                // on the sender's bandwidth. Measured 4 Nov 2026 on the
                // browsers' engine, Chrome 156 through a shaped upload: the
                // shared screen's empty sender held a slow upload's estimate
                // at 262 kbps and the camera's target at 30 (realtime-client,
                // calls/README.md).
                val ceiling = if (source == CallSource.MICROPHONE) micCeiling.bitrate else CallShapes.audioCeiling(source)
                val hasTrack = sender.track() != null

                if (encoding.maxBitrateBps == ceiling && encoding.active == hasTrack) return@withTransceiver

                encoding.maxBitrateBps = ceiling
                encoding.active = hasTrack
                sender.parameters = params

                return@withTransceiver
            }

            val capture = captureHeights[source] ?: CallShapes.DEFAULT_TRACK_HEIGHT
            val family = chosenFamily(source, params)
            val fast = family?.let { CallCodecs.fastDirection(caps, peerCaps, it) } ?: false

            val shape = if (source == CallSource.SCREEN_SHARE) {
                CallShapes.screen(wanted[source], capture, fast)
            } else {
                CallShapes.camera(
                    wanted[source], capture,
                    if (relayed == false) CallShapes.CAMERA_DIRECT_MAX_BITRATE else CallShapes.CAMERA_RELAY_MAX_BITRATE
                )
            }

            // A source with no track is an inactive encoding, not an idle
            // active one. The pause is then a real allocation change held for
            // as long as the camera is off, and the camera coming back is
            // another one — which is what makes libwebrtc probe for bandwidth
            // again. Measured 16 Sep 2026, iPhone → Firefox: a camera switched
            // off and on again sent not one packet for thirty seconds and
            // through two encoder restarts, because the estimate had fallen to
            // what audio alone justifies and nothing it did changed its
            // allocation; the one write that changed it brought the picture
            // back at once.
            val active = shape.active && sender.track() != null

            // Nothing to say means nothing to write.
            //
            // Six paths call this and several land inside the first second of
            // a call — a track attaching, caps arriving from either side, the
            // answer completing, a track being added. Each wrote the sender's
            // parameters whether or not they had changed, and a write that
            // touches the scale or the frame rate can make libwebrtc rebuild
            // the encoder. On the device that read as seven encoder creations
            // in 750 ms, flipping between the qcom HEVC and AVC encoders while
            // the description said H265 throughout — and the codec the call
            // kept was wherever the flipping stopped. The audio path above has
            // always had this guard; the video path never did.
            val same = encoding.active == active &&
                encoding.maxBitrateBps == shape.maxBitrate &&
                encoding.scaleResolutionDownBy == shape.scale &&
                encoding.maxFramerate == shape.maxFramerate &&
                shapedFrom[source] == capture

            if (same) return@withTransceiver

            encoding.active = active
            encoding.maxBitrateBps = shape.maxBitrate
            encoding.scaleResolutionDownBy = shape.scale
            encoding.maxFramerate = shape.maxFramerate

            // Text must stay readable when the link is short: the screen
            // encoder gives up frames before it gives up pixels. The camera
            // keeps libwebrtc's own balance.
            if (source == CallSource.SCREEN_SHARE) {
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }

            // The scale is relative to the capture, so the size it was worked
            // out from is remembered: a shared window the other person resizes
            // changes the capture underneath, and nothing else would notice.
            shapedFrom[source] = capture

            sender.parameters = params
        }
    }

    /**
     * The family this side should send this source with, among the ones the
     * negotiation actually left available.
     */
    private fun chosenFamily(source: CallSource, params: RtpParameters): String? {
        val negotiated = params.codecs.mapNotNull { codec ->
            CallCodecs.FAMILIES.firstOrNull { it.equals("video/" + codec.name, ignoreCase = true) }
        }

        if (negotiated.isEmpty()) return null

        val order = CallCodecs.answerOrder(caps, peerCaps, source == CallSource.SCREEN_SHARE, forcedFamilies)

        return order.firstOrNull { family -> negotiated.any { it.equals(family, ignoreCase = true) } }
    }

    // Negotiation

    /**
     * Run [block] on the transceiver a source is carried by, fetched fresh.
     *
     * Fetched, used, dropped — never stored. Reading the list is also what
     * disposes anything read from it before, so nothing may outlive the call.
     */
    /**
     * What each side says H265 is, in the only place that decides it.
     *
     * A codec order can be applied and then contradicted by the call — the
     * phone put H265 first in its camera section and sent H264 to every Apple
     * peer anyway — which means the family is lost in negotiation rather than
     * in the ranking. The `a=fmtp` line is where that would happen: two HEVC
     * implementations that disagree on profile, tier or level advertise
     * payload types the other will not match.
     *
     * Only the codec lines, never the description: an SDP carries ICE
     * credentials and those belong in no log.
     */
    /**
     * Put the camera back on the codec the ranking chose, if it did not land
     * there.
     *
     * Both sides agree on the ranking, both put the same family first in their
     * sections, and the call still comes up on a different one some of the
     * time — measured against Safari over a LAN: the same pair, the same
     * build, consecutive calls, H265 on most and H264 or VP9 on the rest, with
     * the offer, the answer and the section mapping identical in every logged
     * respect. Six explanations were tried against that and none held.
     *
     * So this does not try to name the race. It states the invariant the call
     * is supposed to satisfy — what is being sent is what the ranking asked
     * for — and repairs the call when it does not, which is what makes the
     * result predictable whichever way the race went.
     *
     * Once per call, and only ever towards a family both sides advertise, so a
     * peer that genuinely cannot take the ranked codec is left alone and the
     * downgrade path stays in charge of codecs that carry nothing. A second
     * disagreement means the ranking is wrong rather than the negotiation, and
     * renegotiating again would only churn the call.
     */
    private fun correctSendCodec(current: CallPeerStats) {
        if (codecCorrected || closed || !isConnected()) return

        val sending = current.videoSend ?: return
        val peer = peerCaps ?: return

        val wanted = CallCodecs.answerOrder(caps, peer, false, forcedFamilies).firstOrNull() ?: return
        val short = wanted.removePrefix("video/")

        if (short.equals(sending, ignoreCase = true)) return

        // Only when the peer says it can take it. Without this the call would
        // be renegotiated towards something the far end never offered.
        if (peer.families[wanted]?.second?.ok != true) return

        codecCorrected = true

        CallLog.write("send codec is $sending but the ranking chose $short: ordering the sections again")

        CallSource.ORDER.filter { it.isVideo }.forEach { source ->
            withTransceiver(source) { transceiver ->
                CallCodecs.apply(
                    CallCodecs.answerOrder(caps, peerCaps, source == CallSource.SCREEN_SHARE, forcedFamilies),
                    transceiver,
                    factory
                )
            }
        }

        negotiate()
    }

    private fun logH265(label: String, sdp: String?) {
        if (sdp == null) return

        val payloads = Regex("""a=rtpmap:(\d+) H265/90000""", RegexOption.IGNORE_CASE)
            .findAll(sdp)
            .map { it.groupValues[1] }
            .toList()

        if (payloads.isEmpty()) {
            CallLog.write("h265 $label: absent")

            return
        }

        val described = payloads.joinToString(" ") { pt ->
            val fmtp = Regex("""a=fmtp:$pt ([^\r\n]*)""").find(sdp)?.groupValues?.get(1) ?: "(no fmtp)"

            "pt$pt $fmtp"
        }

        CallLog.write("h265 $label: $described")

        // Which codec a side sends is not decided by the payload types being
        // present but by their order on the receiver's own `m=` line, and that
        // order is the one thing these logs never carried. Named rather than
        // numbered, because a payload number means nothing on its own.
        val names = Regex("""a=rtpmap:(\d+) ([A-Za-z0-9-]+)/90000""")
            .findAll(sdp)
            .associate { it.groupValues[1] to it.groupValues[2] }

        Regex("""^m=video \d+ [^ ]+ ([0-9 ]+)""", RegexOption.MULTILINE)
            .findAll(sdp)
            .forEachIndexed { index, line ->
                val order = line.groupValues[1].trim().split(" ").mapNotNull { names[it] }.distinct()

                CallLog.write("video m-line $index $label: " + order.joinToString(","))
            }
    }

    private fun withTransceiver(source: CallSource, block: (RtpTransceiver) -> Unit) {
        val id = transceiverSenders[source] ?: return
        val connection = pc ?: return

        val transceiver = try {
            connection.transceivers.firstOrNull { it.sender?.id() == id }
        } catch (e: Exception) {
            null
        } ?: run {
            // A section this side believes it has, that the connection does
            // not list. Silence here is how the camera's codec order went
            // missing for a whole afternoon while the log looked clean.
            CallLog.write("no transceiver for ${source.wire}: sender $id is not in the connection's list")

            return
        }

        try {
            block(transceiver)
        } catch (e: IllegalStateException) {
            // Disposed between the lookup and the use. There is nothing to do
            // about it and it is not worth a call for.
        }
    }

    /**
     * Learn the mids the last description gave this side's sections.
     *
     * A section has no mid until it has been negotiated, so the map the offer
     * carries can only be built after `setLocalDescription`. One read of the
     * live list, and what it says is kept as strings.
     */
    private fun refreshMids() {
        val connection = pc ?: return
        val bySender = transceiverSenders.entries.associate { (source, id) -> id to source }

        try {
            for (transceiver in connection.transceivers) {
                val id = transceiver.sender?.id() ?: continue
                val source = bySender[id] ?: continue
                val mid = transceiver.mid?.takeIf { it.isNotEmpty() } ?: continue

                sourceOfMid[mid] = source
            }
        } catch (e: Exception) {
            // A connection on its way out. The map keeps what it had.
        }
    }

    /** Which of this side's sections is which, for the other side to read. */
    private fun midMap(): JSONObject {
        val map = JSONObject()

        sourceOfMid.forEach { (mid, source) -> map.put(mid, source.wire) }

        return map
    }

    private fun negotiate() {
        val connection = pc ?: return

        if (closed) return

        // The polite side never opens: it answers what it is offered.
        if (polite && connection.remoteDescription == null) {
            restartPending = false
            return
        }

        makingOffer = true
        // This offer is the one a pending restart called for: libwebrtc puts
        // the new credentials in it.
        restartPending = false

        connection.createOffer(object : CallSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : CallSdpObserver() {
                    override fun onSetSuccess() {
                        handler.post {
                            makingOffer = false

                            if (closed) return@post

                            logH265("local " + description.type, description.description)
                            logH265("remote", connection.remoteDescription?.description)

                            // The sections have their mids now, and not
                            // before: this is what the map the offer carries
                            // is built from.
                            refreshMids()

                            // Only the offer this set, while it is still the
                            // one on the table. An offer from the other side
                            // can be handled before this post runs, and roll
                            // this one back: what `localDescription` then held
                            // was the last answer, with the old ICE
                            // credentials, and it went out as though it were
                            // this side's word on the restart.
                            if (connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return@post

                            connection.localDescription?.takeIf { it.type == SessionDescription.Type.OFFER }?.let { sendDescription(it) }
                        }
                    }

                    override fun onSetFailure(error: String?) {
                        handler.post { makingOffer = false }
                    }
                }, description)
            }

            override fun onCreateFailure(error: String?) {
                handler.post { makingOffer = false }
            }
        }, MediaConstraints())
    }

    private fun sendDescription(description: SessionDescription) {
        listener?.onPeerSend(
            JSONObject()
                .put("kind", "sdp")
                .put(
                    "description",
                    JSONObject()
                        .put("type", description.type.canonicalForm())
                        .put("sdp", description.description)
                )
                .put("mids", midMap())
        )
    }

    /**
     * Signals are handled one at a time and in order: negotiation depends on
     * it, and a candidate applied before its description is a candidate lost.
     */
    fun handleSignal(body: JSONObject) {
        handler.post { processSignal(body) }
    }

    /** Told by the engine of every signal the other side sends this peer,
     *  whatever its kind. */
    fun heardFromPeer() {
        val at = System.currentTimeMillis()

        handler.post { peerHeardAt = at }
    }

    /** The longest of the latest round trips to the relay and back, told by
     *  the engine on every stats tick. */
    fun setSignalRoundTrip(ms: Long) {
        handler.post { signalRoundTripMs = maxOf(0L, ms) }
    }

    /** How long the polite side gives the other side's restart to arrive
     *  before it makes its own ([POLITE_RESTART_DELAY_MS]). With the hold
     *  spent, one side cannot be reached and no restart of the other side's
     *  can arrive: the moment it always had, for a glare alone. */
    private fun politeWait(): Long = if (holdSpent) POLITE_UNREACHABLE_DELAY_MS else politeWait(signalRoundTripMs)

    private fun processSignal(body: JSONObject) {
        val connection = pc ?: return

        if (closed) return

        when (body.optString("kind")) {
            "sdp" -> {
                val description = body.optJSONObject("description") ?: return
                val sdp = description.optString("sdp")
                val typeName = description.optString("type")

                if (sdp.isEmpty()) return

                val type = SessionDescription.Type.fromCanonicalForm(typeName)
                // A restart of this side's still waiting for its offer counts
                // as an offer of its own: libwebrtc answers with new ICE
                // credentials whenever a restart is pending, and Firefox
                // refuses such an answer to an offer that did not restart ICE,
                // left holding its own offer with the call's media stopped.
                // The impolite side's restart goes first, and the polite
                // side's offer is made again after it.
                val collision = type == SessionDescription.Type.OFFER &&
                    (makingOffer || connection.signalingState() != PeerConnection.SignalingState.STABLE || restartPending)

                ignoreOffer = !polite && collision

                if (ignoreOffer) return

                if (type == SessionDescription.Type.OFFER) answeringSince = System.currentTimeMillis()

                body.optJSONObject("mids")?.let { applyRemoteMids(it) }

                // An offer over a description already held, with ICE
                // credentials of its own, is the other side's ICE restart.
                val heldUfrag = connection.remoteDescription?.description?.let { iceUfrag(it) }
                val restarted = type == SessionDescription.Type.OFFER && heldUfrag != null && iceUfrag(sdp) != heldUfrag

                if (restarted) lastRestartOfferAt = System.currentTimeMillis()

                // Lines for payload types the description does not declare
                // go first, whatever else happens to it: libwebrtc refuses a
                // description that carries them (see the helper).
                val cleaned = CallSdp.withoutOrphanPayloads(sdp)

                if (cleaned != sdp) {
                    trace("the $typeName arrived with lines for payload types it does not declare, dropped before applying it")
                }

                val rewritten = CallSdp.withStereoOpus(cleaned)

                logH265("remote " + type, sdp)

                connection.setRemoteDescription(object : CallSdpObserver() {
                    override fun onSetSuccess() {
                        handler.post {
                            logH265("local after " + type, connection.localDescription?.description)
                            afterRemoteDescription(type, restarted)
                        }
                    }

                    override fun onSetFailure(error: String?) {
                        // A description this side rewrote for stereo must never
                        // cost the call: what libwebrtc will not take is retried
                        // as it arrived, less the lines it cannot take at all.
                        connection.setRemoteDescription(object : CallSdpObserver() {
                            override fun onSetSuccess() {
                                handler.post { afterRemoteDescription(type, restarted) }
                            }

                            override fun onSetFailure(error: String?) {
                                handler.post {
                                    if (closed) return@post

                                    trace("warn: setRemoteDescription failed (${error ?: "no message"})")

                                    if (type == SessionDescription.Type.ANSWER) {
                                        recoverRefusedAnswer(connection)
                                    } else {
                                        answered()
                                    }
                                }
                            }
                        }, SessionDescription(type, cleaned))
                    }
                }, SessionDescription(type, rewritten))
            }

            "candidate" -> {
                val candidate = body.optJSONObject("candidate") ?: return
                val line = candidate.optString("candidate")

                if (line.isEmpty()) return

                val ice = IceCandidate(
                    candidate.optString("sdpMid", ""),
                    candidate.optInt("sdpMLineIndex", 0),
                    line
                )

                val parsed = CallCandidateInfo.parse(line)

                if (parsed != null && parsed.type == "srflx" && parsed.address.isNotEmpty()) {
                    remotePublicIps.add(parsed.address)
                    reviewHeldCandidates()
                }

                if (parsed != null && parsed.type == "host" && parsed.isPrivate) {
                    holdPrivateCandidate(ice)
                    return
                }

                addRemoteCandidate(ice)
            }

            "refresh" -> CallSource.of(body.optString("source"))?.let { refreshSource(it) }
        }
    }

    /**
     * The other side has seen nothing from one of this side's video sources
     * for a while and asks for a fresh picture. The encoder is stopped and
     * started again through the encoding's active flag, which libwebrtc
     * answers with a keyframe. A source that is off, or that the other side
     * itself asked to be paused, has nothing to start over.
     */
    private fun refreshSource(source: CallSource) {
        if (closed || !source.isVideo || wanted[source] == 0) return

        val now = System.currentTimeMillis()

        if (now - (refreshedAt[source] ?: 0L) < REFRESH_MIN_INTERVAL_MS) return

        withTransceiver(source) { transceiver ->
            val sender = transceiver.sender
            val track = sender.track() ?: return@withTransceiver

            if (track.state() != MediaStreamTrack.State.LIVE) return@withTransceiver

            refreshedAt[source] = now

            // What the source had put out so far, so the frames the capture
            // produces from here can be held against the frames that get
            // encoded: an encoder that takes the capture and turns none of it
            // into frames is dead, and it is this end that can see so.
            val before = lastOutbound[source]

            refreshWatch[source] = RefreshWatch(now, before?.encoded ?: 0L, before?.produced, before?.family)

            trace("starting the ${source.wire} encoder over, the other side sees no picture")

            // Held off for a moment, not flipped: off and on in the same
            // breath is no net change to the bandwidth allocation, and it is
            // that change libwebrtc probes on. Measured 16 Sep 2026: the flip
            // restarted nothing, a held change did.
            val pause = sender.parameters ?: return@withTransceiver
            val paused = pause.encodings.firstOrNull() ?: return@withTransceiver

            paused.active = false
            sender.parameters = pause

            handler.postDelayed({
                if (closed || wanted[source] == 0) return@postDelayed

                withTransceiver(source) { again ->
                    val resume = again.sender.parameters ?: return@withTransceiver
                    val resumed = resume.encodings.firstOrNull() ?: return@withTransceiver

                    resumed.active = true
                    again.sender.parameters = resume
                }
            }, REFRESH_HOLD_MS)
        }
    }

    private fun afterRemoteDescription(type: SessionDescription.Type, restarted: Boolean) {
        if (closed) return

        if (type == SessionDescription.Type.ANSWER) answerRecoveries = 0

        flushCandidates()

        if (type != SessionDescription.Type.OFFER) return

        // When the other side last offered, for the polite side of a dead
        // path: an offer arriving after it switched to the relay is the
        // other side's restart, which it answers rather than restarting into.
        lastOfferAt = System.currentTimeMillis()

        adoptOfferedSections()
        answer()

        // The other side's ICE restart, answered before a first connection:
        // this side's attempt too, watched from now, so its own connect clock
        // does not restart over it. Either side may make it, the one whose
        // own ICE failed: a callee whose caller heard the relay late answered
        // at once and checked alone until it gave up, and only its own offer
        // has the two sides start their checks together.
        if (restarted && !everConnected && !isConnected() && heldSince == 0L && !closed && !failed) {
            if (restartDeadline != null) {
                watchAttempt("the other side's restart", null)
            } else if (!takeOtherAttempt()) {
                trace("the other side restarted once more than this side counts: its deadline ends it")
            }
        } else if (restarted && polite && !closed && !failed) {
            // After a first connection: a polite wait for this very restart
            // ends now, so its deadline counts from this answer.
            endRestartWait()
        }
    }

    /**
     * The other side's restart taken as this side's attempt: counted, and
     * watched on the same deadline as a restart of its own. A spent budget or
     * a hold is decided the way it always was, by the restart this would
     * otherwise have made.
     */
    private fun takeOtherAttempt(giveUp: String? = null): Boolean {
        if (closed || failed || heldSince != 0L || restartCount >= MAX_RESTARTS) return false

        restartCount++
        troubleAt = System.currentTimeMillis()
        trace("the other side restarted, this side answered and watches it (restart $restartCount of $MAX_RESTARTS)")
        watchAttempt("the other side's restart", giveUp)

        return true
    }

    /**
     * Both descriptions held: the first time, the connect clocks start; every
     * time, an attempt in flight counts its deadline from here.
     */
    private fun noteStable() {
        val connection = pc ?: return

        if (closed || connection.signalingState() != PeerConnection.SignalingState.STABLE ||
            connection.localDescription == null || connection.remoteDescription == null) return

        val now = System.currentTimeMillis()

        stableAt = now

        if (negotiatedAt != 0L) return

        negotiatedAt = now
        trace("negotiated ${now - connectStartedAt}ms after the peer was made: the connect clock starts")

        if (closed || everConnected) return

        handler.removeCallbacks(connectRelay)
        handler.postDelayed(connectRelay, CONNECT_RELAY_AT_MS)
        handler.removeCallbacks(connectDeadline)
        handler.postDelayed(connectDeadline, CONNECT_DEADLINE_MS)
    }

    /**
     * The sections a remote offer created become this side's transceivers,
     * matched to sources by the mid map that travelled with it, by kind and
     * order without one. Direction sendrecv, so the answer carries this side's
     * media too, and the local tracks go in before it is made.
     */
    private fun adoptOfferedSections() {
        val connection = pc ?: return

        for (transceiver in connection.transceivers) {
            val mid = transceiver.mid ?: continue

            if (mid.isEmpty() || sourceOfMid.containsKey(mid)) continue

            val isVideo = transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            val mapped = remoteMids[mid]

            val source = if (mapped != null && transceiverSenders[mapped] == null && mapped.isVideo == isVideo) {
                mapped
            } else {
                CallSource.ORDER.firstOrNull { it.isVideo == isVideo && transceiverSenders[it] == null }
            } ?: continue

            transceiver.sender?.id()?.let { transceiverSenders[source] = it }
            sourceOfMid[mid] = source

            transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV

            if (isVideo) {
                CallCodecs.apply(
                    CallCodecs.answerOrder(caps, peerCaps, source == CallSource.SCREEN_SHARE, forcedFamilies),
                    transceiver,
                    factory
                )
            } else if (source == CallSource.MICROPHONE && CallCodecs.applyRed(transceiver, factory)) {
                trace("microphone answers RED first")
            }

            localTracks[source]?.let { transceiver.sender.setTrack(it, false) }
        }
    }

    private fun answer() {
        val connection = pc ?: return

        connection.createAnswer(object : CallSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : CallSdpObserver() {
                    override fun onSetSuccess() {
                        handler.post {
                            if (closed) return@post

                            refreshMids()

                            // The answer is the only place the sent codec is
                            // actually decided, and it does not exist until
                            // here — reading `localDescription` any earlier
                            // gets null, which is how this went unlogged.
                            logH265("local ANSWER", connection.localDescription?.description)

                            // Which section carries which source, beside the
                            // orders above. The two video sections are ranked
                            // differently — the camera for motion, the screen
                            // share for text — so a camera adopted onto the
                            // screen share's section is sent with the screen
                            // share's preference, and the codec that comes out
                            // is nobody's first choice.
                            CallLog.write("sections: " + sourceOfMid.entries.joinToString(" ") { "mid" + it.key + "=" + it.value.wire })

                            connection.localDescription?.takeIf { it.type == SessionDescription.Type.ANSWER }?.let { sendDescription(it) }

                            // Adopted sections carry no ceiling of their own;
                            // they get one the same way a wish does, now that
                            // the answer is in.
                            CallSource.ORDER.forEach { applyWanted(it) }

                            answered()
                        }
                    }

                    override fun onSetFailure(error: String?) {
                        handler.post {
                            if (closed) return@post

                            trace("warn: answer failed (${error ?: "no message"})")
                            answered()
                        }
                    }
                }, description)
            }

            override fun onCreateFailure(error: String?) {
                handler.post {
                    if (closed) return@post

                    trace("warn: answer failed (${error ?: "no message"})")
                    answered()
                }
            }
        }, MediaConstraints())
    }

    // The offer of the other side's is answered, or will not be: a restart
    // that waited for it goes now.
    private fun answered() {
        answeringSince = 0L

        if (!restartAfterAnswer || closed) return

        restartAfterAnswer = false
        pc?.let { requestIceRestart(it) }
    }

    private fun answering(): Boolean =
        answeringSince != 0L && System.currentTimeMillis() - answeringSince < ANSWERING_MAX_MS

    // Every restart of this side's goes through here, on the handler the
    // signals are handled on. One asked for while an offer of the other
    // side's is being answered waits for the answer, which libwebrtc would
    // otherwise give the new credentials (`restartPending`).
    private fun requestIceRestart(connection: PeerConnection) {
        if (answering()) {
            restartAfterAnswer = true
            trace("the restart waits for the answer to the other side's offer")
            return
        }

        restartPending = true
        restartPendingAt = System.currentTimeMillis()
        connection.restartIce()
    }

    // An answer libwebrtc will not take leaves this side holding its own
    // offer, where no later negotiation can begin and the other side's
    // candidates name credentials it never applied. The offer is rolled back
    // and ICE restarted, and the answer to a restart may carry any
    // credentials. Twice at most until an answer applies, so two sides that
    // cannot agree do not go on forever.
    private fun recoverRefusedAnswer(connection: PeerConnection) {
        if (connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return

        if (answerRecoveries >= ANSWER_RECOVERIES) {
            trace("the answer was refused again, $ANSWER_RECOVERIES recoveries since the last one that applied: left to the attempt's deadline")
            return
        }

        answerRecoveries++

        connection.setLocalDescription(object : CallSdpObserver() {
            override fun onSetSuccess() {
                handler.post {
                    if (closed) return@post

                    trace("the answer was refused: this side's offer rolled back, ICE restarted so the next answer may carry new credentials")
                    requestIceRestart(connection)
                }
            }

            override fun onSetFailure(error: String?) {
                handler.post {
                    if (!closed) trace("warn: could not roll back the offer whose answer was refused (${error ?: "no message"})")
                }
            }
        }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
    }

    private fun applyRemoteMids(mids: JSONObject) {
        val parsed = mutableMapOf<String, CallSource>()

        mids.keys().forEach { key ->
            val mid = key as? String ?: return@forEach

            CallSource.of(mids.optString(mid))?.let { parsed[mid] = it }
        }

        remoteMids = parsed
    }

    // Candidates

    private fun addRemoteCandidate(candidate: IceCandidate) {
        val connection = pc ?: return

        if (closed) return

        if (connection.remoteDescription == null) {
            pendingCandidates.add(candidate)
            return
        }

        connection.addIceCandidate(candidate)
    }

    private fun flushCandidates() {
        val pending = pendingCandidates.toList()
        pendingCandidates.clear()

        pending.forEach { pc?.addIceCandidate(it) }
    }

    /**
     * A private host address is worth trying only when both sides are behind
     * the same public address — otherwise it belongs to a stranger's network
     * and pairing it wastes the connectivity check. Held briefly, because the
     * public addresses usually arrive a moment later.
     */
    private fun holdPrivateCandidate(candidate: IceCandidate) {
        if (privateDecision == "drop") return

        if (privateDecision == "keep") {
            addRemoteCandidate(candidate)
            return
        }

        heldPrivateCandidates.add(candidate)
        reviewHeldCandidates()

        if (privateHoldPending) return

        privateHoldPending = true

        handler.postDelayed({
            privateHoldPending = false
            decidePrivateCandidates("keep")
        }, PRIVATE_HOLD_MS)
    }

    private fun reviewHeldCandidates() {
        if (privateDecision != null || localPublicIps.isEmpty() || remotePublicIps.isEmpty()) return

        decidePrivateCandidates(if (remotePublicIps.any { localPublicIps.contains(it) }) "keep" else "drop")
    }

    private fun decidePrivateCandidates(decision: String) {
        if (privateDecision != null) return

        privateDecision = decision

        val held = heldPrivateCandidates.toList()
        heldPrivateCandidates.clear()

        if (decision == "keep") held.forEach { addRemoteCandidate(it) }
    }

    // ICE state and restarts

    private fun setConnection(state: CallConnection) {
        if (connection == state) return

        connection = state
        listener?.onPeerConnection(state)
    }

    /**
     * A restart the ICE state asked for. The polite side waits longer
     * ([politeWait]) so the two do not restart into each other, and an offer
     * that came in the wait is this side's attempt, watched rather than
     * restarted over ([takeOtherAttempt]) — and so is a restart of the other
     * side's that came in just before this side noticed the trouble: the
     * other side often notices first, and a restart made over it abandoned
     * the checks it had begun. The first of an incident goes out on every
     * candidate, relay ones included, because the usual cause is one side
     * moving to another network, where a new direct path is often there to be
     * found; any after it go over the relay alone. An attempt already in
     * flight is left to its own deadline.
     */
    private fun scheduleRestart(delay: Long, why: String) {
        if (restartWork != null || restarting || restartDeadline != null) return

        val startedAt = System.currentTimeMillis()
        val politeWait = if (polite) politeWait() else 0L

        armRestartTimer(delay + politeWait, polite) {
            // The other side's restart, taken as this side's attempt when it
            // arrived (before a first connection): its own deadline decides.
            if (restartDeadline != null) return@armRestartTimer

            val offered = lastOfferAt >= startedAt || (lastRestartOfferAt != 0L && lastRestartOfferAt >= startedAt - politeWait)

            if (polite && offered && !isConnected() && takeOtherAttempt()) return@armRestartTimer

            restart(force = false, why = why, relay = restartCount > 0)
        }
    }

    /** [run] after [delay] as the restart timer; a polite wait's [run] can be
     *  ended early by a restart of the other side's ([endRestartWait]). */
    private fun armRestartTimer(delay: Long, politeWait: Boolean, run: () -> Unit) {
        restartWork?.let { handler.removeCallbacks(it) }

        val work = Runnable {
            restartWork = null
            restartWaitRun = null
            run()
        }

        restartWork = work
        restartWaitRun = if (politeWait) run else null
        handler.postDelayed(work, delay)
    }

    /** A restart of the other side's arrived while the polite side waited
     *  for one: the wait ends now, so the attempt's deadline counts from this
     *  very answer rather than from the end of the wait. */
    private fun endRestartWait() {
        val work = restartWork ?: return
        val run = restartWaitRun ?: return

        handler.removeCallbacks(work)
        restartWork = null
        restartWaitRun = null
        run()
    }

    private fun clearRestartTimers() {
        restartWork?.let { handler.removeCallbacks(it) }
        restartWork = null
        restartWaitRun = null
        restartDeadline?.let { handler.removeCallbacks(it) }
        restartDeadline = null
    }

    /** Whether escalation is waiting for a restart to be negotiable again. */
    val held: Boolean
        get() = heldFlag

    /**
     * Told by the engine on every stats tick and whenever either side's
     * presence changes. While this side's socket is down or answers nothing,
     * or the relay has said the other side's is gone, no offer can reach the
     * other side: a restart
     * made then counted as a failed attempt, and a network change of ten
     * seconds on one side ran the budget out and put the call in the room for
     * good. Held, nothing escalates; released, the ladder starts over.
     */
    fun setSignalingHeld(held: Boolean, why: String) {
        handler.post {
            if (closed || failed) return@post

            if (!held) {
                // Both sides reachable ends the episode: the next time one of
                // them is not is a new one, with a hold of its own.
                holdSpent = false

                if (heldSince != 0L) releaseHold(why)

                return@post
            }

            if (heldSince != 0L) return@post

            // This episode had its hold and it ran out. Held again, the
            // restart the release started was cancelled on the next stats
            // tick, and a phone that had lost every connection sat on
            // "Reconnecting…" for good, holding the camera, the microphone
            // and the call. Not held, the ladder runs as though there had
            // been no hold: the restarts, then the room or the end.
            if (holdSpent) return@post

            heldSince = System.currentTimeMillis()
            heldFlag = true
            heldWhy = why
            clearRestartTimers()
            trace("escalation held: $why")

            // Not for ever: a side that stays out of reach this long is not
            // coming back through the relay, and the old road — the room, or
            // the end of the call — is what is left.
            val work = Runnable {
                holdWork = null

                if (heldSince != 0L) {
                    holdSpent = true

                    releaseHold("still out of reach after ${SIGNALING_HOLD_MAX_MS / 1000}s, not held again until both sides can be reached")
                }
            }

            holdWork = work
            handler.postDelayed(work, SIGNALING_HOLD_MAX_MS)
        }
    }

    private fun releaseHold(why: String) {
        val heldFor = System.currentTimeMillis() - heldSince

        heldSince = 0L
        heldFlag = false
        heldWhy = ""
        holdWork?.let { handler.removeCallbacks(it) }
        holdWork = null

        // What was tried while the other side could not hear it says nothing
        // about the path, so the restarts are all there again —
        // [HOLD_REFRESHES] times until the connection has been healthy since.
        // A socket that stalls and answers again every few seconds is held and
        // released as often, and each release gave a dead call a new trial.
        val refresh = holdRefreshCount < HOLD_REFRESHES

        lastReleaseAt = System.currentTimeMillis()

        if (refresh) {
            holdRefreshCount++
            restartCount = 0
        }

        trace("escalation released after ${String.format(java.util.Locale.US, "%.1f", heldFor / 1000.0)}s: $why" +
            if (refresh) "" else ", the restarts stay as they were: given back $HOLD_REFRESHES times since the connection was last healthy")

        if (closed || failed) return

        // A deadline that ran out during the hold waited for this (it ends
        // nothing while held), so one is armed again either way.
        if (!everConnected) {
            handler.removeCallbacks(connectDeadline)
            handler.postDelayed(connectDeadline, if (refresh) MAX_RESTARTS * RESTART_DEADLINE_MS + 2_000L else RESTART_DEADLINE_MS)
        }

        if (!isConnected()) scheduleRestart(0, if (holdSpent) "the hold ran out" else "the other side can be reached again")
    }

    /**
     * Fresh credentials inside a short budget, or the ones already held.
     * Asking used to inherit the socket's full retry budget — twenty-four
     * seconds inside a restart that was supposed to be the quick way out.
     */
    private fun freshIceServers(done: (List<PeerConnection.IceServer>) -> Unit) {
        var settled = false

        val budget = Runnable {
            if (settled) return@Runnable

            settled = true
            trace("fresh ice servers did not arrive in ${ICE_SERVERS_BUDGET_MS}ms, restarting with the ones held")
            done(emptyList())
        }

        handler.postDelayed(budget, ICE_SERVERS_BUDGET_MS)

        listener?.onPeerRefreshIceServers { servers ->
            handler.post {
                if (settled) return@post

                settled = true
                handler.removeCallbacks(budget)
                done(servers)
            }
        }
    }

    /** Relay candidates only, with credentials fresh enough to allocate on. */
    private fun useRelayOnly() {
        freshIceServers { servers ->
            val connection = pc ?: return@freshIceServers

            if (closed) return@freshIceServers

            if (servers.isNotEmpty()) iceServersHeld = servers

            relayOnly = true

            if (!connection.setConfiguration(configuration(iceServersHeld))) {
                trace("warn: could not switch to relay only")
            }
        }
    }

    /**
     * One attempt to bring the media back. `force` restarts a connection ICE
     * still calls connected, which is what a dead path is; `relay` takes it
     * over the relay alone, and otherwise it goes out on every candidate the
     * call was set up with, the relay's among them; `giveUp` is the reason
     * handed on once the restarts have run out. One at a time: two restarts
     * in flight each counted and each armed a deadline, and the second undid
     * the first.
     */
    fun restart(force: Boolean, why: String = "asked", relay: Boolean = false, giveUp: String? = null) {
        handler.post {
            val connection = pc ?: return@post

            if (closed || failed) return@post
            if (isConnected() && !force) return@post
            if (restarting) return@post

            if (heldSince != 0L) {
                trace("restart ($why) waits: $heldWhy")
                return@post
            }

            troubleAt = System.currentTimeMillis()

            if (restartCount >= MAX_RESTARTS) {
                fail(giveUp ?: if (everConnected) "ice_lost" else "ice_failed", "$why, $restartCount restarts did not bring the media back")
                return@post
            }

            restarting = true
            restartCount++
            restartsTotal++

            // Fresh TURN credentials first: the old ones may be what expired.
            freshIceServers { servers ->
                restarting = false

                if (closed) return@freshIceServers

                if (servers.isNotEmpty()) iceServersHeld = servers

                // Back to the call's own policy when this one is not over the
                // relay alone: a restart that went relay-only used to leave
                // the connection so for the rest of the call.
                relayOnly = relay || baseRelayOnly

                if (!connection.setConfiguration(configuration(iceServersHeld))) {
                    trace("warn: could not apply ice servers for the restart")
                }

                // Tearing down a working candidate pair is the loudest thing
                // this class does, and it used to say nothing at all.
                trace("ice restart #$restartsTotal ($why), $restartCount of $MAX_RESTARTS${if (relay) " over the relay alone" else " on every candidate"} servers=${if (servers.isEmpty()) "held" else "fresh"}")

                requestIceRestart(connection)

                watchAttempt("restart #$restartsTotal", giveUp)
            }
        }
    }

    /**
     * The deadline an attempt has to bring the media back. A connection still
     * down is restarted again over the relay alone, and so is one that came
     * back carrying nothing; the restarts running out is the room. On the
     * polite side an offer that arrived after the answer the attempt's checks
     * counted from is the other side's next attempt, begun at its own earlier
     * deadline, and it is watched in turn rather than restarted over; with
     * none yet, the polite side waits for it ([awaitOtherAttempt]), since the
     * two deadlines run out at about the same moment and the impolite side
     * restarts at once. An offer that crossed this side's own restart, and
     * was answered in its place, is the same attempt, not a next one.
     */
    private fun watchAttempt(what: String, giveUp: String?) {
        armAttemptDeadline(what, giveUp, System.currentTimeMillis(), inboundPackets, false, RESTART_DEADLINE_MS)
    }

    private fun armAttemptDeadline(what: String, giveUp: String?, startedAt: Long, inboundThen: Long, extended: Boolean, after: Long) {
        restartDeadline?.let { handler.removeCallbacks(it) }

        val deadline = Runnable {
            restartDeadline = null

            val connection = pc

            if (closed || failed || heldSince != 0L || connection == null) return@Runnable

            // A connection still down gets [RESTART_DEADLINE_MS] of checks
            // from the attempt's own answer: over a slow trip the answer comes
            // seconds after the offer, and the deadline used to run out on
            // checks that had barely begun. No answer by [ANSWER_WAIT_MS] more
            // is the attempt failed. (One this side answered was answered as
            // it began.)
            val answeredAt = if (connection.signalingState() == PeerConnection.SignalingState.STABLE) maxOf(stableAt, startedAt) else 0L

            if (!isConnected()) {
                val now = System.currentTimeMillis()
                val left = if (answeredAt != 0L) RESTART_DEADLINE_MS - (now - answeredAt) else startedAt + RESTART_DEADLINE_MS + ANSWER_WAIT_MS - now

                if (left > 250L) {
                    if (!extended) {
                        trace(if (answeredAt != 0L) "$what was answered ${answeredAt - startedAt}ms after it began: its checks get ${RESTART_DEADLINE_MS}ms from the answer"
                              else "$what has no answer yet: waiting up to ${ANSWER_WAIT_MS}ms more")
                    }

                    armAttemptDeadline(what, giveUp, startedAt, inboundThen, true, left)
                    return@Runnable
                }
            }

            val down = !isConnected()

            if (!down && !(expectInbound && inboundPackets == inboundThen)) {
                trace("$what brought the media back in ${System.currentTimeMillis() - startedAt}ms")
                return@Runnable
            }

            val failedWhy = if (down) "$what did not connect in ${RESTART_DEADLINE_MS}ms${if (answeredAt != 0L) " from its answer" else ", and was never answered"}"
                else "$what connected and brought nothing in ${RESTART_DEADLINE_MS}ms"

            // (With the restarts run out there is nothing to wait for: the
            // restart below gives the call up. Nor with the hold spent: no
            // restart of the other side's can arrive.)
            if (polite) {
                val after = if (answeredAt != 0L) answeredAt else startedAt

                if (lastOfferAt > after && takeOtherAttempt(if (down) giveUp else "path_dead")) return@Runnable

                if (restartCount < MAX_RESTARTS && !holdSpent) {
                    awaitOtherAttempt(failedWhy, giveUp)
                    return@Runnable
                }
            }

            if (down) {
                restart(force = false, why = failedWhy, relay = true, giveUp = giveUp)
            } else {
                restart(force = true, why = failedWhy, relay = true, giveUp = "path_dead")
            }
        }

        restartDeadline = deadline
        handler.postDelayed(deadline, after)
    }

    /**
     * The polite side's attempt is over and the media is not back: the other
     * side's next restart is due about now, so it is given [politeWait] to
     * arrive and taken as this side's attempt if it does. Only if none does is
     * the restart this side's own.
     */
    private fun awaitOtherAttempt(why: String, giveUp: String?) {
        val waitFrom = System.currentTimeMillis()
        val wait = politeWait()
        val inboundThen = inboundPackets

        trace("$why: waiting up to ${wait}ms for the other side's next restart")

        armRestartTimer(wait, true) {
            if (closed || failed || heldSince != 0L || restarting || restartDeadline != null) return@armRestartTimer

            val down = !isConnected()

            if (!down && (!expectInbound || inboundPackets != inboundThen)) {
                trace("the media came back while this side waited for the other side's restart")
                return@armRestartTimer
            }

            if (lastOfferAt >= waitFrom && takeOtherAttempt(if (down) giveUp else "path_dead")) return@armRestartTimer

            if (down) {
                restart(force = false, why = "$why, and no restart came from the other side", relay = true, giveUp = giveUp)
            } else {
                restart(force = true, why = "$why, and no restart came from the other side", relay = true, giveUp = "path_dead")
            }
        }
    }

    /**
     * New TURN credentials, before the ones the connection holds run out. An
     * allocation is refreshed with the credentials it was made with, and the
     * TURN server refuses those once they have expired, so a call relayed
     * for longer than their life (six hours by default) lost its relay
     * mid-call. Every later restart takes the new ones. A path that runs
     * through this side's own relay candidate is moved onto fresh
     * allocations now, while the old one still carries it, and that restart
     * is not an attempt: it counts as none. The polite side waits a little,
     * so two relayed sides do not offer into each other, and an offer from
     * the other side meanwhile has renewed this side's allocations already.
     */
    fun renewRelay(servers: List<PeerConnection.IceServer>) {
        handler.post {
            val connection = pc ?: return@post

            if (closed || failed || servers.isEmpty()) return@post

            iceServersHeld = servers

            if (!connection.setConfiguration(configuration(iceServersHeld))) {
                trace("warn: could not apply the renewed ice servers")
                return@post
            }

            val relayed = stats.candidateLocal == "relay"

            trace("relay credentials renewed${if (relayed) ", moving this side's relay onto fresh allocations" else ""}")

            if (!relayed) return@post

            val startedAt = System.currentTimeMillis()

            handler.postDelayed({
                val current = pc ?: return@postDelayed

                if (closed || failed || heldSince != 0L || restarting || !isConnected()) return@postDelayed

                if (lastOfferAt >= startedAt) {
                    trace("the other side restarted meanwhile, which moved this side's relay too")
                    return@postDelayed
                }

                restartsTotal++
                trace("ice restart #$restartsTotal (the relay credentials are about to run out), not counted as an attempt")

                requestIceRestart(current)
            }, if (polite) RENEW_POLITE_DELAY_MS else 0L)
        }
    }

    /**
     * The verdict on a silence that began at `since`: [STALL_TIMEOUT_MS] with
     * nothing arriving from a peer that has something on. What it means
     * depends on what the peer itself says it did meanwhile — its RTCP sender
     * reports travel over the same transport, and the relay's pulse carries
     * the same counts by another road.
     *
     * Only samples taken after the silence began count: a sender report says
     * what was sent up to the moment it was sent, and a pulse what was read
     * up to [PULSE_LAG_MS] before it arrived, so the first sample of each
     * after `since` is the baseline and growth past it is sending during the
     * silence. Two such samples are needed either way; with fewer the verdict
     * waits a tick, up to twice [STALL_TIMEOUT_MS], after which a silence
     * nobody can account for is treated as a dead path. Returns whether a
     * verdict was reached.
     */
    private fun judgeSilence(now: Long, since: Long, counters: CallPeerCounters): Boolean {
        val quiet = now - since
        val sinceMs = since.toDouble()
        val reports = remoteSamples.filter { it.reportAt >= sinceMs }
        val pulses = peerPulses.filter { it.seenAt - PULSE_LAG_MS >= sinceMs }

        val byReports = if (reports.size >= 2) {
            (reports.last().audio - reports.first().audio) + (reports.last().video - reports.first().video)
        } else {
            0L
        }

        val byPulses = if (pulses.size >= 2) {
            (pulses.last().outAudio - pulses.first().outAudio) + (pulses.last().outVideo - pulses.first().outVideo)
        } else {
            0L
        }

        val reportFresh = counters.remoteAt > 0 && now - counters.remoteAt <= REMOTE_REPORT_AGE_MS.toDouble()
        val pulseFresh = peerPulses.lastOrNull()?.let { now - it.seenAt <= REMOTE_REPORT_AGE_MS.toDouble() } ?: false
        val peerAlive = reportFresh || pulseFresh

        if (byReports >= SILENCE_SENT_MIN || byPulses >= SILENCE_SENT_MIN) {
            trace(
                "no inbound for ${seconds(quiet)}s while the peer sent " +
                    (if (byReports >= SILENCE_SENT_MIN) "$byReports packets by its own reports" else "$byPulses packets by the relay's pulses") +
                    " during it: the path is dead"
            )
            pathDead("nothing arrives while the peer sends")

            return true
        }

        if (reports.size >= 2 || pulses.size >= 2) {
            // The transport is fine — the peer's word keeps coming — and it
            // sent nothing during the silence. That is its capture, and no
            // restart mends a microphone. It is told, and the call goes on.
            val source = if (expectedVideo.isNotEmpty() && counters.inVideo == 0L) CallSource.CAMERA else CallSource.MICROPHONE
            val last = lastSilentNotice[source] ?: 0L

            val trickle = maxOf(byReports, byPulses)

            trace(
                "no inbound for ${seconds(quiet)}s and the peer sent " +
                    (if (trickle > 0) "nothing but $trickle padding packets" else "nothing") + " during it (" +
                    (if (reports.size >= 2) "by its own reports" else "by the relay's pulses") +
                    "): its ${source.wire} stopped, not the path"
            )

            if (now - last >= SILENT_NOTICE_MIN_MS) {
                lastSilentNotice[source] = now
                listener?.onPeerSilent(source)
            }

            return true
        }

        if (!peerAlive) {
            // Nothing at all, not even RTCP: the transport is gone whatever
            // ICE still thinks, and libwebrtc's own DISCONNECTED is a few
            // seconds behind this.
            trace("no inbound for ${seconds(quiet)}s and no word from the peer at all: the transport is dead")
            pathDead("nothing arrives, not even the peer's reports")

            return true
        }

        if (quiet >= STALL_TIMEOUT_MS * 2) {
            trace("no inbound for ${seconds(quiet)}s and the peer's word does not settle it (${reports.size} reports, ${pulses.size} pulses since): treating the path as dead")
            pathDead("nothing arrives and the silence cannot be accounted for")

            return true
        }

        if (silenceNoted == 0L) {
            silenceNoted = now
            trace("no inbound for ${seconds(quiet)}s, waiting for the peer's word on it (${reports.size} reports, ${pulses.size} pulses since)")
        }

        return false
    }

    /**
     * A dead path is restarted over the relay alone, with fresh credentials,
     * until the restarts run out, and then it is the room. An attempt in
     * flight is judged by its own deadline. Only the impolite side restarts
     * at once: the polite one switches to the relay and gives the offer a
     * moment to arrive, restarting itself only if none does, so the two do
     * not restart into each other — and an offer that did arrive is this
     * side's attempt too, watched on the same deadline.
     */
    private fun pathDead(why: String) {
        if (closed || failed) return

        if (heldSince != 0L) {
            trace("the path looks dead ($why), and no restart can be negotiated now: $heldWhy")
            return
        }

        if (restarting || restartDeadline != null || restartWork != null) return

        troubleAt = System.currentTimeMillis()

        if (restartCount >= MAX_RESTARTS) {
            fail("path_dead", "$why, after $restartCount restarts over the relay")
            return
        }

        if (!polite) {
            restart(force = true, why = why, relay = true, giveUp = "path_dead")
            return
        }

        val startedAt = System.currentTimeMillis()

        useRelayOnly()

        armRestartTimer(politeWait(), true) {
            if (closed || failed || heldSince != 0L) return@armRestartTimer

            if (lastOfferAt >= startedAt) {
                restartCount++
                trace("the other side restarted over the relay, this side answered (restart $restartCount of $MAX_RESTARTS)")
                watchAttempt("the other side's restart", "path_dead")
                return@armRestartTimer
            }

            restart(force = true, why = "$why, no restart came from the other side", relay = true, giveUp = "path_dead")
        }
    }

    // Stats

    private fun collectStats() {
        val connection = pc ?: return

        connection.getStats { report ->
            handler.post {
                if (!closed) readStats(report.statsMap)
            }
        }
    }

    /**
     * A codec that negotiated and then carried nothing.
     *
     * The two sides of it look alike in the statistics and show up nowhere
     * else: a receiver taking packets that no frame ever comes out of, and a
     * sender handing frames to an encoder that emits none. Both mean the call
     * is running on something one end cannot actually do, and both are only
     * visible from the end it is happening at.
     */
    private fun reviewCodecs(entries: Map<String, RTCStats>) {
        val now = System.currentTimeMillis()

        // Nothing is encoded or decoded before the connection is up, whatever
        // the codec, so no verdict comes before it has been up a stats tick;
        // the clocks run as before. A callee whose caller heard the relay
        // late (30 Oct 2026, in the browser engine) wrote off H264 and then
        // VP9 while the two were still connecting, and each write-off's offer
        // crossed the caller's restart. Clocks that started at the connection
        // moved a real write-off seconds later, onto an ICE restart the other
        // side was making: Chrome answered its offer with new ICE
        // credentials, which Firefox refuses.
        val judging = isConnected() && connectedAt > 0L && now - connectedAt >= STATS_INTERVAL_MS

        fun watch(key: String, dead: Boolean, ready: Boolean, grace: Long): Boolean {
            if (!dead) {
                codecWatch.remove(key)

                return false
            }

            if (!ready) return false

            val since = codecWatch[key]

            if (since == null) {
                codecWatch[key] = now

                return false
            }

            return judging && now - since > grace
        }

        for ((_, entry) in entries) {
            val values = entry.members

            if (values["kind"] != "video") continue

            val codecId = values["codecId"] as? String ?: continue
            val mime = entries[codecId]?.members?.get("mimeType") as? String ?: continue
            val family = CallCodecs.FAMILIES.firstOrNull { it.equals(mime, ignoreCase = true) } ?: continue

            if (entry.type == "inbound-rtp") {
                val frames = (values["framesReceived"] as? Number)?.toLong() ?: 0L
                val decoded = (values["framesDecoded"] as? Number)?.toLong() ?: 0L
                val implementation = values["decoderImplementation"] as? String
                val gaveUp = implementation != null && DEAD_DECODER.containsMatchIn(implementation)

                if (decoded == 0L && frames > 0L && gaveUp) {
                    onCodecBroken(family, CallCodecs.DECODE)

                    continue
                }

                // Keyed by family as well as stream, so the clock measures how
                // long *this family* has been failing rather than how long the
                // stream has: a stream's reported codec moves about while a
                // call settles, and a clock that starts on one codec and
                // expires on another writes off whichever happened to be
                // showing. The write-off persists, so that costs the device a
                // good codec for every later call.
                if (watch("dec:" + entry.id + ":" + family, decoded == 0L, frames >= CODEC_DECODE_FRAMES, CODEC_DECODE_GRACE_MS)) {
                    onCodecBroken(family, CallCodecs.DECODE)
                }

                continue
            }

            if (entry.type != "outbound-rtp" || values["active"] == false) continue

            val sourceId = values["mediaSourceId"] as? String ?: continue
            val produced = (entries[sourceId]?.members?.get("frames") as? Number)?.toLong() ?: continue
            val encoded = (values["framesEncoded"] as? Number)?.toLong() ?: 0L

            val key = "enc:" + entry.id + ":" + family

            if (watch(key, encoded == 0L, produced >= CODEC_ENCODE_FRAMES, CODEC_ENCODE_GRACE_MS)) {
                val limit = values["qualityLimitationReason"] as? String ?: "?"
                val target = (values["targetBitrate"] as? Number)?.toLong()

                if (encoderStarved(limit, target)) {
                    // Judged again from the start, once it has something to
                    // spend and still encodes nothing.
                    codecWatch.remove(key)
                    noteStarved(family, encoderLimit(limit, target))
                } else {
                    onCodecBroken(family, CallCodecs.ENCODE, encoderLimit(limit, target))
                }
            }
        }
    }

    private fun noteStarved(family: String, why: String) {
        val now = System.currentTimeMillis()

        if (now - (starvedNotedAt[family] ?: 0L) < STARVED_NOTE_EVERY_MS) return

        starvedNotedAt[family] = now
        trace("${family.removePrefix("video/")} encodes nothing, but its encoder is starved ($why): not written off")
    }

    private fun onCodecBroken(family: String, direction: String, why: String = "") {
        if (closed || !brokenCodecs.add(family + ":" + direction)) return

        CallLog.write("codec $family carried nothing to ${if (direction == CallCodecs.DECODE) "decode" else "encode"}${if (why.isEmpty()) "" else " ($why)"}, dropping it")

        listener?.onPeerCodecBroken(family, direction)
    }

    private fun readStats(entries: Map<String, RTCStats>) {
        // A restart that waited on an answer libwebrtc never finished goes
        // once the wait has run out; one whose negotiation never began is
        // offered here.
        if (restartAfterAnswer && !answering()) answered()

        if (restartPending && !makingOffer && pc?.signalingState() == PeerConnection.SignalingState.STABLE &&
            System.currentTimeMillis() - restartPendingAt > RESTART_PENDING_MAX_MS
        ) {
            trace("the restart's negotiation did not begin by itself, offering it")
            negotiate()
        }

        reviewCodecs(entries)

        var bytesSent = 0L
        var bytesReceived = 0L
        var lost = 0L
        var received = 0L
        var inbound = 0L
        var rtt: Double? = null

        // Counted apart from the total, because "is this call still sending my
        // voice" is a question the total cannot answer on a video call: the
        // picture drowns the microphone a hundred to one, and the codec name
        // the line reports says only what *would* carry it.
        //
        // Both kinds, so that they have to add up to the total: a single figure
        // that quietly counted everything would have answered the question with
        // the wrong number and looked right doing it.
        var audioSent = 0L
        var videoSent = 0L

        val next = CallPeerStats()

        // A section with no media of its own — a camera that is off, an unused
        // screen share — still names a codec, and would otherwise be reported
        // as what the call is running on.
        fun codecName(values: Map<String, Any>): String? {
            val moving = listOf("framesEncoded", "framesDecoded", "packetsSent", "packetsReceived")
                .mapNotNull { values[it] as? Number }
                .any { it.toLong() > 0 }

            if (!moving) return null

            val codecId = values["codecId"] as? String ?: return null
            val mime = entries[codecId]?.members?.get("mimeType") as? String ?: return null

            return mime.substringAfterLast('/')
        }

        // Packets alone do not make a video section the one carrying the call:
        // an idle screen share sends padding and RTCP and would win the last
        // write. Frames do, so the busiest section is the one reported.
        var videoSendFrames = 0L
        var videoRecvFrames = 0L

        // Each inbound video stream by the source it carries: a section's mid
        // is the same on both ends, so this side's own map answers, with the
        // track's id standing in where a stream names no mid. Frames decoded
        // of frames assembled, packets, and the keyframe and retransmission
        // requests this side has made.
        val inboundVideo = mutableMapOf<CallSource, InboundVideo>()

        // And what this side's own video senders have put out (frames
        // encoded, packets sent), so a source the other side was asked to
        // start over can be read from both ends.
        val outboundVideo = mutableMapOf<CallSource, OutboundVideo>()

        // The bandwidth estimates on the selected pair: what this side
        // believes it may send, and what it tells the other side it may
        // receive. A picture that stops after a pause and comes back for a
        // moment on a parameter write is what a collapsed estimate looks
        // like, and these are the only numbers that show it.
        var bweOut: Long? = null
        var bweIn: Long? = null

        // The family a stream runs on, by the codec entry it names.
        fun familyOf(values: Map<String, Any>): String? {
            val codecId = values["codecId"] as? String ?: return null
            val mime = entries[codecId]?.members?.get("mimeType") as? String ?: return null

            return CallCodecs.FAMILIES.firstOrNull { it.equals(mime, ignoreCase = true) }
        }

        val counters = CallPeerCounters()

        for ((_, entry) in entries) {
            val values = entry.members

            when (entry.type) {
                "outbound-rtp" -> {
                    bytesSent += (values["bytesSent"] as? Number)?.toLong() ?: 0L

                    // `kind` is what the newer stats call it and `mediaType`
                    // what the older ones do; libwebrtc's Java map has carried
                    // either, and a section that answers to neither would be
                    // counted as both without this.
                    val sending = (values["kind"] ?: values["mediaType"]) as? String

                    if (sending == "audio") {
                        audioSent += (values["bytesSent"] as? Number)?.toLong() ?: 0L
                        counters.outAudio += (values["packetsSent"] as? Number)?.toLong() ?: 0L
                    }

                    if (sending == "video") {
                        videoSent += (values["bytesSent"] as? Number)?.toLong() ?: 0L
                        counters.outVideo += (values["packetsSent"] as? Number)?.toLong() ?: 0L
                    }

                    if (values["kind"] == "video") {
                        val frames = (values["framesEncoded"] as? Number)?.toLong() ?: 0L

                        if (frames > videoSendFrames) {
                            codecName(values)?.let { name ->
                                videoSendFrames = frames
                                next.videoSend = name
                                next.videoEncoder = values["encoderImplementation"] as? String
                            }
                        }

                        // By mid where the stats carry one, else by the media
                        // source's track id, which is this side's own track.
                        val sourceId = values["mediaSourceId"] as? String ?: ""
                        val trackId = entries[sourceId]?.members?.get("trackIdentifier") as? String
                        val source = sourceOfMid[values["mid"] as? String ?: ""]
                            ?: localTracks.entries.firstOrNull { trackId != null && it.value.id() == trackId }?.key

                        if (source != null) {
                            outboundVideo[source] = OutboundVideo(
                                (entries[sourceId]?.members?.get("frames") as? Number)?.toLong(),
                                frames,
                                (values["packetsSent"] as? Number)?.toLong() ?: 0L,
                                values["qualityLimitationReason"] as? String ?: "?",
                                (values["targetBitrate"] as? Number)?.toLong(),
                                familyOf(values)
                            )
                        }
                    } else {
                        codecName(values)?.let { name ->
                            if (values["kind"] == "audio") next.audioSend = name
                        }
                    }
                }

                "inbound-rtp" -> {
                    bytesReceived += (values["bytesReceived"] as? Number)?.toLong() ?: 0L
                    inbound += (values["packetsReceived"] as? Number)?.toLong() ?: 0L

                    if (values["kind"] == "video") {
                        counters.inVideo += (values["packetsReceived"] as? Number)?.toLong() ?: 0L

                        val frames = (values["framesDecoded"] as? Number)?.toLong() ?: 0L

                        if (frames > videoRecvFrames) {
                            codecName(values)?.let { name ->
                                videoRecvFrames = frames
                                next.videoRecv = name
                                next.videoDecoder = values["decoderImplementation"] as? String
                            }
                        }

                        val mid = values["mid"] as? String ?: ""
                        val trackId = values["trackIdentifier"] as? String
                        val source = sourceOfMid[mid]
                            ?: remoteTracks.entries.firstOrNull { trackId != null && it.value.id() == trackId }?.key

                        if (source != null) {
                            inboundVideo[source] = InboundVideo(
                                frames,
                                (values["framesReceived"] as? Number)?.toLong() ?: 0L,
                                (values["packetsReceived"] as? Number)?.toLong() ?: 0L,
                                (values["pliCount"] as? Number)?.toLong() ?: 0L,
                                (values["nackCount"] as? Number)?.toLong() ?: 0L,
                                familyOf(values)
                            )
                        }
                    } else {
                        if (values["kind"] == "audio") {
                            counters.inAudio += (values["packetsReceived"] as? Number)?.toLong() ?: 0L
                        }

                        codecName(values)?.let { name ->
                            if (values["kind"] == "audio") next.audioRecv = name
                        }
                    }

                    val previous = previousInbound[entry.id] ?: Pair(0L, 0L)
                    val currentLost = (values["packetsLost"] as? Number)?.toLong() ?: 0L
                    val currentReceived = (values["packetsReceived"] as? Number)?.toLong() ?: 0L

                    lost += max(0L, currentLost - previous.first)
                    received += max(0L, currentReceived - previous.second)

                    previousInbound[entry.id] = Pair(currentLost, currentReceived)
                }

                "remote-outbound-rtp" -> {
                    // The other side's own count of what it sent, carried in
                    // its RTCP sender reports over the same transport; the
                    // timestamp is when the last of those arrived here, in
                    // microseconds.
                    val sent = (values["packetsSent"] as? Number)?.toLong() ?: 0L

                    when (values["kind"] as? String) {
                        "audio" -> counters.remoteAudio += sent
                        "video" -> counters.remoteVideo += sent
                    }

                    val at = entry.timestampUs / 1000.0

                    if (at > counters.remoteAt) counters.remoteAt = at
                }
            }
        }

        // The pair in use, not any pair once nominated (CallPair).
        CallPair.selected(entries)?.members?.let { values ->
            (values["currentRoundTripTime"] as? Number)?.let { rtt = it.toDouble() }
            (values["availableOutgoingBitrate"] as? Number)?.let { bweOut = it.toLong() }
            (values["availableIncomingBitrate"] as? Number)?.let { bweIn = it.toLong() }

            (values["localCandidateId"] as? String)?.let { id ->
                entries[id]?.members?.let { local ->
                    next.candidateLocal = local["candidateType"] as? String
                    next.candidateProtocol = (local["relayProtocol"] as? String) ?: (local["protocol"] as? String)
                }
            }

            (values["remoteCandidateId"] as? String)?.let { id ->
                next.candidateRemote = entries[id]?.members?.get("candidateType") as? String
            }
        }

        val now = System.currentTimeMillis()

        // The same report, for the quality reading the next pulse may carry:
        // only while connected, which is when there is a call to tell of. And
        // whether RED leads the microphone's codecs, now and then.
        if (isConnected()) {
            quality.tick(entries, qualityScope(entries), now.toDouble())

            if (now - redReadAt >= RED_READ_MS) {
                redReadAt = now
                readRed()
            }
        }

        // Bytes, not packets. `packetsReceived` was the measure, and a trace of
        // a healthy call shows it frozen at 4867 for the whole of it while
        // `bytesReceived` climbed past five megabytes at five milliseconds of
        // round trip — so the watchdog decided a call carrying video fine had
        // gone silent, and restarted ICE every eight seconds until it really
        // had. Both are summed in the same pass over the same sections, so the
        // one that tracks what arrived is the one to ask.
        val inboundProgress = max(inbound, bytesReceived)

        // The other side's sender reports, one sample per report that
        // arrived, for judging a silence on what was sent during it.
        if (counters.remoteAt > 0 && remoteSamples.lastOrNull()?.reportAt != counters.remoteAt) {
            remoteSamples.add(RemoteSample(counters.remoteAudio, counters.remoteVideo, counters.remoteAt))

            while (remoteSamples.size > SAMPLE_HISTORY) remoteSamples.removeAt(0)
        }

        if (inboundProgress > inboundPackets) {
            inboundPackets = inboundProgress
            inboundChangedAt = now
            silenceNoted = 0L
        } else if (inboundProgress < inboundPackets) {
            // Backwards, which a packet count cannot go: these are the sums
            // over whichever inbound sections exist *now*, and an ICE restart
            // or a renegotiation gives the other side new SSRCs whose entries
            // start again at zero.
            //
            // Held as a high-water mark this was fatal rather than untidy. The
            // sum could never reach the old total again, so "has inbound moved"
            // answered no for the rest of the call: the watchdog fired every
            // eight seconds, each time restarting ICE, and each restart handed
            // out new SSRCs that put the count back down. One stall was enough
            // to start it and nothing could stop it — the call tore down its
            // own working candidate pair on a loop until ICE failed for good.
            //
            // A drop is a new stream, so it becomes the new baseline.
            inboundPackets = inboundProgress
            inboundChangedAt = now
            silenceNoted = 0L
        } else if (isConnected() && expectInbound && connectedAt > 0) {
            val since = max(inboundChangedAt, connectedAt)

            // The counters are taken before the verdict so it reads the
            // peer's reports of this very silence; a verdict that could not
            // be reached yet leaves the clock running and is asked again
            // next tick.
            if (now - since >= STALL_TIMEOUT_MS) {
                stats.counters = counters

                if (judgeSilence(now, since, counters)) {
                    inboundChangedAt = now
                    silenceNoted = 0L
                }
            }
        }

        // A connection that has carried media for [HEALTHY_RESET_MS] since its
        // last trouble has its restarts back: the next incident, an hour or a
        // day into the call, gets the whole trial rather than what the last
        // one left of it.
        //
        // "Carried media" is inbound that moved within [STALL_TIMEOUT_MS], and
        // a side with nothing on sends nothing to move: with the other side's
        // microphone muted and its camera off the restarts never came back,
        // however long the call stayed up. Connected with nothing inbound
        // expected counts as healthy.
        val inboundHealthy = !expectInbound || now - inboundChangedAt < STALL_TIMEOUT_MS

        if (restartCount > 0 && isConnected() && troubleAt > 0 && now - troubleAt >= HEALTHY_RESET_MS && inboundHealthy) {
            trace(
                if (expectInbound) "media has flowed for ${HEALTHY_RESET_MS / 1000}s since the last trouble, the restarts are back"
                else "connected for ${HEALTHY_RESET_MS / 1000}s since the last trouble with nothing inbound expected, the restarts are back"
            )
            restartCount = 0
        }

        // And a hold's release gives them back as often as ever again, once
        // the connection has been as healthy since the last one.
        if (holdRefreshCount > 0 && heldSince == 0L && isConnected() && inboundHealthy &&
            now - lastReleaseAt >= HEALTHY_RESET_MS && (troubleAt == 0L || now - troubleAt >= HEALTHY_RESET_MS)) {
            holdRefreshCount = 0
        }

        lastBweIn = bweIn
        reviewMicCeiling(now, bweOut, outboundVideo)
        lastOutbound = outboundVideo
        reviewVideoSources(now, inboundVideo)

        // A source this side was asked to start over says what it has put out
        // since, tick by tick for a while: the difference between an encoder
        // that came back and one that did not is on this end — and this end
        // acts on it. A capture that has produced twenty frames since the
        // restart while the encoder turned out none is an encoder that is
        // dead for this family, which is written off and the call offered
        // again without it — unless the encoder says it is starved, which is
        // the path's problem.
        for ((source, at) in refreshedAt) {
            if (now - at > REFRESH_WATCH_MS) continue

            val stream = outboundVideo[source]

            trace("${source.wire} ${seconds(now - at)}s after the restart: ${describe(stream)} bwe out=${kbps(bweOut)}")

            val watch = refreshWatch[source] ?: continue

            if (stream == null || now - at < CODEC_ENCODE_GRACE_MS) continue

            val produced = stream.produced ?: continue
            val producedThen = watch.produced ?: continue
            val family = stream.family ?: continue

            val producedSince = produced - producedThen
            val encodedSince = stream.encoded - watch.encoded

            if (producedSince >= CODEC_ENCODE_FRAMES && encodedSince == 0L) {
                val why = encoderLimit(stream.limit, stream.target)
                val name = family.removePrefix("video/")

                refreshWatch.remove(source)

                if (encoderStarved(stream.limit, stream.target)) {
                    trace("the ${source.wire} encoder took $producedSince frames since the restart and encoded none, starved ($why): $name is not written off")
                } else {
                    trace("the ${source.wire} encoder took $producedSince frames since the restart and encoded none ($why): $name is dead here")
                    onCodecBroken(family, CallCodecs.ENCODE, why)
                }
            }
        }

        val packetLoss = if (lost + received > 0) lost.toDouble() / (lost + received).toDouble() else 0.0
        val currentRtt = rtt

        next.bytesSent = bytesSent
        next.bytesReceived = bytesReceived
        next.rtt = currentRtt
        next.packetLoss = packetLoss
        next.restarts = restartsTotal
        next.counters = counters

        next.quality = when {
            !isConnected() -> if (everConnected) CallQuality.LOST else CallQuality.UNKNOWN
            packetLoss < 0.02 && (currentRtt == null || currentRtt < 0.15) -> CallQuality.EXCELLENT
            packetLoss < 0.05 && (currentRtt == null || currentRtt < 0.3) -> CallQuality.GOOD
            else -> CallQuality.POOR
        }

        stats = next

        // The camera's ceiling follows the path: a relay on either side is our
        // TURN server's traffic. A pass that names no pair (the path down for a
        // moment) keeps what the last one said.
        if (next.candidateLocal != null || next.candidateRemote != null) {
            val isRelayed = next.candidateLocal == "relay" || next.candidateRemote == "relay"

            if (isRelayed != relayed) {
                relayed = isRelayed
                val ceiling = if (isRelayed) CallShapes.CAMERA_RELAY_MAX_BITRATE else CallShapes.CAMERA_DIRECT_MAX_BITRATE
                trace("camera ceiling ${ceiling / 1000}k: ${if (isRelayed) "relayed" else "direct"}")
                applyWanted(CallSource.CAMERA)
            }
        }

        correctSendCodec(next)

        // A call that reports nothing moving is a call nobody can hear, and
        // the only place that said so was a statistics sheet nobody opens.
        //
        // Every change of ICE state, and a line every half minute otherwise:
        // the poll is every two seconds, and at that rate it is the only thing
        // left in a log that has to hold a whole call.
        val ice = pc?.iceConnectionState()

        if (ice != loggedIce || now - loggedStatsAt > 30_000) {
            loggedIce = ice
            loggedStatsAt = now

            val video = inboundVideo.entries
                .sortedBy { it.key.wire }
                .joinToString(" ") { (source, v) -> "${source.wire}=${describe(v)}" }
            val sent = outboundVideo.entries
                .sortedBy { it.key.wire }
                .joinToString(" ") { (source, v) -> "${source.wire}=${describe(v)}" }

            CallLog.write(
                "stats ice=$ice sent=${next.bytesSent} a=$audioSent v=$videoSent recv=${next.bytesReceived} " +
                    "audioSend=${next.audioSend} videoSend=${next.videoSend} " +
                    "audioRecv=${next.audioRecv} videoRecv=${next.videoRecv} " +
                    "pair=${next.candidateLocal}->${next.candidateRemote}/${next.candidateProtocol} " +
                    "rtt=${next.rtt} loss=${next.packetLoss} bwe out=${kbps(bweOut)} in=${kbps(bweIn)}" +
                    (if (video.isEmpty()) "" else " in[$video]") +
                    (if (sent.isEmpty()) "" else " out[$sent]")
            )
        }

        listener?.onPeerStats(next)
    }

    /**
     * A video source that is expected and decodes nothing. Once it has been
     * quiet for [VIDEO_STALL_TIMEOUT_MS] the sender is asked for a fresh
     * picture; when that brings nothing for as long again, what the silence
     * is made of decides what is done about it. A source that delivers a
     * frame starts over, and one that stops being expected is forgotten
     * ([setExpectedVideo]), so a camera the other side switched off is never
     * asked for.
     */
    private fun reviewVideoSources(now: Long, inbound: Map<CallSource, InboundVideo>) {
        if (!isConnected() || connectedAt <= 0) return

        for (source in expectedVideo) {
            if (!source.isVideo) continue

            val stream = inbound[source]
            val frames = stream?.decoded ?: 0L
            val progress = videoProgress[source]

            if (progress == null) {
                videoProgress[source] = VideoProgress(frames, now, false)
                continue
            }

            // Moving, or a fresh stream counting from zero again: not a silence.
            if (frames != progress.frames) {
                progress.frames = frames
                progress.since = now
                progress.refreshed = false
                continue
            }

            val quiet = now - max(progress.since, connectedAt)

            // Every tick of a silence is written with what the stream has
            // delivered, because the numbers are the whole diagnosis: packets
            // still climbing with no frame assembled is a receiver that cannot
            // make a picture of what arrives; nothing climbing is a sender that
            // has stopped.
            if (quiet >= STATS_INTERVAL_MS) {
                trace("${source.wire} silent ${seconds(quiet)}s: ${describe(stream)} bwe in=${kbps(lastBweIn)}")
            }

            if (quiet <= VIDEO_STALL_TIMEOUT_MS) continue

            progress.since = now

            if (!progress.refreshed) {
                progress.refreshed = true
                progress.receivedAtAsk = stream?.received ?: 0L
                progress.packetsAtAsk = stream?.packets ?: 0L

                trace("no picture from ${source.wire} for ${VIDEO_STALL_TIMEOUT_MS}ms, asking for a fresh one")

                listener?.onPeerSend(JSONObject().put("kind", "refresh").put("source", source.wire))
                continue
            }

            progress.refreshed = false

            // Three kinds of silence need three different answers, and an
            // ICE restart, which every one of them used to get, mends none.
            val assembled = (stream?.received ?: 0L) - progress.receivedAtAsk
            val packets = (stream?.packets ?: 0L) - progress.packetsAtAsk
            val family = stream?.family

            if (assembled > 0 && family != null) {
                // Whole frames keep arriving and none comes out: the decoder
                // is what stopped, so the family is written off here and the
                // call offered again without it. The old predicate needed a
                // decoder that had never produced a frame, and one that had
                // decoded hundreds and then froze was left to the room.
                trace("still no picture from ${source.wire} after asking, while $assembled frames arrived whole: the ${family.removePrefix("video/")} decoder is dead here")
                onCodecBroken(family, CallCodecs.DECODE)
                continue
            }

            if (packets > 0) {
                // Packets arrive and no frame is ever whole: the path is
                // losing too much to carry a picture.
                trace("still no picture from ${source.wire} after asking: $packets packets arrived and no frame was whole, the path is losing too much")
                pathDead("the ${source.wire} arrives too broken to decode")
                continue
            }

            // Nothing arrived for this source at all while the connection is
            // up and the sender was asked twice: its capture or encoder is
            // what stopped. The sender's own watch writes off an encoder that
            // takes frames and turns out none; a capture that produces none is
            // told to open the device again.
            trace("still no picture from ${source.wire} after asking and nothing arrived for it at all: the sender's ${source.wire} stopped, not the path")

            val last = lastSilentNotice[CallSource.CAMERA] ?: 0L

            if (source == CallSource.CAMERA && now - last >= SILENT_NOTICE_MIN_MS) {
                lastSilentNotice[CallSource.CAMERA] = now
                listener?.onPeerSilent(CallSource.CAMERA)
            }
        }
    }

    // Teardown

    fun close() {
        handler.post {
            if (closed) return@post

            closed = true

            handler.removeCallbacksAndMessages(null)

            holdSpent = false
            heldPrivateCandidates.clear()
            pendingCandidates.clear()
            remoteTracks.clear()
            transceiverSenders.clear()
            sourceOfMid.clear()

            pc?.close()
            pc = null

            setConnection(CallConnection.CLOSED)

            thread.quitSafely()
        }
    }

    // The peer connection's own events

    override fun onRenegotiationNeeded() {
        handler.post {
            // This call is meant to offer once. A trace of a dropped call shows
            // it offering three times in forty seconds, the third colliding
            // with an offer from the other side — and inbound media stopping at
            // exactly that collision, never to recover: ICE went DISCONNECTED,
            // restarted, CHECKING with no pair at all, then FAILED.
            //
            // libwebrtc does not say why it wants renegotiating, so the state
            // that can only have come from this side is written down instead:
            // what the sections look like at the moment it asked.
            val shape = pc?.transceivers?.joinToString(" ") { t ->
                val mid = t.mid?.takeIf { it.isNotEmpty() } ?: "-"
                val kind = if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) "v" else "a"
                val track = if (t.sender?.track() != null) "t" else "-"

                "$mid:$kind:${t.direction}:$track"
            } ?: "no connection"

            CallLog.write(
                "renegotiation wanted: making=$makingOffer state=${pc?.signalingState()} $shape"
            )

            negotiate()
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        handler.post {
            if (closed) return@post

            val parsed = CallCandidateInfo.parse(candidate.sdp)

            if (parsed != null && parsed.type == "srflx" && parsed.address.isNotEmpty()) {
                localPublicIps.add(parsed.address)
                reviewHeldCandidates()
            }

            listener?.onPeerSend(
                JSONObject()
                    .put("kind", "candidate")
                    .put(
                        "candidate",
                        JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    )
            )
        }
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        handler.post {
            if (closed) return@post

            // From the event itself, not the stats tick two seconds later: a
            // state that comes and goes inside one tick used to leave no line.
            trace("ice ${iceName(state)}")

            when (state) {
                // The restarts are not given back here but once media has
                // flowed for [HEALTHY_RESET_MS]: a connection that comes up and
                // drops again at once is one that cannot be kept, and it used
                // to be restarted for ever.
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    everConnected = true
                    handler.removeCallbacks(connectDeadline)
                    handler.removeCallbacks(connectRelay)
                    clearRestartTimers()
                    connectedAt = System.currentTimeMillis()
                    inboundChangedAt = connectedAt
                    setConnection(CallConnection.CONNECTED)

                    CallSource.ORDER.forEach { applyWanted(it) }
                }

                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    setConnection(CallConnection.RECONNECTING)
                    scheduleRestart(DISCONNECT_GRACE_MS, "ice disconnected")
                }

                PeerConnection.IceConnectionState.FAILED -> {
                    setConnection(CallConnection.RECONNECTING)
                    scheduleRestart(0, "ice failed")
                }

                else -> {}
            }
        }
    }

    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
        if (state != PeerConnection.PeerConnectionState.FAILED) return

        handler.post {
            if (closed) return@post

            trace("connection state failed")
            setConnection(CallConnection.RECONNECTING)
            scheduleRestart(0, "connection failed")
        }
    }

    /**
     * A track arrived. Both callbacks are taken because they fire at different
     * moments — `onAddTrack` when the section is negotiated, `onTrack` when
     * media actually starts — and which one comes first depends on the other
     * side. Taking one and missing the other is a call where the picture never
     * appears.
     */
    override fun onTrack(transceiver: RtpTransceiver) {
        adopt(
            transceiver.receiver?.track(),
            transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            transceiver.mid ?: ""
        )
    }

    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
        val track = receiver.track() ?: return

        // A receiver knows nothing about its mid, so the transceiver it
        // belongs to is looked up for one. Reading this list is what disposes
        // every wrapper handed out before it, which is why nothing here holds
        // one — see [transceiverSenders].
        val mid = try {
            pc?.transceivers?.firstOrNull { it.receiver?.id() == receiver.id() }?.mid ?: ""
        } catch (e: Exception) {
            ""
        }

        adopt(track, track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND, mid)
    }

    /**
     * The source of an incoming track is the mid map's answer, or a guess by
     * kind and order: camera before screen, microphone before screen audio.
     */
    private fun adopt(track: MediaStreamTrack?, isVideo: Boolean, mid: String) {
        handler.post {
            if (closed || track == null) return@post

            val mapped = remoteMids[mid]

            val source = when {
                mapped != null && mapped.isVideo == isVideo -> mapped
                isVideo -> if (remoteTracks[CallSource.CAMERA] != null) CallSource.SCREEN_SHARE else CallSource.CAMERA
                else -> if (remoteTracks[CallSource.MICROPHONE] != null) CallSource.SCREEN_SHARE_AUDIO else CallSource.MICROPHONE
            }

            // The same track can arrive twice, once from each callback.
            if (remoteTracks[source]?.id() == track.id()) return@post

            remoteTracks[source] = track

            listener?.onPeerTrack(source, track)
        }
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState) {
        if (state == PeerConnection.SignalingState.STABLE) handler.post { noteStable() }
    }
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
}

/** The observer API predates promises; only the parts used are overridden. */
internal open class CallSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
