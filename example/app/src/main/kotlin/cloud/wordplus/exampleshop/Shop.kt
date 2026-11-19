package cloud.wordplus.exampleshop

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cloud.wordplus.realtime.ConnectionState
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeException
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.TextNodeStore
import cloud.wordplus.realtime.calls.Call
import cloud.wordplus.realtime.calls.CallException
import cloud.wordplus.realtime.calls.CallState
import cloud.wordplus.realtime.calls.CallType
import cloud.wordplus.realtime.calls.GroupCall
import cloud.wordplus.realtime.calls.GroupCallState
import cloud.wordplus.realtime.calls.GroupPerson
import cloud.wordplus.realtime.calls.IncomingCall
import cloud.wordplus.realtime.calls.LiveStream
import cloud.wordplus.realtime.calls.calls
import cloud.wordplus.realtime.calls.stream
import cloud.wordplus.realtime.ui.CallScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executor

/** One of the shop people. The app lists them to sign in. Once signed in, the shop server says who they are. */
data class Person(val id: String, val name: String, val role: String, val staff: Boolean) {
    companion object {
        val ALL = listOf(
            Person("34", "Ann", "Support", true),
            Person("42", "Ben", "Customer", false),
            Person("43", "Cara", "Warehouse", true),
        )

        fun named(id: String) = ALL.firstOrNull { it.id == id }
    }
}

/** A message of the chat. `at` in milliseconds, what orders them. */
data class Message(val id: String, val from: String, val text: String, val at: Double)

data class Member(val id: String, val name: String, val role: String)

data class Notice(val id: Long, val text: String)

// The event data, as the shop server and pages send it.
@Serializable
data class OrderUpdate(val status: String)

@Serializable
data class ChatMessage(val from: JsonPrimitive? = null, val text: String = "")

@Serializable
data class Typing(val on: Boolean = false)

/**
 * The API demo as the signed-in person sees it, on the same rooms as the shop web pages, so the app and a browser
 * signed in as someone else see each other: the order of Ben moving on (a room the shop server emits to, with a notice to
 * Ben alone), who is in the shop (a presence room) and who is online, and the chat of Ann and Ben (emitted from the app with
 * an ack, kept by the shop server, which the webhook brings them to. Typing as volatile events).
 */
class Shop(private val application: Application) {
    companion object {
        val STEPS = listOf("placed", "packed", "shipped", "delivered")
        val LABELS = mapOf("placed" to "Placed", "packed" to "Packed", "shipped" to "Shipped", "delivered" to "Delivered")
        private val JSON = "application/json".toMediaType()
    }

    /**
     * The app's, not the screen's (ShopApplication): a call that rings the app closed starts the process with no screen,
     * and its connection is this one.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The shop server: the Express example (examples/express in realtime-js, `npm start`), which the emulator reaches at 10.0.2.2. */
    var server = "http://10.0.2.2:3000"
        private set

    var me by mutableStateOf<Person?>(null)
        private set
    var signingIn by mutableStateOf<String?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var orderId by mutableIntStateOf(1042)
        private set
    var item by mutableStateOf("")
        private set
    var status by mutableStateOf("placed")
        private set
    var live by mutableStateOf(false)
        private set
    var members by mutableStateOf(listOf<Member>())
        private set
    var inShop by mutableStateOf(true)
        private set
    var online by mutableStateOf(setOf<String>())
        private set
    var inChat by mutableStateOf(false)
        private set
    var other by mutableStateOf<Person?>(null)
        private set
    var otherName by mutableStateOf("")
        private set
    val messages = mutableStateListOf<Message>()

    /** The shop server keeps older messages than those shown. */
    var moreMessages by mutableStateOf(false)
        private set
    var typing by mutableStateOf(false)
        private set
    val notices = mutableStateListOf<Notice>()

    /** The call on now. The Better Messages call screen (cloud.wordplus:realtime-ui) rings it and draws it. The shop follows it. */
    var call by mutableStateOf<Call?>(null)
        private set
    var callState by mutableStateOf<CallState?>(null)
        private set
    var callSeconds by mutableIntStateOf(0)
        private set
    var callEnded by mutableStateOf<String?>(null)

    /** Whether this app is sharing its screen on the call or group call. */
    var sharingScreen by mutableStateOf(false)
        private set
    private var callClock: Runnable? = null

    /** The group call on now: a call someone was added to, or the daily meeting. */
    var group by mutableStateOf<GroupCall?>(null)
        private set
    var groupTitle by mutableStateOf("")
        private set
    var groupPeople by mutableStateOf(listOf<GroupPerson>())
        private set
    var groupState by mutableStateOf<GroupCallState?>(null)
        private set

    /** The shop live show, while this app is in it, which the screen drawn ready shows as it goes. */
    var show by mutableStateOf<LiveStream?>(null)
        private set
    var showRoom by mutableStateOf<String?>(null)
        private set
    var showFailure by mutableStateOf<String?>(null)
        private set

    val reached get() = STEPS.indexOf(status).coerceAtLeast(0)

    private val prefs = application.getSharedPreferences("example-shop", Context.MODE_PRIVATE)
    private val http = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private var rt: Realtime? = null
    private var token = ""
    private var rooms = Triple("order:1042", "chat:34:42", "shop")
    private var events = "updated" to "message"
    private var messagesRoute = "/chat/messages"
    private val seen = HashSet<String>()
    private var historyRead = false
    private var typingSent = 0L
    private var typingEnds: Runnable? = null
    private var opened = false
    private var nextNotice = 0L

    /**
     * Once, from the activity or a call that woke the app: the shop address (kept, for a call that wakes the app with
     * no screen), and whoever signed in last time signed in again.
     */
    fun open(server: String?, reset: Boolean) {
        if (opened && !reset) return
        if (opened) closeConnection()
        opened = true
        (server ?: prefs.getString("server", null))?.let { this.server = it.trimEnd('/') }
        server?.let { prefs.edit().putString("server", it).apply() }
        if (reset) prefs.edit().remove("person").apply()
        prefs.getString("person", null)?.let { signIn(it) }
    }

    /**
     * The connection of the person signed in, for a call that rang the app closed (SystemCalls): the one running, or the
     * one signing in again as the app opens, null when nobody is signed in.
     */
    suspend fun connection(): Realtime? {
        rt?.let { return it }
        open(null, false)
        if (prefs.getString("person", null) == null) return null
        repeat(150) {
            rt?.let { return it }
            if (failure != null) return null
            delay(100)
        }
        return rt
    }

    /**
     * Signs in as one of the shop people (how the example stands in for the sign-in of an app), then connects with the
     * settings its server sent: the user keys and identity included, so no request comes first.
     */
    fun signIn(id: String) {
        signingIn = id
        failure = null
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url("$server/api/token").post("""{"as":$id}""".toRequestBody(JSON)).build()
                    http.newCall(request).execute().use { Json.parseToJsonElement(it.body!!.string()) as JsonObject }
                }
                val token = (answer["token"] as? JsonPrimitive)?.content ?: throw RealtimeException("sign_in")
                prefs.edit().putString("person", id).apply()
                start(token, answer["realtime"]!!, answer["shop"] as JsonObject)
            } catch (e: Exception) {
                failure = (e as? RealtimeException)?.code ?: e.message ?: "error"
            } finally {
                signingIn = null
            }
        }
    }

    fun signOut() {
        call?.end()
        group?.leave()
        leaveShow()
        // Nothing rings this phone for the person signed out any more, the app closed or not.
        rt?.calls(application)?.stopRinging()
        endConnection()
        prefs.edit().remove("person").apply()
        members = emptyList()
        messages.clear()
        moreMessages = false
        notices.clear()
        online = emptySet()
    }

    /**
     * The connection closed as a closed app has none, the person still signed in, so the next call rings the app as a
     * closed app rings (the proof's woken-app test): through its push, which opens the connection again.
     */
    fun closeConnection() {
        call?.end()
        group?.leave()
        show?.leave()
        endConnection()
        opened = false
    }

    /** The connection closed with its room flows, and the chat read from its history again by the next one. */
    private fun endConnection() {
        collectors.forEach { it.cancel() }
        collectors.clear()
        rt?.close()
        rt = null
        me = null
        seen.clear()
        historyRead = false
        live = false
        connected = false
    }

    private fun start(token: String, config: JsonElement, shop: JsonObject) {
        this.token = token
        val myId = shop.obj("me").str("id")
        me = Person(myId, shop.obj("me").str("name"), shop.obj("me").str("role"), (shop.obj("me")?.get("staff") as? JsonPrimitive)?.booleanOrNull ?: false)
        orderId = (shop.obj("order")?.get("id") as? JsonPrimitive)?.intOrNull ?: orderId
        item = shop.obj("order").str("item")
        status = shop.obj("order").str("status").ifEmpty { "placed" }
        rooms = Triple(shop.obj("rooms").str("order").ifEmpty { rooms.first }, shop.obj("rooms").str("chat").ifEmpty { rooms.second }, shop.obj("rooms").str("shop").ifEmpty { rooms.third })
        showRoom = shop.obj("rooms").str("show").ifEmpty { null }
        events = shop.obj("events").str("order").ifEmpty { events.first } to shop.obj("events").str("chat").ifEmpty { events.second }
        val between = (shop.obj("chat")?.get("between") as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        inChat = myId in between
        other = between.firstOrNull { it != myId }?.let { Person.named(it) }
        otherName = other?.name ?: ""
        messagesRoute = shop.obj("routes").str("messages").ifEmpty { messagesRoute }
        messages.clear()

        // Handlers on the main thread. The nodes kept between launches in the app preferences.
        val rt = Realtime(
            RealtimeOptions.fromConfig(
                config, server,
                headers = { mapOf("Authorization" to "Bearer $token") },
                callbackExecutor = Executor { main.post(it) },
                nodeStore = TextNodeStore({ prefs.getString(it, null) }, { key, value -> prefs.edit().putString(key, value).apply() }),
            ),
        )
        this.rt = rt
        rt.onState { state, _ -> connected = state == ConnectionState.Connected }

        // What the shop server sends the signed-in user alone, such as a notice that their order moved on.
        rt.on("notice") { data, _ ->
            val notice = Notice(nextNotice++, data.str("text"))
            notices.add(notice)
            main.postDelayed({ notices.remove(notice) }, 6_000)
        }

        // Who is online across the shop: a dot on every avatar.
        val everyone = Person.ALL.map { it.id }
        rt.online.watch(everyone).on("change") { online = everyone.filter { rt.online.has(it) }.toSet() }

        // The order: the shop server emits each change to its room.
        val order = rt.join(rooms.first)
        collect { order.eventsOf<OrderUpdate>(events.first).collect { (update, _) -> status = update.status } }
        order.on("joined") { _, _ -> live = true }
        order.on("error") { error, _ ->
            live = false
            failure = error.str("code")
        }

        enterShop()
        if (inChat) openChat(rt)

        // A call ringing for this user, from a browser or another app: Better Messages call screen rings and shows it
        // (cloud.wordplus:realtime-ui), the shop follows the call it becomes.
        CallScreen.attach(application, rt.calls(application))
        // The way back to the shop, a button of the app's own in the dock: the call goes on in the shop's bar. The screen
        // has none of its own for the SDK's calls.
        CallScreen.pieces = CallScreen.Pieces(controls = { context, ours, moment ->
            if (moment.phase == "ringing_in") ours
            else ours + CallScreen.CallControl.own(context, "shop", "Back to the shop", "m6 9 6 6 6-6") { CallScreen.hide() }
        })
        // Add Person in the dock: whom the shop server lists (its calls rule's `people`), rung into the call.
        CallScreen.addPerson = true
        CallScreen.onAnswered = { answered ->
            callEnded = null
            when (answered) {
                is IncomingCall.Answered.ToCall -> follow(answered.call)
                is IncomingCall.Answered.ToGroup -> follow(answered.group, "Group call")
            }
        }
        rt.connect()
    }

    // Calls.

    /** The call name for two people, as the shop server allows it: `support:<lower id>:<higher id>`. */
    private fun callName(other: String) = "support:" + listOf(me?.id ?: "", other).sorted().joinToString(":")

    /** Calls someone in the shop. */
    fun startCall(other: String) {
        val rt = rt ?: return
        if (call != null) return
        callEnded = null
        scope.launch {
            try {
                val next = rt.calls(application).start(callName(other), other, CallType.VIDEO)
                follow(next)
                CallScreen.show(application, next, Person.named(other)?.name)
            } catch (e: CallException) {
                callEnded = e.code
            }
        }
    }

    /** Rings someone else in the shop into the call, which becomes a group call. */
    fun addToCall(user: String) {
        val now = call ?: return
        scope.launch { runCatching { now.add(user) } }
    }

    /** The daily meeting: a group call for everyone in the shop, joined silent. */
    fun joinMeeting() {
        val rt = rt ?: return
        if (group != null || call != null) return
        scope.launch {
            try {
                val next = rt.calls(application).group("meeting:daily", CallType.VIDEO)
                follow(next, "Daily meeting")
                CallScreen.show(application, next, title = "Daily meeting")
            } catch (e: CallException) {
                callEnded = e.code
            }
        }
    }

    // The live show.

    /** Watches the shop live show, as the `streams()` rule of the shop server lets this user in (Ann presents it). */
    fun watchShow() {
        val rt = rt ?: return
        val name = showRoom ?: return
        if (show != null) return
        showFailure = null
        scope.launch {
            try {
                val next = rt.calls(application).stream(name)
                next.onEnded { if (show === next) show = null }
                show = next
            } catch (e: CallException) {
                showFailure = e.code
            }
        }
    }

    fun leaveShow() {
        show?.leave()
        show = null
    }

    private fun follow(next: GroupCall, title: String) {
        group = next
        groupTitle = title
        groupState = next.state
        groupPeople = next.people
        next.onState { if (group === next) groupState = it }
        next.onPeople { if (group === next) groupPeople = next.people }
        next.onScreenShare { if (group === next) sharingScreen = it }
        next.onEnded {
            if (group !== next) return@onEnded
            sharingScreen = false
            group = null
            groupPeople = emptyList()
            groupState = null
        }
    }

    private fun follow(next: Call) {
        call = next
        callState = next.state
        callSeconds = 0
        next.onState { state ->
            if (call !== next) return@onState
            callState = state
            if (state == CallState.CONNECTED && callClock == null) {
                val tick = object : Runnable {
                    override fun run() {
                        callSeconds++
                        main.postDelayed(this, 1_000)
                    }
                }
                callClock = tick
                main.postDelayed(tick, 1_000)
            }
        }
        next.onScreenShare { if (call === next) sharingScreen = it }
        // Someone was added: the call goes on as a group call.
        next.onGroup { follow(it, "Group call") }
        next.onEnded { reason ->
            if (call !== next) return@onEnded
            sharingScreen = false
            callClock?.let { main.removeCallbacks(it) }
            callClock = null
            call = null
            callState = null
            callEnded = reason
        }
    }

    /** The room flows of the connection running, which end with it. */
    private val collectors = mutableListOf<Job>()

    /** Collects a room flow on the main thread, its listener in place before the connection opens. */
    private fun collect(block: suspend () -> Unit) {
        collectors += scope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
    }

    // The order.

    /** The staff move the order on, through the shop server, which emits it to the order room and skips this app. */
    fun mark(next: String) {
        val rt = rt ?: return
        status = next
        val request = Request.Builder()
            .url("$server/orders/$orderId/status")
            .header("Authorization", "Bearer $token")
            .header("X-Realtime-Tab", rt.tab)
            .post(buildJsonObject { put("status", next) }.toString().toRequestBody(JSON))
            .build()
        scope.launch(Dispatchers.IO) { runCatching { http.newCall(request).execute().close() } }
    }

    // Who is in the shop.

    private fun enterShop() {
        val rt = rt ?: return
        val room = rt.join(rooms.third)
        for (event in listOf("joined", "join", "leave")) {
            room.on(event) { _, _ ->
                if (!inShop) return@on
                members = room.members
                    .map { (id, info) -> Member(id, info.str("name").ifEmpty { Person.named(id)?.name ?: "" }, info.str("role")) }
                    .sortedBy { it.id.toIntOrNull() ?: 0 }
            }
        }
        inShop = true
    }

    /** Stepping out leaves the presence room. Coming back joins it again. */
    fun toggleShop() {
        if (inShop) {
            rt?.leave(rooms.third)
            inShop = false
            members = emptyList()
        } else {
            enterShop()
        }
    }

    // The chat.

    private fun openChat(rt: Realtime) {
        val chat = rt.join(rooms.second)
        collect {
            chat.eventsOf<ChatMessage>(events.second).collect { (message, meta) ->
                typing = false
                add(message.from?.content ?: meta.from ?: "", message.text, meta.id, meta.at)
            }
        }
        collect {
            chat.eventsOf<Typing>("typing").collect { (data, _) ->
                typing = data.on
                typingEnds?.let { main.removeCallbacks(it) }
                if (data.on) typingEnds = Runnable { typing = false }.also { main.postDelayed(it, 4_000) }
            }
        }
        // What was said before the app opened, once: the conversation from the shop server, then from the room
        // history (its last 5 minutes) what the webhook may not have brought yet. A reconnect gets what it missed itself.
        chat.on("joined") { _, _ ->
            if (historyRead) return@on
            historyRead = true
            scope.launch {
                runCatching { earlier(null) }
                val page = runCatching { chat.history(limit = 50) }.getOrNull() ?: return@launch
                for (e in page.events) if (e.event == events.second) add(e.data.str("from").ifEmpty { e.meta.from ?: "" }, e.data.str("text"), e.meta.id, e.meta.at)
            }
        }
        // Who is on the other side, from the app directory: the name their own server gave when they connected.
        other?.let { other ->
            scope.launch {
                runCatching { rt.users(listOf(other.id))[other.id] }.getOrNull()?.str("name")?.takeIf { it.isNotEmpty() }?.let { otherName = it }
            }
        }
    }

    /** A message once, in the order it was said. */
    private fun add(from: String, text: String, id: String?, at: Double?) {
        if (id != null && !seen.add(id)) return
        val message = Message(id ?: "local-${messages.size}-${System.nanoTime()}", from, text, at ?: System.currentTimeMillis().toDouble())
        val index = messages.indexOfFirst { it.at > message.at }
        if (index < 0) messages.add(message) else messages.add(index, message)
    }

    /** A page of the conversation the shop server keeps: the newest, or the one before the message `before`. */
    private suspend fun earlier(before: String?) {
        val query = before?.let { "?before=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val page = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$server$messagesRoute$query").header("Authorization", "Bearer $token").build()
            http.newCall(request).execute().use { if (it.isSuccessful) Json.parseToJsonElement(it.body!!.string()) as JsonObject else null }
        } ?: return
        for (m in page["messages"] as? JsonArray ?: JsonArray(emptyList())) {
            add(m.str("from"), m.str("text"), m.str("id"), m.str("at").toDoubleOrNull())
        }
        moreMessages = (page["more"] as? JsonPrimitive)?.booleanOrNull ?: false
    }

    /** The page before the oldest message shown. */
    fun showEarlier() {
        scope.launch { runCatching { earlier(messages.firstOrNull()?.id) } }
    }

    /** Typing, sent at most every two seconds while there is something written. */
    fun typed(text: String) {
        if (text.isEmpty() || System.currentTimeMillis() - typingSent < 2_000) return
        typingSent = System.currentTimeMillis()
        rt?.room(rooms.second)?.volatile?.emit("typing", buildJsonObject { put("on", true) })
    }

    /** A message straight to the room, acked by the cloud once it is kept and sent. */
    fun send(text: String) {
        val words = text.trim()
        val chat = rt?.room(rooms.second) ?: return
        val me = me ?: return
        if (words.isEmpty()) return
        typingSent = 0
        chat.volatile.emit("typing", buildJsonObject { put("on", false) })
        scope.launch {
            try {
                val ack = chat.emitWithAck(events.second, buildJsonObject {
                    put("from", me.id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(me.id))
                    put("text", words)
                })
                add(me.id, words, ack.id, null)
            } catch (e: RealtimeException) {
                add(me.id, "Not sent: ${e.code}", null, null)
            }
        }
    }
}

private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

/** A field as text, numbers included. Empty when there is none. */
private fun JsonElement?.str(key: String): String = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content ?: ""
