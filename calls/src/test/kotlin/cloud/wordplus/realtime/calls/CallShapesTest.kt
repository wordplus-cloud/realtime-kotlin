package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A direct call's camera shape by the path's ceiling ([CallShapes.camera]), as the web's calls/peer.ts: full size takes
 * the ceiling it is given, 10 Mbps on a direct path and 4 through a relay, and the picture-size steps stay under both.
 */
class CallShapesTest {
    private val direct = CallShapes.CAMERA_DIRECT_MAX_BITRATE
    private val relay = CallShapes.CAMERA_RELAY_MAX_BITRATE

    @Test
    fun theCeilingsAreTheWebs() {
        assertEquals(10_000_000, direct)
        assertEquals(4_000_000, relay)
    }

    @Test
    fun fullSizeTakesThePathsCeiling() {
        for (wanted in listOf(null, 1080, 1440, 2160)) {
            assertEquals(direct, CallShapes.camera(wanted, 1080, direct).maxBitrate)
            assertEquals(relay, CallShapes.camera(wanted, 1080, relay).maxBitrate)
        }

        val full = CallShapes.camera(2160, 1080, direct)
        assertTrue(full.active)
        assertEquals(1.0, full.scale, 0.0)
        assertNull(full.maxFramerate)
    }

    @Test
    fun theStepsStayUnderBothCeilings() {
        for (ceiling in listOf(direct, relay)) {
            val shown = CallShapes.camera(720, 1080, ceiling)
            assertEquals(2_000_000, shown.maxBitrate)
            assertEquals(30, shown.maxFramerate)
            assertEquals(1.5, shown.scale, 0.0)

            assertEquals(1_000_000, CallShapes.camera(540, 1080, ceiling).maxBitrate)
            assertEquals(200_000, CallShapes.camera(180, 1080, ceiling).maxBitrate)
        }
    }

    @Test
    fun aHiddenCameraIsPausedAtThePathsCeiling() {
        val hidden = CallShapes.camera(0, 1080, direct)
        assertFalse(hidden.active)
        assertEquals(direct, hidden.maxBitrate)
    }
}
