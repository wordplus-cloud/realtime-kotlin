package cloud.wordplus.realtime.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import cloud.wordplus.realtime.ConnectionState
import cloud.wordplus.realtime.ModuleSocket
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.WordPlusModulesApi
import cloud.wordplus.realtime.addHold
import cloud.wordplus.realtime.currentSocket
import cloud.wordplus.realtime.ensureKeys
import cloud.wordplus.realtime.moduleQueue
import cloud.wordplus.realtime.onSocketEvent
import cloud.wordplus.realtime.postToRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoTrack
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class CallType(val wire: String) { AUDIO("audio"), VIDEO("video") }

/** Where a call is: ringing (its caller waits), connecting, connected, reconnecting, or ended. */
enum class CallState(val wire: String) { RINGING("ringing"), CONNECTING("connecting"), CONNECTED("connected"), RECONNECTING("reconnecting"), ENDED("ended") }

/** What carries a call: straight between the two, through TURN, or a room on the mesh. */
enum class CallPath(val wire: String) { DIRECT("direct"), RELAY("relay"), ROOM("room") }

/** A refusal, by its code: `forbidden` (the app's server said no), `auth_failed`, `not_ringing`, `server` … */
class CallException(val code: String) : Exception(code)

/** Someone this app's user may add to a call, as the app's server lists them (`calls.people`). */
data class CallPerson(val id: String, val name: String, val avatar: String? = null)

/** A question for `calls.may`: whether this app's user may call [to] about the call [call]. */
data class CallAsk(val call: String, val to: String)

/** What the app's calls route is asked, and how its answers read, apart from the request that carries them. */
internal object CallsRoute {
    /** How many questions go in one request of `may`, as the browser's (calls.ts, MAY_AT_ONCE). */
    const val MAY_AT_ONCE = 100

    /** How long to wait after being out at [out]: into the next second, and a little more for the clocks to differ. */
    fun outWaitMs(out: Long, now: Long): Long = (out / 1000 + 1) * 1000 + OUT_MARGIN_MS - now

    private const val OUT_MARGIN_MS = 150L

    /** The question of who this user may add to calls of a name. */
    fun people(name: String): JsonObject = JsonObject(mapOf("people" to JsonPrimitive(name)))

    /** The people an answer lists, leaving out any with no valid id or name. */
    fun people(answer: JsonObject): List<CallPerson> = (answer["people"] as? JsonArray)?.mapNotNull { element ->
        val person = element as? JsonObject ?: return@mapNotNull null
        val id = user(person["id"]) ?: return@mapNotNull null
        val name = (person["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        CallPerson(id, name, (person["avatar"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    } ?: emptyList()

    /** The question of whether this user may call each person about each call, an id of digits as the number. */
    fun may(asks: List<CallAsk>): JsonObject = JsonObject(mapOf("may" to JsonArray(asks.map { ask ->
        JsonObject(mapOf(
            "call" to JsonPrimitive(ask.call),
            "to" to when (val to = RealtimeCalls.userValue(ask.to)) {
                is Long -> JsonPrimitive(to)
                else -> JsonPrimitive(ask.to)
            },
        ))
    })))

    /** The answer to [count] questions: only `true` is yes, and null each where the server gave no answer. */
    fun mayAnswer(answer: JsonObject?, count: Int): List<Boolean?> {
        val may = (answer?.get("may") as? JsonArray)?.takeIf { it.size == count } ?: return List(count) { null }
        return may.map { value -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true }
    }

    /**
     * Whether the answer for a group call's room says this user may record it: its `record`, which the server packages give
     * whoever the call's rule answers `record`, else its host. Only `true` is yes.
     */
    fun mayRecord(grant: JSONObject): Boolean = grant.opt("record") == true

    /**
     * A user as the relay names them, in the SDK's spelling, as the browser's read them (calls/users.ts, callUserOf): a
     * whole number, or at most 64 characters with no space or control character in them. Null for anything else, and 0 is
     * nobody.
     */
    fun user(value: JsonElement?): String? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) {
            val number = primitive.doubleOrNull ?: return null
            if (number != kotlin.math.floor(number) || number == 0.0 || kotlin.math.abs(number) >= 9_007_199_254_740_992.0) return null
            return number.toLong().toString()
        }
        val text = primitive.content
        if (text.isEmpty() || text.length > 64 || text == "0") return null
        return text.takeIf { it.none { c -> c.isWhitespace() || c.code < 0x20 || c.code == 0x7f } }
    }

    /**
     * Its answer, a token or not, or its refusal by code: `denied`, `forbidden` for a 401 or 403 without one,
     * `auth_failed` for no answer.
     */
    fun answer(status: Int, answer: JsonElement?): JsonObject {
        val grant = answer as? JsonObject
        val denied = (grant?.get("denied") as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        if (status == 401 || status == 403) throw CallException(denied ?: "forbidden")
        if (status != 200 || grant == null) throw CallException("auth_failed")
        if (denied != null) throw CallException(denied)
        return grant
    }

    /**
     * A host's act in the group call [name], the user an id of digits as the number, any other as its text, and null for
     * everyone out of the spotlight. With [host], the proof a token of the app's server gave its host, as the rule isn't asked.
     */
    fun moderation(name: String, act: ModerationAct, user: String?, host: JsonObject? = null): JsonObject {
        val value = user?.let { RealtimeCalls.userValue(it) }
        return JsonObject(mapOf(
            "moderate" to JsonPrimitive(name),
            "act" to JsonPrimitive(act.wire),
            "user" to when (value) {
                null -> JsonNull
                is Long -> JsonPrimitive(value)
                else -> JsonPrimitive(user)
            },
        ) + (host?.let { mapOf("host" to it) } ?: emptyMap()))
    }

    /** Whether a host's act changed anything, as its answer says: `server` for an answer that doesn't say. */
    fun done(answer: JsonObject): Boolean =
        (answer["done"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw CallException("server")

    /**
     * The group call [name] recorded (`start`) or stopped (`stop`, the [recording] to stop). With [recorder], the proof a
     * token of the app's server gave whoever may record it, as the rule isn't asked.
     */
    fun record(name: String, action: String, recording: String?, recorder: JsonObject? = null): JsonObject = JsonObject(
        mapOf("call" to JsonPrimitive(name), "record" to JsonPrimitive(action)) +
            (recording?.let { mapOf("recording" to JsonPrimitive(it)) } ?: emptyMap()) +
            (recorder?.let { mapOf("recorder" to it) } ?: emptyMap())
    )

    /** The recording an answer tells of, its id and state: `server` for an answer that doesn't say. */
    fun recording(answer: JsonObject): GroupRecording {
        val told = answer["recording"] as? JsonObject
        fun text(key: String) = (told?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = text("id")?.takeIf { it.isNotEmpty() } ?: throw CallException("server")
        return GroupRecording(id, text("state") ?: throw CallException("server"))
    }
}

/**
 * Calls between the app's users, on the realtime connection (`rt.calls(context)`), as the browser's `calls()`: the same
 * relay and the same call engine, so a phone and a browser call each other.
 *
 *     rt.calls(context).onIncoming { incoming -> show(incoming) }      // incoming.accept() or incoming.decline()
 *     val call = rt.calls(context).startCall(tokenFromYourServer)
 *     call.onState { state -> … }
 *
 * The app's server decides who may call whom: a route of its own checks what it needs and gives the app a token
 * (`startCall`, `joinGroupCall`, `joinStream`), or its calls route signs each call and line by a rule (`{endpoint}/calls`,
 * the server package's `calls()`, for `start`, `group` and `stream`). Calls ring while the app is open. With `SystemCalls`
 * started they ring a locked phone and a closed app too. The app asks for RECORD_AUDIO (and CAMERA for video) itself.
 * Without them a call connects and sends nothing.
 */
class RealtimeCalls private constructor(context: Context, internal val rt: Realtime) {
    companion object {
        private val byConnection = HashMap<Realtime, RealtimeCalls>()

        /** The calls of a connection: one for each. */
        @Synchronized
        fun of(context: Context, rt: Realtime): RealtimeCalls = byConnection.getOrPut(rt) { RealtimeCalls(context.applicationContext, rt) }

        /** Every connection's lines again, with the phone's new FCM token (SystemCalls). */
        internal fun refreshLines() = synchronized(this) { byConnection.values.toList() }.forEach { it.refreshLine() }

        /** A ring on a line of this process, by its id. On the main thread. */
        internal fun ringing(id: String): IncomingCall? = synchronized(this) { byConnection.values.toList() }.firstNotNullOfOrNull { it.ringing[id] }

        /**
         * A user id as the relay takes it: a number for an id of digits, written as the number is (`42`, not `042`), and
         * any other id as the text it is, as the realtime server reads them (a UUID, `auth0|5f7c…`).
         */
        internal fun userValue(user: String): Any {
            val number = user.toLongOrNull() ?: return user
            return if (number.toString() == user && number != 0L && kotlin.math.abs(number) < (1L shl 53)) number else user
        }
    }

    internal val context: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val dispatch = HashMap<String, (JsonElement) -> Unit>()
    private val socketListeners = LinkedHashMap<Any, () -> Unit>()
    private val incomingHandlers = CallHandlers<IncomingCall>()
    private val errorHandlers = CallHandlers<String>()
    private val callHandlers = CallHandlers<Call>()
    private val groupHandlers = CallHandlers<GroupCall>()
    private val ringing = LinkedHashMap<String, IncomingCall>()
    private val active = HashMap<String, Call>()

    // The rings into group calls this app heard of, in the order heard, and the group calls they ring into, by the group's
    // id. On the main thread.
    private val invites = LinkedHashMap<String, CallInvite>()
    private val groups = HashMap<String, GroupCall>()

    /** When this app was last taken out of a group call or saw it end, by its name ([afterOut]). On the main thread. */
    private val outAt = HashMap<String, Long>()

    // The line and its renewal: on the module queue.
    private var line: Pair<String, Double>? = null
    private var lineRenewal: java.util.concurrent.ScheduledFuture<*>? = null
    private var ringOn = true

    init {
        rt.onSocketEvent("call") { data -> main.post { relayed(data) } }
        rt.onSocketEvent("line") { data -> onLine(data) }
        // On every socket before it takes over, the line is held there too.
        rt.addHold { socket, done -> holdLine(socket, done) }
        // A connect after a drop, and a move: each call joins on the socket the app has now.
        val changed = { main.post { socketListeners.values.toList().forEach { it() } } }
        rt.onState { state, _ -> if (state == ConnectionState.Connected) changed() }
        rt.on("moved") { _, _ -> changed() }
        rt.moduleQueue.execute { rt.currentSocket?.let { holdLine(it) {} } }
    }

    /** A call ringing for this app's user, on the main thread. */
    fun onIncoming(handler: (IncomingCall) -> Unit): CallListener {
        val listener = CallListener()
        main.post { incomingHandlers.add(handler, listener) }
        return listener
    }

    /** What went wrong with the line: its code. */
    fun onError(handler: (String) -> Unit): CallListener {
        val listener = CallListener()
        main.post { errorHandlers.add(handler, listener) }
        return listener
    }

    /**
     * A call this app started or answered, on the main thread, the call screen's too: so an app can keep to rules of its
     * own, such as how long a call may last.
     */
    fun onCall(handler: (Call) -> Unit): CallListener {
        val listener = CallListener()
        main.post { callHandlers.add(handler, listener) }
        return listener
    }

    /**
     * A group call this app joined by `group`, a ring into one answered, or a call of its that became one, on the main
     * thread, the call screen's too. Not the room a call moved to when no path carried it: that is still the call.
     */
    fun onGroup(handler: (GroupCall) -> Unit): CallListener {
        val listener = CallListener()
        main.post { groupHandlers.add(handler, listener) }
        return listener
    }

    /** Takes a handler off. */
    fun off(listener: CallListener) {
        main.post {
            incomingHandlers.remove(listener)
            errorHandlers.remove(listener)
            callHandlers.remove(listener)
            groupHandlers.remove(listener)
        }
    }

    /** The calls ringing for this app's user now. On the main thread. */
    val incoming: List<IncomingCall> get() = ringing.values.toList()

    /**
     * Calls `user` about `name`, a call the app's server allows: rings their every open app and page. `{pair}` in the name
     * is the two ids in order, so either side names the same call: `start("support:{pair}", to = "34")`. Throws its code.
     */
    suspend fun start(name: String, to: String, type: CallType = CallType.VIDEO): Call = withContext(Dispatchers.Main) {
        val named = CallName.named(name, rt.user, to)
        val id = CallSignaling.randomId(20)
        val request = JSONObject().put("call", named).put("id", id).put("role", "caller").put("to", userValue(to)).put("type", type.wire)
        val grant = authorize(request)
        place(Call(this@RealtimeCalls, id, named, type, Call.Role.CALLER, to, JsonNull, request, grant))
    }

    /**
     * Starts the call in a token from the app's own server (its `callToken()`), which decided that this user may make it,
     * and rings the person it names. The calls route isn't asked. A token rings once, so each call takes a new one. Throws
     * its code: `bad_token`, or `forbidden` for a token made for another user.
     */
    suspend fun startCall(token: String): Call = withContext(Dispatchers.Main) {
        val made = CallTokens.toStart(token, rt.user)
        // Its proof of being in the call gets it a new token through the calls route, should the call outlast this one.
        place(Call(this@RealtimeCalls, made.id, made.call, made.type, Call.Role.CALLER, made.to, JsonNull, CallTokens.renewal(made), made.grant))
    }

    /**
     * A call between two people whose signalling a transport carries, the app's own ([CallTransport]) or Better Messages'
     * ([P2PCallTransport]), on this SDK's call engine: direct between the two, through TURN when it must, or in a room on
     * the mesh when neither works, and on the call screen as any other call ([onCall] hears it). Whoever made the call on
     * the relay, a site's server for Better Messages, decides who is in it, and this joins it: as its `CALLER` it rings
     * until the other side joins, as its `CALLEE` it connects.
     *
     * @param over what carries the call's requests to the relay and its events back.
     * @param id the call on the relay, the same on both sides: a Better Messages call's is its message's id.
     * @param role this side's, `CALLER` or `CALLEE`.
     * @param peer the other side's user id, and [peerInfo] what the app knows of them, for the call screen.
     * @param signalKey the key both sides seal their signals with, which the relay never sees: a Better Messages call's
     * comes from its site.
     * @param mediaKey the key the call's room encrypts its media with, should the call move to one; null for none.
     * @param roomServer where that room is on the mesh, for a relay that hands it over by its token alone: a Better Messages
     * call's is its site's (the `server` the relay answers the site's `/p2p/call/create` with). Null for a relay that
     * names it, as the SDK's own does.
     * @throws CallException the relay's refusal of the join, by its code: `no_call`, `not_participant`, `call_ended`,
     * `answered_elsewhere`, or `signaling_unavailable` when the transport answered nothing.
     */
    suspend fun call(
        over: CallTransport,
        id: String,
        role: Call.Role,
        peer: String,
        peerInfo: JsonElement = JsonNull,
        type: CallType = CallType.VIDEO,
        signalKey: String,
        mediaKey: String? = null,
        roomServer: String? = null,
    ): Call = withContext(Dispatchers.Main) {
        val grant = JSONObject().put("key", signalKey)
        if (mediaKey != null) grant.put("media_key", mediaKey)
        val call = Call(this@RealtimeCalls, id, id, type, role, peer, peerInfo, JSONObject(), grant, over, roomServer)
        active[id] = call
        // Telecom's, with its foreground service, for a call this app places, before it starts (SystemCalls, when started).
        if (role == Call.Role.CALLER) SystemCalls.outgoing(call)
        call.begin()
        callHandlers.fire(call)
        call
    }

    /** A call this app starts, joined, so it rings. On the main thread. */
    private suspend fun place(call: Call): Call {
        active[call.id] = call
        // Telecom's, with its foreground service, before it starts (SystemCalls, when started).
        SystemCalls.outgoing(call)
        call.begin()
        callHandlers.fire(call)
        return call
    }

    /**
     * A group call `name` on the mesh, encrypted end to end when its rule says so. Nothing is sent into it until
     * `microphone` or `camera` say so. Throws its code.
     */
    suspend fun group(name: String, type: CallType = CallType.VIDEO): GroupCall = withContext(Dispatchers.Main) {
        afterOut(name)
        val grant = authorize(JSONObject().put("room", name).put("type", type.wire))
        intoRoom(name, grant.optString("token"), grant.optString("media_key").takeIf { it.isNotEmpty() }, type, null, CallsRoute.mayRecord(grant))
    }

    /**
     * Joins the group call in a token from the app's own server (its `groupCallToken()`), which decided that this user may
     * join and what they may do in it. The calls route isn't asked. Nothing is sent into it until `microphone` or `camera`
     * say so. Throws its code: `bad_token`, `forbidden` for a token made for another user, or `expired`, when someone taken
     * out of the call needs a new one to come back.
     */
    suspend fun joinGroupCall(token: String): GroupCall = withContext(Dispatchers.Main) {
        val made = CallTokens.toJoin(token, rt.user)
        intoRoom(made.room, made.token, made.mediaKey, made.type, made.proofs)
    }

    /** A place in the group call's room, with its token, and the room connected. On the main thread. */
    private suspend fun intoRoom(name: String, token: String, mediaKey: String?, type: CallType, proofs: GroupProofs?, mayRecord: Boolean = false): GroupCall {
        val place = ask("room", JSONObject().put("tab", rt.tab).put("token", token))
        if (place?.optBoolean("ok") != true) throw CallException(place?.optString("error", "server") ?: "server")
        val room = GroupCall(context, this, name, null, mediaKey, microphone = false, camera = false, moderated = true, proofs = proofs, mayRecord = mayRecord)
        room.onEnded { reason -> if (reason == "removed" || reason == "closed") outAt[name] = System.currentTimeMillis() }
        SystemCalls.outgoing(room, type)
        connect(room, place)
        groupHandlers.fire(room)
        return room
    }

    /**
     * Who this app's user may add to calls named [name], as the app's server lists them (its `calls()` rule's `people`):
     * none when it lists nobody, or when its route gave no answer.
     */
    suspend fun people(name: String): List<CallPerson> =
        runCatching { CallsRoute.people(route(CallsRoute.people(name))) }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    /**
     * Whether this app's user may call each person about each call, as the rules of the app's server answer for a caller,
     * in the order asked: what a list of people asks before it offers call buttons. Null where the server gave no answer
     * (a route of the app's own that doesn't know the question, or a failed request): placing the call still asks the
     * rule.
     */
    suspend fun may(asks: List<CallAsk>): List<Boolean?> = asks.chunked(CallsRoute.MAY_AT_ONCE).flatMap { part ->
        val answer = runCatching { route(CallsRoute.may(part)) }.getOrElse { if (it is CancellationException) throw it else null }
        CallsRoute.mayAnswer(answer, part.size)
    }

    /**
     * Stops holding this app's line: its user's calls no longer ring here, nor on this phone with the app closed (as an
     * app signs its user out).
     */
    fun stopRinging() {
        val push = SystemCalls.unlinePush()
        rt.moduleQueue.execute {
            ringOn = false
            lineRenewal?.cancel(false)
            lineRenewal = null
            val payload = HashMap<String, JsonElement>(mapOf("tab" to JsonPrimitive(rt.tab)))
            push?.let { payload["push"] = it }
            rt.currentSocket?.emit("unline", JsonObject(payload))
        }
    }

    /** Holds the line again on the socket the connection has now: the phone's FCM token came or changed. */
    internal fun refreshLine() = rt.moduleQueue.execute {
        val socket = rt.currentSocket
        if (ringOn && socket != null) holdLine(socket) {}
    }

    // Internal.

    /**
     * A ring into a group call, answered with the proof it brought: the app's server's token for this ring, then a place
     * in its room.
     */
    internal suspend fun answerGroup(incoming: IncomingCall, group: String): GroupCall = withContext(Dispatchers.Main) {
        val request = JSONObject().put("call", incoming.call).put("id", incoming.id).put("role", "callee").put("from", userValue(incoming.from))
            .put("type", incoming.type.wire).put("group", group)
        incoming.pair?.let { request.put("pair", it) }
        incoming.ring?.let { request.put("ring", it) }
        val grant = authorize(request)
        ringing.remove(incoming.id)
        val link = GroupOf(group, incoming.call, incoming.type, grant.opt("pair") ?: incoming.pair, grant.optString("member").takeIf { it.isNotEmpty() })
        joinGroup(link, grant.optString("token"), grant.optString("media_key").takeIf { it.isNotEmpty() }, true, incoming.type == CallType.VIDEO)
    }

    /**
     * Before joining a group call this app was just taken out of, or that just ended: the realtime server refuses a token
     * signed in the second of that, as from before it, so the app asks its server once the next second has begun, as the
     * browser's does (calls.ts:425-431). Someone removed who joined again at once was refused `forbidden`.
     */
    private suspend fun afterOut(name: String) {
        val out = outAt.remove(name) ?: return
        val wait = CallsRoute.outWaitMs(out, System.currentTimeMillis())
        if (wait > 0) delay(wait)
    }

    /** A place in a group call's room, with the token of the call it was made from or of the ring answered. */
    internal suspend fun joinGroup(link: GroupOf, token: String, mediaKey: String?, microphone: Boolean, camera: Boolean): GroupCall = withContext(Dispatchers.Main) {
        val place = ask("group", JSONObject().put("tab", rt.tab).put("token", token))
        if (place?.optBoolean("ok") != true) throw CallException(place?.optString("error", "server") ?: "server")
        val room = GroupCall(context, this@RealtimeCalls, link.name, link, mediaKey, microphone, camera)
        groups[link.id] = room
        room.onEnded {
            if (groups[link.id] === room) {
                groups.remove(link.id)
                invites.values.removeAll { it.group == link.id }
            }
        }
        connect(room, place)
        groupHandlers.fire(room)
        room
    }

    /** The rings into the group call [group] this app heard of, in the order they were heard. On the main thread. */
    internal fun invites(group: String): List<CallInvite> = invites.values.filter { it.group == group }

    /**
     * Rings `user` into a group call, or into the call that becomes one: the app's server's token for the ring first,
     * given for the proof that this user is in the call, and the proof the person was rung, which they get with it.
     */
    internal suspend fun invite(link: GroupOf, user: String): CallInvite = withContext(Dispatchers.Main) {
        val id = CallSignaling.randomId(20)
        val request = JSONObject().put("call", link.name).put("id", id).put("role", "caller").put("to", userValue(user)).put("type", link.type.wire).put("group", link.id)
        link.pair?.let { request.put("pair", it) }
        link.member?.let { request.put("member", it) }
        val grant = authorize(request)
        ringInto(link, id, user, grant.optString("token"), grant.optString("ring").takeIf { it.isNotEmpty() })
    }

    /**
     * Rings someone into a group call, or the call that becomes one: with a token from the app's own server (its
     * `callToken(user, to, { into: call.ref })`), or by their user id, which a rule of the app's decides. A string that
     * opens as a call's token is a token. Throws `forbidden` for one that doesn't ring into this call.
     */
    internal suspend fun addTo(link: GroupOf, who: String): CallInvite = withContext(Dispatchers.Main) {
        val made = CallTokens.toRing(who, link)
        if (made != null) ringInto(link, made.id, made.to, made.token, made.ring) else invite(link, who)
    }

    /** A ring asked of the realtime server, with its token and the proof the person was rung. On the main thread. */
    private suspend fun ringInto(link: GroupOf, id: String, user: String, token: String, ring: String?): CallInvite {
        val payload = JSONObject().put("tab", rt.tab).put("token", token)
        ring?.let { payload.put("ring", it) }
        val reply = ask("invite", payload)
        if (reply?.optBoolean("ok") != true) throw CallException(reply?.optString("error", "server") ?: "server")

        // Its `ringing` may have come on the line first.
        val invite = invites[id] ?: CallInvite(id, link.id, to = user, state = reply.optString("state").takeIf { it in CallInvite.STATES } ?: "ringing").also { remember(it) }
        invite.own {
            val over = ask("uninvite", JSONObject().put("tab", rt.tab).put("token", token))
            if (over?.optBoolean("ok") != true) throw CallException(over?.optString("error", "server") ?: "server")
            val state = over.optString("state")
            if (state in CallInvite.STATES) withContext(Dispatchers.Main) { invite.update(state) }
        }
        return invite
    }

    /** A ring kept, and told to the group call it rings into. On the main thread. */
    private fun remember(invite: CallInvite) {
        invites[invite.id] = invite
        groups[invite.group]?.invited(invite)
    }

    /** Someone rung into a group call this app's user is in, or how that ring ended, as the relay tells it. On the main thread. */
    internal fun heardInvite(message: JsonObject) {
        fun text(key: String) = (message[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = text("id")?.takeIf { it.isNotEmpty() } ?: return
        val state = text("state")?.takeIf { it in CallInvite.STATES } ?: return
        val group = text("group") ?: return
        val to = CallsRoute.user(message["to"]) ?: return
        val known = invites[id]
        if (known != null) known.update(state) else remember(CallInvite(id, group, CallsRoute.user(message["from"]), to, state))
    }

    /** A room joined in the background: one that fails is left, and said so. */
    private fun connect(room: GroupCall, place: JSONObject) {
        // Before anyone draws it, which comes before the room has connected.
        room.namedBy(place.optString("token"))
        CoroutineScope(Dispatchers.Main).launch {
            try {
                room.connect(place.optString("server"), place.optString("token"), place.optJSONArray("ice_servers"))
            } catch (e: Exception) {
                room.finish("room_failed")
                fail("room_failed")
            }
        }
    }

    /** The room a call moves to when neither the direct path nor TURN carries it, encrypted with the call's key. */
    internal fun handoffRoom(name: String, ticket: CallRoomTicket, mediaKey: String?, microphone: Boolean, camera: Boolean): GroupCall {
        val room = GroupCall(context, this, name, null, mediaKey, microphone, camera)
        connect(room, JSONObject().put("server", ticket.server ?: "").put("token", ticket.token))
        return room
    }

    internal suspend fun answer(incoming: IncomingCall): Call = withContext(Dispatchers.Main) {
        // A call from a token of the app's server brought the proof that this user was rung, which is all the calls route needs.
        val request = CallTokens.answering(incoming.call, incoming.id, incoming.from, incoming.type, incoming.ring)
        val grant = authorize(request)
        val call = Call(this@RealtimeCalls, incoming.id, incoming.call, incoming.type, Call.Role.CALLEE, incoming.from, incoming.info, request, grant)
        ringing.remove(incoming.id)
        active[incoming.id] = call
        call.begin()
        callHandlers.fire(call)
        call
    }

    internal suspend fun decline(incoming: IncomingCall, reason: String) {
        withContext(Dispatchers.Main) { ringing.remove(incoming.id) }
        val reply = ask("decline", JSONObject().put("tab", rt.tab).put("id", incoming.id).put("reason", reason))
        if (reply?.optBoolean("ok") != true) throw CallException(reply?.optString("error", "server") ?: "server")
    }

    internal fun forget(call: Call) {
        if (active[call.id] === call) active.remove(call.id)
    }

    /** The app's server's answer to a request (its calls route), or its refusal by code. */
    internal suspend fun authorize(request: JSONObject): JSONObject {
        val grant = JSONObject(route(Json.parseToJsonElement(request.toString())).toString())
        if (grant.optString("token").isEmpty()) throw CallException("auth_failed")
        return grant
    }

    /** The calls route's answer, a token or not, or its refusal by code ([CallsRoute.answer]). */
    private suspend fun route(request: JsonElement): JsonObject {
        val asked = routeAsked
        val (status, answer) = if (asked != null) asked(request) else suspendCancellableCoroutine { continuation ->
            rt.postToRoute("calls", request) { status, answer -> continuation.resume(status to answer) }
        }
        return CallsRoute.answer(status, answer)
    }

    /** The calls route in place of the app's server, for the tests: the request's body in, its status and answer out. */
    internal var routeAsked: ((JsonElement) -> Pair<Int, JsonElement?>)? = null

    /** Whether a call's relay is there, in place of the connection's state, for the tests. */
    internal var connectedAsked: Boolean? = null

    /**
     * A host's act in the group call [name], as the app's calls route answers it (`{ moderate, act, user, host? }`, the
     * server package's `moderate`, `user` null for everyone out of the spotlight): whether it changed anything.
     */
    internal suspend fun moderate(name: String, act: ModerationAct, user: String?, host: JsonObject? = null): Boolean =
        CallsRoute.done(route(CallsRoute.moderation(name, act, user, host)))

    /**
     * The group call [name] recorded or stopped, as the app's calls route answers it (`{ call, record, recording?,
     * recorder? }`, the server package's `record`, @wordplus/realtime-client 1.0.65): the recording, its id and state.
     */
    internal suspend fun record(name: String, action: String, recording: String?, recorder: JsonObject? = null): GroupRecording =
        CallsRoute.recording(route(CallsRoute.record(name, action, recording, recorder)))

    /** A request on the socket the connection holds now, or on `via`; null when it didn't answer. */
    private suspend fun ask(event: String, payload: JSONObject, via: ModuleSocket? = null): JSONObject? = suspendCancellableCoroutine { continuation ->
        rt.moduleQueue.execute {
            val socket = via ?: rt.currentSocket
            if (socket == null) return@execute continuation.resume(null)
            socket.emit(event, Json.parseToJsonElement(payload.toString()), 8_000) { reply ->
                continuation.resume(reply?.let { runCatching { JSONObject(it.toString()) }.getOrNull() })
            }
        }
    }

    internal fun transport(token: () -> String): CallTransport = SdkCallTransport(this, token)

    internal fun listen(key: String, handler: (JsonElement) -> Unit): () -> Unit {
        dispatch[key] = handler
        return { if (dispatch[key] === handler) dispatch.remove(key) }
    }

    internal fun onSocketChange(listener: () -> Unit): () -> Unit {
        val key = Any()
        socketListeners[key] = listener
        return { socketListeners.remove(key) }
    }

    /** The relay's events for a call, to the session they name: every app on the socket hears all of them. */
    private fun relayed(message: JsonElement) {
        val (call, session) = relayKey(message) ?: return
        dispatch["$call:$session"]?.invoke(message)
    }

    // The line, on the module queue.

    /** Holds the line on a socket, asking the app's server for it when it has none or it runs out within a minute. */
    private fun holdLine(socket: ModuleSocket, done: () -> Unit) {
        // The line is `/channels`' own: on another product's namespace, its calls ring that product's way.
        if (!ringOn || rt.options.namespace != RealtimeOptions.CHANNELS) return done()
        val held = line
        if (held != null && held.second - System.currentTimeMillis() / 1000.0 > 60) return sendLine(held.first, socket, done)
        Thread {
            val result = runCatching { kotlinx.coroutines.runBlocking { authorize(JSONObject().put("line", true)) } }
            rt.moduleQueue.execute {
                result.onSuccess { grant ->
                    val token = grant.optString("token")
                    line = token to grant.optDouble("exp", System.currentTimeMillis() / 1000.0 + 3600)
                    sendLine(token, socket, done)
                }.onFailure { error ->
                    // An app with no signed-in user has no line: nothing rings here.
                    line = null
                    val code = (error as? CallException)?.code ?: "auth_failed"
                    if (code != "no_user") fail(code)
                    done()
                }
            }
        }.start()
    }

    private fun sendLine(token: String, socket: ModuleSocket, done: () -> Unit) {
        val payload = HashMap<String, JsonElement>(mapOf("tab" to JsonPrimitive(rt.tab), "token" to JsonPrimitive(token)))
        // This phone, so its user's calls ring it with the app closed too (SystemCalls).
        SystemCalls.linePush()?.let { payload["push"] = it }
        val askedAt = System.currentTimeMillis()
        socket.emit("line", JsonObject(payload), 8_000) { reply ->
            rt.moduleQueue.execute {
                val answer = reply?.let { runCatching { JSONObject(it.toString()) }.getOrNull() }
                if (answer == null || !answer.optBoolean("ok")) {
                    if (answer?.optString("error") == "expired") line = null
                    if (answer != null) fail(answer.optString("error", "server"))
                    return@execute done()
                }
                val list = (reply as? JsonObject)?.get("ringing") as? kotlinx.serialization.json.JsonArray
                val ids = HashSet<String>()
                list?.forEach { ring ->
                    val message = JSONObject(ring.toString())
                    message.optString("id").takeIf { it.isNotEmpty() }?.let { ids.add(it) }
                    incomingRing(message, (ring as? JsonObject)?.get("info") ?: JsonNull)
                }
                SystemCalls.lineHeld(ids, askedAt, rt.keyring)
                scheduleLineRenewal()
                done()
            }
        }
    }

    private fun scheduleLineRenewal() {
        lineRenewal?.cancel(false)
        val held = line ?: return
        val inSeconds = maxOf(30.0, held.second - 600 - System.currentTimeMillis() / 1000.0)
        lineRenewal = rt.moduleQueue.schedule({
            line = null
            rt.currentSocket?.let { holdLine(it) {} }
        }, (inSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    internal fun fail(code: String) {
        main.post { errorHandlers.fire(code) }
    }

    internal fun onLine(data: JsonElement) {
        val message = runCatching { JSONObject(data.toString()) }.getOrNull() ?: return
        val id = message.optString("id").takeIf { it.isNotEmpty() } ?: return
        when (message.optString("op")) {
            "incoming" -> incomingRing(message, (data as? JsonObject)?.get("info") ?: JsonNull)
            "ring_over" -> {
                val reason = message.optString("reason", "ended")
                main.post {
                    SystemCalls.ringOver(id, reason)
                    ringing.remove(id)?.over(reason)
                }
            }
            "invite" -> (data as? JsonObject)?.let { told -> main.post { heardInvite(told) } }
        }
    }

    /** A call ringing: shown once the caller's info opens (the app's server seals it with the day's key). */
    private fun incomingRing(message: JSONObject, info: JsonElement) {
        rt.ensureKeys(info) {
            val opened = rt.keyring.openJson(info) ?: JsonNull
            main.post {
                val id = message.optString("id")
                if (id.isEmpty() || ringing.containsKey(id) || active.containsKey(id)) return@post
                val incoming = IncomingCall(this, message, opened)
                ringing[id] = incoming
                // The ring a push brought, now on the line; or one during a call, answered busy.
                if (SystemCalls.ringArrived(incoming)) return@post
                incomingHandlers.fire(incoming)
            }
        }
    }
}

/** Calls between the app's users, on this connection. */
fun Realtime.calls(context: Context): RealtimeCalls = RealtimeCalls.of(context, this)

/**
 * Calls `to` about `name`, which your server's `calls()` rule owns: rings their every open app and page. `{pair}` in the
 * name is the two ids in order, so either side names the same call: `rt.call(context, "support:{pair}", to = "34")`.
 */
suspend fun Realtime.call(context: Context, name: String, to: String, type: CallType = CallType.VIDEO): Call = calls(context).start(name, to, type)

/** Joins the group call `name`, which your server's `groupCalls()` rule owns. */
suspend fun Realtime.groupCall(context: Context, name: String, type: CallType = CallType.VIDEO): GroupCall = calls(context).group(name, type)

/**
 * Joins the live stream `name`, as your server's `streams()` rule makes this user its host, a guest or a viewer. `video = false`
 * for an app that shows only the chat or the details.
 */
suspend fun Realtime.stream(context: Context, name: String, video: Boolean = true): LiveStream = calls(context).stream(name, video)

/**
 * Starts the call in a token from your own server (`realtime.callToken(user, to)`), which decided that this user may make
 * it, and rings the person it names. A token rings once, so each call takes a new one. Throws its code: `bad_token`, or
 * `forbidden` for a token made for another user.
 */
suspend fun Realtime.startCall(context: Context, token: String): Call = calls(context).startCall(token)

/**
 * Joins the group call in a token from your own server (`realtime.groupCallToken(user, name)`), which decided that this
 * user may join and what they may do in it. Throws its code: `bad_token`, `forbidden` for a token made for another user, or
 * `expired`, when someone taken out of the call needs a new one to come back.
 */
suspend fun Realtime.joinGroupCall(context: Context, token: String): GroupCall = calls(context).joinGroupCall(token)

/**
 * Joins the live stream in a token from your own server (`realtime.streamToken(user, name)`), which decided who this user
 * is in it. A token lasts ten minutes, so `getToken` asks your route for one, and is asked again as each runs out. Your route
 * may then say no. Call it on the main thread.
 */
suspend fun Realtime.joinStream(context: Context, getToken: suspend () -> String): LiveStream = calls(context).joinStream(getToken)

/** The same, with `video = false` for an app that shows only the chat or the details. */
suspend fun Realtime.joinStream(context: Context, video: Boolean, getToken: suspend () -> String): LiveStream = calls(context).joinStream(video, getToken)

/**
 * Joins the live stream in one token from your own server, for a stay shorter than the token: `expired` is what it comes to
 * when it would need another. Call it on the main thread.
 */
suspend fun Realtime.joinStream(context: Context, token: String, video: Boolean = true): LiveStream = calls(context).joinStream(token, video)

/** The relay on the realtime connection: the `call` request, with the call's token, since the socket is the app's. */
private class SdkCallTransport(private val calls: RealtimeCalls, private val token: () -> String) : CallTransport {
    override val connected: Boolean get() = calls.connectedAsked ?: (calls.rt.state == ConnectionState.Connected)

    override fun send(payload: JsonElement, ack: ((JsonElement?) -> Unit)?) {
        val json = JsonObject((payload as? JsonObject ?: JsonObject(emptyMap())) + mapOf("tab" to JsonPrimitive(calls.rt.tab), "token" to JsonPrimitive(token())))
        // The engine takes the answer to the main thread.
        calls.rt.moduleQueue.execute {
            val socket = calls.rt.currentSocket
            if (socket == null) {
                ack?.invoke(null)
                return@execute
            }
            if (ack == null) return@execute socket.emit("call", json)
            socket.emit("call", json, 10_000, ack)
        }
    }

    override fun listen(callId: String, session: String, handler: (JsonElement) -> Unit): () -> Unit = calls.listen("$callId:$session", handler)

    override fun onReconnect(listener: () -> Unit): () -> Unit = calls.onSocketChange(listener)
}

/**
 * A call ringing for this app's user. `onOver` (the reason) when it stops ringing: answered or declined, here or
 * elsewhere, the caller gave up …
 */
class IncomingCall internal constructor(private val owner: RealtimeCalls, message: JSONObject, opened: JsonElement) {
    enum class State { RINGING, ANSWERED, DECLINED, OVER }

    private val told = CallTokens.ringInfo(opened)

    /** What the app's server said about the caller, opened. */
    val info: JsonElement = told.info

    val id: String = message.optString("id")

    /** The app's name for the call, such as `support:34:42`. */
    val call: String = message.optString("call")

    /** Who calls: their user id. What the app's server said about them is `info`, opened. */
    val from: String = message.opt("from")?.toString() ?: ""
    val type: CallType = if (message.optString("type") == "audio") CallType.AUDIO else CallType.VIDEO

    /** The group call it rings into, for a ring into one; null for a call between two people. */
    val group: String? = message.optString("group").takeIf { it.isNotEmpty() }
    var state = State.RINGING
        private set
    var reason: String? = null
        private set

    /**
     * What a ring brought for the app's server: the call's pair, and the proof this user was rung, beside a ring into a
     * group call or, for a call from a token, inside the info. An answer needs no token of its own with it.
     */
    internal val pair: Any? = message.optJSONArray("pair")
    internal val ring: String? = told.ring ?: message.optString("ring").takeIf { it.isNotEmpty() }
    private val overHandlers = CallHandlers<String>()

    /** When it stops ringing, with the reason. On the main thread. */
    fun onOver(handler: (String) -> Unit): CallListener = overHandlers.add(handler)

    /** Takes a handler off. On the main thread. */
    fun off(listener: CallListener) = overHandlers.remove(listener)

    /** What answering gives: the call, or for a ring into a group call (`group`) the GroupCall. */
    sealed interface Answered {
        data class ToCall(val call: Call) : Answered
        data class ToGroup(val group: GroupCall) : Answered
    }

    /**
     * Answers: the call, connecting, or the group call it rings into. Throws its code, such as `forbidden` when the
     * app's server said no.
     */
    suspend fun accept(): Answered = withContext(Dispatchers.Main) {
        if (state != State.RINGING) throw CallException(reason ?: "not_ringing")
        // The same answer as the notification's when the system rings it too (SystemCalls), so it is answered once.
        SystemCalls.answer(this@IncomingCall) ?: acceptNow()
    }

    /** The answer itself, from the app or from the system's side. On the main thread. */
    internal suspend fun acceptNow(): Answered {
        if (state != State.RINGING) throw CallException(reason ?: "not_ringing")
        state = State.ANSWERED
        // It rings no more, wherever it shows.
        overHandlers.fire("answered")
        val into = group
        val answered = if (into != null) Answered.ToGroup(owner.answerGroup(this, into)) else Answered.ToCall(owner.answer(this))
        SystemCalls.answered(id, answered, this)
        return answered
    }

    /** Turns it down, or says this user is busy: it ends for the caller, and stops ringing everywhere else. */
    suspend fun decline(busy: Boolean = false) {
        if (state != State.RINGING) return
        state = State.DECLINED
        val reason = if (busy) "busy" else "declined"
        overHandlers.fire(reason)
        SystemCalls.declined(id)
        owner.decline(this, reason)
    }

    internal fun over(reason: String) {
        if (state != State.RINGING) return
        state = State.OVER
        this.reason = reason
        overHandlers.fire(reason)
    }
}

/**
 * A call between two people: straight between them, through TURN when no direct path works. The app's microphone and,
 * for a video call, camera go on it as it is joined; `microphone` and `camera` switch them. `onState`, `onRemote` (what
 * the other side sends changed), `onEnded` (the reason). Everything on the main thread.
 */
class Call internal constructor(
    private val owner: RealtimeCalls,
    val id: String,
    val name: String,
    val type: CallType,
    val role: Role,
    /** The other side: their user id, and what the app's server said about them (a callee has the caller's). */
    val peer: String,
    val peerInfo: JsonElement,
    private val request: JSONObject,
    grant: JSONObject,
    /**
     * A transport of the app's own ([RealtimeCalls.call]): no token of the SDK's to renew, and no group call of the SDK's
     * to add people in. Null for the SDK's own.
     */
    transport: CallTransport? = null,
    /** Where the call's room is on the mesh, for a relay that hands one over by its token alone. */
    private val roomServer: String? = null,
) {
    enum class Role { CALLER, CALLEE }

    private val carried = transport != null

    var state: CallState = if (role == Role.CALLER) CallState.RINGING else CallState.CONNECTING
        private set
    var path: CallPath? = null
        private set
    var endReason: String? = null
        private set

    /** Whether the other side's media is arriving: the connection is up and carrying it. */
    var bytesReceived = 0L
        private set

    private val context = owner.context
    private val main = Handler(Looper.getMainLooper())
    private val media = CallMedia.get(context)
    private var token: String = grant.optString("token")

    /**
     * The key the call's rooms encrypt their media with, and the call's two people with this user's proof of being in
     * it, from the app's server: what a room, and ringing someone in, need.
     */
    private val mediaKey: String? = grant.optString("media_key").takeIf { it.isNotEmpty() }
    private val pair: Any? = grant.opt("pair")
    private val member: String? = grant.optString("member").takeIf { it.isNotEmpty() }

    /** The room carrying the call when neither path could. */
    private var room: GroupCall? = null
    private var movingToGroup = false
    internal val session: CallSession
    private val stateHandlers = CallHandlers<CallState>()
    private val pathHandlers = CallHandlers<CallPath>()
    private val waitingHandlers = CallHandlers<CallWait>()
    private val remoteHandlers = CallHandlers<Unit>()
    private val endHandlers = CallHandlers<String>()
    private val groupHandlers = CallHandlers<GroupCall>()
    private val screenHandlers = CallHandlers<Boolean>()
    private var screenCapture: CallScreenCapture? = null
    private var renewal: Runnable? = null
    private var connectedOnce = false
    private var microphoneOn = true
    private var cameraOn = type == CallType.VIDEO
    private var preferFront = true

    init {
        session = CallSession(context, transport ?: owner.transport { token }, id, type == CallType.VIDEO, grant.optString("key"))
        if (!carried) scheduleRenewal(grant.optDouble("exp", System.currentTimeMillis() / 1000.0 + 3600))
        session.onPeerJoined = { if (state == CallState.RINGING) set(CallState.CONNECTING) }
        session.onConnection = { connection ->
            when (connection) {
                CallConnection.CONNECTED -> {
                    connectedOnce = true
                    set(CallState.CONNECTED)
                }
                CallConnection.RECONNECTING -> set(CallState.RECONNECTING)
                CallConnection.CONNECTING -> if (state != CallState.RINGING) set(if (connectedOnce) CallState.RECONNECTING else CallState.CONNECTING)
                else -> {}
            }
        }
        session.onRemote = { remoteHandlers.fire() }
        session.onStats = { stats ->
            bytesReceived = stats.bytesReceived
            if (stats.candidateLocal != null || stats.candidateRemote != null) {
                changePath(if (stats.candidateLocal == "relay" || stats.candidateRemote == "relay") CallPath.RELAY else CallPath.DIRECT)
            }
        }
        session.onReacquire = { reacquire(it) }
        session.onFallback = { toRoom(it) }
        // Someone was added: the relay ends the call between two people as `group`, and it goes on in the group's room.
        session.onEnded = { if (it == "group") toGroup() else finish(it) }
    }

    /** Someone was added and the call goes on as a group call there: handed to the app before the call ends as `group`. */
    fun onGroup(handler: (GroupCall) -> Unit): CallListener = groupHandlers.add(handler)

    /**
     * Rings someone into the call, which becomes a group call: both sides go on in a room of its own on the mesh, and they
     * arrive there once they answer. Takes a token from your own server, `callToken(user, to, { into: call.ref })`, which
     * decided they may be added, or a user id, which a rule of your server decides. A string that opens as a call's token is
     * a token. Throws `not_connected` before the call is up, `forbidden` for a token of another call, or a refusal's code;
     * `not_supported` for a call on a transport of the app's own, whose product adds people its own way.
     */
    suspend fun add(user: String): CallInvite {
        if (carried) throw CallException("not_supported")
        if (state != CallState.CONNECTED && state != CallState.RECONNECTING) throw CallException("not_connected")
        return owner.addTo(groupLink(), user)
    }

    /** What the app sends your route to ring someone into this call: `realtime.callToken(user, to, { into: ref })`. */
    val ref: String get() = CallTokens.ref(groupLink())

    private fun groupLink() = GroupOf(id, name, type, pair, member)

    /**
     * Someone was added: the call goes on in the group call's room, encrypted with the call's key, with what this side
     * sends, and the app gets it before the call ends as `group`.
     */
    private fun toGroup() {
        if (movingToGroup || state == CallState.ENDED) return
        // A group call of another product's is that product's to join: the call ends as `group`, and the app follows it.
        if (carried) return finish("group")
        movingToGroup = true
        CoroutineScope(Dispatchers.Main).launch {
            try {
                stopMedia()
                val group = owner.joinGroup(groupLink(), token, mediaKey, microphoneOn, cameraOn)
                changePath(CallPath.ROOM)
                SystemCalls.moved(this@Call, group)
                groupHandlers.fire(group)
                finish("group")
            } catch (e: CallException) {
                finish("group_failed")
            }
        }
    }

    /** Neither the direct path nor TURN carried the call: it goes on in a room on the mesh, encrypted with the call's key. */
    internal fun toRoom(ticket: CallRoomTicket) {
        if (room != null || state == CallState.ENDED) return
        changePath(CallPath.ROOM)
        set(CallState.CONNECTING)
        stopMedia()
        val next = owner.handoffRoom(name, ticket.at(roomServer), mediaKey, microphoneOn, cameraOn)
        room = next
        next.onState {
            when (it) {
                GroupCallState.CONNECTED -> set(CallState.CONNECTED)
                GroupCallState.RECONNECTING -> set(CallState.RECONNECTING)
                else -> {}
            }
        }
        next.onWaiting { waitingHandlers.fire(it) }
        next.onPeople { remoteHandlers.fire() }
        next.onEnded {
            if (state != CallState.ENDED) {
                session.leaveAfterHandoff("room_lost")
                finish("room_lost")
            }
        }
    }

    /** The direct path's own microphone and camera let go: a room opens its own. */
    private fun stopMedia() {
        stopSharing()
        media.stopCamera()
        media.setMicrophoneEnabled(false)
    }

    /** What the other side has on: `camera`, `microphone`, `screen_share`, `screen_share_audio`. */
    val remoteMedia: Map<String, Boolean> get() = session.remoteState

    /** The state as it changes. */
    fun onState(handler: (CallState) -> Unit): CallListener = stateHandlers.add(handler)

    /** What carries the call changed: `DIRECT`, `RELAY` or `ROOM`. */
    fun onPath(handler: (CallPath) -> Unit): CallListener = pathHandlers.add(handler)

    /**
     * The room the call moved to waits for a place on the mesh: until when, and why. The state stays `CONNECTING`, and it
     * goes on by itself.
     */
    fun onWaiting(handler: (CallWait) -> Unit): CallListener = waitingHandlers.add(handler)

    /** The other side's media or tracks changed. */
    fun onRemote(handler: () -> Unit): CallListener = remoteHandlers.add({ handler() })

    /** This app began or stopped sharing its screen: said once it did, the system's own stop included. */
    fun onScreenShare(handler: (Boolean) -> Unit): CallListener = screenHandlers.add(handler)

    /** The call ended, with the reason: `hangup`, `declined`, `busy`, `cancelled`, `connection_failed` … */
    fun onEnded(handler: (String) -> Unit): CallListener = endHandlers.add(handler)

    /** Takes a handler off. */
    fun off(listener: CallListener) {
        stateHandlers.remove(listener)
        pathHandlers.remove(listener)
        waitingHandlers.remove(listener)
        remoteHandlers.remove(listener)
        endHandlers.remove(listener)
        groupHandlers.remove(listener)
        screenHandlers.remove(listener)
    }

    /** Whether this app is sharing its screen on the call. */
    val sharingScreen: Boolean get() = room?.sharingScreen ?: (screenCapture != null)

    /**
     * Shares this phone's screen, or stops: whether it is shared now. To share, `permission` is the system's answer to
     * `ScreenShare.permissionIntent`, and is good for one share. False before the call is up, without an answer, and where
     * the system refuses it. On the main thread.
     */
    suspend fun setScreenShare(on: Boolean, permission: Intent? = null): Boolean {
        if (state != CallState.CONNECTED && state != CallState.RECONNECTING) return false
        room?.let { return it.setScreenShare(on, permission) }
        if (!on) {
            stopSharing()
            return false
        }
        if (screenCapture != null) return true
        permission ?: return false
        if (!ScreenShareService.start(context)) return false
        if (state == CallState.ENDED) {
            ScreenShareService.stop(context)
            return false
        }
        val capture = CallScreenCapture(context)
        capture.onStopped = { main.post { stopSharing() } }
        val track = runCatching { capture.start(permission) }.onFailure { CallLog.write("the screen was refused: ${it.message}") }.getOrNull()
        if (track == null) {
            capture.stop()
            ScreenShareService.stop(context)
            return false
        }
        startedSharing(capture, track)
        return true
    }

    /** The capture running: on the call, and said. */
    internal fun startedSharing(capture: CallScreenCapture, track: VideoTrack) {
        screenCapture = capture
        session.setLocalTrack(CallSource.SCREEN_SHARE, track, capture.captureHeight)
        screenHandlers.fire(true)
    }

    /** The screen off the call, its capture and its service let go. */
    private fun stopSharing() {
        val capture = screenCapture ?: return
        screenCapture = null
        session.setLocalTrack(CallSource.SCREEN_SHARE, null)
        capture.stop()
        ScreenShareService.stop(context)
        screenHandlers.fire(false)
    }

    /** Whether this side's microphone is heard. */
    var microphone: Boolean
        get() = microphoneOn
        set(value) {
            microphoneOn = value
            media.setMicrophoneEnabled(value)
            media.microphoneTrack?.let { session.setLocalTrack(CallSource.MICROPHONE, if (value) it else null) }
        }

    /** Whether this side's camera is sent. */
    var camera: Boolean
        get() = cameraOn
        set(value) {
            cameraOn = value
            if (value) startCamera() else {
                session.setLocalTrack(CallSource.CAMERA, null)
                media.stopCamera()
            }
        }

    /** The other side's camera, to draw (CallVideoView). */
    val remoteCamera: CallVideoTrack? get() = (session.remoteTracks[CallSource.CAMERA] as? VideoTrack)?.let { CallVideoTrack(it) }

    /**
     * The screen the other side shares, to draw (CallVideoView), while it comes: `remoteMedia["screen_share"]` says they
     * share one. As the browser's `remote.screen`.
     */
    val remoteScreen: CallVideoTrack? get() = (session.remoteTracks[CallSource.SCREEN_SHARE] as? VideoTrack)?.let { CallVideoTrack(it) }

    /** This side's camera, for its own picture. */
    val localCamera: CallVideoTrack? get() = media.cameraTrack?.let { CallVideoTrack(it) }

    /** Hangs up, or calls it off while it still rings (`cancelled`). */
    fun end() {
        if (state == CallState.ENDED) return
        SystemCalls.endingHere(call = this)
        val reason = if (state == CallState.RINGING) "cancelled" else "hangup"
        if (room != null) session.leaveAfterHandoff(reason) else session.end(reason)
        finish(reason)
    }

    /** Turns the camera round, the front one to the back and back, while it is sent. */
    fun flipCamera() {
        if (!cameraOn || state == CallState.ENDED) return
        room?.let {
            it.flipCamera()
            return
        }
        val track = media.flipCamera() ?: return
        session.setLocalTrack(CallSource.CAMERA, track, media.captureHeight)
        remoteHandlers.fire()
    }

    /** Whether the camera sent is the front one, or while none is sent, the one that opens next. */
    val frontCamera: Boolean get() = room?.frontCamera ?: if (cameraOn) media.usingFrontCamera else preferFront

    /** The side the next camera opens on, while none is sent: a camera on turns round with `flipCamera()`. Nothing opens. */
    fun preferCamera(front: Boolean) {
        preferFront = front
        room?.preferCamera(front)
    }

    /** How loud this app's microphone is while it is sent, from 0 to 1, for a meter: null while it is not. */
    fun microphoneLevel(): Float? {
        room?.let { return it.microphoneLevel() }
        return if (microphoneOn) media.heard.level() else null
    }

    /** The room carrying the call after neither the direct path nor TURN could: null while it is direct. */
    @WordPlusModulesApi
    val carryingRoom: GroupCall? get() = room

    /** The calls it is one of, for whom the screen offers to add. */
    @WordPlusModulesApi
    val calls: RealtimeCalls get() = owner

    /** Joins the call: as its caller, that makes it ring. The microphone and camera are opened as it is joined. */
    internal suspend fun begin() {
        CallAudio.startCall(context, type == CallType.VIDEO)
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            val track = media.startMicrophone()
            media.setMicrophoneEnabled(microphoneOn)
            session.setLocalTrack(CallSource.MICROPHONE, track)
        }
        if (cameraOn) startCamera()
        val joined = suspendCancellableCoroutine { continuation -> session.start { continuation.resume(it) } }
        if (!joined) {
            finish(endReason ?: "not_started")
            throw CallException(endReason ?: "not_started")
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The app was allowed the microphone or the camera after the call began (it asked once the call was answered): the
     * microphone is opened now, and the camera a video call wanted.
     */
    fun permissionsGranted() {
        if (state == CallState.ENDED || room != null) return
        if (media.microphoneTrack == null && granted(Manifest.permission.RECORD_AUDIO)) {
            val track = media.startMicrophone()
            media.setMicrophoneEnabled(microphoneOn)
            session.setLocalTrack(CallSource.MICROPHONE, track)
        }
        if (cameraRefused && granted(Manifest.permission.CAMERA)) {
            cameraRefused = false
            cameraOn = true
            startCamera()
        }
    }

    /** The camera a video call wanted, refused for want of the permission. */
    private var cameraRefused = false

    private fun startCamera() {
        if (!granted(Manifest.permission.CAMERA)) {
            cameraOn = false
            cameraRefused = true
            return
        }
        val track = media.startCamera(preferFront)
        if (track == null) {
            cameraOn = false
            return
        }
        session.setLocalTrack(CallSource.CAMERA, track, media.captureHeight)
        remoteHandlers.fire()
    }

    private fun reacquire(source: CallSource) {
        if (state == CallState.ENDED) return
        if (source == CallSource.MICROPHONE) {
            val track = media.restartMicrophone()
            media.setMicrophoneEnabled(microphoneOn)
            session.setLocalTrack(CallSource.MICROPHONE, if (microphoneOn) track else null)
        } else if (source == CallSource.CAMERA && cameraOn) {
            media.stopCamera()
            startCamera()
        }
    }

    internal fun changePath(next: CallPath) {
        if (path == next) return
        path = next
        pathHandlers.fire(next)
    }

    private fun set(next: CallState) {
        if (state == next || state == CallState.ENDED) return
        state = next
        stateHandlers.fire(next)
    }

    /** The token asked for again ten minutes before it runs out, so a join after a long drop still has one. */
    private fun scheduleRenewal(exp: Double) {
        renewal?.let { main.removeCallbacks(it) }
        val work = Runnable {
            if (state == CallState.ENDED) return@Runnable
            Thread {
                val grant = runCatching { kotlinx.coroutines.runBlocking { owner.authorize(request) } }.getOrNull()
                main.post {
                    if (grant != null) {
                        token = grant.optString("token", token)
                        scheduleRenewal(grant.optDouble("exp", System.currentTimeMillis() / 1000.0 + 3600))
                    } else {
                        scheduleRenewal(System.currentTimeMillis() / 1000.0 + 660)
                    }
                }
            }.start()
        }
        renewal = work
        main.postDelayed(work, (maxOf(30.0, exp - 600 - System.currentTimeMillis() / 1000.0) * 1000).toLong())
    }

    private fun finish(reason: String) {
        if (state == CallState.ENDED) return
        endReason = reason
        state = CallState.ENDED
        renewal?.let { main.removeCallbacks(it) }
        room?.leave()
        stopSharing()
        media.stopCamera()
        media.setMicrophoneEnabled(false)
        CallAudio.endCall(context)
        owner.forget(this)
        stateHandlers.fire(CallState.ENDED)
        endHandlers.fire(reason)
    }
}

/** A video track of a call, for CallVideoView. */
class CallVideoTrack internal constructor(internal val track: VideoTrack) {
    /** libwebrtc's track, for the SDK's call screens (cloud.wordplus:realtime-ui). An app draws with CallVideoView. */
    @WordPlusModulesApi
    val rtcTrack: VideoTrack get() = track
}

/** What the SDK's call screens (cloud.wordplus:realtime-ui) draw with: the calls' shared EGL context, and the speaker. */
@WordPlusModulesApi
object CallsScreenSupport {
    fun eglBase(context: Context): livekit.org.webrtc.EglBase = CallMedia.get(context).eglBase

    fun audioRoutes(context: Context): List<Pair<String, String>> = CallAudio.outputs(context)

    fun audioRoute(context: Context): String = CallAudio.currentRoute(context)

    fun setAudioRoute(context: Context, route: String) = CallAudio.setRoute(context, route)

    fun setSpeaker(context: Context, on: Boolean) = CallAudio.setSpeaker(context, on)

    /** A device check's camera, opened on this side alone and never sent: null where it would not open. */
    fun startCameraCheck(context: Context, front: Boolean): livekit.org.webrtc.VideoTrack? = CallMedia.get(context).startPreview(front)

    fun stopCameraCheck(context: Context) = CallMedia.get(context).stopPreview()

    /** How loud the call's audio device is as it records, from 0 to 1: null while it is not recording, muted or not. */
    fun microphoneRecorded(context: Context): Float? = CallMedia.get(context).heard.let { if (it.recording()) it.level() else null }
}

/** A call's video, drawn: the other side's camera (`call.remoteCamera`) or this side's own picture (`call.localCamera`). */
class CallVideoView(context: Context) : FrameLayout(context) {
    private val renderer = SurfaceViewRenderer(context)
    private var shown: VideoTrack? = null
    private var initialized = false

    init {
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Whether the picture is drawn as a mirror, as one's own camera usually is. */
    var mirrored: Boolean = false
        set(value) {
            field = value
            renderer.setMirror(value)
        }

    /** The track to draw, or null for none. */
    fun show(track: CallVideoTrack?) {
        if (shown === track?.track) return
        if (!initialized && track != null) {
            renderer.init(CallMedia.get(context).eglBase.eglBaseContext, null)
            renderer.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            initialized = true
        }
        shown?.removeSink(renderer)
        shown = track?.track
        shown?.addSink(renderer)
    }

    /** Lets the renderer go; call when the view leaves the screen for good. */
    fun release() {
        shown?.removeSink(renderer)
        shown = null
        if (initialized) renderer.release()
        initialized = false
    }
}
