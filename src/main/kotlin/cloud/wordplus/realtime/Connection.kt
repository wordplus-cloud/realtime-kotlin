package cloud.wordplus.realtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The app's one connection to the realtime servers, as the browser's connector keeps
 * it: straight to a node when it knows one, else the realtime server (the load balancer); to another node at a
 * `migrate`, make before break, its rooms joined there before the switch; and again after a drop, from the first node
 * that hasn't expired. Everything runs on `queue`. On one namespace of the realtime server, `/channels` unless the app
 * names another: the server sends `migrate` and `nodes` in every namespace, so all of this is the same on each.
 */
internal class Connection(
    val server: String,
    private val path: String,
    private val auth: JsonElement,
    private val queue: ScheduledExecutorService,
    private val client: OkHttpClient,
    private val store: NodeStore,
    /** The namespace every socket of this connection opens, the first, after a drop and at a move alike. */
    val namespace: String = RealtimeOptions.CHANNELS,
) {
    enum class State { Connecting, Connected, Disconnected, Closed }

    var onEvent: (String, JsonElement) -> Unit = { _, _ -> }
    var onState: (State, String?) -> Unit = { _, _ -> }
    var onRefused: (String) -> Unit = {}
    /** What to hold again on a socket before it takes over (identity, rooms, watches); called on `queue`, answers on it. */
    var hold: (SocketIO, Boolean, (Boolean) -> Unit) -> Unit = { _, _, done -> done(true) }

    var current: SocketIO? = null
        private set
    var state = State.Closed
        private set
    private var attempt = 0
    private var moving = false
    private var scheduled: Pair<String, ScheduledFuture<*>>? = null
    private var closed = false

    fun start() = queue.execute {
        closed = false
        connect(candidates())
    }

    fun close() = queue.execute {
        closed = true
        scheduled?.second?.cancel(false)
        current?.close()
        current = null
        setState(State.Closed, null)
    }

    /** Where to try, in order: the cached nodes that haven't expired, unless direct connections are paused, then the realtime server. */
    private fun candidates(): List<String> {
        val cache = store.load(server)
        return (if (cache.paused) emptyList() else cache.nodes.map { it.url }) + server
    }

    private fun connect(targets: List<String>) {
        val target = targets.firstOrNull() ?: return
        if (closed) return
        setState(State.Connecting, null)
        val socket = SocketIO(target, path, namespace, auth, queue, client) { socket, event -> socketEvent(socket, event, targets.drop(1)) }
        current = socket
        socket.open(if (target == server) 7_000 else 5_000)
    }

    private fun socketEvent(socket: SocketIO, event: SocketIO.Event, rest: List<String>) {
        when (event) {
            SocketIO.Event.Connected -> {
                if (socket !== current) return
                attempt = 0
                hold(socket, false) { if (socket === current) setState(State.Connected, null) }
            }
            is SocketIO.Event.Refused -> {
                if (socket !== current) return
                if (event.reason.startsWith("connect_failed:")) {
                    // A node that doesn't answer: the next one, then the realtime server, then a while off line.
                    if (socket.base != server) store.forget(server, socket.base)
                    if (rest.isNotEmpty()) return connect(rest)
                } else {
                    // The handshake refused (not_licensed, over_capacity …): the app's to answer.
                    onRefused(event.reason)
                }
                retry()
            }
            is SocketIO.Event.Closed -> {
                if (socket !== current) return
                setState(State.Disconnected, event.reason)
                retry()
            }
            is SocketIO.Event.Message -> {
                if (event.name == "migrate") return migrate(event.data, socket)
                if (event.name == "nodes") return remember(event.data["nodes"])
                if (socket === current) onEvent(event.name, event.data)
            }
        }
    }

    private fun retry() {
        if (closed) return
        attempt++
        val delay = listOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L)[minOf(attempt - 1, 4)] * Random.nextDouble(0.7, 1.3)
        queue.schedule({ if (!closed && state != State.Connected) connect(candidates()) }, delay.toLong(), TimeUnit.MILLISECONDS)
    }

    /** A `migrate`: to `target` (the realtime server for none) at a random moment within `spread`. */
    private fun migrate(m: JsonElement, socket: SocketIO) {
        if (socket !== current || closed) return
        val reason = m["reason"].text ?: "drain"
        remember(m["nodes"])
        val target = m["target"].text
        if (reason == "direct") {
            // Only from the realtime server itself, to an https node other than it, and never while paused or moving.
            if (socket.base != server || target == null || !target.startsWith("https://") || target == server || store.load(server).paused || moving || scheduled != null) return
        } else if (reason == "direct_off") {
            store.save(server, NodeCache())
        }

        scheduled?.let { if (it.first == "direct" || reason != "direct") it.second.cancel(false) }
        val spreadMs = maxOf(0.0, (m["spread"] as? JsonPrimitive)?.doubleOrNull ?: 5_000.0).toLong()
        val future = queue.schedule({
            scheduled = null
            move(target ?: server, reason)
        }, if (spreadMs > 0) Random.nextLong(spreadMs + 1) else 0, TimeUnit.MILLISECONDS)
        scheduled = reason to future
    }

    /**
     * Make before break: a second socket to the target, everything held there, then the switch; the old socket goes
     * three seconds later, once its answers under way are in. A move that fails leaves the connection where it was.
     */
    private fun move(target: String, reason: String) {
        val old = current ?: return
        if (closed || moving) return
        moving = true
        var switched = false
        val next = SocketIO(target, path, namespace, auth, queue, client) { socket, event ->
            when (event) {
                SocketIO.Event.Connected -> hold(socket, true) { ok ->
                    moving = false
                    if (!ok || closed || current !== old) return@hold socket.close()
                    switched = true
                    current = socket
                    setState(State.Connected, null)
                    onEvent("moved", JsonNull)
                    queue.schedule({ old.close() }, 3, TimeUnit.SECONDS)
                }
                is SocketIO.Event.Refused, is SocketIO.Event.Closed -> {
                    if (switched) return@SocketIO socketEvent(socket, event, emptyList())
                    moving = false
                    // An offered node this client can't reach while the realtime server answers: no more offers for an hour.
                    if (reason == "direct") store.pause(server)
                }
                is SocketIO.Event.Message -> if (switched) socketEvent(socket, event, emptyList())
            }
        }
        next.open(if (target == server) 7_000 else 10_000)
    }

    private fun remember(nodes: JsonElement?) {
        val list = nodes as? JsonArray ?: return
        if (list.isEmpty()) return
        val now = System.currentTimeMillis() / 1000.0
        val kept = list.take(3).mapNotNull { node ->
            val url = node["url"].text?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val ttl = ((node["ttl"] as? JsonPrimitive)?.doubleOrNull ?: 60.0).coerceIn(60.0, 86_400.0)
            CachedNode(url, now + ttl)
        }
        store.save(server, store.load(server).copy(nodes = kept, pausedUntil = null))
    }

    private fun setState(next: State, reason: String?) {
        if (next == state) return
        state = next
        onState(next, reason)
    }
}

@Serializable
data class CachedNode(val url: String, val until: Double)

@Serializable
data class NodeCache(val nodes: List<CachedNode> = emptyList(), val pausedUntil: Double? = null) {
    val paused: Boolean get() = (pausedUntil ?: 0.0) > System.currentTimeMillis() / 1000.0
}

/** Where the nodes are kept between launches: up to three, each until its ttl runs out. */
interface NodeStore {
    fun load(server: String): NodeCache
    fun save(server: String, cache: NodeCache)
}

internal fun NodeStore.forget(server: String, url: String) = save(server, load(server).let { it.copy(nodes = it.nodes.filter { n -> n.url != url }) })

internal fun NodeStore.pause(server: String) = save(server, load(server).copy(pausedUntil = System.currentTimeMillis() / 1000.0 + 3_600))

/**
 * Where the nodes are kept when the app doesn't say: on Android in a file of the app's cache folder, which an app's process
 * has as `java.io.tmpdir`, so a launch starts on the nodes the last one knew, as Swift's do in UserDefaults. This library
 * is plain Kotlin, with no Context to ask. Anywhere else, or in a folder it can't write to, in memory only.
 */
internal fun defaultNodeStore(runtime: String? = System.getProperty("java.runtime.name"), folder: String? = System.getProperty("java.io.tmpdir")): NodeStore {
    if (runtime != "Android Runtime" || folder == null) return MemoryNodeStore()
    val dir = File(folder, "wordplus-realtime-nodes")
    val held = HashMap<String, String>()
    fun file(key: String) = File(dir, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
    return TextNodeStore(
        { key -> held[key] ?: runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull() },
        { key, value ->
            held[key] = value
            runCatching {
                dir.mkdirs()
                val next = File(dir, file(key).name + ".tmp")
                next.writeText(value)
                next.renameTo(file(key))
            }
        },
    )
}

/** For tests and apps that keep nothing between launches. */
class MemoryNodeStore : NodeStore {
    private val caches = HashMap<String, NodeCache>()

    @Synchronized
    override fun load(server: String): NodeCache = caches[server]?.let { c -> c.copy(nodes = c.nodes.filter { it.until > System.currentTimeMillis() / 1000.0 }) } ?: NodeCache()

    @Synchronized
    override fun save(server: String, cache: NodeCache) {
        caches[server] = cache
    }
}

/**
 * The nodes kept as text, wherever the app keeps text: Android's SharedPreferences, say,
 * `TextNodeStore( { prefs.getString( it, null ) }, { key, value -> prefs.edit().putString( key, value ).apply() } )`.
 */
class TextNodeStore(private val read: (String) -> String?, private val write: (String, String) -> Unit) : NodeStore {
    override fun load(server: String): NodeCache {
        val text = read(key(server)) ?: return NodeCache()
        val cache = try {
            Json.decodeFromString<NodeCache>(text)
        } catch (_: Exception) {
            NodeCache()
        }
        return cache.copy(nodes = cache.nodes.filter { it.until > System.currentTimeMillis() / 1000.0 })
    }

    override fun save(server: String, cache: NodeCache) = write(key(server), Json.encodeToString(cache))

    private fun key(server: String) = "wordplus-realtime-nodes|$server"
}
