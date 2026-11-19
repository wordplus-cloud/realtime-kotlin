package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The room's recording flag and who is offered Start Recording. The Swift package's ModerationTests has the same, and hears
 * the flag: the room's event has no way in here with no server, so the flag's hearing rests on the proof. With no server.
 */
@RunWith(AndroidJUnit4::class)
class GroupRecordingFlagTest {
    private fun group(moderated: Boolean) = GroupCall(
        ApplicationProvider.getApplicationContext(), StandIn().calls, "meeting:7", null, null, false, false, moderated,
    )

    @Test
    fun nothingRecordsYetAndNobodyWhoIsNotTheHostIsOfferedIt() {
        val named = group(moderated = true)
        val unnamed = group(moderated = false)
        assertFalse(named.isRecording)
        assertFalse(named.canRecord)
        assertFalse(unnamed.canRecord)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            named.leave()
            unnamed.leave()
        }
    }
}
