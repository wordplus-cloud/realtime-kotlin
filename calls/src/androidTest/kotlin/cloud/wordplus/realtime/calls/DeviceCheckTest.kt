package cloud.wordplus.realtime.calls

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import livekit.org.webrtc.VideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the device check asks of the calls: the camera opened on this side alone, the level of a microphone, and the side
 * the next camera opens on. Run with the emulator's camera, a test pattern, whose frames are real ones.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCheckTest {
    @get:Rule
    val devices: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private fun samples(value: Short, count: Int = 160): ByteArray {
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { buffer.putShort(value) }
        return buffer.array()
    }

    @Test
    fun aLevelIsOnTheScaleTheWebReads() {
        val heard = CallMicLevel()
        assertEquals("nothing yet", 0f, heard.level(), 0f)
        assertFalse(heard.recording())

        heard.samples(samples(0))
        assertEquals("silence", 0f, heard.level(), 0f)
        assertTrue(heard.recording())

        heard.samples(samples(Short.MAX_VALUE))
        assertEquals("-10 dB and over is all of it", 1f, heard.level(), 0f)

        // A tenth of the scale is -20 dB, which is 40 of the 50 dB from -60 to -10.
        heard.samples(samples((Short.MAX_VALUE / 10).toShort()))
        assertEquals(0.8f, heard.level(), 0.01f)

        Thread.sleep(600)
        assertEquals("a reading this old says the recording stopped", 0f, heard.level(), 0f)
        assertFalse(heard.recording())
    }

    @Test
    fun theCameraCheckGivesFramesAndLetsGo() {
        val media = CallMedia.get(ApplicationProvider.getApplicationContext())
        val track = media.startPreview(true)
        assertNotNull(track)
        val frames = CountDownLatch(3)
        val sink = VideoSink { frames.countDown() }
        track!!.addSink(sink)
        assertTrue("frames from the camera", frames.await(10, TimeUnit.SECONDS))
        track.removeSink(sink)
        media.stopPreview()
    }

    @Test
    fun aCallOpensTheCameraChosenWhileNoneIsSent() = runBlocking {
        val s = StandIn()
        mainSettled()
        val call = s.calls.start("support:34:42", "34", CallType.AUDIO)
        assertTrue(call.frontCamera)
        call.preferCamera(false)
        assertFalse("the side said", call.frontCamera)
        withContext(Dispatchers.Main) { call.end() }
    }

    @Test
    fun aCallSaysHowLoudOnlyWhileItSends() = runBlocking {
        val s = StandIn()
        mainSettled()
        val call = s.calls.start("support:34:42", "34", CallType.AUDIO)
        withContext(Dispatchers.Main) {
            assertNotNull(call.microphoneLevel())
            call.microphone = false
            assertNull("muted, nothing is sent to read", call.microphoneLevel())
            call.end()
        }
    }

    @Test
    fun aGroupCallOpensTheCameraChosenAndSaysNoLevelWhileItSendsNone() = runBlocking {
        val s = StandIn()
        mainSettled()
        val group = s.calls.group("meeting:1")
        withContext(Dispatchers.Main) {
            assertTrue(group.frontCamera)
            group.preferCamera(false)
            assertFalse(group.frontCamera)
            assertNull(group.microphoneLevel())
            group.leave()
        }
    }
}
