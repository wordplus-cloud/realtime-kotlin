package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The picture giving way to the sound a step at a time ([CallPictureSteps]), as the web's calls/picture-steps.ts. */
class CallPictureStepsTest {
    private val down = CallPictureSteps.DOWN_MS
    private val up = CallPictureSteps.UP_MS
    private val relapse = CallPictureSteps.RELAPSE_MS
    private val last = CallPictureSteps.CAMERA_STEPS.size
    private val suffering = CallPictureSteps.Reading.SUFFERING
    private val clean = CallPictureSteps.Reading.CLEAN
    private val idle = CallPictureSteps.Reading.IDLE

    /** Readings every 5 s, as a room's microphone makes them, from `from` to `to`, all saying the same. Answers the moments a step changed at. */
    private fun say(steps: CallPictureSteps, from: Long, to: Long, reading: CallPictureSteps.Reading, fitting: Int = 0): List<Long> {
        val moved = ArrayList<Long>()
        var at = from

        while (at <= to) {
            if (steps.review(at, reading, fitting)) moved.add(at)
            at += 5000
        }

        return moved
    }

    @Test
    fun takesAStepOnceTheSoundHasSufferedForTheHoldAndTheNextOnlyAfterAsLongAgain() {
        val steps = CallPictureSteps()

        assertEquals(emptyList<Long>(), say(steps, 0, 0, suffering))
        assertEquals(0, steps.step)
        assertEquals(listOf(down, 2 * down), say(steps, down, 2 * down, suffering))
        assertEquals(2, steps.step)
    }

    @Test
    fun stopsAtItsLastStepHoweverLongTheSoundSuffers() {
        val steps = CallPictureSteps()

        say(steps, 0, 120_000, suffering)
        assertEquals(last, steps.step)
    }

    @Test
    fun startsItsWaitOverWhenAReadingSaysOtherwiseAndHoldsThroughReadingsThatSayNothing() {
        val steps = CallPictureSteps()

        say(steps, 0, 0, suffering)
        say(steps, 5000, 5000, clean)
        assertEquals(emptyList<Long>(), say(steps, 10_000, 10_000, suffering))
        assertEquals(listOf(15_000L), say(steps, 15_000, 15_000, suffering))

        // silence between two halves of the wait: neither a step nor a new start
        val quiet = CallPictureSteps()

        say(quiet, 0, 0, suffering)
        say(quiet, 5000, 60_000, idle)
        assertEquals(0, quiet.step)
        assertEquals(listOf(65_000L), say(quiet, 65_000, 65_000, suffering))
    }

    @Test
    fun comesBackAStepForEveryWaitTheSoundStaysCleanThePersonTalking() {
        val steps = CallPictureSteps()

        say(steps, 0, 2 * down, suffering)
        assertEquals(2, steps.step)

        val from = 2 * down + 5000

        assertEquals(emptyList<Long>(), say(steps, from, from + up - 5000, clean))
        assertEquals(listOf(from + up, from + 2 * up), say(steps, from + up, from + 2 * up, clean))
        assertEquals(0, steps.step)
    }

    @Test
    fun staysWhereItIsThroughSilence() {
        val steps = CallPictureSteps()

        say(steps, 0, down, suffering)
        say(steps, down + 5000, 600_000, idle)
        assertEquals(1, steps.step)
    }

    @Test
    fun waitsTwiceAsLongForTheNextStepUpAfterOneThatBroughtTheTroubleBackUpToItsLongest() {
        val steps = CallPictureSteps()
        var at = 0L

        fun run(ms: Long, reading: CallPictureSteps.Reading) {
            say(steps, at, at + ms, reading)
            at += ms + 5000
        }

        run(down, suffering)
        assertEquals(1, steps.step)
        run(up, clean)
        assertEquals(0, steps.step)
        // the trouble is back straight after the step up: taken back by that reading
        run(0, suffering)
        assertEquals(1, steps.step)
        assertEquals(2 * up, steps.wait)
        // not before the doubled wait is over
        assertEquals(emptyList<Long>(), say(steps, at, at + 2 * up - 5000, clean))
        at += 2 * up
        assertEquals(true, steps.review(at, clean))
        at += 5000

        repeat(12) {
            run(0, suffering)
            run(steps.wait, clean)
        }

        assertEquals(CallPictureSteps.UP_MAX_MS, steps.wait)
    }

    @Test
    fun takesAStepUpBackAtTheFirstReadingThatSaysItCostTheSoundAndPastItsTrialWaitsTheHoldAgain() {
        val steps = CallPictureSteps()

        say(steps, 0, down, suffering)

        val lifted = down + 5000 + up

        assertEquals(listOf(lifted), say(steps, down + 5000, lifted, clean))
        assertEquals(0, steps.step)

        // on trial: no hold, and back the one step it came up, whatever is said to fit
        assertEquals(true, steps.review(lifted + 5000, suffering, 3))
        assertEquals(1, steps.step)
        assertEquals(2 * up, steps.wait)

        // a step up that has held its trial: trouble after it is a step down like any other
        val held = CallPictureSteps()

        say(held, 0, down, suffering)
        say(held, down + 5000, lifted + relapse, clean)
        assertEquals(0, held.step)

        val again = lifted + relapse + 5000

        assertEquals(emptyList<Long>(), say(held, again, again, suffering))
        assertEquals(listOf(again + down), say(held, again + down, again + down, suffering))
    }

    @Test
    fun goesStraightDownToTheFirstStepSaidToFitAndNoFurtherWhileThatIsSaid() {
        val steps = CallPictureSteps()

        assertEquals(listOf(down), say(steps, 0, down, suffering, 2))
        assertEquals(2, steps.step)
        // the line is said to carry that step, or more: still suffering, the picture isn't what loses the sound
        assertEquals(emptyList<Long>(), say(steps, down + 5000, 60_000, suffering, 2))
        assertEquals(emptyList<Long>(), say(steps, 65_000, 80_000, suffering, 1))
        assertEquals(2, steps.step)
        // said to carry less after all: the next step at once, its wait long over
        assertEquals(listOf(85_000L), say(steps, 85_000, 85_000, suffering, 3))
        assertEquals(3, steps.step)

        // said to carry the first step: none is taken from the whole picture, however long the sound suffers
        val whole = CallPictureSteps()

        assertEquals(emptyList<Long>(), say(whole, 0, 60_000, suffering, 1))
        assertEquals(0, whole.step)

        val far = CallPictureSteps()

        say(far, 0, down, suffering)
        assertEquals(1, far.step)
        say(far, down + 5000, down + 5000, clean)
        say(far, down + 10_000, down + 10_000 + down, suffering, 9)
        assertEquals(last, far.step)
    }

    @Test
    fun goesWithAMicrophoneThatCameDownForHeavyLossWhereNotEvenItsFirstStepIsSaidToFit() {
        val steps = CallPictureSteps()

        // nothing says, or the first step fits: the microphone alone first
        assertEquals(false, steps.giveWay(0, 0))
        assertEquals(false, steps.giveWay(0, 1))
        assertEquals(0, steps.step)

        assertEquals(true, steps.giveWay(0, 2))
        assertEquals(2, steps.step)
        assertEquals(false, steps.giveWay(5000, 2))
        // the next step waits its hold from there
        assertEquals(false, steps.review(down - 1000, suffering, 3))
        assertEquals(true, steps.review(down, suffering, 3))
        assertEquals(3, steps.step)

        val all = CallPictureSteps()

        assertEquals(true, all.giveWay(0, 9))
        assertEquals(last, all.step)
    }

    @Test
    fun goesBackToItsFirstWaitOnceAStepUpToTheWholePictureHasHeld() {
        val steps = CallPictureSteps()
        var at = 0L

        fun run(ms: Long, reading: CallPictureSteps.Reading) {
            say(steps, at, at + ms, reading)
            at += ms + 5000
        }

        run(down, suffering)
        run(up, clean)
        run(0, suffering)
        assertEquals(2 * up, steps.wait)
        run(2 * up, clean)
        assertEquals(0, steps.step)
        run(relapse + down + 5000, clean)
        assertEquals(up, steps.wait)
    }

    @Test
    fun aStepsCeilingIsACamerasSizeFramesAndBitrate() {
        assertNull(CallPictureSteps.caps(0))
        assertEquals(CallPictureSteps.CAMERA_STEPS[0], CallPictureSteps.caps(1))
        assertEquals(CallPictureSteps.CAMERA_STEPS[last - 1], CallPictureSteps.caps(9))
        assertEquals(listOf(150, 50, 30), CallPictureSteps.CAMERA_STEPS.map { it.kbps })
    }

    @Test
    fun fitsALineAtTheFirstStepWhoseCameraItCarries() {
        assertEquals(1, CallPictureSteps.fitting(true, 200.0))
        assertEquals(2, CallPictureSteps.fitting(true, 149.0))
        assertEquals(3, CallPictureSteps.fitting(true, 49.0))
        assertEquals(last, CallPictureSteps.fitting(true, 5.0))
        // nothing to go by: no figure, or no camera going out
        assertEquals(0, CallPictureSteps.fitting(true, null))
        assertEquals(0, CallPictureSteps.fitting(false, 100.0))
    }
}
