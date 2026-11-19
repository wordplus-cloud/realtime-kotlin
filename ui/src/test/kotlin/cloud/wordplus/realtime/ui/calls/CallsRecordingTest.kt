package cloud.wordplus.realtime.ui.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A group call's recording on the call screens, as the web's recording.tsx draws it: who is told and asked under each of
 * the site's ways of telling, the words, the time and the start's facts. The words are the English the screens fall back
 * to. Run on the JVM: `./gradlew testDebugUnitTest` in ui/.
 */
class CallsRecordingTest {
    private val say: (String, String) -> String = { _, fallback -> fallback }

    private fun on(
        tell: String = "notice",
        mine: Boolean = false,
        answer: String = "",
        notice: Boolean = true,
        can: Boolean = false,
        state: String = "on",
        id: String = "7",
        by: String = "Ann",
    ) = CallsRecording(state = state, id = id, can = can, tell = tell, by = by, mine = mine, notice = notice, answer = answer)

    @Test
    fun eachWayOfTellingAsTheWebTellsIt() {
        // REC alone: nobody is told or asked more.
        assertFalse(on(tell = "indicator").told)
        assertTrue(on(tell = "indicator").pillShown)

        // Only moderators know: a member sees no REC and is told nothing; a moderator sees REC.
        assertFalse(on(tell = "moderators").pillShown)
        assertFalse(on(tell = "moderators").told)
        assertTrue(on(tell = "moderators", can = true).pillShown)

        // A notice, until it is seen or turned off for good.
        assertTrue(on(tell = "notice").noticeShown)
        assertFalse(on(tell = "notice").asking)
        assertFalse(on(tell = "notice", answer = "seen").noticeShown)
        assertFalse(on(tell = "notice", notice = false).noticeShown)

        // The question, until they agree.
        assertTrue(on(tell = "ask").asking)
        assertTrue(on(tell = "muted").asking)
        assertFalse(on(tell = "muted", answer = "agreed").asking)
        assertFalse(on(tell = "ask").noticeShown)

        // Whoever started it is told and asked nothing, and nothing is told before it records.
        assertFalse(on(tell = "ask", mine = true).asking)
        assertFalse(on(tell = "notice", mine = true).noticeShown)
        assertFalse(on(tell = "ask", state = "starting").asking)
        assertFalse(on(tell = "ask", id = "").asking)

        assertEquals("notice", CallsRecording.tellOf("shout"))
        assertEquals("on", CallsRecording.stateOf("recording"))
        assertEquals("off", CallsRecording.stateOf("finishing"))
    }

    @Test
    fun joinedWhileItRecordedOrThereAsItStarted() {
        val there = CallsRecordingLate()
        there.see(true, on(state = "off"))
        there.see(true, on())
        assertFalse(there.isLate("7"))

        val joined = CallsRecordingLate()
        joined.see(false, on())
        joined.see(true, on())
        assertTrue(joined.isLate("7"))

        val said = on().copy(watch = "people")
        assertEquals("Ann started recording this call", said.noticeTitle(false, say))
        assertEquals("This call is being recorded", said.noticeTitle(true, say))
        assertEquals("Ann started recording. The people who were in the call can watch it afterwards.", said.noticeNote(true, say))
        assertEquals("The people who were in the call can watch it afterwards.", said.noticeNote(false, say))
    }

    @Test
    fun theQuestionAndTheModeratorsWords() {
        assertEquals("Ann started recording. Your microphone and camera stay off until you agree to be recorded.", on(tell = "muted").questionLead(say))
        assertEquals("I Agree", on(tell = "muted").questionYes(say))
        assertEquals("If you stay in the call, you agree to be recorded.", on(tell = "ask", by = "").questionLead(say))
        assertEquals("Stay in Call", on(tell = "ask").questionYes(say))

        assertEquals("Asks you first. Nobody else in the call is told.", on(tell = "moderators").startNote(say))
        assertEquals("Asks you first. Everyone in the call then sees REC beside the call controls.", on(tell = "indicator").startNote(say))
        assertEquals("Site administrators can watch it in a few minutes.", on().copy(watch = "admins").readyLead(say))
        assertEquals("", on().copy(watch = "admins").watchNote(say))
        assertEquals(
            "Everyone in the conversation can watch it afterwards. A transcript is made too.",
            on().copy(watch = "everyone", transcript = true).watchNote(say)
        )
    }

    @Test
    fun theTimeAndTheStartsFacts() {
        assertEquals("0:00", CallsRecording.elapsed(0))
        assertEquals("12:04", CallsRecording.elapsed(724))
        assertEquals("1:02:05", CallsRecording.elapsed(3725))
        assertEquals("0:00", CallsRecording.elapsed(-5))

        val video = on().copy(size = 720, layout = "gallery", transcript = true, language = "auto", watch = "moderators", keepDays = 0)
        assertEquals(
            listOf("Picture: 720p, Gallery layout", "Transcript: Automatic", "Who can watch: Moderators", "Kept: Until deleted"),
            video.facts(false, say).map { it.first + ": " + it.second }
        )

        // An audio call's picture only where the site has its layout; a language and days only in the host's words.
        val audio = on().copy(size = 1080, transcript = true, language = "uk", keepDays = 30, keptWords = "30 днів", languageWords = "українська")
        assertEquals(listOf("Transcript: українська", "Kept: 30 днів"), audio.facts(true, say).map { it.first + ": " + it.second })
        assertEquals(0, on().copy(keepDays = 30, transcript = true, language = "uk").facts(false, say).size)
    }
}
