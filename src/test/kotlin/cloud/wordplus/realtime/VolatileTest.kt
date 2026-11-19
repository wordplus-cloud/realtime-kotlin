package cloud.wordplus.realtime

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A volatile event with its ack, as the browser's rooms send it (realtime-client rooms.ts, `volatile.emitWithAck`): never
 * kept, so its answer has no id, and unlike one with no ack it waits for the room rather than being dropped.
 */
class VolatileTest {
    private val rt = Realtime(RealtimeOptions(
        app = "s1", endpoint = "http://127.0.0.1:9/api/realtime",
        grants = listOf(PublicGrant("t", System.currentTimeMillis() / 1000.0 + 3600, listOf("chat:{a}:{b}"))),
    ))
    private val socket = FakeSocket(rt).also { rt.socketAsked = it }

    private fun joined(name: String): Room {
        val room = rt.join(name)
        rt.onQueue { room.joined(null, null) }
        return room
    }

    @Test
    fun itIsSentVolatileAndAnsweredWithNoId() = runBlocking {
        val ack = joined("chat:34:42").volatile.emitWithAck("typing", json("""{"on":true}"""))

        assertEquals(Ack("", 0.0), ack)
        val sent = socket.asked("emit").first()
        assertEquals("chat:34:42", sent["room"].text)
        assertEquals("typing", sent["event"].text)
        assertEquals(JsonPrimitive(1), sent["v"])
    }

    @Test
    fun aRefusalComesBackByItsCode() = runBlocking {
        socket.answer = { _, _ -> json("""{"error":"rate_limited"}""") }
        val error = assertFailsWith<RealtimeException> { joined("chat:34:42").volatile.emitWithAck("typing") }
        assertEquals("rate_limited", error.code)
    }

    @Test
    fun itWaitsForTheRoomWhereOneWithNoAckIsDropped() = runBlocking {
        // The room's own join isn't answered, so it stays joining.
        socket.answer = { event, _ -> if (event == "join") null else json("""{"ok":true}""") }
        val room = rt.join("chat:34:42")
        room.volatile.emit("typing")
        val ack = async { room.volatile.emitWithAck("read", json("""{"id":"1-0"}""")) }

        // Neither has gone: the one with no ack never will.
        delay(100)
        assertTrue(socket.asked("emit").isEmpty())

        rt.queue.execute { room.joined(null, null) }
        ack.await()
        assertEquals(listOf("read"), socket.asked("emit").map { it["event"].text })
    }

    @Test
    fun aKeptEventStillHasItsId() = runBlocking {
        socket.answer = { _, _ -> json("""{"ok":true,"id":"1700000000000-0","at":1700000000000}""") }
        val ack = joined("chat:34:42").emitWithAck("message", json("""{"text":"Hi"}"""))
        assertEquals(Ack("1700000000000-0", 1700000000000.0), ack)
        assertNull(socket.asked("emit").first()["v"])
    }
}
