package cloud.wordplus.realtime.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import cloud.wordplus.realtime.calls.Call
import cloud.wordplus.realtime.calls.CallAsk
import cloud.wordplus.realtime.calls.CallException
import cloud.wordplus.realtime.calls.CallInvite
import cloud.wordplus.realtime.calls.CallPerson
import cloud.wordplus.realtime.calls.CallState
import cloud.wordplus.realtime.calls.CallType
import cloud.wordplus.realtime.calls.CallsScreenSupport
import cloud.wordplus.realtime.calls.GroupCall
import cloud.wordplus.realtime.calls.GroupCallFailure
import cloud.wordplus.realtime.calls.GroupCallState
import cloud.wordplus.realtime.calls.GroupRecording
import cloud.wordplus.realtime.calls.IncomingCall
import cloud.wordplus.realtime.calls.ModerationAct
import cloud.wordplus.realtime.calls.RealtimeCalls
import cloud.wordplus.realtime.calls.SystemCalls
import cloud.wordplus.realtime.ui.calls.CallsActivity
import cloud.wordplus.realtime.ui.calls.CallsAddPerson
import cloud.wordplus.realtime.ui.calls.CallsAddPersonStore
import cloud.wordplus.realtime.ui.calls.CallsButtonTone
import cloud.wordplus.realtime.ui.calls.CallsControl
import cloud.wordplus.realtime.ui.calls.CallsIcons
import cloud.wordplus.realtime.ui.calls.CallsPieces
import cloud.wordplus.realtime.ui.calls.CallsPiecesStore
import cloud.wordplus.realtime.ui.calls.CallsRoundButton
import cloud.wordplus.realtime.ui.calls.CallsConfig
import cloud.wordplus.realtime.ui.calls.CallsConfigStore
import cloud.wordplus.realtime.ui.calls.CallsEngine
import cloud.wordplus.realtime.ui.calls.CallsLog
import cloud.wordplus.realtime.ui.calls.CallsPermissions
import cloud.wordplus.realtime.ui.calls.CallsRoomParticipant
import cloud.wordplus.realtime.ui.calls.CallsSource
import cloud.wordplus.realtime.ui.calls.CallsSpotlight
import cloud.wordplus.realtime.ui.calls.CallsTheme
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * The call screen on the SDK's calls: the call ringing in, ringing out, connecting and in progress, the
 * other side's picture and one's own in the corner, a group call's grid, and the controls (the microphone, the camera,
 * turning it round, the speaker and the audio route, picture in picture, hanging up), in an activity of its own. Optional,
 * as the module is: an app that draws its own screens uses `RealtimeCalls` alone.
 *
 * ```kotlin
 * CallScreen.configure(CallScreen.Appearance(strings = mapOf("end_call" to "Anruf beenden")))
 * CallScreen.attach(context, rt.calls(context))          // rings for every call coming in, and shows it
 * val call = rt.calls(context).start("support:34:42", to = "34", type = CallType.VIDEO)
 * CallScreen.show(context, call)                          // a call placed from the app
 * CallScreen.show(context, rt.calls(context).group("meeting:daily"), title = "Daily meeting")
 * ```
 *
 * The screen is the Calls* classes of this module (cloud.wordplus.realtime.ui.calls), driven as a call engine drives
 * it: the state, the tracks and the roster in, what the person presses out. On the main thread.
 */
object CallScreen {
    /** The screen's words and colours. */
    data class Appearance(
        /**
         * Its strings in the user's language, by key: `answer`, `reject`, `end_call`, `calling`, `optimizing_video` (over
         * a camera changing codec) … (English otherwise).
         */
        val strings: Map<String, String> = emptyMap(),
        /**
         * Design tokens, as the web's call screen names them (`--wplus-color-accent` to `33, 117, 155` or a hex value), light
         * and dark.
         */
        val tokens: Map<String, String> = emptyMap(),
        val tokensDark: Map<String, String> = emptyMap(),
        val darkMode: Boolean = false,
        /** Whether the microphone and camera wear the state they are in (true) or the action they perform. */
        val iconsReverted: Boolean = true,
    )

    /** What is on the screen. */
    private sealed interface Showing {
        data class Ringing(val incoming: IncomingCall) : Showing
        data class Direct(val call: Call) : Showing
        data class Group(val group: GroupCall) : Showing
    }

    private val scope = MainScope()
    private var context: Context? = null
    private var showing: Showing? = null
    /** The recording of the group call on the screen, as this app knows it. */
    private var recording = CallRecordingState()
    /** The ring this screen is answering, until its call comes with the answer. */
    private var answering: IncomingCall? = null
    private var title = ""
    private var isVideo = true
        set(value) {
            field = value
            // The moderation menus read it from the engine.
            engine()?.isVideo = value
        }
    private var speaker = false
    private var minimised = false
    private var connectedAt = 0L
    private var watching: Job? = null

    /** A call answered from the screen, once it is on: the call between two, or the group call it rang into. */
    var onAnswered: ((IncomingCall.Answered) -> Unit)? = null

    /** The lines the screen writes, for the app's own log. */
    var log: ((String) -> Unit)?
        get() = CallsLog.sink
        set(value) {
            CallsLog.sink = value
        }

    /** Whether a call is on the screen, or ringing on it, or put away from it. */
    val active: Boolean get() = showing != null

    /** The words and colours the screen draws with. */
    fun configure(appearance: Appearance) {
        CallsConfigStore.config = CallsConfig(
            strings = appearance.strings,
            iconsReverted = appearance.iconsReverted,
            designTokens = appearance.tokens,
            designTokensDark = appearance.tokensDark,
            darkMode = appearance.darkMode,
        )
    }

    /**
     * Every state of the screen with no call, to look at a change: the list of states, or `shown` alone. It draws the
     * app's pieces and look as a call would.
     */
    fun preview(context: Context, shown: CallScreenPreviewActivity.Shown? = null) {
        context.startActivity(CallScreenPreviewActivity.intent(context, shown))
    }

    /** Where a call is, as the pieces are told: `ringing_in`, `ringing_out`, `connecting`, `in_progress`, `reconnecting`. */
    data class Moment(val phase: String, val group: Boolean, val seconds: Int)

    /** A call ringing in, for the app's own ringing view (`Pieces.ringing`). */
    class Ringing(
        val name: String,
        val avatar: String,
        val video: Boolean,
        /** Answers it: the screen then shows the call. */
        val answer: () -> Unit,
        /** Declines it. */
        val decline: () -> Unit,
    )

    /** Someone in a group call, for the app's view over their tile (`Pieces.tile`). */
    data class Person(
        val identity: String,
        val name: String,
        val avatar: String,
        val isMe: Boolean,
        val speaking: Boolean,
        val muted: Boolean,
        val handRaised: Boolean,
    )

    /**
     * A button in the call's dock: one of ours by its id (`answer`, `chat` where there is a chat beside the call,
     * `settings`, `flip`, `camera`, `mute`, `speaker`, `pip`, `share`, `add`, `host`, `end`), or one of the app's own
     * (`CallControl.own`). Hanging up stays last, whatever the app answers. `hand` is hidden: these calls raise no hand.
     */
    class CallControl internal constructor(val id: String, internal val view: View) {
        companion object {
            /**
             * A button of the app's own, drawn as ours are: `icon` an icon's path in a 24 grid, as Lucide's are (`icons` has
             * ours by name), `label` what TalkBack says, `latched` drawn on, as the speaker is.
             */
            fun own(context: Context, id: String, label: String, icon: String, latched: Boolean = false, action: () -> Unit): CallControl {
                val button = CallsRoundButton(context, icon, if (latched) CallsButtonTone.LATCH else CallsButtonTone.CONTROL, action)
                button.isOn = latched
                button.contentDescription = label
                button.tag = "call-control-$id"
                return CallControl(id, button)
            }

            /** Our icons by name, for an app's own button. */
            val icons: Map<String, String> = mapOf(
                "phone" to CallsIcons.phone, "mic" to CallsIcons.mic, "micOff" to CallsIcons.micOff, "video" to CallsIcons.video,
                "videoOff" to CallsIcons.videoOff, "settings" to CallsIcons.settings, "users" to CallsIcons.users,
                "raiseHand" to CallsIcons.raiseHand, "screenShare" to CallsIcons.screenShare, "speaker" to CallsIcons.speaker,
                "pip" to CallsIcons.pip, "alert" to CallsIcons.alert,
            )
        }
    }

    /**
     * The app's own pieces in place of ours, the rest of the screen as it is. Each null
     * keeps ours.
     *
     * ```kotlin
     * CallScreen.pieces = CallScreen.Pieces(
     *     controls = { context, ours, moment ->
     *         ours.filter { it.id != "pip" } + CallScreen.CallControl.own(context, "notes", "Call notes", CallScreen.CallControl.icons.getValue("alert")) { openNotes() }
     *     },
     *     status = { moment -> if (moment.phase == "ringing_out") "Ringing Ann…" else null },
     *     ringing = { context, ring -> RingView(context, ring.name, ring.answer, ring.decline) },
     * )
     * ```
     */
    class Pieces(
        /** The dock's buttons, given ours in their order and where the call is: the buttons to show. */
        val controls: ((Context, List<CallControl>, Moment) -> List<CallControl>)? = null,
        /** The status line under the name: null for ours. */
        val status: ((Moment) -> String?)? = null,
        /** The app's own view for a call ringing in, full screen, in place of ours. */
        val ringing: ((Context, Ringing) -> View)? = null,
        /** A view over someone's tile in a group call, filling it (a badge, a frame): the same view again stays. */
        val tile: ((Context, Person) -> View?)? = null,
        /** A view over a call between two, filling the screen under the dock: the same view again stays. */
        val stage: ((Context, Moment) -> View?)? = null,
    )

    /** The app's own pieces, from the screen's next change on. */
    var pieces: Pieces = Pieces()
        set(value) {
            field = value
            val group = { showing is Showing.Group }
            CallsPiecesStore.current = CallsPieces(
                controls = value.controls?.let { arrange ->
                    { ours, phase, context ->
                        arrange(context, ours.map { CallControl(it.id, it.view) }, Moment(phase, group(), 0)).map { CallsControl(it.id, it.view) }
                    }
                },
                status = value.status?.let { status -> { phase, seconds -> status(Moment(phase, group(), seconds)) } },
                tile = value.tile?.let { tile ->
                    { who, context ->
                        tile(context, Person(who.identity, who.name, who.avatar, who.isLocal, who.speaking, who.muted, who.handRaised))
                    }
                },
                stage = value.stage?.let { stage -> { phase, context -> stage(context, Moment(phase, group(), 0)) } },
            )
        }

    /**
     * Add Person in the dock, once a call is up, as the web's `add`: whom the app's server lists (`calls.people`), each rung
     * into the call on a press, which goes on as a group call. In a call between two, and in a group call made from one: a
     * group call the app's server names takes nobody that way. Off unless an app turns it on.
     */
    var addPerson: Boolean = false
        set(value) {
            field = value
            offerAddPerson()
            publish()
        }

    /** The app's own ringing view, for CallRingActivity while a call rings in. */
    internal fun ringView(context: Context): View? {
        val ringing = pieces.ringing ?: return null
        if (showing !is Showing.Ringing) return null
        return ringing(context, Ringing(
            name = title,
            avatar = "",
            video = isVideo,
            answer = { CallRingActivity.finishShown(); answer() },
            decline = {
                val incoming = (showing as? Showing.Ringing)?.incoming
                if (incoming != null) scope.launch { runCatching { incoming.decline() } }
                close()
            },
        ))
    }

    /**
     * Shows every call ringing for this user, and the call once answered: from this screen, or with `SystemCalls`
     * started, from the notification, the ring over a locked phone, a car or a watch. A call ringing during another is
     * answered busy.
     */
    fun attach(context: Context, calls: RealtimeCalls) {
        this.context = context.applicationContext
        calls.onIncoming { ring(it) }
        if (answersFromSystem) return
        answersFromSystem = true
        SystemCalls.onAnswered { answered ->
            when (answered) {
                is IncomingCall.Answered.ToCall -> {
                    title = field(answered.call.peerInfo, "name")
                    isVideo = answered.call.type == CallType.VIDEO
                    bind(answered.call)
                }
                is IncomingCall.Answered.ToGroup -> bind(answered.group)
            }
            onAnswered?.invoke(answered)
        }
    }

    private var answersFromSystem = false

    /** Shows a call placed from the app: ringing out, then connecting and in progress, until it ends. */
    fun show(context: Context, call: Call, name: String? = null) {
        this.context = context.applicationContext
        title = name ?: field(call.peerInfo, "name")
        isVideo = call.type == CallType.VIDEO
        bind(call)
    }

    /** Shows a group call, its grid of everyone in it. */
    fun show(context: Context, group: GroupCall, title: String = "", video: Boolean = true) {
        this.context = context.applicationContext
        this.title = title
        isVideo = video
        bind(group)
    }

    /** The screen of the call going on, again, after it was put away. */
    fun restore() {
        if (showing == null) return
        minimised = false
        open()
    }

    /**
     * Takes the screen away, the call going on, as its chevron does (an app's own button, such as one that shows what the
     * call is about): `restore()` brings it back.
     */
    fun hide() {
        if (showing == null || minimised) return
        CallsActivity.putAway()
    }

    // Ringing

    private fun ring(incoming: IncomingCall) {
        if (showing != null) {
            scope.launch { runCatching { incoming.decline(busy = true) } }
            return
        }
        title = field(incoming.info, "name")
        isVideo = incoming.type == CallType.VIDEO
        connectedAt = 0L
        showing = Showing.Ringing(incoming)
        incoming.onOver { reason ->
            if ((showing as? Showing.Ringing)?.incoming === incoming && ringGoes(reason, answering === incoming)) close()
        }
        // The app's own ringing view, where it gives one; the call's screen comes with the answer.
        if (pieces.ringing != null) {
            context?.let { CallRingActivity.open(it) }
            return
        }
        open()
    }

    private fun answer() {
        val incoming = (showing as? Showing.Ringing)?.incoming ?: return
        answering = incoming
        scope.launch {
            try {
                val answered = incoming.accept()
                when (answered) {
                    is IncomingCall.Answered.ToCall -> bind(answered.call)
                    is IncomingCall.Answered.ToGroup -> bind(answered.group)
                }
                onAnswered?.invoke(answered)
            } catch (e: CallException) {
                engine()?.error(CallsTheme.string(context!!, "call_unavailable", "This call could not be connected"))
                close()
            } finally {
                answering = null
            }
        }
    }

    // A call between two

    private fun bind(call: Call) {
        showing = Showing.Direct(call)
        minimised = false
        connectedAt = if (call.state == CallState.CONNECTED) System.currentTimeMillis() else 0L
        call.onState {
            if (it == CallState.CONNECTED && connectedAt == 0L) connectedAt = System.currentTimeMillis()
            if (isShown(call)) {
                watch(call.carryingRoom?.liveKitRoom)
                publishTracks()
                publish()
            }
        }
        call.onRemote { if (isShown(call)) { publishTracks(); publish() } }
        call.onScreenShare { if (isShown(call)) publish() }
        call.onWaiting { if (isShown(call)) publish() }
        call.onGroup { group -> bind(group) }
        call.onEnded { reason -> if (reason != "group" && isShown(call)) close() }
        offerAddPerson()
        open()
        publishTracks()
    }

    private fun isShown(call: Call) = (showing as? Showing.Direct)?.call === call

    // Add Person

    /**
     * The rings made from the screen and heard in a group call. A call between two lists none of its own, so the first
     * ring is kept here over it becoming a group call.
     */
    private val rung = mutableListOf<CallInvite>()
    private val heardRings = mutableSetOf<String>()

    private fun hear(invite: CallInvite) {
        if (heardRings.add(invite.id)) invite.onState { publish() }
    }

    private val rings: List<CallInvite>
        get() {
            val group = (showing as? Showing.Group)?.group ?: return rung.toList()
            return rung + group.invites.filter { invite -> rung.none { it.id == invite.id } }
        }

    /** The name of the call on the screen and who is in it, where it can add people. */
    private class Adding(val name: String, val calls: RealtimeCalls, val inCall: Set<String>)

    private val adding: Adding?
        get() = when (val shown = showing) {
            is Showing.Direct -> Adding(shown.call.name, shown.call.calls, setOf(shown.call.peer))
            is Showing.Group -> if (shown.group.group != null) Adding(shown.group.name, shown.group.calls, shown.group.people.mapNotNull { it.user }.toSet()) else null
            else -> null
        }

    /** What the screen's Add Person asks ([CallsAddPerson]), from the call on it. */
    private fun offerAddPerson() {
        if (!addPerson || adding == null) {
            CallsAddPersonStore.current = null
            return
        }
        CallsAddPersonStore.current = CallsAddPerson(
            people = people@{ done ->
                val adding = adding ?: return@people done(emptyList())
                scope.launch {
                    val listed = adding.calls.people(adding.name)
                    val may = adding.calls.may(listed.map { CallAsk(adding.name, it.id) })
                    done(offered(listed, may, adding.inCall))
                }
            },
            add = { person, done ->
                scope.launch {
                    try {
                        val invite = when (val shown = showing) {
                            is Showing.Direct -> shown.call.add(person.id)
                            is Showing.Group -> shown.group.add(person.id)
                            else -> return@launch done(null)
                        }
                        rung.add(invite)
                        hear(invite)
                        done(null)
                    } catch (e: CallException) {
                        done(addFailure(context!!, e))
                    }
                }
            },
            rings = { rings.map { CallsAddPerson.Ring(it.id, it.to, it.state, it.canCancel) } },
            cancel = cancel@{ id, done ->
                val invite = rings.firstOrNull { it.id == id } ?: return@cancel done(null)
                scope.launch {
                    try {
                        invite.cancel()
                        done(null)
                    } catch (e: CallException) {
                        done(addFailure(context!!, e))
                    }
                }
            },
        )
    }

    /**
     * Whom Add Person offers: those the app's server lists, less any its rule answers no for (an answer of none keeps them,
     * and the ring still asks the rule) and anyone in the call.
     */
    internal fun offered(listed: List<CallPerson>, may: List<Boolean?>, inCall: Set<String>): List<CallsAddPerson.Person> =
        listed.filterIndexed { index, person -> may.getOrNull(index) != false && person.id !in inCall }
            .map { CallsAddPerson.Person(it.id, it.name, it.avatar ?: "") }

    /** A ring that did not go out, as the web says a call that did not. */
    internal fun addFailure(context: Context, error: CallException): String =
        if (error.code == "forbidden") CallsTheme.string(context, "call_forbidden", "You cannot make this call")
        else CallsTheme.string(context, "call_unavailable", "This call could not be connected")

    // A group call

    private fun bind(group: GroupCall) {
        showing = Showing.Group(group)
        // A call that became a group call comes back on the screen, its grid the news, as on iOS.
        minimised = false
        if (connectedAt == 0L && group.state == GroupCallState.CONNECTED) connectedAt = System.currentTimeMillis()
        group.onState {
            if (it == GroupCallState.CONNECTED && connectedAt == 0L) connectedAt = System.currentTimeMillis()
            if (isShown(group)) publish()
        }
        group.onPeople { if (isShown(group)) { publishRoster(); publish() } }
        group.onPermissions { if (isShown(group)) { publishRoster(); publish() } }
        group.onSpotlight { if (isShown(group)) publish() }
        group.onScreenShare { if (isShown(group)) publish() }
        group.onWaiting { if (isShown(group)) publish() }
        // What could not go out in a call that goes on, as the web's `error` of a group call is shown.
        group.onError { failure ->
            val context = context
            if (isShown(group) && context != null) engine()?.error(SdkFailures.said(context, failure))
        }
        // REC for everyone, late joiners too, from the room's flag.
        recording = CallRecordingState(group.isRecording, seconds())
        group.onRecording { on ->
            if (isShown(group)) {
                recording.flagged(on, seconds())
                publish()
            }
        }
        // How the rings into it stand, for Add Person: the first one came with the call it was made from.
        group.invites.forEach { hear(it) }
        group.onInvite { invite ->
            hear(invite)
            if (isShown(group)) publish()
        }
        group.onEnded { if (isShown(group)) close() }
        offerAddPerson()
        watch(group.liveKitRoom)
        engine()?.remoteVideo(CallsSource.CAMERA, null)
        engine()?.remoteVideo(CallsSource.SCREEN_SHARE, null)
        open()
        publishRoster()
    }

    private fun isShown(group: GroupCall) = (showing as? Showing.Group)?.group === group

    private fun seconds(): Long = System.currentTimeMillis() / 1000

    /** A room's own events (its local camera published, someone speaking) redraw what the screen shows of it. */
    private fun watch(room: Room?) {
        watching?.cancel()
        watching = room?.let {
            scope.launch {
                it.events.collect {
                    when (showing) {
                        is Showing.Group -> publishRoster()
                        is Showing.Direct -> publishTracks()
                        else -> {}
                    }
                }
            }
        }
    }

    // The screen

    private fun engine(): CallsEngine? = context?.let { CallsEngine.get(it) }

    private fun open() {
        val context = context ?: return
        val engine = CallsEngine.get(context)
        engine.driver = driver
        engine.callMode = "participatory"
        engine.publish()
        if (!minimised) CallsActivity.open(context, driver.callId)
    }

    private fun close() {
        showing = null
        CallRingActivity.finishShown()
        watch(null)
        minimised = false
        connectedAt = 0L
        speaker = false
        recording = CallRecordingState()
        CallsAddPersonStore.current = null
        rung.clear()
        heardRings.clear()
        engine()?.close()
    }

    private val microphoneCheck = CallMicrophoneCheck()

    private val driver = object : CallsEngine.Driver {
        override val callId: String
            get() = when (val shown = showing) {
                is Showing.Ringing -> shown.incoming.id
                is Showing.Direct -> shown.call.id
                is Showing.Group -> shown.group.group ?: shown.group.name
                null -> ""
            }

        override fun state(): JSONObject = screenState()

        override fun answer() = this@CallScreen.answer()

        override fun decline() {
            val incoming = (showing as? Showing.Ringing)?.incoming ?: return
            scope.launch { runCatching { incoming.decline() } }
            close()
        }

        override fun end() {
            when (val shown = showing) {
                is Showing.Ringing -> scope.launch { runCatching { shown.incoming.decline() } }
                is Showing.Direct -> shown.call.end()
                is Showing.Group -> shown.group.leave()
                null -> {}
            }
            close()
        }

        override fun setMuted(muted: Boolean) {
            // A device check's microphone goes before the call's is sent: the call's is the only one then.
            if (!muted) stopMicrophoneTest()
            when (val shown = showing) {
                is Showing.Direct -> shown.call.microphone = !muted
                is Showing.Group -> shown.group.microphone = !muted
                else -> {}
            }
            publish()
        }

        override fun setCamera(on: Boolean) {
            if (on) stopCameraTest()
            when (val shown = showing) {
                is Showing.Direct -> shown.call.camera = on
                is Showing.Group -> shown.group.camera = on
                else -> {}
            }
            publishTracks()
            publish()
        }

        override fun flipCamera() {
            when (val shown = showing) {
                is Showing.Direct -> shown.call.flipCamera()
                is Showing.Group -> shown.group.flipCamera()
                else -> {}
            }
            publishTracks()
            publish()
        }

        override fun setSpeaker(on: Boolean) {
            speaker = on
            context?.let { CallsScreenSupport.setSpeaker(it, on) }
            publish()
        }

        override fun setMinimised(minimised: Boolean) {
            this@CallScreen.minimised = minimised
        }

        /** The microphone or camera allowed once the call was on: opened now. */
        override fun permissionsSettled() {
            when (val shown = showing) {
                is Showing.Direct -> shown.call.permissionsGranted()
                is Showing.Group -> {
                    shown.group.microphone = shown.group.microphone
                    shown.group.camera = shown.group.camera
                }
                else -> {}
            }
            publishTracks()
            publish()
        }

        override fun trafficBytes(): Pair<Long, Long>? = null

        // The device check, its tries on this side alone and never sent, as Device Settings has them.
        override fun cameraTestAvailable(): Boolean = showing != null

        override fun startCameraTest(front: Boolean): livekit.org.webrtc.VideoTrack? {
            val at = context ?: return null
            if (!CallsPermissions.cameraGranted(at)) return null
            return when (val shown = showing) {
                is Showing.Group -> shown.group.startCameraCheck(front)
                else -> CallsScreenSupport.startCameraCheck(at, front)
            }
        }

        override fun stopCameraTest() {
            (showing as? Showing.Group)?.group?.stopCameraCheck()
            context?.let { CallsScreenSupport.stopCameraCheck(it) }
        }

        override fun microphoneTestAvailable(): Boolean = showing != null

        override fun startMicrophoneTest(level: (Float) -> Unit): Boolean = context?.let {
            microphoneCheck.start(it, recordsAlready = showing is Showing.Direct, level)
        } ?: false

        override fun stopMicrophoneTest() = microphoneCheck.stop()

        override fun microphoneLevel(): Float? = when (val shown = showing) {
            is Showing.Direct -> shown.call.microphoneLevel()
            is Showing.Group -> shown.group.microphoneLevel()
            else -> null
        }

        override fun microphones(): List<Pair<String, String>> = context?.let { CallMicrophones.list(it) } ?: emptyList()

        override fun microphone(): String = context?.let { CallMicrophones.current(it) } ?: ""

        override fun setMicrophone(id: String) {
            context?.let { CallMicrophones.choose(it, id, isVideo) }
            publish()
        }

        override fun setCameraFacing(front: Boolean) {
            when (val shown = showing) {
                is Showing.Direct -> shown.call.preferCamera(front)
                is Showing.Group -> shown.group.preferCamera(front)
                else -> {}
            }
            publish()
        }

        override fun startScreenShare(resultCode: Int, data: Intent?) {
            // The person said no in the system's dialog: nothing to share, and the button goes back.
            if (resultCode != Activity.RESULT_OK || data == null) return publish()
            shareScreen(true, data)
        }

        override fun stopScreenShare() = shareScreen(false, null)

        override fun sharingScreen(): Boolean = this@CallScreen.sharingScreen()

        // A host (its rule's `admin`) of a group call the app's server names moderates the others through its calls
        // route, which asks the call's rule again.
        override fun canModerate(): Boolean = (showing as? Showing.Group)?.group?.can?.admin ?: false

        override fun groupAdmin(action: String, identity: String, done: (Boolean) -> Unit) {
            val group = (showing as? Showing.Group)?.group
            val act = SdkModeration.acts[action]
            val user = SdkModeration.user(identity)
            if (group == null || act == null || user == null) return done(false)
            scope.launch { done(runCatching { group.moderate(act, user) }.isSuccess) }
        }

        // Spotlight for Everyone and Remove Spotlight in a host's person menu, Clear Spotlight in Host Controls, each
        // through the app's calls route (`spotlight`, `unspotlight`), a host's own tile too. A fifth person is said in the
        // call's words.
        override fun carriesSpotlight(): Boolean = showing is Showing.Group

        override fun spotlight(identity: String?, on: Boolean, done: (Boolean, String?) -> Unit) {
            val group = (showing as? Showing.Group)?.group ?: return done(false, null)
            val user = identity?.let { SdkModeration.user(it) }
            if (user == null && (identity != null || on)) return done(false, null)
            scope.launch {
                try {
                    group.moderate(if (on) ModerationAct.SPOTLIGHT else ModerationAct.UNSPOTLIGHT, user)
                    done(true, null)
                } catch (e: CallException) {
                    val context = context
                    done(false, if (e.code == "spotlight_full" && context != null) CallsTheme.string(context, "spotlight_full", CallsSpotlight.FULL) else null)
                }
            }
        }

        // A host's Start Recording and REC's Stop, through the app's calls route, `starting` or `stopping` until it
        // answers. A refusal (`forbidden`, `no_credits`, `not_included`, `already_recording`, `not_on_mesh` …) is the
        // screen's words, the code being the app's to read where it calls `startRecording` itself.
        override fun record(start: Boolean, done: (Boolean, String?) -> Unit) {
            val group = (showing as? Showing.Group)?.group ?: return done(false, null)
            val stopping = recording.own?.id
            if (!start && stopping == null) return done(false, null)
            recording.ask(if (start) "starting" else "stopping")
            publish()
            scope.launch {
                try {
                    if (start) {
                        recording.started(group.startRecording(), seconds())
                    } else if (stopping != null) {
                        group.stopRecording(stopping)
                        recording.stopped()
                    }
                    publish()
                    done(true, null)
                } catch (e: CallException) {
                    CallsLog.write("the recording was refused: ${e.code}")
                    recording.refused()
                    publish()
                    done(false, null)
                }
            }
        }
    }

    /** The screen's state, as a call engine writes it. */
    private fun screenState(): JSONObject {
        var phase = "idle"
        var group = false
        var microphone = true
        var camera = false
        var front = true
        var peer = JSONObject().put("known", false)
        var host = false
        var spotlight = emptyList<String>()
        var waiting = false
        var recorded: Map<String, Any>? = null

        when (val shown = showing) {
            is Showing.Ringing -> phase = "ringing_in"
            is Showing.Direct -> {
                val call = shown.call
                phase = when (call.state) {
                    CallState.RINGING -> if (call.role == Call.Role.CALLER) "ringing_out" else "connecting"
                    CallState.CONNECTING -> "connecting"
                    CallState.CONNECTED -> "in_progress"
                    CallState.RECONNECTING -> "reconnecting"
                    CallState.ENDED -> "idle"
                }
                microphone = call.microphone
                camera = call.camera
                front = call.frontCamera
                waiting = call.carryingRoom?.waiting != null
                val media = call.remoteMedia
                peer = JSONObject()
                    // A camera changing codec is away for a second under its placeholder, not turned off: no
                    // "camera off" notice for it.
                    .put("camera", (media["camera"] ?: false) || peerOptimizing(call))
                    .put("microphone", media["microphone"] ?: false)
                    .put("screen", media["screen_share"] ?: false)
                    .put("known", media.isNotEmpty())
            }
            is Showing.Group -> {
                val room = shown.group
                group = true
                phase = when (room.state) {
                    GroupCallState.CONNECTING -> "connecting"
                    GroupCallState.CONNECTED -> "in_progress"
                    GroupCallState.RECONNECTING -> "reconnecting"
                    GroupCallState.ENDED -> "idle"
                }
                microphone = room.microphone
                camera = room.camera
                front = room.frontCamera
                host = room.can.admin
                spotlight = room.spotlight
                waiting = room.waiting != null
                recorded = recording.state(room.canRecord, engine()?.recordingNoticeShown ?: true)
            }
            null -> {}
        }

        val route = context?.let { if (speaker) "speaker" else CallsScreenSupport.audioRoute(it) } ?: ""

        return JSONObject()
            .put("phase", phase)
            .put("trouble", if (phase == "reconnecting") "peer" else JSONObject.NULL)
            .put("threadId", JSONObject.NULL)
            .put("messageId", JSONObject.NULL)
            .put("type", if (phase == "idle") JSONObject.NULL else if (isVideo) "video" else "audio")
            .put("group", group)
            .put("waiting", waiting)
            .put("recording", recorded?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("mode", JSONObject.NULL)
            .put("durationMs", if (connectedAt == 0L) 0 else (System.currentTimeMillis() - connectedAt).toInt())
            .put("peerName", title)
            .put("peer", peer)
            .put("local", JSONObject().put("muted", !microphone).put("camera", camera).put("screen", sharingScreen()).put("front", front).put("route", route))
            .put("minimised", minimised)
            .put("callMode", if (group) "participatory" else JSONObject.NULL)
            .put("roomLocked", false)
            .put("roomMuted", false)
            .put("canModerate", host)
            .put("spotlight", JSONArray(spotlight))
            .put("handRaised", false)
            .put("online", JSONArray())
            .put("peerOnline", false)
            .put("canShareScreen", true)
    }

    private fun publish() {
        engine()?.publish()
    }

    private fun sharingScreen(): Boolean = when (val shown = showing) {
        is Showing.Direct -> shown.call.sharingScreen
        is Showing.Group -> shown.group.sharingScreen
        else -> false
    }

    /** The share as the call took it: the screen's button says what the call says once it has answered. */
    private fun shareScreen(on: Boolean, permission: Intent?) {
        scope.launch {
            when (val shown = showing) {
                is Showing.Direct -> shown.call.setScreenShare(on, permission)
                is Showing.Group -> shown.group.setScreenShare(on, permission)
                else -> {}
            }
            publish()
        }
    }

    /** A call between two's pictures: direct, libwebrtc's tracks; carried by a room, the room's. */
    private fun publishTracks() {
        val call = (showing as? Showing.Direct)?.call ?: return
        val engine = engine() ?: return
        val carrying = call.carryingRoom
        val room = carrying?.liveKitRoom
        if (carrying != null && room != null) {
            val other = room.remoteParticipants.values.firstOrNull()
            val identity = other?.identity?.value ?: ""
            // Their picture, which the placeholder is drawn over, blurred: the room's, else the call's.
            val avatar = carrying.people.firstOrNull { it.identity == identity }?.let { field(it.info, "avatar") }
                ?.takeIf { it.isNotEmpty() } ?: field(call.peerInfo, "avatar").takeIf { it.isNotEmpty() }
            engine.remoteVideo(CallsSource.CAMERA, track(other, Track.Source.CAMERA)?.rtcTrack)
            engine.remoteVideo(CallsSource.SCREEN_SHARE, track(other, Track.Source.SCREEN_SHARE)?.rtcTrack)
            // Its camera changing codec, as the room holds it (GroupCall.cameraSwitch): the stage draws the placeholder
            // until the frame that ends it.
            engine.peerOptimizing(peerOptimizing(call), avatar)
            engine.localVideo(track(room.localParticipant, Track.Source.CAMERA)?.rtcTrack)
        } else {
            engine.remoteVideo(CallsSource.CAMERA, call.remoteCamera?.rtcTrack)
            engine.remoteVideo(CallsSource.SCREEN_SHARE, call.remoteScreen?.rtcTrack)
            engine.peerOptimizing(false, null)
            engine.localVideo(if (call.camera) call.localCamera?.rtcTrack else null)
        }
    }

    /** Whether the other side of a call carried by a room is changing its camera's codec. */
    private fun peerOptimizing(call: Call): Boolean {
        val carrying = call.carryingRoom ?: return false
        val other = carrying.liveKitRoom.remoteParticipants.values.firstOrNull() ?: return false
        return carrying.cameraSwitch(other.identity?.value ?: "").first
    }

    /** A group call's people, this side first, as the grid draws them. */
    private fun publishRoster() {
        val group = (showing as? Showing.Group)?.group ?: return
        val engine = engine() ?: return
        val room = group.liveKitRoom
        val info = group.people.associate { it.identity to it.info }
        val me = room.localParticipant
        // A host (its rule's `admin`) moderates the others through the app's calls route.
        val can = group.can
        // This user's own tile by the name and picture the app's server gave them, as the others see them, from the first
        // moment: the room knows nobody until it has connected.
        val (identity, own) = group.own
        val people = listOf(participant(me, own, null, publish = can.publish, admin = can.admin, identity = identity)) +
            room.remoteParticipants.values
                .sortedBy { it.identity?.value ?: "" }
                .map { participant(it, info[it.identity?.value ?: ""], group, publish = it.permissions?.canPublish ?: true) }
        engine.localVideo(track(me, Track.Source.CAMERA)?.rtcTrack)
        engine.roster(people)
    }

    /**
     * Someone in a group call, this side with no [group]: the others' camera switches are the group call's. [publish],
     * whether they may send (a listener may not), and [admin], whether they host it, which only this side knows of itself.
     */
    private fun participant(participant: Participant, info: JsonElement?, group: GroupCall?, publish: Boolean = true, admin: Boolean = false, identity: String = participant.identity?.value ?: ""): CallsRoomParticipant {
        val local = group == null
        val microphone = participant.trackPublications.values.firstOrNull { it.source == Track.Source.MICROPHONE }
        return CallsRoomParticipant(
            identity = identity,
            name = info?.let { field(it, "name") }?.takeIf { it.isNotEmpty() } ?: participant.name ?: "",
            avatar = info?.let { field(it, "avatar") } ?: "",
            isLocal = local,
            video = track(participant, Track.Source.CAMERA),
            screen = track(participant, Track.Source.SCREEN_SHARE),
            speaking = participant.isSpeaking,
            audioLevel = participant.audioLevel,
            muted = microphone == null || microphone.muted,
            micMuted = false,
            handRaised = false,
            isPinned = false,
            canPublish = publish,
            canAdmin = admin,
            mode = "participant",
            // A camera changing codec, as the room holds it: the tile draws its placeholder until the frame that ends
            // it.
            optimizing = group?.cameraSwitch(identity)?.first ?: false,
        )
    }

    private fun track(participant: Participant?, source: Track.Source): VideoTrack? =
        participant?.trackPublications?.values?.firstOrNull { it.source == source && !it.muted }?.track as? VideoTrack

    private fun field(info: JsonElement, key: String): String =
        ((info as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
}

/**
 * Whether a ring's end takes its screen away: not one this screen is answering, whose call comes with the answer. One
 * the app answered through the API goes, as its screen is the app's to show (`show( call )`).
 */
internal fun ringGoes(reason: String, answeringHere: Boolean): Boolean = reason != "answered" || !answeringHere

/**
 * A host's actions in the screens' words, as the SDK's group calls take them (realtime-ui's use-group-call.ts): Remove
 * from Call, Stop Camera & Mic to listening, Allow Camera & Mic to speaking, and Mute Microphone. What the SDK's calls
 * carry none of answers false.
 */
internal object SdkModeration {
    val acts: Map<String, ModerationAct> = mapOf(
        "remove_participant" to ModerationAct.REMOVE,
        "mute_participant" to ModerationAct.LISTEN,
        "unmute_participant" to ModerationAct.SPEAK,
        "mute_mic" to ModerationAct.MUTE,
    )

    /** The user a tile is, by the room's name for them (`user_<id>`): null for any other. */
    fun user(identity: String): String? = identity.takeIf { it.startsWith("user_") && it.length > 5 }?.substring(5)
}

/** What went wrong in a group call that goes on, in the call's words. */
internal object SdkFailures {
    fun said(context: Context, failure: GroupCallFailure): String = when {
        failure.code == "publish_failed" && failure.source == "camera" ->
            CallsTheme.string(context, "camera_not_sent", "Your camera could not be sent.")
        failure.code == "publish_failed" && failure.source == "microphone" ->
            CallsTheme.string(context, "microphone_not_sent", "Your microphone could not be sent.")
        failure.code == "encryption_failed" ->
            CallsTheme.string(context, "call_not_encrypted", "Part of this call could not be encrypted.")
        else -> CallsTheme.string(context, "call_trouble", "Something went wrong in this call.")
    }
}

/**
 * A group call's recording as the screens read it (the state's `recording`, CallsRecording), from what this app knows of
 * it: the one it started, by its id, and the room's flag for one anyone else started, which names no id. So a host who
 * did not start it sees REC and has no Stop: the calls route stops a recording by its id, and nothing hands the id to
 * them. Everyone else is told by the notice, the screens' default.
 */
internal class CallRecordingState(flag: Boolean = false, now: Long = 0) {
    /** The recording this app started, until it is stopped. */
    var own: GroupRecording? = null
        private set

    /** `starting` or `stopping` while the app's calls route answers: "" otherwise. */
    private var asked = ""

    /** The room says it is being recorded. */
    private var flag = false

    /** When it started, or when this app heard of it, in seconds since 1970. */
    private var since = 0L

    /** This app's recording, stopped, while the room still says it records. */
    private var stopping: String? = null

    /** Which recording of someone else's this is in this call, for the answers kept by its id. */
    private var run = 0

    init {
        flagged(flag, now)
    }

    fun ask(what: String) {
        asked = what
    }

    fun started(recording: GroupRecording, now: Long) {
        own = recording
        asked = ""
        since = now
    }

    /** The route said no: what was on stays on. */
    fun refused() {
        asked = ""
    }

    fun stopped() {
        if (flag) stopping = own?.id
        own = null
        asked = ""
    }

    /** The room's flag changed. Off ends whatever this app knew, whoever stopped it. */
    fun flagged(on: Boolean, now: Long) {
        if (on == flag) return
        flag = on
        if (on) {
            if (own == null) {
                run += 1
                since = now
            }
        } else {
            own = null
            stopping = null
            since = 0
        }
    }

    /**
     * The state's `recording`, [can] whether this user may start one, [notice] whether the notice tells them: none for
     * someone who may not, while nothing records.
     */
    fun state(can: Boolean, notice: Boolean): Map<String, Any>? {
        val one = mutableMapOf<String, Any>("tell" to "notice", "notice" to notice)
        val own = own
        val stopping = stopping
        when {
            asked == "starting" -> {
                one["state"] = "starting"
                one["can"] = can
                one["mine"] = true
            }
            own != null -> {
                one["state"] = if (asked == "stopping") "stopping" else "on"
                one["id"] = own.id
                one["since"] = since
                one["can"] = can
                one["mine"] = true
            }
            stopping != null -> {
                one["state"] = "stopping"
                one["id"] = stopping
                one["can"] = can
                one["mine"] = true
            }
            flag -> {
                one["state"] = "on"
                one["id"] = "room-$run"
                one["since"] = since
                one["can"] = false
            }
            can -> {
                one["state"] = "off"
                one["can"] = true
            }
            else -> return null
        }
        return one
    }
}
