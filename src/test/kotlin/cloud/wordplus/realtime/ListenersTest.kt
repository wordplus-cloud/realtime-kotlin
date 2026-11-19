package cloud.wordplus.realtime

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `once` and `off( event )`, as the browser's rooms have them (realtime-client rooms.ts, `once`, `off`). With no server:
 * a room's events fired as the connection fires them.
 */
class ListenersTest {
    private val callbacks = Executors.newSingleThreadExecutor()
    private val rt = Realtime(RealtimeOptions(app = "s1", endpoint = "http://127.0.0.1:9/api/realtime", callbackExecutor = callbacks))

    /** Everything fired so far has run. */
    private fun settle() {
        rt.onQueue {}
        callbacks.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun heard() = Collections.synchronizedList(ArrayList<String>())

    @Test
    fun onceHearsTheNextOneOnly() {
        val room = rt.join("chat:34:42")
        val got = heard()
        room.once("message") { data, _ -> got.add(data.toString()) }

        // Fired twice before the first has run, as a burst of events is: still once.
        room.fire("message", JsonPrimitive(1), EventMeta())
        room.fire("message", JsonPrimitive(2), EventMeta())
        settle()
        assertEquals(listOf("1"), got.toList())
    }

    @Test
    fun onceCanBeTakenOffBeforeItHears() {
        val room = rt.join("chat:34:42")
        val got = heard()
        val listener = room.once("message") { _, _ -> got.add("heard") }
        room.off(listener)
        room.fire("message", JsonNull, EventMeta())
        settle()
        assertEquals(emptyList(), got.toList())
    }

    @Test
    fun onceBesideOnLeavesOnInPlace() {
        val room = rt.join("chat:34:42")
        val got = heard()
        room.on("message") { _, _ -> got.add("always") }
        room.once("message") { _, _ -> got.add("once") }
        room.fire("message", JsonNull, EventMeta())
        room.fire("message", JsonNull, EventMeta())
        settle()
        assertEquals(listOf("always", "once", "always"), got.toList())
    }

    @Test
    fun offWithAnEventTakesEveryHandlerOfItOffAndNoOther() {
        val room = rt.join("chat:34:42")
        val got = heard()
        room.on("message") { _, _ -> got.add("message") }
        room.once("message") { _, _ -> got.add("message") }
        room.on("typing") { _, _ -> got.add("typing") }
        room.off("message")
        room.fire("message", JsonNull, EventMeta())
        room.fire("typing", JsonNull, EventMeta())
        settle()
        assertEquals(listOf("typing"), got.toList())
    }

    @Test
    fun theConnectionHasOnceAndOffToo() {
        val got = heard()
        rt.once("notice") { _, _ -> got.add("notice") }
        rt.on("moved") { _, _ -> got.add("moved") }
        rt.off("moved")
        val notice = JsonObject(mapOf("event" to JsonPrimitive("notice"), "data" to JsonPrimitive(1)))
        rt.onQueue {
            rt.event("u", notice)
            rt.event("u", notice)
            rt.event("moved", JsonNull)
        }
        settle()
        assertEquals(listOf("notice"), got.toList())
    }

    @Test
    fun onlineTakesAnEventOffToo() {
        val got = heard()
        rt.online.on("change") { got.add("change") }
        rt.online.off("change")
        rt.online.watch(listOf("34"))
        rt.onQueue { rt.online.changed(JsonObject(mapOf("id" to JsonPrimitive("34"), "online" to JsonPrimitive(true)))) }
        settle()
        assertEquals(emptyList(), got.toList())
    }
}
