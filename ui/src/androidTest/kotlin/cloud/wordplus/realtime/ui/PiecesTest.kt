package cloud.wordplus.realtime.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.ui.calls.CallsDock
import cloud.wordplus.realtime.ui.calls.CallsRoundButton
import cloud.wordplus.realtime.ui.calls.CallsStage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The call screen's pieces (CallScreen.Pieces), on the real stage with no call
 * (the preview's, CallScreenPreviewActivity): the dock arranged as the app answers, its own button drawn as ours are
 * and hanging up still last, the status line, the view over the stage, and none of it without pieces.
 * `./gradlew connectedDebugAndroidTest` with a device or an emulator.
 */
@RunWith(AndroidJUnit4::class)
class PiecesTest {
    @After
    fun reset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CallScreen.pieces = CallScreen.Pieces() }
    }

    /** The stage in a state of the preview's, and what it then shows. */
    private fun shown(state: CallScreenPreviewActivity.Shown, look: (CallsStage) -> Unit) {
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), state)
        ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> look(all(activity.window.decorView).filterIsInstance<CallsStage>().first()) }
        }
    }

    private fun all(view: View): List<View> = listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } } ?: emptyList())

    /** The dock's buttons, in their order: by what TalkBack says of them. */
    private fun dock(stage: CallsStage): List<String> {
        val dock = all(stage).filterIsInstance<CallsDock>().first()
        return (0 until dock.childCount).map { dock.getChildAt(it).contentDescription?.toString() ?: "?" }
    }

    private fun text(stage: CallsStage, words: String): TextView? =
        all(stage).filterIsInstance<TextView>().firstOrNull { it.text?.toString() == words && it.visibility == View.VISIBLE }

    @Test
    fun withoutPiecesTheScreenIsOurs() = shown(CallScreenPreviewActivity.Shown.CALLING) { stage ->
        assertEquals("End Call", dock(stage).last())
        assertFalse(dock(stage).contains("Call notes"))
        assertNotNull(text(stage, "Calling…"))
    }

    @Test
    fun theAppArrangesTheDockAndAddsItsOwnButtonWithHangingUpLast() {
        var pressed = 0
        val phases = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CallScreen.pieces = CallScreen.Pieces(controls = { context, ours, moment ->
                phases += moment.phase
                // Hanging up put first: it stays last. Picture in picture dropped, the app's button added.
                ours.filter { it.id == "end" } + ours.filter { it.id != "end" && it.id != "pip" } +
                    CallScreen.CallControl.own(context, "notes", "Call notes", CallScreen.CallControl.icons.getValue("alert")) { pressed++ }
            })
        }
        shown(CallScreenPreviewActivity.Shown.CALLING) { stage ->
            val row = dock(stage)
            assertEquals("End Call", row.last())
            assertEquals("Call notes", row[row.size - 2])
            assertFalse(row.contains("Picture In Picture"))
            assertTrue(phases.contains("ringing_out"))

            // Drawn as ours: a dock button, and it does what the app said.
            val notes = all(stage).first { it.tag == "call-control-notes" }
            assertTrue(notes is CallsRoundButton)
            notes.performClick()
            assertEquals(1, pressed)
        }
    }

    @Test
    fun theStatusLineAndTheViewOverTheStage() {
        var over: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CallScreen.pieces = CallScreen.Pieces(
                status = { moment -> if (moment.phase == "ringing_out") "Ringing Ann…" else null },
                stage = { context, _ -> over ?: View(context).also { over = it } },
            )
        }
        shown(CallScreenPreviewActivity.Shown.CALLING) { stage ->
            assertNotNull(text(stage, "Ringing Ann…"))
            assertNull(text(stage, "Calling…"))
            assertSame(stage, over?.parent)
        }
        // A phase the app leaves to us: ours.
        over = null
        shown(CallScreenPreviewActivity.Shown.CONNECTING) { stage -> assertNotNull(text(stage, "Connecting...")) }
    }

    @Test
    fun aCallRingingInOnTheSameScreenAfterACallWithTheAppsButtonCanBeAnswered() {
        // The app's button in a call, none while one rings in, made anew at each state as an app's lambda makes it.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CallScreen.pieces = CallScreen.Pieces(controls = { context, ours, moment ->
                if (moment.phase == "ringing_in") ours
                else ours + CallScreen.CallControl.own(context, "order", "Order", CallScreen.CallControl.icons.getValue("alert")) {}
            })
        }
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), CallScreenPreviewActivity.Shown.VIDEO)
        ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val stage = arrayOfNulls<CallsStage>(1)
            scenario.onActivity { activity ->
                stage[0] = all(activity.window.decorView).filterIsInstance<CallsStage>().first()
                assertTrue(dock(stage[0]!!).contains("Order"))
                // The screen stays for the next call, which rings in.
                stage[0]!!.onScreenState(CallScreenPreviewActivity.state(CallScreenPreviewActivity.Shown.RINGING))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val answer = all(stage[0]!!).first { it.contentDescription?.toString() == "Answer" }
                assertFalse(dock(stage[0]!!).contains("Order"))
                assertEquals(View.VISIBLE, answer.visibility)
                assertTrue("Answer has room: ${answer.width}", answer.width > 0)
                assertSame(all(stage[0]!!).filterIsInstance<CallsDock>().first(), answer.parent)
            }
        }
    }

    @Test
    fun aButtonTheAppMakesAnewAtEachChangeKeepsTheDockAsItIsAndDoesTheNewestAction() {
        // As the docs write it: the app's button made in `controls`, so anew at every change of the call, many a second
        // while it connects. The dock keeps the one it shows, with what the newest one says, and isn't built again.
        var made = 0
        val pressed = mutableListOf<Int>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CallScreen.pieces = CallScreen.Pieces(controls = { context, ours, _ ->
                val n = ++made
                ours + CallScreen.CallControl.own(context, "order", "Order $n", CallScreen.CallControl.icons.getValue("alert")) { pressed += n }
            })
        }
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), CallScreenPreviewActivity.Shown.VIDEO)
        ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            lateinit var stage: CallsStage
            lateinit var before: List<View>
            lateinit var order: View
            scenario.onActivity { activity ->
                stage = all(activity.window.decorView).filterIsInstance<CallsStage>().first()
                val dock = all(stage).filterIsInstance<CallsDock>().first()
                before = (0 until dock.childCount).map { dock.getChildAt(it) }
                order = all(stage).first { it.tag == "call-control-order" }
                repeat(5) { stage.onScreenState(CallScreenPreviewActivity.state(CallScreenPreviewActivity.Shown.VIDEO)) }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val dock = all(stage).filterIsInstance<CallsDock>().first()
                assertTrue("the pieces ran again: $made", made > 5)
                assertEquals(before, (0 until dock.childCount).map { dock.getChildAt(it) })
                assertSame(order, all(stage).first { it.tag == "call-control-order" })
                assertEquals("Order $made", order.contentDescription)
                order.performClick()
                assertEquals(listOf(made), pressed)
            }
        }
    }
}
