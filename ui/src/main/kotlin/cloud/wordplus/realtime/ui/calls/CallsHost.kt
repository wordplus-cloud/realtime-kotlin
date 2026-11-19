package cloud.wordplus.realtime.ui.calls

import android.app.Activity
import android.content.Context
import android.content.Intent
import cloud.wordplus.realtime.calls.CallsScreenSupport
import cloud.wordplus.realtime.calls.ScreenShare
import io.livekit.android.room.track.VideoTrack as RoomVideoTrack
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.VideoTrack
import org.json.JSONObject

// What the call screen (the Calls* files beside this one) asks of the app it is in, answered here under the names it
// uses. cloud.wordplus.realtime.ui.CallScreen drives this engine from the SDK's calls. An app with a call engine of its
// own that takes the screen's files answers the same names from its engine instead of this file, so the screen stays
// the same files in both.

/** The screen's words and colours: strings by key in the user's language, design tokens light and dark. */
data class CallsConfig(
    val strings: Map<String, String> = emptyMap(),
    val iconsReverted: Boolean = true,
    val designTokens: Map<String, String> = emptyMap(),
    val designTokensDark: Map<String, String> = emptyMap(),
    val darkMode: Boolean = false,
    /**
     * Whether a group call, joined with the microphone and the camera off, says so over the dock as it is first joined,
     * with a press that opens Device Settings with the tests running (CallsJoinNotice). Off unless an app turns it on.
     */
    val devicesOnJoin: Boolean = false,
)

object CallsConfigStore {
    @Volatile
    var config: CallsConfig = CallsConfig()

    fun current(context: Context): CallsConfig? = config
}

/**
 * One of the dock's buttons as the host may arrange them: ours by their id (`answer`, `chat`, `settings`, `flip`,
 * `camera`, `mute`, `speaker`, `pip`, `share`, `add`, `hand`, `host`, `end`), with the view the screen built, or one of
 * the host's own. `chat` is there only where the host has a chat beside the call ([CallsPlugin.chatToggle]), `add` only
 * where it gives whom to add ([CallsAddPersonStore]).
 */
class CallsControl(val id: String, val view: android.view.View)

/**
 * The pieces a host draws in place of the screen's own: each null keeps ours.
 * CallScreen fills them from an app's `CallScreen.Pieces`.
 */
class CallsPieces(
    /** The dock's buttons in their order, given ours in theirs, the call's phase, and the context to build with. */
    val controls: ((ours: List<CallsControl>, phase: String, context: Context) -> List<CallsControl>)? = null,
    /** The status line under the name, given the phase and the seconds the call has run: null for ours. */
    val status: ((phase: String, seconds: Int) -> String?)? = null,
    /** A view over someone's tile in a group call, filling it: null for none. The same view again stays. */
    val tile: ((CallsRoomParticipant, Context) -> android.view.View?)? = null,
    /** A view over the stage of a call between two, filling it, given the phase: null for none. */
    val stage: ((phase: String, Context) -> android.view.View?)? = null,
)

object CallsPiecesStore {
    @Volatile
    var current: CallsPieces = CallsPieces()
}

/** Where the screen's lines go: the app's own log, when it gives one (CallScreen.log). */
object CallsLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun write(message: String) {
        sink?.invoke(message)
    }
}

/** What a track carries. */
enum class CallsSource(val wire: String) {
    CAMERA("camera"),
    MICROPHONE("microphone"),
    SCREEN_SHARE("screen_share"),
    SCREEN_SHARE_AUDIO("screen_share_audio");

    val isVideo: Boolean
        get() = this == CAMERA || this == SCREEN_SHARE

    companion object {
        val ORDER = listOf(CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO)

        fun of(wire: String?): CallsSource? = values().firstOrNull { it.wire == wire }
    }
}

/** Someone in a group call, as the grid and the participants' panel draw them. */
data class CallsRoomParticipant(
    val identity: String,
    val name: String,
    val avatar: String,
    val isLocal: Boolean,
    val video: RoomVideoTrack?,
    val screen: RoomVideoTrack?,
    val speaking: Boolean,
    val audioLevel: Float,
    val muted: Boolean,
    /** Muted by a moderator: a rule, so the control that would undo it is not offered. */
    val micMuted: Boolean,
    val handRaised: Boolean,
    /**
     * Pinned for everyone by a moderator's Pin Participant, where the call has no spotlight or an older app pinned them:
     * shown as a spotlight ([CallsSpotlight]).
     */
    val isPinned: Boolean,
    val canPublish: Boolean,
    val canAdmin: Boolean,
    /** `participant` or `viewer`. */
    val mode: String,
    /** The room says this person has lost their connection to it. */
    val lost: Boolean = false,
    /**
     * Their camera is changing codec ([CallsCameraSwitch]): the tile keeps the "Optimizing video quality..."
     * placeholder up, never the look of a camera turned off, until the room hears the frame that ends it, the camera
     * turned off or the failsafe.
     */
    val optimizing: Boolean = false,
    /** Away for a moment and held for a return: the tile wears the "Reconnecting..." veil. */
    val reconnecting: Boolean = false,
    /**
     * What of theirs stopped arriving while they are still in the call, null for nothing: the tile blurs it and says
     * so. The SDK's calls judge none yet.
     */
    val stalled: CallsStalled? = null,
)

/** What of someone's stopped arriving while they are still in a room call: their camera alone, or everything. */
enum class CallsStalled { VIDEO, ALL }

/** The picture sizes a view asks the sender for: the rungs a sender sends at. */
object CallsShapes {
    val VIEW_STEPS = listOf(180, 360, 540, 720, 1080, 1440, 2160)

    fun viewStep(height: Int): Int {
        if (height <= 0) return 0

        return VIEW_STEPS.firstOrNull { it >= height } ?: VIEW_STEPS.last()
    }

    /** A screen at the pixel it is drawn at, rounded up to an even one. */
    fun exactStep(height: Int): Int {
        if (height <= 0) return 0

        return ((height + 1) / 2) * 2
    }
}

/** The renderers' EGL context: the SDK's calls' own, which their tracks are drawn in. */
class CallsMedia private constructor(private val context: Context) {
    val eglBase: EglBase get() = CallsScreenSupport.eglBase(context)

    companion object {
        fun get(context: Context): CallsMedia = CallsMedia(context.applicationContext)
    }
}

/**
 * An app's bridge to a web view that holds the call, and the conversation beside it. This one has no page and no chat:
 * the dock draws no Show Chat.
 */
object CallsPlugin {
    /** Whether the host has a chat to show beside the call, which puts Show Chat / Hide Chat in the dock. */
    val chatToggle: Boolean get() = false

    fun askChatToggle(): Boolean = false

    fun askExpand() {}
}

/** The call in a web page's own box, for an app whose web view gives it one; none here. */
object CallsFrame {
    fun reattach() {}
}

/** The system's own dialog that asks for the screen, whose answer comes back to the call screen's activity. */
object CallsScreenCapture {
    const val REQUEST_CODE = 7312

    fun requestFrom(activity: Activity) {
        // No user action: the call stays on its screen behind the dialog, not in a corner window.
        activity.startActivityForResult(ScreenShare.permissionIntent(activity).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION), REQUEST_CODE)
    }
}

/**
 * The call as the screen reads it and what it asks of it, in the names the screen uses. Its [driver] is CallScreen, on
 * the SDK's calls; the per-view picture sizes an engine of an app's own may have are not on them, so those answer that
 * nothing happened.
 */
class CallsEngine private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var instance: CallsEngine? = null

        fun get(context: Context): CallsEngine =
            instance ?: synchronized(this) { instance ?: CallsEngine(context.applicationContext).also { instance = it } }

        private const val RECORDING_NOTICE_NEVER = "recording_notice_never"
    }

    /** What the screen's buttons do to the call on it. */
    internal interface Driver {
        val callId: String
        fun state(): JSONObject
        fun answer()
        fun decline()
        fun end()
        fun setMuted(muted: Boolean)
        fun setCamera(on: Boolean)
        fun flipCamera()
        fun setSpeaker(on: Boolean)
        fun setMinimised(minimised: Boolean)
        fun permissionsSettled()
        fun trafficBytes(): Pair<Long, Long>?

        /**
         * A host's act on someone ([groupAdmin]): `done` with whether it went through. On the SDK's calls a host of a
         * group call the app's server names mutes a microphone, lets speak, makes a listener and removes.
         */
        fun groupAdmin(action: String, identity: String, done: (Boolean) -> Unit) = done(false)

        /** The system's answer to sharing the screen, once it was asked ([CallsScreenCapture]), and stopping. */
        fun startScreenShare(resultCode: Int, data: Intent?) {}

        fun stopScreenShare() {}

        fun sharingScreen(): Boolean = false

        /** Whether the call carries Spotlight for Everyone ([CallsEngine.carriesSpotlight]): the SDK's group calls do. */
        fun carriesSpotlight(): Boolean = false

        /** A host's spotlight act ([CallsEngine.spotlight]): `done` with false and the error to show where it failed. */
        fun spotlight(identity: String?, on: Boolean, done: (Boolean, String?) -> Unit) = done(false, null)

        /** Starts ([start]) or stops a group call's recording: `done` with false and the error to show where it failed. */
        fun record(start: Boolean, done: (Boolean, String?) -> Unit) = done(false, null)

        // What the SDK's calls do not offer yet: each answers that it cannot, and the screen leaves the piece out.
        fun canModerate(): Boolean = false
        fun canEndForEveryone(): Boolean = false
        fun endForEveryone(done: (Boolean) -> Unit) = done(false)

        // The device check: a driver with none of it answers that it cannot, and Device Settings offers no test or picker.
        fun cameraTestAvailable(): Boolean = false
        fun startCameraTest(front: Boolean): VideoTrack? = null
        fun stopCameraTest() {}
        fun microphoneTestAvailable(): Boolean = false
        fun startMicrophoneTest(level: (Float) -> Unit): Boolean = false
        fun stopMicrophoneTest() {}
        fun microphoneLevel(): Float? = null
        fun microphones(): List<Pair<String, String>> = emptyList()
        fun microphone(): String = ""
        fun setMicrophone(id: String) {}
        fun setCameraFacing(front: Boolean) {}
    }

    internal var driver: Driver? = null

    private var lastRemoteVideo: VideoTrack? = null
    private var lastRemoteScreen: VideoTrack? = null
    private var lastLocalVideo: VideoTrack? = null
    private var roster: List<CallsRoomParticipant> = emptyList()

    /**
     * The other side of a call carried by a room changing its camera's codec, as last told ([peerOptimizing]): up, as the
     * room holds it, and their picture for the placeholder's backdrop.
     */
    private data class PeerSwitch(
        val optimizing: Boolean = false,
        val avatar: String? = null,
    )

    private var lastPeerSwitch = PeerSwitch()

    /** The screen on show. One that opens into a call already running is given what that call has. */
    var screenListener: CallsScreenListener? = null
        set(value) {
            field = value
            value?.onScreenState(state())
            value?.onScreenRemoteVideo(CallsSource.CAMERA, lastRemoteVideo)
            value?.onScreenRemoteVideo(CallsSource.SCREEN_SHARE, lastRemoteScreen)
            value?.onScreenLocalVideo(lastLocalVideo)
            value?.onScreenRoster(roster)
            lastPeerSwitch.takeIf { it.optimizing }?.let { value?.onScreenPeerOptimizing(true, it.avatar) }
        }

    var callMode = "participatory"
    var roomLocked = false
    var roomMuted = false
    val isSharingScreen: Boolean get() = driver?.sharingScreen() ?: false
    val runningCallId: String get() = driver?.callId ?: ""

    fun state(): JSONObject = driver?.state() ?: JSONObject().put("phase", "idle")

    fun currentCallId(): String = runningCallId

    fun currentCallKey(): String = runningCallId

    fun answerFromScreen(callId: String) {
        driver?.answer()
    }

    fun decline(callId: String) {
        driver?.decline()
    }

    fun endCall(reason: String) {
        driver?.end()
    }

    fun setMuted(value: Boolean) {
        driver?.setMuted(value)
    }

    fun setCameraEnabled(value: Boolean) {
        driver?.setCamera(value)
    }

    fun flipCamera() {
        driver?.flipCamera()
    }

    fun setSpeaker(on: Boolean) {
        driver?.setSpeaker(on)
    }

    fun audioRoutes(): List<Pair<String, String>> = CallsScreenSupport.audioRoutes(context)

    fun audioRoute(): String = CallsScreenSupport.audioRoute(context)

    fun setRoute(route: String) {
        CallsScreenSupport.setAudioRoute(context, route)
        publish()
    }

    fun trafficBytes(): Pair<Long, Long>? = driver?.trafficBytes()

    fun setMinimised(value: Boolean) {
        driver?.setMinimised(value)
    }

    fun permissionsSettled() {
        driver?.permissionsSettled()
    }

    fun startScreenShare(resultCode: Int, data: Intent?) {
        driver?.startScreenShare(resultCode, data)
    }

    fun stopScreenShare() {
        driver?.stopScreenShare()
    }

    fun keepAWayBack(waitForABox: Boolean = true) {}

    fun scheduleView() {}

    fun canModerate(): Boolean = driver?.canModerate() ?: false

    /**
     * Whether Host Controls offers End for Everyone: everyone else taken out of the call and this side leaving too. The
     * SDK's own calls end by the app's server (`end()`), so it is not offered there.
     */
    fun canEndForEveryone(): Boolean = driver?.canEndForEveryone() ?: false

    /** End for Everyone, asked already: `done` once it is over, false where it failed (the engine says why). */
    fun endForEveryone(done: (Boolean) -> Unit) {
        driver?.endForEveryone(done) ?: done(false)
    }

    // Device Settings (CallsDevicePanel): each device tried out only on this side, never sent. Going live is the dock's.

    /** Whether the camera can be opened here for a look that is never sent. */
    fun cameraTestAvailable(): Boolean = driver?.cameraTestAvailable() ?: false

    /** The camera, facing `front` or the back, opened only for this side to see: null where it would not open. */
    fun startCameraTest(front: Boolean): VideoTrack? = driver?.startCameraTest(front)

    fun stopCameraTest() {
        driver?.stopCameraTest()
    }

    /** Whether the microphone can be opened here for a level that is never sent. */
    fun microphoneTestAvailable(): Boolean = driver?.microphoneTestAvailable() ?: false

    /**
     * The microphone opened only for this side, its level from 0 to 1 handed to `level` about twenty times a second, on
     * any thread. False where it would not open.
     */
    fun startMicrophoneTest(level: (Float) -> Unit): Boolean = driver?.startMicrophoneTest(level) ?: false

    fun stopMicrophoneTest() {
        driver?.stopMicrophoneTest()
    }

    /** How loud the call's own microphone is while it is sent, from 0 to 1: null where the engine cannot say. */
    fun microphoneLevel(): Float? = driver?.microphoneLevel()

    /** The phone's microphones, id to name, and the one the call hears: empty where there is no choice. */
    fun microphones(): List<Pair<String, String>> = driver?.microphones() ?: emptyList()

    fun microphone(): String = driver?.microphone() ?: ""

    fun setMicrophone(id: String) {
        driver?.setMicrophone(id)
        publish()
    }

    /** The camera the call opens, and turns to while it runs: the front one or the back. */
    fun setCameraFacing(front: Boolean) {
        val driver = driver ?: return

        if (state().optJSONObject("local")?.optBoolean("camera", false) == true) {
            if (state().optJSONObject("local")?.optBoolean("front", true) != front) driver.flipCamera()
        } else {
            driver.setCameraFacing(front)
        }

        publish()
    }

    /** Whether the call is a video one: the moderation menus name a camera only there, and pin and spotlight only there. */
    var isVideo = true
        internal set

    /**
     * [identities]: the people Mute Everyone and its undo mean, everyone but the moderators. On the SDK's calls the
     * driver answers a host's acts on one person ([Driver.groupAdmin]), and every other action answers false.
     */
    fun groupAdmin(
        action: String,
        identity: String = "",
        identities: List<String> = emptyList(),
        done: ((Boolean) -> Unit)? = null
    ) {
        val driver = driver
        if (driver == null) {
            done?.invoke(false)
            return
        }
        driver.groupAdmin(action, identity) { done?.invoke(it) }
    }

    /**
     * Whether the call carries the room's own rules, which Host Controls offers a moderator: Mute Everyone, Lock Room,
     * Broadcast Mode, raised hands and their sound, Lower All Hands. The SDK's calls carry none: the cloud locks and mutes
     * no room for everyone, so its Host Controls hold the join alerts, the spotlight and recording alone.
     */
    val carriesRoomRules: Boolean
        get() = false

    /**
     * A moderator's asks that someone turn on their camera or microphone ([CallsAsks]): where the call carries them, a
     * person's menu offers them (groupAdmin's `ask_camera`, `ask_microphone`, `ask_camera_microphone`), and the state
     * names the asks this user was sent (`asks`: id, device, by, the oldest first) and the answers to this user's own
     * (`askAnswers`: id, from, device, answer, the newest last). The SDK's calls carry none.
     */
    val carriesAsks: Boolean
        get() = false

    /** This user's answer to an ask (`on`, `not_now`, `blocked`, `no_device`, `busy`), sent to whoever asked alone. */
    fun answerAsk(id: String, device: String, by: String, answer: String) {}

    /** The ask put away, answered: the engine takes it out of the state's `asks`. */
    fun dismissAsk(id: String, device: String) {}

    /**
     * A group call's recording ([CallsRecording]), where the engine's state names one (`recording`): the screens show REC,
     * the notice and the question to stay or leave as the site tells people, and a moderator's Start and Stop. The engine
     * keeps this user's answer to a recording by its id (`agreed` to the question, `seen` for the notice) and holds the
     * microphone, camera and screen off while the site asks for that and the answer is not `agreed`. The SDK's group
     * calls name one where the app's server names the call ([cloud.wordplus.realtime.ui.CallRecordingState]), told by a
     * notice, and keep the answers on the screen alone.
     */
    fun answerRecording(id: String, answer: String) {}

    /** Do Not Show Again on the notice: the engine keeps the user's choice where the app keeps their settings. */
    fun recordingNoticeNever() {
        settings.edit().putBoolean(RECORDING_NOTICE_NEVER, true).apply()
    }

    /** Whether the notice tells this user of a recording: false after Do Not Show Again, REC alone telling them. */
    val recordingNoticeShown: Boolean
        get() = !settings.getBoolean(RECORDING_NOTICE_NEVER, false)

    private val settings get() = context.getSharedPreferences("cloud.wordplus.realtime.calls", Context.MODE_PRIVATE)

    /**
     * The user's own choice about the join notice ([CallsJoinNotice]), where the host keeps one (the web's
     * `joinNotice`): null keeps none, and the notice offers no Do Not Show Again; false, said with it, and the notice
     * no longer shows.
     */
    val joinNotice: Boolean?
        get() = null

    /** Do Not Show Again on the join notice: the host keeps the user's choice where the app keeps their settings. */
    fun joinNoticeNever() {}

    /**
     * A moderator's Start and Stop: `done` with false and the error to show where it failed. The driver answers from
     * `GroupCall.startRecording` and `stopRecording`. With none, refused, as nothing records.
     */
    fun startRecording(done: (Boolean, String?) -> Unit) = record(true, done)

    fun stopRecording(done: (Boolean, String?) -> Unit) = record(false, done)

    private fun record(start: Boolean, done: (Boolean, String?) -> Unit) {
        val driver = driver
        if (driver == null) {
            done(false, null)
            return
        }
        driver.record(start, done)
    }

    /**
     * Spotlight for Everyone ([CallsSpotlight]): where the call carries it, a moderator's person menu offers Spotlight for
     * Everyone and Remove Spotlight in place of Pin Participant, and Host Controls Clear Spotlight while anyone is in it,
     * each through [spotlight]. Whoever is in it is the state's `spotlight` (identities, in the order they were
     * spotlighted), which the grid follows wherever an engine names one. The SDK's group calls carry it: the driver says
     * so while one shows, and answers [spotlight] from `GroupCall.moderate`.
     */
    val carriesSpotlight: Boolean
        get() = driver?.carriesSpotlight() ?: false

    /**
     * Puts [identity] in the spotlight for everyone ([on]), takes them out of it, or takes everyone out (null and not on):
     * `done` with false and the error to show where it failed.
     */
    fun spotlight(identity: String?, on: Boolean, done: (Boolean, String?) -> Unit) {
        val driver = driver
        if (driver == null) {
            done(false, null)
            return
        }
        driver.spotlight(identity, on, done)
    }

    fun cycleDebugRoster(): Boolean = false

    // What CallScreen tells the screen.

    internal fun publish() {
        screenListener?.onScreenState(state())
    }

    internal fun remoteVideo(source: CallsSource, track: VideoTrack?) {
        if (source == CallsSource.SCREEN_SHARE) lastRemoteScreen = track else lastRemoteVideo = track
        screenListener?.onScreenRemoteVideo(source, track)
    }

    internal fun localVideo(track: VideoTrack?) {
        lastLocalVideo = track
        screenListener?.onScreenLocalVideo(track)
    }

    internal fun roster(people: List<CallsRoomParticipant>) {
        roster = people
        screenListener?.onScreenRoster(people)
    }

    /** The other side of a call carried by a room changing its camera's codec ([CallsCameraSwitch]). */
    internal fun peerOptimizing(optimizing: Boolean, avatar: String?) {
        val next = PeerSwitch(optimizing, avatar)
        if (next == lastPeerSwitch) return
        lastPeerSwitch = next
        screenListener?.onScreenPeerOptimizing(optimizing, avatar)
    }

    internal fun error(message: String) {
        screenListener?.onScreenError(message)
    }

    /** The call is over: the screen goes, and what it held is let go. */
    internal fun close() {
        lastRemoteVideo = null
        lastRemoteScreen = null
        lastLocalVideo = null
        roster = emptyList()
        lastPeerSwitch = PeerSwitch()
        screenListener?.onScreenClose()
    }
}
