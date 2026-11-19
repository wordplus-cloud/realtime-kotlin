package cloud.wordplus.realtime.calls

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import livekit.org.webrtc.VideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** Asks the system for the screen and keeps its answer, as an app's activity does. */
class ScreenAskActivity : Activity() {
    companion object {
        @Volatile var resultCode: Int? = null
        @Volatile var data: Intent? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityForResult(ScreenShare.permissionIntent(this), 1)
    }

    @Deprecated("startActivityForResult is what an app without androidx has")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Companion.data = data
        Companion.resultCode = resultCode
    }
}

/**
 * Sharing the screen on Android, through the system's own dialog on the emulator: its yes, the service of the
 * `mediaProjection` type Android 14 requires first, frames from the capture, and the call told. With no server: what goes
 * from there is the call's peer, which carries a camera the same way.
 */
@RunWith(AndroidJUnit4::class)
class ScreenShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The system's yes, pressed through its dialog: the whole screen, in whichever words this Android has them. */
    private fun systemsYes(): Intent {
        ScreenAskActivity.resultCode = null
        ScreenAskActivity.data = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(Intent(context, ScreenAskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(instrumentation)
        val systemUi = "com.android.systemui"
        device.wait(Until.hasObject(By.pkg(systemUi).clickable(true)), 15_000)

        // Android 14 and later offer one app or the whole screen, one app first.
        device.findObject(By.res(systemUi, "screen_share_mode_options"))?.let { chooser ->
            chooser.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i).*(entire|whole|full) screen.*"))), 5_000)?.click()
            SystemClock.sleep(500)
        }
        val yes = device.wait(Until.findObject(By.pkg(systemUi).clickable(true).text(Pattern.compile("(?i)start now|start|share screen"))), 10_000)
            ?: fail("no yes in the system's dialog").let { error("unreachable") }
        yes.click()

        val until = SystemClock.elapsedRealtime() + 15_000
        while (ScreenAskActivity.resultCode == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertEquals("the system's answer", Activity.RESULT_OK, ScreenAskActivity.resultCode)
        return ScreenAskActivity.data ?: error("a yes with no data")
    }

    @Test
    fun aCallWantsTheSystemsYesAndToBeUp() = runBlocking {
        val s = StandIn()
        mainSettled()
        val call = s.calls.start("support:34:42", "34", CallType.AUDIO)
        withContext(Dispatchers.Main) {
            assertFalse("not connected yet", call.setScreenShare(true, Intent()))
            assertFalse(call.sharingScreen)
            call.end()
        }
    }

    @Test
    fun aStreamShareIsAHostsAndShowsAsItsOwnTile() = runBlocking {
        val s = StandIn()
        val stream = withContext(Dispatchers.Main) { LiveStream(s.calls, "show:1") }
        withContext(Dispatchers.Main) {
            assertFalse("a viewer sends nothing", stream.setScreenShare(true, Intent()))

            stream.apply("hello", org.json.JSONObject("""{"you": {"role": "host", "chat": "write"}, "stream": {"state": "idle"}}"""))
            stream.screenStarted()
            assertTrue(stream.sharingScreen)
            assertEquals(true, stream.tiles.first { it.local }.screen)
            assertEquals(true, stream.mainTile?.local)

            assertFalse(stream.setScreenShare(false))
            assertFalse(stream.sharingScreen)
            assertTrue(stream.tiles.none { it.local })
        }
    }

    /** The room client's service draws a notice with no icon when handed none, and the system ends the process for that. */
    @Test
    fun aRoomsShareCarriesANoticeTheSystemTakes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val params = ScreenShareNotice.roomParams(context, Intent()) {}
        val notice = params.notification
        assertNotNull(notice)
        assertNotNull("a foreground service's notice needs an icon", notice!!.smallIcon)
        assertEquals(ScreenShareNotice.ID, params.notificationId)
        assertNotNull(
            "and a channel that is there",
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(notice.channelId),
        )
    }

    @Test
    fun aGroupCallNotUpSharesNothing() = runBlocking {
        val s = StandIn()
        mainSettled()
        val group = s.calls.group("meeting:7", CallType.AUDIO)
        withContext(Dispatchers.Main) {
            assertFalse(group.setScreenShare(true, Intent()))
            assertFalse(group.sharingScreen)
            group.leave()
        }
    }

    @Test
    fun theSystemsYesGivesFramesAndTheCallIsTold() = runBlocking {
        val permission = systemsYes()
        assertTrue("the service runs as the mediaProjection type", ScreenShareService.start(context))

        val capture = CallScreenCapture(context)
        val track = withContext(Dispatchers.Main) { capture.start(permission) }
        val frames = AtomicInteger()
        val counting = VideoSink { frames.incrementAndGet() }
        track.addSink(counting)
        val until = SystemClock.elapsedRealtime() + 15_000
        while (frames.get() == 0 && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue("frames from the screen", frames.get() > 0)
        assertTrue(capture.captureHeight > 0)
        track.removeSink(counting)

        val s = StandIn()
        mainSettled()
        val call = s.calls.start("support:34:42", "34", CallType.AUDIO)
        withContext(Dispatchers.Main) {
            val heard = ArrayList<Boolean>()
            call.onScreenShare { heard.add(it) }
            call.startedSharing(capture, track)
            assertTrue(call.sharingScreen)
            assertEquals(listOf(true), heard)

            // It ends with the call, the capture and the service let go.
            call.end()
            assertFalse(call.sharingScreen)
            assertEquals(listOf(true, false), heard)
            assertEquals(null, capture.track)
        }
    }
}
