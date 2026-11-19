package cloud.wordplus.realtime.calls

import cloud.wordplus.realtime.MemoryNodeStore
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Better Messages' `p2pCall` as a transport, on a connection opened on its namespace `/` with its handshake, to a node the
 * test plays (OkHttp's WebSocket stood in for, as the core's SocketIOTest does): a request goes as it is, with no token or
 * tab of the SDK's own, and its answer comes back; each session hears only its own events, a call id that is a number
 * being the same call; and the connection back after a drop is told. On the JVM, with no call: the engine needs a device.
 */
class P2PCallTransportTest {
    /** A node of the realtime server's main namespace: what each client socket opened, asked and was told. */
    private class Node : OkHttpClient() {
        inner class Client(private val listener: WebSocketListener, request: Request) {
            @Volatile var auth: JsonElement? = null
            @Volatile var namespace: String? = null
            val asked: MutableList<Pair<String, JsonElement>> = Collections.synchronizedList(ArrayList())
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(text: String): Boolean = true.also { server.execute { heard(text) } }
                override fun send(bytes: ByteString) = true
                override fun close(code: Int, reason: String?) = true
                override fun cancel() {}
            }

            fun emit(event: String, data: JsonElement) = server.execute { listener.onMessage(socket, "42" + JsonArray(listOf(JsonPrimitive(event), data))) }

            fun drop() = server.execute { listener.onFailure(socket, IOException("dropped"), null) }

            fun open() = server.execute { listener.onMessage(socket, """0{"sid":"e1","upgrades":[],"pingInterval":25000,"pingTimeout":60000,"maxPayload":1000000}""") }

            private fun heard(text: String) {
                when {
                    // The main namespace's connect names none: `40{auth}`.
                    text.startsWith("40") -> {
                        namespace = if (text.startsWith("40/")) text.substring(2).substringBefore(',') else "/"
                        auth = Json.parseToJsonElement(text.substring(2))
                        listener.onMessage(socket, """40{"sid":"n1"}""")
                        connected.add(this)
                    }
                    text.startsWith("42") -> {
                        val rest = text.substring(2)
                        val digits = rest.takeWhile { it.isDigit() }
                        val items = Json.parseToJsonElement(rest.substring(digits.length)) as JsonArray
                        asked.add((items[0] as JsonPrimitive).content to items[1])
                        val id = digits.toIntOrNull() ?: return
                        listener.onMessage(socket, "43$id" + JsonArray(listOf(answer(items[1]))))
                    }
                }
            }
        }

        val server = Executors.newSingleThreadExecutor { Thread(it, "fake-node").apply { isDaemon = true } }
        val connected: MutableList<Client> = Collections.synchronizedList(ArrayList())
        @Volatile var answer: (JsonElement) -> JsonElement = { json("""{"ok":true}""") }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = Client(listener, request).also { it.open() }.socket

        fun client(n: Int, timeoutMs: Long = 5_000): Client? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (connected.size < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
            return connected.getOrNull(n - 1)
        }
    }

    private val node = Node()
    private val rt = Realtime(
        RealtimeOptions(
            app = "site-9",
            endpoint = "https://site.example/wp-json/better-messages/v1",
            server = "https://cloud.better-messages.test",
            nodeStore = MemoryNodeStore(),
            httpClient = node,
            namespace = "/",
            auth = json("""{"sid":"site-9","uid":7,"sk":"user-key"}"""),
        ),
    )
    private val transport = P2PCallTransport(rt)

    @After
    fun closeIt() {
        rt.close()
        node.server.shutdownNow()
    }

    private fun connected(): Node.Client {
        rt.connect()
        val client = node.client(1) ?: error("it never connected")
        val deadline = System.currentTimeMillis() + 5_000
        while (!transport.connected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return client
    }

    @Test
    fun aRequestGoesAsP2PCallAsItIsAndItsAnswerComesBack() {
        node.answer = { payload -> json("""{"ok":true,"role":"polite","op":${payload.jsonObjectOp()}}""") }
        val client = connected()
        assertEquals("/", client.namespace)
        assertEquals(json("""{"sid":"site-9","uid":7,"sk":"user-key","rt":1}"""), client.auth)
        assertTrue(transport.connected)

        val join = json("""{"op":"join","call_id":"4711","session":"s1","epoch":0,"last_id":null}""")
        val answered = CountDownLatch(1)
        val reply = AtomicReference<JsonElement?>()
        transport.send(join) {
            reply.set(it)
            answered.countDown()
        }
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        assertEquals(json("""{"ok":true,"role":"polite","op":"join"}"""), reply.get())
        assertEquals(listOf("p2pCall" to join), client.asked.toList())

        transport.send(json("""{"op":"leave","call_id":"4711","session":"s1","reason":"hangup"}"""), null)
        val deadline = System.currentTimeMillis() + 5_000
        while (client.asked.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("p2pCall", "p2pCall"), client.asked.map { it.first })
    }

    @Test
    fun eachSessionHearsItsOwnEventsAndACallIdOfDigitsIsTheSameCall() {
        val client = connected()
        val mine = Collections.synchronizedList(ArrayList<JsonElement>())
        val theirs = Collections.synchronizedList(ArrayList<JsonElement>())
        val stop = transport.listen("4711", "s1") { mine.add(it) }
        transport.listen("4711", "s2") { theirs.add(it) }

        val ready = json("""{"op":"ready","call_id":4711,"session":"s1","generation":1}""")
        client.emit("p2pCall", ready)
        client.emit("p2pCall", json("""{"op":"ended","call_id":"4711","session":"s2","reason":"hangup"}"""))
        client.emit("p2pCall", json("""{"op":"ready","call_id":"4712","session":"s1"}"""))
        val deadline = System.currentTimeMillis() + 5_000
        while ((mine.isEmpty() || theirs.isEmpty()) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(100)
        assertEquals(listOf(ready), mine.toList())
        assertEquals(1, theirs.size)

        stop()
        client.emit("p2pCall", ready)
        Thread.sleep(200)
        assertEquals("nothing after it stopped listening", 1, mine.size)
    }

    @Test
    fun theConnectionBackAfterADropIsTold() {
        val client = connected()
        val told = CountDownLatch(1)
        transport.onReconnect { told.countDown() }
        client.drop()
        assertNotNull("it reconnects, on the same namespace", node.client(2, 10_000))
        assertTrue(told.await(10, TimeUnit.SECONDS))
        assertEquals("/", node.client(2)?.namespace)
    }

    @Test
    fun theEngineAndATransportSpeakTheSameJson() {
        val engine = JSONObject().put("op", "join").put("epoch", 0).put("last_id", JSONObject.NULL).put("resume", true).put("body", JSONObject().put("sealed", 1))
        val wire = engine.toJsonElement()
        assertEquals(json("""{"op":"join","epoch":0,"last_id":null,"resume":true,"body":{"sealed":1}}"""), wire)
        val back = wire.toJSONObject() ?: error("an object reads back")
        assertEquals(0, back.getInt("epoch"))
        assertTrue(back.isNull("last_id"))
        assertEquals(1, back.getJSONObject("body").getInt("sealed"))
        assertNull("an answer that isn't an object is none", JsonPrimitive("ok").toJSONObject())
    }

    /**
     * Better Messages' relay hands a room over by its token alone (its pages know the media server from the site), where
     * the SDK's own names the mesh in it: a call on a transport of the app's own goes to the server the app gave.
     */
    @Test
    fun aRoomHandedOverByItsTokenAloneIsOnTheServerTheAppGave() {
        val bare = CallRoomTicket.from(JSONObject().put("token", "room-token").put("room", "bm_9_4711")) ?: error("a ticket")
        assertNull(bare.server)
        assertEquals(CallRoomTicket("room-token", "wss://mesh.example"), bare.at("wss://mesh.example"))
        assertNull("nothing given, nothing named", bare.at(null).server)
        val named = CallRoomTicket.from(JSONObject().put("token", "room-token").put("server", "wss://named.example")) ?: error("a ticket")
        assertEquals("the relay's own address wins", "wss://named.example", named.at("wss://mesh.example").server)
    }

    private fun JsonElement.jsonObjectOp(): String = (this as kotlinx.serialization.json.JsonObject)["op"].toString()
}

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
