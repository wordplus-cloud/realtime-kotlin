package cloud.wordplus.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.TimeUnit

enum class RoomState { Joining, Joined, Failed, Left }

/** How `eventsOf` decodes: what an app's server or another client adds to an event never stops it decoding. */
@PublishedApi
internal val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * A room the app joined: its events, what it may send, and for a presence room who is in it. Its own events are
 * `joined` (the members), `join` ({ id, info }), `leave` ({ id }), `count` ({ n }), `error` ({ code }) and `kicked`.
 */
class Room internal constructor(private val owner: Realtime, val name: String) {
    private var state_ = RoomState.Joining
    private var error_: String? = null
    private var members_ = LinkedHashMap<String, JsonElement>()
    private var count_: Int? = null
    private val readyWaiters = ArrayList<(String?) -> Unit>()
    private val listeners = Listeners()
    /** Why the app's server refused it, from the auth route. */
    internal var denied: String? = null
    /** The last kept event it had, which a reconnect joins again from. */
    internal var lastId: String? = null

    val state: RoomState get() = owner.onQueue { state_ }
    internal val stateOnQueue: RoomState get() = state_
    /** Why it failed: forbidden, expired, bad_token, full, kicked, no_rule, auth_failed … */
    val error: String? get() = owner.onQueue { error_ }
    /** A presence room's members, as many as the server listed and the changes since: id to info. */
    val members: Map<String, JsonElement> get() = owner.onQueue { LinkedHashMap(members_) }
    /** How many are in it, for a counting room (presence, or its rule's `count`); null for one that doesn't. */
    val count: Int? get() = owner.onQueue { count_ }

    /** An event of the room, `handler( data, meta )`, or one of the room's own. */
    fun on(event: String, handler: (JsonElement, EventMeta) -> Unit): Listener = listeners.add(event, handler)

    /** As [on], for the next one only. */
    fun once(event: String, handler: (JsonElement, EventMeta) -> Unit): Listener = listeners.once(event, handler)

    /** Every event of the room, whatever its name. */
    fun onAny(handler: (String, JsonElement, EventMeta) -> Unit): Listener = listeners.onAny(handler)

    fun off(listener: Listener) = listeners.remove(listener)

    /** Takes every handler of an event off. */
    fun off(event: String) = listeners.remove(event)

    /** An event of the room, as a flow. */
    fun events(event: String): Flow<Pair<JsonElement, EventMeta>> = callbackFlow {
        val listener = on(event) { data, meta -> trySend(data to meta) }
        awaitClose { off(listener) }
    }

    /** An event of the room, its data decoded (fields the type doesn't name are left out); one that doesn't decode is skipped. */
    inline fun <reified T> eventsOf(event: String): Flow<Pair<T, EventMeta>> =
        events(event).mapNotNull { (data, meta) -> runCatching { lenientJson.decodeFromJsonElement<T>(data) }.getOrNull()?.let { it to meta } }

    /** Sends an event to the room's others, as its rule allows; nothing is waited for. Before the room is joined, it waits for it. */
    fun emit(event: String, data: JsonElement = JsonNull) = send(event, data, ack = false, volatile = false) {}

    /** Sends an event and waits for the cloud to take it (checked, kept, sent): its id and time, or a RealtimeException. */
    suspend fun emitWithAck(event: String, data: JsonElement = JsonNull, timeoutMs: Long = 8_000): Ack {
        val answer = CompletableDeferred<Result<Ack>>()
        send(event, data, ack = true, volatile = false, timeoutMs = timeoutMs) { answer.complete(it) }
        return answer.await().getOrThrow()
    }

    /** Sends without waiting and without keeping: dropped rather than queued while off line. */
    val volatile: VolatileEmitter get() = VolatileEmitter(this)

    /** A page of the room's history, oldest first: the newest `limit`, or those before or after an event's id. */
    suspend fun history(before: String? = null, after: String? = null, limit: Int = 50): HistoryPage {
        ready()
        val ask = mutableMapOf<String, JsonElement>("room" to JsonPrimitive(name), "limit" to JsonPrimitive(limit))
        before?.let { ask["before"] = JsonPrimitive(it) }
        after?.let { ask["after"] = JsonPrimitive(it) }
        val reply = owner.request("history", ask)
        val events = (reply["events"] as? JsonArray) ?: JsonArray(emptyList())
        val opened = CompletableDeferred<Unit>()
        owner.queue.execute { owner.inOrder(events.map { it["data"] ?: JsonNull }) { opened.complete(Unit) } }
        opened.await()
        return HistoryPage(
            events.map { e ->
                HistoryEvent(e["event"].text ?: "", owner.keyring.openJson(e["data"] ?: JsonNull) ?: JsonNull, EventMeta(e["from"].id, e["id"].text, (e["at"] as? JsonPrimitive)?.doubleOrNull))
            },
            (reply["more"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /** A page of a presence room's members past the first ones listed, from `cursor` ("0" first); the next cursor is "0" after the last. */
    suspend fun membersPage(cursor: String = "0", limit: Int? = null): Pair<Map<String, JsonElement>, String> {
        ready()
        val ask = mutableMapOf<String, JsonElement>("room" to JsonPrimitive(name), "cursor" to JsonPrimitive(cursor))
        limit?.let { ask["limit"] = JsonPrimitive(it) }
        val reply = owner.request("members", ask)
        val out = LinkedHashMap<String, JsonElement>()
        (reply["list"] as? JsonArray)?.forEach { m -> m["id"].id?.let { out[it] = owner.openInfo(m["info"] ?: JsonNull) } }
        return out to (reply["next"].text ?: "0")
    }

    fun leave() = owner.leave(name)

    /** Waits until the room is joined; throws its error when it failed, or `timeout` when it isn't joined in time. */
    suspend fun ready(timeoutMs: Long = 20_000) {
        val answer = CompletableDeferred<String?>()
        owner.queue.execute {
            when (state_) {
                RoomState.Joined -> answer.complete(null)
                RoomState.Failed, RoomState.Left -> answer.complete(error_ ?: "left")
                RoomState.Joining -> waitForJoin(timeoutMs) { answer.complete(it) }
            }
        }
        answer.await()?.let { throw RealtimeException(it) }
    }

    /** On the owner's queue: `done` (with the error, null when joined) once the room is joined, or failed, or after `timeoutMs`. */
    private fun waitForJoin(timeoutMs: Long, done: (String?) -> Unit) {
        var answered = false
        val once = { error: String? ->
            if (!answered) {
                answered = true
                done(error)
            }
        }
        readyWaiters.add(once)
        owner.queue.schedule({ once("timeout") }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    internal fun send(event: String, data: JsonElement, ack: Boolean, volatile: Boolean, timeoutMs: Long = 8_000, done: (Result<Ack>) -> Unit) {
        owner.queue.execute {
            if (state_ == RoomState.Failed || state_ == RoomState.Left) return@execute done(Result.failure(RealtimeException(error_ ?: "left")))
            val label = owner.keyring.newest("ch:$name:")
            val sent: JsonElement = label?.let { owner.keyring.sealJson(data, it) }?.let { JsonPrimitive(it) } ?: data
            val payload = mutableMapOf("tab" to JsonPrimitive(owner.tab), "room" to JsonPrimitive(name), "event" to JsonPrimitive(event), "data" to sent)
            if (volatile) payload["v"] = JsonPrimitive(1)

            val go = go@{
                val socket = owner.socketForEmit()?.takeIf { it.isConnected } ?: return@go done(Result.failure(RealtimeException("offline")))
                if (!ack) {
                    socket.emit("emit", JsonObject(payload))
                    return@go done(Result.success(Ack("", 0.0)))
                }
                socket.emit("emit", JsonObject(payload), timeoutMs) { reply ->
                    // A volatile event is never kept, so it has no id.
                    val id = reply?.get("id").text ?: ""
                    when {
                        reply == null -> done(Result.failure(RealtimeException("timeout")))
                        (reply["ok"] as? JsonPrimitive)?.booleanOrNull != true -> done(Result.failure(RealtimeException(reply["error"].text ?: "server")))
                        else -> {
                            owner.queue.execute { seen(id) }
                            done(Result.success(Ack(id, (reply["at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)))
                        }
                    }
                }
            }
            when {
                state_ == RoomState.Joined -> go()
                volatile && !ack -> done(Result.success(Ack("", 0.0)))
                else -> waitForJoin(if (ack) timeoutMs else 120_000) { error -> if (error == null) go() else done(Result.failure(RealtimeException(error))) }
            }
        }
    }

    // On the owner's queue.

    internal fun joined(members: List<Pair<String, JsonElement>>?, count: Int?) {
        state_ = RoomState.Joined
        error_ = null
        if (members != null) members_ = LinkedHashMap(members.toMap())
        if (count != null) count_ = maxOf(count, members_.size)
        val waiters = readyWaiters.toList()
        readyWaiters.clear()
        waiters.forEach { it(null) }
        fire("joined", JsonArray(members_.map { (id, info) -> JsonObject(mapOf("id" to JsonPrimitive(id), "info" to info)) }), EventMeta())
    }

    internal fun failed(code: String) {
        state_ = RoomState.Failed
        error_ = code
        // Out of it: nobody in it shows here any more.
        members_.clear()
        count_ = null
        val waiters = readyWaiters.toList()
        readyWaiters.clear()
        waiters.forEach { it(code) }
        fire(if (code == "kicked") "kicked" else "error", JsonObject(mapOf("code" to JsonPrimitive(code))), EventMeta())
    }

    internal fun lost() {
        if (state_ == RoomState.Joined) state_ = RoomState.Joining
    }

    internal fun ended() {
        state_ = RoomState.Left
        val waiters = readyWaiters.toList()
        readyWaiters.clear()
        waiters.forEach { it("left") }
    }

    internal fun memberJoined(id: String, info: JsonElement) {
        if (!members_.containsKey(id)) count_ = count_?.plus(1)
        members_[id] = info
        fire("join", JsonObject(mapOf("id" to JsonPrimitive(id), "info" to info)), EventMeta())
    }

    internal fun memberLeft(id: String) {
        if (members_.remove(id) != null) count_ = count_?.let { maxOf(0, it - 1) }
        fire("leave", JsonObject(mapOf("id" to JsonPrimitive(id))), EventMeta())
    }

    internal fun counted(n: Int) {
        count_ = n
        fire("count", JsonObject(mapOf("n" to JsonPrimitive(n))), EventMeta())
    }

    /** Whether a kept event is newer than the last this app had; it becomes the last. */
    internal fun fresh(id: String): Boolean {
        val parsed = parse(id) ?: return true
        val last = lastId?.let { parse(it) }
        if (last != null && compare(parsed, last) <= 0) return false
        lastId = id
        return true
    }

    internal fun seen(id: String) {
        val parsed = parse(id) ?: return
        val last = lastId?.let { parse(it) }
        if (last == null || compare(parsed, last) > 0) lastId = id
    }

    internal fun fire(event: String, data: JsonElement, meta: EventMeta) {
        val own = event in setOf("joined", "join", "leave", "count", "error", "kicked")
        listeners.fire(event, data, meta, owner.options.callbackExecutor, fireAny = !own)
    }

    private fun parse(id: String): Pair<Long, Long>? {
        val parts = id.split('-')
        if (parts.size != 2) return null
        return (parts[0].toLongOrNull() ?: return null) to (parts[1].toLongOrNull() ?: return null)
    }

    private fun compare(a: Pair<Long, Long>, b: Pair<Long, Long>) = if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)
}

/** `room.volatile`: sent without waiting and never kept. */
class VolatileEmitter internal constructor(private val room: Room) {
    fun emit(event: String, data: JsonElement = JsonNull) = room.send(event, data, ack = false, volatile = true) {}

    /**
     * Sends without keeping, once the room is joined, and waits for the cloud to take it (checked and sent): when it did,
     * with no id as it isn't kept, or a RealtimeException.
     */
    suspend fun emitWithAck(event: String, data: JsonElement = JsonNull, timeoutMs: Long = 8_000): Ack {
        val answer = CompletableDeferred<Result<Ack>>()
        room.send(event, data, ack = true, volatile = true, timeoutMs = timeoutMs) { answer.complete(it) }
        return answer.await().getOrThrow()
    }
}

/** What the cloud answers an acked emit: the event's id, rising within the room, and when it took it. */
data class Ack(val id: String, val at: Double)

data class HistoryEvent(val event: String, val data: JsonElement, val meta: EventMeta)

data class HistoryPage(val events: List<HistoryEvent>, val more: Boolean)

/**
 * Who is online across the app, for a user the app vouched for: the users it watches,
 * each with `change` ({ id, online, at? }: offline since `at`), and how many are online, with `count`.
 */
class Online internal constructor(private val owner: Realtime) {
    private val states = HashMap<String, Pair<Boolean, Double?>>()
    private val watching = LinkedHashSet<String>()
    private var counting = false
    private var count_: Int? = null
    private val listeners = Listeners()

    /** How many are online, once something listens to `count`; null before. */
    val count: Int? get() = owner.onQueue { count_ }

    /** Watches users' status: now, then every change (`change`). */
    fun watch(ids: List<String>): Online {
        owner.queue.execute {
            val fresh = ids.filter { watching.add(it) }
            if (fresh.isNotEmpty()) owner.socketForEmit()?.takeIf { it.isConnected }?.let { send(mapOf("ids" to JsonArray(fresh.map { id -> JsonPrimitive(id) })), it) {} }
        }
        return this
    }

    fun unwatch(ids: List<String>) = owner.queue.execute {
        val gone = ids.filter { watching.remove(it) }
        gone.forEach { states.remove(it) }
        if (gone.isNotEmpty()) owner.socketForEmit()?.emit("unwatch", JsonObject(mapOf("tab" to JsonPrimitive(owner.tab), "ids" to JsonArray(gone.map { JsonPrimitive(it) }))))
    }

    fun has(id: String): Boolean = owner.onQueue { states[id]?.first ?: false }

    /** When a watched user was last seen, ms since 1970; null while online or never seen. */
    fun lastSeen(id: String): Double? = owner.onQueue { states[id]?.takeIf { !it.first }?.second }

    /**
     * `change` ({ id, online, at? }) for the users watched, `count` ({ n }) once listened to, and `error` ({ code }) for a
     * watch refused, such as `no_identity`, or not answered (`timeout`).
     */
    fun on(event: String, handler: (JsonElement) -> Unit): Listener {
        val listener = listeners.add(event) { data, _ -> handler(data) }
        if (event == "count") owner.queue.execute {
            if (!counting) {
                counting = true
                owner.socketForEmit()?.takeIf { it.isConnected }?.let { send(mapOf("count" to JsonPrimitive(true)), it) {} }
            }
        }
        return listener
    }

    fun off(listener: Listener) = listeners.remove(listener)

    /** Takes every handler of an event off. */
    fun off(event: String) = listeners.remove(event)

    /** A page of who is online: their ids, and the cursor of the next page ("0" after the last). */
    suspend fun list(cursor: String = "0", limit: Int = 1000): Pair<List<String>, String> {
        val reply = owner.request("online", mapOf("cursor" to JsonPrimitive(cursor), "limit" to JsonPrimitive(limit)))
        return ((reply["ids"] as? JsonArray)?.mapNotNull { it.id } ?: emptyList()) to (reply["next"].text ?: "0")
    }

    // On the owner's queue.

    internal fun again(socket: RoomSocket, done: () -> Unit) {
        if (watching.isEmpty() && !counting) return done()
        val payload = mutableMapOf<String, JsonElement>("ids" to JsonArray(watching.map { JsonPrimitive(it) }))
        if (counting) payload["count"] = JsonPrimitive(true)
        send(payload, socket, done)
    }

    private fun send(payload: Map<String, JsonElement>, socket: RoomSocket, done: () -> Unit) {
        socket.emit("watch", JsonObject(payload + ("tab" to JsonPrimitive(owner.tab)))) { reply ->
            owner.queue.execute {
                if ((reply?.get("ok") as? JsonPrimitive)?.booleanOrNull != true) {
                    val code = if (reply == null) "timeout" else reply["error"].text ?: "server"
                    listeners.fire("error", JsonObject(mapOf("code" to JsonPrimitive(code))), EventMeta(), owner.options.callbackExecutor)
                } else {
                    (reply["online"] as? JsonArray)?.forEach { set(it) }
                    (reply["count"] as? JsonPrimitive)?.intOrNull?.let { counted(it) }
                }
                done()
            }
        }
    }

    internal fun changed(o: JsonElement) {
        val id = o["id"].id ?: return
        if (id in watching) set(o)
    }

    internal fun counted(n: Int) {
        count_ = n
        listeners.fire("count", JsonObject(mapOf("n" to JsonPrimitive(n))), EventMeta(), owner.options.callbackExecutor)
    }

    private fun set(o: JsonElement) {
        val id = o["id"].id ?: return
        val online = (o["online"] as? JsonPrimitive)?.booleanOrNull ?: false
        val at = (o["at"] as? JsonPrimitive)?.doubleOrNull
        val before = states[id]
        states[id] = online to at
        if (before?.first != online || before?.second != at) {
            val value = mutableMapOf<String, JsonElement>("id" to JsonPrimitive(id), "online" to JsonPrimitive(online))
            at?.let { value["at"] = JsonPrimitive(it) }
            listeners.fire("change", JsonObject(value), EventMeta(), owner.options.callbackExecutor)
        }
    }
}
