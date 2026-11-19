package cloud.wordplus.realtime.calls

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The codec a room's camera goes out in and what goes behind it (CallCodecs.roomPublishCodec, roomBackupCodec), as the
 * browser's rule picks them (the realtime client's calls/room-publish.ts): the media
 * server's policy first choice first, where this device encodes it, else H.264 from hardware with VP8 behind it. The
 * Swift package's RoomCodecsTests' twin. `./gradlew connectedDebugAndroidTest` with a device or an emulator.
 */
@RunWith(AndroidJUnit4::class)
class RoomCodecsTest {
    private fun caps(encoders: Map<String, Pair<Boolean, Boolean>>): CallCodecCaps = CallCodecCaps(
        CallCodecs.FAMILIES.associateWith { family ->
            val (ok, hw) = encoders[family.removePrefix("video/")] ?: (false to false)
            CallCodecScore(ok = ok, hw = hw, smooth = true, fast = hw) to CallCodecScore(ok = true, hw = true, smooth = true, fast = true)
        }
    )

    private val phone = caps(mapOf("H264" to (true to true), "H265" to (true to true), "VP8" to (true to false), "VP9" to (true to false)))

    @Test
    fun withoutAPolicyItIsH264FromHardwareWithVP8BehindIt() {
        assertEquals("h264", CallCodecs.roomPublishCodec(phone, false))
        assertEquals("vp8", CallCodecs.roomBackupCodec("h264", false))

        val software = caps(mapOf("H264" to (true to false), "VP8" to (true to false)))
        assertEquals("vp8", CallCodecs.roomPublishCodec(software, false))
        assertNull(CallCodecs.roomBackupCodec("vp8", false))
    }

    @Test
    fun anEncryptedRoomTakesVP8AloneWhateverThePolicy() {
        assertEquals("vp8", CallCodecs.roomPublishCodec(phone, true, true, listOf("h265:hw", "h264")))
        assertNull(CallCodecs.roomBackupCodec("vp8", true))
        assertNull(CallCodecs.roomBackupCodec("h264", true))
    }

    @Test
    fun thePolicyGoesFirstChoiceFirstWhereThisDeviceEncodesIt() {
        // VP9 in software is taken where the list says vp9; "h265:hw" only from hardware, and only on the mesh.
        assertEquals("vp9", CallCodecs.roomPublishCodec(phone, false, true, listOf("vp9", "h264", "vp8")))
        assertEquals("h265", CallCodecs.roomPublishCodec(phone, false, true, listOf("h265:hw", "vp8")))
        assertEquals("vp8", CallCodecs.roomPublishCodec(phone, false, false, listOf("h265:hw", "vp8")))
        assertEquals("h264", CallCodecs.roomPublishCodec(phone, false, true, listOf("vp9:hw", "h264:hw")))
        // A codec this device can't encode, or a name nobody knows, is passed over, and VP8 is where any list ends.
        assertEquals("vp8", CallCodecs.roomPublishCodec(phone, false, true, listOf("av1", "opus")))
        // An empty list is no policy.
        assertEquals("h264", CallCodecs.roomPublishCodec(phone, false, true, emptyList()))
    }

    @Test
    fun thePolicysBackupIsH264WhereItAsksForItAndTheCodecIsAnother() {
        assertEquals("h264", CallCodecs.roomBackupCodec("vp9", false, "h264"))
        assertEquals("vp8", CallCodecs.roomBackupCodec("h264", false, "h264"))
        assertEquals("vp8", CallCodecs.roomBackupCodec("h265", false, "vp8"))
    }

    @Test
    fun theJoinNamesWhatThisDeviceEncodesAndDecodesInHardware() {
        assertEquals(listOf("h264", "h265"), CallCodecs.hardwareEncoders(phone))
        assertEquals(listOf("h264", "h265", "vp8", "vp9", "av1"), CallCodecs.hardwareDecoders(phone))
    }

    @Test
    fun theMeshIsTheCloudsRtcNameAndTheNamesUnderIt() {
        assertTrue(CallCodecs.onMesh("wss://rtc.wordplus.cloud"))
        assertTrue(CallCodecs.onMesh("wss://n-5c03a.de.rtc.wordplus.cloud/rtc"))
        assertFalse(CallCodecs.onMesh("wss://video-cloud.better-messages.com"))
        assertFalse(CallCodecs.onMesh("ws://10.0.2.2:17431"))
        assertFalse(CallCodecs.onMesh("not a url"))
    }
}
