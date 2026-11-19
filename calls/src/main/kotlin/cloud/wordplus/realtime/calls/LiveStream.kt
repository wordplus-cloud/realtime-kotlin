package cloud.wordplus.realtime.calls

import android.content.Intent
import android.os.Handler
import android.os.Looper
import cloud.wordplus.realtime.NamespaceSocket
import cloud.wordplus.realtime.moduleHttpClient
import cloud.wordplus.realtime.serverUrl
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.BackupVideoCodec
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

// A live stream: Easy Stream's protocol, as the web's engine speaks it, for an app's own code; the Swift package's
// LiveStream twin. The app's server signs the grant (`streams()`), the cloud's join answers with the stream, this
// user's place in it and two seats: the chat's socket (`/estream`, on a connection of its own here) and the media's
// room on the mesh. A viewer joins the room while the stream is live; a host previews, goes live, and ends it.

/** Where a live stream is: before its start, live, or over. A host may take an ended stream live again. */
enum class LiveStreamState(val wire: String) {
    IDLE("idle"), LIVE("live"), ENDED("ended");

    internal companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * This app's place in the stream's room, where its picture and sound come: not joined (the stream isn't live, or the app
 * only previews), joining, in it, or joining again after a drop.
 */
enum class LiveStreamMedia { OFF, CONNECTING, LIVE, RECONNECTING }

/** Who the app's grant made this user in a stream. */
enum class LiveStreamRole(val wire: String) {
    HOST("host"), GUEST("guest"), VIEWER("viewer");

    internal companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

/** The reactions players offer unless the site offers its own: a reaction is any one emoji, and these are theirs. */
enum class LiveStreamReaction(val emoji: String) {
    HEART("❤️"), CLAP("👏"), LAUGH("😂"), WOW("😮"), FIRE("🔥");

    companion object {
        /** The emoji of a reaction as it arrives: itself, or for the name a player sent before emoji, the emoji of that name. */
        fun emoji(key: String): String = entries.firstOrNull { it.name.lowercase() == key }?.emoji ?: key
    }
}

/** A message in a stream's chat. `at` in milliseconds since 1970. */
data class LiveStreamMessage(
    val id: String,
    /** The app's user id of who wrote it, as the app's grants name users; null for a visitor who isn't signed in. */
    val user: String?,
    val name: String,
    val avatar: String?,
    val role: LiveStreamRole,
    val text: String,
    val at: Long,
)

/** Someone on the stage: the host, a guest, or OBS's picture (`encoder`). */
data class LiveStreamTile(
    /** Their place in the stream's room. */
    val identity: String,
    /** The app's user id, for a signed-in person. */
    val user: String?,
    val name: String,
    val avatar: String?,
    val role: LiveStreamRole,
    /** This app's own camera and microphone. */
    val local: Boolean,
    val camera: Boolean,
    val screen: Boolean,
    val microphone: Boolean,
    val speaking: Boolean,
    val encoder: Boolean,
)

/** How the stage shows, as the host chose: everyone, the one speaking large, or one person alone (`identity`). */
data class LiveStreamLayout(val mode: Mode, val identity: String?) {
    enum class Mode(val wire: String) {
        GRID("grid"), SPEAKER("speaker"), SPOTLIGHT("spotlight");

        internal companion object {
            fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
        }
    }
}

/** A hand raised to come on stage: who, by the app's user id, and since when (milliseconds since 1970). */
data class LiveStreamHand(val user: String?, val name: String, val at: Long)

/**
 * A live stream the app's server lets this user into, by a token from a route of its own (`rt.joinStream(context) { … }`)
 * or as its `streams()` rule says (`rt.calls(context).stream("show:42")`).
 * Its state, the audience's count, the chat and the stage change as the stream goes, on the main thread (`onChange`); the
 * picture shows in a `LiveStreamVideoView`. A host goes live with the camera and microphone (`goLive()`), and ends it.
 * What a role may not do throws its code (`not_host`, `read_only`, `slow` …).
 */
class LiveStream internal constructor(
    private val owner: RealtimeCalls,
    val name: String,
    video: Boolean,
    /** Each grant the stream needs, from the app's own server (`joinStream`). Null for one the calls route signs. */
    private val tokens: (suspend () -> String)? = null,
) {
    internal constructor(owner: RealtimeCalls, name: String) : this(owner, name, true)

    /** Whether this app takes the picture: false for one joined with `video = false`, until `watch()`. */
    var video: Boolean = video
        private set

    var role = LiveStreamRole.VIEWER
        private set
    var state = LiveStreamState.IDLE
        private set

    /** This app's place in the stream's room: `LIVE` once the picture can come. */
    var media = LiveStreamMedia.OFF
        internal set(value) {
            field = value
            if (value != LiveStreamMedia.CONNECTING) waiting = null
        }

    /**
     * While the join waits for a place: until when, and why (`busy`, `slot`, `unreachable`, or `full` while as many watch as
     * the stream takes, `until` its next try then). `media` stays as it was, and the join goes on by itself. Null at any
     * other time.
     */
    var waiting: CallWait? = null
        private set

    /** How many may watch at once, as the cloud said at the join; null where it set no limit. */
    var audience: Int? = null
        internal set

    /** When it went live, in milliseconds since 1970. */
    var startedAt: Long? = null
        private set

    /** How many watch, the stream's hosts and guests not counted, and the most at once since it went live. */
    var viewers = 0
        private set
    var peak = 0
        private set

    /** The chat, oldest first, as far back as the cloud keeps it. */
    var messages: List<LiveStreamMessage> = emptyList()
        private set

    /** The message pinned over the chat. */
    var pinned: LiveStreamMessage? = null
        private set

    /** Who is on the stage, the host first. */
    var tiles: List<LiveStreamTile> = emptyList()
        internal set
    var layout = LiveStreamLayout(LiveStreamLayout.Mode.GRID, null)
        private set

    /** Whether the stream takes reactions now. */
    var reactionsOn = true
        private set

    /** Whether this user keeps the chat (a host, or a moderator by their grant). */
    var isModerator = false
        private set

    /** Until when a moderator keeps this user from writing, in milliseconds since 1970. */
    var timeoutUntil: Long? = null
        private set

    /** Slow mode: one message per this many seconds, 0 for none. */
    var slow = 0
        private set

    /** The hands raised to come on stage, the oldest first: the host's and moderators' to see. */
    var hands: List<LiveStreamHand> = emptyList()
        private set

    /** Whether this user's hand is up, and whether the host invited them on stage (`joinStage()` takes the place). */
    var handRaised = false
        private set
    var invited = false
        private set

    /** Whether this user, a viewer, is on stage beside the host. */
    var onStage = false
        internal set

    /** Whether this user may ask to come on stage now: signed in, not the host, while it is live and takes guests. */
    val canAskStage: Boolean
        get() = role != LiveStreamRole.HOST && identity?.startsWith("u:") == true && state == LiveStreamState.LIVE && stageOn

    /** This user in the stream, and whether the stream takes guests (its setting, which may change as it goes). */
    private var identity: String? = null
    private var stageOn = true

    /** What `ask` does in a test, which has no chat socket. */
    internal var asking: (suspend (String, JSONObject) -> JSONObject?)? = null

    /** What this app sends, a host's or a guest's: its camera, its microphone and its screen. */
    var camera = false
        private set
    var microphone = false
        private set
    var sharingScreen = false
        private set

    /** Why this app is out of the stream: `left`, `banned`, `kicked`, or what it couldn't go on with. Null while in it. */
    var endReason: String? = null
        private set

    /** The viewer's choice of picture (`setQuality`): `AUTO` by the size of the view showing it, the default. */
    var quality = LiveStreamQuality.AUTO
        private set

    /** Whether this user may write in the chat: their grant's right, while no moderator keeps them from it. */
    val canWrite: Boolean get() = chatRight == "write" && (timeoutUntil?.let { it <= System.currentTimeMillis() } ?: true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    internal val playback = LiveStreamPlayback()
    private var reading: Job? = null
    internal var seats = JSONObject()
    private var joinedAt = 0L
    private var chatRight = "read"
    private var chat: NamespaceSocket? = null
    private var chatTries = 0
    private var room: Room? = null
    /** The address of the media server the room was joined on, which says whether it is the mesh. */
    private var mediaServer = ""
    private var connected = false
    private var connecting: Deferred<Room?>? = null
    private var localVideo: LocalVideoTrack? = null
    private var localAudio: LocalAudioTrack? = null
    private val published = HashSet<Track>()
    private val changeHandlers = ArrayList<() -> Unit>()
    private val waitingHandlers = ArrayList<(CallWait) -> Unit>()
    private val messageHandlers = ArrayList<(LiveStreamMessage) -> Unit>()
    private val reactionHandlers = ArrayList<(Map<String, Int>) -> Unit>()
    private val endHandlers = ArrayList<(String) -> Unit>()
    private val views: MutableSet<LiveStreamVideoView> = Collections.newSetFromMap(WeakHashMap())

    internal companion object {
        /** A stream's seats are renewed past this age before its room is joined: the media's token lasts six hours. */
        const val SEATS_FRESH_MS = 5 * 3600 * 1000L

        /** Viewers spread their arrival over this when a stream goes live, so a big audience doesn't come at once. */
        const val LIVE_JITTER_MS = 2000L
        const val HISTORY_KEPT = 200

        /**
         * A viewer still joining this long in while the stream is full waits for a place, as the web's player has it, and
         * one the room refused while it is full tries again this often: the media server doesn't say why it refuses one.
         */
        const val FULL_AFTER_MS = 4000L
        const val FULL_RETRY_MS = 10_000L

        fun message(value: Any?): LiveStreamMessage? {
            val o = value as? JSONObject ?: return null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            if (!o.has("text")) return null
            val from = o.optJSONObject("from")
            return LiveStreamMessage(
                id = id,
                user = userOf(from?.optString("id") ?: ""),
                name = from?.optString("name") ?: "",
                avatar = from?.optString("avatar")?.takeIf { it.isNotEmpty() && it != "null" },
                role = LiveStreamRole.of(from?.optString("role")) ?: LiveStreamRole.VIEWER,
                text = o.optString("text"),
                at = o.optLong("at"),
            )
        }

        fun layout(value: Any?): LiveStreamLayout? {
            val o = value as? JSONObject ?: return null
            val mode = LiveStreamLayout.Mode.of(o.optString("mode")) ?: return null
            return LiveStreamLayout(mode, o.optString("identity").takeIf { it.isNotEmpty() && it != "null" && !o.isNull("identity") })
        }

        /** The app's user id in the cloud's name for a person: `u:<id>`, or `u:<id>.<8 hex>` for a place in the room. */
        fun userOf(identity: String): String? {
            if (!identity.startsWith("u:")) return null
            val rest = identity.removePrefix("u:")
            val dot = rest.lastIndexOf('.')
            if (dot >= 0 && rest.length - dot == 9 && rest.substring(dot + 1).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return rest.substring(0, dot)
            return rest
        }
    }

    // Hearing it

    /** Any change: the state, the count, the chat, the stage. On the main thread. */
    fun onChange(handler: () -> Unit) {
        changeHandlers.add(handler)
    }

    /** The join waits for a place on the mesh: until when, and why. It goes on by itself. */
    fun onWaiting(handler: (CallWait) -> Unit) {
        waitingHandlers.add(handler)
    }

    /** Each new message in the chat. */
    fun onMessage(handler: (LiveStreamMessage) -> Unit) {
        messageHandlers.add(handler)
    }

    /** The reactions sent since the last ones, by emoji, about every half second while the stream takes them. */
    fun onReactions(handler: (Map<String, Int>) -> Unit) {
        reactionHandlers.add(handler)
    }

    /** This app is out of the stream: `left`, `banned`, `kicked`, or what it couldn't go on with. */
    fun onEnded(handler: (String) -> Unit) {
        endHandlers.add(handler)
    }

    // Everyone's

    /** Writes in the chat. Throws the cloud's code: `read_only`, `slow`, `timeout`, `too_long`, `not_live` … */
    suspend fun send(text: String) {
        tell("chat", JSONObject().put("text", text))
    }

    /** A reaction everyone watching sees float up. */
    fun react(kind: LiveStreamReaction) = react(kind.emoji)

    /** A reaction everyone watching sees float up: any one emoji the app offers. */
    fun react(emoji: String) {
        chat?.emit("react", json(JSONObject().put("kind", emoji)))
    }

    /** Leaves the stream: its picture, what this app sends, and its chat. */
    fun leave() = finish("left")

    // On stage

    /** Asks to come on stage, or no longer: the host sees the hand. A signed-in viewer's. */
    suspend fun hand(up: Boolean) {
        ask("hand", JSONObject().put("up", up))
        handRaised = up
        changed()
    }

    /** Comes on stage when invited (`invited`), with the camera and microphone beside the host's. */
    suspend fun joinStage() {
        val reply = try {
            ask("stage", JSONObject())
        } catch (e: Exception) {
            invited = false
            changed()
            throw e
        }
        invited = false
        handRaised = false
        val seat = reply.optJSONObject("media")
        if (seat == null || seat.optString("url").isEmpty() || seat.optString("token").isEmpty()) {
            changed()
            throw CallException("server")
        }
        // The viewer's place in the room is left for the guest's.
        leaveRoom()
        onStage = true
        try {
            preview()
            mediaRoom(seat) ?: throw CallException("publish_failed")
            publish()
        } catch (e: Exception) {
            leaveStage()
            throw e
        }
    }

    /** Says no to the invitation, for now: the host is told nothing, and may invite again. */
    fun declineStage() {
        if (!invited) return
        invited = false
        changed()
    }

    /**
     * Off stage by choice, back to watching. The cloud takes this guest off everyone's stage first: left to itself, the
     * guest's session stays a publisher in the room until the media server lets it go, and the viewer's join, the same
     * person's, waits for it. A cloud that refuses leaves it at that.
     */
    fun leaveStage() {
        if (!onStage) return
        val me = identity ?: return backToWatching(watching = true)
        backToWatching(watching = false)
        scope.launch {
            runCatching { ask("unstage", JSONObject().put("identity", me)) }
            if (video) watch(0)
        }
    }

    /** Off stage, once, whichever comes first: the word of the cloud, or this app. */
    private fun backToWatching(watching: Boolean) {
        if (!onStage) return
        onStage = false
        stopMedia()
        if (watching && video && state == LiveStreamState.LIVE) watch(0)
    }

    // A host's

    /** The camera and microphone, shown in a `LiveStreamVideoView` before going live. The app asks for them first. */
    suspend fun preview(camera: Boolean = true, microphone: Boolean = true) {
        if (!sends) throw CallException("not_host")
        if (camera) setCamera(true)
        if (microphone) setMicrophone(true)
    }

    /** Whether this app may send: a host, a guest, or a viewer on stage. */
    private val sends: Boolean get() = role != LiveStreamRole.VIEWER || onStage

    /** Asks a signed-in viewer on stage, by the app's user id; `unstage` takes a guest off. */
    suspend fun invite(user: String) {
        tell("invite", JSONObject().put("identity", "u:$user"))
    }

    suspend fun unstage(user: String) {
        tell("unstage", JSONObject().put("identity", "u:$user"))
    }

    /** What everyone sees on stage: everyone, the one speaking large, or one person (`user`) in the spotlight. */
    suspend fun layout(mode: LiveStreamLayout.Mode, user: String? = null) {
        tell("layout", JSONObject().put("mode", mode.wire).apply { if (user != null) put("identity", "u:$user") })
    }

    /** Where OBS streams to, over WHIP, and its key; `renew` makes a new key, which cuts off an encoder using the old. */
    suspend fun ingest(renew: Boolean = false): Pair<String, String> {
        val reply = ask("ingest", if (renew) JSONObject().put("renew", true) else JSONObject())
        val key = reply.optString("key").takeIf { it.isNotEmpty() } ?: throw CallException("server")
        val path = reply.optString("path").takeIf { it.isNotEmpty() } ?: throw CallException("server")
        return owner.rt.serverUrl.trimEnd('/') + path to key
    }

    // Keeping the chat (a host's, and a moderator's)

    suspend fun deleteMessage(id: String) {
        tell("delete", JSONObject().put("id", id))
    }

    suspend fun pin(id: String) {
        tell("pin", JSONObject().put("id", id))
    }

    suspend fun unpin() {
        tell("unpin", JSONObject())
    }

    /** Keeps a user from writing for a minute to a day; they go on watching. */
    suspend fun timeout(user: String, seconds: Int) {
        tell("timeout", JSONObject().put("identity", "u:$user").put("seconds", seconds))
    }

    /** One message per `seconds`, 0 to turn it off. */
    suspend fun setSlow(seconds: Int) {
        tell("slow", JSONObject().put("seconds", seconds))
    }

    suspend fun clearChat() {
        tell("clear", JSONObject())
    }

    /** Out of the stream now; they may come back. The same word as on a room and a group call. */
    suspend fun remove(user: String) {
        tell("kick", JSONObject().put("identity", "u:$user"))
    }

    /** The same as [remove]. */
    suspend fun kick(user: String) = remove(user)

    /** Out of the stream, and kept out. */
    suspend fun ban(user: String) {
        tell("ban", JSONObject().put("identity", "u:$user"))
    }

    suspend fun setCamera(on: Boolean) {
        if (!sends) throw CallException("not_host")
        val existing = localVideo
        if (on) {
            if (existing != null) {
                publication(existing)?.let { it.muted = false } ?: existing.startCapture()
            } else {
                val track = roomObject().localParticipant.createVideoTrack()
                track.startCapture()
                localVideo = track
                if (state == LiveStreamState.LIVE && connected) publish()
            }
        } else if (existing != null) {
            publication(existing)?.let { it.muted = true } ?: existing.stopCapture()
        }
        camera = on && localVideo != null
        changed()
    }

    suspend fun setMicrophone(on: Boolean) {
        if (!sends) throw CallException("not_host")
        val existing = localAudio
        if (on) {
            if (existing != null) {
                publication(existing)?.muted = false
            } else {
                localAudio = roomObject().localParticipant.createAudioTrack()
                if (state == LiveStreamState.LIVE && connected) publish()
            }
        } else if (existing != null) {
            publication(existing)?.muted = true
        }
        microphone = on && localAudio != null
        changed()
    }

    /** Starts the stream: what this app has on goes out (the camera and microphone when nothing is), then everyone watching sees it live. */
    suspend fun goLive() {
        if (role != LiveStreamRole.HOST) throw CallException("not_host")
        if (localVideo == null && localAudio == null) preview()
        mediaRoom() ?: throw CallException("publish_failed")
        publish()
        ask("live", JSONObject()).optJSONObject("stream")?.let { applyStream(it) }
    }

    /** Ends the stream for everyone: its room closes. */
    suspend fun end() {
        ask("end", JSONObject()).optJSONObject("stream")?.let { applyStream(it) }
        stopMedia()
    }

    // Joining

    /** The grant from the app's server, then the cloud's join; the chat opens, and a viewer of a live stream joins its room. */
    internal suspend fun join() {
        requestSeats()
        openChat()
        if (video && role == LiveStreamRole.VIEWER && state == LiveStreamState.LIVE) watch(0)
    }

    private suspend fun requestSeats(retried: Boolean = false) {
        val token = tokens?.invoke() ?: owner.authorize(JSONObject().put("stream", name)).getString("token")
        val reply = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(owner.rt.serverUrl.trimEnd('/') + "/easy-stream/v1/join")
                .post(JSONObject().put("grant", token).toString().toRequestBody("application/json".toMediaType()))
                .build()
            runCatching { owner.rt.moduleHttpClient.newCall(request).execute().use { JSONObject(it.body?.string() ?: "{}") } }.getOrNull()
        } ?: throw CallException("server")

        if (!reply.optBoolean("ok")) {
            val error = reply.optString("error").takeIf { it.isNotEmpty() } ?: "server"
            // A grant signed afresh may pass where one that ran out on the way didn't.
            if (error == "expired" && !retried) return requestSeats(retried = true)
            throw CallException(error)
        }
        seats = reply
        joinedAt = System.currentTimeMillis()
        audience = if (reply.has("audience") && !reply.isNull("audience")) reply.optInt("audience") else null
        reply.optJSONObject("you")?.let { applyYou(it) }
        reply.optJSONObject("stream")?.let { applyStream(it) }
    }

    // The chat's socket

    private fun openChat() {
        val seat = seats.optJSONObject("chat") ?: return
        val token = seat.optString("token").takeIf { it.isNotEmpty() } ?: return
        if (endReason != null) return
        chat?.close()

        val named = seat.optString("url").takeIf { it.isNotEmpty() } ?: owner.rt.serverUrl
        val base = Regex("^(https?://[^/]+)").find(named)?.value ?: owner.rt.serverUrl
        // No `rt`: this socket isn't handed on to a node, and one a draining node closes is opened again here.
        val socket = NamespaceSocket(
            base = base,
            path = seat.optString("path").takeIf { it.isNotEmpty() } ?: "/websocket",
            namespace = seat.optString("namespace").takeIf { it.isNotEmpty() } ?: "/estream",
            auth = json(JSONObject().put("t", token)),
            client = owner.rt.moduleHttpClient,
        ) { event -> main.post { heard(event) } }
        chat = socket
        socket.open()
    }

    private fun heard(event: NamespaceSocket.Event) {
        if (endReason != null) return
        when (event) {
            is NamespaceSocket.Event.Connected -> chatTries = 0
            is NamespaceSocket.Event.Refused -> when {
                event.reason.contains("banned") -> finish("banned")
                // The seats ran out: new ones, then the chat again.
                event.reason.contains("bad_token") -> scope.launch {
                    runCatching { requestSeats() }.onSuccess { openChat() }.onFailure { finish((it as? CallException)?.code ?: "server") }
                }
                else -> reopenChat()
            }
            is NamespaceSocket.Event.Closed -> reopenChat()
            is NamespaceSocket.Event.Message -> {
                // An event's data is an object, or an array (the hands) handed on as `list`.
                val text = event.data.toString()
                val data = runCatching { if (text.startsWith("[")) JSONObject().put("list", JSONArray(text)) else JSONObject(text) }.getOrNull() ?: JSONObject()
                apply(event.name, data)
            }
        }
    }

    /** The chat again after a drop, sooner at first: a draining node closes it, and the server's address hands it on. */
    private fun reopenChat() {
        chatTries++
        val delayMs = (min(15.0, 2.0.pow(min(chatTries, 4)) / 2) * 1000).toLong()
        main.postDelayed({ if (endReason == null) openChat() }, delayMs)
    }

    /** An event of the chat's socket. */
    internal fun apply(name: String, data: JSONObject) {
        when (name) {
            "hello" -> {
                data.optJSONObject("you")?.let { applyYou(it) }
                data.optJSONObject("stream")?.let { applyStream(it) }
                viewers = data.optInt("viewers", viewers)
                peak = if (data.has("peak")) data.optInt("peak") else maxOf(peak, viewers)
                slow = data.optInt("slow", 0)
                messages = (data.optJSONArray("history") ?: JSONArray()).let { list -> (0 until list.length()).mapNotNull { message(list.opt(it)) } }
                data.optJSONArray("hands")?.let { hands = handsOf(it) }
                pinned = message(data.opt("pinned"))
                layout = layout(data.opt("layout")) ?: LiveStreamLayout(LiveStreamLayout.Mode.GRID, null)
                timeoutUntil = if (data.isNull("timeout") || !data.has("timeout")) null else data.optLong("timeout")
                reactionsOn = if (data.has("reactions")) data.optBoolean("reactions") else true
            }
            "state" -> applyStream(data)
            "chat" -> {
                val message = message(data) ?: return
                if (messages.any { it.id == message.id }) return
                messages = (messages + message).takeLast(HISTORY_KEPT)
                messageHandlers.toList().forEach { it(message) }
            }
            "chat_deleted" -> {
                val id = data.optString("id")
                messages = messages.filter { it.id != id }
                if (pinned?.id == id) pinned = null
            }
            "cleared" -> {
                messages = emptyList()
                pinned = null
            }
            "pinned" -> pinned = message(data.opt("message"))
            "viewers" -> {
                viewers = data.optInt("n", viewers)
                peak = if (data.has("peak")) data.optInt("peak") else maxOf(peak, viewers)
            }
            "reactions" -> {
                val counts = LinkedHashMap<String, Int>()
                for (key in data.keys()) data.optInt(key, 0).takeIf { it > 0 }?.let { n -> counts.merge(LiveStreamReaction.emoji(key), n, Int::plus) }
                if (counts.isNotEmpty()) reactionHandlers.toList().forEach { it(counts) }
                return
            }
            "reacting" -> reactionsOn = data.optBoolean("on", reactionsOn)
            "slow" -> slow = data.optInt("seconds", 0)
            "layout" -> layout = layout(data) ?: layout
            "timeout" -> timeoutUntil = if (data.isNull("until") || !data.has("until")) null else data.optLong("until")
            "muted" -> scope.launch { runCatching { setMicrophone(false) } }
            // The whole queue, an array: heard() hands it on as `list`.
            "hands" -> hands = handsOf(data.optJSONArray("list") ?: JSONArray())
            "invited" -> {
                invited = true
                handRaised = false
            }
            "unstaged" -> backToWatching(watching = true)
            "banned" -> return finish("banned")
            "kicked" -> return finish("kicked")
            else -> return
        }
        changed()
    }

    private fun applyYou(you: JSONObject) {
        LiveStreamRole.of(you.optString("role"))?.let { role = it }
        you.optString("identity").takeIf { it.isNotEmpty() }?.let { identity = it }
        stageOn = you.optBoolean("stage", true)
        chatRight = you.optString("chat").takeIf { it.isNotEmpty() } ?: chatRight
        isModerator = role == LiveStreamRole.HOST || you.optBoolean("mod")
    }

    private fun applyStream(stream: JSONObject) {
        val next = LiveStreamState.of(stream.optString("state")) ?: return
        val was = state
        state = next
        startedAt = if (stream.isNull("started_at") || !stream.has("started_at")) null else stream.optLong("started_at")

        if (video && next == LiveStreamState.LIVE && was != LiveStreamState.LIVE && role == LiveStreamRole.VIEWER && !connected) {
            watch(if (was == LiveStreamState.IDLE) Random.nextLong(0, LIVE_JITTER_MS) else 0)
        }
        if (next == LiveStreamState.ENDED) stopMedia()
        changed()
    }

    /** What the chat's socket answers, or its refusal by code. */
    private suspend fun ask(event: String, payload: JSONObject): JSONObject = reply(answer(event, payload))

    /**
     * The same for an act that returns nothing. Not `ask` and nothing after it: Kotlin then leaves the function no state of
     * its own, and the reply itself resumes whoever called it, where an app on a newer Kotlin casts it to Unit
     * (`ClassCastException`, SuspendUnitTest). Reading the reply here is what stands between them.
     */
    private suspend fun tell(event: String, payload: JSONObject) {
        reply(answer(event, payload))
    }

    private suspend fun answer(event: String, payload: JSONObject): JSONObject? {
        val test = asking
        val socket = chat
        if (test == null && socket == null) throw CallException("not_connected")
        return if (test != null) test(event, payload) else suspendCancellableCoroutine<JSONObject?> { continuation ->
            socket!!.emit(event, json(payload)) { answer -> continuation.resume(answer?.let { runCatching { JSONObject(it.toString()) }.getOrNull() }) }
        }
    }

    private fun reply(answer: JSONObject?): JSONObject {
        val reply = answer ?: throw CallException("timeout")
        if (!reply.optBoolean("ok")) throw CallException(reply.optString("error").takeIf { it.isNotEmpty() } ?: "server")
        return reply
    }

    private fun json(o: JSONObject) = Json.parseToJsonElement(o.toString())

    // The picture

    /**
     * The picture and sound of a stream joined with `video = false`: they come as they would have. A `LiveStreamVideoView`
     * asks for them by itself.
     */
    fun watch() {
        if (video) return
        video = true
        if (role == LiveStreamRole.VIEWER && state == LiveStreamState.LIVE && !connected) watch(0)
    }

    private fun watch(delayMs: Long) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (endReason == null && state == LiveStreamState.LIVE && !connected) mediaRoom()
        }
    }

    /** The room's object, made once: a host's camera is made on it before the room is joined. */
    private fun roomObject(): Room = room ?: LiveKit.create(
        owner.context,
        options = RoomOptions(adaptiveStream = true, dynacast = true),
        overrides = LiveKitOverrides(eglBase = CallMedia.get(owner.context).eglBase),
    ).also { created ->
        room = created
        scope.launch { created.events.collect { handle(it) } }
    }

    private fun handsOf(list: JSONArray): List<LiveStreamHand> = (0 until list.length()).mapNotNull { i ->
        val hand = list.optJSONObject(i) ?: return@mapNotNull null
        LiveStreamHand(userOf(hand.optString("identity")), hand.optString("name"), hand.optLong("at"))
    }

    /**
     * Shares this phone's screen, or stops: whether it is shared now, for a host or a guest on stage. To share, `permission`
     * is the system's answer to `ScreenShare.permissionIntent`, and is good for one share. The room is joined for it, and it
     * goes out from there. It shows as the stage's main picture once the stream is live.
     */
    suspend fun setScreenShare(on: Boolean, permission: Intent? = null): Boolean {
        if (!on) {
            if (sharingScreen) stopScreen()
            return false
        }
        if (sharingScreen) return true
        if (!sends) return false
        permission ?: return false
        val room = mediaRoom() ?: return false
        val params = ScreenShareNotice.roomParams(owner.context, permission) { scope.launch { screenStopped() } }
        val shared = runCatching { room.localParticipant.setScreenShareEnabled(true, params) }.getOrDefault(false)
        if (!shared) return false
        screenStarted()
        return true
    }

    /** The screen went out. */
    internal fun screenStarted() {
        sharingScreen = true
        refreshTiles()
    }

    private suspend fun stopScreen() {
        runCatching { room?.localParticipant?.setScreenShareEnabled(false) }
        screenStopped()
    }

    private fun screenStopped() {
        if (!sharingScreen) return
        sharingScreen = false
        refreshTiles()
    }

    /**
     * The stream's room, joined with this user's seat (renewed first when it is old), or a guest's (`guest`, from the
     * stage's invitation).
     */
    private suspend fun mediaRoom(guest: JSONObject? = null): Room? {
        if (connected) return room
        connecting?.let { return it.await() }
        val task = scope.async {
            if (media != LiveStreamMedia.RECONNECTING) media = LiveStreamMedia.CONNECTING
            playback.joining()
            changed()
            if (guest == null && System.currentTimeMillis() - joinedAt > SEATS_FRESH_MS) runCatching { requestSeats() }
            val seat = guest ?: seats.optJSONObject("media") ?: return@async null.also { media = LiveStreamMedia.OFF }
            val room = roomObject()
            // The join names, as the browser's does, the codecs this device encodes and decodes in hardware.
            val caps = CallMedia.get(owner.context).codecCaps
            val options = ConnectOptions(
                meshHardwareDecoders = CallCodecs.hardwareDecoders(caps),
                onMeshWait = { wait -> scope.launch { heardWait(CallWait(wait)) } },
                meshHardwareEncoders = CallCodecs.hardwareEncoders(caps),
            )
            mediaServer = seat.optString("url")
            val asViewer = guest == null && role == LiveStreamRole.VIEWER
            val fullCheck = if (asViewer) scope.launch {
                delay(FULL_AFTER_MS)
                waitIfFull()
            } else null
            val ok = runCatching { room.connect(seat.getString("url"), seat.getString("token"), options) }.isSuccess
            fullCheck?.cancel()
            if (!ok) {
                joinFailed(asViewer)
                return@async null
            }
            if (endReason != null) {
                room.disconnect()
                return@async null
            }
            connected = true
            media = LiveStreamMedia.LIVE
            playback.joined(LiveStreamStats.Server(seat.optString("url"), room.serverInfo?.nodeId, null, room.serverInfo?.version?.toString()))
            startReading()
            refreshTiles()
            room
        }
        connecting = task
        val room = task.await()
        connecting = null
        return room
    }

    private suspend fun publish() {
        val room = room ?: return
        localVideo?.takeIf { it !in published }?.let {
            room.localParticipant.videoTrackPublishDefaults = cameraDefaults(room)
            if (room.localParticipant.publishVideoTrack(it)) published.add(it)
        }
        localAudio?.takeIf { it !in published }?.let { if (room.localParticipant.publishAudioTrack(it)) published.add(it) }
        refreshTiles()
    }

    /**
     * The camera's codec and what goes behind it, as the browser's streams take them (cameraCodecFor(), backupFor()):
     * the media server's policy, which comes with the join, else H.264 from hardware with VP8 behind it. H.265 only
     * on the mesh.
     */
    private fun cameraDefaults(room: Room): VideoTrackPublishDefaults {
        val caps = CallMedia.get(owner.context).codecCaps
        val policy = room.meshCodecPolicy
        val codec = CallCodecs.roomPublishCodec(caps, false, CallCodecs.onMesh(mediaServer), policy?.camera)
        val backup = CallCodecs.roomBackupCodec(codec, false, policy?.backup)
        return VideoTrackPublishDefaults(videoCodec = codec, backupCodec = backup?.let { BackupVideoCodec(codec = it) })
    }

    private fun publication(track: Track): LocalTrackPublication? =
        if (track in published) room?.localParticipant?.trackPublications?.values?.firstOrNull { it.track === track } as? LocalTrackPublication else null

    /** The room left, its object kept for another place in it (a viewer's for a guest's). */
    private fun leaveRoom() {
        stopReading()
        connected = false
        published.clear()
        media = LiveStreamMedia.OFF
        room?.disconnect()
    }

    /** The room left and what this app sent stopped: the stream ended, or this app left it. */
    private fun stopMedia() {
        stopReading()
        localVideo?.stopCapture()
        localVideo?.stop()
        localAudio?.stop()
        localVideo = null
        localAudio = null
        published.clear()
        camera = false
        microphone = false
        sharingScreen = false
        media = LiveStreamMedia.OFF
        room?.disconnect()
        room?.release()
        room = null
        connected = false
        refreshTiles()
    }

    private fun handle(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected, is RoomEvent.TrackSubscribed,
            is RoomEvent.TrackUnsubscribed, is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted,
            is RoomEvent.ParticipantMetadataChanged, is RoomEvent.ActiveSpeakersChanged -> refreshTiles()
            is RoomEvent.Disconnected -> {
                if (!connected) return
                stopReading()
                connected = false
                published.clear()
                // A guest whose place dropped watches again, as the web's player does.
                if (onStage) return leaveStage()
                // Still live: the room again, as a viewer's page does.
                val again = endReason == null && state == LiveStreamState.LIVE
                media = if (again) LiveStreamMedia.RECONNECTING else LiveStreamMedia.OFF
                if (video && again && role == LiveStreamRole.VIEWER) watch(2000)
                refreshTiles()
            }
            is RoomEvent.Reconnecting -> playback.reconnecting()
            is RoomEvent.Reconnected -> playback.reconnected()
            else -> {}
        }
    }

    /** The stage again, from the room: whoever sends in it, the host first. */
    internal fun refreshTiles() {
        val next = ArrayList<LiveStreamTile>()
        val room = room
        if (room != null && connected) {
            for (participant in room.remoteParticipants.values) {
                val identity = participant.identity?.value ?: continue
                val meta = participant.metadata?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                val encoder = identity == "ingest"
                val role = LiveStreamRole.of(meta.optString("role")) ?: LiveStreamRole.VIEWER
                val tracks = participant.trackPublications.values
                if ((role == LiveStreamRole.VIEWER && !encoder) || tracks.isEmpty()) continue
                next.add(LiveStreamTile(
                    identity = identity,
                    user = userOf(identity),
                    name = participant.name ?: identity,
                    avatar = meta.optString("avatar").takeIf { it.startsWith("https://") },
                    role = if (encoder) LiveStreamRole.HOST else role,
                    local = false,
                    camera = tracks.any { it.source == Track.Source.CAMERA && !it.muted },
                    screen = tracks.any { it.source == Track.Source.SCREEN_SHARE && !it.muted },
                    microphone = tracks.any { it.source == Track.Source.MICROPHONE && !it.muted },
                    speaking = participant.isSpeaking,
                    encoder = encoder,
                ))
            }
        }
        if (sends && (camera || microphone || sharingScreen)) {
            next.add(LiveStreamTile("local", null, "", null, if (onStage) LiveStreamRole.GUEST else role, local = true, camera = camera, screen = sharingScreen, microphone = microphone, speaking = false, encoder = false))
        }
        tiles = next.sortedBy { if (it.role == LiveStreamRole.HOST) 0 else 1 }
        changed()
    }

    /**
     * The stage's main picture, as the web's player picks it: the spotlight's, a shared screen, OBS's, the one speaking
     * (for that layout), the host's, or anyone's.
     */
    internal val mainTile: LiveStreamTile?
        get() {
            val pictured = tiles.filter { it.camera || it.screen }
            val spot = layout.identity
            if (layout.mode == LiveStreamLayout.Mode.SPOTLIGHT && spot != null) {
                pictured.firstOrNull { it.identity == spot || it.identity.startsWith("$spot.") }?.let { return it }
            }
            return pictured.firstOrNull { it.screen }
                ?: pictured.firstOrNull { it.encoder }
                ?: (if (layout.mode == LiveStreamLayout.Mode.SPEAKER) pictured.firstOrNull { it.speaking } else null)
                ?: pictured.firstOrNull { it.role == LiveStreamRole.HOST }
                ?: pictured.firstOrNull()
        }

    /** A tile's picture: its shared screen, else its camera; this app's own camera for its own tile. */
    internal fun videoTrack(tile: LiveStreamTile?): VideoTrack? {
        tile ?: return null
        if (tile.local) {
            val screen = if (sharingScreen) room?.localParticipant?.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack else null
            return screen ?: if (camera) localVideo else null
        }
        return picture(tile)?.track as? VideoTrack
    }

    private fun publications(tile: LiveStreamTile): Collection<TrackPublication> =
        room?.remoteParticipants?.values?.firstOrNull { it.identity?.value == tile.identity }?.trackPublications?.values ?: emptyList()

    /** The publication of someone else's picture: their shared screen, else their camera. */
    private fun picture(tile: LiveStreamTile): TrackPublication? {
        val publications = publications(tile)
        return publications.firstOrNull { it.source == Track.Source.SCREEN_SHARE && !it.muted }
            ?: publications.firstOrNull { it.source == Track.Source.CAMERA && !it.muted }
    }

    private fun layers(tile: LiveStreamTile?): List<LiveStreamLayer> {
        if (tile == null || tile.local) return emptyList()
        val publication = picture(tile) ?: return emptyList()
        val size = publication.dimensions ?: return emptyList()
        return LiveStreamPicture.layers(size.width, size.height, publication.simulcasted == true)
    }

    /**
     * The layers of the main picture a viewer may choose, the best first, each with its size: what `setQuality` chooses
     * from. None above 720p, which comes only on `AUTO`, to a view big enough for it. None before the picture arrives, and
     * for a host's own. Their sizes are worked out from the picture's: the largest, then half and a quarter.
     */
    fun qualities(): List<LiveStreamLayer> = LiveStreamPicture.choosable(layers(mainTile))

    /**
     * The picture's quality: one of the layers `qualities()` gives, whatever the size of the view, or `AUTO`, the default,
     * by its size. A layer above 720p chosen still gives 720p.
     */
    fun setQuality(quality: LiveStreamQuality) {
        if (quality == this.quality) return
        this.quality = quality
        playback.note("quality", quality.wire)
        changed()
    }

    /**
     * How the stream plays here, to debug it: the picture and the sound that arrive, the media server and the way they
     * come, and the session so far (how long the picture took to start, stalls, reconnects), with the last things that
     * happened. Measured on this device once a second while the room is joined, and sent nowhere.
     */
    fun stats(): LiveStreamStats = playback.snapshot()

    /** The layer a view showing this tile asks for: null on `AUTO`. */
    internal fun chosenLayer(tile: LiveStreamTile?): LiveStreamLayer? = LiveStreamPicture.chosen(quality, layers(tile))

    private fun startReading() {
        reading?.cancel()
        reading = scope.launch {
            while (true) {
                delay(1000)
                readPlayback()
            }
        }
    }

    private fun stopReading() {
        reading?.cancel()
        reading = null
        playback.left()
    }

    /** One reading a second of the main picture and its sound, from the room client's counters. */
    private suspend fun readPlayback() {
        if (!connected) return
        val tile = mainTile?.takeIf { !it.local }
        val track = tile?.let { picture(it)?.track }
        val sound = tile?.let { t -> publications(t).firstOrNull { it.source == Track.Source.MICROPHONE && !it.muted }?.track }
        val videoStats = track?.let { report(it) }
        val audioStats = sound?.let { report(it) }
        playback.read(
            video = videoStats?.inbound(),
            audio = audioStats?.inbound(),
            layers = layers(tile),
            transport = (videoStats ?: audioStats)?.transport(),
            pictured = views.isNotEmpty() && track != null,
        )
    }

    private suspend fun report(track: Track): LiveStreamReport? = runCatching { track.getRTCStats() }.getOrNull()?.let { report ->
        LiveStreamReport(report.statsMap.values.map { LiveStreamReport.Entry(it.id, it.type, it.members) })
    }

    internal fun show(view: LiveStreamVideoView) {
        watch()
        views.add(view)
        view.draw(this)
    }

    internal fun hide(view: LiveStreamVideoView) {
        views.remove(view)
    }

    /** Whether as many watch as the stream takes. */
    private val full: Boolean get() = audience?.let { viewers >= it } ?: false

    /**
     * A viewer's join that is taking a while, while the stream is full, is waiting for a place: the room refuses them until
     * someone leaves, and the room client tries again meanwhile.
     */
    internal fun waitIfFull() {
        if (connected || media != LiveStreamMedia.CONNECTING || waiting != null || !full) return
        heardWait(CallWait(System.currentTimeMillis() + FULL_RETRY_MS, "full"))
    }

    /**
     * The room could not be joined. Still live, a viewer tries again in a while, as the web's player does: every few seconds
     * while the stream is full, as a place may come, and told so.
     */
    internal fun joinFailed(asViewer: Boolean) {
        playback.failed(if (asViewer) "media_unreachable" else "publish_failed")
        val live = video && endReason == null && state == LiveStreamState.LIVE
        if (asViewer && full) {
            media = LiveStreamMedia.CONNECTING
            heardWait(CallWait(System.currentTimeMillis() + FULL_RETRY_MS, "full"))
            if (live) watch(FULL_RETRY_MS + Random.nextLong(LIVE_JITTER_MS))
            return
        }
        media = LiveStreamMedia.RECONNECTING
        if (live && asViewer) watch(3000)
        changed()
    }

    /** A join waiting for a place on the mesh, as the room client tells it. On the main thread. */
    internal fun heardWait(wait: CallWait) {
        if (connected || (media != LiveStreamMedia.CONNECTING && media != LiveStreamMedia.RECONNECTING)) return
        waiting = wait
        waitingHandlers.toList().forEach { it(wait) }
        changed()
    }

    private fun changed() {
        views.toList().forEach { it.draw(this) }
        changeHandlers.toList().forEach { it() }
    }

    private fun finish(reason: String) {
        if (endReason != null) return
        endReason = reason
        chat?.close()
        chat = null
        stopMedia()
        endHandlers.toList().forEach { it(reason) }
        scope.cancel()
    }
}

/**
 * The live stream `name`, as the app's server's `streams()` rule lets this user in: a host, a guest or a viewer. A viewer
 * of a live stream sees its picture at once in a `LiveStreamVideoView`. Throws its code: `forbidden` (the rule said no),
 * `not_included` (the app's plan doesn't stream), `banned`, `no_hours` … Call it on the main thread.
 */
suspend fun RealtimeCalls.stream(name: String): LiveStream = stream(name, video = true)

/**
 * The live stream `name`, with `video = false` for an app that shows only the chat or the stream's details: the picture is
 * fetched once something wants it (`watch()`, or a `LiveStreamVideoView`). Call it on the main thread.
 */
suspend fun RealtimeCalls.stream(name: String, video: Boolean): LiveStream {
    val stream = LiveStream(this, name, video)
    stream.join()
    return stream
}

/**
 * Joins the live stream in a token from the app's own server (its `streamToken()`), which decided who this user is in it. A
 * token lasts ten minutes, so `getToken` asks your route for one, and is asked again as each runs out. Your route may then
 * say no. The calls route isn't asked. Throws its code: `bad_token`, or what a stream's join throws. Call it on the main
 * thread.
 */
suspend fun RealtimeCalls.joinStream(getToken: suspend () -> String): LiveStream = joinStream(video = true, getToken)

/** The same, with `video = false` for an app that shows only the chat or the stream's details. Call it on the main thread. */
suspend fun RealtimeCalls.joinStream(video: Boolean, getToken: suspend () -> String): LiveStream {
    val first = CallTokens.toWatch(getToken())
    return joinedStream(first, video) { CallTokens.toWatch(getToken()).token }
}

/**
 * Joins the live stream in one token from the app's own server, for a stay shorter than the token: `expired` is what it
 * comes to when it would need another, thrown here for a token that has already run out, and `onEnded`'s reason later on.
 * Call it on the main thread.
 */
suspend fun RealtimeCalls.joinStream(token: String, video: Boolean = true): LiveStream =
    joinedStream(CallTokens.toWatch(token), video) { throw CallException("expired") }

private suspend fun RealtimeCalls.joinedStream(made: MadeStream, video: Boolean, next: suspend () -> String): LiveStream {
    val stream = LiveStream(this, made.name, video, CallTokens.tokens(made.token, next))
    stream.join()
    return stream
}
