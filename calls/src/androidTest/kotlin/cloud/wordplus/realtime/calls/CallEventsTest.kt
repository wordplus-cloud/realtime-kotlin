package cloud.wordplus.realtime.calls

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `onCall` and `onGroup`, as the browser's calls fire `call` and `group` (realtime-client calls.ts:186, 215, 232, 358):
 * each call this app started or answered and each group call it joined, whoever asked for it, as a call screen does. The
 * Swift package's CallEventsTests' twin. With no server: the route and the socket are stand-ins, and the test's app has
 * no microphone to open.
 */
@RunWith(AndroidJUnit4::class)
class CallEventsTest {
    private val ring = JSONObject().put("call", "support:34:42").put("from", 34).put("type", "audio")

    @Test
    fun aGroupCallJoinedIsHeard() = runBlocking {
        val s = StandIn()
        val heard = ArrayList<GroupCall>()
        s.calls.onGroup { heard.add(it) }
        mainSettled()

        val group = s.calls.group("meeting:7", CallType.AUDIO)
        mainSettled()
        assertEquals(1, heard.size)
        assertSame(group, heard.first())
        group.leave()
    }

    @Test
    fun aGroupCallRefusedIsNotHeard() = runBlocking {
        val s = StandIn()
        s.answer = { event, _ -> if (event == "room") json("""{"ok":false,"error":"full"}""") else json("""{"ok":true}""") }
        var heard = 0
        s.calls.onGroup { heard++ }
        mainSettled()

        assertRefused("full") { s.calls.group("meeting:7") }
        mainSettled()
        assertEquals(0, heard)
    }

    @Test
    fun aRingIntoAGroupCallAnsweredIsHeardAsAGroupCall() = runBlocking {
        val s = StandIn()
        var calls = 0
        val groups = ArrayList<GroupCall>()
        s.calls.onCall { calls++ }
        s.calls.onGroup { groups.add(it) }
        mainSettled()

        val incoming = IncomingCall(s.calls, JSONObject(ring.toString()).put("id", "ring-1").put("group", "g-1"), JsonNull)
        val answered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { incoming.acceptNow() }
        mainSettled()
        val group = (answered as IncomingCall.Answered.ToGroup).group
        assertEquals(0, calls)
        assertSame(group, groups.first())
        group.leave()
    }

    @Test
    fun aCallStartedIsHeardOnceItIsJoined() = runBlocking {
        val s = StandIn()
        val heard = ArrayList<Call>()
        s.calls.onCall { heard.add(it) }
        mainSettled()

        val call = s.calls.start("support:34:42", "34", CallType.AUDIO)
        mainSettled()
        assertEquals(1, heard.size)
        assertSame(call, heard.first())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { call.end() }
    }

    @Test
    fun aCallAnsweredIsHeard() = runBlocking {
        val s = StandIn()
        val heard = ArrayList<Call>()
        s.calls.onCall { heard.add(it) }
        mainSettled()

        val incoming = IncomingCall(s.calls, JSONObject(ring.toString()).put("id", "ring-2"), JsonNull)
        val answered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { incoming.acceptNow() }
        mainSettled()
        val call = (answered as IncomingCall.Answered.ToCall).call
        assertSame(call, heard.first())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { call.end() }
    }

    @Test
    fun aCallRefusedIsNotHeard() = runBlocking {
        val s = StandIn(route = { 200 to json("""{"denied":"forbidden"}""") })
        var heard = 0
        s.calls.onCall { heard++ }
        mainSettled()

        assertRefused("forbidden") { s.calls.start("support:34:42", "34", CallType.AUDIO) }
        mainSettled()
        assertEquals(0, heard)
    }

    @Test
    fun aHandlerTakenOffHearsNoMore() = runBlocking {
        val s = StandIn()
        var heard = 0
        val listener = s.calls.onGroup { heard++ }
        mainSettled()

        s.calls.group("meeting:7", CallType.AUDIO).leave()
        s.calls.off(listener)
        mainSettled()
        s.calls.group("meeting:8", CallType.AUDIO).leave()
        mainSettled()
        assertEquals(1, heard)
    }

    @Test
    fun theRoomACallMovesToIsStillTheCall() = runBlocking {
        val s = StandIn()
        var heard = 0
        s.calls.onGroup { heard++ }
        mainSettled()

        val room = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            s.calls.handoffRoom("support:34:42", CallRoomTicket("t", "wss://nowhere.invalid"), null, false, false)
        }
        mainSettled()
        assertEquals(0, heard)
        room.leave()
    }
}
