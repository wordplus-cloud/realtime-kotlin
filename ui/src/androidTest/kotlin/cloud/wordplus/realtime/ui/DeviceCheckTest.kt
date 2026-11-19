package cloud.wordplus.realtime.ui

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The device check behind Device Settings and the join prompt: the phone's microphones, which one the route carries, and
 * the microphone tried on this side alone. On the emulator, whose microphone is one built-in one that records.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCheckTest {
    @get:Rule
    val microphone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun thePhonesOwnMicrophoneIsAlwaysThereUnderItsName() {
        assertEquals("builtin" to "Phone microphone", CallMicrophones.list(context).first())
    }

    @Test
    fun theMicrophoneChosenFollowsTheRoute() {
        val ids = listOf("builtin", "wired", "bluetooth")
        assertEquals("bluetooth", CallMicrophones.chosen(ids, "bluetooth"))
        assertEquals("wired", CallMicrophones.chosen(ids, "wired"))
        assertEquals("builtin", CallMicrophones.chosen(ids, "speaker"))
        assertEquals("builtin", CallMicrophones.chosen(ids, "earpiece"))
        assertEquals("a headset gone", "builtin", CallMicrophones.chosen(listOf("builtin"), "bluetooth"))
    }

    @Test
    fun choosingAMicrophoneChoosesItsRoute() {
        assertEquals("bluetooth", CallMicrophones.routeFor("bluetooth", "speaker", video = true))
        assertEquals("wired", CallMicrophones.routeFor("wired", "earpiece", video = false))
        assertEquals("off a headset, a video call to the speaker", "speaker", CallMicrophones.routeFor("builtin", "bluetooth", video = true))
        assertEquals("off a headset, a voice call to the earpiece", "earpiece", CallMicrophones.routeFor("builtin", "wired", video = false))
        assertNull("already on it, nothing changes", CallMicrophones.routeFor("builtin", "speaker", video = true))
    }

    @Test
    fun theMicrophoneCheckReadsALevelUntilStopped() {
        val check = CallMicrophoneCheck()
        val heard = CountDownLatch(3)
        assertTrue(check.start(context, recordsAlready = false) { level ->
            assertTrue("a level from 0 to 1: $level", level in 0f..1f)
            heard.countDown()
        })
        assertTrue("it reads about twenty times a second", heard.await(5, TimeUnit.SECONDS))
        check.stop()
    }
}
