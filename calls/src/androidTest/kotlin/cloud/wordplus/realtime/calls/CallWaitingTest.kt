package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.MeshWait
import io.livekit.android.room.MeshWaitReason
import kotlinx.serialization.json.JsonNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A join waiting for a place on the mesh, as the browser's group calls and streams tell it (realtime-client
 * group-call.ts:284-288, stream.ts:187, 408), and what carries a call as it changes (calls.ts:800). The state stays
 * `CONNECTING`, since a new value would break a `when` over it in every app: `waiting` says the rest. The Swift package's
 * CallWaitingTests' twin. With no server.
 */
@RunWith(AndroidJUnit4::class)
class CallWaitingTest {
    private val wait = CallWait(1_760_000_000_000L, "busy")

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun group(s: StandIn = StandIn()) =
        GroupCall(ApplicationProvider.getApplicationContext(), s.calls, "meeting:7", null, null, microphone = false, camera = false)

    private fun call(s: StandIn = StandIn()) =
        Call(s.calls, "call-1", "support:34:42", CallType.AUDIO, Call.Role.CALLER, "34", JsonNull, JSONObject(), JSONObject().put("token", "t"))

    @Test
    fun aGroupCallWaitsAndSaysSo() = onMain {
        val call = group()
        val heard = ArrayList<CallWait>()
        call.onWaiting { heard.add(it) }
        assertNull(call.waiting)

        call.heardWait(wait)
        assertEquals(wait, call.waiting)
        assertEquals(listOf(wait), heard)
        assertEquals(GroupCallState.CONNECTING, call.state)
        call.leave()
    }

    @Test
    fun aGroupCallConnectedWaitsNoMore() = onMain {
        val call = group()
        var heard = 0
        call.onWaiting { heard++ }
        call.heardWait(wait)

        call.handle(RoomEvent.Reconnected(call.liveKitRoom))
        assertEquals(GroupCallState.CONNECTED, call.state)
        assertNull(call.waiting)

        call.heardWait(wait)
        assertNull("a wait heard late is over", call.waiting)
        assertEquals(1, heard)
        call.leave()
    }

    @Test
    fun aGroupCallLeftWaitsNoMore() = onMain {
        val call = group()
        call.heardWait(wait)
        call.leave()
        assertNull(call.waiting)
    }

    @Test
    fun aWaitingHandlerComesOff() = onMain {
        val call = group()
        var heard = 0
        val listener = call.onWaiting { heard++ }
        call.off(listener)
        call.heardWait(wait)
        assertEquals(0, heard)
        call.leave()
    }

    @Test
    fun theConnectTellsOfTheWait() {
        val caps = CallCodecCaps(emptyMap(), false)
        val told = ArrayList<MeshWait>()
        val options = GroupShapes.connectOptions(io.livekit.android.room.MeshSettings.NONE, emptyList(), caps) { told.add(it) }
        options.onMeshWait?.invoke(MeshWait(1, MeshWaitReason.FULL))
        assertEquals(listOf(MeshWaitReason.FULL), told.map { it.reason })
        assertNull(GroupShapes.connectOptions(io.livekit.android.room.MeshSettings.NONE, emptyList(), caps).onMeshWait)
        assertEquals(CallWait(1, "unreachable"), CallWait(MeshWait(1, MeshWaitReason.UNREACHABLE)))
    }

    @Test
    fun aPathChangedIsHeardOnce() = onMain {
        val call = call()
        val heard = ArrayList<CallPath>()
        call.onPath { heard.add(it) }

        call.changePath(CallPath.DIRECT)
        call.changePath(CallPath.DIRECT)
        call.changePath(CallPath.RELAY)
        assertEquals(CallPath.RELAY, call.path)
        assertEquals(listOf(CallPath.DIRECT, CallPath.RELAY), heard)
        call.end()
    }

    @Test
    fun aCallMovedToARoomSaysSoAndTellsOfItsWait() = onMain {
        val call = call()
        val paths = ArrayList<CallPath>()
        val waits = ArrayList<CallWait>()
        call.onPath { paths.add(it) }
        call.onWaiting { waits.add(it) }

        call.toRoom(CallRoomTicket("t", "wss://nowhere.invalid"))
        assertEquals(listOf(CallPath.ROOM), paths)
        val room = call.carryingRoom
        assertNotNull(room)
        room!!.heardWait(wait)
        assertEquals(listOf(wait), waits)
        assertEquals(CallState.CONNECTING, call.state)
        call.end()
    }

    /** As the browser's player infers it (realtime-client streams/client.ts:1565, 1612): the room doesn't say why it refuses. */
    @Test
    fun aFullStreamWaitsForAPlaceAndSaysSo() = onMain {
        val s = StandIn()
        val stream = LiveStream(s.calls, "show:1")
        val heard = ArrayList<CallWait>()
        stream.onWaiting { heard.add(it) }
        stream.apply("viewers", JSONObject().put("n", 50))

        // No limit, or room for more: a join that takes a while is only that, and a failed one is out of it.
        stream.media = LiveStreamMedia.CONNECTING
        stream.waitIfFull()
        assertNull(stream.waiting)
        stream.audience = 51
        stream.waitIfFull()
        assertNull(stream.waiting)
        stream.joinFailed(asViewer = true)
        assertNull(stream.waiting)
        assertEquals(LiveStreamMedia.RECONNECTING, stream.media)

        // As many watch as it takes.
        stream.audience = 50
        stream.media = LiveStreamMedia.CONNECTING
        val before = System.currentTimeMillis()
        stream.waitIfFull()
        assertEquals("full", stream.waiting?.reason)
        assertTrue("until the next try", stream.waiting!!.until >= before + LiveStream.FULL_RETRY_MS)
        assertEquals(1, heard.size)

        // Refused while full: still joining, as far as the app goes, and told again.
        stream.joinFailed(asViewer = true)
        assertEquals(LiveStreamMedia.CONNECTING, stream.media)
        assertEquals("full", stream.waiting?.reason)
        assertEquals(2, heard.size)

        // A host or a guest takes no place among those watching.
        stream.media = LiveStreamMedia.CONNECTING
        stream.joinFailed(asViewer = false)
        assertEquals(LiveStreamMedia.RECONNECTING, stream.media)
        assertNull(stream.waiting)
    }

    @Test
    fun aStreamWaitsAndSaysSo() = onMain {
        val s = StandIn()
        val stream = LiveStream(s.calls, "show:1")
        val heard = ArrayList<CallWait>()
        var changes = 0
        stream.onWaiting { heard.add(it) }
        stream.onChange { changes++ }

        stream.heardWait(wait)
        assertNull("nothing waits with no join under way", stream.waiting)

        stream.media = LiveStreamMedia.CONNECTING
        stream.heardWait(wait)
        assertEquals(wait, stream.waiting)
        assertEquals(listOf(wait), heard)
        assertEquals(1, changes)
        assertEquals(LiveStreamMedia.CONNECTING, stream.media)

        stream.media = LiveStreamMedia.LIVE
        assertNull(stream.waiting)
        stream.heardWait(wait)
        assertEquals(listOf(wait), heard)

        stream.media = LiveStreamMedia.CONNECTING
        stream.heardWait(wait)
        stream.media = LiveStreamMedia.OFF
        assertNull(stream.waiting)
    }
}
