package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** Whether the upload this side sends on is jammed ([CallUploadJam]), as the web's calls/upload-jam.ts. */
class CallUploadJamTest {
    /** A reading of the connection: its round trip (ms, null where the client gives none), whether a check was answered since the last, and whether anything arrived. */
    private class Step(val rtt: Long?, val answered: Boolean = true, val arrives: Boolean = true)

    /** The connection's counts as they stand, and when the next reading is made. */
    private class Line(var at: Long = 0) {
        var requests = 0L
        var responses = 0L
        var bytes = 0L
    }

    /** Readings every 5 s, as a room's microphone makes them. Answers each change with its moment. */
    private fun play(jam: CallUploadJam, steps: List<Step>, line: Line): List<Pair<Long, Boolean>> {
        val changes = ArrayList<Pair<Long, Boolean>>()

        for (step in steps) {
            line.requests += 1
            if (step.answered) line.responses += 1
            if (step.arrives) line.bytes += 4000

            if (jam.review(CallUploadJam.Reading(line.at, step.rtt, line.requests, line.responses, line.bytes))) changes.add(line.at to jam.jammed)

            line.at += 5000
        }

        return changes
    }

    private fun times(count: Int, step: Step) = List(count) { step }

    @Test
    fun isNotACleanLine() {
        assertEquals(emptyList<Pair<Long, Boolean>>(), play(CallUploadJam(), times(300, Step(20)), Line()))
    }

    /** A 400 kbit/s line with a microphone in RED on it: a queue of the call's own of 1.1-1.7 s, for minutes. */
    @Test
    fun isNotANarrowLineTheCallItselfFills() {
        val own = listOf(529L, 575, 559, 1102, 1407, 1554, 378, 1071, 1353, 1522, 1618, 1638, 1495, 1598, 1557, 1581, 1603)

        assertEquals(emptyList<Pair<Long, Boolean>>(), play(CallUploadJam(), own.flatMap { times(3, Step(it)) }, Line()))
    }

    @Test
    fun isARoundTripOfSecondsThatLasts() {
        val jam = CallUploadJam()
        val line = Line()

        play(jam, times(3, Step(20)), line)
        // a moment's spike, gone before it has lasted: nothing to tell
        assertEquals(emptyList<Pair<Long, Boolean>>(), play(jam, listOf(Step(CallUploadJam.RTT_MS + 500), Step(20)), line))
        assertEquals(false, jam.jammed)

        val began = line.at

        // told once every reading has said so for the wait: the second reading after the first, 5 s apart
        assertEquals(listOf(began + 10_000 to true), play(jam, times(4, Step(26_090)), line))
    }

    /** The answer to a check waits in the same queue: no answer at all is the round trip that hasn't come yet. */
    @Test
    fun isChecksUnansweredWhileWhatIsSentToItStillArrives() {
        val jam = CallUploadJam()
        val line = Line()

        play(jam, times(3, Step(20)), line)

        val lastAnswer = line.at - 5000
        val changes = play(jam, times(10, Step(20, answered = false)), line)

        // unanswered from the first reading 6 s after the last answer (10 s, at readings 5 s apart), and then for the wait (10 s more)
        assertEquals(listOf(lastAnswer + 20_000 to true), changes)
    }

    @Test
    fun isNotAConnectionThatIsGoneWhichTheCallShowsAsReconnecting() {
        val jam = CallUploadJam()
        val line = Line()

        play(jam, times(3, Step(20)), line)
        assertEquals(emptyList<Pair<Long, Boolean>>(), play(jam, times(30, Step(20, answered = false, arrives = false)), line))
    }

    @Test
    fun clearsAfterItsRoundTripIsQuickAgainForAWhileAndASlowReadingStartsTheWaitAgain() {
        val jam = CallUploadJam()
        val line = Line()

        play(jam, times(4, Step(30_000)), line)
        assertEquals(true, jam.jammed)
        play(jam, listOf(Step(40), Step(40), Step(2500)), line)
        assertEquals(true, jam.jammed)

        val began = line.at

        assertEquals(listOf(began + CallUploadJam.CLEAR_FOR_MS to false), play(jam, times(4, Step(40)), line))
    }

    @Test
    fun staysThroughAResumeWhoseNewConnectionCountsFromNothing() {
        val jam = CallUploadJam()

        play(jam, times(4, Step(30_000)), Line())
        assertEquals(true, jam.jammed)

        // a new pair: its counts start again, and nothing is decided from its first reading
        assertEquals(emptyList<Pair<Long, Boolean>>(), play(jam, listOf(Step(30_000)), Line(100_000)))
        assertEquals(true, jam.jammed)
    }

    @Test
    fun aClientThatGivesNoRoundTripIsToldByItsChecksAndClearsOnThemAnswered() {
        val jam = CallUploadJam()
        val line = Line()

        play(jam, times(3, Step(null)), line)
        assertEquals(listOf(true), play(jam, times(6, Step(null, answered = false)), line).map { it.second })
        assertEquals(listOf(false), play(jam, times(4, Step(null)), line).map { it.second })
    }
}
