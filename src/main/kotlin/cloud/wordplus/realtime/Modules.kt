package cloud.wordplus.realtime

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

// What the SDK's other modules (cloud.wordplus:realtime-calls) reach of the connection, behind `@WordPlusModulesApi`:
// not part of the API an app uses. A module speaks on the same `/channels` socket as the rooms, with requests of its own
// (`call`, `line`, `invite` …), hears the events the rooms leave alone, and holds what it needs again on every socket
// before that socket takes over: after a drop, and at a move between nodes, make before break.

@RequiresOptIn(message = "For the WordPlus SDK's own modules, not for apps.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class WordPlusModulesApi

/** A socket the connection holds, or the one a move is about to switch to. */
@WordPlusModulesApi
class ModuleSocket internal constructor(private val socket: RoomSocket) {
    val isConnected: Boolean get() = socket.isConnected

    /** An event to the socket; with `ack`, its answer on the module queue (null when the socket ended first, or after the timeout). */
    fun emit(event: String, payload: JsonElement, timeoutMs: Long = 8_000, ack: ((JsonElement?) -> Unit)? = null) =
        socket.emit(event, payload, timeoutMs, ack)
}

/** The queue the connection runs on; everything a module hears comes on it. */
@WordPlusModulesApi
val Realtime.moduleQueue: ScheduledExecutorService get() = queue

/** Where an app wants its handlers called. */
@WordPlusModulesApi
val Realtime.callbackExecutor: Executor get() = options.callbackExecutor

/** The socket the connection holds now, when it is connected. */
@WordPlusModulesApi
val Realtime.currentSocket: ModuleSocket?
    get() = onQueue { socketForEmit() }?.takeIf { it.isConnected }?.let { ModuleSocket(it) }

/**
 * A socket in place of the connection's, for the tests of a module, as the tests of the rooms have one: what it is asked goes to
 * `send`, with the ack to answer on.
 */
@WordPlusModulesApi
fun Realtime.standInSocket(send: (event: String, payload: JsonElement, ack: ((JsonElement?) -> Unit)?) -> Unit) {
    socketAsked = object : RoomSocket {
        override val isConnected = true
        override fun emit(event: String, data: JsonElement, timeoutMs: Long, ack: ((JsonElement?) -> Unit)?) = send(event, data, ack)
    }
}

/** The events of `name` the connection doesn't handle itself, on the module queue. */
@WordPlusModulesApi
fun Realtime.onSocketEvent(name: String, handler: (JsonElement) -> Unit) {
    queue.execute { moduleEvents.getOrPut(name) { ArrayList() }.add(handler) }
}

/** A step of holding a socket before it takes over: at a connect, after a drop, at a move. `done` when it is held. */
@WordPlusModulesApi
fun Realtime.addHold(step: (ModuleSocket, () -> Unit) -> Unit) {
    queue.execute { moduleHolds.add { socket, done -> step(ModuleSocket(socket), done) } }
}

/** A POST to one of the app's routes: its status (0 when it never answered) and JSON answer, on the module queue. */
@WordPlusModulesApi
fun Realtime.postToRoute(route: String, body: JsonElement, done: (Int, JsonElement?) -> Unit) = postRoute(route, body, done)

/** The keys a value needs that the keyring lacks, asked for on the app's keys route; `done` on the module queue. */
@WordPlusModulesApi
fun Realtime.ensureKeys(value: JsonElement, done: () -> Unit) {
    queue.execute { inOrder(listOf(value), done) }
}

/** The realtime server the app connects to (`https://ws.wordplus.cloud`), whose cloud a live stream joins. */
@WordPlusModulesApi
val Realtime.serverUrl: String get() = options.server

/** The connection's HTTP client, for a module's own requests to the cloud. */
@WordPlusModulesApi
val Realtime.moduleHttpClient: okhttp3.OkHttpClient get() = client

/**
 * A namespace on a connection of its own, for a module whose server speaks on one: a live stream's chat (`/estream`,
 * Easy Stream's protocol). It goes to one address and stays there; the module opens it again after a drop. Its events
 * come on its own thread.
 */
@WordPlusModulesApi
class NamespaceSocket(base: String, path: String, namespace: String, auth: JsonElement, client: okhttp3.OkHttpClient, handler: (Event) -> Unit) {
    sealed interface Event {
        data object Connected : Event
        data class Refused(val reason: String) : Event
        data class Closed(val reason: String) : Event
        data class Message(val name: String, val data: JsonElement) : Event
    }

    private companion object {
        // One thread for every such socket, never stopped, so a late callback of a closed one still has somewhere to run.
        val queue: ScheduledExecutorService = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "wordplus-namespace").apply { isDaemon = true } }
    }

    private val socket = SocketIO(base, path, namespace, auth, queue, client) { _, event ->
        handler(
            when (event) {
                is SocketIO.Event.Connected -> Event.Connected
                is SocketIO.Event.Refused -> Event.Refused(event.reason)
                is SocketIO.Event.Closed -> Event.Closed(event.reason)
                is SocketIO.Event.Message -> Event.Message(event.name, event.data)
            },
        )
    }

    fun open(timeoutMs: Long = 10_000) {
        queue.execute { socket.open(timeoutMs) }
    }

    /** An event to the namespace; `ack` with its answer (null when the socket ended first, or after `timeoutMs`). */
    fun emit(event: String, payload: JsonElement, timeoutMs: Long = 8_000, ack: ((JsonElement?) -> Unit)? = null) {
        queue.execute { socket.emit(event, payload, timeoutMs, ack) }
    }

    fun close() {
        socket.close()
    }
}
