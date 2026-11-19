package cloud.wordplus.realtime

import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A room this user is out of forgets who is in it, as the browser's (realtime-client a738a57, its test/rooms.test.ts). */
class RoomFailedTest {
    private fun heldAndOut(code: String): Room {
        val rt = Realtime(RealtimeOptions(app = "s1", endpoint = "http://127.0.0.1:9/api/realtime"))
        val room = rt.join("chat:34:42")
        rt.onQueue { room.joined(listOf("34" to JsonNull, "42" to JsonNull), 2) }
        assertEquals(setOf("34", "42"), room.members.keys)
        assertEquals(2, room.count)
        rt.onQueue { room.failed(code) }
        return room
    }

    @Test
    fun aRoomTheUserIsKickedOutOfForgetsItsPeople() {
        val room = heldAndOut("kicked")
        assertEquals(RoomState.Failed, room.state)
        assertTrue(room.members.isEmpty())
        assertNull(room.count)
    }

    @Test
    fun aRoomThatFailsForgetsItsPeople() {
        val room = heldAndOut("forbidden")
        assertEquals("forbidden", room.error)
        assertTrue(room.members.isEmpty())
        assertNull(room.count)
    }
}
