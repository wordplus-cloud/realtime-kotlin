package cloud.wordplus.realtime.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.calls.CallException
import cloud.wordplus.realtime.calls.CallPerson
import cloud.wordplus.realtime.ui.calls.CallsAddPerson
import cloud.wordplus.realtime.ui.calls.CallsAddPersonPanel
import cloud.wordplus.realtime.ui.calls.CallsAddPersonStore
import cloud.wordplus.realtime.ui.calls.CallsRoundButton
import cloud.wordplus.realtime.ui.calls.CallsStage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Add Person (realtime-ui's add-person.tsx) on the real stage, with a stand-in for the host: the button only where the
 * host gives whom to add and only once the call is up, the list as it loads, a press that rings, how each ring stands,
 * Cancel, and a failure said by the screen. On a device or an emulator, as PiecesTest.
 */
@RunWith(AndroidJUnit4::class)
class AddPersonTest {
    private val asked = mutableListOf<(List<CallsAddPerson.Person>) -> Unit>()
    private val added = mutableListOf<Pair<CallsAddPerson.Person, (String?) -> Unit>>()
    private val cancelled = mutableListOf<Pair<String, (String?) -> Unit>>()
    private var rings: List<CallsAddPerson.Ring> = emptyList()

    private val ann = CallsAddPerson.Person("43", "Ann", "")
    private val ben = CallsAddPerson.Person("44", "Ben", "")

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val standIn = CallsAddPerson(
        people = { asked.add(it) },
        add = { person, done -> added.add(person to done) },
        rings = { rings },
        cancel = { ring, done -> cancelled.add(ring to done) },
    )

    @After
    fun reset() {
        CallsAddPersonStore.current = null
    }

    /** Something on the main thread, and what the stage posted for itself after it. */
    private fun step(run: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(run)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun state(phase: String, group: Boolean = false) = CallScreenPreviewActivity.state(CallScreenPreviewActivity.Shown.VOICE)
        .put("phase", phase)
        .put("group", group)

    /** The stage of the preview with this host (the preview's own goes as it draws), the call up. */
    private fun stage(host: CallsAddPerson? = standIn, look: (CallsStage) -> Unit) {
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), CallScreenPreviewActivity.Shown.VOICE)
        ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            lateinit var found: CallsStage
            scenario.onActivity { activity -> found = all(activity.window.decorView).filterIsInstance<CallsStage>().first() }
            step {
                CallsAddPersonStore.current = host
                found.onScreenState(state("in_progress"))
            }
            look(found)
        }
    }

    private fun all(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } } ?: emptyList())

    private fun button(stage: View): CallsRoundButton? = all(stage).filterIsInstance<CallsRoundButton>().firstOrNull { it.contentDescription == "Add Person" }

    private fun panel(stage: View): CallsAddPersonPanel? = all(stage).filterIsInstance<CallsAddPersonPanel>().firstOrNull()

    private fun texts(view: View): List<String> = all(view).filter { it.visibility == View.VISIBLE }.mapNotNull { (it as? TextView)?.text?.toString() }

    private fun row(stage: View, starts: String): View? = panel(stage)?.let { shown -> all(shown).firstOrNull { it.contentDescription?.startsWith(starts) == true && it !is TextView } }

    private fun cancel(stage: View): TextView? =
        panel(stage)?.let { shown -> all(shown).filterIsInstance<TextView>().firstOrNull { it.contentDescription?.startsWith("Cancel") == true } }

    private fun opened(stage: CallsStage, people: List<CallsAddPerson.Person>? = null) = step {
        stage.showAddPerson()
        if (people != null) asked.removeAt(asked.lastIndex)(people)
    }

    private fun apply(stage: CallsStage, phase: String, group: Boolean = false) = step { stage.onScreenState(state(phase, group)) }

    @Test
    fun aLongListScrollsUnderItsTitleAboveTheDock() = stage { stage ->
        opened(stage, (0 until 100).map { CallsAddPerson.Person("$it", "Person $it", "") })
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val shown = panel(stage)!!
        val title = all(shown).filterIsInstance<TextView>().first { it.text == "Add Person" }
        val scroll = all(shown).filterIsInstance<android.widget.ScrollView>().first()
        assertTrue("the rows give way, never the title", title.height > 0)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        assertTrue(scroll.height > 100)
        assertTrue("it stays on the screen", title.isShown)
    }

    @Test
    fun aHostThatGivesNoneDrawsNoButton() = stage(host = null) { stage ->
        assertEquals(View.GONE, button(stage)?.visibility)
        step { stage.showAddPerson() }
        assertNull("and nothing opens", panel(stage))
    }

    @Test
    fun theButtonIsThereOnceTheCallIsUp() = stage { stage ->
        for (phase in listOf("ringing_out", "ringing_in", "connecting", "reconnecting")) {
            apply(stage, phase)
            assertEquals(phase, View.GONE, button(stage)?.visibility)
        }
        apply(stage, "in_progress")
        assertEquals("a call between two", View.VISIBLE, button(stage)?.visibility)
        apply(stage, "in_progress", group = true)
        assertEquals("a group call", View.VISIBLE, button(stage)?.visibility)
        apply(stage, "connecting", group = true)
        assertEquals("a group call joining", View.GONE, button(stage)?.visibility)
    }

    @Test
    fun theListSaysItIsLoadingThenWhomItOffersOrThatThereIsNoOne() = stage { stage ->
        opened(stage)
        assertTrue(texts(stage).contains("Add Person"))
        assertTrue(texts(stage).contains("Loading..."))
        assertEquals("its button latched while it is open", true, button(stage)?.isOn)

        step { asked.removeAt(asked.lastIndex)(listOf(ann, ben)) }
        assertFalse(texts(stage).contains("Loading..."))
        assertTrue(texts(stage).contains("Ann"))
        assertTrue(texts(stage).contains("Ben"))

        step { stage.hideAddPerson() }
        opened(stage, emptyList())
        assertTrue(texts(stage).contains("There is no one else to add"))
    }

    @Test
    fun aPressRingsThemAndTheirRowSaysHowItStands() = stage { stage ->
        opened(stage, listOf(ann, ben))
        step { row(stage, "Ann")!!.performClick() }
        assertEquals(listOf(ann), added.map { it.first })
        assertEquals("one at a time, as the web", false, row(stage, "Ben")?.isEnabled)

        rings = listOf(CallsAddPerson.Ring("r1", "43", "ringing", true))
        step { added.removeAt(added.lastIndex).second(null) }
        assertNotNull("it stays, where the row says how the ring stands", panel(stage))
        assertTrue(texts(stage).contains("Ringing…"))
        assertEquals("Ann, Ringing…", row(stage, "Ann")?.contentDescription)
        assertEquals("ringing, not rung again", false, row(stage, "Ann")?.isEnabled)
        assertEquals(true, row(stage, "Ben")?.isEnabled)
    }

    @Test
    fun eachWayARingEndsIsSaid() = stage { stage ->
        opened(stage, listOf(ann))
        for ((state, said) in listOf("answered" to "Answered", "rejected" to "Declined", "busy" to "Busy", "no_answer" to "No answer", "cancelled" to "Cancelled")) {
            rings = listOf(CallsAddPerson.Ring("r", "43", state, false))
            apply(stage, "in_progress")
            assertTrue(state, texts(stage).contains(said))
            assertEquals("$state: the person can be rung again", true, row(stage, "Ann")?.isEnabled)
            assertNull("$state: nothing to call off", cancel(stage))
        }
    }

    @Test
    fun aPersonsLastRingIsWhatTheirRowSays() {
        rings = listOf(CallsAddPerson.Ring("old", "43", "busy", false), CallsAddPerson.Ring("new", "43", "no_answer", false))
        stage { stage ->
            opened(stage, listOf(ann))
            assertTrue(texts(stage).contains("No answer"))
            assertFalse(texts(stage).contains("Busy"))
        }
    }

    @Test
    fun cancelCallsOffARingThisAppRangAlone() {
        rings = listOf(CallsAddPerson.Ring("r1", "43", "ringing", false))
        stage { stage ->
            opened(stage, listOf(ann))
            assertNull("a ring of someone else in the call", cancel(stage))

            rings = listOf(CallsAddPerson.Ring("r1", "43", "ringing", true))
            apply(stage, "in_progress")
            assertEquals("Cancel Ann", cancel(stage)?.contentDescription)

            step { cancel(stage)!!.performClick() }
            assertEquals(listOf("r1"), cancelled.map { it.first })
            assertEquals("once", false, cancel(stage)?.isEnabled)

            rings = listOf(CallsAddPerson.Ring("r1", "43", "cancelled", false))
            step { cancelled.removeAt(cancelled.lastIndex).second(null) }
            assertNotNull(panel(stage))
            assertTrue(texts(stage).contains("Cancelled"))
            assertNull(cancel(stage))
        }
    }

    @Test
    fun aFailureClosesItAndTheScreenSaysWhy() = stage { stage ->
        opened(stage, listOf(ann))
        step { row(stage, "Ann")!!.performClick() }
        step { added.removeAt(added.lastIndex).second("You cannot make this call") }
        assertNull(panel(stage))
        assertEquals(false, button(stage)?.isOn)
        assertTrue(texts(stage).contains("You cannot make this call"))
    }

    @Test
    fun aCancelThatFailedIsSaid() {
        rings = listOf(CallsAddPerson.Ring("r1", "43", "ringing", true))
        stage { stage ->
            opened(stage, listOf(ann))
            step { cancel(stage)!!.performClick() }
            step { cancelled.removeAt(cancelled.lastIndex).second("You cannot make this call") }
            assertNull(panel(stage))
            assertTrue(texts(stage).contains("You cannot make this call"))
        }
    }

    @Test
    fun aPressOfItsButtonClosesItAndAnotherPanelTakesItsPlace() = stage { stage ->
        opened(stage, listOf(ann))
        step { stage.showAddPerson() }
        assertNull("the same press closes it", panel(stage))

        step { stage.showAddPerson() }
        assertNotNull(panel(stage))
        step { stage.showPanel("Something else", rows = emptyList()) }
        assertNull("one at a time", panel(stage))
    }

    @Test
    fun itStaysOverACallBecomingAGroupCall() = stage { stage ->
        opened(stage, listOf(ann))
        rings = listOf(CallsAddPerson.Ring("r1", "43", "ringing", true))

        for (phase in listOf("connecting", "reconnecting", "in_progress")) {
            apply(stage, phase, group = true)
            assertNotNull(phase, panel(stage))
            assertTrue(phase, texts(stage).contains("Ringing…"))
        }
        assertEquals(View.VISIBLE, button(stage)?.visibility)
        assertEquals(true, button(stage)?.isOn)
    }

    @Test
    fun aChangeOfTheCallLeavesItsRowsWhereNothingOfThemChanged() = stage { stage ->
        opened(stage, listOf(ann))
        val before = row(stage, "Ann")
        apply(stage, "in_progress")
        assertTrue("a finger on one stays on it", row(stage, "Ann") === before)

        rings = listOf(CallsAddPerson.Ring("r1", "43", "busy", false))
        apply(stage, "in_progress")
        assertFalse(row(stage, "Ann") === before)
    }

    @Test
    fun itGoesWithTheCallAndWithAHostThatStopsGivingIt() = stage { stage ->
        opened(stage, listOf(ann))
        apply(stage, "idle")
        assertNull(panel(stage))
        assertEquals(View.GONE, button(stage)?.visibility)

        apply(stage, "in_progress")
        step { stage.showAddPerson() }
        assertNotNull(panel(stage))
        CallsAddPersonStore.current = null
        apply(stage, "in_progress")
        assertNull(panel(stage))
        assertEquals(View.GONE, button(stage)?.visibility)
    }

    @Test
    fun theAppArrangesItByItsId() {
        var ids = emptyList<String>()
        step {
            CallScreen.pieces = CallScreen.Pieces(controls = { _, ours, _ ->
                ids = ours.map { it.id }
                ours.filter { it.id != "add" }
            })
        }
        try {
            stage { stage ->
                assertTrue(ids.contains("add"))
                assertTrue(ids.indexOf("share") < ids.indexOf("add"))
                assertTrue(ids.indexOf("add") < ids.indexOf("end"))
                assertTrue("an app can take it away", button(stage)?.parent == null || button(stage)?.visibility != View.VISIBLE)
            }
        } finally {
            step { CallScreen.pieces = CallScreen.Pieces() }
        }
    }

    @Test
    fun theScreenOffersThoseListedLessWhomTheRuleRefusesAndAnyoneInTheCall() {
        val listed = listOf(
            CallPerson("1", "One"), CallPerson("2", "Two", "https://example.invalid/2.png"), CallPerson("3", "Three"), CallPerson("4", "Four"),
        )
        assertEquals(
            "no answer keeps them, as the ring still asks the rule",
            listOf(CallsAddPerson.Person("1", "One", ""), CallsAddPerson.Person("2", "Two", "https://example.invalid/2.png")),
            CallScreen.offered(listed, listOf(true, null, false, true), setOf("4")),
        )
        assertEquals("a route that gave no answers at all", 4, CallScreen.offered(listed, emptyList(), emptySet()).size)
        assertEquals(emptyList<CallsAddPerson.Person>(), CallScreen.offered(emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun aRingThatDidNotGoOutIsSaidAsACallThatDidNot() {
        assertEquals("You cannot make this call", CallScreen.addFailure(context, CallException("forbidden")))
        assertEquals("This call could not be connected", CallScreen.addFailure(context, CallException("not_connected")))
    }

    @Test
    fun theScreenSaysEachStateOfARingInTheAppsWords() = step {
        CallScreen.configure(CallScreen.Appearance(strings = mapOf("add_person_busy" to "Besetzt")))
        assertEquals("Besetzt", CallsAddPersonPanel.said(context, "busy"))
        assertEquals("Ringing…", CallsAddPersonPanel.said(context, "ringing"))
        assertEquals("", CallsAddPersonPanel.said(context, "something else"))
        CallScreen.configure(CallScreen.Appearance())
    }
}
