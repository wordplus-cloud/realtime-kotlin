package cloud.wordplus.realtime.calls

import io.livekit.android.room.MeshApp
import io.livekit.android.room.MeshCaps
import io.livekit.android.room.MeshSettings
import livekit.org.webrtc.RtpParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a group call sends by the media server's settings ([GroupShapes]): the schema's defaults, the browsers' values,
 * where the server says nothing, and the server's value where it does.
 */
class GroupSettingsTest {
    private fun settings(json: String) = MeshSettings.parse(json)!!

    @Test
    fun theMicrophoneIsTheBrowsersAndNeverRedIntoAnEncryptedRoom() {
        val open = GroupShapes.microphone(encrypted = false, settings = MeshSettings.NONE)
        assertEquals(64_000, open.audioBitrate)
        assertTrue(open.red)
        assertTrue(open.dtx)
        assertTrue("its node may ask it again for what its upload lost", open.resend)
        assertFalse(GroupShapes.microphone(encrypted = true, settings = MeshSettings.NONE).red)
        assertTrue(GroupShapes.microphone(encrypted = true, settings = MeshSettings.NONE).resend)

        val mic = GroupShapes.microphone(false, settings("""{"rev":3,"set":{"mic.kbps":32,"mic.red":false,"mic.dtx":false}}"""))
        assertEquals(32_000, mic.audioBitrate)
        assertFalse(mic.red)
        assertFalse(mic.dtx)
    }

    @Test
    fun theCameraIsCapturedAt1080AndSentWithTheLadderAt30Frames() {
        val capture = GroupShapes.cameraCapture(MeshSettings.NONE)
        assertEquals(1080, capture.height)
        assertEquals(30, capture.maxFps)

        val publish = GroupShapes.camera("h264", "vp8", 1080, MeshSettings.NONE)
        assertEquals(3_500_000, publish.videoEncoding?.maxBitrate)
        assertEquals(listOf(270, 540), publish.simulcastLayers?.map { it.capture.height })
        assertEquals(listOf(30, 30), publish.simulcastLayers?.map { it.encoding.maxFps })
        assertEquals(listOf(400_000, 1_200_000), publish.simulcastLayers?.map { it.encoding.maxBitrate })
        assertEquals("vp8", publish.backupCodec?.codec)
        assertEquals(4_000_000, publish.backupCodec?.encoding?.maxBitrate)
        assertNull(publish.degradationPreference)

        assertEquals(4_000_000, GroupShapes.camera("vp8", null, 1080, MeshSettings.NONE).videoEncoding?.maxBitrate)
    }

    @Test
    fun theServersNumbersShapeTheCamera() {
        val set = settings(
            """{"rev":2,"set":{"camera.captureHeight":720,"camera.captureFps":24,"camera.fps":24,"camera.topKbps.h264.720":1500,""" +
                """"camera.ladder.720":[{"h":360,"kbps":500,"fps":24}],"camera.degradation":"maintain-resolution"}}"""
        )
        val capture = GroupShapes.cameraCapture(set)
        assertEquals(720, capture.height)
        assertEquals(24, capture.maxFps)

        val publish = GroupShapes.camera("h264", null, 720, set)
        assertEquals(1_500_000, publish.videoEncoding?.maxBitrate)
        assertEquals(24, publish.videoEncoding?.maxFps)
        assertEquals(listOf(640), publish.simulcastLayers?.map { it.capture.width })
        assertEquals(RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION, publish.degradationPreference)
    }

    @Test
    fun aSoftwareCameraIsHeldTo720AndHardwareTo1080() {
        assertEquals(MeshCaps(height = 720), GroupShapes.cameraCap(hardware = false, lines = 1080, settings = MeshSettings.NONE))
        assertTrue(GroupShapes.cameraCap(hardware = true, lines = 1080, settings = MeshSettings.NONE).isEmpty)
        assertTrue(GroupShapes.cameraCap(hardware = false, lines = 720, settings = MeshSettings.NONE).isEmpty)
    }

    @Test
    fun theCodecRulesComeBeforeTheCodecs() {
        val set = settings(
            """{"rev":4,"set":{"camera.codecRules":[{"browser":"chrome","camera":["vp9"]},{"os":"windows","camera":["av1"]},""" +
                """{"os":"android","device":"tablet","camera":["vp8"]},{"os":"android","camera":["h265:hw","vp8"]}],"camera.codecs":["vp8"]}}"""
        )
        assertEquals(listOf("h265:hw", "vp8"), GroupShapes.cameraCodecs(set, tablet = false))
        assertEquals(listOf("vp8"), GroupShapes.cameraCodecs(set, tablet = true))
        assertEquals(listOf("vp8"), GroupShapes.cameraCodecs(settings("""{"rev":4,"set":{"camera.codecs":["vp8"]}}"""), tablet = false))
        assertEquals(listOf("h264:hw", "vp8"), GroupShapes.cameraCodecs(MeshSettings.NONE, tablet = false))
    }

    @Test
    fun anSvcCameraTakesTheSettingsMode() {
        assertEquals("L3T3_KEY", GroupShapes.camera("vp9", "vp8", 1080, MeshSettings.NONE).scalabilityMode)
        assertNull("simulcast for any other codec", GroupShapes.camera("h264", "vp8", 1080, MeshSettings.NONE).scalabilityMode)
        val set = settings("""{"rev":2,"set":{"camera.svcMode":"L2T3_KEY"}}""")
        assertEquals("L2T3_KEY", GroupShapes.camera("av1", null, 720, set).scalabilityMode)
        assertEquals("L2T3_KEY", GroupShapes.camera("vp9", null, 540, set).scalabilityMode)
    }

    @Test
    fun aRevisionWithApplyNowRepublishesACameraWhosePublishSettingsChanged() {
        val before = settings("""{"rev":1,"set":{"camera.fps":30,"mic.kbps":48}}""")
        val fps = settings("""{"rev":2,"applyNow":true,"set":{"camera.fps":24,"mic.kbps":48}}""")
        val mic = settings("""{"rev":3,"applyNow":true,"set":{"camera.fps":30,"mic.kbps":32}}""")
        assertTrue("a camera key changed: out again", GroupShapes.cameraPrint(before) != GroupShapes.cameraPrint(fps))
        assertEquals("the microphone's: the camera stays", GroupShapes.cameraPrint(before), GroupShapes.cameraPrint(mic))
        assertTrue(GroupShapes.touchesPublish(setOf("camera.fps")))
        assertFalse("live keys need no publish", GroupShapes.touchesPublish(setOf("camera.capHeight", "tune.failsafeS")))
        assertTrue(MeshSettings.republishDelayMs("user_7") in 0 until 60_000)
    }

    @Test
    fun theJoinNamesThisModuleAndTakesTheSettingsTimeouts() {
        val caps = CallCodecCaps(emptyMap())
        val defaults = GroupShapes.connectOptions(MeshSettings.NONE, emptyList(), caps)
        assertEquals(MeshApp("realtime-kotlin", GroupShapes.VERSION), defaults.meshApp)
        assertEquals(20_000L, defaults.meshSignalTimeoutMs)
        assertEquals(20_000L, defaults.meshPcTimeoutMs)
        assertEquals(300_000L, defaults.meshJoinBudgetMs)
        assertEquals(listOf(0, 300, 1200, 2700, 4800, 7000, 7000, 7000, 7000, 7000).map { it.milliseconds }, GroupShapes.reconnectPolicy(MeshSettings.NONE)?.retryDelays)

        val set = settings("""{"rev":5,"set":{"connect.signalTimeoutMs":10000,"connect.pcTimeoutMs":15000,"connect.joinBudgetMs":60000,"reconnect.delaysMs":[100,1000]}}""")
        val options = GroupShapes.connectOptions(set, emptyList(), caps)
        assertEquals(10_000L, options.meshSignalTimeoutMs)
        assertEquals(15_000L, options.meshPcTimeoutMs)
        assertEquals(60_000L, options.meshJoinBudgetMs)
        assertEquals(listOf(100.milliseconds, 1000.milliseconds), GroupShapes.reconnectPolicy(set)?.retryDelays)
    }
}
