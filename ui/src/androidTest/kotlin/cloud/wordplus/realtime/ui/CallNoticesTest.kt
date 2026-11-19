package cloud.wordplus.realtime.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.calls.GroupCallFailure
import cloud.wordplus.realtime.ui.calls.CallsEngine
import cloud.wordplus.realtime.ui.calls.CallsRecordingPill
import cloud.wordplus.realtime.ui.calls.CallsRoomParticipant
import cloud.wordplus.realtime.ui.calls.CallsStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the SDK's call screen says of a group call that goes on, and what its dock leaves out: the waiting, REC, what could
 * not go out, and no hand to raise. `./gradlew connectedDebugAndroidTest`, as PiecesTest.
 */
@RunWith(AndroidJUnit4::class)
class CallNoticesTest {
    private fun said(view: View): List<String> =
        (if (view.visibility == View.VISIBLE) listOfNotNull(view.contentDescription?.toString()) else emptyList()) +
            ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { said(group.getChildAt(it)) } } ?: emptyList())

    private fun texts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()

        return listOfNotNull((view as? TextView)?.text?.toString()) +
            ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { texts(group.getChildAt(it)) } } ?: emptyList())
    }

    private fun <T : View> find(view: View, type: Class<T>): T? =
        if (type.isInstance(view)) type.cast(view) else (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it), type) }
        }

    private inline fun <reified T : View> find(view: View): T? = find(view, T::class.java)

    private fun <T> shown(shown: CallScreenPreviewActivity.Shown, look: (CallScreenPreviewActivity) -> T): T {
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), shown)
        return ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var result: T? = null
            scenario.onActivity { result = look(it) }
            result!!
        }
    }

    @Test
    fun aJoinWaitingForAPlaceSaysSo() {
        // The web's status of a group call waiting (use-group-call.ts).
        assertTrue("Waiting for a free place in the call..." in shown(CallScreenPreviewActivity.Shown.GROUP_WAITING) { texts(it.window.decorView) })
        assertFalse("Waiting for a free place in the call..." in shown(CallScreenPreviewActivity.Shown.GROUP_JOINING) { texts(it.window.decorView) })
    }

    @Test
    fun aRecordingIsTold() {
        // Someone else's recording: the pill, and the notice.
        shown(CallScreenPreviewActivity.Shown.RECORDING) { activity ->
            val root = activity.window.decorView
            assertEquals(View.VISIBLE, find<CallsRecordingPill>(root)?.visibility)
            assertTrue("${texts(root)}", "This call is being recorded" in texts(root))
        }
        shown(CallScreenPreviewActivity.Shown.GROUP) { activity ->
            assertTrue(find<CallsRecordingPill>(activity.window.decorView)?.visibility != View.VISIBLE)
        }
    }

    @Test
    fun theCallsWordsForWhatCouldNotGoOut() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Your camera could not be sent.", SdkFailures.said(context, GroupCallFailure("publish_failed", "camera")))
        assertEquals("Your microphone could not be sent.", SdkFailures.said(context, GroupCallFailure("publish_failed", "microphone")))
        assertEquals("Part of this call could not be encrypted.", SdkFailures.said(context, GroupCallFailure("encryption_failed", message = "no key")))
        assertEquals("Something went wrong in this call.", SdkFailures.said(context, GroupCallFailure("something_new")))
    }

    @Test
    fun withNoDriverNothingRecordsAndDoNotShowAgainIsKept() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = CallsEngine.get(context)
        var answer: Boolean? = null
        engine.startRecording { ok, _ -> answer = ok }
        assertEquals(false, answer)

        val settings = context.getSharedPreferences("cloud.wordplus.realtime.calls", android.content.Context.MODE_PRIVATE)
        settings.edit().remove("recording_notice_never").commit()
        assertTrue(engine.recordingNoticeShown)
        engine.recordingNoticeNever()
        assertFalse(engine.recordingNoticeShown)
        settings.edit().remove("recording_notice_never").commit()
    }

    @Test
    fun anSdkListenerHasNoHandToRaise() {
        // The call's own hands are a host's and its site's (`carriesRoomRules`): the SDK's group call has no way to send
        // one, so a listener's dock has no button that would do nothing. An app adds a control of its own (docs).
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), CallScreenPreviewActivity.Shown.GROUP)
        ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                find<CallsStage>(activity.window.decorView)!!.onScreenRoster(
                    listOf(
                        CallsRoomParticipant(
                            identity = "me", name = "You", avatar = "", isLocal = true, video = null, screen = null,
                            speaking = false, audioLevel = 0f, muted = true, micMuted = false, handRaised = false,
                            isPinned = false, canPublish = false, canAdmin = false, mode = "viewer",
                        ),
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val words = said(activity.window.decorView)
                assertFalse("$words", "Raise Hand" in words)
                // It is a listener's dock: nothing of theirs to turn on.
                assertFalse("$words", "Enable Microphone" in words)
            }
        }
    }
}
