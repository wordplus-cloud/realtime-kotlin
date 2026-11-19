package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A group call keeping its people's camera switches (GroupCall, [CameraSwitch]), with no room: the word puts one up and
 * tells `onPeople`, said again or `ready` it stays the same switch, the frame its ear hears ends it, the camera turned
 * off ends it, its failsafe ends it, and an ended call hears nothing. The Swift package's CameraSwitchTests' twin.
 * `./gradlew connectedDebugAndroidTest` with a device or an emulator.
 */
@RunWith(AndroidJUnit4::class)
class GroupCallCameraSwitchTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun group(): GroupCall {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val rt = Realtime(RealtimeOptions(app = "app_test", endpoint = "https://shop.example/api/realtime"))
        return GroupCall(context, rt.calls(context), "room", null, null, microphone = false, camera = false)
    }

    @Test
    fun aGroupCallHearsTheWordAndTellsItsPeople() = onMain {
        val call = group()
        var told = 0
        call.onPeople { told += 1 }

        call.heardCameraSwitch("optimizing", "user_9", "TR_old")
        assertEquals("the people are read again", 1, told)
        assertEquals(true to "TR_old", call.cameraSwitch("user_9"))

        // Said again, and `ready`: the same switch, nobody told twice.
        call.heardCameraSwitch("optimizing", "user_9", null)
        call.heardCameraSwitch("ready", "user_9", "TR_new")
        assertEquals(1, told)
        assertEquals(true to "TR_old", call.cameraSwitch("user_9"))
        assertFalse("only the one who said it", call.cameraSwitch("user_8").first)

        // The frame its ear hears ends it, and tells the people.
        call.cameraDrew("user_9", "TR_new")
        assertFalse(call.cameraSwitch("user_9").first)
        assertEquals(2, told)
        call.finish("left")
    }

    @Test
    fun aGroupCallEndsASwitchWhenTheCameraGoesOff() = onMain {
        val call = group()

        call.heardCameraSwitch("optimizing", "user_1", "TR_a")
        call.cameraWentOff("user_1", "TR_a", muted = false)
        assertTrue("the old camera going is the move", call.cameraSwitch("user_1").first)
        call.cameraWentOff("user_1", null, muted = true)
        assertFalse("muted: turned off", call.cameraSwitch("user_1").first)
        call.finish("left")
    }

    @Test
    fun aGroupCallEndsASwitchAtItsFailsafeAndHearsNothingOnceOver() = onMain {
        val call = group()
        var told = 0
        call.onPeople { told += 1 }

        // Said eleven seconds ago: past its failsafe as soon as it is looked at.
        call.heardCameraSwitch("optimizing", "user_3", "TR_a", System.currentTimeMillis() - 11_000)
        assertFalse(call.cameraSwitch("user_3").first)
        assertEquals("up, then down", 2, told)

        call.finish("left")
        call.heardCameraSwitch("optimizing", "user_4", "TR_b")
        assertFalse("an ended call hears nothing", call.cameraSwitch("user_4").first)
    }

    @Test
    fun theFieldDefaultsToFalse() {
        val person = GroupPerson("user_1", "1", kotlinx.serialization.json.JsonNull, camera = true, microphone = true, speaking = false)
        assertFalse(person.optimizing)
        assertTrue(person.copy(optimizing = true).optimizing)
    }
}
