package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What keeps a direct call from escalating into a socket that cannot carry
 * it, and from giving up on a side that is slow rather than gone, as the web
 * engine does (realtime-client's calls-engine tests). A Firefox caller's
 * socket stalled for 47 s while its audio kept flowing, and its restart
 * arrived after the connection had come back (31 Oct 2026); a callee gave up
 * on a caller whose offer came nine seconds after the call had gone to the
 * room (the same day).
 */
class CallSocketWatchTest {
    @Test
    fun aSocketThatAnswersNothingForFiveSecondsIsStalledUntilAnythingIsAnswered() {
        val watch = CallSocketWatch()
        val t0 = 1_000_000L

        assertEquals(0L, watch.stalledForMs(t0))

        val pulse = watch.sent(t0)

        assertEquals(0L, watch.stalledForMs(t0 + 4_900))
        assertTrue(watch.stalledForMs(t0 + 5_100) >= 5_000)

        // Given up at its timeout: still stalled, since nothing came back.
        watch.settled(pulse, false, t0 + 8_000)
        assertTrue(watch.stalledForMs(t0 + 9_000) >= 9_000)

        // Something else goes and is answered: the socket carries answers again.
        val sync = watch.sent(t0 + 9_000)
        watch.settled(sync, true, t0 + 9_200)
        assertEquals(0L, watch.stalledForMs(t0 + 9_300))
    }

    @Test
    fun aStallCountsFromTheFirstOpThroughItsRetries() {
        val watch = CallSocketWatch()
        val t0 = 1_000_000L

        val first = watch.sent(t0)
        assertTrue(watch.stalledForMs(t0 + 6_000) >= 6_000)

        watch.settled(first, false, t0 + 8_000)
        watch.sent(t0 + 8_000)
        assertTrue(watch.stalledForMs(t0 + 9_000) >= 9_000)
    }

    @Test
    fun aLateAnswerEndsTheStallToo() {
        val watch = CallSocketWatch()
        val t0 = 1_000_000L

        val op = watch.sent(t0)
        watch.settled(op, false, t0 + 8_000)
        assertTrue(watch.stalledForMs(t0 + 8_500) > 0)

        // socket.io-client's ack can arrive after this side's own timeout.
        watch.settled(op, true, t0 + 9_000)
        assertEquals(0L, watch.stalledForMs(t0 + 9_100))
    }

    @Test
    fun aSlowSocketThatAnswersIsNotStalled() {
        val watch = CallSocketWatch()
        val t0 = 1_000_000L

        for (i in 0 until 10) {
            val at = t0 + i * 2_000L
            val op = watch.sent(at)

            assertEquals(0L, watch.stalledForMs(at + 1_000))
            watch.settled(op, true, at + 3_000)
            assertEquals(0L, watch.stalledForMs(at + 3_500))
        }

        assertEquals(3_000L, watch.roundTripMs)
    }

    @Test
    fun theRoundTripIsTheLongestOfTheLatestFive() {
        val watch = CallSocketWatch()
        var at = 1_000_000L

        assertEquals(0L, watch.roundTripMs)

        for (ms in listOf(100L, 900L, 200L, 300L, 250L, 150L, 120L)) {
            val op = watch.sent(at)
            at += ms
            watch.settled(op, true, at)
        }

        assertEquals(300L, watch.roundTripMs)
    }

    @Test
    fun thePoliteSideWaitsLongerOnASlowSocketUpToSixSeconds() {
        assertEquals(3_000L, CallPeer.politeWait(0L))
        assertEquals(4_800L, CallPeer.politeWait(900L))
        assertEquals(6_000L, CallPeer.politeWait(11_000L))
        assertEquals(3_000L, CallPeer.politeWait(-5L))
    }

    @Test
    fun aNegotiationIsWaitedForWhileTheOtherSideKeepsSpeakingUpToFiftyTwoSeconds() {
        // Never heard: given up at the deadline, as before.
        assertNull(CallPeer.negotiationGrace(26_000L, null))
        // Heard fifteen seconds ago: eleven more.
        assertEquals(11_000L, CallPeer.negotiationGrace(26_000L, 15_000L))
        // Silent for twenty-six: given up.
        assertNull(CallPeer.negotiationGrace(40_000L, 26_000L))
        // However lately heard, never past fifty-two from the birth.
        assertEquals(2_000L, CallPeer.negotiationGrace(50_000L, 1_000L))
        assertNull(CallPeer.negotiationGrace(52_000L, 1_000L))
    }
}
