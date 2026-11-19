package cloud.wordplus.realtime.calls

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Joining a group call again after being taken out of it, as the browser's (realtime-client calls.ts:425-431): the
 * realtime server refuses a token signed in the second of that. The Swift package's GroupAfterOutTests' twin. With no
 * server.
 */
@RunWith(AndroidJUnit4::class)
class GroupAfterOutTest {
    private suspend fun taken(s: StandIn, reason: String): Long {
        val first = s.calls.group("meeting:7", CallType.AUDIO)
        withContext(Dispatchers.Main) { first.finish(reason) }
        return System.currentTimeMillis()
    }

    private suspend fun leave(group: GroupCall) = withContext(Dispatchers.Main) { group.leave() }

    @Test
    fun joiningAgainAfterBeingRemovedAsksInTheNextSecond() = runBlocking {
        val s = StandIn()
        val out = taken(s, "removed")
        leave(s.calls.group("meeting:7", CallType.AUDIO))
        assertTrue("not in the second it was removed", s.bodiesAt.last() >= (out / 1000 + 1) * 1000)
    }

    @Test
    fun joiningAgainAfterItWasClosedAsksInTheNextSecond() = runBlocking {
        val s = StandIn()
        val out = taken(s, "closed")
        leave(s.calls.group("meeting:7", CallType.AUDIO))
        assertTrue(s.bodiesAt.last() >= (out / 1000 + 1) * 1000)
    }

    @Test
    fun joiningAgainAfterLeavingWaitsForNothing() = runBlocking {
        val s = StandIn()
        leave(s.calls.group("meeting:7", CallType.AUDIO))
        val before = System.currentTimeMillis()
        leave(s.calls.group("meeting:7", CallType.AUDIO))
        assertTrue(s.bodiesAt.last() - before < 100)
    }

    @Test
    fun anotherCallWaitsForNothing() = runBlocking {
        val s = StandIn()
        taken(s, "removed")
        val before = System.currentTimeMillis()
        leave(s.calls.group("meeting:8", CallType.AUDIO))
        assertTrue(s.bodiesAt.last() - before < 100)
    }

    @Test
    fun theWaitIsOnce() = runBlocking {
        val s = StandIn()
        taken(s, "removed")
        leave(s.calls.group("meeting:7", CallType.AUDIO))
        val before = System.currentTimeMillis()
        leave(s.calls.group("meeting:7", CallType.AUDIO))
        assertTrue(s.bodiesAt.last() - before < 100)
    }
}
