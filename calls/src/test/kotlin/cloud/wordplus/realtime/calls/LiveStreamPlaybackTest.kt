package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live stream's layers, the viewer's choice among them, and its account of how it plays, as the browser's has them
 * (realtime-client sdk-calls/stream.ts:268-289, streams/playback.ts:64-90). The Swift package's LiveStreamPlaybackTests,
 * its twin. `./gradlew testDebugUnitTest` in calls/.
 */
class LiveStreamPlaybackTest {
    private val fullHd = LiveStreamPicture.layers(1920, 1080, simulcast = true)

    @Test
    fun aPictureInLayersHasThreeAndOneNotHasOne() {
        assertEquals(
            listOf(
                LiveStreamLayer(LiveStreamQuality.HIGH, 1920, 1080),
                LiveStreamLayer(LiveStreamQuality.MEDIUM, 960, 540),
                LiveStreamLayer(LiveStreamQuality.LOW, 480, 270),
            ),
            fullHd,
        )
        assertEquals(listOf(LiveStreamLayer(LiveStreamQuality.HIGH, 1280, 720)), LiveStreamPicture.layers(1280, 720, simulcast = false))
    }

    @Test
    fun noneAbove720pMayBeChosenAndOneAboveItGivesTheLargestBelow() {
        assertEquals(listOf(LiveStreamQuality.MEDIUM, LiveStreamQuality.LOW), LiveStreamPicture.choosable(fullHd).map { it.quality })
        assertEquals(LiveStreamQuality.MEDIUM, LiveStreamPicture.chosen(LiveStreamQuality.HIGH, fullHd)?.quality)
        assertEquals(LiveStreamQuality.LOW, LiveStreamPicture.chosen(LiveStreamQuality.LOW, fullHd)?.quality)
        assertNull(LiveStreamPicture.chosen(LiveStreamQuality.AUTO, fullHd))

        // A picture on its side is held to its shorter side, and a layer of no size stays.
        assertEquals(3, LiveStreamPicture.choosable(LiveStreamPicture.layers(720, 1280, simulcast = true)).size)
        assertEquals(1, LiveStreamPicture.choosable(LiveStreamPicture.layers(0, 0, simulcast = true)).size)
    }

    @Test
    fun aPictureIsTheLayerNearestItsHeight() {
        assertEquals(LiveStreamQuality.MEDIUM, LiveStreamPicture.layerAt(500, fullHd))
        assertNull(LiveStreamPicture.layerAt(0, fullHd))
    }

    @Test
    fun ratesComeFromTwoReadings() {
        val p = LiveStreamPlayback()
        p.joining(0)
        p.joined(LiveStreamStats.Server("wss://node", "n1", null, "1.0"), 100)

        p.read(LiveStreamRtp(bytes = 1000, frames = 10, height = 540, received = 100), null, fullHd, null, pictured = true, at = 1100)
        assertEquals(0.0, p.snapshot(1100).video!!.kbps, 0.0)

        p.read(
            LiveStreamRtp(bytes = 126_000, frames = 40, height = 540, received = 190, lost = 10, bufferDelay = 3.0, emitted = 30),
            LiveStreamRtp(bytes = 5000, samples = 1000, concealed = 10),
            fullHd,
            LiveStreamStats.Transport("udp", false, null, 40.0),
            pictured = true,
            at = 2100,
        )
        val stats = p.snapshot(2100)
        assertEquals(1000.0, stats.video!!.kbps, 0.01)
        assertEquals(10.0, stats.video!!.lossPct, 0.01)
        assertEquals(100.0, stats.video!!.bufferMs, 0.01)
        assertEquals(LiveStreamQuality.MEDIUM, stats.video!!.layer)
        assertEquals("udp", stats.transport?.protocol)
        assertEquals("n1", stats.server?.node)
    }

    @Test
    fun theSessionCountsTheStartupStallsAndRejoins() {
        val p = LiveStreamPlayback()
        p.joining(0)
        p.joined(null, 500)
        p.read(LiveStreamRtp(frames = 5), null, fullHd, null, pictured = true, at = 1000)
        assertEquals(1000.0, p.session.startupMs!!, 0.0)

        // No new frame for 2 s while it should show them.
        p.read(LiveStreamRtp(frames = 5), null, fullHd, null, pictured = true, at = 2000)
        assertEquals(0, p.session.stalls)
        p.read(LiveStreamRtp(frames = 5), null, fullHd, null, pictured = true, at = 3000)
        assertEquals(1, p.session.stalls)
        p.read(LiveStreamRtp(frames = 9), null, fullHd, null, pictured = true, at = 4000)
        assertEquals(3000.0, p.session.stalledMs, 0.0)

        // A view that isn't showing it is no stall.
        p.read(LiveStreamRtp(frames = 9), null, fullHd, null, pictured = false, at = 9000)
        assertEquals(1, p.session.stalls)

        p.reconnecting(9500)
        p.reconnected(9600)
        p.left(10_000)
        assertEquals(9500.0, p.session.watchedMs, 0.0)
        p.joining(11_000)
        p.joined(null, 11_500)
        p.failed("media_unreachable", 12_000)
        assertEquals(listOf(1, 1, 1), listOf(p.session.reconnects, p.session.rejoins, p.session.errors))
        assertEquals(
            listOf("joining", "joined", "first-frame", "stalled", "resumed", "reconnecting", "reconnected", "left", "joining", "rejoined", "error"),
            p.events.map { it.kind },
        )
    }

    @Test
    fun itKeepsTheLastEvents() {
        val p = LiveStreamPlayback()
        repeat(60) { p.note("quality", "$it", it.toLong()) }
        assertEquals(LiveStreamPlayback.EVENTS_KEPT, p.events.size)
        assertEquals("59", p.events.last().detail)
    }

    @Test
    fun theReportNamesWhatWebRtcCounts() {
        val report = LiveStreamReport(
            listOf(
                LiveStreamReport.Entry("in", "inbound-rtp", mapOf("bytesReceived" to java.math.BigInteger.valueOf(900), "framesDecoded" to 12L, "packetsLost" to 3, "frameHeight" to 540L, "codecId" to "c", "decoderImplementation" to "MediaCodec", "powerEfficientDecoder" to true)),
                LiveStreamReport.Entry("c", "codec", mapOf("mimeType" to "video/H264")),
                LiveStreamReport.Entry("t", "transport", mapOf("selectedCandidatePairId" to "p")),
                LiveStreamReport.Entry("p", "candidate-pair", mapOf("localCandidateId" to "l", "currentRoundTripTime" to 0.04)),
                LiveStreamReport.Entry("l", "local-candidate", mapOf("protocol" to "udp", "candidateType" to "relay", "relayProtocol" to "tls")),
            ),
        )
        val rtp = report.inbound()!!
        assertEquals(listOf(900L, 12L, 3L, 540L), listOf(rtp.bytes, rtp.frames, rtp.lost, rtp.height))
        assertEquals("video/H264", rtp.codec)
        assertEquals(true, rtp.hardware)
        val way = report.transport()
        assertEquals("udp", way.protocol)
        assertEquals(true, way.relayed)
        assertEquals("tls", way.relayProtocol)
        assertEquals(40.0, way.rttMs!!, 0.001)
        assertTrue(LiveStreamReport(emptyList()).inbound() == null)
    }
}
