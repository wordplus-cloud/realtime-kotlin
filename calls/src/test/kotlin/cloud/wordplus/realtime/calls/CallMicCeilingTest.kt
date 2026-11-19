package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** A direct call's microphone ceiling on a slow upload ([CallMicCeiling]), as the web's calls/mic-ceiling.ts. */
class CallMicCeilingTest {
    private val whole = CallShapes.MICROPHONE_MAX_BITRATE
    private val hold = CallMicCeiling.HOLD_MS

    /** A reading every two seconds, as the stats poll makes them, from `from` for `ms`. Answers how many changed it. */
    private fun readings(ceiling: CallMicCeiling, from: Long, ms: Long, estimate: Long?, video: Boolean): Int {
        var changes = 0
        var at = from

        while (at <= from + ms) {
            if (ceiling.review(at, estimate, video)) changes++
            at += 2000
        }

        return changes
    }

    private fun slowed(): CallMicCeiling {
        val ceiling = CallMicCeiling()

        readings(ceiling, 0, hold, CallMicCeiling.SLOW_BELOW - 1, true)
        assertEquals(CallMicCeiling.SLOW_BITRATE, ceiling.bitrate)

        return ceiling
    }

    @Test
    fun isTheWholeOfItToBeginWith() {
        assertEquals(whole, CallMicCeiling().bitrate)
    }

    @Test
    fun leavesACallsFirstSecondsAlone() {
        val ceiling = CallMicCeiling()

        assertEquals(0, readings(ceiling, 0, hold - 2000, 300_000, true))
        assertEquals(0, readings(ceiling, hold, 60_000, 3_000_000, true))
        assertEquals(whole, ceiling.bitrate)
    }

    @Test
    fun comesDownOnceWhenTheEstimateStaysLowWhileVideoGoesOut() {
        val ceiling = CallMicCeiling()

        assertEquals(1, readings(ceiling, 0, 60_000, CallMicCeiling.SLOW_BELOW - 1, true))
        assertEquals(CallMicCeiling.SLOW_BITRATE, ceiling.bitrate)
    }

    @Test
    fun startsItsWaitOverWhenAReadingSaysOtherwise() {
        val ceiling = CallMicCeiling()

        readings(ceiling, 0, hold - 2000, CallMicCeiling.SLOW_BELOW - 1, true)
        ceiling.review(hold, CallMicCeiling.SLOW_BELOW, true)
        readings(ceiling, hold + 2000, hold - 2000, CallMicCeiling.SLOW_BELOW - 1, true)
        assertEquals(whole, ceiling.bitrate)
    }

    @Test
    fun keepsAllOfItWithNoVideoGoingOutHoweverLowTheEstimate() {
        val ceiling = CallMicCeiling()

        assertEquals(0, readings(ceiling, 0, 600_000, 60_000, false))
        assertEquals(whole, ceiling.bitrate)
    }

    @Test
    fun keepsAllOfItWhereThereIsNoEstimate() {
        val ceiling = CallMicCeiling()

        assertEquals(0, readings(ceiling, 0, 600_000, null, true))
        assertEquals(whole, ceiling.bitrate)
    }

    @Test
    fun doesNotStartItsWaitOverForAReadingWithNoEstimate() {
        val ceiling = CallMicCeiling()

        ceiling.review(0, CallMicCeiling.SLOW_BELOW - 1, true)
        ceiling.review(4000, null, true)
        ceiling.review(hold, CallMicCeiling.SLOW_BELOW - 1, true)
        assertEquals(CallMicCeiling.SLOW_BITRATE, ceiling.bitrate)
    }

    @Test
    fun staysDownBetweenTheTwoMarks() {
        val ceiling = slowed()

        assertEquals(0, readings(ceiling, 100_000, 600_000, CallMicCeiling.BACK_AT - 1, true))
        assertEquals(CallMicCeiling.SLOW_BITRATE, ceiling.bitrate)
    }

    @Test
    fun goesBackUpOnceTheEstimateHoldsClearOfTheMark() {
        val ceiling = slowed()

        assertEquals(0, readings(ceiling, 100_000, hold - 2000, CallMicCeiling.BACK_AT, true))
        assertEquals(1, readings(ceiling, 100_000 + hold, 60_000, CallMicCeiling.BACK_AT, true))
        assertEquals(whole, ceiling.bitrate)
    }

    @Test
    fun goesBackUpWhenNoVideoGoesOutAndAShortPauseLeavesItDown() {
        val ceiling = slowed()

        assertEquals(0, readings(ceiling, 100_000, hold - 2000, 300_000, false))
        assertEquals(CallMicCeiling.SLOW_BITRATE, ceiling.bitrate)
        ceiling.review(110_000, 300_000, true)
        assertEquals(1, readings(ceiling, 200_000, 60_000, 300_000, false))
        assertEquals(whole, ceiling.bitrate)
    }

    /** What the estimate read at each ceiling through a shaped upload (calls/mic-ceiling.ts): 850 and 818 on 1000 kbps, 966 and 1028 on 1200. */
    @Test
    fun aRoomsMarksComeDownOn1000KbpsStayDownThereAndLeave1200KbpsAlone() {
        val ceiling = CallMicCeiling(CallMicCeiling.ROOM_SLOW_BELOW, CallMicCeiling.ROOM_BACK_AT)
        assertEquals(0, readings(ceiling, 0, 60_000, 966_000, true))
        assertEquals(1, readings(ceiling, 100_000, 60_000, 850_000, true))
        assertEquals(true, ceiling.lowered)
        assertEquals(0, readings(ceiling, 200_000, 60_000, 818_000, true))
        assertEquals(1, readings(ceiling, 300_000, 60_000, 1_028_000, true))
        assertEquals(false, ceiling.lowered)
    }

    /** What a room reads of its microphone, 125 packets apart as at 25 a second and a reading every 5 s. */
    @Test
    fun aLossSaysNothingOnItsFirstReadingAndThenReadsFromTheLastOne() {
        val loss = CallMicLoss()

        assertEquals(false, loss.losing(125, 50))
        assertEquals(false, loss.losing(250, 50))
        assertEquals(true, loss.losing(375, 55))
        assertEquals(false, loss.losing(500, 59))
    }

    @Test
    fun aLossTakesNothingOfAQuietMicrophoneOrOfAClientThatGivesNone() {
        val quiet = CallMicLoss()

        assertEquals(false, quiet.losing(0, 0))
        assertEquals(false, quiet.losing(49, 25))

        val none = CallMicLoss()

        assertEquals(false, none.losing(125, null))
        assertEquals(false, none.losing(250, null))
    }

    @Test
    fun aQueueReadsTheWaitSinceTheLastReadingAndANewSenderFromNothing() {
        val loss = CallMicLoss()

        assertEquals(false, loss.queued(100, 0.0))
        assertEquals(true, loss.queued(200, 10.0))
        assertEquals(false, loss.queued(300, 15.0))
        assertEquals(false, loss.queued(300, 40.0))
        assertEquals(false, loss.queued(100, 5.0))
        assertEquals(true, loss.queued(200, 25.0))
    }

    @Test
    fun aLossReadsAReconnectsNewSenderFromItsFirstReading() {
        val loss = CallMicLoss()

        assertEquals(false, loss.losing(5000, 100))
        assertEquals(false, loss.losing(125, 10))
        assertEquals(true, loss.losing(250, 30))
    }

    /**
     * A reading of a room's microphone says more than whether it is lost: whether the person talks, whether the loss is
     * past what a spare copy hides, and what the microphone put on the line.
     */
    @Test
    fun aReadingTellsTalkingHeavyLossAndWhatWentOnTheWire() {
        val loss = CallMicLoss()

        assertEquals(CallMicLoss.Heard(), loss.hearing(0, 0, 0, 0))

        // 125 packets in 5 s, 8% of them lost: talking, lossy, not heavy; 67,500 bytes and 125 packets' headers on the wire
        val light = loss.hearing(5000, 125, 10, 67_500)

        assertEquals(true, light.talking)
        assertEquals(true, light.lossy)
        assertEquals(false, light.heavy)
        assertEquals((67_500 + 125 * 38) * 8 / 5.0, light.wire ?: 0.0, 0.5)

        // 12% and more: heavy
        assertEquals(true, loss.hearing(10_000, 250, 25, 135_000).heavy)

        // a quiet microphone says nothing of loss, and still says what it put on the wire
        val quiet = loss.hearing(15_000, 260, 30, 136_000)

        assertEquals(false, quiet.talking)
        assertEquals(false, quiet.lossy)
        assertEquals(true, quiet.wire != null)

        // a client that doesn't count its bytes: nothing said of the wire
        assertEquals(null, loss.hearing(20_000, 385, 30, null).wire)
    }

    /** Beside video, sound lost past what its spare copy hides brings the ceiling down sooner, and no sooner back up. */
    @Test
    fun comesDownSoonerOnAShorterHoldAndGoesBackUpOnTheWholeOne() {
        val heavy = CallMicCeiling(CallMicCeiling.ROOM_SLOW_BELOW, CallMicCeiling.ROOM_BACK_AT)
        val short = CallMicCeiling.HEAVY_HOLD_MS

        assertEquals(false, heavy.review(0, 300_000, true, short))
        assertEquals(true, heavy.review(5000, 300_000, true, short))
        assertEquals(true, heavy.lowered)

        // the whole hold on the way up, whatever the shorter one says
        assertEquals(false, heavy.review(10_000, 2_000_000, true, short))
        assertEquals(false, heavy.review(15_000, 2_000_000, true, short))
        assertEquals(true, heavy.review(20_000, 2_000_000, true, short))
        assertEquals(false, heavy.lowered)

        // and never a longer one than the hold
        val long = CallMicCeiling()

        assertEquals(false, long.review(0, 300_000, true, 60_000))
        assertEquals(true, long.review(hold, 300_000, true, 60_000))
    }

    /**
     * A room's microphone alone: no estimate to read, so its loss is given with 0, which stands under every mark, and
     * having come down alone is what keeps it there.
     */
    @Test
    fun comesDownAloneOnItsLossAndStaysDownWhileThatIsSaid() {
        val ceiling = CallMicCeiling(CallMicCeiling.ROOM_SLOW_BELOW, CallMicCeiling.ROOM_BACK_AT)

        assertEquals(0, readings(ceiling, 0, 60_000, 0, false))
        assertEquals(1, readings(ceiling, 100_000, 60_000, 0, true))
        assertEquals(true, ceiling.lowered)
        assertEquals(0, readings(ceiling, 200_000, 600_000, 0, true))
        assertEquals(true, ceiling.lowered)
    }
}
