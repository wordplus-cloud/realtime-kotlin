package cloud.wordplus.realtime.calls

import android.content.Context
import android.content.Intent
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import cloud.wordplus.realtime.WordPlusModulesApi
import io.livekit.android.room.MeshCaps
import io.livekit.android.room.MeshCodecPolicy
import io.livekit.android.room.MeshSettings
import io.livekit.android.room.MeshWait
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Where a group call is: connecting, connected, reconnecting, or ended. */
enum class GroupCallState(val wire: String) { CONNECTING("connecting"), CONNECTED("connected"), RECONNECTING("reconnecting"), ENDED("ended") }

/** How a connection is, as the media server rates it. */
enum class CallConnectionQuality {
    EXCELLENT, GOOD, POOR, LOST, UNKNOWN;

    internal companion object {
        fun of(quality: ConnectionQuality?): CallConnectionQuality = when (quality) {
            ConnectionQuality.EXCELLENT -> EXCELLENT
            ConnectionQuality.GOOD -> GOOD
            ConnectionQuality.POOR -> POOR
            ConnectionQuality.LOST -> LOST
            else -> UNKNOWN
        }
    }
}

/**
 * Someone else in a group call: the room's name for them (`user_<id>`), their user id, what the app's server said about
 * them, opened, and what they send.
 */
data class GroupPerson(
    val identity: String,
    val user: String?,
    val info: JsonElement,
    val camera: Boolean,
    val microphone: Boolean,
    val speaking: Boolean,
    /** Whether they share their screen: `GroupVideoView.show(identity, call, GroupVideoSource.SCREEN)` draws it. */
    val screen: Boolean = false,
    /** Whether they may send their camera, microphone and screen: the rule of the app, or what its server changed since. */
    val publish: Boolean = true,
    /**
     * Whether their camera is changing codec (a browser moving to the codec its computer sends best): it is away for a
     * moment, not turned off, so a screen shows a placeholder such as "Optimizing video quality..." where the camera was
     * rather than a camera turned off. From their word until a new camera of theirs arrives ([camera] true again with a
     * new track), their camera is turned off, or the media server's failsafe has passed (10 seconds unless it says
     * otherwise). False by default.
     */
    val optimizing: Boolean = false,
    /** How their connection is, as the media server rates it: `onPeople` fires when it changes. */
    val quality: CallConnectionQuality = CallConnectionQuality.UNKNOWN,
)

/** What this app may do in a group call, as the media server holds it now. */
data class GroupPermissions(
    /** Send its camera, microphone and screen. */
    val publish: Boolean,
    /** Get those of the others. */
    val subscribe: Boolean,
    /**
     * Host the call, as its rule's `admin` said, read from the room's token: [GroupCall.moderate] mutes, lets speak and
     * removes the others. False for everyone else.
     */
    val admin: Boolean = false,
)

/**
 * What a host does to someone else in a group call ([GroupCall.moderate]): `mute` turns their microphone off, which they
 * may turn on again, `speak` lets a listener send, `listen` makes a speaker a listener, `remove` takes them out.
 * `spotlight` puts someone, the host too, on the main tiles for everyone (up to four, the room's [GroupCall.spotlight]),
 * and `unspotlight` takes them off, or everyone with no user.
 */
enum class ModerationAct(val wire: String) {
    MUTE("mute"), SPEAK("speak"), LISTEN("listen"), REMOVE("remove"), SPOTLIGHT("spotlight"), UNSPOTLIGHT("unspotlight"),
}

/**
 * A group call's recording, as WordPlus Cloud tells it: its id, and its state (`recording`, `stopping`, `finishing`,
 * `complete` or `failed`).
 */
data class GroupRecording(val id: String, val state: String)

/** What a room's token says of its holder, read without the room itself. */
@OptIn(ExperimentalEncodingApi::class)
internal object GroupRoom {
    /**
     * Whether a track's encryption says it failed, as the browser's `EncryptionError` tells it. A key missing for a moment
     * as someone joins says nothing yet.
     */
    fun encryptionFailed(state: E2EEState) =
        state == E2EEState.ENCRYPTION_FAILED || state == E2EEState.DECRYPTION_FAILED || state == E2EEState.INTERNAL_ERROR

    private val url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** The media server's identities as user ids (`user_<id>`), leaving out any that names no user. */
    fun users(identities: List<String>): List<String> = identities.filter { it.startsWith("user_") }.map { it.removePrefix("user_") }

    /**
     * Whether a room's token makes its holder the host: its media grant's `roomAdmin`, which the realtime server writes
     * from the app's rule. False for a token that can't be read.
     */
    fun admin(token: String): Boolean = runCatching {
        claims(token)?.get("video")?.jsonObject?.get("roomAdmin")?.jsonPrimitive?.takeIf { !it.isString }?.booleanOrNull == true
    }.getOrDefault(false)

    /**
     * Whether to offer Start Recording ([GroupCall.canRecord]), in a group call the app's server names ([moderated]) whose
     * media the recorder can read: for one joined by a token of the app's own server, whoever its [proofs] let record; for
     * one its rule lets in, whoever the calls route's answer said may record it ([mayRecord], as the browser's group calls
     * read it).
     */
    fun offersRecording(moderated: Boolean, encrypted: Boolean, proofs: GroupProofs?, mayRecord: Boolean): Boolean =
        moderated && !encrypted && (proofs?.let { it.recorder != null } ?: mayRecord)

    /** What a room's token says, or null for one that can't be read. */
    private fun claims(token: String): JsonObject? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return null
        Json.parseToJsonElement(String(url.decode(parts[1]))).jsonObject
    }.getOrNull()

    /**
     * Who a room's token makes its holder: `sub`, the identity the media server will give them, and `metadata`, their info as
     * the app's server gave it, still sealed. Null and nothing for a token that can't be read.
     */
    fun holder(token: String): Pair<String?, JsonElement> {
        val claims = claims(token) ?: return null to JsonNull
        fun text(key: String) = (claims[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return text("sub") to (text("metadata")?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
    }

    /**
     * Who is in a room's spotlight, as its metadata says (the realtime server's `spotlight`): the identities, once each,
     * at most four, as the browser's group calls read them (spotlightOf()).
     */
    fun spotlight(metadata: String?): List<String> = runCatching {
        if (metadata.isNullOrEmpty()) return emptyList()
        val list = Json.parseToJsonElement(metadata).jsonObject["spotlight"] as? JsonArray ?: return emptyList()
        list.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString && value.content.isNotEmpty() }?.content }.distinct().take(4)
    }.getOrDefault(emptyList())

    /**
     * Why the room let this app go, as `onEnded` says it: `removed` when the app's server took it out (`remove`, the
     * media server's PARTICIPANT_REMOVED), `closed` when it ended the call for everyone (`end`, ROOM_DELETED), else
     * `disconnected`, as the browser's group calls say them (realtime-client a738a57, disconnectedBy).
     */
    fun endReason(reason: DisconnectReason): String = when (reason) {
        DisconnectReason.PARTICIPANT_REMOVED -> "removed"
        DisconnectReason.ROOM_DELETED -> "closed"
        else -> "disconnected"
    }
}

/**
 * A join waiting for a place on the mesh, and going on by itself: until when it waits, in milliseconds since 1970, and why
 * (`busy`, `slot`, `full` or `unreachable`), as the browser's group calls and streams tell it.
 */
data class CallWait(val until: Long, val reason: String) {
    internal constructor(wait: MeshWait) : this(wait.until, wait.reason.wire)
}

/**
 * What went wrong in a group call that goes on: `publish_failed`, what this app sends could not go out, its [source]
 * `microphone` or `camera`, or `encryption_failed`, a track could not be encrypted or decrypted, with the system's word in
 * [message].
 */
data class GroupCallFailure(val code: String, val source: String? = null, val message: String? = null)

/** A group call made from a call between two people: what ringing someone into it needs. */
internal data class GroupOf(val id: String, val name: String, val type: CallType, val pair: Any?, val member: String?)

/**
 * Someone rung into a group call, as everyone in it hears of it: `ringing`, then `answered`, `rejected`, `busy`,
 * `no_answer` or `cancelled`, the relay's words. On the main thread.
 */
class CallInvite internal constructor(
    val id: String,
    /** The group call it rings into: the id of the call it was made from. */
    val group: String,
    /** Who rang, for a ring this app heard of. Null for one it rang itself, unless the relay told of it first. */
    val from: String? = null,
    val to: String,
    state: String,
) {
    internal companion object {
        val STATES = setOf("ringing", "answered", "rejected", "busy", "no_answer", "cancelled")
    }

    var state: String = state
        private set

    private val stateHandlers = CallHandlers<String>()
    private var canceller: (suspend () -> Unit)? = null

    /** The state as it changes. */
    fun onState(handler: (String) -> Unit): CallListener = stateHandlers.add(handler)

    /** Takes a handler off. */
    fun off(listener: CallListener) = stateHandlers.remove(listener)

    /** Whether `cancel()` can call it off now: it still rings, and this app rang it. */
    val canCancel: Boolean get() = state == "ringing" && canceller != null

    /** Calls it off while it rings: from the app that rang only, `forbidden` for anyone else. Throws its code. */
    suspend fun cancel() {
        if (state != "ringing") return
        (canceller ?: throw CallException("forbidden"))()
    }

    /** The app that rang can call it off. */
    internal fun own(canceller: suspend () -> Unit) {
        this.canceller = canceller
    }

    internal fun update(next: String) {
        // A ring that ended stays ended, whichever word of it arrives last.
        if (state == next || state != "ringing") return
        state = next
        stateHandlers.fire(next)
    }
}

/**
 * A room on the mesh: a group call (`rt.calls(context).group(name)`), or a call between two people that became one when
 * either added someone. End to end encrypted when the app's server gives the room a key (a call's rooms always): every
 * frame is encrypted here with the key everyone in the room shares, as the browser's rooms do, so the mesh forwards what
 * it can't read. Nothing is sent into a group call until `microphone` or `camera` say so; a call that became one goes on
 * sending what it sent. `onState`, `onPeople` (someone arrived, left, or what they send or may send changed),
 * `onPermissions` (the app's server changed what this app may do), `onEnded`. On the main thread.
 */
class GroupCall internal constructor(
    context: Context,
    private val owner: RealtimeCalls,
    val name: String,
    private val link: GroupOf?,
    key: String?,
    microphone: Boolean,
    camera: Boolean,
    /**
     * A group call the app's server names (`rt.calls(context).group(name)`), whose host acts through its calls route: not
     * a call that became one, nor the room a call moved to.
     */
    private val moderated: Boolean = false,
    /**
     * For one joined by a token of the app's own server (`joinGroupCall`): what it gave this user to host it or record it,
     * which the calls route checks. Null for one its rule lets in.
     */
    private val proofs: GroupProofs? = null,
    /**
     * For one its rule lets in: whether the calls route's answer said this user may record it (its `record`, which the server
     * packages give whoever the rule answers `record`, else its host). One joined by a token goes by [proofs].
     */
    private val mayRecord: Boolean = false,
) {
    /** The group call's id, for a call between two people that became one; null for any other room. */
    val group: String? = link?.id

    /** Whether everyone's media in it is encrypted end to end, with a key only the app's server gives out. */
    val encrypted: Boolean = !key.isNullOrEmpty()

    var state = GroupCallState.CONNECTING
        private set

    /**
     * While a join waits for a place on the mesh: until when, and why. The state stays `CONNECTING`, and the join goes on
     * by itself. Null at any other time.
     */
    var waiting: CallWait? = null
        private set
    var endReason: String? = null
        private set

    /** Everyone else in the room. */
    var people: List<GroupPerson> = emptyList()
        private set

    /**
     * This app's own user in the room, as the others see them: their info as the app's server gave it. Null until
     * connected, and once it has ended. `self` on the browser's, as Swift cannot name it.
     */
    val me: GroupPerson?
        get() = if (state == GroupCallState.ENDED) null else person(room.localParticipant)

    /**
     * This app's own user for a screen to draw from the first moment, their identity and info: [me] once the room has
     * connected, and what the room's token says before that, as the room knows nobody until then, and a tile with no name
     * reads as a stranger.
     */
    @WordPlusModulesApi
    val own: Pair<String, JsonElement>
        get() {
            val joined = me
            val info = joined?.info ?: JsonNull
            return (joined?.identity ?: tokenHolder.first ?: "") to if (info != JsonNull) info else owner.rt.keyring.openJson(tokenHolder.second) ?: JsonNull
        }

    /** Who the room's token makes this app's user, from the moment the join has its place ([namedBy]). */
    @Volatile
    private var tokenHolder: Pair<String?, JsonElement> = null to JsonNull

    /** Takes who this app's user is from the room's token, before the room connects: [own]. */
    internal fun namedBy(token: String) {
        tokenHolder = GroupRoom.holder(token)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screenHandlers = CallHandlers<Boolean>()
    private val stateHandlers = CallHandlers<GroupCallState>()
    private val waitingHandlers = CallHandlers<CallWait>()
    private val peopleHandlers = CallHandlers<Unit>()
    private val permissionHandlers = CallHandlers<GroupPermissions>()
    private val inviteHandlers = CallHandlers<CallInvite>()
    private val errorHandlers = CallHandlers<GroupCallFailure>()
    private val spotlightHandlers = CallHandlers<List<String>>()
    private val recordingHandlers = CallHandlers<Boolean>()
    private val qualityHandlers = CallHandlers<CallConnectionQuality>()
    private val endHandlers = CallHandlers<String>()
    private var microphoneOn = microphone
    private var cameraOn = camera

    /**
     * Whether the room's token makes this app its host (`video.roomAdmin`, the rule's `admin`): the room's own permissions
     * don't carry it.
     */
    @Volatile
    private var grantedAdmin = false
    private val media = CallMedia.get(context)
    private val appContext = context.applicationContext

    /**
     * The codec the camera goes out in, what goes behind it and whether this device encodes it in hardware
     * ([useCodecs]): the built-in rule until the media server's settings or policy say otherwise. What this device
     * encodes and decodes, and whether the room is on the mesh, for the codecs the settings choose during the call.
     * On the main thread.
     */
    private var cameraCodec = "vp8"
    private var cameraBackup: String? = null
    private var cameraHardware = false
    private var codecCaps: CallCodecCaps? = null
    private var onMesh = false

    /**
     * The publish settings the running camera went out under ([GroupShapes.cameraPrint]), null with none published, and
     * the re-publish a revision with `applyNow` asked for, at its moment. On the main thread.
     */
    private var cameraPrint: String? = null
    private var settingsRepublish: Job? = null

    /**
     * Cameras changing codec, by who said so ([CameraSwitch.TOPIC]), the look that ends one at its failsafe, and the ear
     * on each camera whose next frame ends one ([watchCameraSwitches]). On the main thread.
     */
    private val cameraSwitch = CameraSwitch()
    private var cameraSwitchLook: Job? = null
    private val cameraSwitchEars = HashMap<String, CameraEar>()

    /** The microphone under its ceiling on a slow upload ([reviewMicCeiling]), and what each stream of the video going out had encoded at the last reading. On the main thread. */
    private val micCeiling = CallMicCeiling(CallMicCeiling.ROOM_SLOW_BELOW, CallMicCeiling.ROOM_BACK_AT)
    private var micWatch: Job? = null
    private val micFrames = HashMap<String, Long>()
    private val micLoss = CallMicLoss()
    private var micDownForLoss = false
    private var micDownAlone = false

    /**
     * The picture giving way to the sound a step at a time once the microphone is down ([CallPictureSteps]), and the cap
     * the camera is under for its codec ([GroupShapes.cameraCap]), which a step's ceiling goes beside. On the main thread.
     */
    private val picture = CallPictureSteps()
    private var cameraCodecCap = MeshCaps()

    /** The upload this app sends on judged for a jam, from the same readings ([CallUploadJam]). On the main thread. */
    private val uploadJam = CallUploadJam()
    private val jamHandlers = CallHandlers<Boolean>()

    // The mesh decides who needs what and stops sending what nobody looks at. A shared key never ratchets on its own:
    // this SDK's window is 16 and the web pins 0, the one default that differs, so a phone and a browser in one
    // encrypted room read each other.
    private val room: Room = LiveKit.create(
        context.applicationContext,
        options = RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            e2eeOptions = key?.takeIf { it.isNotEmpty() }?.let { shared ->
                E2EEOptions(BaseKeyProvider(ratchetWindowSize = 0).apply { setSharedKey(shared, 0) })
            },
        ),
        overrides = LiveKitOverrides(eglBase = CallMedia.get(context).eglBase),
    )

    init {
        scope.launch { room.events.collect { handle(it) } }
    }

    /** The state as it changes. */
    fun onState(handler: (GroupCallState) -> Unit): CallListener = stateHandlers.add(handler)

    /** Someone arrived or left, or what they send changed: read `people`. */
    fun onPeople(handler: () -> Unit): CallListener = peopleHandlers.add({ handler() })

    /**
     * The room ended for this app, with the reason: `left`, `removed` (the app's server took this user out), `closed`
     * (it ended the call for everyone), `disconnected` …
     */
    fun onEnded(handler: (String) -> Unit): CallListener = endHandlers.add(handler)

    /**
     * The app's server changed what this app may do (`permit`): made a speaker, it sends what `microphone` and `camera`
     * then say. Back to listening, the media server takes down what it sent, and both turn false.
     */
    fun onPermissions(handler: (GroupPermissions) -> Unit): CallListener = permissionHandlers.add(handler)

    /** Who a host put on the main tiles for everyone changed ([spotlight]), or was on as this app joined. */
    fun onSpotlight(handler: (List<String>) -> Unit): CallListener = spotlightHandlers.add(handler)

    /** A join waits for a place on the mesh: until when, and why. It goes on by itself, `onState` saying `CONNECTED` then. */
    fun onWaiting(handler: (CallWait) -> Unit): CallListener = waitingHandlers.add(handler)

    /** The room's recording flag changed ([isRecording]), whoever started or stopped it. */
    fun onRecording(handler: (Boolean) -> Unit): CallListener = recordingHandlers.add(handler)

    /** Someone is rung into this group call, by anyone in it: follow them with `onState`. */
    fun onInvite(handler: (CallInvite) -> Unit): CallListener = inviteHandlers.add(handler)

    /**
     * What went wrong in a call that goes on: the camera or microphone could not go out (`publish_failed`), or a track
     * could not be encrypted or decrypted (`encryption_failed`).
     */
    fun onError(handler: (GroupCallFailure) -> Unit): CallListener = errorHandlers.add(handler)

    /** Takes a handler off. */
    fun off(listener: CallListener) {
        stateHandlers.remove(listener)
        waitingHandlers.remove(listener)
        peopleHandlers.remove(listener)
        permissionHandlers.remove(listener)
        inviteHandlers.remove(listener)
        errorHandlers.remove(listener)
        spotlightHandlers.remove(listener)
        recordingHandlers.remove(listener)
        qualityHandlers.remove(listener)
        jamHandlers.remove(listener)
        endHandlers.remove(listener)
    }

    /**
     * Who a host put on the main tiles for everyone, in the order they were, by their user ids, as [spotlightOn] takes
     * them: the room's state, so an app that joins later has it too. Empty while nobody is in it.
     */
    val spotlit: List<String>
        get() = GroupRoom.users(spotlight)

    /** Who a host put on the main tiles for everyone changed, or was on as this app joined, by their user ids. */
    fun onSpotlit(handler: (List<String>) -> Unit): CallListener = spotlightHandlers.add({ handler(GroupRoom.users(it)) })

    /**
     * The same by the media server's identity ([GroupPerson.identity], `user_<id>`, this app's own user's too): [spotlit]
     * names users, as a host's acts take them.
     */
    val spotlight: List<String>
        get() = GroupRoom.spotlight(room.metadata)

    /** How this app's own connection is, as the media server rates it ([onQuality] when it changes). */
    val quality: CallConnectionQuality
        get() = CallConnectionQuality.of(room.localParticipant.connectionQuality)

    /** This app's own connection changed, as the media server rates it. */
    fun onQuality(handler: (CallConnectionQuality) -> Unit): CallListener = qualityHandlers.add(handler)

    /**
     * Whether the upload this app sends on is jammed: what it sends waits seconds on the person's own line, or gets no
     * answer at all, while what is sent to it still arrives. Others may then see its video freeze or hear its sound
     * break up, and the one who can mend it is the person: whatever else uploads on their network. While it is, a call
     * screen shows its connection as poor whatever [quality] says, as the media server rates such a line good.
     */
    var uploadJammed: Boolean = false
        private set

    /** This app's upload became jammed, or no longer is ([uploadJammed]). */
    fun onUploadJam(handler: (Boolean) -> Unit): CallListener = jamHandlers.add(handler)

    /** Sends this app's camera, or stops: as setting [camera], in the words of a live stream and of the web. */
    @JvmName("sendCamera")
    fun setCamera(on: Boolean) {
        camera = on
    }

    /** Sends this app's microphone, or stops: as setting [microphone], in the words of a live stream and of the web. */
    @JvmName("sendMicrophone")
    fun setMicrophone(on: Boolean) {
        microphone = on
    }

    /** A host turns someone's microphone off, which they may turn on again. Throws as [moderate] does. */
    suspend fun mute(user: String): Boolean = moderate(ModerationAct.MUTE, user)

    /** A host takes someone out of the call. The tokens signed for them before do not take them back in. */
    suspend fun remove(user: String): Boolean = moderate(ModerationAct.REMOVE, user)

    /** A host lets a listener send their camera, microphone and screen. */
    suspend fun letSpeak(user: String): Boolean = moderate(ModerationAct.SPEAK, user)

    /** A host makes a speaker a listener: what they send stops. */
    suspend fun makeListener(user: String): Boolean = moderate(ModerationAct.LISTEN, user)

    /** A host puts someone, themselves too, on the main tiles for everyone, up to four. A fifth throws `spotlight_full`. */
    suspend fun spotlightOn(user: String): Boolean = moderate(ModerationAct.SPOTLIGHT, user)

    /** A host takes someone off the main tiles, or everyone with no user. */
    suspend fun spotlightOff(user: String? = null): Boolean = moderate(ModerationAct.UNSPOTLIGHT, user)

    /**
     * What this app may do in the room now: the rule of the app, or what its server changed since, and nothing once the call
     * is over. Hosting is the room's token's alone ([GroupPermissions.admin]).
     */
    val can: GroupPermissions
        get() {
            if (state == GroupCallState.ENDED) return GroupPermissions(publish = false, subscribe = false, admin = grantedAdmin)
            // The room says nothing until it has joined, and what the app's rule let in stands until then.
            val joining = state == GroupCallState.CONNECTING
            val permissions = room.localParticipant.permissions
            return GroupPermissions(publish = permissions?.canPublish ?: joining, subscribe = permissions?.canSubscribe ?: joining, admin = grantedAdmin)
        }

    /**
     * What a host does to someone else in the call ([ModerationAct]), through the app's calls route, which asks the
     * call's rule again and takes it from a host alone (its `admin`). Whether it changed anything. [user] is null only
     * for `unspotlight`, which then takes everyone off the main tiles. Throws its code: `forbidden` for an app that isn't
     * the host, `not_moderated` for a room that isn't a group call the app's server names (a call that became one, or the
     * room a call moved to), `bad_request` for nobody named with any other act, `spotlight_full` for a fifth person in
     * the spotlight.
     */
    suspend fun moderate(act: ModerationAct, user: String?): Boolean {
        if (!moderated) throw CallException("not_moderated")
        if (user == null && act != ModerationAct.UNSPOTLIGHT) throw CallException("bad_request")
        return owner.moderate(name, act, user, proofs?.host)
    }

    /**
     * Starts recording the call, through the app's calls route (`{ call, record: "start" }`), which asks the call's rule
     * again and takes it from whoever it lets record (its `record`, else its host): the recording, its id and state.
     * Throws its code: `forbidden` for anyone else, `not_moderated` for a room that isn't a group call the app's server
     * names, or WordPlus Cloud's refusal (`no_credits`, `not_included`, `already_recording` …). Its files go to the app's
     * server (the server package's `recordings`). The call screen offers it to whoever [canRecord] says.
     */
    suspend fun startRecording(): GroupRecording {
        if (!moderated) throw CallException("not_moderated")
        return owner.record(name, "start", null, proofs?.recorder)
    }

    /** Stops the recording [id] of the call (`{ call, record: "stop", recording }`): the recording, being stopped. */
    suspend fun stopRecording(id: String): GroupRecording {
        if (!moderated) throw CallException("not_moderated")
        return owner.record(name, "stop", id, proofs?.recorder)
    }

    /**
     * Whether the room says it is being recorded, by whoever started it: the media server's flag, which late joiners
     * have too.
     */
    val isRecording: Boolean get() = room.isRecording

    /**
     * Whether to offer Start Recording to this user, in a group call the app's server names whose media the recorder can
     * read: whoever the calls route's answer said may record it (its `record`, which the server packages give whoever the
     * call's rule answers `record`, else its host), as the browser's group calls read it. For one joined by a token of the
     * app's own server, whoever that token lets record, host or not. The call's rule is asked again as it starts.
     */
    val canRecord: Boolean get() = GroupRoom.offersRecording(moderated, encrypted, proofs, mayRecord)

    /** Whether this app is sharing its screen into the room. */
    val sharingScreen: Boolean get() = room.localParticipant.isScreenShareEnabled

    /** This app began or stopped sharing its screen: said once it did, the system's own stop included. */
    fun onScreenShare(handler: (Boolean) -> Unit): CallListener = screenHandlers.add(handler)

    /**
     * Shares this phone's screen into the room, or stops: whether it is shared now. To share, `permission` is the system's
     * answer to `ScreenShare.permissionIntent`, and is good for one share. False before the room is up, for one who may not
     * send, and without an answer. One that could not go out is told by `onError` too (`publish_failed`, `source` `screen`).
     */
    suspend fun setScreenShare(on: Boolean, permission: Intent? = null): Boolean {
        if (state != GroupCallState.CONNECTED && state != GroupCallState.RECONNECTING) return false
        if (!on) {
            val was = sharingScreen
            runCatching { room.localParticipant.setScreenShareEnabled(false) }
            if (was) screenHandlers.fire(false)
            return false
        }
        if (sharingScreen) return true
        if (!can.publish) return false
        permission ?: return false
        beforePublish()
        val params = ScreenShareNotice.roomParams(appContext, permission) { scope.launch { screenHandlers.fire(false) } }
        val shared = runCatching { room.localParticipant.setScreenShareEnabled(true, params) }
        if (!shared.getOrDefault(false)) {
            publishFailed("screen", shared.exceptionOrNull())
            return false
        }
        screenHandlers.fire(true)
        return true
    }

    /** Whether this app's microphone is sent into the room. */
    var microphone: Boolean
        get() = microphoneOn
        set(value) {
            microphoneOn = value
            if (state == GroupCallState.CONNECTED || state == GroupCallState.RECONNECTING) scope.launch { publishMicrophone(value) }
        }

    /** Whether this app's camera is sent into the room. */
    var camera: Boolean
        get() = cameraOn
        set(value) {
            cameraOn = value
            if (state == GroupCallState.CONNECTED || state == GroupCallState.RECONNECTING) scope.launch { publishCamera(value) }
        }

    /**
     * Before this app publishes: until the room's publisher has no section left open by a track taken down, which the
     * publish would take over, an offer asked for to close one, again after two seconds, eight seconds at most, after which
     * it publishes as before ([RoomPublisher]). At once when nothing was taken down. On the main thread.
     */
    private suspend fun beforePublish() {
        if (room.state != Room.State.CONNECTED) return
        val end = System.currentTimeMillis() + 8_000L
        var asked = 0L
        while (state != GroupCallState.ENDED && System.currentTimeMillis() < end) {
            // Told on the room client's thread, and resumed here on the main one.
            val now = suspendCancellableCoroutine<Int> { waiting -> RoomPublisher.look(room) { waiting.resume(it) {} } }
            if (now == RoomPublisher.SETTLED) break
            if (now == RoomPublisher.OPEN && System.currentTimeMillis() - asked >= 2_000L) {
                RoomPublisher.negotiate(room)
                asked = System.currentTimeMillis()
            }
            delay(50)
        }
    }

    /** The microphone sent or stopped, opened and published by the media server's settings as they are now ([GroupShapes]). */
    private suspend fun publishMicrophone(enabled: Boolean) {
        if (enabled) beforePublish()
        val settings = room.meshSettings
        room.localParticipant.audioTrackCaptureDefaults = GroupShapes.microphoneCapture(settings)
        room.localParticipant.audioTrackPublishDefaults = GroupShapes.microphone(encrypted, settings)
        val published = runCatching { room.localParticipant.setMicrophoneEnabled(enabled) }
        if (enabled && !published.getOrDefault(false)) publishFailed("microphone", published.exceptionOrNull())
    }

    /** What this app sends could not go out: told on the main thread, while the call goes on. */
    internal fun publishFailed(source: String, error: Throwable?) {
        fail(GroupCallFailure("publish_failed", source, error?.toString()))
    }

    private fun fail(failure: GroupCallFailure) {
        scope.launch { if (state != GroupCallState.ENDED) errorHandlers.fire(failure) }
    }

    /**
     * The camera sent or stopped, opened and published by the media server's settings as they are now: its capture,
     * codec, layers and the tallest it may go out at in that codec ([GroupShapes]).
     */
    internal suspend fun publishCamera(enabled: Boolean) {
        if (enabled) {
            beforePublish()
            val settings = room.meshSettings
            val capture = GroupShapes.cameraCapture(settings)
            val lines = minOf(capture.width, capture.height)
            room.localParticipant.videoTrackCaptureDefaults = room.localParticipant.videoTrackCaptureDefaults.copy(
                captureParams = capture,
                position = if (frontCamera) CameraPosition.FRONT else CameraPosition.BACK,
            )
            room.localParticipant.videoTrackPublishDefaults = GroupShapes.camera(cameraCodec, cameraBackup, lines, settings)
            // beside the step the picture is at for the sound's sake, which a camera turned on later comes under too
            cameraCodecCap = GroupShapes.cameraCap(cameraHardware, lines, settings)
            room.setProductCameraCaps(GroupShapes.cameraCap(cameraCodecCap, picture.step))
        }
        val result = runCatching { room.localParticipant.setCameraEnabled(enabled) }
        val published = result.getOrDefault(false)
        if (enabled && !published) publishFailed("camera", result.exceptionOrNull())
        cameraPrint = if (enabled && published) GroupShapes.cameraPrint(room.meshSettings) else null
    }

    /**
     * The camera out again with the settings it is under now, as the web moves a camera to another codec (the realtime
     * client's switchCameraCodec): "optimizing" to everyone, so the others show the tile as being optimized rather than
     * gone; the camera published again within `tune.republishTimeoutS`, else again in the codec it had, so it is never
     * lost; and "ready" either way.
     */
    private suspend fun republishCamera() {
        if (room.state != Room.State.CONNECTED || !cameraOn || state == GroupCallState.ENDED) return
        val before = Triple(cameraCodec, cameraBackup, cameraHardware)
        val timeoutMs = (room.meshSettings.int("tune.republishTimeoutS") ?: 8) * 1000L

        announceCamera("optimizing")
        unpublishCamera()
        publishCamera(true)
        if (!cameraPublished(timeoutMs)) {
            cameraCodec = before.first
            cameraBackup = before.second
            cameraHardware = before.third
            unpublishCamera()
            publishCamera(true)
            cameraPublished(timeoutMs)
        }
        announceCamera("ready")
    }

    /** The camera taken out of the room, not muted, so the next publish goes out in its new shape. */
    private fun unpublishCamera() {
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track ?: return
        runCatching { room.localParticipant.unpublishTrack(track) }
    }

    private suspend fun cameraPublished(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.let { it.track != null && !it.muted } != true) delay(200)
            true
        } ?: false

    /** A word on the camera's move to everyone ([CameraSwitch.TOPIC]), as the web says it; one that can't go out within a second doesn't hold the move up. */
    private suspend fun announceCamera(word: String) {
        withTimeoutOrNull(1_000) {
            runCatching {
                room.localParticipant.publishData(org.json.JSONObject().put("state", word).toString().toByteArray(), topic = CameraSwitch.TOPIC)
            }
        }
    }

    /**
     * Rings someone into this group call, one made from a call between two people: they arrive in `people` once they
     * answer. Takes a token from your own server, `callToken(user, to, { into: group.ref })`, or a user id, which a rule of
     * your server decides. A string that opens as a call's token is a token. Throws its code: `not_a_call` for any other
     * room, `forbidden` for a token of another call, or the app's server's or the realtime server's refusal.
     */
    suspend fun add(user: String): CallInvite {
        val of = link ?: throw CallException("not_a_call")
        if (state == GroupCallState.ENDED) throw CallException("ended")
        return owner.addTo(of, user)
    }

    /**
     * What the app sends your route to ring someone into this call, when it was a call between two people: null for a group
     * call joined by its name or a token. `realtime.callToken(user, to, { into: ref })`.
     */
    val ref: String? get() = link?.let { CallTokens.ref(it) }

    /** The rings into this group call this app heard of, still ringing or ended. On the main thread. */
    val invites: List<CallInvite>
        get() = link?.let { owner.invites(it.id) } ?: emptyList()

    /** Someone is rung into this group call. On the main thread. */
    internal fun invited(invite: CallInvite) {
        if (state != GroupCallState.ENDED) inviteHandlers.fire(invite)
    }

    /** Leaves the room. */
    fun leave() {
        SystemCalls.endingHere(group = this)
        finish("left")
    }

    /** Whether the camera sent is the front one, or while none is sent, the one that opens next. */
    var frontCamera = true
        private set

    /** The side the next camera opens on, while none is sent: a camera on turns round with `flipCamera()`. Nothing opens. */
    fun preferCamera(front: Boolean) {
        if (cameraOn) return
        frontCamera = front
    }

    /** The calls it is one of, for whom the screen offers to add. */
    @WordPlusModulesApi
    val calls: RealtimeCalls get() = owner

    /** A device check's camera, opened on this side alone and never published. `stopCameraCheck()` lets it go. */
    @WordPlusModulesApi
    fun startCameraCheck(front: Boolean): livekit.org.webrtc.VideoTrack? {
        stopCameraCheck()
        val track = runCatching {
            room.localParticipant.createVideoTrack(
                name = "preview",
                options = room.localParticipant.videoTrackCaptureDefaults.copy(position = if (front) CameraPosition.FRONT else CameraPosition.BACK),
            ).also { it.startCapture() }
        }.onFailure { CallLog.write("room preview camera would not start: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() ?: return null
        cameraCheck = track
        return track.rtcTrack
    }

    @WordPlusModulesApi
    fun stopCameraCheck() {
        val track = cameraCheck ?: return
        cameraCheck = null
        runCatching {
            track.stopCapture()
            track.dispose()
        }
    }

    private var cameraCheck: LocalVideoTrack? = null
    private var levelTrack: LocalAudioTrack? = null
    private val levelSink = CallMicLevel()

    /** How loud this app's microphone is while it is sent, from 0 to 1, for a meter: null while it is not. */
    fun microphoneLevel(): Float? {
        val track = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
        if (track !== levelTrack) {
            levelTrack?.let { old -> runCatching { old.removeSink(levelSink) } }
            levelTrack = track
            track?.addSink(levelSink)
        }
        return if (track == null) null else levelSink.level()
    }

    /** Turns the camera round, the front one to the back and back, while it is sent. */
    fun flipCamera() {
        if (!cameraOn || state == GroupCallState.ENDED) return
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        frontCamera = !frontCamera
        runCatching { track.switchCamera(position = if (frontCamera) CameraPosition.FRONT else CameraPosition.BACK) }
    }

    /** The LiveKit room, for the SDK's call screens (cloud.wordplus:realtime-ui), which draw everyone's tracks from it. */
    @WordPlusModulesApi
    val liveKitRoom: Room get() = room

    /** Someone's picture as the room has it, for `GroupVideoView`: none when they don't send it. */
    internal fun videoTrack(identity: String, source: GroupVideoSource): io.livekit.android.room.track.VideoTrack? {
        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == identity } ?: return null
        val wanted = if (source == GroupVideoSource.SCREEN) Track.Source.SCREEN_SHARE else Track.Source.CAMERA
        val publication = participant.trackPublications.values.firstOrNull { it.source == wanted && !it.muted }
        return publication?.track as? io.livekit.android.room.track.VideoTrack
    }

    /** This app's own camera, while it is sent. */
    internal val ownCameraTrack: io.livekit.android.room.track.VideoTrack?
        get() {
            val publication = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
            return if (publication == null || publication.muted) null else publication.track as? io.livekit.android.room.track.VideoTrack
        }

    /**
     * The camera's codec and what goes behind it, as the browser's group calls take them (cameraCodecFor(), backupFor()):
     * the media server's settings once a settings document is in use (`camera.codecRules`, `camera.codecs`,
     * `camera.backup`), else its policy, else the built-in rule. What is already published stays as it went out.
     */
    private fun useCodecs(policy: MeshCodecPolicy?, settings: MeshSettings, caps: CallCodecCaps, onMesh: Boolean) {
        val fromSettings = settings.rev > 0
        val tablet = appContext.resources.configuration.smallestScreenWidthDp >= 600
        val list = if (fromSettings) GroupShapes.cameraCodecs(settings, tablet) else policy?.camera
        val backupWord = if (fromSettings) settings.word("camera.backup") else policy?.backup
        val codec = CallCodecs.roomPublishCodec(caps, encrypted, onMesh, list)
        cameraCodec = codec
        cameraBackup = CallCodecs.roomBackupCodec(codec, encrypted, backupWord)
        cameraHardware = caps.families["video/${codec.uppercase()}"]?.first?.hw ?: false
    }

    /**
     * How long a camera changing codec is shown so at most: the media server's `tune.failsafeS`, from its settings once a
     * document is in use, else from its policy.
     */
    private fun failsafe(settings: MeshSettings, policy: MeshCodecPolicy?): Long =
        CameraSwitch.failsafeFor(if (settings.rev > 0) settings.number("tune.failsafeS") else CameraSwitch.tune("failsafeS", policy))

    /**
     * The media server's settings changed during the call: the camera's codecs for its next publish and the failsafe;
     * and a revision with `applyNow` re-publishes the camera, at this device's moment in the next minute
     * ([MeshSettings.republishDelayMs]).
     */
    private fun settingsChanged(settings: MeshSettings, changed: Set<String>) {
        if (state == GroupCallState.ENDED) return
        val caps = codecCaps ?: return
        useCodecs(room.meshCodecPolicy, settings, caps, onMesh)
        cameraSwitch.failsafeMs = failsafe(settings, room.meshCodecPolicy)
        CallCodecs.dropSettings = settings

        // a camera that went out under other publish settings than these: one published at this join, or since,
        // already has them
        val print = cameraPrint
        if (!settings.applyNow || !GroupShapes.touchesPublish(changed) || print == null || print == GroupShapes.cameraPrint(settings)) return
        val delayMs = MeshSettings.republishDelayMs(room.localParticipant.identity?.value ?: "")
        settingsRepublish?.cancel()
        settingsRepublish = scope.launch {
            delay(delayMs)
            republishCamera()
        }
    }

    /** Joins the room at its place: the mesh's address, a token for it, and TURN for the way there. */
    internal suspend fun connect(server: String, token: String, iceServers: JSONArray?) {
        val ice = CallSession.iceServers(iceServers)
        // H.264 from hardware with VP8 behind it, VP8 alone in an encrypted room, until the media server's settings or
        // policy say otherwise once joined. The join names, as the browser's does, the codecs this device encodes and
        // decodes in hardware, and this module; its timeouts, reconnect waits and budget are the settings this device
        // last had from the server (the schema's defaults, the browsers', on a first call).
        val caps = media.codecCaps
        val onMesh = CallCodecs.onMesh(server)
        grantedAdmin = GroupRoom.admin(token)
        codecCaps = caps
        this.onMesh = onMesh
        useCodecs(null, MeshSettings.NONE, caps, onMesh)
        val cached = GroupShapes.cached(appContext, server)
        GroupShapes.reconnectPolicy(cached)?.let { room.reconnectPolicy = it }
        room.connect(server, token, GroupShapes.connectOptions(cached, ice, caps) { wait -> scope.launch { heardWait(CallWait(wait)) } })
        if (state == GroupCallState.ENDED) {
            room.disconnect()
            return
        }
        val settings = room.meshSettings
        useCodecs(room.meshCodecPolicy, settings, caps, onMesh)
        // A camera changing codec is shown so for the node's failsafe at most.
        cameraSwitch.failsafeMs = failsafe(settings, room.meshCodecPolicy)
        CallCodecs.dropSettings = settings
        if (microphoneOn) publishMicrophone(true)
        if (cameraOn) publishCamera(true)
        setState(GroupCallState.CONNECTED)
        refresh()
        // A spotlight already on as this app joins, as the room had it.
        spotlight.takeIf { it.isNotEmpty() }?.let { spotlightHandlers.fire(it) }
        micWatch?.cancel()
        micWatch = scope.launch {
            while (isActive) {
                // Twice within the wait the ceiling holds a reading for.
                delay(5_000L)
                reviewMicCeiling()
            }
        }
    }

    /**
     * The microphone under its ceiling on a slow upload ([CallMicCeiling], with a room's marks), as the browsers'
     * `watchRoomMic` keeps theirs: the estimate read off the publisher's connection, while video goes out on it, which is
     * what the ceiling waits for; and with no video going out, on its node's count of it lost alone, from which it stays
     * down while it is alone. While it is down each reading asks it of the track again, so a reconnect's new sender comes
     * down too.
     *
     * The same readings carry two things more, as the browsers' do. Once the microphone is down and the sound still
     * suffers while the person talks, the camera gives way a step at a time and comes back as the sound holds
     * ([CallPictureSteps]). And the connection it all goes out on is judged for a jam ([CallUploadJam]), told through
     * [onUploadJam].
     */
    private suspend fun reviewMicCeiling() {
        // Off the main thread, at most 2 s: a connection with nothing to say does not hold the next reading up.
        val entries = withContext(Dispatchers.IO) {
            withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine<RTCStatsReport?> { waiting ->
                    try {
                        room.getPublisherRTCStats(RTCStatsCollectorCallback { report -> waiting.resume(report) {} })
                    } catch (e: Exception) {
                        waiting.resume(null) {}
                    }
                }
            }
        }?.statsMap ?: return
        var videoSending = false
        var heldBack = false
        var micSent: Long? = null
        var micResent = 0L
        var micLost: Long? = null
        var micBytes: Long? = null
        var videoPackets = 0L
        var videoDelay = 0.0

        entries.values.forEach { stats ->
            // What its node says of the microphone, and of a shared screen's sound with it: they go up the one link.
            if (stats.type == "outbound-rtp" && stats.members["kind"] == "audio") {
                micSent = (micSent ?: 0L) + ((stats.members["packetsSent"] as? Number)?.toLong() ?: 0L)
                micResent += (stats.members["retransmittedPacketsSent"] as? Number)?.toLong() ?: 0L
                (stats.members["bytesSent"] as? Number)?.toLong()?.let { sent ->
                    micBytes = (micBytes ?: 0L) + sent + ((stats.members["headerBytesSent"] as? Number)?.toLong() ?: 0L)
                }
            }
            if (stats.type == "remote-inbound-rtp" && stats.members["kind"] == "audio") micLost = (micLost ?: 0L) + ((stats.members["packetsLost"] as? Number)?.toLong() ?: 0L)

            if (stats.type != "outbound-rtp" || stats.members["kind"] != "video") return@forEach

            val frames = (stats.members["framesEncoded"] as? Number)?.toLong() ?: 0L

            if (frames > (micFrames[stats.id] ?: 0L)) videoSending = true

            micFrames[stats.id] = frames
            videoPackets += (stats.members["packetsSent"] as? Number)?.toLong() ?: 0L
            videoDelay += (stats.members["totalPacketSendDelay"] as? Number)?.toDouble() ?: 0.0

            // A layer paused for want of viewers says nothing of the upload.
            if (stats.members["active"] != false && stats.members["qualityLimitationReason"] == "bandwidth") heldBack = true
        }

        val now = System.currentTimeMillis()
        val pair = CallPair.selected(entries)?.members
        val estimate = (pair?.get("availableOutgoingBitrate") as? Number)?.toLong()
        // What the node says of the microphone: lost, or asked for again, which goes up the same upload.
        val heard = micLoss.hearing(now, micSent, micLost?.plus(micResent), micBytes)
        val lossy = heard.lossy
        val queued = micLoss.queued(videoPackets, videoDelay)
        // A camera whose viewers want little sends little, and its estimate reads low on an open line: only video the upload holds back counts, or one it queues, or a microphone it can't carry.
        // Down for its own loss, or its video's queue, it stays down until the estimate is clear of the mark or the video stops: coming down ends them.
        // Alone, there is no estimate to read (0 stands under every mark): its loss brings it down, and having come down alone keeps it there.
        // Beside video, sound lost past what its spare copy hides is answered sooner: the video can give the room back, a line alone can't.
        val moved = if (videoSending) {
            micCeiling.review(now, estimate, heldBack || queued || lossy || micDownForLoss || micDownAlone, if (heard.heavy) CallMicCeiling.HEAVY_HOLD_MS else CallMicCeiling.HOLD_MS)
        } else {
            micCeiling.review(now, 0L, lossy || micDownAlone)
        }

        if (moved) {
            micDownAlone = micCeiling.lowered && (micDownAlone || !videoSending)
            micDownForLoss = micCeiling.lowered && (queued || lossy)
        }

        val top = (room.meshSettings.int("mic.kbps") ?: 64) * 1000
        val bitrate = if (micCeiling.lowered) minOf(CallMicCeiling.SLOW_BITRATE, top) else top
        val bwe = estimate?.let { "${it / 1000}k" } ?: "-"

        if (moved) CallLog.write("room microphone ceiling ${bitrate / 1000}k: bwe out=$bwe")

        if (moved || micCeiling.lowered) {
            (room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack)?.setMeshMaxBitrate(bitrate)
        }

        // The picture's turn comes once the microphone has given what it can: the person talking, the camera going out,
        // and the sound still lost or still waiting behind the video. A step down goes to the first step the estimate
        // says the line carries beside what the microphone puts on it, which, having just come down, is the share of
        // what it did that its new ceiling is of its old.
        val camera = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
        val cameraSending = videoSending && camera?.track != null && camera?.muted != true
        val troubled = lossy || queued
        val down = moved && micCeiling.lowered
        val wire = heard.wire?.let { if (down && top > 0) it * bitrate / top else it }
        val left = if (estimate != null && wire != null) (estimate * CallPictureSteps.FIT - wire) / 1000 else null
        val fitting = CallPictureSteps.fitting(cameraSending, left)
        val talking = cameraSending && heard.talking
        val reading = when {
            !talking -> CallPictureSteps.Reading.IDLE
            !troubled -> CallPictureSteps.Reading.CLEAN
            micCeiling.lowered -> CallPictureSteps.Reading.SUFFERING
            else -> CallPictureSteps.Reading.IDLE
        }
        val stepped = (down && heard.heavy && talking && picture.giveWay(now, fitting)) || picture.review(now, reading, fitting)

        if (stepped) {
            CallLog.write("room picture for the sound: step ${picture.step}: bwe out=$bwe")
            room.setProductCameraCaps(GroupShapes.cameraCap(cameraCodecCap, picture.step))
        }

        if (pair == null) return

        // A client's checks are its consent requests where it counts no others.
        val jam = CallUploadJam.Reading(
            at = now,
            rttMs = (pair["currentRoundTripTime"] as? Number)?.toDouble()?.let { (it * 1000).toLong() },
            requests = ((pair["requestsSent"] ?: pair["consentRequestsSent"]) as? Number)?.toLong(),
            responses = (pair["responsesReceived"] as? Number)?.toLong(),
            bytesReceived = (pair["bytesReceived"] as? Number)?.toLong(),
        )

        if (uploadJam.review(jam)) {
            uploadJammed = uploadJam.jammed
            CallLog.write(if (uploadJammed) "room upload jammed" else "room upload no longer jammed")
            jamHandlers.fire(uploadJammed)
        }
    }

    internal fun handle(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantDisconnected -> {
                // Gone: their switch goes with them.
                cameraSwitch.left(event.participant.identity?.value ?: "")
                refresh()
            }
            // The web's call screen says its camera is changing codec, and when it is back.
            is RoomEvent.DataReceived -> if (event.topic == CameraSwitch.TOPIC) {
                val identity = event.participant?.identity?.value
                val word = CameraSwitch.stateOf(event.data)
                if (identity != null && word != null) {
                    val camera = event.participant?.trackPublications?.values
                        ?.firstOrNull { it.source == Track.Source.CAMERA && !it.muted }?.sid
                    heardCameraSwitch(word, identity, camera)
                }
            }
            // A camera muted while it was changing codec: turned off, which no move does.
            is RoomEvent.TrackMuted -> {
                if (event.participant !is LocalParticipant && event.publication.source == Track.Source.CAMERA) {
                    cameraWentOff(event.participant.identity?.value ?: "", null, muted = true)
                }
                refresh()
            }
            // A camera turned off while it was changing codec, other than by the move itself.
            is RoomEvent.TrackUnpublished -> {
                if (event.participant !is LocalParticipant && event.publication.source == Track.Source.CAMERA) {
                    cameraWentOff(event.participant.identity?.value ?: "", event.publication.sid, muted = false)
                }
                refresh()
            }
            is RoomEvent.ParticipantConnected, is RoomEvent.TrackSubscribed,
            is RoomEvent.TrackUnsubscribed, is RoomEvent.TrackUnmuted,
            is RoomEvent.ParticipantMetadataChanged, is RoomEvent.ActiveSpeakersChanged -> refresh()
            // How a connection is: this app's own by onQuality, someone else's with the people.
            is RoomEvent.ConnectionQualityChanged -> if (state != GroupCallState.ENDED) {
                if (event.participant is LocalParticipant) qualityHandlers.fire(CallConnectionQuality.of(event.quality)) else refresh()
            }
            is RoomEvent.ParticipantPermissionsChanged -> permissionsChanged(event)
            // A host's spotlight, kept in the room's metadata by the realtime server (its `spotlight`).
            is RoomEvent.RoomMetadataChanged -> if (state != GroupCallState.ENDED) {
                spotlightHandlers.fire(GroupRoom.spotlight(event.newMetadata))
            }
            // The media server says a recording of the room began or ended.
            is RoomEvent.RecordingStatusChanged -> if (state != GroupCallState.ENDED) {
                recordingHandlers.fire(room.isRecording)
            }
            is RoomEvent.MeshSettingsChanged -> settingsChanged(event.settings, event.changedKeys)
            is RoomEvent.TrackE2EEStateEvent -> if (GroupRoom.encryptionFailed(event.state)) {
                fail(GroupCallFailure("encryption_failed", message = event.state.name.lowercase()))
            }
            is RoomEvent.Reconnecting -> setState(GroupCallState.RECONNECTING)
            is RoomEvent.Reconnected -> setState(GroupCallState.CONNECTED)
            is RoomEvent.Disconnected -> finish(GroupRoom.endReason(event.reason))
            else -> {}
        }
    }

    /** The app's server changed what someone may do: this app, or another person. */
    private fun permissionsChanged(event: RoomEvent.ParticipantPermissionsChanged) {
        if (state == GroupCallState.ENDED) return
        if (event.participant != room.localParticipant) return refresh()
        val can = GroupPermissions(publish = event.newPermissions?.canPublish ?: false, subscribe = event.newPermissions?.canSubscribe ?: false, admin = grantedAdmin)
        // The media server took down what it sent: nothing is sent again on a reconnect.
        if (!can.publish) {
            microphoneOn = false
            cameraOn = false
        }
        permissionHandlers.fire(can)
    }

    /** Everyone else in the room, read again, their info opened with the app's keys. */
    private fun refresh() {
        if (state == GroupCallState.ENDED) return
        // The cameras whose next frame ends a switch, with the tracks read here.
        watchCameraSwitches()
        people = room.remoteParticipants.values.mapNotNull { person(it) }.sortedBy { it.identity }
        peopleHandlers.fire()
    }

    /** Someone in the room as `people` and `me` tell them. */
    private fun person(participant: Participant): GroupPerson? {
        val identity = participant.identity?.value?.takeIf { it.isNotEmpty() } ?: return null
        val sealed = participant.metadata?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
        val tracks = participant.trackPublications.values
        // The camera of theirs that has arrived here, which ends a switch once it is not the old one.
        val arrived = tracks.firstOrNull { it.source == Track.Source.CAMERA && !it.muted && it.track != null }?.sid
        return GroupPerson(
            identity = identity,
            user = identity.removePrefix("user_").takeIf { identity.startsWith("user_") },
            info = owner.rt.keyring.openJson(sealed) ?: JsonNull,
            camera = tracks.any { it.source == Track.Source.CAMERA && !it.muted },
            microphone = tracks.any { it.source == Track.Source.MICROPHONE && !it.muted },
            speaking = participant.isSpeaking,
            screen = tracks.any { it.source == Track.Source.SCREEN_SHARE && !it.muted },
            publish = participant.permissions?.canPublish ?: true,
            optimizing = cameraSwitch.awaiting(identity, arrived),
            quality = CallConnectionQuality.of(participant.connectionQuality),
        )
    }

    /**
     * A camera changing codec, said by the web's call screen before it takes the camera down and after it is up again
     * ([CameraSwitch]). On the main thread.
     */
    internal fun heardCameraSwitch(word: String, identity: String, camera: String?, now: Long = System.currentTimeMillis()) {
        if (state == GroupCallState.ENDED) return
        val changed = cameraSwitch.heard(word, identity, camera, now)
        // `ready` changes which frame ends the switch, if not the switch.
        watchCameraSwitches()
        if (changed) refresh()
        lookAtCameraSwitches()
    }

    /** Their camera taken out of the room, or muted: turned off, unless it is the move taking the old camera down. */
    internal fun cameraWentOff(identity: String, camera: String?, muted: Boolean) {
        if (state == GroupCallState.ENDED) return
        val ended = if (muted) cameraSwitch.muted(identity) else camera?.let { cameraSwitch.unpublished(identity, it) } ?: false
        if (ended) lookAtCameraSwitches()
    }

    /** A frame of [identity]'s camera [camera], heard by its ear. On the main thread. */
    internal fun cameraDrew(identity: String, camera: String) {
        if (state == GroupCallState.ENDED) return
        if (cameraSwitch.shown(identity, camera)) refresh() else watchCameraSwitches()
        lookAtCameraSwitches()
    }

    /**
     * Each switch up listens for the next frame of the camera that ends it ([CameraSwitch.watches]): a new camera's
     * first, or after `ready` any camera's next. Heard on the track itself rather than in a view, so a switch ends once
     * and for all, and a renderer that stalls or a tile drawn again later can't bring it back. On the main thread.
     */
    private fun watchCameraSwitches() {
        if (!cameraSwitch.anyUp() && cameraSwitchEars.isEmpty()) return
        val wanted = HashMap<String, Pair<VideoTrack, String>>()
        if (state != GroupCallState.ENDED && cameraSwitch.anyUp()) {
            for (participant in room.remoteParticipants.values) {
                val identity = participant.identity?.value ?: continue
                if (!cameraSwitch.optimizing(identity)) continue
                val publication = participant.trackPublications.values
                    .firstOrNull { it.source == Track.Source.CAMERA && !it.muted } ?: continue
                val track = publication.track as? VideoTrack ?: continue
                if (cameraSwitch.watches(identity, publication.sid)) wanted[identity] = track to publication.sid
            }
        }
        for ((identity, ear) in cameraSwitchEars.entries.toList()) {
            val want = wanted[identity]
            if (want == null || want.first !== ear.track || want.second != ear.sid) {
                runCatching { ear.track.removeRenderer(ear.frame) }
                cameraSwitchEars.remove(identity)
            }
        }
        for ((identity, want) in wanted) {
            if (cameraSwitchEars.containsKey(identity)) continue
            val (track, sid) = want
            val frame = NextFrame { cameraDrew(identity, sid) }
            track.addRenderer(frame)
            cameraSwitchEars[identity] = CameraEar(track, sid, frame)
        }
    }

    /**
     * Takes down a switch whose new camera never came, at its failsafe (the node's, from the first word of the move), and
     * looks again while any is up.
     */
    private fun lookAtCameraSwitches() {
        cameraSwitchLook?.cancel()
        cameraSwitchLook = null
        val now = System.currentTimeMillis()
        if (cameraSwitch.expire(now).isNotEmpty()) refresh()
        if (state == GroupCallState.ENDED) return
        val next = cameraSwitch.nextExpiry() ?: return
        cameraSwitchLook = scope.launch {
            delay(maxOf(100L, next - now))
            lookAtCameraSwitches()
        }
    }

    /**
     * Someone's camera changing codec as this room holds it, for the SDK's call screens, which draw their placeholder
     * while it is up: whether a switch is up for [identity] (until the frame that ends it, the camera turned off or the
     * failsafe), and the camera publication that was up when it was said. [GroupPerson.optimizing] is the same switch,
     * ended as soon as the new camera arrives.
     */
    @WordPlusModulesApi
    fun cameraSwitch(identity: String): Pair<Boolean, String?> =
        cameraSwitch.optimizing(identity) to cameraSwitch.from(identity)

    private fun setState(next: GroupCallState) {
        if (state == next || state == GroupCallState.ENDED) return
        state = next
        if (next != GroupCallState.CONNECTING) waiting = null
        stateHandlers.fire(next)
    }

    /** A join waiting for a place on the mesh, as the room client tells it. On the main thread. */
    internal fun heardWait(wait: CallWait) {
        if (state != GroupCallState.CONNECTING) return
        waiting = wait
        waitingHandlers.fire(wait)
    }

    internal fun finish(reason: String) {
        if (state == GroupCallState.ENDED) return
        cameraSwitchLook?.cancel()
        cameraSwitchLook = null
        settingsRepublish?.cancel()
        settingsRepublish = null
        micWatch?.cancel()
        micWatch = null
        uploadJammed = false
        cameraSwitchEars.values.forEach { ear -> runCatching { ear.track.removeRenderer(ear.frame) } }
        cameraSwitchEars.clear()
        endReason = reason
        state = GroupCallState.ENDED
        waiting = null
        room.disconnect()
        stateHandlers.fire(GroupCallState.ENDED)
        endHandlers.fire(reason)
        scope.cancel()
    }

    /** An ear on [track], the camera publication [sid]: its [frame] hears the next frame. */
    private class CameraEar(val track: VideoTrack, val sid: String, val frame: NextFrame)

    /**
     * The next frame a camera's track delivers, heard once and told on the main thread: what ends a camera switch
     * ([watchCameraSwitches]). It is no view, so adaptive stream never counts it as someone looking: the app's own
     * renderers decide what is sent. Taken off its track on the main thread, never from a frame's own thread.
     */
    private class NextFrame(private val heard: () -> Unit) : VideoSink {
        private val done = AtomicBoolean(false)

        override fun onFrame(frame: VideoFrame) {
            // libwebrtc's frame-delivery thread, where a throw aborts the process.
            try {
                if (done.compareAndSet(false, true)) MAIN.post { heard() }
            } catch (e: Throwable) {
                // nothing to do here: the failsafe still ends the switch
            }
        }

        private companion object {
            val MAIN = Handler(Looper.getMainLooper())
        }
    }
}
