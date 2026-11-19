package cloud.wordplus.realtime.calls

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import livekit.org.webrtc.RTCStats
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Which sources a quality reading speaks of at a stats pass: this side's that
 * are live, switched on and not paused for the other side drawing none of
 * them, the other side's that are on and shown here, and each stats entry's
 * source as the pass itself finds it (by its mid, else by its track).
 */
internal class CallQualityScope(
    val sending: Set<CallSource>,
    val playing: Set<CallSource>,
    val outbound: (Map<String, Any>) -> CallSource?,
    val inbound: (Map<String, Any>) -> CallSource?
)

/**
 * How a direct call is going at this side, told to the relay on one pulse in
 * every few (the pulse reply's `qms`, 10 s by default, 0 for none: a relay
 * before it asks none and gets none), as the browser's engine tells it
 * (@wordplus/realtime-client 1.0.77, calls/quality.ts) and the Swift SDK's
 * (CallQuality.swift): what this side sends of each source, what it plays
 * of each source the other side sends, its connection, and its machine
 * ([CallHealthWatch]). Numbers only. The relay keeps the latest reading of
 * each side for the operator's live view of calls.
 *
 * Nothing here reads stats of its own: the peer hands every stats pass it
 * makes anyway to [tick], on its own thread, and the engine takes a [reading]
 * from its: both hold the lock.
 */
internal class CallQualityWatch {
    class Out(val kind: String) {
        var bytes = 0.0; var packets = 0.0
        var frames: Double? = null; var produced: Double? = null; var capturedFps: Double? = null; var height: Double? = null
        var limit: String? = null; var cpuS: Double? = null; var bwS: Double? = null; var hw: Boolean? = null
        var sendDelayS: Double? = null; var nack: Double? = null; var pli: Double? = null; var remoteLost: Double? = null
        var codec: String? = null; var encoder: String? = null; var encodeS: Double? = null; var encoded: Double? = null; var framesSent: Double? = null; var keyFrames: Double? = null
        var target: Double? = null; var resent: Double? = null; var huge: Double? = null; var captureWidth: Double? = null; var captureHeight: Double? = null
    }

    class In(val kind: String) {
        var bytes = 0.0; var packets = 0.0; var lost = 0.0
        var frames: Double? = null; var height: Double? = null; var freezes: Double? = null; var freezeS: Double? = null; var dropped: Double? = null
        var jbDelayS: Double? = null; var jbEmitted: Double? = null; var samples: Double? = null; var concealed: Double? = null; var hw: Boolean? = null
        var codec: String? = null; var decoder: String? = null; var decodeS: Double? = null
        /** The gaps between frames shown, their sum and the sum of their squares, s. */
        var gaps: Double? = null; var gapsSquared: Double? = null
        var pauses: Double? = null; var pauseS: Double? = null; var plis: Double? = null; var nacks: Double? = null; var stretched: Double? = null
        /** The network's jitter now, s: not a total. */
        var jitter: Double? = null
    }

    class Path(
        val rtt: Double? = null, val out: Double? = null, val inbound: Double? = null,
        val sendFailed: Double? = null, val asked: Double? = null, val answered: Double? = null, val switched: Double? = null
    )

    class Totals(val at: Double) {
        val out = mutableMapOf<CallSource, Out>()
        val inbound = mutableMapOf<CallSource, In>()
        var path = Path()
    }

    private var base: Totals? = null
    private var latest: Totals? = null
    private var sending: Set<CallSource> = emptySet()
    private var playing: Set<CallSource> = emptySet()
    private val produced = mutableMapOf<CallSource, Pair<Double, Double>>()
    private val stalls = mutableMapOf<CallSource, Double>()
    /** When this side last started showing each source again after not showing it. */
    private val shownAgain = mutableMapOf<CallSource, Double>()

    companion object {
        /**
         * How long after this side shows a source again its first frame may still
         * come, ending the gap this side made: the other side starts sending it
         * again and its picture needs a key frame. A frame landing after a reading
         * is the next one's.
         */
        private const val RESUME_MS = 3000.0

        /**
         * Encoders that run on the CPU, by the name WebRTC gives; any other it names
         * is the device's. The name decides where there is one and
         * `powerEfficientEncoder` where there isn't, as a group call's client reads them.
         */
        private val SOFTWARE_ENCODER = Regex("^(libvpx|openh264|libaom|svt|ffmpeg|libx26|dav1d|rav1e|fake)", RegexOption.IGNORE_CASE)

        fun encoderHardware(name: String?, powerEfficient: Boolean?): Boolean? =
            if (name != null) !SOFTWARE_ENCODER.containsMatchIn(name) else powerEfficient

        private fun word(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }

        /** A stats entry's codec by its short name: `video/H264` as "h264". */
        private fun codecWord(entry: Map<String, Any>?): String? = word(entry?.get("mimeType"))?.substringAfterLast('/')?.lowercase()

        private fun num(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

        private fun add(a: Double?, b: Double?): Double? = if (a == null) b else if (b == null) a else a + b

        private fun delta(a: Double?, b: Double?): Double? = if (a != null && b != null && b >= a) b - a else null

        private fun round1(v: Double): Double = Math.round(v * 10) / 10.0

        /** One stats pass's running totals, by source, and the selected path. */
        fun totals(entries: Map<String, RTCStats>, scope: CallQualityScope, at: Double): Totals {
            val totals = Totals(at)
            var transport: Map<String, Any>? = null
            var pair: Map<String, Any>? = null

            for ((_, entry) in entries) {
                val v = entry.members

                when (entry.type) {
                    "outbound-rtp" -> {
                        val kind = (v["kind"] ?: v["mediaType"]) as? String
                        if (kind != "audio" && kind != "video") continue
                        val source = scope.outbound(v) ?: continue
                        val capture = (v["mediaSourceId"] as? String)?.let { entries[it]?.members }
                        val remote = (v["remoteId"] as? String)?.let { entries[it]?.members }
                        val durations = v["qualityLimitationDurations"] as? Map<*, *>
                        val encoder = word(v["encoderImplementation"])
                        val o = totals.out.getOrPut(source) { Out(kind) }

                        o.bytes += num(v["bytesSent"]) ?: 0.0
                        o.packets += num(v["packetsSent"]) ?: 0.0
                        o.frames = add(o.frames, num(v["framesSent"]) ?: num(v["framesEncoded"]))
                        o.produced = o.produced ?: num(capture?.get("frames"))
                        o.capturedFps = o.capturedFps ?: num(capture?.get("framesPerSecond"))
                        o.height = max(o.height ?: 0.0, num(v["frameHeight"]) ?: 0.0).takeIf { it > 0 }
                        o.limit = (v["qualityLimitationReason"] as? String) ?: o.limit
                        o.cpuS = add(o.cpuS, num(durations?.get("cpu")))
                        o.bwS = add(o.bwS, num(durations?.get("bandwidth")))
                        o.hw = encoderHardware(encoder, v["powerEfficientEncoder"] as? Boolean) ?: o.hw
                        o.sendDelayS = add(o.sendDelayS, num(v["totalPacketSendDelay"]))
                        o.nack = add(o.nack, num(v["nackCount"]))
                        o.pli = add(add(o.pli, num(v["pliCount"])), num(v["firCount"]))
                        o.remoteLost = add(o.remoteLost, remote?.let { max(0.0, num(it["packetsLost"]) ?: 0.0) })
                        o.codec = codecWord((v["codecId"] as? String)?.let { entries[it]?.members }) ?: o.codec
                        o.encoder = encoder ?: o.encoder
                        o.encodeS = add(o.encodeS, num(v["totalEncodeTime"]))
                        o.encoded = add(o.encoded, num(v["framesEncoded"]))
                        o.framesSent = add(o.framesSent, num(v["framesSent"]))
                        o.keyFrames = add(o.keyFrames, num(v["keyFramesEncoded"]))
                        o.target = add(o.target, num(v["targetBitrate"]))
                        o.resent = add(o.resent, num(v["retransmittedPacketsSent"]))
                        o.huge = add(o.huge, num(v["hugeFramesSent"]))
                        o.captureWidth = o.captureWidth ?: num(capture?.get("width"))
                        o.captureHeight = o.captureHeight ?: num(capture?.get("height"))
                    }

                    "inbound-rtp" -> {
                        val kind = (v["kind"] ?: v["mediaType"]) as? String
                        if (kind != "audio" && kind != "video") continue
                        val source = scope.inbound(v) ?: continue
                        val i = totals.inbound.getOrPut(source) { In(kind) }

                        i.bytes += num(v["bytesReceived"]) ?: 0.0
                        i.packets += num(v["packetsReceived"]) ?: 0.0
                        i.lost += max(0.0, num(v["packetsLost"]) ?: 0.0)
                        i.frames = add(i.frames, num(v["framesDecoded"]))
                        i.height = max(i.height ?: 0.0, num(v["frameHeight"]) ?: 0.0).takeIf { it > 0 }
                        i.freezes = add(i.freezes, num(v["freezeCount"]))
                        i.freezeS = add(i.freezeS, num(v["totalFreezesDuration"]))
                        i.dropped = add(i.dropped, num(v["framesDropped"]))
                        i.jbDelayS = add(i.jbDelayS, num(v["jitterBufferDelay"]))
                        i.jbEmitted = add(i.jbEmitted, num(v["jitterBufferEmittedCount"]))
                        i.samples = add(i.samples, num(v["totalSamplesReceived"]))
                        // made up while the other side was silent costs nothing anyone hears
                        num(v["concealedSamples"])?.let { i.concealed = add(i.concealed, it - (num(v["silentConcealedSamples"]) ?: 0.0)) }
                        i.hw = (v["powerEfficientDecoder"] as? Boolean) ?: i.hw
                        i.codec = codecWord((v["codecId"] as? String)?.let { entries[it]?.members }) ?: i.codec
                        i.decoder = word(v["decoderImplementation"]) ?: i.decoder
                        i.decodeS = add(i.decodeS, num(v["totalDecodeTime"]))
                        i.gaps = add(i.gaps, num(v["totalInterFrameDelay"]))
                        i.gapsSquared = add(i.gapsSquared, num(v["totalSquaredInterFrameDelay"]))
                        i.pauses = add(i.pauses, num(v["pauseCount"]))
                        i.pauseS = add(i.pauseS, num(v["totalPausesDuration"]))
                        i.plis = add(i.plis, num(v["pliCount"]))
                        i.nacks = add(i.nacks, num(v["nackCount"]))
                        i.stretched = add(i.stretched, add(num(v["insertedSamplesForDeceleration"]), num(v["removedSamplesForAcceleration"])))
                        i.jitter = num(v["jitter"]) ?: i.jitter
                    }

                    "transport" -> if (transport == null && v["selectedCandidatePairId"] is String) transport = v

                    "candidate-pair" -> {
                        val selected = v["selected"] as? Boolean ?: false
                        val nominated = v["nominated"] as? Boolean ?: false

                        if (pair == null && v["state"] == "succeeded" && (selected || nominated)) pair = v
                    }
                }
            }

            val selected = (transport?.get("selectedCandidatePairId") as? String)?.let { entries[it]?.members } ?: pair

            if (selected != null) {
                totals.path = Path(
                    rtt = num(selected["currentRoundTripTime"])?.let { Math.round(it * 1000).toDouble() },
                    out = num(selected["availableOutgoingBitrate"])?.let { Math.round(it / 1000).toDouble() },
                    inbound = num(selected["availableIncomingBitrate"])?.let { Math.round(it / 1000).toDouble() },
                    sendFailed = num(selected["packetsDiscardedOnSend"]),
                    asked = num(selected["consentRequestsSent"]) ?: num(selected["requestsSent"]),
                    answered = num(selected["responsesReceived"]),
                    switched = num(transport?.get("selectedCandidatePairChanges"))
                )
            }

            return totals
        }

        fun between(prev: Totals, now: Totals, sending: Set<CallSource>, playing: Set<CallSource>, stalls: Map<CallSource, Double>, shownAgain: Map<CallSource, Double> = emptyMap()): JSONObject {
            val ms = max(0.0, Math.round(now.at - prev.at).toDouble())
            val seconds = ms / 1000
            val reading = JSONObject().put("ms", ms.toLong())
            val sent = JSONArray()
            val played = JSONArray()

            now.path.rtt?.let { reading.put("rtt", it) }
            now.path.out?.let { reading.put("out", it) }
            now.path.inbound?.let { reading.put("in", it) }

            var upSent = 0.0
            var upLost = 0.0

            for (source in CallSource.ORDER) {
                val n = now.out[source] ?: continue
                val p = prev.out[source] ?: continue
                if (seconds <= 0) continue
                val packets = delta(p.packets, n.packets)
                val lost = delta(p.remoteLost, n.remoteLost)

                if (packets != null && lost != null) {
                    upSent += packets
                    upLost += lost
                }

                if (source !in sending) continue

                val t = JSONObject().put("kind", n.kind).put("source", source.wire)

                delta(p.bytes, n.bytes)?.let { t.put("kbps", Math.round(it * 8 / seconds / 1000)) }
                n.codec?.let { t.put("codec", it) }
                val resent = delta(p.resent, n.resent)
                if (resent != null && resent > 0 && packets != null && packets > 0) t.put("resent", round1(min(100.0, resent * 100 / packets)))

                if (n.kind == "video") {
                    n.encoder?.let { t.put("encoder", it) }
                    val encoded = delta(p.encoded, n.encoded)
                    val encodeS = delta(p.encodeS, n.encodeS)
                    if (encoded != null && encoded > 0 && encodeS != null) t.put("encodeMs", round1(encodeS * 1000 / encoded))
                    encoded?.let { t.put("encoded", it) }
                    delta(p.framesSent, n.framesSent)?.let { t.put("framesSent", it) }
                    delta(p.keyFrames, n.keyFrames)?.let { t.put("keyFrames", it) }
                    n.target?.takeIf { it > 0 }?.let { t.put("target", Math.round(it / 1000)) }
                    delta(p.huge, n.huge)?.takeIf { it > 0 }?.let { t.put("huge", it) }

                    // The size its capture gives, as its stats say. No frames a second:
                    // a mode's are what the capture was opened at, and these stats say
                    // only what it gives, which a dim room halves.
                    val width = n.captureWidth
                    val height = n.captureHeight
                    if (width != null && height != null && width > 0 && height > 0) {
                        t.put("mode", JSONObject().put("width", width).put("height", height))
                    }

                    delta(p.frames, n.frames)?.let { t.put("fps", round1(it / seconds)) }
                    val made = delta(p.produced, n.produced)
                    if (made != null) t.put("captured", round1(made / seconds)) else n.capturedFps?.let { t.put("captured", round1(it)) }
                    n.height?.let { t.put("height", it) }
                    n.limit?.let { t.put("limit", it) }
                    delta(p.cpuS, n.cpuS)?.let { t.put("cpuMs", Math.round(it * 1000)) }
                    delta(p.bwS, n.bwS)?.let { t.put("bwMs", Math.round(it * 1000)) }
                    n.hw?.let { t.put("hw", it) }
                    stalls[source]?.let { t.put("stallMs", it.roundToLong()) }
                    delta(p.pli, n.pli)?.let { t.put("pli", it) }
                }

                val delay = delta(p.sendDelayS, n.sendDelayS)
                if (delay != null && packets != null && packets > 0) t.put("queueMs", round1(delay * 1000 / packets))
                delta(p.nack, n.nack)?.let { t.put("nack", it) }
                if (packets != null && packets > 0 && lost != null) t.put("lost", round1(min(100.0, lost * 100 / packets)))

                sent.put(t)
            }

            if (upSent > 0) reading.put("up", round1(min(100.0, upLost * 100 / upSent)))

            for (source in CallSource.ORDER) {
                val n = now.inbound[source] ?: continue
                val p = prev.inbound[source] ?: continue
                if (seconds <= 0 || source !in playing) continue

                val t = JSONObject().put("kind", n.kind).put("source", source.wire)

                delta(p.bytes, n.bytes)?.let { t.put("kbps", Math.round(it * 8 / seconds / 1000)) }

                val received = delta(p.packets, n.packets)
                val lost = delta(p.lost, n.lost)
                if (received != null && lost != null && received + lost > 0) t.put("lost", round1(lost * 100 / (received + lost)))
                n.codec?.let { t.put("codec", it) }

                val jbDelay = delta(p.jbDelayS, n.jbDelayS)
                val jbEmitted = delta(p.jbEmitted, n.jbEmitted)
                if (jbDelay != null && jbEmitted != null && jbEmitted > 0) t.put("jbMs", Math.round(jbDelay * 1000 / jbEmitted))

                if (n.kind == "video") {
                    val frames = delta(p.frames, n.frames)
                    frames?.let { t.put("fps", round1(it / seconds)) }
                    n.height?.let { t.put("height", it) }

                    var freezes = delta(p.freezes, n.freezes)
                    var frozen = delta(p.freezeS, n.freezeS)
                    val pauses = delta(p.pauses, n.pauses)
                    val paused = delta(p.pauseS, n.pauseS)
                    // A gap this side made itself, not showing the source (the other
                    // side's camera off until now, or a call screen not drawing it),
                    // isn't the call's: a source shown again since just before the
                    // last reading owns the pauses ending in this one, or the one
                    // freeze its gap came out as when too short for a pause.
                    val resumed = shownAgain[source]?.let { it > prev.at - RESUME_MS } ?: false
                    var hidden: Pair<Double, Double?>? = null

                    if (resumed && pauses != null && pauses > 0) {
                        hidden = pauses to paused
                    } else if (resumed && freezes == 1.0) {
                        hidden = 1.0 to frozen
                        freezes = 0.0
                        frozen = 0.0
                    }

                    hidden?.let { gap ->
                        t.put("hiddenGaps", gap.first)
                        gap.second?.let { t.put("hiddenGapMs", Math.round(it * 1000)) }
                    }

                    freezes?.let {
                        t.put("freezes", it)
                        frozen?.let { s -> t.put("freezeMs", Math.round(s * 1000)) }
                    }

                    delta(p.dropped, n.dropped)?.let { t.put("dropped", it) }
                    n.hw?.let { t.put("hw", it) }
                    n.decoder?.let { t.put("decoder", it) }
                    val decodeS = delta(p.decodeS, n.decodeS)
                    if (frames != null && frames > 0 && decodeS != null) t.put("decodeMs", round1(decodeS * 1000 / frames))

                    // WebRTC adds every gap to both sums, a pause's and a freeze's too,
                    // as its very length, so one hidden gap comes out exactly; several,
                    // or one no total tells, leave the spread unsaid this reading.
                    var gaps = delta(p.gaps, n.gaps)
                    var squared = delta(p.gapsSquared, n.gapsSquared)
                    var shown = frames

                    if (resumed) {
                        val gap = hidden?.takeIf { it.first == 1.0 }?.second
                        val sum = gaps
                        val sumSquared = squared

                        if (gap != null && frames != null && frames > 0 && sum != null && sumSquared != null) {
                            gaps = sum - gap
                            squared = sumSquared - gap * gap
                            shown = frames - 1
                        } else {
                            gaps = null
                        }
                    }

                    val count = shown
                    val sum = gaps
                    val sumSquared = squared

                    if (count != null && count > 1 && sum != null && sumSquared != null) {
                        val mean = sum / count

                        t.put("unevenMs", Math.round(sqrt(max(0.0, sumSquared / count - mean * mean)) * 1000))
                    }

                    if (pauses != null && pauses > 0 && hidden == null) {
                        t.put("pauses", pauses)
                        paused?.let { t.put("pauseMs", Math.round(it * 1000)) }
                    }

                    delta(p.plis, n.plis)?.takeIf { it > 0 }?.let { t.put("plis", it) }
                    delta(p.nacks, n.nacks)?.takeIf { it > 0 }?.let { t.put("nacks", it) }
                } else {
                    val samples = delta(p.samples, n.samples)
                    val concealed = delta(p.concealed, n.concealed)
                    val stretched = delta(p.stretched, n.stretched)
                    if (samples != null && samples > 0 && concealed != null) t.put("concealed", round1(min(100.0, concealed * 100 / samples)))
                    if (samples != null && samples > 0 && stretched != null && stretched > 0) t.put("stretched", round1(min(100.0, stretched * 100 / samples)))
                }

                n.jitter?.takeIf { it >= 0.001 }?.let { t.put("jitterMs", Math.round(it * 1000)) }

                played.put(t)
            }

            return reading.put("sent", sent).put("played", played)
        }

        /** Sends that failed, checks left unanswered and switches of path. */
        fun pathBetween(prev: Totals, now: Totals): JSONObject? {
            val out = JSONObject()

            delta(prev.path.sendFailed, now.path.sendFailed)?.takeIf { it > 0 }?.let { out.put("sendFailed", it) }
            val asked = delta(prev.path.asked, now.path.asked)
            val answered = delta(prev.path.answered, now.path.answered)
            if (asked != null && answered != null && asked > answered) out.put("unanswered", asked - answered)
            delta(prev.path.switched, now.path.switched)?.takeIf { it > 0 }?.let { out.put("switched", it) }

            return if (out.length() > 0) out else null
        }
    }

    @Synchronized
    fun tick(entries: Map<String, RTCStats>, scope: CallQualityScope, at: Double) {
        note(totals(entries, scope, at), scope, at)
    }

    /** One pass's totals: what it changes of what the next reading says. */
    @Synchronized
    fun note(totals: Totals, scope: CallQualityScope, at: Double) {
        if (latest != null) {
            for (source in scope.playing) if (source !in playing) shownAgain[source] = at
        }

        latest = totals
        sending = scope.sending
        playing = scope.playing

        if (base == null) base = totals

        for ((source, out) in totals.out) {
            if (out.kind != "video") continue
            val frames = out.produced ?: continue
            val last = produced[source]

            produced[source] = frames to at

            if (last == null || source !in scope.sending) continue

            if (frames == last.first) {
                stalls[source] = (stalls[source] ?: 0.0) + (at - last.second)
            } else if (source !in stalls) {
                stalls[source] = 0.0
            }
        }
    }

    /** What changed since the last reading, the window started over; null before a pass has come after the first. */
    @Synchronized
    fun reading(health: JSONObject?): JSONObject? {
        val b = base ?: return null
        val l = latest ?: return null
        if (l.at <= b.at) return null

        val reading = between(b, l, sending, playing, stalls, shownAgain)
        val h = health ?: JSONObject()

        pathBetween(b, l)?.let { h.put("path", it) }
        if (h.length() > 0) reading.put("health", h)

        base = l
        stalls.clear()

        return reading
    }
}

/**
 * How the phone running a direct call is doing, for its quality readings, in
 * the words a browser's engine uses (calls/health.ts): Android's thermal
 * status for the CPU pressure a browser reports (none nominal, light fair,
 * moderate serious, severe and past it critical: how hard the system is
 * holding the device back), the time the app had no activity started, a
 * battery, audio devices plugged in or out, and the machine. Each from a
 * callback the system makes anyway; nothing runs a timer. Started once a
 * relay asks for readings, on the main thread.
 */
internal class CallHealthWatch(private val context: Context) {
    private val rank = mapOf("nominal" to 0, "fair" to 1, "serious" to 2, "critical" to 3)
    private val main = Handler(Looper.getMainLooper())

    private var worst: String? = null
    private var pressure: String? = null
    private var pressureAt = 0.0
    private var cpuMs = 0.0
    private var hiddenMs = 0.0
    private var hiddenAt: Double? = null
    private var devices = 0
    private var startedActivities = 0
    private val stops = mutableListOf<() -> Unit>()
    private var running = false

    private val machine: JSONObject by lazy { describeMachine() }

    val started: Boolean
        get() = running

    private fun now(): Double = System.currentTimeMillis().toDouble()

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "nominal"
        PowerManager.THERMAL_STATUS_LIGHT -> "fair"
        PowerManager.THERMAL_STATUS_MODERATE -> "serious"
        else -> "critical"
    }

    fun start() {
        if (running) return

        val at = now()

        worst = null
        pressure = null
        pressureAt = at
        cpuMs = 0.0
        hiddenMs = 0.0
        hiddenAt = null
        devices = 0

        // the device's thermal status (Android 10 and later)
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        if (power != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pressureChanged(thermalName(power.currentThermalStatus), at)

            val listener = PowerManager.OnThermalStatusChangedListener { status -> pressureChanged(thermalName(status), now()) }

            power.addThermalStatusListener(context.mainExecutor, listener)
            stops.add { power.removeThermalStatusListener(listener) }
        }

        // the app in the background: none of its activities started
        val app = context.applicationContext as? Application

        if (app != null) {
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities++
                    visibilityChanged(false, now())
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = max(0, startedActivities - 1)
                    if (startedActivities == 0) visibilityChanged(true, now())
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }

            app.registerActivityLifecycleCallbacks(callbacks)
            stops.add { app.unregisterActivityLifecycleCallbacks(callbacks) }
        }

        // audio devices plugged in or out; the first call names the devices already there
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (audio != null) {
            var primed = false
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    if (primed) devices += added.size
                    primed = true
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    devices += removed.size
                }
            }

            audio.registerAudioDeviceCallback(callback, main)
            stops.add { audio.unregisterAudioDeviceCallback(callback) }
        }

        running = true
    }

    fun stop() {
        stops.forEach { it() }
        stops.clear()
        running = false
    }

    private fun pressureChanged(state: String, at: Double) {
        val r = rank[state] ?: return

        accountPressure(at)
        pressure = state

        if (worst == null || r > (rank[worst] ?: 0)) worst = state
    }

    private fun accountPressure(at: Double) {
        val p = pressure

        if (p != null && (rank[p] ?: 0) >= 2) cpuMs += at - pressureAt

        pressureAt = at
    }

    private fun visibilityChanged(hidden: Boolean, at: Double) {
        val since = hiddenAt

        if (hidden && since == null) {
            hiddenAt = at
        } else if (!hidden && since != null) {
            hiddenMs += at - since
            hiddenAt = null
        }
    }

    /** What happened since the last reading, and the counters started over. */
    fun reading(at: Double): JSONObject? {
        if (!started) return null

        accountPressure(at)

        val out = JSONObject().put("machine", machine)

        worst?.takeIf { it != "nominal" }?.let { out.put("cpu", it) }
        if (cpuMs > 0) out.put("cpuMs", cpuMs.roundToLong())

        val hidden = hiddenMs + (hiddenAt?.let { at - it } ?: 0.0)

        if (hidden > 0) out.put("hiddenMs", hidden.roundToLong())

        // the battery's last broadcast, which the system keeps: no receiver needed
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            out.put("battery", it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0)
        }

        if (devices > 0) out.put("devices", devices)

        // what carries on starts the next reading as it is now
        worst = pressure
        cpuMs = 0.0
        hiddenMs = 0.0
        hiddenAt = if (hiddenAt == null) null else at
        devices = 0

        return out
    }

    /** Its cores, its memory rounded to whole gigabytes, and its chip where Android names it. */
    private fun describeMachine(): JSONObject {
        val out = JSONObject().put("cores", Runtime.getRuntime().availableProcessors())
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        if (activity != null) {
            val memory = ActivityManager.MemoryInfo()

            activity.getMemoryInfo(memory)
            out.put("memGb", Math.round(memory.totalMem / 1_073_741_824.0))
        }

        // the system on a chip, its graphics in it (Android 12 and later)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")

            if (soc.isNotEmpty()) out.put("gpu", soc.take(60))
        }

        return out
    }
}

/**
 * What else a group call's client tells its node on a join, for a direct call's
 * report, as the browser's engine says it (calls/session.ts): this module and its
 * version, the kind of device, the network, and the video codecs it encodes and
 * decodes in hardware.
 */
internal object CallClientInfo {
    /** By the engine's own probe, once: the report comes every 20 s, and what the device does in hardware stays what it is. */
    @Volatile
    private var hardware: Pair<String, String>? = null

    fun putInto(report: JSONObject, context: Context, media: CallMedia) {
        val hw = hardware ?: media.codecCaps.let { hardwareFamilies(it, true) to hardwareFamilies(it, false) }.also { hardware = it }

        report.put("lib", "realtime-kotlin " + GroupShapes.VERSION)
        report.put("device", if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone")
        network(context)?.let { report.put("network", it) }
        if (hw.first.isNotEmpty()) report.put("hwEnc", hw.first)
        if (hw.second.isNotEmpty()) report.put("hwDec", hw.second)
    }

    /** The families a probe says run in hardware, as short names ("h264,vp9"). */
    fun hardwareFamilies(caps: CallCodecCaps, encoding: Boolean): String =
        CallCodecs.FAMILIES.filter { family ->
            val pair = caps.families[family]
            val score = if (encoding) pair?.first else pair?.second

            score != null && score.ok && score.hw
        }.joinToString(",") { it.removePrefix("video/").lowercase() }

    /** The network it is on, by the system's word on it; null on anything else. This module's manifest asks for the permission. */
    @SuppressLint("MissingPermission")
    private fun network(context: Context): String? = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)

        when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> null
        }
    } catch (e: SecurityException) {
        null
    }
}
