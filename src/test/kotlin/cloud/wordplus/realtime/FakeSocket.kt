package cloud.wordplus.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Collections

/**
 * A socket for the tests, in place of a realtime server: what it was asked, in order, and what it answers each with, on
 * the connection's queue as a socket's answers come. Null from [answer] is no answer: the socket ended, or it timed out.
 */
internal class FakeSocket(private val rt: Realtime) : RoomSocket {
    override var isConnected = true
    val asked: MutableList<Pair<String, JsonElement>> = Collections.synchronizedList(ArrayList())
    var answer: (String, JsonElement) -> JsonElement? = { _, _ -> json("""{"ok":true}""") }

    fun asked(event: String): List<JsonElement> = synchronized(asked) { asked.filter { it.first == event }.map { it.second } }

    override fun emit(event: String, data: JsonElement, timeoutMs: Long, ack: ((JsonElement?) -> Unit)?) {
        asked.add(event to data)
        if (ack == null) return
        val reply = answer(event, data)
        rt.queue.execute { ack(reply) }
    }
}

internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)
