package cloud.wordplus.realtime

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** What rooms and modules need of a socket: [SocketIO], or a test's stand-in. On the connection's queue, as its acks are. */
internal interface RoomSocket {
    val isConnected: Boolean

    /** An event to the namespace; with `ack`, its answer (null when the connection ended first, or after `timeoutMs`). */
    fun emit(event: String, data: JsonElement, timeoutMs: Long = 8_000, ack: ((JsonElement?) -> Unit)? = null)
}

internal suspend fun RoomSocket.request(event: String, data: JsonElement, timeoutMs: Long = 8_000): JsonElement? =
    suspendCancellableCoroutine { continuation -> emit(event, data, timeoutMs) { continuation.resume(it) } }

/**
 * One Socket.IO namespace on one Engine.IO connection, over OkHttp's WebSocket, as the realtime servers speak it:
 * Engine.IO 4's open, ping and pong, and Socket.IO 5's connect, events, acks and binary attachments (`{ "$b": base64 }`
 * here, sent and received as bytes, as the browser's and the Swift SDK's are). Only the WebSocket transport, as every WordPlus client connects. Moves between nodes are the
 * connection's (Connection.kt): this socket goes to one address and stays there. Everything runs on `queue`.
 */
internal class SocketIO(
    val base: String,
    private val path: String,
    private val namespace: String,
    private val auth: JsonElement,
    private val queue: ScheduledExecutorService,
    private val client: OkHttpClient,
    private val handler: (SocketIO, Event) -> Unit,
) : WebSocketListener(), RoomSocket {
    sealed interface Event {
        data object Connected : Event
        data class Refused(val reason: String) : Event
        data class Closed(val reason: String) : Event
        data class Message(val name: String, val data: JsonElement) : Event
    }

    private var socket: WebSocket? = null
    private var nextAck = 0
    private val acks = HashMap<Int, (JsonElement?) -> Unit>()
    private var pingTimer: ScheduledFuture<*>? = null
    private var pingWindowMs = 45_000L
    private var binary: Binary? = null
    @Volatile override var isConnected = false
        private set
    private var ended = false

    private class Binary(val packet: JsonElement, val type: Int, val id: Int?, var left: Int, val buffers: MutableList<ByteArray> = ArrayList())

    fun open(timeoutMs: Long) {
        val url = base.replaceFirst(Regex("^http"), "ws").trimEnd('/') + (if (path.endsWith("/")) path else "$path/") + "?EIO=4&transport=websocket"
        socket = client.newWebSocket(Request.Builder().url(url).build(), this)
        queue.schedule({ if (!isConnected && !ended) end("timeout") }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun emit(event: String, data: JsonElement, timeoutMs: Long, ack: ((JsonElement?) -> Unit)?) {
        queue.execute {
            if (!isConnected || ended) {
                ack?.invoke(null)
                return@execute
            }
            var id: Int? = null
            if (ack != null) {
                val n = ++nextAck
                id = n
                acks[n] = ack
                queue.schedule({ acks.remove(n)?.invoke(null) }, timeoutMs, TimeUnit.MILLISECONDS)
            }
            // Bytes go as attachments, each a frame of its own after the packet that holds its placeholder.
            val buffers = ArrayList<ByteArray>()
            val packet = deconstruct(JsonArray(listOf(JsonPrimitive(event), data)), buffers)
            val prefix = if (buffers.isEmpty()) "42" else "45${buffers.size}-"
            send(prefix + namespacePart + (id?.toString() ?: "") + packet.toString())
            buffers.forEach { socket?.send(it.toByteString()) }
        }
    }

    fun close() {
        queue.execute {
            if (isConnected) send("41$namespacePart")
            end("io client disconnect", tell = false)
        }
    }

    private val namespacePart get() = if (namespace == "/") "" else "$namespace,"

    private fun send(text: String) {
        socket?.send(text)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        queue.execute { if (webSocket === socket) packet(text) }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        queue.execute { if (webSocket === socket) attachment(bytes.toByteArray()) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        queue.execute { if (webSocket === socket) end("transport close") }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        queue.execute { if (webSocket === socket) end("transport close") }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        queue.execute { if (webSocket === socket) end("transport error") }
    }

    /** An Engine.IO packet: open, close, ping, or a Socket.IO packet inside a message. */
    private fun packet(text: String) {
        when (text.firstOrNull()) {
            '0' -> {
                val open = parse(text.substring(1))
                val interval = (open["pingInterval"] as? JsonPrimitive)?.doubleOrNull ?: 25_000.0
                val timeout = (open["pingTimeout"] as? JsonPrimitive)?.doubleOrNull ?: 20_000.0
                pingWindowMs = (interval + timeout).toLong()
                heard()
                send("40" + namespacePart + auth.toString())
            }
            '1' -> end("transport close")
            '2' -> {
                heard()
                send("3")
            }
            '4' -> socketPacket(text.substring(1))
        }
    }

    /** A Socket.IO packet: `{type}[{attachments}-][{namespace},][{ack id}][json]`. */
    private fun socketPacket(text: String) {
        val type = text.firstOrNull()?.digitToIntOrNull() ?: return
        var rest = text.substring(1)
        var attachments = 0
        if (type == 5 || type == 6) {
            val dash = rest.indexOf('-')
            if (dash >= 0) {
                attachments = rest.substring(0, dash).toIntOrNull() ?: 0
                rest = rest.substring(dash + 1)
            }
        }
        if (rest.startsWith("/")) {
            val comma = rest.indexOf(',').let { if (it < 0) rest.length else it }
            if (rest.substring(0, comma) != namespace) return
            rest = if (comma == rest.length) "" else rest.substring(comma + 1)
        } else if (namespace != "/") {
            return
        }
        val digits = rest.takeWhile { it.isDigit() }
        val id = digits.toIntOrNull()
        rest = rest.substring(digits.length)
        val json = if (rest.isEmpty()) JsonNull else parse(rest)

        when (type) {
            0 -> {
                isConnected = true
                heard()
                handler(this, Event.Connected)
            }
            1 -> end("io server disconnect")
            4 -> end(((json as? JsonObject)?.get("message") as? JsonPrimitive)?.content ?: (json as? JsonPrimitive)?.content ?: "refused", refused = true)
            2, 3 -> deliver(type, id, json as? JsonArray ?: JsonArray(emptyList()))
            5, 6 -> {
                binary = Binary(json, if (type == 5) 2 else 3, id, attachments)
                if (attachments == 0) attachment(null)
            }
        }
    }

    private fun attachment(data: ByteArray?) {
        val pending = binary ?: return
        if (data != null) {
            pending.buffers.add(data)
            pending.left--
        }
        if (pending.left > 0) return
        binary = null
        deliver(pending.type, pending.id, reconstruct(pending.packet, pending.buffers) as? JsonArray ?: JsonArray(emptyList()))
    }

    private fun deliver(type: Int, id: Int?, items: JsonArray) {
        if (type == 3) {
            if (id != null) acks.remove(id)?.invoke(items.firstOrNull() ?: JsonNull)
            return
        }
        val name = (items.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        handler(this, Event.Message(name, items.getOrNull(1) ?: JsonNull))
    }

    /** The connection is alive: its ping is due again within the server's interval and timeout. */
    private fun heard() {
        pingTimer?.cancel(false)
        pingTimer = queue.schedule({ end("ping timeout") }, pingWindowMs, TimeUnit.MILLISECONDS)
    }

    private fun end(reason: String, refused: Boolean = false, tell: Boolean = true) {
        if (ended) return
        ended = true
        val wasConnected = isConnected
        isConnected = false
        pingTimer?.cancel(false)
        socket?.cancel()
        val waiting = acks.values.toList()
        acks.clear()
        waiting.forEach { it(null) }
        if (!tell) return
        handler(
            this,
            when {
                refused -> Event.Refused(reason)
                wasConnected -> Event.Closed(reason)
                else -> Event.Refused("connect_failed:$reason")
            },
        )
    }

    private fun parse(text: String): JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        JsonNull
    }

    /**
     * Binary parts out of a packet, each `{ "$b": base64 }` as [bytes] reads it put in [buffers] and replaced by its
     * placeholder, as socket.io-parser's deconstructPacket and the Swift SDK's `deconstruct`, so the realtime server
     * counts and keeps them as bytes.
     */
    private fun deconstruct(value: JsonElement, buffers: MutableList<ByteArray>, depth: Int = 0): JsonElement {
        value.bytes()?.let { bytes ->
            buffers.add(bytes)
            return JsonObject(mapOf("_placeholder" to JsonPrimitive(true), "num" to JsonPrimitive(buffers.size - 1)))
        }
        if (depth > 17) return value
        return when (value) {
            is JsonObject -> JsonObject(value.mapValues { deconstruct(it.value, buffers, depth + 1) })
            is JsonArray -> JsonArray(value.map { deconstruct(it, buffers, depth + 1) })
            else -> value
        }
    }

    /** Placeholders replaced by their attachments, as `{ "$b": base64 }`, as socket.io-parser's reconstructPacket. */
    private fun reconstruct(value: JsonElement, buffers: List<ByteArray>): JsonElement = when (value) {
        is JsonObject -> {
            val placeholder = (value["_placeholder"] as? JsonPrimitive)?.booleanOrNull == true
            val n = (value["num"] as? JsonPrimitive)?.intOrNull
            if (placeholder && n != null && n in buffers.indices) buffers[n].toJson() else JsonObject(value.mapValues { reconstruct(it.value, buffers) })
        }
        is JsonArray -> JsonArray(value.map { reconstruct(it, buffers) })
        else -> value
    }
}
