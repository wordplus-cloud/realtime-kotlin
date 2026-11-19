package cloud.wordplus.realtime.ui

import cloud.wordplus.realtime.calls.GroupRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A group call's recording as the SDK's CallScreen names it to the screens (the state's `recording`): Start for a host,
 * its answer, REC from the room's flag for everyone, and what a refusal leaves. The same cases as realtime-swift's
 * CallRecordingStateTests. Who is told what from there is CallsRecordingTest. Run on the JVM.
 */
class CallRecordingStateTest {
    private val rec = GroupRecording("rec_0123456789", "recording")

    @Test
    fun nothingForSomeoneWhoMayNotWhileNothingRecords() {
        assertNull(CallRecordingState().state(can = false, notice = true))
        val host = CallRecordingState().state(can = true, notice = true)
        assertEquals("off", host?.get("state"))
        assertEquals(true, host?.get("can"))
    }

    @Test
    fun aHostStartsAndStopsIt() {
        val state = CallRecordingState()
        state.ask("starting")
        assertEquals("starting", state.state(true, true)?.get("state"))

        state.started(rec, 1_000)
        var named = state.state(true, true)
        assertEquals("on", named?.get("state"))
        assertEquals("rec_0123456789", named?.get("id"))
        assertEquals(1_000L, named?.get("since"))
        assertEquals(true, named?.get("mine"))
        assertEquals(true, named?.get("can"))

        // The room says so a moment later: the same recording, not another.
        state.flagged(true, 1_002)
        assertEquals("rec_0123456789", state.state(true, true)?.get("id"))
        assertEquals(1_000L, state.state(true, true)?.get("since"))

        state.ask("stopping")
        assertEquals("stopping", state.state(true, true)?.get("state"))

        // Stopped, and still stopping until the room says it has.
        state.stopped()
        named = state.state(true, true)
        assertEquals("stopping", named?.get("state"))
        assertEquals("rec_0123456789", named?.get("id"))
        state.flagged(false, 1_060)
        assertEquals("off", state.state(true, true)?.get("state"))
    }

    @Test
    fun aRoomThatNeverSaidSoStopsAtOnce() {
        val state = CallRecordingState()
        state.started(rec, 1_000)
        state.stopped()
        assertEquals("off", state.state(true, true)?.get("state"))
    }

    @Test
    fun aRefusalLeavesItAsItWas() {
        val state = CallRecordingState()
        state.ask("starting")
        state.refused()
        assertEquals("off", state.state(true, true)?.get("state"))

        state.started(rec, 1_000)
        state.ask("stopping")
        state.refused()
        assertEquals("on", state.state(true, true)?.get("state"))
    }

    @Test
    fun someoneElsesRecordingIsRecWithNoStop() {
        // Joined while it records, or there as someone else started it: the room's flag names no id to stop.
        val state = CallRecordingState(flag = true, now = 2_000)
        var named = state.state(true, true)
        assertEquals("on", named?.get("state"))
        assertEquals(false, named?.get("can"))
        assertNull(named?.get("mine"))
        assertEquals("notice", named?.get("tell"))
        assertEquals(2_000L, named?.get("since"))
        val first = named?.get("id")

        // The next one is told again, as the answers are kept by its id.
        state.flagged(false, 2_100)
        assertNull(state.state(false, true))
        state.flagged(true, 2_200)
        named = state.state(false, true)
        assertNotEquals(first, named?.get("id"))
    }

    @Test
    fun theRoomStoppingItEndsOurOwnToo() {
        val state = CallRecordingState()
        state.started(rec, 1_000)
        state.flagged(true, 1_001)
        state.flagged(false, 1_100)
        assertEquals("off", state.state(true, true)?.get("state"))
        assertNull(state.own)
    }

    @Test
    fun doNotShowAgainLeavesRecAlone() {
        assertEquals(true, CallRecordingState(true, 1).state(false, notice = true)?.get("notice"))
        assertEquals(false, CallRecordingState(true, 1).state(false, notice = false)?.get("notice"))
    }
}
