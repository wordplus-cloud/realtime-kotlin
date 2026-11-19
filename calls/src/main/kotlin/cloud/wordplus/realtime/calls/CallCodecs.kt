package cloud.wordplus.realtime.calls

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import io.livekit.android.room.MeshHardwareDrops
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpCapabilities
import livekit.org.webrtc.RtpTransceiver
import org.json.JSONObject

/**
 * Which video codec each direction of a direct call uses.
 *
 * libwebrtc's own order is a legacy one — VP8 first, software everywhere — and
 * the answerer follows the offerer, so a call lands on VP8 unless somebody
 * says otherwise. Each side says what it can encode and decode and whether
 * that is hardware, and the two orders are arranged from that.
 *
 * The scoring is the web's, number for number, because the two sides have to
 * agree about a call they are both in. What differs is where the answers come
 * from: a browser asks the Media Capabilities API, and this asks MediaCodec.
 */
internal data class CallCodecScore(
    val ok: Boolean,
    val hw: Boolean,
    val smooth: Boolean,
    val fast: Boolean
) {
    fun json(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("hw", hw)
        .put("smooth", smooth)
        .put("fast", fast)

    companion object {
        val UNKNOWN = CallCodecScore(ok = true, hw = false, smooth = true, fast = false)
        val NONE = CallCodecScore(ok = false, hw = false, smooth = false, fast = false)

        fun from(value: JSONObject?): CallCodecScore {
            if (value == null) return NONE

            return CallCodecScore(
                ok = value.optBoolean("ok", false),
                hw = value.optBoolean("hw", false),
                smooth = value.optBoolean("smooth", false),
                fast = value.optBoolean("fast", false)
            )
        }
    }
}

internal data class CallCodecCaps(
    val families: Map<String, Pair<CallCodecScore, CallCodecScore>>,
    /**
     * Whether a sender can be moved to another codec after negotiation.
     * `RtpParameters.Encoding` has no `codec` field in M144 — checked against
     * the artifact — so this is always false and the protocol arranges the SDP
     * order for the side that cannot switch.
     */
    val canSwitch: Boolean = false,
    /**
     * Which engine answered the probe: `native` here, `chromium`, `gecko` or
     * `webkit` from a browser. A codec an engine vouches for can still fail
     * in it — Chrome's H265 did, on both ends, in every camera stall the
     * production traces of 19 to 21 Sep 2026 held — and the ranking holds
     * that against it. Null from a peer older than the field, which is then
     * not held to anything.
     */
    val engine: String? = null
) {
    fun json(): JSONObject {
        val map = JSONObject()

        families.forEach { (family, pair) ->
            map.put(family, JSONObject().put("enc", pair.first.json()).put("dec", pair.second.json()))
        }

        val result = JSONObject().put("families", map).put("switch", canSwitch)

        engine?.let { result.put("engine", it) }

        return result
    }

    companion object {
        fun from(value: JSONObject?): CallCodecCaps? {
            val families = value?.optJSONObject("families") ?: return null

            val parsed = mutableMapOf<String, Pair<CallCodecScore, CallCodecScore>>()

            families.keys().forEach { key ->
                val name = key as? String ?: return@forEach
                val pair = families.optJSONObject(name) ?: return@forEach

                parsed[name] = Pair(
                    CallCodecScore.from(pair.optJSONObject("enc")),
                    CallCodecScore.from(pair.optJSONObject("dec"))
                )
            }

            return CallCodecCaps(parsed, value.optBoolean("switch", false), value.optString("engine").ifEmpty { null })
        }
    }
}

internal object CallCodecs {
    val FAMILIES = listOf("video/H265", "video/H264", "video/VP9", "video/VP8", "video/AV1")

    /**
     * How long a codec whose hardware dropped a camera's frames stays out, as the media server's settings say it
     * (`tune.rememberDropsDays`, `tune.forgetBefore`): the last group call's, set by [GroupCall]; the room client's month
     * where none came.
     */
    @Volatile
    var dropSettings: io.livekit.android.room.MeshSettings = io.livekit.android.room.MeshSettings.NONE

    private val MIME = mapOf(
        "video/H265" to "video/hevc",
        "video/H264" to "video/avc",
        "video/VP9" to "video/x-vnd.on2.vp9",
        "video/VP8" to "video/x-vnd.on2.vp8",
        "video/AV1" to "video/av01"
    )

    /**
     * Newer first among hardware matches, cheaper first among software ones.
     * VP8 is last: software everywhere and the weakest at 1080p.
     */
    private val TIE = mapOf(
        "video/H265" to 0.5,
        "video/H264" to 0.4,
        "video/VP9" to 0.3,
        "video/AV1" to 0.2,
        "video/VP8" to 0.1
    )

    /**
     * A screen share is mostly text, and hardware encoders are tuned for
     * camera pictures: at the same bitrate they smear glyphs the software
     * codecs with screen-content tools keep. So the screen ranks by that, and
     * hardware counts only on the decoding end.
     */
    private val SCREEN_CONTENT = mapOf(
        "video/AV1" to 6.0,
        "video/VP9" to 5.0,
        "video/H265" to 3.0,
        "video/H264" to 2.0,
        "video/VP8" to 0.0
    )

    /**
     * A codec both sides agreed on can still carry nothing.
     *
     * Chrome on a Mac answers `mediaCapabilities.decodingInfo` for H265 with
     * "supported, power efficient" — VideoToolbox does decode HEVC — and then
     * its WebRTC receive path hands the stream to a decoder that throws every
     * frame away. Measured here: 29059 packets and 33 MB arrived with nothing
     * lost, `framesDecoded` stayed at zero, and the far side sat on a 0x0
     * video element for a whole call. No capability answer says so.
     *
     * So the device remembers what has failed it and stops asking for it, in
     * the failing direction only: a codec this phone cannot decode may still
     * be the right thing for it to send. VP8 is never written off — it is the
     * one video codec every WebRTC implementation must have, so it is where a
     * downgrade lands when there is nothing else left.
     */
    /**
     * H265 used to be kept out of this side's sections altogether.
     *
     * On libwebrtc M137 the hardware HEVC encoder every phone here advertises
     * produced a stream nothing could read: every frame carried a bogus
     * one-byte NAL unit ahead of the real one
     * (`00 00 00 01 03 | 00 00 00 01 40 01 ...` where the VPS should start the
     * access unit), Chrome's VideoToolbox decoder refused it and substituted
     * `NullVideoDecoder`, and 29059 packets and 33 MB arrived with nothing
     * lost, `framesDecoded` at zero and a 0x0 video element for a whole call.
     * The same send path also segfaulted libwebrtc's worker thread six times
     * in one afternoon with `OMX.qcom.video.encoder.hevc` configured.
     *
     * Upstream fixed it. On `io.livekit:livekit-android:2.28.2`, which brings
     * `io.github.webrtc-sdk:android-prefixed:144.7559.14` — the same milestone
     * the iOS app has been running H265 on all along — the phone encodes H265
     * through that same `OMX.qcom.video.encoder.hevc` and Chrome decodes it in
     * VideoToolbox: first frame about a second in, 1080x1920, nothing written
     * off, across repeated calls and no crash.
     *
     * The exclusion never worked as written anyway — the phone went on sending
     * H265 through it, which is what made the old failure so hard to read. Now
     * that what it sends can be decoded, the family belongs in the sections,
     * and the win is the other direction: Chrome can answer H265 from
     * VideoToolbox instead of software H264, which this phone decodes in
     * hardware.
     */
    private const val BROKEN_PREFS = "bm_calls_codecs"
    private const val BROKEN_KEY = "broken"
    private const val BASELINE = "video/VP8"

    const val ENCODE = "enc"
    const val DECODE = "dec"

    /** The engine this device runs, for the ranking on both ends. */
    const val OWN_ENGINE = "native"

    /**
     * Whether a family is held back in a direction because of what runs it.
     *
     * Chrome's H265 is hardware on paper and failed in practice on both ends
     * — a decoder on Windows and one on Android that decoded a burst after
     * each keyframe and then nothing, an encoder on Android that produced no
     * frame after a restart — in every camera stall the production traces of
     * 19 to 21 Sep 2026 held. Firefox has no H265 at all, but its answer to
     * the probe is not trusted either. Apple's is, and so is this app's, so a
     * call between the two keeps it. An engine that did not say what it is
     * is not held to anything.
     */
    private fun heldBack(family: String, sender: CallCodecCaps, receiver: CallCodecCaps?): Boolean {
        if (family != "video/H265") return false

        return listOf(sender.engine, receiver?.engine).any { it == "chromium" || it == "gecko" }
    }

    /**
     * What a call learns about a codec that carries nothing belongs to that
     * call and dies with it.
     *
     * It used to persist, and that was worse than the problem it solved: a
     * write-off earned against one peer's broken build then applied to every
     * later peer forever. Measured on the web side, which had the same store —
     * Chrome carried H265:dec away from an app call, and Chrome to Safari,
     * hardware H265 at both ends, fell to H264 on a software encoder and
     * stayed there. Clearing the entry put it straight back to VideoToolbox.
     * Every call now starts from what the hardware can do and finds out for
     * itself.
     */
    private var broken: MutableMap<String, MutableSet<String>> = mutableMapOf()

    private var cached: CallCodecCaps? = null

    private fun shortName(family: String): String = family.removePrefix("video/")

    private var loggedShapes = false

    /**
     * What the two capability lists actually carry, once per process.
     *
     * The ordering below is applied from the *receiver* list, and the caller's
     * first offer went out advertising `H265 (no fmtp)` — no level, no
     * tx-mode. An H265 payload with no level is not one a sender can be
     * configured for, so the call fell down the list to something that could.
     * This says whether the sender list carries what the receiver list is
     * missing, which is the difference between that being the cause and it
     * merely being true.
     */
    private fun logCapabilityShapes(factory: PeerConnectionFactory) {
        if (loggedShapes) return

        loggedShapes = true

        val describe = { label: String, codecs: List<RtpCapabilities.CodecCapability>? ->
            val shown = codecs.orEmpty()
                .filter { codec -> FAMILIES.any { codec.mimeType?.equals(it, ignoreCase = true) == true } }
                .joinToString("  ") { codec ->
                    val params = codec.parameters?.entries
                        ?.joinToString(";") { "${it.key}=${it.value}" }
                        ?.takeIf { it.isNotEmpty() } ?: "(none)"

                    "${codec.name}:$params"
                }

            CallLog.write("caps $label $shown")
        }

        try {
            describe("recv", factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs)
            describe("send", factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs)
        } catch (e: Exception) {
            CallLog.write("caps shapes unavailable: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Called as a call starts. Nothing an earlier call learnt about an earlier
     * peer should narrow what this one may offer.
     */
    @Synchronized
    fun forgetBroken(context: Context) {
        broken = mutableMapOf()

        try {
            context.getSharedPreferences(BROKEN_PREFS, Context.MODE_PRIVATE).edit().remove(BROKEN_KEY).apply()
        } catch (e: Exception) {
        }
    }

    /**
     * True when the call has just learnt something it did not know; the caller
     * acts on it, so a family already written off answers false.
     */
    @Synchronized
    fun markBroken(context: Context, family: String, direction: String): Boolean {
        if (!FAMILIES.contains(family) || family == BASELINE) return false

        if (broken[family]?.contains(direction) == true) return false

        broken.getOrPut(family) { mutableSetOf() }.add(direction)

        return true
    }

    private fun withoutBroken(caps: CallCodecCaps, context: Context): CallCodecCaps {
        val current = broken

        // A codec whose hardware this device saw taking a camera's frames off
        // (the room client's MeshHardwareDrops, as the web's encoder-watch.ts)
        // is not sent: a room's camera goes out in VP8 (roomPublishCodec), and
        // a direct call's is not sent from that hardware at a few frames a
        // second. Remembered for a month, or as long as the media server's settings say ([dropSettings]).
        val dropped = MeshHardwareDrops.codecs(context, settings = dropSettings).map { "video/" + it.uppercase() }.toSet()

        if (current.isEmpty() && dropped.isEmpty()) return caps

        return caps.copy(
            families = caps.families.mapValues { (family, pair) ->
                val directions = current[family] ?: emptySet<String>()

                Pair(
                    if (directions.contains(ENCODE) || family in dropped) CallCodecScore.NONE else pair.first,
                    if (directions.contains(DECODE)) CallCodecScore.NONE else pair.second
                )
            }
        )
    }

    /**
     * What this device can do with each family, asked of MediaCodec once.
     *
     * `isHardwareAccelerated()` only exists from API 29; below it the vendor
     * name is the only signal there is, and the software codecs Google ships
     * all announce themselves.
     */
    fun probe(context: Context, forced: List<String>? = null): CallCodecCaps {
        cached?.let { return applyForced(withoutBroken(it, context), forced) }

        val encoders = mutableMapOf<String, CallCodecScore>()
        val decoders = mutableMapOf<String, CallCodecScore>()

        val list = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            emptyArray<MediaCodecInfo>()
        }

        for (family in FAMILIES) {
            val mime = MIME[family] ?: continue

            encoders[family] = scoreOf(list, mime, encoder = true)
            decoders[family] = scoreOf(list, mime, encoder = false)
        }

        val caps = CallCodecCaps(
            FAMILIES.associateWith { family ->
                Pair(
                    encoders[family] ?: CallCodecScore.NONE,
                    decoders[family] ?: CallCodecScore.NONE
                )
            },
            engine = OWN_ENGINE
        )

        cached = caps

        return applyForced(withoutBroken(caps, context), forced)
    }

    private fun applyForced(caps: CallCodecCaps, forced: List<String>?): CallCodecCaps {
        if (forced.isNullOrEmpty()) return caps

        return caps.copy(
            families = caps.families.mapValues { (family, pair) ->
                if (forced.contains(family)) pair else Pair(CallCodecScore.NONE, CallCodecScore.NONE)
            }
        )
    }

    private fun scoreOf(list: Array<MediaCodecInfo>, mime: String, encoder: Boolean): CallCodecScore {
        var found = false
        var hardware = false
        var smooth = false
        var fast = false

        for (info in list) {
            if (info.isEncoder != encoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue

            val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !isSoftwareName(info.name)
            }

            found = true

            val video = try {
                info.getCapabilitiesForType(mime).videoCapabilities
            } catch (e: Exception) {
                null
            }

            val at30 = video?.areSizeAndRateSupported(1920, 1080, 30.0) ?: false
            val at60 = video?.areSizeAndRateSupported(1920, 1080, 60.0) ?: false

            if (isHardware) hardware = true
            if (at30) smooth = true
            if (at60 && isHardware) fast = true
        }

        return CallCodecScore(ok = found, hw = hardware, smooth = smooth, fast = fast)
    }

    private fun isSoftwareName(name: String): Boolean {
        val lower = name.lowercase()

        return lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.startsWith("omx.ffmpeg.") ||
            lower.contains(".sw.")
    }

    private fun encodeScore(caps: CallCodecCaps, family: String) =
        caps.families[family]?.first ?: CallCodecScore.NONE

    private fun decodeScore(caps: CallCodecCaps, family: String) =
        caps.families[family]?.second ?: CallCodecScore.NONE

    /**
     * Which codec a room's camera (or screen) goes out in, as the browser
     * picks it (the realtime client's calls/room-publish.ts, cameraCodecFor()).
     *
     * A room is not negotiated the way a direct call is: the media server
     * forwards each publisher's codec to everyone subscribed, so the choice
     * is this device's alone, with VP8 behind it for a subscriber that can't
     * decode it ([roomBackupCodec]). H.264 where this device encodes it in
     * hardware, which nearly every receiver decodes in hardware too. VP8
     * otherwise: many phones have no VP9 encoder at all, and libwebrtc's
     * software one encoded every room call's 1080p on the CPU.
     *
     * An encrypted room takes VP8 alone, as the browser publishes into one:
     * nothing can stand behind the codec there, since only a track's first
     * sender is encrypted, so it has to be one every subscriber decodes.
     *
     * The media server may ask for others: the mesh's codec policy, which the
     * room hands over once joined (`Room.meshCodecPolicy`). Its list is a
     * preference, first choice first: a codec goes out only where this device
     * encodes it ("h265:hw" only in hardware), H.265 only to the mesh, never
     * into an encrypted room, and VP8 is where any list ends. Without one,
     * the built-in list: H.264 from hardware, else VP8.
     */
    fun roomPublishCodec(caps: CallCodecCaps, encrypted: Boolean, onMesh: Boolean = false, policy: List<String>? = null): String {
        if (encrypted) return "vp8"

        val list = policy?.takeIf { it.isNotEmpty() } ?: listOf("h264:hw", "vp8")

        for (entry in list) {
            val parts = entry.lowercase().trim().split(":", limit = 2)
            val name = parts[0]

            if (name !in listOf("h265", "h264", "vp8", "vp9", "av1")) continue
            if (name == "vp8") return "vp8"
            if (name == "h265" && !onMesh) continue

            val enc = caps.families["video/${name.uppercase()}"]?.first ?: continue

            if (!enc.ok) continue
            if (parts.getOrNull(1) == "hw" && !enc.hw) continue

            return name
        }

        return "vp8"
    }

    /**
     * What goes behind a room's camera (the browser's backupFor()): nothing
     * behind VP8 or in an encrypted room; H.264 where the media server's
     * policy asks for it and the codec isn't H.264; VP8 otherwise.
     */
    fun roomBackupCodec(codec: String, encrypted: Boolean, policy: String? = null): String? {
        if (codec == "vp8" || encrypted) return null

        return if (policy?.lowercase() == "h264" && codec != "h264") "h264" else "vp8"
    }

    /**
     * The video codecs this device encodes in hardware, as a join to the mesh
     * names them (`mesh_hwenc`) for its live view and what a codec policy
     * could have it send.
     */
    fun hardwareEncoders(caps: CallCodecCaps): List<String> =
        listOf("H264", "H265", "VP8", "VP9", "AV1").filter { name ->
            val enc = caps.families["video/$name"]?.first ?: return@filter false

            enc.ok && enc.hw
        }.map { it.lowercase() }

    /**
     * The video codecs this device decodes in hardware, as a join to the mesh
     * names them (`mesh_hwdec`), as the browser's client does.
     */
    fun hardwareDecoders(caps: CallCodecCaps): List<String> =
        listOf("H264", "H265", "VP8", "VP9", "AV1").filter { name ->
            val dec = caps.families["video/$name"]?.second ?: return@filter false

            dec.ok && dec.hw
        }.map { it.lowercase() }

    /**
     * Whether a room's server is WordPlus Mesh, whose rooms take H.265: the
     * cloud's `rtc.wordplus.cloud` and the names under it, as the room
     * client's own mesh check.
     */
    fun onMesh(server: String): Boolean {
        val host = runCatching { java.net.URI(server).host }.getOrNull()?.lowercase() ?: return false

        return host == "rtc.wordplus.cloud" || host.endsWith(".rtc.wordplus.cloud")
    }

    /**
     * How good one direction would be: hardware on either end counts most, a
     * smooth encoder next, then the tie-breaker. Null when it is not possible.
     */
    fun directionScore(sender: CallCodecCaps, receiver: CallCodecCaps?, family: String): Double? {
        val enc = encodeScore(sender, family)
        val dec = receiver?.let { decodeScore(it, family) } ?: CallCodecScore.UNKNOWN

        if (!enc.ok || !dec.ok) return null

        // A family held back loses its hardware standing and its tie-breaker:
        // it lands under every software codec that is smooth (VP9 at 3.3, AV1
        // at 3.2) and stays above VP8 (3.1), so it is still there when nothing
        // better is, and never chosen over something that works. The web
        // scores it the same way, number for number.
        if (heldBack(family, sender, receiver)) {
            return (if (enc.smooth) 2.0 else 0.0) + (if (dec.smooth) 1.0 else 0.0) + 0.15
        }

        return (if (enc.hw) 4.0 else 0.0) +
            (if (dec.hw) 4.0 else 0.0) +
            (if (enc.smooth) 2.0 else 0.0) +
            (if (dec.smooth) 1.0 else 0.0) +
            (TIE[family] ?: 0.0)
    }

    fun screenDirectionScore(sender: CallCodecCaps, receiver: CallCodecCaps?, family: String): Double? {
        val enc = encodeScore(sender, family)
        val dec = receiver?.let { decodeScore(it, family) } ?: CallCodecScore.UNKNOWN

        if (!enc.ok || !dec.ok) return null

        return (SCREEN_CONTENT[family] ?: 0.0) +
            (if (enc.smooth) 2.0 else 0.0) +
            (if (dec.smooth) 1.0 else 0.0) +
            (if (dec.hw) 1.0 else 0.0) +
            (TIE[family] ?: 0.0)
    }

    /** Whether a direction can run at 60 frames, by both ends' own word. */
    fun fastDirection(sender: CallCodecCaps, receiver: CallCodecCaps?, family: String): Boolean {
        val enc = encodeScore(sender, family)
        val dec = receiver?.let { decodeScore(it, family) } ?: CallCodecScore.NONE

        return enc.fast && dec.fast
    }

    private fun ranked(sender: CallCodecCaps, receiver: CallCodecCaps?, screen: Boolean): List<String> =
        FAMILIES
            .mapNotNull { family ->
                val value = if (screen) {
                    screenDirectionScore(sender, receiver, family)
                } else {
                    directionScore(sender, receiver, family)
                }

                value?.let { Pair(family, it) }
            }
            .sortedByDescending { it.second }
            .map { it.first }

    /** The order for an offer: this side's sending, the other side unknown. */
    fun offerOrder(own: CallCodecCaps, screen: Boolean, forced: List<String>? = null): List<String> {
        if (!forced.isNullOrEmpty()) return forced

        return ranked(own, null, screen)
    }

    /**
     * The order for an answer is what both sides send with until they switch,
     * and what a side that cannot switch sends with for good. So it serves the
     * offerer's sending when the offerer cannot switch, and this side's
     * sending otherwise.
     */
    fun answerOrder(own: CallCodecCaps, peer: CallCodecCaps?, screen: Boolean, forced: List<String>? = null): List<String> {
        if (!forced.isNullOrEmpty()) return forced

        if (peer != null && !peer.canSwitch) return ranked(peer, own, screen)

        return ranked(own, peer, screen)
    }

    /**
     * Reorders a video transceiver's codecs: every capability of a family in
     * ranking order, the families not ranked after them, then the rest (RTX,
     * RED, FEC) as they were.
     */
    fun apply(order: List<String>, transceiver: RtpTransceiver, factory: PeerConnectionFactory) {
        // Every exit from here used to be silent, and a silent exit means the
        // section keeps a family this device has been told not to use — which
        // is how H265 kept reaching peers that cannot decode what this phone
        // encodes. If the order cannot be applied, that has to be visible.
        val all = try {
            factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        } catch (e: Exception) {
            CallLog.write("codec order not applied: capabilities threw ${e.javaClass.simpleName}")

            return
        }

        logCapabilityShapes(factory)

        if (all.isNullOrEmpty()) {
            CallLog.write("codec order not applied: this build lists no video codecs")

            return
        }

        val isFamily = { codec: RtpCapabilities.CodecCapability, family: String ->
            codec.mimeType?.equals(family, ignoreCase = true) == true
        }

        // A section says what this side can be sent, and the other side sends
        // with the order in it. So a family this device has been caught
        // failing to decode comes out of the list altogether, before it is
        // ordered: leaving it in is an invitation to send exactly what will
        // not arrive, and demoting it is not enough, because the ranking that
        // built `order` may have put it first for this side's own *sending* —
        // which is what the order means when the other side's abilities are
        // not known yet.
        val codecs = all.filter { codec ->
            val family = FAMILIES.firstOrNull { isFamily(codec, it) }

            family == null || broken?.get(family)?.contains(DECODE) != true
        }

        val preferred = order.flatMap { family -> codecs.filter { isFamily(it, family) } }

        if (preferred.isEmpty()) {
            CallLog.write("codec order not applied: nothing in ${order.joinToString(",") { shortName(it) }} survived the filter")

            return
        }

        // A family that is merely not preferred still belongs in the section:
        // the other side may have nothing else.
        val others = codecs.filter { codec -> FAMILIES.any { isFamily(codec, it) } && !preferred.contains(codec) }
        val rest = codecs.filter { codec -> FAMILIES.none { isFamily(codec, it) } }

        try {
            transceiver.setCodecPreferences(preferred + others + rest)

            CallLog.write("codec order applied mid=${transceiver.mid ?: "-"} ${preferred.joinToString(",") { it.name ?: "?" }}${if (others.isEmpty()) "" else " then " + others.joinToString(",") { it.name ?: "?" }}")
        } catch (e: Exception) {
            // The class name alone says nothing useful: libwebrtc throws the
            // same IllegalStateException for a stopped transceiver and for a
            // codec the sender cannot match, and which one it is decides
            // whether the call silently falls back to VP8.
            CallLog.write(
                "codec order not applied: setCodecPreferences threw ${e.javaClass.simpleName}: ${e.message ?: "no message"}" +
                    " (asked for ${(preferred + others).joinToString(",") { it.name ?: "?" }})"
            )

            // A device that will not take the order still makes the call, on
            // whatever libwebrtc offers by default.
        }
    }

    /**
     * Redundant audio (RED, RFC 2198) on the microphone, as the web's
     * `applyRedPreferences`: each packet carries the one before it again, so a
     * packet lost on the way is played from the next instead of made up. A
     * room's microphone has had it from the start; a direct call's sent plain
     * Opus, and over the direct calls of 31 Oct 2026 a microphone broke up in
     * 31 of the moments something went wrong. RED is sent only when it leads
     * the negotiated codecs, so both sides put it first where they can: a side
     * without it leaves it out of its section and the call goes on plain Opus
     * that way. The shared screen's sound stays plain, as in a room.
     *
     * The positions of [mimeTypes] with RED first, then Opus, then the rest as
     * they were; null when RED is not among them.
     */
    fun redOrder(mimeTypes: List<String>): List<Int>? {
        val lower = mimeTypes.map { it.lowercase() }
        val red = lower.indices.filter { lower[it] == "audio/red" }

        if (red.isEmpty()) return null

        val opus = lower.indices.filter { lower[it] == "audio/opus" }

        return red + opus + lower.indices.filter { it !in red && it !in opus }
    }

    /** Puts RED first on a microphone's transceiver; answers whether it did. */
    fun applyRed(transceiver: RtpTransceiver, factory: PeerConnectionFactory): Boolean {
        return try {
            val codecs = factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs ?: return false
            val order = redOrder(codecs.map { it.mimeType ?: "" }) ?: return false

            transceiver.setCodecPreferences(order.map { codecs[it] })

            true
        } catch (e: Exception) {
            CallLog.write("RED not applied: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")

            false
        }
    }
}
