package cloud.wordplus.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WordPlus Cloud's realtime SDK for an Android or other Kotlin app, on the same
 * realtime servers and protocol as the browser's `rooms()`: rooms with events and acks, presence, live counts and
 * history, who is online, and what the app's server sends the user, sealed end to end with keys only the app's server
 * hands out.
 *
 *     val rt = Realtime(RealtimeOptions(app = "s1500000007", endpoint = "https://shop.example.com/api/realtime",
 *                                       headers = { mapOf("Authorization" to "Bearer ${session.token}") }))
 *     rt.connect()
 *     val chat = rt.join("chat:34:42")
 *     chat.on("message") { message, meta -> show(message, meta.from) }
 *     val ack = chat.emitWithAck("message", json { "text" to "Hi" })
 *
 * The app's server answers its routes as every server package does (`{endpoint}/auth`, `/keys`): the same Express,
 * Laravel or Django server its web pages use.
 */
class Realtime(val options: RealtimeOptions) {
    /** This app's name on the connection, as a browser tab's: what the app's server's emit takes as `except`. */
    val tab: String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val keyring: Keyring = options.keyring ?: Keyring()
    /** Who is online across the app, for a user the app vouched for. */
    val online: Online

    internal val queue = Executors.newSingleThreadScheduledExecutor { Thread(it, "wordplus-realtime").apply { isDaemon = true } }
    @Volatile private var queueThread: Thread? = null
    internal val client = options.httpClient ?: OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS).build()
    private val connection: Connection
    private val rooms = HashMap<String, Room>()
    private val tokens = HashMap<String, Pair<String, Double>>()
    private var identity: Identity? = options.identity
    private val listeners = Listeners()
    private val stateListeners = ArrayList<(ConnectionState, String?) -> Unit>()
    private val pendingAuth = ArrayList<String>()
    private val authWaiters = ArrayList<() -> Unit>()
    private val waiting = ArrayDeque<Pair<List<String>, () -> Unit>>()
    private var resolving = false
    private var renewal: ScheduledFuture<*>? = null
    private var identityRenewal: ScheduledFuture<*>? = null
    private var closed = false
    /**
     * Whether the server takes several rooms in one request: a server from before it answers `bad_request`, and the app
     * goes on one room a request.
     */
    internal var many = true
        private set
    /** The rooms joined and left in this moment, which go together. */
    private val joining = ArrayList<Room>()
    private val leaving = ArrayList<String>()
    private var flushDue = false
    /** What this app answers the asks of its server with, by event (`answer`), and where the answers run. */
    private val answerers = HashMap<String, suspend (JsonElement, EventMeta) -> JsonElement>()
    private val answering = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** What the SDK's other modules hear and hold (Modules.kt). */
    internal val moduleEvents = HashMap<String, MutableList<(JsonElement) -> Unit>>()
    internal val moduleHolds = ArrayList<(RoomSocket, () -> Unit) -> Unit>()

    init {
        queue.execute { queueThread = Thread.currentThread() }
        keyring.add(options.keys)
        connection = Connection(options.server, options.path, options.handshake, queue, client, options.nodeStore ?: defaultNodeStore(), options.namespace)
        online = Online(this)
        connection.onEvent = { name, data -> event(name, data) }
        connection.onState = { state, reason ->
            if (state == Connection.State.Disconnected) rooms.values.forEach { it.lost() }
            val public = when (state) {
                Connection.State.Connected -> ConnectionState.Connected
                Connection.State.Connecting -> ConnectionState.Connecting
                Connection.State.Closed -> ConnectionState.Closed
                Connection.State.Disconnected -> ConnectionState.Disconnected
            }
            val list = synchronized(stateListeners) { stateListeners.toList() }
            options.callbackExecutor.execute { list.forEach { it(public, reason) } }
        }
        connection.onRefused = { code -> listeners.fire("error", JsonObject(mapOf("code" to JsonPrimitive(code))), EventMeta(), options.callbackExecutor) }
        connection.hold = { socket, _, done -> holdAll(socket, done) }
        queue.execute { scheduleIdentity() }
    }

    /**
     * Connects to the realtime servers, and keeps the connection: through drops, and from node to node. On `/channels` with
     * no keys or identity given, the keys route is asked for them first; on another namespace the handshake says who the
     * user is, and nothing is asked first.
     */
    fun connect() = queue.execute {
        if (identity == null && options.keys.isEmpty() && options.namespace == RealtimeOptions.CHANNELS) askKeys(emptyList()) { connection.start() } else connection.start()
    }

    /** Leaves every room, and closes the connection. */
    fun close() = queue.execute {
        closed = true
        renewal?.cancel(false)
        identityRenewal?.cancel(false)
        answering.cancel()
        rooms.values.forEach { it.ended() }
        rooms.clear()
        connection.close()
    }

    /** The user the app vouched for, or null. */
    val user: String?
        get() = identity?.token?.let { payload(it) }?.get("sub").id

    val state: ConnectionState
        get() = onQueue {
            when (connection.state) {
                Connection.State.Connected -> ConnectionState.Connected
                Connection.State.Connecting -> ConnectionState.Connecting
                Connection.State.Closed -> ConnectionState.Closed
                Connection.State.Disconnected -> ConnectionState.Disconnected
            }
        }

    /** The connection's state as it changes: connecting, connected, disconnected (with the reason), closed. */
    fun onState(handler: (ConnectionState, String?) -> Unit) {
        synchronized(stateListeners) { stateListeners.add(handler) }
    }

    /**
     * What the app's server sends this user (`toUser( id ).emit( event, data )`), `handler( data, meta )`, and those of
     * the connection itself: `error` ({ code }: the handshake refused, such as `over_capacity`), `moved`, and
     * `signed_out` (the server of the app disconnected the user, and the app asks its routes again).
     */
    fun on(event: String, handler: (JsonElement, EventMeta) -> Unit): Listener = listeners.add(event, handler)

    /** As [on], for the next one only. */
    fun once(event: String, handler: (JsonElement, EventMeta) -> Unit): Listener = listeners.once(event, handler)

    /**
     * Answers what the server of the app asks this user (`user( id ).ask( event, data )`): `handler( data, meta )`
     * returns the reply, sealed with the key of the user when the app holds it. One answer an event, null takes it away.
     * A handler that throws does not answer.
     */
    fun answer(event: String, handler: (suspend (JsonElement, EventMeta) -> JsonElement)?) = queue.execute {
        if (handler == null) answerers.remove(event) else answerers[event] = handler
    }

    fun off(listener: Listener) = listeners.remove(listener)

    /** Takes every handler of an event off. */
    fun off(event: String) = listeners.remove(event)

    /** What the app's server sends this user, as a flow. */
    fun events(event: String): Flow<Pair<JsonElement, EventMeta>> = callbackFlow {
        val listener = on(event) { data, meta -> trySend(data to meta) }
        awaitClose { off(listener) }
    }

    /** A room of the app, joined: the same one again for the same name. */
    fun join(name: String): Room = onQueue {
        rooms[name] ?: Room(this, name).also { room ->
            rooms[name] = room
            if (socketForEmit()?.isConnected == true) {
                joining.add(room)
                flushSoon()
            }
        }
    }

    /**
     * A room of this user and [with]: `{pair}` in the name is the two ids in order, so both of them name the same room,
     * `join("chat:{pair}", with = "34")`. Throws `no_identity` where the app's server named no user.
     */
    fun join(name: String, with: String): Room = join(RoomName.named(name, user, with))

    /** A room this app joined, or null. */
    fun room(name: String): Room? = onQueue { rooms[name] }

    /** The room `join(name, with)` joined, or null. */
    fun room(name: String, with: String): Room? = room(RoomName.named(name, user, with))

    /** Leaves a room. */
    fun leave(name: String) = queue.execute {
        val room = rooms.remove(name) ?: return@execute
        tokens.remove(name)
        room.ended()
        leaving.add(name)
        flushSoon()
    }

    /** Leaves the room `join(name, with)` joined. */
    fun leave(name: String, with: String) = leave(RoomName.named(name, user, with))

    /** Users of the app from its directory, by id, opened: those it holds, as `{ id: { user_id, name, avatar, … } }`. */
    suspend fun users(ids: List<String>): Map<String, JsonElement> {
        val asked = ids.toSet().take(100).map { id -> id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(id) }
        val reply = request("users", mapOf("ids" to JsonArray(asked)))
        val found = (reply["users"] as? JsonObject) ?: JsonObject(emptyMap())
        val ready = CompletableDeferred<Unit>()
        queue.execute { inOrder(listOf(found)) { ready.complete(Unit) } }
        ready.await()
        return found.mapValues { (_, profile) -> (profile as? JsonObject)?.let { JsonObject(it.mapValues { f -> openDeep(f.value, 0) }) } ?: JsonNull }
    }

    // On the queue.

    internal fun <T> onQueue(block: () -> T): T = if (Thread.currentThread() === queueThread) block() else queue.submit(Callable { block() }).get()

    internal suspend fun request(event: String, payload: Map<String, JsonElement>, timeoutMs: Long = 8_000): JsonElement {
        val socket = onQueue { socketForEmit() }?.takeIf { it.isConnected } ?: throw RealtimeException("offline")
        val body = JsonObject(payload + ("tab" to JsonPrimitive(tab)))
        var reply = socket.request(event, body, timeoutMs)
        if (reply["error"].text == "no_identity") {
            val done = CompletableDeferred<Unit>()
            queue.execute { identify(socket) { done.complete(Unit) } }
            done.await()
            reply = socket.request(event, body, timeoutMs)
        }
        if (reply == null) throw RealtimeException("timeout")
        if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw RealtimeException(reply["error"].text ?: "server")
        return reply
    }

    /** Everything held again on a socket: the identity first, every room from the last event it had, the watches. */
    internal fun holdAll(socket: RoomSocket, done: (Boolean) -> Unit) {
        var left = 3 + moduleHolds.size
        var ok = true
        val one = { joined: Boolean ->
            if (!joined) ok = false
            if (--left == 0) done(ok)
        }
        identify(socket) { one(true) }
        joinMany(rooms.values.toList(), socket) { one(it) }
        online.again(socket) { one(true) }
        for (hold in moduleHolds.toList()) hold(socket) { queue.execute { one(true) } }
    }

    /**
     * Tells the socket who the user is. An identity refused as `expired` (signed before the server of the app
     * disconnected the user, or run out) is asked for again, once.
     */
    private fun identify(socket: RoomSocket, renew: Boolean = true, done: () -> Unit) {
        val token = identity?.token ?: return done()
        socket.emit("identify", JsonObject(mapOf("tab" to JsonPrimitive(tab), "token" to JsonPrimitive(token)))) { reply ->
            queue.execute {
                if (reply?.get("error").text == "expired" && renew && identity?.token == token) {
                    identity = null
                    queue.schedule({ renewIdentity() }, RENEW_AFTER_MS, TimeUnit.MILLISECONDS)
                }
                done()
            }
        }
    }

    /** The identity asked of the keys route again, then who the app watches and what its modules hold, held again. */
    private fun renewIdentity() {
        askKeys(emptyList(), renew = false) {
            val socket = connection.current?.takeIf { it.isConnected } ?: return@askKeys
            if (identity == null) return@askKeys
            online.again(socket) {}
            for (hold in moduleHolds.toList()) hold(socket) {}
        }
    }

    /** An ask of the server of the app for this user: answered when this app answers its event (`answer`). */
    private fun answerAsk(data: JsonElement) {
        val id = data["id"].text ?: return
        val handler = answerers[data["event"].text ?: return] ?: return
        val payload = data["data"] ?: JsonNull
        inOrder(listOf(payload)) {
            val opened = keyring.openJson(payload) ?: return@inOrder
            val meta = EventMeta(at = (data["at"] as? JsonPrimitive)?.doubleOrNull)
            answering.launch {
                val reply = runCatching { handler(opened, meta) }.getOrNull() ?: return@launch
                queue.execute {
                    val label = user?.let { keyring.newest("u:$it:") }
                    val sent: JsonElement = label?.let { keyring.sealJson(reply, it) }?.let { JsonPrimitive(it) } ?: reply
                    connection.current?.emit("reply", JsonObject(mapOf("tab" to JsonPrimitive(tab), "id" to JsonPrimitive(id), "data" to sent)))
                }
            }
        }
    }

    /** Joins a room on a socket with its token, from the last kept event it had; true when it is in. */
    internal fun joinOne(room: Room, socket: RoomSocket, retry: Boolean = true, done: (Boolean) -> Unit) {
        tokenFor(room.name) { token ->
            if (rooms[room.name] !== room) return@tokenFor done(true)
            if (token == null) {
                if (room.stateOnQueue != RoomState.Failed) room.failed(room.denied ?: "auth_failed")
                return@tokenFor done(true)
            }
            val payload = mutableMapOf<String, JsonElement>("tab" to JsonPrimitive(tab), "room" to JsonPrimitive(room.name), "token" to JsonPrimitive(token))
            room.lastId?.let { payload["since"] = JsonPrimitive(it) }
            socket.emit("join", JsonObject(payload)) { reply ->
                queue.execute {
                    if (reply == null) done(rooms[room.name] !== room) else joined(room, reply, socket, retry, done)
                }
            }
        }
    }

    /** A room's answer to its join, alone or among others; true when it is in, or it is done with. */
    private fun joined(room: Room, reply: JsonElement, socket: RoomSocket, retry: Boolean, done: (Boolean) -> Unit) {
        if (rooms[room.name] !== room) return done(true)
        if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull == true) {
            val members = reply["members"]
            val missed = (reply["missed"] as? JsonArray) ?: JsonArray(emptyList())
            inOrder(listOf(members?.get("list") ?: JsonNull) + missed.map { it["data"] ?: JsonNull }) {
                if (rooms[room.name] !== room) return@inOrder
                val list = (members?.get("list") as? JsonArray)?.mapNotNull { m -> m["id"].id?.let { it to openInfo(m["info"] ?: JsonNull) } }
                room.joined(if (members == null || members is JsonNull) null else list ?: emptyList(), (reply["count"] as? JsonPrimitive)?.intOrNull)
                missed.forEach { deliver(it, room.name, replayed = true) }
                if ((reply["gap"] as? JsonPrimitive)?.booleanOrNull == true) room.fire("error", JsonObject(mapOf("code" to JsonPrimitive("gap"))), EventMeta())
            }
            done(true)
        } else if (reply["error"].text == "expired" && retry) {
            tokens.remove(room.name)
            joinOne(room, socket, false, done)
        } else {
            room.failed(reply["error"].text ?: "server")
            done(true)
        }
    }

    private fun flushSoon() {
        if (flushDue) return
        flushDue = true
        queue.schedule({ flushRooms() }, togetherAfterMs, TimeUnit.MILLISECONDS)
    }

    /**
     * The rooms left then the rooms joined in this moment, on the socket the connection has now. With none, a connect
     * joins what is held then.
     */
    private fun flushRooms() {
        flushDue = false
        val left = leaving.toList()
        val wanted = joining.toList()
        leaving.clear()
        joining.clear()
        val socket = socketForEmit()?.takeIf { it.isConnected } ?: return
        leaveMany(left, socket)
        joinMany(wanted.distinct().filter { rooms[it.name] === it }, socket) {}
    }

    /**
     * Rooms in one `join`: each token once, each room naming its own by index, each answered as its own join. True when
     * every room had its answer: a move holds its new socket by this, and a socket that stops answering fails it.
     */
    internal fun joinMany(list: List<Room>, socket: RoomSocket, done: (Boolean) -> Unit) {
        if (list.size > JOIN_MAX) {
            val parts = list.chunked(JOIN_MAX)
            val one = allOf(parts.size, done)
            parts.forEach { joinMany(it, socket, one) }
            return
        }
        if (list.size < 2 || !many) return joinEach(list, socket, done)

        val found = HashMap<String, String>()
        var left = list.size
        for (room in list) {
            tokenFor(room.name) { token ->
                if (token != null) found[room.name] = token
                if (--left == 0) joinAsked(list, found, socket, done)
            }
        }
    }

    private fun joinAsked(list: List<Room>, found: Map<String, String>, socket: RoomSocket, done: (Boolean) -> Unit) {
        val sent = ArrayList<String>()
        val asked = ArrayList<JsonElement>()
        val going = ArrayList<Room>()
        for (room in list) {
            if (rooms[room.name] !== room) continue
            val token = found[room.name]
            if (token == null) {
                if (room.stateOnQueue != RoomState.Failed) room.failed(room.denied ?: "auth_failed")
                continue
            }
            var index = sent.indexOf(token)
            if (index < 0) {
                index = sent.size
                sent.add(token)
            }
            val entry = mutableMapOf<String, JsonElement>("room" to JsonPrimitive(room.name), "t" to JsonPrimitive(index))
            room.lastId?.let { entry["since"] = JsonPrimitive(it) }
            asked.add(JsonObject(entry))
            going.add(room)
        }
        if (going.size < 2) return joinEach(going, socket, done)

        val payload = JsonObject(mapOf("tab" to JsonPrimitive(tab), "tokens" to JsonArray(sent.map { JsonPrimitive(it) }), "rooms" to JsonArray(asked)))
        socket.emit("join", payload) { reply ->
            queue.execute {
                // A server from before this has no `room` to read.
                if (reply?.get("error").text == "bad_request" && reply?.get("rooms") == null) {
                    many = false
                    return@execute joinEach(going, socket, done)
                }
                if (reply == null) return@execute done(false)
                val told = (reply["ok"] as? JsonPrimitive)?.booleanOrNull == true
                val one = allOf(going.size, done)
                for (room in going) joined(room, if (told) reply["rooms"]?.get(room.name) ?: JsonNull else reply, socket, true, one)
            }
        }
    }

    private fun joinEach(list: List<Room>, socket: RoomSocket, done: (Boolean) -> Unit) {
        if (list.isEmpty()) return done(true)
        val one = allOf(list.size, done)
        list.forEach { joinOne(it, socket, true, one) }
    }

    /** `done` once `count` have answered: true when all said true. On the queue. */
    private fun allOf(count: Int, done: (Boolean) -> Unit): (Boolean) -> Unit {
        var left = count
        var ok = true
        return { joined ->
            if (!joined) ok = false
            if (--left == 0) done(ok)
        }
    }

    /** Rooms in one `leave`, a server from before it taking them one by one. */
    internal fun leaveMany(names: List<String>, socket: RoomSocket) {
        val each = { names.forEach { socket.emit("leave", JsonObject(mapOf("tab" to JsonPrimitive(tab), "room" to JsonPrimitive(it)))) } }
        if (names.size < 2 || !many) return each()
        socket.emit("leave", JsonObject(mapOf("tab" to JsonPrimitive(tab), "rooms" to JsonArray(names.map { JsonPrimitive(it) })))) { reply ->
            queue.execute {
                if (reply?.get("error").text == "bad_request") {
                    many = false
                    each()
                }
            }
        }
    }

    internal fun tokenFor(name: String, done: (String?) -> Unit) {
        val now = System.currentTimeMillis() / 1000.0
        tokens[name]?.takeIf { it.second - now > 60 }?.let { return done(it.first) }
        options.grants.firstOrNull { g -> g.exp - now > 60 && g.rooms.any { patternMatches(it, name) } }?.let { return done(it.token) }
        requestToken(name) { done(tokens[name]?.first) }
    }

    /** Asks the app's server for a room's token, in one request with every other room asked for in the same moment. */
    private fun requestToken(name: String, done: () -> Unit) {
        val first = pendingAuth.isEmpty()
        pendingAuth.add(name)
        authWaiters.add(done)
        if (first) queue.execute { flushAuth() }
    }

    /** Asks the app's server for the rooms asked for in the same moment, in one request. */
    private fun flushAuth() {
        val names = pendingAuth.toSet().toList()
        val waiters = authWaiters.toList()
        pendingAuth.clear()
        authWaiters.clear()
        post("auth", JsonObject(mapOf("rooms" to JsonArray(names.map { JsonPrimitive(it) })))) { answer ->
            val exp = (answer?.get("exp") as? JsonPrimitive)?.doubleOrNull ?: (System.currentTimeMillis() / 1000.0 + 3600)
            keyring.add((answer?.get("keys") as? JsonArray)?.mapNotNull { it.text } ?: emptyList())
            for (name in names) {
                val denied = answer?.get("denied")?.get(name).text
                val token = answer?.get("token").text
                when {
                    denied != null -> {
                        tokens.remove(name)
                        rooms[name]?.denied = denied
                    }
                    token != null -> tokens[name] = token to exp
                    else -> rooms[name]?.denied = "auth_failed"
                }
            }
            scheduleRenewal()
            waiters.forEach { it() }
        }
    }

    /** The tokens of the rooms still held, asked for again ten minutes before the first runs out. */
    private fun scheduleRenewal() {
        renewal?.cancel(false)
        val held = tokens.filterKeys { rooms.containsKey(it) }
        val first = held.values.minOfOrNull { it.second } ?: return
        val inMs = maxOf(renewFloorS, first - RENEW_BEFORE_S - System.currentTimeMillis() / 1000.0) * 1000
        // Asked of the route, not taken from the tokens kept, which are still good then: their answer schedules the next.
        renewal = queue.schedule({ held.keys.filter { rooms.containsKey(it) }.forEach { requestToken(it) {} } }, inMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /** The keys route: keys asked for that the user may have, or with none their own keys and identity. */
    internal fun askKeys(labels: List<String>, renew: Boolean = true, done: () -> Unit) {
        post("keys", JsonObject(mapOf("labels" to JsonArray(labels.map { JsonPrimitive(it) })))) { answer ->
            keyring.add((answer?.get("keys") as? JsonArray)?.mapNotNull { it.text } ?: emptyList())
            val token = answer?.get("identity")?.get("token").text
            if (token != null && token != identity?.token) {
                identity = Identity(token, (answer?.get("identity")?.get("exp") as? JsonPrimitive)?.doubleOrNull ?: 0.0)
                scheduleIdentity()
                connection.current?.takeIf { it.isConnected }?.let { identify(it, renew) {} }
            }
            done()
        }
    }

    /** The identity asked for again before it runs out, so an app open for days stays known. One with no end is left. */
    private fun scheduleIdentity() {
        identityRenewal?.cancel(false)
        identityRenewal = null
        val exp = identity?.exp?.takeIf { it > 0 } ?: return
        if (closed) return
        val inMs = maxOf(renewFloorS, exp - RENEW_BEFORE_S - System.currentTimeMillis() / 1000.0) * 1000
        identityRenewal = queue.schedule({ askKeys(emptyList()) {} }, inMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /** A POST to one of the app's routes, its JSON answer on the queue (null when it failed). */
    private fun post(route: String, body: JsonElement, done: (JsonElement?) -> Unit) {
        postRoute(route, body) { status, answer -> done(if (status == 200) answer else null) }
    }

    /** A POST to one of the app's routes: its status (0 when it never answered) and JSON answer, on the queue. */
    internal fun postRoute(route: String, body: JsonElement, done: (Int, JsonElement?) -> Unit) {
        val request = Request.Builder()
            .url(options.routes[route] ?: (options.endpoint.trimEnd('/') + "/" + route))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { options.headers().forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = queue.execute { done(0, null) }
            override fun onResponse(call: Call, response: Response) {
                val status = response.code
                val text = response.use { it.body?.string() }
                val answer = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                queue.execute { done(status, answer) }
            }
        })
    }

    /**
     * Runs `then` once the keyring holds the keys these values need, after whatever waits before it, so a listener
     * never gets an event ahead of an earlier one.
     */
    internal fun inOrder(values: List<JsonElement>, then: () -> Unit) {
        val missing = values.flatMap { keyring.missing(it) }
        if (missing.isEmpty() && waiting.isEmpty()) return then()
        waiting.addLast(missing to then)
        pump()
    }

    private fun pump() {
        if (resolving) return
        val head = waiting.firstOrNull() ?: return
        val need = head.first.filter { !keyring.has(it) }
        if (need.isEmpty()) {
            waiting.removeFirst()
            head.second()
            return pump()
        }
        resolving = true
        askKeys(need) {
            resolving = false
            waiting.removeFirst()
            head.second()
            pump()
        }
    }

    internal fun event(name: String, data: JsonElement) {
        when (name) {
            "e" -> deliver(data, data["room"].text ?: "", replayed = false)
            "join" -> {
                if (data["x"].text == tab) return
                val room = rooms[data["room"].text] ?: return
                val id = data["id"].id ?: return
                inOrder(listOf(data["info"] ?: JsonNull)) { room.memberJoined(id, openInfo(data["info"] ?: JsonNull)) }
            }
            "leave" -> {
                val room = rooms[data["room"].text] ?: return
                val id = data["id"].id ?: return
                inOrder(emptyList()) { room.memberLeft(id) }
            }
            "count" -> rooms[data["room"].text]?.counted((data["n"] as? JsonPrimitive)?.intOrNull ?: 0)
            "kicked" -> {
                if (data["tab"].text != tab) return
                val roomName = data["room"].text ?: return
                val room = rooms[roomName] ?: return
                tokens.remove(roomName)
                // The server of the app disconnected the user: the room is asked of its routes again, whose rules decide.
                if ((data["renew"] as? JsonPrimitive)?.booleanOrNull == true) {
                    room.lost()
                    queue.schedule({
                        val socket = connection.current?.takeIf { it.isConnected }
                        if (rooms[roomName] === room && socket != null) joinOne(room, socket) {}
                    }, RENEW_AFTER_MS, TimeUnit.MILLISECONDS)
                    return
                }
                rooms.remove(roomName)
                room.failed("kicked")
            }
            "signed_out" -> {
                identity = null
                listeners.fire("signed_out", JsonNull, EventMeta(), options.callbackExecutor)
                queue.schedule({ renewIdentity() }, RENEW_AFTER_MS, TimeUnit.MILLISECONDS)
            }
            "ask" -> answerAsk(data)
            "u" -> {
                val event = data["event"].text ?: return
                if (data["x"].text == tab) return
                val payload = data["data"] ?: JsonNull
                inOrder(listOf(payload)) {
                    val meta = EventMeta(at = (data["at"] as? JsonPrimitive)?.doubleOrNull)
                    val opened = keyring.openJson(payload)
                    if (opened != null) listeners.fire(event, opened, meta, options.callbackExecutor)
                    else listeners.fire("error", unopened(event, payload), meta, options.callbackExecutor)
                }
            }
            "online" -> online.changed(data)
            "online_count" -> online.counted((data["n"] as? JsonPrimitive)?.intOrNull ?: 0)
            "moved" -> listeners.fire("moved", JsonNull, EventMeta(), options.callbackExecutor)
            else -> moduleEvents[name]?.toList()?.forEach { it(data) }
        }
    }

    /** An event for one of this app's rooms: never one its own action made (`x`), nor one it had already. */
    private fun deliver(e: JsonElement, roomName: String, replayed: Boolean) {
        val event = e["event"].text ?: return
        if (e["x"].text == tab) return
        val room = rooms[roomName] ?: return
        val payload = e["data"] ?: JsonNull
        inOrder(listOf(payload)) {
            if (rooms[roomName] !== room || room.stateOnQueue == RoomState.Failed) return@inOrder
            val id = e["id"].text
            if (id != null && !room.fresh(id)) return@inOrder
            val meta = EventMeta(from = e["from"].id, id = id, at = (e["at"] as? JsonPrimitive)?.doubleOrNull, replayed = replayed)
            val opened = keyring.openJson(payload)
            if (opened != null) room.fire(event, opened, meta) else room.fire("error", unopened(event, payload), meta)
        }
    }

    private fun unopened(event: String, payload: JsonElement) =
        JsonObject(mapOf("code" to JsonPrimitive("unopened"), "event" to JsonPrimitive(event), "label" to JsonPrimitive(Keyring.labelOf(payload.text ?: "") ?: "")))

    internal fun openInfo(info: JsonElement): JsonElement = keyring.openJson(info) ?: JsonNull

    private fun openDeep(value: JsonElement, depth: Int): JsonElement = when {
        value is JsonPrimitive && value.isString -> keyring.open(value.content)?.let { JsonPrimitive(it) } ?: JsonNull
        value is JsonArray && depth < 9 -> JsonArray(value.map { openDeep(it, depth + 1) })
        value is JsonObject && depth < 9 -> JsonObject(value.mapValues { openDeep(it.value, depth + 1) })
        else -> value
    }

    internal fun socketForEmit(): RoomSocket? = socketAsked ?: connection.current

    /** A socket in place of the connection's, for the tests. */
    internal var socketAsked: RoomSocket? = null

    companion object {
        /**
         * After the server of the app disconnected the user: how long the app waits before it asks its routes again, as
         * the realtime server refuses the tokens signed in the second of the disconnect too.
         */
        internal const val RENEW_AFTER_MS = 1_100L

        /** How long before a room's token or the identity runs out it is asked for again, as the browser's rooms() asks. */
        internal const val RENEW_BEFORE_S = 600.0

        /** The most rooms one `join` names, as the realtime server takes them. */
        internal const val JOIN_MAX = 100

        /**
         * How long the rooms joined and left in one moment wait for each other: an app's `join`s one after the other reach
         * the queue apart, where a page's run in one turn of its loop.
         */
        @Volatile internal var togetherAfterMs = 10L

        /** The least a renewal waits, so a token that came near its end isn't asked for again and again. Tests shorten it. */
        @Volatile internal var renewFloorS = 30.0

        /** A pattern of a public grant, as the realtime server matches it: each `{part}` one or more characters other than `:` and `.`. */
        fun patternMatches(pattern: String, room: String): Boolean {
            if (!pattern.contains('{')) return pattern == room
            val source = StringBuilder("^")
            var rest = pattern
            while (true) {
                val open = rest.indexOf('{')
                val close = if (open >= 0) rest.indexOf('}', open) else -1
                if (open < 0 || close < 0) break
                source.append(Regex.escape(rest.substring(0, open))).append("[^:.]+?")
                rest = rest.substring(close + 1)
            }
            if (rest.isNotEmpty()) source.append(Regex.escape(rest))
            return Regex(source.append("$").toString()).matches(room)
        }

        internal fun payload(token: String): JsonElement? {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val bytes = Base64Url.decode(parts[1]) ?: return null
            return runCatching { Json.parseToJsonElement(String(bytes)) }.getOrNull()
        }
    }
}

/** connect()'s settings: the app, its routes, and a signed-in user's keys and identity when the app's server gave them. */
data class RealtimeOptions(
    /** The app's key, from the WordPlus Cloud account (Apps). Public: every app carries it. */
    val app: String,
    /** Where the app's server answers: `{endpoint}/auth`, `/calls` and `/keys`, in full. */
    val endpoint: String,
    /** What every request to the app's routes carries, such as the user's session token. */
    val headers: () -> Map<String, String> = { emptyMap() },
    val server: String = "https://ws.wordplus.cloud",
    val path: String = "/websocket",
    /** A signed-in user's keys and identity, from the server's config(): then no request comes first. */
    val keys: List<String> = emptyList(),
    val identity: Identity? = null,
    /** Grants for the app's public rooms, from the server's config(). */
    val grants: List<PublicGrant> = emptyList(),
    /** Where handlers are called: a thread of their own by default; on Android, the main thread's executor. */
    val callbackExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "wordplus-realtime-callbacks").apply { isDaemon = true } },
    val keyring: Keyring? = null,
    /** Where the nodes are kept between launches: on Android a file in the app's cache folder unless this says otherwise, elsewhere only in memory. */
    val nodeStore: NodeStore? = null,
    val httpClient: OkHttpClient? = null,
    /**
     * A route at an address of its own, in full, in place of `{endpoint}/{name}`, as the server package's `routes` put
     * it: `mapOf("calls" to "https://shop.example.com/check-call")`.
     */
    val routes: Map<String, String> = emptyMap(),
    /**
     * The namespace of the realtime server the connection opens: `/channels`, this SDK's own, unless the app speaks another
     * product's protocol, such as Better Messages' on `/` (`P2PCallTransport` carries its calls). Reconnecting, moving to
     * another node and the nodes kept between launches work the same on every namespace, as the realtime server sends
     * `migrate` and `nodes` in each. Rooms, presence and the SDK's own calls are `/channels`' protocol.
     */
    val namespace: String = CHANNELS,
    /**
     * The handshake of a namespace other than `/channels`, such as Better Messages' `{ "sid": site, "uid": user, "sk": key,
     * "v": version }`. Null sends this SDK's own, `{ "site": app, "rt": 3 }`. An object without `rt` is sent with `"rt": 1`,
     * which tells the realtime server that this connection moves between nodes.
     */
    val auth: JsonElement? = null,
) {
    /** What the connection's handshake sends: [auth] with `rt` in it, or this SDK's own. */
    internal val handshake: JsonElement
        get() = when (val given = auth) {
            null -> JsonObject(mapOf("site" to JsonPrimitive(app), "rt" to JsonPrimitive(3)))
            is JsonObject -> if (given.containsKey("rt")) given else JsonObject(given + ("rt" to JsonPrimitive(1)))
            else -> given
        }

    companion object {
        /** The SDK's own namespace, which a connection opens unless its options name another. */
        const val CHANNELS = "/channels"

        /**
         * From the server package's config() answer (`realtime` in the API demo's sign-in), its routes resolved
         * against `base`, the app server's address.
         */
        fun fromConfig(config: JsonElement, base: String, headers: () -> Map<String, String> = { emptyMap() }, callbackExecutor: Executor? = null, nodeStore: NodeStore? = null, server: String? = null): RealtimeOptions {
            // Each route in full, and the ones not beside the auth route kept apart.
            fun address(name: String): String {
                val given = config[name]?.get("endpoint").text ?: "/api/realtime/$name"
                return if (given.startsWith("http")) given else base.trimEnd('/') + "/" + given.trimStart('/')
            }
            val endpoint = address("auth").removeSuffix("/auth")
            val identity = config["keys"]?.get("identity")
            return RealtimeOptions(
                app = config["site"].text ?: "",
                endpoint = endpoint,
                routes = listOf("auth", "calls", "keys").associateWith(::address).filter { (name, given) -> given != "$endpoint/$name" },
                headers = headers,
                server = server ?: config["server"].text ?: "https://ws.wordplus.cloud",
                path = config["path"].text ?: "/websocket",
                keys = (config["keys"]?.get("entries") as? JsonArray)?.mapNotNull { it.text } ?: emptyList(),
                identity = identity?.get("token").text?.let { Identity(it, (identity?.get("exp") as? JsonPrimitive)?.doubleOrNull ?: 0.0) },
                grants = (config["grants"] as? JsonArray)?.mapNotNull { g ->
                    g["token"].text?.let { PublicGrant(it, (g["exp"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, (g["rooms"] as? JsonArray)?.mapNotNull { r -> r.text } ?: emptyList()) }
                } ?: emptyList(),
                callbackExecutor = callbackExecutor ?: Executors.newSingleThreadExecutor { Thread(it, "wordplus-realtime-callbacks").apply { isDaemon = true } },
                nodeStore = nodeStore,
            )
        }
    }
}

data class Identity(val token: String, val exp: Double)

data class PublicGrant(val token: String, val exp: Double, val rooms: List<String>)

/** What comes with every event: who sent it (none for the app's server), its id when the room keeps it, when, and whether it was missed while the connection was down. */
data class EventMeta(val from: String? = null, val id: String? = null, val at: Double? = null, val replayed: Boolean = false)

enum class ConnectionState { Connecting, Connected, Disconnected, Closed }

/** What the realtime server or the app's server refused, by its code: `forbidden`, `rate_limited`, `too_large` … */
class RealtimeException(val code: String) : Exception(code)

/** A handler that can be removed. */
class Listener internal constructor(internal val event: String, internal val id: Long)

internal class Listeners {
    private class Entry(val id: Long, val once: Boolean, val fn: (JsonElement, EventMeta) -> Unit)

    private val byEvent = HashMap<String, MutableList<Entry>>()
    private val any = ArrayList<Pair<Long, (String, JsonElement, EventMeta) -> Unit>>()
    private var next = 0L

    fun add(event: String, fn: (JsonElement, EventMeta) -> Unit): Listener = put(event, false, fn)

    fun once(event: String, fn: (JsonElement, EventMeta) -> Unit): Listener = put(event, true, fn)

    @Synchronized
    private fun put(event: String, once: Boolean, fn: (JsonElement, EventMeta) -> Unit): Listener {
        val id = ++next
        byEvent.getOrPut(event) { ArrayList() }.add(Entry(id, once, fn))
        return Listener(event, id)
    }

    @Synchronized
    fun onAny(fn: (String, JsonElement, EventMeta) -> Unit): Listener {
        val id = ++next
        any.add(id to fn)
        return Listener("*", id)
    }

    @Synchronized
    fun remove(listener: Listener) {
        byEvent[listener.event]?.removeAll { it.id == listener.id }
        any.removeAll { it.first == listener.id }
    }

    @Synchronized
    fun remove(event: String) {
        byEvent.remove(event)
    }

    fun fire(event: String, data: JsonElement, meta: EventMeta, executor: Executor, fireAny: Boolean = false) {
        val list: List<(JsonElement, EventMeta) -> Unit>
        val anyList: List<(String, JsonElement, EventMeta) -> Unit>
        synchronized(this) {
            list = byEvent[event]?.map { it.fn } ?: emptyList()
            // Off here, not as it runs: two fired before the first runs reach it once.
            byEvent[event]?.removeAll { it.once }
            anyList = if (fireAny) any.map { it.second } else emptyList()
        }
        if (list.isEmpty() && anyList.isEmpty()) return
        executor.execute {
            anyList.forEach { it(event, data, meta) }
            list.forEach { it(data, meta) }
        }
    }
}
