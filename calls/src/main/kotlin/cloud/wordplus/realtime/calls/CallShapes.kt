package cloud.wordplus.realtime.calls

import kotlin.math.max
import kotlin.math.min

/**
 * The four things a call can carry, always in this order.
 *
 * The order is the wire: the caller offers one section per source in it, and
 * the answerer matches them by the mid map that travels with every
 * description, falling back to kind and order when a map is missing.
 */
internal enum class CallSource(val wire: String) {
    CAMERA("camera"),
    MICROPHONE("microphone"),
    SCREEN_SHARE("screen_share"),
    SCREEN_SHARE_AUDIO("screen_share_audio");

    val isVideo: Boolean
        get() = this == CAMERA || this == SCREEN_SHARE

    companion object {
        val ORDER = listOf(CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO)

        fun of(wire: String?): CallSource? = values().firstOrNull { it.wire == wire }
    }
}

internal data class CallEncodingShape(
    val active: Boolean,
    val maxBitrate: Int,
    val scale: Double,
    val maxFramerate: Int?
)

/**
 * How much of a picture actually goes out.
 *
 * The other side says how big it draws each of our video sources, and the
 * encoder is set to exactly that: a camera in a thumbnail is not paid for at
 * 1080p, a screen share on a large stage gets its real pixels, and a source
 * nobody is looking at is switched off rather than sent and thrown away.
 *
 * Every number here is the web's. Two sides of one call have to make the same
 * picture out of the same wish.
 */
internal object CallShapes {
    val VIEW_STEPS = listOf(180, 360, 540, 720, 1080, 1440, 2160)

    /**
     * A camera's ceiling at full size. On a direct path nothing between the
     * two sides is ours, so it sits well above what a camera needs and the
     * bandwidth estimate the two keep between them decides. Through a relay
     * every bit is our TURN server's traffic, so there it stays at 4 Mbps, as
     * it does until the first candidate pair is known. Leaving it out would be
     * no "unlimited": libwebrtc then holds a camera to 2.5 Mbps above 960×540
     * and 2 Mbps at or below.
     */
    const val CAMERA_DIRECT_MAX_BITRATE = 10_000_000
    const val CAMERA_RELAY_MAX_BITRATE = 4_000_000
    const val MICROPHONE_MAX_BITRATE = 64_000
    const val SCREEN_AUDIO_MAX_BITRATE = 128_000
    const val DEFAULT_TRACK_HEIGHT = 1080

    private val CAMERA_RUNGS = listOf(
        Triple(180, 200_000, 15),
        Triple(360, 500_000, 20),
        Triple(540, 1_000_000, 25),
        Triple(720, 2_000_000, 30)
    )

    private const val SCREEN_DEFAULT_HEIGHT = 1080
    private const val SCREEN_SMALL_HEIGHT = 360
    private const val SCREEN_SMALL_FRAMERATE = 5
    private const val SCREEN_FRAMERATE = 30

    private val SCREEN_CEILINGS = listOf(
        Pair(540, 4_000_000),
        Pair(720, 6_000_000),
        Pair(1080, 10_000_000),
        Pair(1440, 14_000_000),
        Pair(Int.MAX_VALUE, 20_000_000)
    )

    /**
     * A camera is asked for at a rung, so a box that grows by a few pixels
     * does not reconfigure the far encoder. Exactly the web's ladder.
     */
    fun viewStep(height: Int): Int {
        if (height <= 0) return 0

        return VIEW_STEPS.firstOrNull { it >= height } ?: VIEW_STEPS.last()
    }

    /**
     * A screen is asked for at the pixel it is drawn at, rounded up to an even
     * one — text is either sharp or it is not, and an odd height is not
     * something an encoder can give.
     */
    fun exactStep(height: Int): Int {
        if (height <= 0) return 0

        return ((height + 1) / 2) * 2
    }

    fun screenCeiling(height: Int): Int =
        (SCREEN_CEILINGS.firstOrNull { it.first >= height } ?: SCREEN_CEILINGS.last()).second

    fun audioCeiling(source: CallSource): Int =
        if (source == CallSource.SCREEN_SHARE_AUDIO) SCREEN_AUDIO_MAX_BITRATE else MICROPHONE_MAX_BITRATE

    /**
     * The camera at the rung the other side asked for; full size keeps the
     * capture's own frame rate and the path's ceiling
     * (CAMERA_DIRECT_MAX_BITRATE or CAMERA_RELAY_MAX_BITRATE). The rungs sit
     * under both.
     */
    fun camera(wanted: Int?, captureHeight: Int, ceiling: Int): CallEncodingShape {
        if (wanted == 0) {
            return CallEncodingShape(false, ceiling, 1.0, null)
        }

        val full = VIEW_STEPS.last()
        val rung = if (wanted != null && wanted < full) CAMERA_RUNGS.firstOrNull { it.first >= wanted } else null

        if (rung == null) {
            return CallEncodingShape(true, ceiling, 1.0, null)
        }

        return CallEncodingShape(
            active = true,
            maxBitrate = rung.second,
            scale = max(1.0, captureHeight.toDouble() / rung.first.toDouble()),
            maxFramerate = rung.third
        )
    }

    /**
     * The screen at exactly the size it is shown. In a chip it keeps up to
     * 1080 lines and drops to 5 frames instead, so it is sharp the moment it
     * is put back on the stage.
     *
     * `fast` means both ends run the chosen codec smoothly at 60, so the
     * capture's own rate goes through.
     */
    fun screen(wanted: Int?, captureHeight: Int, fast: Boolean): CallEncodingShape {
        if (wanted == 0) {
            return CallEncodingShape(false, screenCeiling(captureHeight), 1.0, null)
        }

        if (wanted != null && wanted <= SCREEN_SMALL_HEIGHT) {
            val height = min(captureHeight, SCREEN_DEFAULT_HEIGHT)

            return CallEncodingShape(
                active = true,
                maxBitrate = screenCeiling(height),
                scale = max(1.0, captureHeight.toDouble() / height.toDouble()),
                maxFramerate = SCREEN_SMALL_FRAMERATE
            )
        }

        val height = min(captureHeight, wanted ?: SCREEN_DEFAULT_HEIGHT)

        return CallEncodingShape(
            active = true,
            maxBitrate = screenCeiling(height),
            scale = max(1.0, captureHeight.toDouble() / height.toDouble()),
            maxFramerate = if (fast) null else SCREEN_FRAMERATE
        )
    }
}

/**
 * Screen audio is music or a video's sound as often as speech, and Opus only
 * sends it in stereo when the side *receiving* it says so in its SDP. So
 * every audio section of a description that arrives is marked stereo before
 * it is applied, and this side's encoder then sends screen audio in stereo.
 *
 * Every audio section, not the screen-audio one alone. Both carry Opus under
 * one payload type, and a payload type shared across a bundle has to mean
 * one thing (RFC 8843): marking one section and not the other had libwebrtc
 * reporting a "codec collision" at every connect — one it still let through,
 * where a stricter build refuses the description. The microphone captures
 * mono, so its section reading stereo changes nothing it sends, and the
 * bitrate it may use is set on its sender, not here.
 */
internal object CallSdp {
    fun withStereoOpus(sdp: String): String {
        val params = "stereo=1;sprop-stereo=1;maxaveragebitrate=" + CallShapes.SCREEN_AUDIO_MAX_BITRATE

        // Worked on line by line and joined back with the same separator, so
        // an SDP with nothing to change comes out byte for byte as it went in.
        // Anything else is a description libwebrtc may refuse for a reason
        // that has nothing to do with stereo.
        val lines = sdp.split("\n").toMutableList()
        val starts = lines.indices.filter { lines[it].startsWith("m=") }

        // Backwards: inserting a line moves everything after it.
        for (position in starts.indices.reversed()) {
            val start = starts[position]

            if (!lines[start].startsWith("m=audio")) continue

            val end = if (position + 1 < starts.size) starts[position + 1] else lines.size
            val section = lines.subList(start, end)

            val payload = opusPayload(section) ?: continue
            val prefix = "a=fmtp:$payload "

            val fmtp = (start until end).firstOrNull { lines[it].startsWith(prefix) }

            if (fmtp != null) {
                val existing = lines[fmtp].removePrefix(prefix).trim()

                val kept = existing.split(";")
                    .map { it.trim() }
                    .filter {
                        it.isNotEmpty() &&
                            !it.startsWith("stereo=") &&
                            !it.startsWith("sprop-stereo=") &&
                            !it.startsWith("maxaveragebitrate=")
                    }

                lines[fmtp] = prefix + (kept + params).joinToString(";") + "\r"
            } else {
                val rtpmap = (start until end).firstOrNull { lines[it].startsWith("a=rtpmap:$payload ") } ?: continue

                lines.add(rtpmap + 1, prefix + params + "\r")
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * A description without the attribute lines for payload types its media
     * sections do not declare.
     *
     * Firefox answers a negotiation on the offerer's payload types and then,
     * in every offer of its own — an ICE restart, a codec change — renumbers
     * them to its own defaults while keeping the `a=rtcp-fb` lines it had for
     * the old ones. libwebrtc's parser, this app's as much as Chrome's, makes
     * a codec out of every payload type it sees a feedback or format line for,
     * and refuses the whole description over the nameless ones: "Failed to
     * parse codecs correctly". Measured 21 Sep 2026 on production between
     * Firefox and Chrome. The web strips them before sending; this strips them
     * before applying, for a Firefox on a plugin older than that.
     */
    fun withoutOrphanPayloads(sdp: String): String {
        val lines = sdp.split("\n")
        val starts = lines.indices.filter { lines[it].startsWith("m=") }
        val drop = HashSet<Int>()

        for (position in starts.indices) {
            val start = starts[position]
            val end = if (position + 1 < starts.size) starts[position + 1] else lines.size
            val fields = lines[start].trim().split(" ")

            if (fields.size <= 3) continue

            val declared = fields.drop(3).toSet()

            if (declared.any { it.toIntOrNull() == null }) continue

            for (index in start + 1 until end) {
                val line = lines[index]

                if (!line.startsWith("a=rtcp-fb:") && !line.startsWith("a=fmtp:") && !line.startsWith("a=rtpmap:")) continue

                val payload = line.substringAfter(':').takeWhile { it.isDigit() }

                if (payload.isEmpty() || declared.contains(payload)) continue

                drop.add(index)
            }
        }

        if (drop.isEmpty()) return sdp

        return lines.filterIndexed { index, _ -> index !in drop }.joinToString("\n")
    }

    private fun opusPayload(section: List<String>): String? {
        for (line in section) {
            if (!line.startsWith("a=rtpmap:")) continue

            val body = line.removePrefix("a=rtpmap:")
            val space = body.indexOf(' ')

            if (space < 0) continue

            if (body.substring(space + 1).lowercase().startsWith("opus/48000/2")) {
                return body.substring(0, space)
            }
        }

        return null
    }
}

/** A candidate line, read far enough to decide what to do with it. */
internal data class CallCandidateInfo(val type: String, val address: String, val isPrivate: Boolean) {
    companion object {
        fun parse(candidate: String): CallCandidateInfo? {
            val line = candidate.removePrefix("candidate:")
            val parts = line.split(" ")

            if (parts.size < 8) return null

            val address = parts[4]
            val typIndex = parts.indexOf("typ")
            val type = if (typIndex >= 0 && typIndex + 1 < parts.size) parts[typIndex + 1] else ""

            return CallCandidateInfo(type, address, isPrivateAddress(address))
        }

        /**
         * The ranges a candidate can name that mean nothing outside its own
         * network. Two people behind different routers can each hold
         * 192.168.1.x, and pairing those wastes the whole connectivity check
         * on an address belonging to a stranger's printer.
         */
        fun isPrivateAddress(address: String): Boolean {
            val lower = address.lowercase()

            if (lower.endsWith(".local")) return true
            if (lower == "::1") return true
            if (lower.startsWith("fc") || lower.startsWith("fd")) return true
            if (lower.startsWith("fe8") || lower.startsWith("fe9") || lower.startsWith("fea") || lower.startsWith("feb")) return true

            val octets = lower.split(".")

            if (octets.size != 4) return false

            val first = octets[0].toIntOrNull() ?: return false
            val second = octets[1].toIntOrNull() ?: return false

            if (first == 10 || first == 127) return true
            if (first == 169 && second == 254) return true
            if (first == 192 && second == 168) return true
            if (first == 172 && second in 16..31) return true
            if (first == 100 && second in 64..127) return true

            return false
        }
    }
}
