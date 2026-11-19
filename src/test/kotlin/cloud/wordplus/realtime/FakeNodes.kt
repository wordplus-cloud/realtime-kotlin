package cloud.wordplus.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Realtime nodes for the tests, in place of the realtime server: OkHttp's client, whose WebSockets reach a node played
 * here by the host they ask for (`https://node-a.test`), speaking Engine.IO 4's open and Socket.IO 5's connect, events and
 * acks in whatever namespace a client opens. It keeps each client's namespace, handshake and requests, answers every
 * request with [answer], and the test sends a client events (`migrate` and `nodes` among them) or drops it. Its HTTP
 * requests, the app's routes, are kept by their path and fail.
 */
internal class FakeNodes : OkHttpClient() {
    /** One client's socket: the node it reached, the namespace and handshake it opened, and what it asked. */
    inner class Client(val host: String, private val listener: WebSocketListener, request: Request) {
        @Volatile var namespace: String? = null
            private set
        @Volatile var auth: JsonElement? = null
            private set
        @Volatile var closed = false
            private set
        val asked: MutableList<Pair<String, JsonElement>> = Collections.synchronizedList(ArrayList())

        fun asked(event: String): List<JsonElement> = synchronized(asked) { asked.filter { it.first == event }.map { it.second } }

        internal val socket = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(text: String): Boolean {
                server.execute { heard(text) }
                return true
            }
            override fun send(bytes: ByteString) = true
            override fun close(code: Int, reason: String?): Boolean {
                closed = true
                return true
            }
            override fun cancel() {
                closed = true
            }
        }

        /** An event from the server, in the namespace this client opened. */
        fun emit(event: String, data: JsonElement) = send("42$prefix" + JsonArray(listOf(JsonPrimitive(event), data)))

        /** The connection gone, as a node that dies or a network that drops it. */
        fun drop() = server.execute {
            closed = true
            listener.onFailure(socket, IOException("dropped"), null)
        }

        private val prefix get() = namespace.let { if (it == null || it == "/") "" else "$it," }

        private fun send(text: String) = server.execute { listener.onMessage(socket, text) }

        internal fun opened() = send("""0{"sid":"e1","upgrades":[],"pingInterval":25000,"pingTimeout":60000,"maxPayload":1000000}""")

        /** A packet from the client: `40` its connect with the handshake, `42` an event, `41` its disconnect. */
        private fun heard(text: String) {
            if (!text.startsWith("4") || text.length < 2) return
            val type = text[1]
            var rest = text.substring(2)
            var name = "/"
            if (rest.startsWith("/")) {
                val comma = rest.indexOf(',').let { if (it < 0) rest.length else it }
                name = rest.substring(0, comma)
                rest = if (comma >= rest.length) "" else rest.substring(comma + 1)
            }
            when (type) {
                '0' -> {
                    namespace = name
                    auth = if (rest.isEmpty()) null else Json.parseToJsonElement(rest)
                    send("40$prefix" + """{"sid":"n1"}""")
                    connected(this)
                }
                '2' -> {
                    val digits = rest.takeWhile { it.isDigit() }
                    val items = Json.parseToJsonElement(rest.substring(digits.length)) as? JsonArray ?: return
                    val event = items.firstOrNull().text ?: return
                    val data = items.getOrNull(1) ?: JsonNull
                    asked.add(event to data)
                    val id = digits.toIntOrNull() ?: return
                    val reply = answer(event, data) ?: return
                    send("43$prefix$id" + JsonArray(listOf(reply)))
                }
                '1' -> closed = true
            }
        }
    }

    /** What each request is answered with, null for no answer. `{ok: true}` unless the test says otherwise. */
    @Volatile var answer: (String, JsonElement) -> JsonElement? = { _, _ -> buildJsonObject { put("ok", true) } }

    private val server = Executors.newSingleThreadExecutor { Thread(it, "fake-nodes").apply { isDaemon = true } }
    private val opened: MutableList<Client> = Collections.synchronizedList(ArrayList())

    /** The paths of the app's routes asked, in order. */
    val routesAsked: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The clients that opened a namespace on `host`'s node, in the order they did. */
    fun clients(host: String): List<Client> = synchronized(opened) { opened.filter { it.host == host } }

    /** The `n`th client to open a namespace on `host`'s node (from 1), once it has; null after the timeout. */
    fun client(host: String, n: Int, timeoutMs: Long = 5_000): Client? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (clients(host).size < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return clients(host).getOrNull(n - 1)
    }

    private fun connected(client: Client) {
        opened.add(client)
    }

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val client = Client(request.url.host, listener, request)
        client.opened()
        return client.socket
    }

    override fun newCall(request: Request): Call {
        routesAsked.add(request.url.encodedPath)
        return super.newCall(request.newBuilder().url("http://127.0.0.1:9/").build())
    }

    fun stop() = server.shutdownNow()
}

/** Until `done` holds, or five seconds (or `timeoutMs`) have gone. */
internal fun eventually(timeoutMs: Long = 5_000, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
}
