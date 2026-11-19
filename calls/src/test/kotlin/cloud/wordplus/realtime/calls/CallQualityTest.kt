package cloud.wordplus.realtime.calls

import livekit.org.webrtc.RTCStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** How a direct call encodes and plays in its quality readings, at the level a group call's client tells its node, as the web's calls/quality.ts says it. */
class CallQualityTest {
    private val both = setOf(CallSource.CAMERA, CallSource.MICROPHONE)
    private val mids = mapOf("0" to CallSource.CAMERA, "1" to CallSource.MICROPHONE)

    private fun scope(playing: Set<CallSource> = both) = CallQualityScope(
        sending = both,
        playing = playing,
        outbound = { mids[it["mid"] as? String ?: ""] },
        inbound = { mids[it["mid"] as? String ?: ""] }
    )

    private fun stats(id: String, type: String, vararg members: Pair<String, Any>) = id to RTCStats(0L, type, id, mapOf(*members))

    private val codecs = mapOf(
        stats("CV", "codec", "mimeType" to "video/H264"),
        stats("CD", "codec", "mimeType" to "video/VP9"),
        stats("CA", "codec", "mimeType" to "audio/opus")
    )

    private fun capture(t: Double) = stats("S1", "media-source", "frames" to t * 30, "framesPerSecond" to 30.0, "width" to 1280L, "height" to 720L)

    private fun out(t: Double, vararg more: Pair<String, Any>) = stats(
        "O1", "outbound-rtp",
        "kind" to "video", "mid" to "0", "codecId" to "CV", "mediaSourceId" to "S1", "bytesSent" to t * 150_000, "packetsSent" to t * 150,
        "framesSent" to t * 29, "framesEncoded" to t * 30, "totalEncodeTime" to t * 30 * 0.006, "keyFramesEncoded" to t,
        "pliCount" to t, "firCount" to t, "targetBitrate" to 1_500_000.0, "retransmittedPacketsSent" to t * 3, "hugeFramesSent" to t,
        *more
    )

    private fun video(t: Double, vararg more: Pair<String, Any>): Pair<String, RTCStats> {
        val members = mapOf(
            "kind" to "video", "mid" to "0", "codecId" to "CD", "bytesReceived" to t * 125_000, "packetsReceived" to t * 140, "packetsLost" to 0L,
            "framesDecoded" to t * 25, "frameHeight" to 540L, "totalDecodeTime" to t * 25 * 0.003, "totalInterFrameDelay" to t,
            "totalSquaredInterFrameDelay" to t * 25 * (0.04 * 0.04 + 0.01 * 0.01), "freezeCount" to 0L, "totalFreezesDuration" to 0.0,
            "pauseCount" to 0L, "totalPausesDuration" to 0.0, "pliCount" to t, "nackCount" to t * 2, "jitter" to 0.009
        ) + more

        return "I1" to RTCStats(0L, "inbound-rtp", "I1", members)
    }

    private fun sound(t: Double) = stats(
        "I2", "inbound-rtp",
        "kind" to "audio", "mid" to "1", "codecId" to "CA", "bytesReceived" to t * 4_000, "packetsReceived" to t * 50, "packetsLost" to 0L,
        "totalSamplesReceived" to t * 48_000, "insertedSamplesForDeceleration" to t * 240, "removedSamplesForAcceleration" to t * 240
    )

    private fun totals(t: Double, vararg entries: Pair<String, RTCStats>) = CallQualityWatch.totals(codecs + entries, scope(), t * 1000)

    private fun between(from: CallQualityWatch.Totals, to: CallQualityWatch.Totals) = CallQualityWatch.between(from, to, both, both, emptyMap())

    /** The same gap read two ways: while this side doesn't show the source its counters stand, and the pause it comes out as lands in the reading after it shows it again. */
    private fun watched(resumes: Boolean, vararg frozen: Pair<String, Any>): JSONObject {
        val watch = CallQualityWatch()

        watch.note(totals(10.0, video(10.0)), scope(), 10_000.0)
        watch.note(totals(12.0, video(12.0)), scope(), 12_000.0)
        watch.reading(null)

        if (resumes) watch.note(totals(14.0, video(14.0)), scope(setOf(CallSource.MICROPHONE)), 14_000.0)

        watch.note(totals(22.0, video(22.0, *frozen)), scope(), 22_000.0)

        return watch.reading(null)!!.getJSONArray("played").getJSONObject(0)
    }

    @Test
    fun saysWhatRunsEachSourceTheTimeAFrameTakesAndWhatItCosts() {
        val named = arrayOf<Pair<String, Any>>("encoderImplementation" to "c2.qti.avc.encoder", "powerEfficientEncoder" to true)
        val sent = between(totals(10.0, capture(10.0), out(10.0, *named)), totals(20.0, capture(20.0), out(20.0, *named)))
            .getJSONArray("sent").getJSONObject(0)

        assertEquals("h264", sent.getString("codec"))
        assertEquals("c2.qti.avc.encoder", sent.getString("encoder"))
        assertEquals(true, sent.getBoolean("hw"))
        assertEquals(6.0, sent.getDouble("encodeMs"), 0.0)
        assertEquals(10.0, sent.getDouble("keyFrames"), 0.0)
        assertEquals(300.0, sent.getDouble("encoded"), 0.0)
        assertEquals(290.0, sent.getDouble("framesSent"), 0.0)
        assertEquals(20.0, sent.getDouble("pli"), 0.0)
        assertEquals(1500.0, sent.getDouble("target"), 0.0)
        assertEquals(2.0, sent.getDouble("resent"), 0.0)
        assertEquals(10.0, sent.getDouble("huge"), 0.0)

        val mode = sent.getJSONObject("mode")

        assertEquals(1280.0, mode.getDouble("width"), 0.0)
        assertEquals(720.0, mode.getDouble("height"), 0.0)
        // what it gives is no mode's frames a second
        assertFalse(mode.has("fps"))
    }

    @Test
    fun readsAnEncoderByItsNameFirst() {
        assertEquals(false, CallQualityWatch.encoderHardware("OpenH264", true))
        assertEquals(false, CallQualityWatch.encoderHardware("libvpx", null))
        assertEquals(true, CallQualityWatch.encoderHardware("c2.exynos.h264.encoder", false))
        assertEquals(true, CallQualityWatch.encoderHardware(null, true))
        assertEquals(null, CallQualityWatch.encoderHardware(null, null))
    }

    @Test
    fun saysHowAPictureAndASoundPlay() {
        val played = between(
            totals(10.0, video(10.0, "decoderImplementation" to "c2.qti.vp9.decoder", "pauseCount" to 0L, "totalPausesDuration" to 0.0), sound(10.0)),
            totals(20.0, video(20.0, "decoderImplementation" to "c2.qti.vp9.decoder", "pauseCount" to 1L, "totalPausesDuration" to 6.0), sound(20.0))
        ).getJSONArray("played")
        val picture = played.getJSONObject(0)
        val heard = played.getJSONObject(1)

        assertEquals("vp9", picture.getString("codec"))
        assertEquals("c2.qti.vp9.decoder", picture.getString("decoder"))
        assertEquals(3.0, picture.getDouble("decodeMs"), 0.0)
        assertEquals(10.0, picture.getDouble("unevenMs"), 0.0)
        assertEquals(1.0, picture.getDouble("pauses"), 0.0)
        assertEquals(6000.0, picture.getDouble("pauseMs"), 0.0)
        assertEquals(10.0, picture.getDouble("plis"), 0.0)
        assertEquals(20.0, picture.getDouble("nacks"), 0.0)
        assertEquals(9.0, picture.getDouble("jitterMs"), 0.0)
        assertEquals("opus", heard.getString("codec"))
        assertEquals(1.0, heard.getDouble("stretched"), 0.0)
    }

    @Test
    fun keepsAGapThisSideMadeOutOfTheCallsPausesAndFreezes() {
        val paused = arrayOf<Pair<String, Any>>("pauseCount" to 1L, "totalPausesDuration" to 16.1)
        val shownAgain = watched(true, *paused)

        assertEquals(1.0, shownAgain.getDouble("hiddenGaps"), 0.0)
        assertEquals(16100.0, shownAgain.getDouble("hiddenGapMs"), 0.0)
        assertFalse(shownAgain.has("pauses"))

        // a freeze short of a pause is the same gap
        val froze = watched(true, "freezeCount" to 1L, "totalFreezesDuration" to 2.0)

        assertEquals(0.0, froze.getDouble("freezes"), 0.0)
        assertEquals(0.0, froze.getDouble("freezeMs"), 0.0)
        assertEquals(1.0, froze.getDouble("hiddenGaps"), 0.0)
        assertEquals(2000.0, froze.getDouble("hiddenGapMs"), 0.0)

        // the same figures on a source shown all along are the call's
        val whole = watched(false, *paused)

        assertEquals(1.0, whole.getDouble("pauses"), 0.0)
        assertEquals(16100.0, whole.getDouble("pauseMs"), 0.0)
        assertFalse(whole.has("hiddenGaps"))
    }

    @Test
    fun saysNoSpreadWhenItCantTellAGapThisSideMadeFromIt() {
        // two gaps in one reading can't be told apart
        assertFalse(watched(true, "pauseCount" to 2L, "totalPausesDuration" to 8.0).has("unevenMs"))
        assertEquals(10.0, watched(false).getDouble("unevenMs"), 0.0)
    }

    @Test
    fun namesWhatTheDeviceDoesInHardwareInTheFamiliesOrder() {
        val hardware = CallCodecScore(ok = true, hw = true, smooth = true, fast = true)
        val software = CallCodecScore(ok = true, hw = false, smooth = true, fast = false)
        val caps = CallCodecCaps(
            families = mapOf(
                "video/VP8" to (software to software),
                "video/VP9" to (software to hardware),
                "video/H264" to (hardware to hardware),
                // written off here, whatever its probe said of its hardware
                "video/H265" to (CallCodecScore(ok = false, hw = true, smooth = false, fast = false) to hardware)
            ),
            canSwitch = true
        )

        assertEquals("h264", CallClientInfo.hardwareFamilies(caps, true))
        assertEquals("h265,h264,vp9", CallClientInfo.hardwareFamilies(caps, false))
    }
}
