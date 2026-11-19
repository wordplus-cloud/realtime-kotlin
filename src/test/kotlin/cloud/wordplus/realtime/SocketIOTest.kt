package cloud.wordplus.realtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bytes in an event on the wire, as the browser's socket.io-client and the Swift SDK carry them: each `{ "$b": base64 }`
 * part goes as a Socket.IO binary attachment (a `45` packet with a placeholder, then the bytes in a frame of their own),
 * so the realtime server counts and keeps them as bytes, and one that comes that way reads as `{ "$b": base64 }` again.
 * Over OkHttp's WebSocket stood in for by one that keeps what is sent, with no server.
 */
class SocketIOTest {
    /** OkHttp's client, whose WebSocket keeps each frame sent: text as a String, bytes as a ByteString. */
    private class Wire : OkHttpClient() {
        val sent: MutableList<Any> = Collections.synchronizedList(ArrayList())
        lateinit var listener: WebSocketListener
        lateinit var socket: WebSocket

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(text: String) = sent.add(text)
                override fun send(bytes: ByteString) = sent.add(bytes)
                override fun close(code: Int, reason: String?) = true
                override fun cancel() {}
            }
            return socket
        }

        fun hear(text: String) = listener.onMessage(socket, text)
        fun hear(bytes: ByteArray) = listener.onMessage(socket, bytes.toByteString())
    }

    private val queue = Executors.newSingleThreadScheduledExecutor()
    private val wire = Wire()
    private val heard: MutableList<Pair<String, JsonElement>> = Collections.synchronizedList(ArrayList())
    private val connected = CountDownLatch(1)
    private val io = SocketIO("https://nowhere.invalid", "/socket.io", "/channels", JsonObject(emptyMap()), queue, wire) { _, event ->
        when (event) {
            is SocketIO.Event.Connected -> connected.countDown()
            is SocketIO.Event.Message -> heard.add(event.name to event.data)
            else -> {}
        }
    }

    /** Opened and connected to the namespace, as the realtime server answers, with nothing sent since. */
    private fun connect() {
        io.open(10_000)
        wire.hear("""0{"sid":"e1","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}""")
        wire.hear("""40/channels,{"sid":"s1"}""")
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        settle()
        wire.sent.clear()
    }

    /** Whatever was queued has run. */
    private fun settle() = queue.submit {}.get(5, TimeUnit.SECONDS)

    @AfterTest
    fun closeIt() {
        io.close()
        settle()
        queue.shutdownNow()
    }

    @Test
    fun bytesInAnEventGoAsAnAttachment() {
        connect()
        io.emit("emit", json("""{"room":"chat:34:42","event":"message","data":{"text":"Hi","photo":{"${'$'}b":"AQL6"},"pages":[{"${'$'}b":"/w=="}]}}"""))
        settle()

        assertEquals(3, wire.sent.size)
        assertEquals(
            """452-/channels,["emit",{"room":"chat:34:42","event":"message","data":{"text":"Hi","photo":{"_placeholder":true,"num":0},"pages":[{"_placeholder":true,"num":1}]}}]""",
            wire.sent[0],
        )
        assertContentEquals(byteArrayOf(1, 2, 250.toByte()), (wire.sent[1] as ByteString).toByteArray())
        assertContentEquals(byteArrayOf(255.toByte()), (wire.sent[2] as ByteString).toByteArray())
    }

    @Test
    fun anAckIsAskedForBytesToo() {
        connect()
        io.emit("emit", json("""{"photo":{"${'$'}b":"AQL6"}}""")) {}
        settle()

        assertEquals("""451-/channels,1["emit",{"photo":{"_placeholder":true,"num":0}}]""", wire.sent[0])
        assertEquals(2, wire.sent.size)
    }

    @Test
    fun anEventWithNoBytesIsTextAlone() {
        connect()
        // A `$b` that isn't base64, or isn't alone in its object, is no bytes, as `bytes()` reads it.
        io.emit("emit", json("""{"text":"Hi","odd":{"${'$'}b":"not base64!"},"two":{"${'$'}b":"AQL6","name":"x"}}"""))
        settle()

        assertEquals(listOf<Any>("""42/channels,["emit",{"text":"Hi","odd":{"${'$'}b":"not base64!"},"two":{"${'$'}b":"AQL6","name":"x"}}]"""), wire.sent.toList())
    }

    @Test
    fun bytesThatComeAsAnAttachmentReadAsBytes() {
        connect()
        wire.hear("""451-/channels,["message",{"text":"Hi","photo":{"_placeholder":true,"num":0}}]""")
        wire.hear(byteArrayOf(1, 2, 250.toByte()))
        settle()

        val (name, data) = heard.single()
        assertEquals("message", name)
        assertContentEquals(byteArrayOf(1, 2, 250.toByte()), data["photo"]!!.bytes())
        assertEquals("Hi", data["text"].text)
    }
}
