package cloud.wordplus.realtime.ui.calls

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.util.Rational
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.TouchDelegate
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

/**
 * The call, as the person sees it.
 *
 * One screen for all of it — ringing out, ringing in, connecting, in progress
 * and reconnecting — because they are the same call and a phone that swaps
 * screens under someone mid-answer feels broken. What changes is which parts
 * are on show.
 *
 * **A view, not a screen.** It used to be an activity, and the band over the
 * conversation was a second, smaller call built beside it — two of everything,
 * and handing a call from one to the other tore down a renderer and built
 * another, which is why it read as the screen reloading rather than the
 * picture shrinking. iOS never had that: its call is a view controller in the
 * app's own window and `setCallFrame` resizes it.
 *
 * So this is one view at whatever size it is given. The activity that remains
 * is only what a full-screen intent needs to open over a locked screen.
 */
class CallsStage(context: Context, private val host: Host) :
    FrameLayout(context), CallsScreenListener {

    /**
     * What the call cannot do for itself.
     *
     * Each of these needs an `Activity` that is really hosting it: the system
     * asks for permissions and screen-capture consent through one, the sheets
     * need somewhere to show, and the corner window is a property of a window
     * rather than of a view.
     */
    interface Host {
        val activity: Activity

        /** Whether the window this is in is currently the corner window. */
        val inCornerWindow: Boolean

        fun requestCallPermissions(state: JSONObject)
        fun startScreenCapture()
        fun enterCornerWindow()
        fun dismissKeyguard()

        /**
         * Take the call back to the whole screen. Only a band can do this, and
         * only a band is ever asked.
         */
        fun expandCall() {}

        /**
         * Whether the conversation is behind this rather than around it.
         *
         * A window is: it is in front of the web view, so handing the screen
         * back means getting out of the way. The box on the page is not — the
         * conversation is already beside it, and the page answers the chevron
         * by resizing the box rather than by being revealed. Closing it there
         * would throw away the renderers the page is about to resize, which is
         * the reload the chevron was supposed to have stopped causing.
         */
        val coversPage: Boolean get() = true

        /** The call is over, or has been handed back to the conversation. */
        fun closeCallScreen()

        /** The corner window should take [cornerAspect] now: a group call's window has followed someone else. */
        fun cornerShapeChanged() {}
    }

    companion object {
        /**
         * The proportions a picture is assumed to have before its own are
         * known, matching the web.
         */
        private const val DEFAULT_ASPECT = 9f / 16f

        /**
         * The dock's floor: 14 above the bottom, and above the system bars, as the web's dock stands. There is no
         * chevron under it any more: the dock's own chat button is the toggle.
         */
        private const val DOCK_FLOOR = 14

        /** The web's group bar: 14 from the stage's edges, and the gap between its connection and the dock. */
        private const val BAR_INSET = 14
        private const val BAR_SPACING = 10
        private const val RECORDING_GAP = 8
        private const val RECORDING_AFTER_CONNECTION = 0
        private const val RECORDING_AT_START = 1
        private const val RECORDING_OVER_BAR = 2

        /**
         * Between the last tile and the dock: the bar's top padding, 8, and the pixel its 64-pixel minimum leaves over
         * the dock it centres.
         */
        private const val BAR_GAP = 9
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Ours, by the ids a host arranges them by, as buildControls made them (CallsPieces). Above init: Kotlin
     *  initializes in order, and one declared below would be reset after buildControls filled it. */
    private var ourControls: List<CallsControl> = emptyList()
    private var stagePiece: View? = null

    private lateinit var stage: SurfaceViewRenderer
    private lateinit var selfStage: SurfaceViewRenderer
    private lateinit var selfScrim: View
    private lateinit var peerSwap: CallsIconView

    /// Where each small tile has been dragged to, and the finger it is
    /// following.
    private var dragFromX = 0f
    private var dragFromY = 0f
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragging = false
    private lateinit var chip: SurfaceViewRenderer
    /**
     * The other side's second picture. Someone sharing their screen with the
     * camera still on sends two, and one replacing the other is how you end up
     * watching a face while they point at a document.
     */
    private lateinit var peerChip: SurfaceViewRenderer
    private lateinit var grid: CallsGrid
    private lateinit var avatar: TextView
    private lateinit var nameLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var controls: CallsDock
    /** A group call's room on its way, before the dock. */
    private lateinit var connectionPill: CallsConnectionPill

    private var muteButton: CallsRoundButton? = null
    private var lockedMicButton: CallsRoundButton? = null
    private var cameraButton: CallsRoundButton? = null
    private var flipButton: CallsRoundButton? = null
    private var speakerButton: CallsRoundButton? = null
    private var shareButton: CallsRoundButton? = null

    /** Add Person, latched while its list is open: only where the host gives whom to add ([CallsAddPerson]). */
    private var addPersonButton: CallsRoundButton? = null
    private var addPerson: CallsAddPersonPanel? = null

    /** A moderator's Host Controls (`.bm-host-controls`): the room's rules in a menu above the dock. */
    private var hostControlsButton: CallsRoundButton? = null
    private var settingsButton: CallsRoundButton? = null
    private var pipButton: CallsRoundButton? = null
    private var answerButton: CallsRoundButton? = null

    /**
     * Show Chat / Hide Chat (the web's `.wplus-toggle-chat`): the conversation beside the call, or the call over it.
     * Only where the host has a chat to show ([CallsPlugin.chatToggle]); on while the chat shows ([setChatShown]).
     */
    private var chatButton: CallsRoundButton? = null

    /** Whether the host shows the conversation beside the call now ([setChatShown]). */
    private var chatShown = false

    /**
     * A viewer's only way to ask to speak in a broadcast room. The web draws it
     * on exactly the same condition and nowhere else.
     */
    private var handButton: CallsRoundButton? = null

    /** Held so a group call can turn its handset into a door. */
    private var endButton: CallsRoundButton? = null
    private lateinit var peerNotice: LinearLayout
    private lateinit var peerNoticeText: TextView
    private lateinit var peerNoticeIcon: CallsIconView

    /**
     * The other side has dropped, said over a picture that has stopped moving.
     * `.bm-placeholder-participant-lost-message`, which the web draws whatever
     * else is on the stage; here it is only needed over a picture, because
     * without one the status line already says it.
     */
    private lateinit var lostNotice: LinearLayout
    private var lostNoticeText: TextView? = null

    /**
     * What went wrong, said on the call screen rather than behind it.
     *
     * Everything the engine raises — a room that would not take us, a peer
     * that would not connect, a microphone we were refused, the reason a call
     * ended — leaves as `emit("error")` and becomes a toast in the web view.
     * This screen covers the web view, so none of them were ever seen. The
     * card is the web's own `.bm-placeholder-no-access-message`, and it keeps
     * the toast's ten seconds because that is what it stands in for.
     */
    private lateinit var errorNotice: LinearLayout
    private lateinit var errorNoticeText: TextView
    private lateinit var errorNoticeIcon: CallsIconView
    private lateinit var errorNoticeCard: GradientDrawable
    private val errorNoticeAway = Runnable { hideError() }

    /** Configure, drawn on the call instead of over it. */
    private var panel: CallsPanel? = null

    /** Device Settings, from the dock's settings button or the join notice's Preview ([showDevices]). */
    private var devices: CallsDevicePanel? = null

    /** The notice of a group call's join ([drawJoinNotice]), while it stands. */
    private var joinNotice: CallsJoinNotice? = null
    private var joinNoticeShown = false
    private var joinNoticeDone = false
    private var joinNoticeCall = ""
    private val joinNoticeAway = Runnable { dismissJoinNotice() }

    /** A moderator's toasts, in the stage's top end corner ([CallsHostToasts]). */
    private lateinit var hostToasts: CallsHostToasts

    /** End for Everyone's question, on the call ([confirm]). */
    private var question: CallsConfirm? = null

    // A recording ([CallsRecording])

    /** The group call's recording as the state names it: null for none, and in a call that is not a group call. */
    internal var recording: CallsRecording? = null
        private set
    private val recordingLate = CallsRecordingLate()
    private lateinit var recordingPill: CallsRecordingPill
    /**
     * Where REC stands: in the bar after the connection ([RECORDING_AFTER_CONNECTION]); in the connection's place at the
     * bar's start, the connection stepping aside, where the dock at its smallest leaves no room beside both
     * ([RECORDING_AT_START]); at the top of the call, centred, where it leaves none even then, clear of every tile's name
     * at the bottom of its tile ([RECORDING_OVER_BAR]). A moderator's REC, with Stop, beside a dock with Host Controls on
     * a phone is the second; Starting recording… and Saving recording… on a phone, for their few seconds, the third.
     */
    private var recordingPlace = RECORDING_AFTER_CONNECTION
    private var recordingSavingUntil = 0L
    private var recordingNotice: CallsRecordingNotice? = null
    private var recordingQuestion: CallsConfirm? = null
    private var recordingQuestionContent: List<String> = emptyList()
    /** A moderator's confirm of Start or Stop, open while it fits: `start` or `stop`. */
    private var recordingConfirm: CallsConfirm? = null
    private var recordingConfirming: String? = null
    /** The answers given on this screen, by the recording's id, until the engine says them back. */
    private val recordingAnswers = HashMap<String, String>()
    /** Recordings said to a screen reader as they started. */
    private val recordingAnnounced = HashSet<String>()
    private val recordingTick = object : Runnable {
        override fun run() {
            drawRecordingPill()
            if (recording?.state == "on" && recordingPill.visibility == View.VISIBLE) postDelayed(this, 1000L)
        }
    }
    private val recordingSaved = Runnable { drawRecordingPill() }

    /** The state the screen was last given, which Device Settings reads. */
    internal var lastState: JSONObject = JSONObject()
        private set

    internal val groupCall: Boolean get() = isGroupCall
    internal val videoCall: Boolean get() = isVideoCall

    /** Whether this side may send at all: a viewer in a broadcast room may not. */
    internal val mayPublish: Boolean get() = !isGroupCall || canPublish

    /** A moderator keeps this side's microphone off. */
    internal val micHeld: Boolean get() = isGroupCall && micLocked

    /** Talking while muted, as "You are muted" hears it: said in Device Settings' microphone row while that is open. */
    internal var mutedTalking: Boolean = false

    internal val hostActivity: Activity get() = host.activity

    /** How far above the bottom a card over the dock stands. */
    internal fun devicesClearance(): Int = errorNoticeClearance(bottomInset)

    internal fun topClearance(): Int = topInset()

    /** Keeps a panel's second line current while it is up. */
    private var panelTick: Runnable? = null

    /** A moderator's menu, open at the button it came from ([togglePopover]). */
    private var popover: CallsPopover? = null

    /** What the open menu is for: `room`, or the identity of the person whose menu it is. */
    private var popoverKey: String? = null

    /** When the last menu closed, so the press that closed it does not also put the dock away. */
    private var popoverClosedAt = 0L

    /**
     * `.bm-group-call-status`: a line of glass at the top of the call, centred, for what the call has to say for a
     * moment ([showStatusLine]).
     */
    private lateinit var statusLine: TextView
    private val statusLineAway = Runnable { statusLine.visibility = View.GONE }

    private var roster: List<CallsRoomParticipant> = emptyList()

    private var inRoom = false

    /** What the navigation bar takes at the bottom of the box, as
     *  `applyBottomInset` last read it. The grid stops above it. */
    private var bottomInset = 0
    private var canPublish = true

    /**
     * A call with more than two people. Not the same question as [inRoom]: a
     * direct call the server moved into a room has a roster too, and it is
     * still a call between two people.
     */
    private var isGroupCall = false
    private var callMode = "participatory"

    /**
     * Whether the call can carry a shared screen at all. An engine of an
     * app's own may; an app on the WordPlus SDK's calls cannot yet, and a
     * button that opens the system's consent dialog for nothing is worse than
     * no button — iOS's screen has the same switch (`canShareScreen`).
     */
    private var canShareScreen = true

    /** The mesh is keeping the room's join waiting for a place in the call. */
    private var waitingForPlace = false

    /**
     * The room was switched to broadcast, or this side was muted by a
     * moderator. Both are rules rather than settings, and the control that
     * would undo them is not drawn.
     */
    private var micLocked = false

    /**
     * Whether this call's roster has said yet whether a moderator keeps the
     * microphone off, so the first word of it, a newcomer to a muted room, is
     * not announced as a moderator's doing just now.
     */
    private var micLockKnown = false
    private var handRaised = false

    /** Whether the identity block is laid out for a room or for a face. */
    private var identityInRoom: Boolean? = null

    private var remoteVideo: VideoTrack? = null
    private var remoteScreen: VideoTrack? = null

    /**
     * What the other side says it is actually sending.
     *
     * A track exists for every source from the moment the call is negotiated -
     * the warm transceivers see to that - so a track's existence says nothing
     * about whether anything is coming down it. Only the announcement does.
     * Without this an audio call arrives with a camera track and a screen
     * track that carry nothing, the screen takes the stage because a share is
     * what someone means to be looked at, and the other person's picture is
     * left in the corner behind an empty rectangle.
     *
     * It is the same rule the web draws by.
     */
    private var peerStateKnown = false
    private var peerCameraOn = false
    private var peerScreenOn = false

    /**
     * The other side of a two-person room changing its camera's codec
     * ([CallsCameraSwitch]), as the room holds it: until the frame that ends
     * it, the camera turned off or the failsafe.
     */
    private var peerOptimizing = false

    /** Over the stage while that lasts, wherever their camera is the one on the stage. */
    private val stageOptimizing = CallsOptimizingView(context)

    /**
     * What of the other side's stopped arriving in a two-person room
     * ([CallsStallWatch]), for [applyStageStalled]: their picture on the
     * stage blurred, and the one in the corner, and "Video interrupted" with
     * why over a camera that alone stopped.
     */
    private var peerStalled: CallsStalled? = null
    private val stageStalled = CallsStalledView(context, main = true)
    private val cornerStalled = CallsStalledView(context)

    /**
     * The other side's picture, the placeholder's backdrop (blurred, as the
     * grid's tiles and iOS draw it), and the address it came from.
     */
    private var peerFaceUrl = ""
    private var peerFace: Bitmap? = null

    /**
     * What each renderer is showing, and whether it has anything to show yet.
     *
     * The picture is revealed by the first frame of it, never by the track it
     * will arrive on. See [Reveal].
     */
    private val stageReveal = Reveal("stage", { stage }) { drawIdentity() }
    private val cornerReveal = Reveal("peer tile", { peerChip }) { drawPeerVideo() }
    private val chipReveal = Reveal("own tile", { chip }) { drawSelfStage() }
    private val selfReveal = Reveal("own picture", { selfStage }) { drawSelfStage() }

    private var swapped = false
    private var stopped = false
    private var localVideo: VideoTrack? = null

    private var phase = "idle"

    /**
     * The last name this call was able to put to the person, and the thread it
     * belonged to.
     *
     * The name is not always there on the first frame — the page may not have
     * had it and the site is still being asked — and `reset()` blanks it before
     * the ending is drawn. So the word "Unknown" appeared for a moment at both
     * ends of a call that knew perfectly well who it was with. Keyed on the
     * thread so the next call to someone else starts from nothing rather than
     * wearing the last person's name, and not cleared when the thread goes to
     * zero, because that is the ending itself.
     */
    private var lastName = ""
    private var lastNameThread = 0

    /**
     * Whether this is drawn in a band rather than over the whole screen.
     *
     * The same call, at the size the page made room for. It is not a
     * second layout — only the metrics the full screen already switches to
     * for a room, where the identity has to clear a grid.
     */
    var compact = false
        set(value) {
            if (field == value) return
            field = value
            applyIdentityMetrics()
            applyBoxMetrics()
            fitControls()
        }

    private var isVideoCall = false
    private var connectedAt = 0L
    private var controlsVisible = true

    /**
     * The shape of what is on the stage, so the corner window is cut to the
     * picture rather than to the phone it is on.
     */
    private var pictureRatio: Rational? = null

    /**
     * Whether each corner tile is standing up or lying down.
     *
     * The only thing about a picture its tile's shape follows. The tile used
     * to take the exact ratio of every frame size the camera reported, and a
     * camera reports several: it opens at whatever format it feels like,
     * settles on the one that was asked for, and steps down again whenever the
     * encoder decides the connection cannot carry it. Each of those moved the
     * tile's bottom edge and changed what the fill cropped, which is one's own
     * face appearing to zoom for no reason anybody could see.
     *
     * Orientation does not move like that. It is the same for the whole call
     * unless the phone is turned, and then the tile should turn with it.
     */
    private var chipTall = true
    private var peerChipTall = true

    private val tick = object : Runnable {
        override fun run() {
            // Stopped rather than merely hidden in a room: the roster can
            // arrive after the phase has already started this, and a clock
            // nobody is shown is a second of work a call does not need.
            if (inRoom || (phase != "in_progress" && phase != "reconnecting")) return

            statusLabel.text = pieceStatus() ?: durationText()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(CallsTheme.stage(context))

        buildStage()
        buildIdentity()
        buildPeerNotice()
        buildLostNotice()
        buildControls()
        // After the dock, because it sits above it and is drawn over it.
        buildErrorNotice()
        // Over the grid and the dock, under a panel or a menu, which come later.
        buildStatusLine()

        // A moderator's toasts: over the tiles, under the menus.
        hostToasts = CallsHostToasts(context)
        addView(hostToasts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        watchBoxes()
    }

    /** Start following the call. The host calls this once it is in a window. */
    fun attach() {
        val engine = CallsEngine.get(context)

        engine.screenListener = this

        val state = engine.state()

        applyState(state)

        host.requestCallPermissions(state)
    }

    /** Stop following it, and let go of everything holding a surface. */
    fun detach() {
        val engine = CallsEngine.get(context)

        if (engine.screenListener === this) engine.screenListener = null

        handler.removeCallbacksAndMessages(null)

        chipReveal.show(null)
        selfReveal.show(null)
        stageReveal.show(null)
        cornerReveal.show(null)

        stage.release()
        selfStage.release()
        chip.release()
        peerChip.release()
        grid.release()
    }

    /**
     * Keep the dock clear of the system bars.
     *
     * The window reaches behind the navigation bar and nothing insets anything
     * for us — iOS gets this from `safeAreaLayoutGuide` and there is no
     * equivalent here. The dock sat 14dp from the *window's* bottom edge,
     * which on this phone is 36dp below the bottom of the screen: the controls
     * were drawn underneath the navigation bar and a ringing call had no
     * visible Answer at all.
     *
     * Read rather than assumed, because a gesture bar, a three-button bar and
     * a notch are three different numbers, and they change when the phone is
     * turned.
     */
    fun applyBottomInset(bottom: Int) {
        // A band has nothing under it to clear: no navigation bar, and the
        // page owns everything below the box.
        if (compact) {
            bottomInset = 0

            applyBoxMetrics()
            applyGridMetrics()

            return
        }

        bottomInset = bottom

        applyGridMetrics()

        (controls.layoutParams as? FrameLayout.LayoutParams)?.let {
            // The web's floor under the dock, above the bars.
            it.bottomMargin = bottom + dp(DOCK_FLOOR)
            controls.layoutParams = it
        }

        (errorNotice.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = errorNoticeClearance(bottom)
            errorNotice.layoutParams = it
        }
    }

    /**
     * A rotation, a fold, a split-screen resize or the corner window: all of
     * them change the box a picture is drawn in.
     *
     * This lived at the end of [showOverLockScreen], which runs as the very
     * first thing in `onCreate` — before the views it names exist. Every
     * Android call has died on `lateinit property stage has not been
     * initialized` since 9 Sep, which is to say the Android call screen has
     * not opened at all.
     */
    private fun watchBoxes() {
        for (box in listOf(stage, peerChip)) {
            box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> viewChanged() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildStage() {
        val egl = CallsMedia.get(context).eglBase

        // Held here rather than read from the field inside the callback below.
        // These run on libwebrtc's own delivery thread, and that thread reached
        // `handler` as null and took the whole process down with it — the crash
        // on returning to the call from the band:
        //
        //     NullPointerException: Attempt to invoke virtual method
        //     'boolean android.os.Handler.post(java.lang.Runnable)'
        //     on a null object reference
        //
        // An exception thrown in one of these does not stay in it. It returns
        // through libwebrtc's JNI bridge, which checks for a pending exception
        // and aborts:  "Fatal error in jvm.cc, line 81. Check failed: false",
        // SIGABRT on the IncomingVideoStream thread. So the body is guarded as
        // well, because a crash here costs the call and everything in it.
        val ui = handler

        stage = SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {}

                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    try {
                        val sideways = rotation == 90 || rotation == 270
                        val shown = if (sideways) height else width
                        val tall = if (sideways) width else height

                        if (shown <= 0 || tall <= 0) return

                        ui.post { pictureRatio = ratioWithin(shown, tall) }
                    } catch (e: Throwable) {
                        CallsLog.write("stage resolution callback: ${e.javaClass.simpleName}")
                    }
                }
            })
            // Fitted from the first frame, not from whenever the layout pass
            // gets around to it, so there is no cropped frame to see first.
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

            // No hardware scaler on the stage, or the picture flinches every
            // time the far end changes resolution.
            //
            // `setEnableHardwareScaler(true)` is the only thing that makes
            // `updateSurfaceSize` do anything, and what it does is fix the
            // surface to `min(view, incoming frame)` — recomputed on every
            // resolution change. A call ramps 320x180, 480x270, 1280x720,
            // 1920x1080 as the bandwidth estimate rises, so the surface is
            // reallocated four times, and at 320 wide it is 22% of a 1440-wide
            // view. Between the reallocation and the next composited frame the
            // picture is drawn at the new surface size inside a view that has
            // not moved: it goes narrow for a moment and snaps back: the
            // video seen at less than its full width for less than a second.
            //
            // Off, the surface stays the view's size and a resolution change
            // only swaps the texture. The cost is that the GPU does the
            // scaling the display controller would have done, over 1440x810 —
            // which is nothing next to a picture that flinches.
            setEnableHardwareScaler(false)
            visibility = View.GONE
        }

        // Wrapped and centred, not filled, or the fitted scaling below is
        // thrown away before it can do anything.
        //
        // `SurfaceViewRenderer` only ever consults its scaling type in
        // `onMeasure`, and `VideoLayoutMeasure` discards the size it worked out
        // whenever the measure spec is EXACTLY — which is exactly what
        // `MATCH_PARENT` in a `FrameLayout` hands it. The view then stays the
        // full height of the screen, and `EglRenderer` has no letterbox path at
        // all: it centre-crops the picture to whatever shape the view is. So
        // `SCALE_ASPECT_FIT` was set on every frame and never applied once.
        //
        // The crop that follows is as bad as the shapes are unalike. A portrait
        // camera into a tall screen keeps about two thirds of its width, which
        // looks close enough to right to pass a glance. A 16:9 desktop camera —
        // anyone calling from a laptop — keeps barely a quarter, and the person
        // on the other end is mostly off the sides of the screen.
        //
        // `WRAP_CONTENT` measures AT_MOST instead, so the fitted size survives
        // and the view becomes the picture's own rectangle. The letterbox is
        // then just the root's own black showing around it.
        addView(
            stage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // Over the stage's picture while it stopped arriving, wherever the
        // picture lies ([applyStageStalled]).
        addView(
            stageStalled,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Over the stage while the other side's camera changes codec, below
        // the corners and the controls ([applyStageOptimizing]).
        addView(
            stageOptimizing,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // The web fills a ringing call with your own camera and lays the
        // avatar, the name and the status over a gradient on top of it
        // (`.bm-placeholder-video`, `_call-stage.scss`). Same picture, same
        // gradient, same order.
        selfStage = SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            // Full-bleed like the stage, so the same flinch — see above.
            setEnableHardwareScaler(false)
            visibility = View.GONE
        }

        addView(
            selfStage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        selfScrim = View(context).apply {
            visibility = View.GONE
            isClickable = false

            // The stops are the stylesheet's, to the number.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(97, 0, 0, 0), Color.argb(15, 0, 0, 0), Color.argb(153, 0, 0, 0))
            )
        }

        addView(
            selfScrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // A call with more than two people is a grid rather than a stage.
        grid = CallsGrid(context, egl).apply {
            visibility = View.GONE
            onPerson = { person -> CallsModeration.showFor(this@CallsStage, person) }
            // Pin for Me from the pin on a tile, anyone's.
            onPin = { person -> this@CallsStage.togglePinForMe(person.identity) }
            // Qualified: inside `apply` a bare `performClick()` is the grid's
            // own, which has no listener, so the tap went nowhere at all.
            onTap = { this@CallsStage.performClick() }
        }

        // No insets here: `applyGridMetrics` owns them, and reads them from
        // what is drawn rather than from a pair of constants kept in two
        // places that have to agree.
        addView(
            grid,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // No hardware scaler on either tile. It answers a resolution change by
        // handing the surface a new fixed size, which throws the buffers away
        // and rebuilds them — a black flash in the corner every time the
        // camera steps, for a saving nothing this size can notice.
        chip = SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, shapeWatcher { chip })
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderMediaOverlay(true)
            visibility = View.GONE
        }

        addView(
            chip,
            FrameLayout.LayoutParams(dp(104), tileHeight(dp(104), true), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(48)
                marginEnd = dp(16)
            }
        )

        // The second chip sits under the first, and tapping it swaps what is
        // on the stage.
        peerChip = SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, shapeWatcher { peerChip })
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderMediaOverlay(true)
            visibility = View.GONE

            setOnClickListener {
                swapped = !swapped

                drawPeerVideo()
                viewChanged()
            }
        }

        // The web puts a flip glyph on this tile (`.bm-switch-videos`), which is
        // the only thing that says the tile can be tapped at all. Without it
        // the swap is a gesture nobody discovers.
        peerSwap = CallsIconView(context).apply {
            set(CallsIcons.swapVideos)
            visibility = View.GONE
            contentDescription = CallsTheme.string(context, "switch_videos", "Switch Videos")
        }

        addView(
            peerChip,
            FrameLayout.LayoutParams(dp(104), tileHeight(dp(104), true), Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(16)
            }
        )

        // Over the corner's picture while it stopped arriving, following the
        // tile wherever it is dragged.
        addView(
            cornerStalled,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        addView(
            peerSwap,
            FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(16) + dp(104) - dp(20)
            }
        )

        stackTiles()

        draggable(chip)
        draggable(peerChip, peerSwap)
    }

    /**
     * `.bm-placeholder-no-access-message`: the same card as the peer notice —
     * the card radius, 10 by 14 of padding, a glyph ten from the words — on
     * the danger colour at 0.88 with no hairline, and a 22dp alert instead of
     * the 20dp microphone.
     *
     * Above the dock rather than in the placeholder the stylesheet puts it in,
     * because a room call has no placeholder and a failure in one still has to
     * be readable. That is also where the toast it replaces would have been.
     */
    private fun buildErrorNotice() {
        errorNoticeText = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        val glyph = CallsIconView(context).apply { set(CallsIcons.alert) }

        errorNoticeIcon = glyph
        errorNoticeCard = GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(CallsTheme.withAlpha(CallsTheme.color(context, "danger", Color.rgb(232, 65, 65)), 224))
        }

        errorNotice = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))

            background = errorNoticeCard

            addView(glyph, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(
                errorNoticeText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = dp(10) }
            )
        }

        addView(
            errorNotice,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = errorNoticeClearance(0)
            }
        )
    }

    /**
     * Above the dock, whatever the dock is standing on: its own clearance of
     * the system bars, then the tallest a control ever is, then twelve. The
     * dock shrinks to 34dp on a narrow phone and this does not follow it down
     * — a slightly wider gap is the right way to be wrong about it.
     */
    private fun errorNoticeClearance(bottom: Int): Int =
        bottom + dp(DOCK_FLOOR) + dp(50) + dp(12)

    /**
     * The latest message wins and restarts the ten seconds: errors in a call
     * arrive in bursts about the same failure, and the last one is the one
     * worth reading.
     */
    override fun onScreenError(message: String) {
        post {
            if (message.isEmpty()) return@post

            errorNoticeCard.setColor(CallsTheme.withAlpha(CallsTheme.color(context, "danger", Color.rgb(232, 65, 65)), 224))
            errorNoticeCard.setStroke(0, Color.TRANSPARENT)
            errorNoticeIcon.set(CallsIcons.alert)
            errorNoticeText.text = message
            errorNotice.visibility = View.VISIBLE

            removeCallbacks(errorNoticeAway)
            postDelayed(errorNoticeAway, 10_000L)
        }
    }

    override fun onScreenMicHeld() {
        showModeratorNotice(true)
    }

    /**
     * What a moderator just did to this side's microphone, for six seconds:
     * in a room, where moderators are, in the call's status line at the top,
     * as the web's useModeratorNotice says it; anywhere else in the error
     * notice's place, on the peer notice's glass rather than in red, because
     * it is news, not a failure.
     */
    fun showModeratorNotice(muted: Boolean) {
        post {
            if (!::errorNotice.isInitialized) return@post

            val words = if (muted) {
                CallsTheme.string(context, "moderator_muted_you", "A moderator muted your microphone.")
            } else {
                CallsTheme.string(context, "moderator_allowed_you", "A moderator allowed you to unmute your microphone.")
            }

            if (inRoom) {
                showStatusLine(words, 6_000L)
                return@post
            }

            errorNoticeCard.setColor(Color.argb(140, 0, 0, 0))
            errorNoticeCard.setStroke(1, Color.argb(20, 255, 255, 255))
            errorNoticeIcon.set(if (muted) CallsIcons.silenced else CallsIcons.mic)
            errorNoticeText.text = words
            errorNotice.visibility = View.VISIBLE

            removeCallbacks(errorNoticeAway)
            postDelayed(errorNoticeAway, 6_000L)
        }
    }

    private fun hideError() {
        removeCallbacks(errorNoticeAway)

        if (!::errorNotice.isInitialized) return

        errorNotice.visibility = View.GONE
        errorNoticeText.text = null
    }

    /**
     * Ask something on the call rather than over it.
     *
     * Configure and Participants were `AlertDialog`s — a piece of Android laid
     * on top of the call rather than a piece of it. They are drawn on this
     * view now, in the glass the rest of it is made of, above the dock and at
     * its width.
     *
     * `refresh` is asked for a new second line once a second for as long as the
     * card is up. The byte counts behind the gear move — the connection is
     * sampled every two seconds — and the web counts them up on screen as they
     * arrive, so a card that held the opening figure would disagree with it.
     */
    fun showPanel(
        title: String,
        message: String? = null,
        rows: List<CallsPanelRow>,
        refresh: (() -> String?)? = null
    ) {
        hidePanel()

        val panel = CallsPanel(context)

        panel.placeAbove(errorNoticeClearance(bottomInset))
        panel.fill(title, message, rows) { hidePanel() }

        addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        this.panel = panel

        if (refresh == null) return

        panelTick = object : Runnable {
            override fun run() {
                val showing = this@CallsStage.panel ?: return

                showing.update(refresh())
                postDelayed(this, 1_000L)
            }
        }

        postDelayed(panelTick!!, 1_000L)
    }

    fun hidePanel() {
        panelTick?.let { removeCallbacks(it) }
        panelTick = null

        panel?.let { removeView(it) }
        panel = null

        hideDevices()
        hideAddPerson()
    }

    // Add Person

    /**
     * Whom the user may bring in, over the call, where the host gives them ([CallsAddPerson]). The same press closes it,
     * as the web's Add Person does.
     */
    fun showAddPerson() {
        if (addPerson != null) return hideAddPerson()

        closePopover()
        hidePanel()

        if (CallsAddPersonStore.current == null) return

        val shown = CallsAddPersonPanel(
            context,
            close = { hideAddPerson() },
            failed = { error ->
                hideAddPerson()
                onScreenError(error)
            },
        )

        shown.placeAbove(errorNoticeClearance(bottomInset))

        addView(
            shown,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        addPerson = shown
        shown.open()
        drawAddPerson()
    }

    fun hideAddPerson() {
        addPerson?.let { removeView(it) }
        addPerson = null
        addPersonButton?.isOn = false
    }

    /**
     * Add Person once the call is up, where the host gives it, what the web asks of it, `action === 'call_in_progress'`, and
     * of a group call its connection. An open panel stays while the call is on and draws the rings as they change: a ring
     * moves a call between two to its group call, which connects under it.
     */
    private fun drawAddPerson() {
        val given = CallsAddPersonStore.current != null

        addPersonButton?.visibility = if (given && phase == "in_progress") View.VISIBLE else View.GONE

        if (!given || phase !in listOf("in_progress", "connecting", "reconnecting")) return hideAddPerson()

        addPersonButton?.isOn = addPerson != null
        addPerson?.draw()
    }

    // Device Settings

    /**
     * Device Settings over the call, above the dock; called again while it is open, it closes, as the web's Configure
     * does. `start` runs the tests from the start: `both` (the camera's preview and the microphone's test) or
     * `microphone`. Opening it closes a menu, and the join notice, which it answers.
     */
    fun showDevices(start: String?) {
        if (devices != null) {
            hideDevices()
            return
        }

        closePopover()
        panelTick?.let { removeCallbacks(it) }
        panel?.let { removeView(it) }
        panel = null
        hideAddPerson()
        dismissJoinNotice()

        val shown = CallsDevicePanel(context, this, start)

        shown.onClose = {
            if (devices === shown) devices = null
            // A moderator's ask comes back once the settings go.
            drawAskCard()
        }

        // Under the toasts and a menu's question, over everything else.
        addView(shown, indexOfChild(hostToasts), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        devices = shown
        // Where the settings stand above the dock, so a moderator's ask steps aside while they are open.
        drawAskCard()
    }

    fun hideDevices() {
        devices?.close()
        devices = null
    }

    // The notice of a group call's join

    /**
     * As a group call is first joined, where the app asks for it (`devicesOnJoin`) and this side may send with both
     * the microphone and the camera off: that they are off, and Preview. It goes after ten seconds, once either is
     * turned on, once Device Settings opens, on its ×, or when the call ends; a reconnect is not a join. A menu over
     * the dock hides it while it is open.
     */
    private fun drawJoinNotice() {
        // A moderator's ask stands where the notice does, and steps aside for a menu as it does.
        drawAskCard()

        val key = CallsEngine.get(context).currentCallKey()

        if (key != joinNoticeCall) {
            joinNoticeCall = key
            joinNoticeDone = false
            dismissJoinNotice()
        }

        val local = lastState.optJSONObject("local")
        val microphoneOn = local?.optBoolean("muted", true) == false
        val cameraOn = local?.optBoolean("camera", false) == true

        if (!joinNoticeDone && isGroupCall && phase == "in_progress") {
            joinNoticeDone = true

            val asked = CallsConfigStore.current(context)?.devicesOnJoin ?: false
            // The user has not said Do Not Show Again.
            val wanted = CallsEngine.get(context).joinNotice != false

            if (asked && wanted && mayPublish && !microphoneOn && !cameraOn) {
                joinNoticeShown = true
                removeCallbacks(joinNoticeAway)
                postDelayed(joinNoticeAway, 10_000L)
            }
        }

        if (joinNoticeShown && (microphoneOn || cameraOn || !mayPublish || devices != null || phase == "idle")) {
            dismissJoinNotice()
        }

        if (!joinNoticeShown) return

        val notice = joinNotice ?: CallsJoinNotice(
            context,
            video = isVideoCall,
            preview = { showDevices(if (isVideoCall) "both" else "microphone") },
            dismiss = { dismissJoinNotice() },
            // Do Not Show Again where the host keeps the user's choice, as the web's.
            never = if (CallsEngine.get(context).joinNotice != null) {
                {
                    CallsEngine.get(context).joinNoticeNever()
                    dismissJoinNotice()
                }
            } else null
        ).also { made ->
            joinNotice = made

            addView(made, indexOfChild(hostToasts), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            })

            made.alpha = 0f
            made.translationY = 4 * resources.displayMetrics.density
            made.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }

        (notice.layoutParams as? FrameLayout.LayoutParams)?.let {
            val bottom = errorNoticeClearance(bottomInset)
            val wide = minOf(dp(560), width - dp(24)).coerceAtLeast(0)

            if (it.bottomMargin != bottom || (wide > 0 && it.width != wide)) {
                it.bottomMargin = bottom
                if (wide > 0) it.width = wide
                notice.layoutParams = it
            }
        }

        // It waits under a recording's notice, as the web's does.
        notice.visibility = if (popover != null || recordingNotice != null) View.GONE else View.VISIBLE
    }

    private fun dismissJoinNotice() {
        removeCallbacks(joinNoticeAway)
        joinNoticeShown = false
        joinNotice?.let { removeView(it) }
        joinNotice = null
    }

    // A moderator's ask ([CallsAsks])

    /** The asks this user was sent, the oldest first, as the state names them. */
    private var asks: List<CallsTurnOnAsk> = emptyList()
    private var askCard: CallsAskCard? = null

    /** Why each device would not turn on, from the last press of Turn On, for the ask [askShown]. */
    private var askStuck: Map<String, CallsAsks.Stuck> = emptyMap()
    private var askShown = ""
    private var askTrying = false

    /** The devices Turn On asked the system for, waiting on its answer. */
    private var askWaiting: List<String> = emptyList()

    /** What each ask was answered already, by its id and device: an ask for both is two under one id. */
    private val askTold = HashMap<String, MutableSet<String>>()

    /** A person's menu says what it offers again, as an "Asked…" runs out or an answer comes. */
    fun refreshPopover() {
        popover?.refresh()
    }

    private fun applyAsks(state: JSONObject) {
        val engine = CallsEngine.get(context)

        asks = state.optJSONArray("asks")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                val one = list.optJSONObject(index) ?: return@mapNotNull null
                val device = one.optString("device")

                CallsTurnOnAsk(one.optString("id"), device, one.optString("by"))
                    .takeIf { it.id.isNotEmpty() && it.by.isNotEmpty() && (device == "camera" || device == "microphone") }
            }
        } ?: emptyList()

        CallsAsks.answers = state.optJSONArray("askAnswers")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                val one = list.optJSONObject(index) ?: return@mapNotNull null

                CallsTurnOnAnswer(one.optString("id"), one.optString("from"), one.optString("device"), one.optString("answer"))
                    .takeIf { it.id.isNotEmpty() && it.from.isNotEmpty() }
            }
        } ?: emptyList()

        if (engine.carriesAsks && inRoom && isGroupCall && engine.canModerate()) hostToasts.heard(CallsAsks.answers, roster)

        // Turned on, from the card or the dock: said to each who asked for it, and their asks go, after this drawing, as
        // the engine draws the screen again for it.
        val local = state.optJSONObject("local")
        val on = mapOf("camera" to (local?.optBoolean("camera", false) == true), "microphone" to (local?.optBoolean("muted", true) == false))

        for (ask in asks) {
            if (on[ask.device] != true) continue

            tellAsk(ask, "on")
            post { engine.dismissAsk(ask.id, ask.device) }
        }

        drawAskCard()
    }

    /**
     * The card over the dock for the newest ask, in the join notice's place, while the call carries asks and this user
     * may send: for its device, and the other one too where the same moderator asked for it, both while both are off.
     * It steps aside while Device Settings or a menu is open.
     */
    private fun drawAskCard() {
        val engine = CallsEngine.get(context)
        val ask = asks.lastOrNull()
        val local = lastState.optJSONObject("local")
        val microphoneOn = local?.optBoolean("muted", true) == false
        val cameraOn = local?.optBoolean("camera", false) == true

        // It waits under a recording's notice, as the web's does.
        if (!engine.carriesAsks || !inRoom || !isGroupCall || phase != "in_progress" || !mayPublish || devices != null || ask == null ||
            recordingNotice != null) {
            return hideAskCard()
        }

        // The camera first, on a video call alone, and neither once it is on.
        val also = asks.any { it.by == ask.by && it.device != ask.device }
        val wanted = listOf("camera", "microphone").filter { device ->
            (device == ask.device || also) && !(if (device == "camera") cameraOn else microphoneOn) && (device != "camera" || isVideoCall)
        }

        if (wanted.isEmpty()) return hideAskCard()

        // A new ask starts again from its question.
        if (askShown != ask.id) {
            askShown = ask.id
            askStuck = emptyMap()
        }

        val stuckOn = wanted.firstOrNull { askStuck[it] != null }
        val stuck = stuckOn?.let { askStuck[it] }
        val named = CallsHostToasts.plain(roster.firstOrNull { it.identity == ask.by }?.name ?: "")
        val who = named.ifEmpty { CallsTheme.string(context, "a_moderator", "A moderator") }

        val title = when {
            stuckOn != null && stuck != null -> CallsAsks.stuckTitle(context, stuckOn, stuck)
            wanted.size > 1 -> CallsTheme.string(context, "ask_title_both", "%s asks you to turn on your camera and microphone").replace("%s", who)
            wanted[0] == "camera" -> CallsTheme.string(context, "ask_title_camera", "%s asks you to turn on your camera").replace("%s", who)
            else -> CallsTheme.string(context, "ask_title_microphone", "%s asks you to turn on your microphone").replace("%s", who)
        }

        val secondary = when {
            stuck == CallsAsks.Stuck.BLOCKED -> CallsTheme.string(context, "open_settings", "Open Settings")
            stuck != null -> null
            "camera" in wanted -> CallsTheme.string(context, "device_preview", "Preview")
            else -> CallsTheme.string(context, "test_microphone", "Test Microphone")
        }

        val content = CallsAskCard.Content(
            title = title,
            steps = if (stuckOn != null && stuck != null) CallsAsks.stuckSteps(context, stuckOn, stuck) else emptyList(),
            camera = (stuckOn ?: wanted[0]) == "camera",
            primary = if (stuck == null) {
                CallsTheme.string(context, "ask_turn_on", "Turn On")
            } else {
                CallsTheme.string(context, "ask_try_again", "Try Again")
            },
            secondary = secondary,
        )

        val was = askCard

        if (was == null || was.content != content) {
            was?.let { removeView(it) }
            dismissJoinNotice()

            val made = CallsAskCard(
                context,
                content,
                primary = { turnOnAsked(wanted) },
                secondary = {
                    if (stuck == CallsAsks.Stuck.BLOCKED) {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } else {
                        showDevices(if ("camera" in wanted) "both" else "microphone")
                    }
                },
                notNow = { notNowAsked(wanted) },
            )

            askCard = made

            addView(made, indexOfChild(hostToasts), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            })

            if (was?.content?.title != title) made.announceForAccessibility(title)
        }

        val card = askCard ?: return

        card.busy = askTrying

        (card.layoutParams as? FrameLayout.LayoutParams)?.let {
            val bottom = errorNoticeClearance(bottomInset)
            val wide = minOf(dp(560), width - dp(24)).coerceAtLeast(0)

            if (it.bottomMargin != bottom || (wide > 0 && it.width != wide)) {
                it.bottomMargin = bottom
                if (wide > 0) it.width = wide
                card.layoutParams = it
            }
        }

        card.visibility = if (popover != null) View.GONE else View.VISIBLE
    }

    private fun hideAskCard() {
        askCard?.let { removeView(it) }
        askCard = null
    }

    /**
     * Turn On: each device turned on as the dock's button does, the system asked once for the ones it has not allowed,
     * which the engine opens as it allows them ([CallsAsks.onPermissions] says what it answered); a device there is none
     * of, or one the system refuses, said on the card and to whoever asked.
     */
    private fun turnOnAsked(wanted: List<String>) {
        if (askTrying) return

        val stuck = HashMap<String, CallsAsks.Stuck>()
        val missing = ArrayList<String>()

        for (device in wanted) {
            when {
                !CallsAsks.hasDevice(context, device) -> stuck[device] = CallsAsks.Stuck.NO_DEVICE
                !CallsAsks.granted(context, device) -> missing.add(device)
            }
        }

        askStuck = stuck
        stuck.forEach { (device, why) -> tell(device, why.answer) }

        for (device in wanted) if (stuck[device] == null) turnDeviceOn(device)

        if (missing.isNotEmpty()) {
            askTrying = true
            askWaiting = missing
            CallsAsks.onPermissions = { permissionsAnswered() }
            host.activity.requestPermissions(missing.map { CallsAsks.permission(it) }.toTypedArray(), CallsPermissions.REQUEST_CODE)
        }

        drawAskCard()
    }

    private fun permissionsAnswered() {
        CallsAsks.onPermissions = null
        askTrying = false

        val refused = askWaiting.filter { !CallsAsks.granted(context, it) }

        askWaiting = emptyList()

        if (refused.isNotEmpty()) {
            askStuck = askStuck + refused.associateWith { CallsAsks.Stuck.BLOCKED }
            refused.forEach { tell(it, "blocked") }
        }

        drawAskCard()
    }

    /** What the dock's button does to turn the device on. */
    private fun turnDeviceOn(device: String) {
        val engine = CallsEngine.get(context)
        val local = lastState.optJSONObject("local")

        if (device == "camera") {
            if (local?.optBoolean("camera", false) == true) return

            cameraButton?.isOn = false
            engine.setCameraEnabled(true)
        } else {
            if (local?.optBoolean("muted", true) == false) return

            muteButton?.isOn = false
            engine.setMuted(false)
        }
    }

    private fun notNowAsked(wanted: List<String>) {
        val engine = CallsEngine.get(context)

        for (device in wanted) {
            tell(device, "not_now")

            for (ask in asks) if (ask.device == device) engine.dismissAsk(ask.id, ask.device)
        }

        hideAskCard()
    }

    /** The answer, to each who asked for the device. */
    private fun tell(device: String, answer: String) {
        for (ask in asks) if (ask.device == device) tellAsk(ask, answer)
    }

    private fun tellAsk(ask: CallsTurnOnAsk, answer: String) {
        if (!askTold.getOrPut(ask.id + "|" + ask.device) { mutableSetOf() }.add(answer)) return

        CallsEngine.get(context).answerAsk(ask.id, ask.device, ask.by, answer)
    }

    // A recording ([CallsRecording])

    /** The site's words, by key with their English, for the recording's model. */
    private val say: (String, String) -> String = { key, fallback -> CallsTheme.string(context, key, fallback) }

    /**
     * A recording is drawn over a group call that is joining, up or getting its connection back: someone who joins while
     * it records hears of it with the join, before the room is up.
     */
    private val recordingDrawn: Boolean
        get() = isGroupCall && (phase == "connecting" || phase == "in_progress" || phase == "reconnecting")

    private fun applyRecording(state: JSONObject) {
        var now = if (isGroupCall) CallsRecording.of(state.optJSONObject("recording")) else null

        now?.let { shown ->
            val given = recordingAnswers[shown.id]
            if (shown.answer.isEmpty() && given != null) now = shown.copy(answer = given)
        }

        recordingLate.see(phase == "in_progress", now)

        // Stopped: "Saving recording…" for a few seconds, then nothing.
        if (now?.state == "stopping") {
            if (recording?.state != "stopping") {
                recordingSavingUntil = SystemClock.uptimeMillis() + CallsRecordingPill.SAVING_MS
                removeCallbacks(recordingSaved)
                postDelayed(recordingSaved, CallsRecordingPill.SAVING_MS + 50)
            }
        } else {
            recordingSavingUntil = 0L
            removeCallbacks(recordingSaved)
        }

        // Said once to a screen reader, to everyone REC shows to, as it starts.
        now?.let { shown ->
            if (shown.state == "on" && shown.pillShown && recordingDrawn && shown.id.isNotEmpty() && recordingAnnounced.add(shown.id)) {
                announceForAccessibility(say("rec_recorded", "This call is being recorded."))
            }
        }

        val changed = now != recording

        recording = now

        drawRecordingPill()
        drawRecordingNotice()
        drawRecordingQuestion()
        closeRecordingConfirm()

        if (changed) {
            // Host Controls' row turns over, and shows for whoever may record.
            drawHostControls()
            popover?.refresh()
        }
    }

    private fun drawRecordingPill() {
        if (!::recordingPill.isInitialized) return

        val saving = recordingSavingUntil > SystemClock.uptimeMillis()

        if (groupBar && recordingDrawn) {
            recordingPill.show(recording, saving, widthDp() < 600)
        } else {
            recordingPill.show(null, saving = false, narrow = false)
        }

        // The time, each second while it records.
        removeCallbacks(recordingTick)
        if (recordingPill.visibility == View.VISIBLE && recording?.state == "on") postDelayed(recordingTick, 1000L)

        placeRecordingPill()
    }

    /**
     * The notice to everyone else, over the dock in the join notice's place, where the site tells people by a notice: the
     * join notice and a moderator's ask wait under it.
     */
    private fun drawRecordingNotice() {
        val shown = recording?.takeIf { recordingDrawn && it.noticeShown } ?: return hideRecordingNotice()
        val late = recordingLate.isLate(shown.id)
        val content = CallsRecordingNotice.Content(shown.noticeTitle(late, say), shown.noticeNote(late, say))
        val was = recordingNotice

        if (was == null || was.content != content) {
            was?.let { removeView(it) }
            hideAskCard()
            joinNotice?.visibility = View.GONE

            val id = shown.id
            val made = CallsRecordingNotice(
                context,
                content,
                seen = { answerRecording(id, "seen") },
                never = {
                    CallsEngine.get(context).recordingNoticeNever()
                    answerRecording(id, "seen")
                },
            )

            recordingNotice = made

            addView(made, indexOfChild(hostToasts), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            })

            if (was?.content?.title != content.title) made.announceForAccessibility(content.title)
        }

        val notice = recordingNotice ?: return

        (notice.layoutParams as? FrameLayout.LayoutParams)?.let {
            val bottom = errorNoticeClearance(bottomInset)
            val wide = minOf(dp(520), width - dp(24)).coerceAtLeast(0)

            if (it.bottomMargin != bottom || (wide > 0 && it.width != wide)) {
                it.bottomMargin = bottom
                if (wide > 0) it.width = wide
                notice.layoutParams = it
            }
        }
    }

    private fun hideRecordingNotice() {
        val notice = recordingNotice ?: return

        removeView(notice)
        recordingNotice = null
        // What waited under it comes back.
        drawJoinNotice()
    }

    /**
     * The question to everyone else where the site asks them to stay or leave (`ask`), or to agree with the microphone and
     * camera off until they do (`muted`), over everything and answered before anything else: no press outside it and no
     * Back puts it away. Leave Call leaves as the dock's way out does.
     */
    private fun drawRecordingQuestion() {
        val shown = recording?.takeIf { recordingDrawn && it.asking } ?: return hideRecordingQuestion()
        val note = shown.watchNote(say)
        val lead = shown.questionLead(say)
        val yes = shown.questionYes(say)
        val content = listOf(lead, note, yes)

        if (recordingQuestion != null && recordingQuestionContent == content) return

        recordingQuestion?.close()

        // Whatever was open goes first: the question is answered before anything else.
        closePopover()
        hideDevices()
        hidePanel()
        question?.close()

        val id = shown.id
        val asked = CallsConfirm(
            context,
            say("rec_title", "This call is being recorded"),
            lead,
            yes,
            false,
            yes = { answerRecording(id, "agreed") },
            cancel = say("rec_leave", "Leave Call"),
            note = note.ifEmpty { null },
            badge = CallsIcons.record,
            dismissible = false,
            no = { CallsEngine.get(context).endCall("hangup") },
        )

        asked.onClose = {
            if (recordingQuestion === asked) {
                recordingQuestion = null
                recordingQuestionContent = emptyList()
            }
        }

        addView(asked, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        recordingQuestion = asked
        recordingQuestionContent = content
    }

    private fun hideRecordingQuestion() {
        val open = recordingQuestion ?: return

        recordingQuestion = null
        recordingQuestionContent = emptyList()
        open.close()
    }

    private fun answerRecording(id: String, answer: String) {
        recordingAnswers[id] = answer
        CallsEngine.get(context).answerRecording(id, answer)
    }

    /**
     * A moderator's Start Recording or Stop Recording, asked first on the call (the web's RecordingDialog), from Host
     * Controls or REC's Stop. Start asks with how everyone is told and the admin's settings; Stop with when it can be
     * watched.
     */
    fun askRecording(which: String) {
        val shown = recording?.takeIf { it.can } ?: return

        if (if (which == "start") shown.state != "off" else shown.state != "on") return

        val engine = CallsEngine.get(context)
        val failed = { error: String?, key: String, fallback: String ->
            post { onScreenError(error ?: say(key, fallback)) }
        }

        question?.close()

        val asked = if (which == "start") {
            CallsConfirm(
                context,
                say("rec_start_question", "Start recording?"),
                shown.toldLead(say),
                say("rec_start", "Start Recording"),
                false,
                yes = {
                    engine.startRecording { ok, error ->
                        if (!ok) failed(error, "rec_start_failed", "The recording could not be made.")
                    }
                },
                cancel = say("rec_cancel", "Cancel"),
                note = say("rec_stops_when", "It stops when the call ends, or when you press REC beside the call controls."),
                facts = shown.facts(!isVideoCall, say),
                badge = CallsIcons.record,
            )
        } else {
            CallsConfirm(
                context,
                say("rec_stop_question", "Stop recording?"),
                shown.readyLead(say),
                say("rec_stop_recording", "Stop Recording"),
                true,
                yes = {
                    engine.stopRecording { ok, error ->
                        if (!ok) failed(error, "rec_stop_failed", "The recording could not be stopped.")
                    }
                },
                cancel = say("rec_cancel", "Cancel"),
                badge = CallsIcons.record,
            )
        }

        asked.onClose = {
            if (question === asked) question = null
            if (recordingConfirm === asked) {
                recordingConfirm = null
                recordingConfirming = null
            }
        }

        addView(asked, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        question = asked
        recordingConfirm = asked
        recordingConfirming = which
    }

    /** A confirm that no longer fits closes: Start once it records or stops, Stop once it no longer records. */
    private fun closeRecordingConfirm() {
        val which = recordingConfirming ?: return
        val open = recordingConfirm ?: return run { recordingConfirming = null }
        val state = recording?.state ?: "off"
        val gone = recording == null || !recordingDrawn || (if (which == "start") state != "off" else state != "on")

        if (gone) open.close()
    }

    // A question on the call

    /**
     * A question asked on the call, in a card of its own (no system dialog): a title, a line, Cancel and the answer,
     * filled red where it is a danger. `yes` only on the answer; Cancel, the system's back and a press outside the card
     * answer no.
     */
    fun confirm(title: String, message: String, answer: String, danger: Boolean, yes: () -> Unit) {
        question?.close()

        val asked = CallsConfirm(context, title, message, answer, danger, yes)

        asked.onClose = { if (question === asked) question = null }

        addView(asked, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        question = asked
    }

    // The moderator's menus

    /** Everyone in the room now, as the menus read them. */
    val people: List<CallsRoomParticipant> get() = roster

    // Spotlight for Everyone and Pin for Me

    /**
     * Who a moderator put on the main tiles for everyone, by identity, in the order they were spotlighted: the engine's
     * state's `spotlight` in a group video call ([CallsSpotlight]), nobody elsewhere.
     */
    var spotlight: List<String> = emptyList()
        private set

    /**
     * This user's own pins (Pin for Me), by identity, in the order they were pinned: on the main tiles while nobody is in
     * the spotlight. This phone's alone, kept for the call.
     */
    var pinnedForMe: List<String> = emptyList()
        private set

    /** Pin for Me on or off for someone, from their tile's pin or their menu. */
    fun togglePinForMe(identity: String) {
        if (roster.none { it.identity == identity }) return

        pinnedForMe = CallsSpotlight.toggled(pinnedForMe, identity)
        CallsLog.write("pin for me: $identity ${if (identity in pinnedForMe) "pinned" else "unpinned"}${if (spotlight.isEmpty()) "" else ", waiting for the spotlight"}")
        drawSpotlight()
    }

    /** The spotlight and the pins as the grid and an open menu draw them. */
    private fun drawSpotlight() {
        grid.spotlight = spotlight
        grid.pinned = pinnedForMe
        chooseCornerFocus()
        popover?.refresh()
    }

    /** Who spoke last in a group call: the corner window stays on them through a silence. */
    private var cornerLast: String? = null

    /** Whom a group call's corner window shows now, by identity: null for no window. */
    private var cornerFocus: String? = null

    /** Whether this screen is the corner window now. */
    private var cornerWindow = false

    /**
     * A group call's corner window, as the web's useGroupPip: a video call has it all through, an audio call only while
     * someone else shares a screen. It follows the call: a new speaker, a screen shared or stopped.
     */
    private fun chooseCornerFocus() {
        val people = roster.map(CallsPipFocus::person)
        val chosen = if (CallsPipFocus.offered(people, isVideoCall)) {
            CallsPipFocus.pick(people, spotlight + pinnedForMe, cornerLast)
        } else {
            null
        }
        val focus = roster.firstOrNull { it.identity == chosen }

        if (focus?.speaking == true) cornerLast = focus.identity
        if (!inRoom) cornerLast = null

        val moved = cornerFocus != focus?.identity
        cornerFocus = focus?.identity

        pipButton?.visibility = if (pipOffered) View.VISIBLE else View.GONE

        // An audio call's last share stopping leaves nobody to choose, and only the person can close a window here: it
        // stays on the one it showed.
        grid.solo = if (cornerWindow) cornerFocus ?: grid.solo else null

        if (moved && cornerWindow) post { host.cornerShapeChanged() }
    }

    /** Whether the dock offers Picture In Picture. */
    private val pipOffered: Boolean
        get() = (phase == "in_progress" || phase == "reconnecting") &&
            (if (inRoom) cornerFocus != null else isVideoCall) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** The spotlight from the engine's state: only a group video call has main tiles, and a call over has no pins. */
    private fun applySpotlight(state: JSONObject) {
        val list = state.optJSONArray("spotlight")
        val next = if (isGroupCall && isVideoCall && list != null) {
            (0 until list.length()).mapNotNull { list.optString(it).takeIf { identity -> identity.isNotEmpty() } }
        } else {
            emptyList()
        }

        if (phase == "idle") pinnedForMe = emptyList()

        if (next == spotlight && grid.pinned == pinnedForMe) return

        if (next != spotlight) CallsLog.write("spotlight: ${if (next.isEmpty()) "nobody" else next.joinToString(", ")}")

        spotlight = next
        drawSpotlight()
    }

    /**
     * Where Host Controls opens: its button, while the dock shows it. Null when it is hidden, or when the app's own
     * arrangement of the dock left it out (CallsPieces).
     */
    val hostControlsAnchor: View?
        get() = hostControlsButton?.takeIf { it.visibility == View.VISIBLE && it.isShown }

    /** Where a person's menu opens: the ⋯ on their tile, or the tile; null when they have no tile. */
    fun personAnchor(identity: String): View? = grid.manageAnchor(identity)

    /**
     * A moderator's menu at its button, or closed if that one is open: as on the web, a second press on the button
     * that opened it closes it. Opening one closes any other, and a panel. `anchor` is asked again each frame, so the
     * menu follows its button, or its person's tile, wherever they go; `content` on every [CallsPopover.refresh].
     */
    fun togglePopover(
        key: String,
        anchor: () -> View?,
        side: CallsPopover.Side,
        align: CallsPopover.Align,
        width: Int,
        room: Boolean,
        content: () -> Pair<CallsPopover.Head, List<CallsPopover.Entry>>?
    ) {
        if (popoverKey == key) {
            closePopover()
            return
        }

        closePopover()
        hidePanel()

        val popover = CallsPopover(context, anchor, side, align, width, room, content)

        popover.onClose = {
            if (this.popover === popover) {
                this.popover = null
                popoverKey = null
                popoverClosedAt = SystemClock.uptimeMillis()
                drawModerating()
            }
        }

        // Nothing to show, a person who left as the press landed: nothing opens.
        if (!popover.place(this, topInset(), bottomInset)) return

        this.popover = popover
        popoverKey = key
        drawModerating()
    }

    fun closePopover() {
        popover?.close()
    }

    /** The tile whose menu is open is outlined, and Host Controls latched while its own is. */
    private fun drawModerating() {
        grid.moderating = if (popoverKey == "room") null else popoverKey
        drawHostControls()
        drawJoinNotice()
    }

    /**
     * Host Controls for a moderator in a room once the call is up: latched while its menu is open, amber while a
     * room rule holds (everyone muted, the room locked, broadcast mode), as the web's dock draws it. It still opens
     * through [CallsModeration.show], as the participants' list did.
     */
    private fun drawHostControls() {
        val button = hostControlsButton ?: return
        val engine = CallsEngine.get(context)
        // Once the room is up, as the rest of the dock past Show Chat. For anyone who may record the call too, whose
        // Start Recording is there (the web's roomModerated).
        val shown = inRoom && isGroupCall && phase == "in_progress" && (engine.canModerate() || recording?.can == true)

        button.visibility = if (shown) View.VISIBLE else View.GONE

        when {
            popoverKey == "room" -> {
                button.tone = CallsButtonTone.LATCH
                button.isOn = true
            }
            engine.roomMuted || engine.roomLocked || callMode == "broadcast" -> {
                button.tone = CallsButtonTone.WARNING
                button.isOn = true
            }
            else -> {
                button.tone = CallsButtonTone.CONTROL
                button.isOn = false
            }
        }

        if (!shown && popoverKey == "room") closePopover()
    }

    /**
     * What the system bars take at the top of the box, which the status line and a menu keep clear of. Nothing in a
     * band: the page owns everything above the box.
     */
    private fun topInset(): Int {
        if (compact) return 0

        val insets = ViewCompat.getRootWindowInsets(this) ?: return 0

        return insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
    }

    // The call's status line

    private fun buildStatusLine() {
        statusLine = TextView(context).apply {
            textSize = 14f
            typeface = CallsTheme.MEDIUM
            setTextColor(Color.argb(204, 255, 255, 255))
            gravity = Gravity.CENTER
            maxWidth = dp(420)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            isClickable = false
            // Said by TalkBack as it changes, as the web's line is read out.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

            background = GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.argb(140, 0, 0, 0))
                setStroke(dp(1), Color.argb(20, 255, 255, 255))
            }
        }

        addView(
            statusLine,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                topMargin = dp(14)
            }
        )
    }

    /** `text` at the top of the call for `millis`, 14 below the status bar; the latest one wins and starts again. */
    fun showStatusLine(text: String, millis: Long) {
        (statusLine.layoutParams as? FrameLayout.LayoutParams)?.let {
            val top = topInset() + dp(14)

            if (it.topMargin != top) {
                it.topMargin = top
                statusLine.layoutParams = it
            }
        }

        statusLine.visibility = View.VISIBLE
        statusLine.text = text

        removeCallbacks(statusLineAway)
        postDelayed(statusLineAway, millis)
    }

    /**
     * `.bm-placeholder-participant-lost-message`: the same glass chip as the
     * peer notice with a 24dp `wifiOff` beside the words, at the top of the
     * stage and centred, which is where the stylesheet puts it.
     */
    private fun buildLostNotice() {
        val text = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            this.text = CallsTheme.string(context, "reconnecting", "The other side lost connection")
        }

        lostNoticeText = text

        val glyph = CallsIconView(context).apply { set(CallsIcons.wifiOff) }

        lostNotice = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))

            background = GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.argb(140, 0, 0, 0))
                setStroke(dp(1), Color.argb(20, 255, 255, 255))
            }

            addView(glyph, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(
                text,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = dp(10) }
            )
        }

        addView(
            lostNotice,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(14)
            }
        )
    }

    private fun buildPeerNotice() {
        // `.bm-placeholder-no-source-message` / `.bm-placeholder-participant-muted-message`:
        // black at 0.55 behind a hairline of white at 0.08, the card radius,
        // 10 by 14 of padding, and a 20dp glyph ten from the words. It used to
        // be a bare label with no icon at all.
        peerNoticeText = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        val glyph = CallsIconView(context).apply { set(CallsIcons.micOff) }

        peerNoticeIcon = glyph

        peerNotice = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))

            background = GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.argb(140, 0, 0, 0))
                setStroke(dp(1), Color.argb(20, 255, 255, 255))
            }

            addView(glyph, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(
                peerNoticeText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = dp(10) }
            )
        }

        addView(
            peerNotice,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /// Over a picture it is a chip in the top corner; with no picture the
    /// placeholder carries it, under the name and the status. The web moves it
    /// the same way.
    private fun placePeerNotice(overPicture: Boolean) {
        val params = peerNotice.layoutParams as? FrameLayout.LayoutParams ?: return

        // Over a picture this is the grid's chip, to the pixel: a 24dp disc in
        // recording red with the muted glyph in it and no words at all. The
        // sentence belongs to the placeholder, where there is room for it and
        // where it has to explain why there is nothing to look at. Over a
        // picture it explained nothing the icon did not, and in the corner
        // window it covered a good part of the call.
        //
        val disc = dp(24)
        val inset = dp(5)

        if (overPicture) {
            peerNoticeText.visibility = View.GONE
            peerNotice.setPadding(inset, inset, inset, inset)

            peerNotice.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CallsTheme.withAlpha(CallsTheme.recording(context), 235))
            }

            peerNoticeIcon.layoutParams =
                LinearLayout.LayoutParams(disc - inset * 2, disc - inset * 2)

            params.width = disc
            params.height = disc
            params.gravity = Gravity.TOP or Gravity.START
            params.setMargins(dp(14), dp(48), 0, 0)
        } else {
            peerNoticeText.visibility = View.VISIBLE
            peerNotice.setPadding(dp(14), dp(10), dp(14), dp(10))

            peerNotice.background = GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.argb(140, 0, 0, 0))
                setStroke(dp(1), Color.argb(20, 255, 255, 255))
            }

            peerNoticeIcon.layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))

            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            params.setMargins(dp(16), dp(430), dp(16), 0)
        }

        peerNotice.layoutParams = params
    }

    private fun buildIdentity() {
        avatar = TextView(context).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CallsTheme.accent(context))
            }
        }

        addView(
            avatar,
            FrameLayout.LayoutParams(dp(108), dp(108), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(120)
            }
        )

        nameLabel = TextView(context).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        addView(
            nameLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(250) }
        )

        statusLabel = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
        }

        addView(
            statusLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(290) }
        )
    }

    /**
     * Who the call is with, shown only when there is no picture of them.
     *
     * In the corner window none of it fits, and over a picture the web and iOS
     * both draw nothing at all.
     *
     * "A picture" means a frame that has been drawn, not a track that might
     * one day carry one. A track exists from the moment the call is negotiated
     * and the first frame is a second or more behind it — longer after an ICE
     * restart, or when the other side turns its camera back on — and putting
     * the avatar away the instant the track arrived left a black rectangle
     * standing in for the other person until the picture caught up.
     */
    private fun drawIdentity() {
        val corner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && host.inCornerWindow
        // A camera changing codec is a picture on its way back, under its
        // placeholder: not the camera turned off.
        val shown = if (corner || stageReveal.painted || stageOptimizing.isUp) View.GONE else View.VISIBLE

        // A room carries neither the name nor the clock.
        //
        // The web's group call is the grid and its controls and nothing else,
        // and this drew the conversation name and a running timer in a strip
        // across the top of it — the one place the three platforms disagreed
        // about what a group call looks like. The name goes outright; the
        // status stays only while the room is still being joined, because
        // "Connecting…" is the whole of what the screen has to say then, and
        // goes with the rest once the call is up.
        //
        // The avatar goes with them. The grid draws everybody, this side
        // included, so the stage's own circle belongs to nobody — left on, it
        // is the conversation's picture and initials over the top of the first
        // tile.
        if (inRoom) {
            avatar.visibility = View.GONE
            nameLabel.visibility = View.GONE
            // Only for something to say: waiting for a place, or the host's
            // own line.
            statusLabel.visibility = if (phase == "in_progress" || statusText().isEmpty()) View.GONE else shown

            applyGridMetrics()

            return
        }

        avatar.visibility = shown
        nameLabel.visibility = shown
        statusLabel.visibility = shown

        applyGridMetrics()
    }

    /**
     * The width the control metrics step on, in dp.
     *
     * This view's own, not the display's: in a band it is as wide as the box
     * the page made room for, and sizing the row to the whole phone would push
     * it past the edges of the box it is in. Before it has been measured there
     * is nothing to go on but the display.
     */
    private fun widthDp(): Int {
        val px = if (width > 0) width else resources.displayMetrics.widthPixels

        return (px / resources.displayMetrics.density).toInt()
    }

    private fun buildControls() {
        val engine = CallsEngine.get(context)

        muteButton = CallsRoundButton(context, "mic", CallsButtonTone.MEDIUM) {
            val next = !(muteButton?.isOn ?: false)
            muteButton?.isOn = next
            engine.setMuted(next)

            // Unmuting a microphone that is not allowed is the person asking
            // for it, and the prompt the call put up on its own is put up once
            // per call: dismissed once, nothing ever asked again. Asked here,
            // the way the camera button asks for the camera, and the answer
            // opens it through onRequestPermissionsResult.
            if (!next && !CallsPermissions.microphoneGranted(context)) {
                CallsPermissions.request(host.activity, false, force = true)
            }
        }

        cameraButton = CallsRoundButton(context, "camera", CallsButtonTone.MEDIUM) {
            val next = !(cameraButton?.isOn ?: false)
            cameraButton?.isOn = next

            // An audio call never asked for the camera, because it had no use
            // for one. Switching it on is the first moment there is anything
            // to ask about; the answer comes back through
            // onRequestPermissionsResult and starts it.
            if (!next && !CallsPermissions.cameraGranted(context)) {
                engine.setCameraEnabled(true)
                CallsPermissions.request(host.activity, true, force = true)
            } else {
                engine.setCameraEnabled(!next)
            }
        }

        flipButton = CallsRoundButton(context, "flip") { engine.flipCamera() }

        speakerButton = CallsRoundButton(context, "speaker", CallsButtonTone.LATCH) {
            val next = !(speakerButton?.isOn ?: false)
            speakerButton?.isOn = next
            engine.setSpeaker(next)
        }

        shareButton = CallsRoundButton(context, "share", CallsButtonTone.LATCH) {
            if (engine.isSharingScreen) {
                engine.stopScreenShare()
                shareButton?.isOn = false
            } else {
                // Android will not hand the screen over without the person
                // saying so in the system's own dialog.
                host.startScreenCapture()
            }
        }.apply { visibility = View.GONE }

        settingsButton = CallsRoundButton(context, "settings") {
            CallsSettings.show(this@CallsStage)
        }.apply { visibility = View.GONE }

        addPersonButton = CallsRoundButton(context, CallsIcons.userPlus, CallsButtonTone.LATCH) {
            showAddPerson()
        }.apply { visibility = View.GONE }

        // Only where the device can actually do it, and only in a video call:
        // a button that opens a black square is worse than no button.
        pipButton = CallsRoundButton(context, "pip", CallsButtonTone.LATCH) {
            host.enterCornerWindow()
        }.apply { visibility = View.GONE }

        // Host Controls, for a moderator in a room: the room's rules in a menu
        // above the dock, as the web's dock has them. There is nothing to
        // moderate in a call between two people. A person's own actions are
        // on the ⋯ of their tile.
        hostControlsButton = CallsRoundButton(context, "host") {
            CallsModeration.show(this@CallsStage, roster)
        }.apply {
            visibility = View.GONE

            // Nobody can gather thirty-two people to see whether the grid
            // holds, so a long press fills the room with people who are not
            // there. Debuggable builds only — a release build never enables it.
            setOnLongClickListener {
                if (CallsEngine.get(context).cycleDebugRoster()) true else false
            }
        }

        // A viewer in a broadcast room cannot publish anything; putting a hand
        // up is the whole of what they can do, so it gets a control of its own
        // in the dock, where nobody has to look for it.
        handButton = CallsRoundButton(context, "raisehand", CallsButtonTone.LATCH) {
            engine.groupAdmin(if (handRaised) "lower_own_hand" else "raise_hand")
        }.apply { visibility = View.GONE }

        // Answering is part of the same dock, as it is on the web and on iOS,
        // and it is simply not there once the call has been picked up. It was
        // a second row of its own here with two 78dp targets in it — nothing
        // else on either phone looks like that.
        answerButton = CallsRoundButton(context, "answer", CallsButtonTone.ANSWER) {
            engine.answerFromScreen(engine.currentCallId())
        }.apply { visibility = View.GONE }

        // The chat beside the call, or the call over it: a button of the dock, after Answer, where the host has a
        // chat to show. It is the only way back to the conversation, so it is in every phase the dock is drawn in.
        chatButton = CallsRoundButton(context, "chat", CallsButtonTone.LATCH) { toggleChat() }.apply {
            visibility = if (CallsPlugin.chatToggle) View.VISIBLE else View.GONE
        }

        // Every control says what it is. TalkBack read the whole row as
        // "button" before this, and the device rig had nothing to select by.
        // The words are the web's own, from the same translation store; the
        // two that change meaning follow their state in onScreenState.
        muteButton?.contentDescription = CallsTheme.string(context, "disable_microphone", "Disable Microphone")
        cameraButton?.contentDescription = CallsTheme.string(context, "enable_camera", "Enable Camera")
        flipButton?.contentDescription = CallsTheme.string(context, "switch_camera", "Switch Camera")
        speakerButton?.contentDescription = CallsTheme.string(context, "speaker", "Speaker")
        shareButton?.contentDescription = CallsTheme.string(context, "share_screen", "Share Screen")
        addPersonButton?.contentDescription = CallsTheme.string(context, "add_person", "Add Person")
        settingsButton?.contentDescription = CallsTheme.string(context, "device_settings", "Device Settings")
        hostControlsButton?.contentDescription = CallsTheme.string(context, "host_controls", "Host Controls")
        pipButton?.contentDescription = CallsTheme.string(context, "picture_in_picture", "Picture In Picture")
        answerButton?.contentDescription = CallsTheme.string(context, "answer", "Answer")
        drawChatButton()

        // One button, because that is what the row has room for and what the
        // web puts there: while it is still ringing this declines, afterwards
        // it hangs up.
        val end = CallsRoundButton(context, "hangup", CallsButtonTone.DANGER) {
            when (phase) {
                "ringing_in" -> engine.decline(engine.currentCallId())
                "ringing_out" -> engine.endCall("abort")
                else -> engine.endCall("hangup")
            }
        }

        end.contentDescription = CallsTheme.string(context, "end_call", "End Call")

        controls = CallsDock(context)

        endButton = end

        // The microphone while a moderator keeps it off, in the dock where it
        // was: the silenced glyph on the amber of a restriction someone else
        // holds, rather than gone. A press says why again. The web's
        // lockedMicItem.
        lockedMicButton = CallsRoundButton(context, "silenced", CallsButtonTone.WARNING) {
            showModeratorNotice(true)
        }.apply {
            isOn = true
            visibility = View.GONE
            contentDescription = CallsTheme.string(context, "moderator_muted_you", "A moderator muted your microphone.")
        }

        // iOS's order exactly, which is the stylesheet's.
        val row = listOfNotNull(
            answerButton, chatButton, settingsButton, flipButton, cameraButton, muteButton, lockedMicButton,
            speakerButton, pipButton, shareButton, addPersonButton, handButton, hostControlsButton, end
        )

        for (button in row) controls.addView(button, LinearLayout.LayoutParams(0, 0))

        // By the ids a host arranges them by (CallsPieces), in this order.
        ourControls = listOf(
            "answer" to answerButton, "chat" to chatButton, "settings" to settingsButton, "flip" to flipButton, "camera" to cameraButton,
            "mute" to muteButton, "speaker" to speakerButton, "pip" to pipButton, "share" to shareButton,
            "add" to addPersonButton, "hand" to handButton, "host" to hostControlsButton, "end" to end,
        ).mapNotNull { (id, view) -> view?.let { CallsControl(id, it) } }

        addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(DOCK_FLOOR) }
        )

        // Placed by hand beside the dock, wherever the dock's own layout puts it.
        connectionPill = CallsConnectionPill(context).apply { visibility = View.GONE }
        addView(connectionPill, FrameLayout.LayoutParams(dp(CallsConnectionPill.SIZE_DP), dp(CallsConnectionPill.SIZE_DP)))

        // REC after the connection, level with the dock (the web's RecordingPill), in the connection's place, or at the
        // top of the call where the dock leaves it no room even at its smallest: it is how everyone in the call knows,
        // so it is never left out (placeRecordingPill).
        recordingPill = CallsRecordingPill(context).apply { onStop = { askRecording("stop") } }
        addView(recordingPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(CallsRecordingPill.HEIGHT_DP)))
        recordingPill.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            // As wide as it is now, the dock fits itself beside it again, starting from its first place.
            if (right - left != oldRight - oldLeft) post {
                recordingPlace = RECORDING_AFTER_CONNECTION
                fitControls()
                placeConnectionPill()
                placeRecordingPill()
            }
        }

        controls.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            placeConnectionPill()

            // A group call's grid stands on the dock, so a dock of another height moves its floor. After this layout
            // pass, not inside it.
            if (bottom - top != oldBottom - oldTop) post { applyGridMetrics() }
        }

        fitControls()

        // A tap on the picture hides the controls on the whole screen, and
        // takes the call back out of a band. It has to be answered here either
        // way: this view fills the band, so a listener on the band behind it
        // never sees the tap at all.
        setOnClickListener {
            CallsLog.write("tap: compact=$compact room=$inRoom phase=$phase controls=$controlsVisible")

            // A press outside a menu closes the menu and nothing more: the dock
            // stays where it is. The menu closes as the press goes down, so the
            // tap that follows it up is the same press, under 400 ms later.
            if (popover != null || (popoverClosedAt != 0L && SystemClock.uptimeMillis() - popoverClosedAt < 400L)) {
                return@setOnClickListener
            }

            if (compact) host.expandCall() else toggleControls()
        }
    }

    private fun toggleControls() {
        if (phase != "in_progress") return

        // A group call's bar stays, as the web's does: its tiles stop above it,
        // so it covers nothing to get out of the way of.
        if (groupBar) return

        // A two-person room has no stage track — the grid draws everybody and
        // the stage is hidden behind it — and its picture is as much a picture
        // as one on the stage.
        if (!inRoom && stageTrack() == null) return

        controlsVisible = !controlsVisible

        val alpha = if (controlsVisible) 1f else 0f

        // Before the fade, not after it. A control at alpha 0 is invisible and
        // still takes every touch it would have taken, so a tap on a dock that
        // had been put away ran whatever used to be under the finger — the red
        // button included — instead of bringing the dock back. The first tap
        // on a hidden dock now reaches the stage, which is what shows it
        // again; the second tap is the one that presses anything.
        setControlsTappable(controlsVisible)

        controls.animate().alpha(alpha).setDuration(200).start()
        connectionPill.animate().alpha(alpha).setDuration(200).start()
        nameLabel.animate().alpha(alpha).setDuration(200).start()
        statusLabel.animate().alpha(alpha).setDuration(200).start()
    }

    /**
     * Show Chat / Hide Chat, as the edge chevron did it: in a band, back to the whole box (the page's to decide);
     * otherwise the page is asked to show the conversation, and a window gets out of its way. With nothing listening,
     * a cold start into a call, the call is minimised and the ongoing-call notification is the way back.
     */
    private fun toggleChat() {
        if (compact) {
            host.expandCall()
            return
        }

        val asked = CallsPlugin.askChatToggle()

        if (!asked) CallsEngine.get(context).setMinimised(true)

        // Only a window has to get out of the way, so the page under it can be seen and report its box. In the page's
        // own box there is nothing to close: the page answers by resizing the box this is drawn in.
        if (!asked || host.coversPage) host.closeCallScreen()
    }

    /**
     * Whether the host shows the conversation beside the call: the chat button is on while it does, and says Hide
     * Chat. A host with no chat never calls this.
     */
    fun setChatShown(shown: Boolean) {
        chatShown = shown
        drawChatButton()
    }

    private fun drawChatButton() {
        val button = chatButton ?: return

        button.isOn = chatShown
        button.contentDescription = if (chatShown) {
            CallsTheme.string(context, "hide_chat", "Hide Chat")
        } else {
            CallsTheme.string(context, "show_chat", "Show Chat")
        }
    }

    /**
     * Whether the dock answers a finger.
     *
     * Each control rather than the row: a `LinearLayout` with no click
     * listener of its own does not consume a touch, so with its children deaf
     * the tap carries on to the stage underneath — which is exactly where it
     * has to land for the dock to come back.
     */
    private fun setControlsTappable(on: Boolean) {
        for (index in 0 until controls.childCount) {
            controls.getChildAt(index)?.let {
                it.isClickable = on
                it.isEnabled = on
            }
        }
    }

    /** Every control back, whatever the last call left them at. */
    private fun restoreControls() {
        if (controlsVisible) return

        controlsVisible = true

        for (view in listOf<View>(controls, connectionPill, nameLabel, statusLabel)) {
            view.animate().cancel()
            view.alpha = 1f
        }

        // Back in every sense: a dock that came back deaf is worse than one
        // that stayed away.
        setControlsTappable(true)
    }

    // What it shows

    override fun onScreenState(state: JSONObject) {
        post {
            applyState(state)

            // Answering is what moves the phase off `ringing_in`, and that is
            // the first moment it is right to ask.
            host.requestCallPermissions(state)
        }
    }

    /**
     * One of the other side's two pictures. Which of them is on the stage is
     * decided in [drawPeerVideo], not here, so a screen share arriving does not
     * silently take the camera's place.
     */
    override fun onScreenRemoteVideo(source: CallsSource, track: VideoTrack?) {
        post {
            if (source == CallsSource.SCREEN_SHARE) {
                remoteScreen = track

                // A share that has stopped hands the stage back. One that
                // starts does the same, but that is the announcement's to say,
                // not the track's - the track has been there since the call
                // was negotiated.
                if (track == null) swapped = false
            } else {
                remoteVideo = track
            }

            drawPeerVideo()
            viewChanged()
        }
    }

    override fun onScreenPeerOptimizing(optimizing: Boolean, avatar: String?) {
        post {
            loadPeerFace(avatar ?: "")

            if (peerOptimizing == optimizing) return@post

            peerOptimizing = optimizing

            // Which picture is on the stage depends on it ([stageTrack]).
            drawPeerVideo()
            viewChanged()
        }
    }

    /** The other side's picture for the placeholder's backdrop, loaded once per address. */
    private fun loadPeerFace(url: String) {
        if (url == peerFaceUrl) return

        peerFaceUrl = url
        peerFace = null

        if (url.isEmpty()) return

        CallsAvatars.load(context, url) { bitmap ->
            if (peerFaceUrl != url || bitmap == null) return@load

            peerFace = bitmap
            if (stageOptimizing.isUp) applyStageOptimizing()
        }
    }

    /**
     * The placeholder over the stage while the other side's camera changes
     * codec, wherever their camera is the one on the stage: never over a
     * shared screen there, nor in a room's grid.
     */
    private fun applyStageOptimizing() {
        val onStage = stageTrack()
        val showsScreen = onStage != null && onStage === shownRemoteScreen()

        stageOptimizing.show(!inRoom && peerOptimizing && !showsScreen, peerFace)
    }

    override fun onScreenPeerStalled(stalled: CallsStalled?) {
        post {
            if (peerStalled == stalled) return@post

            peerStalled = stalled

            applyStageStalled()
        }
    }

    /**
     * Their camera's last frame blurred wherever it is, on the stage or in the
     * corner, and their screen's too when nothing of theirs arrives, which the
     * call says as "The other side lost connection" (the engine's `lost`).
     * "Video interrupted", with why, while their camera alone stopped and the
     * stage shows it, as the web's stage does: never over a shared screen, a
     * still one sending nothing, nor under the placeholder of a camera
     * changing codec.
     */
    private fun applyStageStalled() {
        val stall = if (inRoom) null else peerStalled
        val onStage = stageTrack()
        val inCorner = if (cornerReveal.painted) cornerTrack() else null
        val camera = shownRemoteVideo()
        val cameraOnStage = onStage != null && onStage === camera

        stageStalled.show(
            onStage != null && (stall == CallsStalled.ALL || (stall == CallsStalled.VIDEO && cameraOnStage)),
            stall == CallsStalled.VIDEO && cameraOnStage && !stageOptimizing.isUp,
            stage
        )
        cornerStalled.show(
            inCorner != null && (stall == CallsStalled.ALL || (stall == CallsStalled.VIDEO && inCorner === camera)),
            false,
            peerChip
        )
    }

    /**
     * Their camera away for a moment while it changes codec: it keeps its
     * place, under the placeholder, rather than being taken for off.
     */
    private fun peerCameraHeld(): Boolean = peerOptimizing && shownRemoteVideo() == null

    /**
     * A share goes on the stage the moment it starts, because that is what the
     * other person meant by sharing it; the swap chip undoes that.
     */
    /** The other side's camera, if the other side says it is sending one. */
    private fun shownRemoteVideo(): VideoTrack? =
        if (peerStateKnown && peerCameraOn) remoteVideo else null

    private fun shownRemoteScreen(): VideoTrack? =
        if (peerStateKnown && peerScreenOn) remoteScreen else null

    private fun stageIsScreen(): Boolean = shownRemoteScreen() != null && !swapped

    private fun stageTrack(): VideoTrack? = when {
        stageIsScreen() -> shownRemoteScreen()
        // Their camera's place, swapped onto the stage or with no screen to
        // share it: while it changes codec the stage keeps it, under the
        // placeholder, and the screen stays in the corner, as the web's stage
        // does for its `primaryVideo === 'camera'`.
        peerCameraHeld() -> null
        else -> shownRemoteVideo() ?: shownRemoteScreen()
    }

    private fun cornerTrack(): VideoTrack? {
        val screen = shownRemoteScreen() ?: return null

        // The camera held on the stage leaves the corner to the screen; held
        // in the corner, the corner waits for it.
        if (peerCameraHeld()) return if (stageIsScreen()) null else screen

        if (shownRemoteVideo() == null) return null

        return if (stageIsScreen()) shownRemoteVideo() else screen
    }

    private fun viewChanged() {
        CallsEngine.get(context).scheduleView()
    }

    /**
     * How big each of the other side's pictures is actually drawn, in device
     * pixels, so it is sent at the size it is shown rather than at 1080 into a
     * thumbnail.
     *
     * Zero means "not shown", which switches that source off at the far end: a
     * source with no box, a screen the app is not on, or a picture that is
     * simply not there. Picture in Picture counts as shown, because it is.
     */
    override fun viewHint(): JSONObject {
        val hint = JSONObject()

        if (remoteVideo != null) {
            // Every input that can turn this into a zero, which is an order to
            // the other side to stop sending.
            CallsLog.write(
                "view hint: box=${boxHeight(remoteVideo)} stopped=$stopped inRoom=$inRoom" +
                    " known=$peerStateKnown cameraOn=$peerCameraOn" +
                    " onStage=${remoteVideo === stageTrack()} inCorner=${remoteVideo === cornerTrack()}" +
                    " stage=${stage.visibility == View.VISIBLE}/${stage.width}x${stage.height}"
            )

            hint.put("camera", CallsShapes.viewStep(boxHeight(remoteVideo)))
        }

        if (remoteScreen != null) {
            // A screen is asked for at the exact pixel it is drawn at, not at
            // a rung: text is either sharp or it is not.
            hint.put("screen_share", CallsShapes.exactStep(boxHeight(remoteScreen)))
        }

        return hint
    }

    private fun boxHeight(track: VideoTrack?): Int {
        if (track == null || inRoom) return 0

        // Stopped is not shown — except in the corner window, which is the
        // whole point of it.
        if (stopped && !host.inCornerWindow) return 0

        val box = when {
            track === stageTrack() -> stage
            track === cornerTrack() -> peerChip
            else -> return 0
        }

        if (box.visibility != View.VISIBLE || box.width <= 0 || box.height <= 0) return 0

        // Contained in its box: the shorter of the box's height and the height
        // the box's width allows at the picture's proportions. The web assumes
        // 9:16 until it knows better and so does this.
        val shown = minOf(box.height.toFloat(), box.width * DEFAULT_ASPECT)

        return ceil(shown.toDouble()).toInt()
    }

    private fun drawPeerVideo() {
        // In a room the grid owns the screen and there is no one "remote" to
        // put on a stage, so a stray track must not raise it.
        if (inRoom) {
            stageReveal.show(null)
            cornerReveal.show(null)

            stage.visibility = View.GONE
            peerChip.visibility = View.GONE
            stageOptimizing.show(false, null)
            stageStalled.show(false, false, null)
            cornerStalled.show(false, false, null)
            return
        }

        val onStage = stageTrack()
        val inCorner = cornerTrack()

        stageReveal.show(onStage)
        cornerReveal.show(inCorner)

        applyStageOptimizing()
        applyStageStalled()

        // Fitted, never filled. The web's stage is `object-fit: contain` for a
        // camera as much as for a screen - cropping the sides off someone to
        // fill a phone-shaped hole is not showing them, and a screen cropped is
        // a document read from the middle. Only the corner thumbnails fill,
        // which is what the web does too.
        stage.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        // The stage goes up with the track rather than with the picture, so
        // that its surface is made and ready for the first frame instead of
        // being made *by* it. Nothing black shows through in the meantime: the
        // avatar and the name are drawn over it until there is a picture.
        //
        // A corner tile has nothing behind it to show instead, so it waits —
        // which is the web's own `.bm-call-small-video`, held at `opacity: 0`
        // until its video reports that it is playing.
        stage.visibility = if (onStage != null) View.VISIBLE else View.GONE
        peerChip.visibility = if (cornerReveal.painted) View.VISIBLE else View.GONE
        peerSwap.visibility = if (cornerReveal.painted) View.VISIBLE else View.GONE

        // The avatar, the name and the timer are what a call shows when it has
        // no picture to show instead — over a live camera the web draws none
        // of them, and iOS draws none of them. Only the avatar was following
        // that here, so a call with video carried the other person's name and
        // a running clock across the top of their face.
        drawIdentity()

        drawSelfStage()
    }

    /**
     * A corner tile stands up or lies down with its picture, and nothing else
     * about the picture moves it.
     *
     * A fixed box would crop a landscape camera into a portrait slot and show
     * the middle of somebody's face, which is why the tile followed the frames
     * at all. But following them *exactly* was worse: a camera reports several
     * different frame sizes on the way to the one it was asked for, and steps
     * down again whenever the encoder decides the connection cannot carry it,
     * so the tile's bottom edge moved through a call and the fill cropped
     * something different each time.
     *
     * Orientation is the part that is stable, and it is the part that matters.
     */
    private fun shapeWatcher(target: () -> SurfaceViewRenderer): RendererCommon.RendererEvents {
        // Off the delivery thread, and guarded — see `buildStage`.
        val ui = handler

        return object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                try {
                    val sideways = rotation == 90 || rotation == 270
                    val wide = if (sideways) height else width
                    val tall = if (sideways) width else height

                    if (wide <= 0 || tall <= 0) return

                    ui.post { standTile(target(), tall >= wide) }
                } catch (e: Throwable) {
                    CallsLog.write("corner resolution callback: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Turn a tile to match its picture, if it is not already that way up. */
    private fun standTile(view: SurfaceViewRenderer, tall: Boolean) {
        if (view === chip) {
            if (chipTall == tall) return
            chipTall = tall
        } else {
            if (peerChipTall == tall) return
            peerChipTall = tall
        }

        sizeTile(view)
    }

    /** How tall a tile [width] wide is, standing up or lying down. */
    private fun tileHeight(width: Int, tall: Boolean): Int =
        if (tall) width * 16 / 9 else width * 9 / 16

    /** Give a tile the height its width and its orientation ask for. */
    private fun sizeTile(view: SurfaceViewRenderer) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val wanted = tileHeight(params.width, if (view === chip) chipTall else peerChipTall)

        if (params.height != wanted) {
            params.height = wanted
            view.layoutParams = params
        }

        if (view === chip) stackTiles()
    }

    /**
     * The peer's tile sits under one's own, and the swap glyph on the corner of
     * it. Both were placed for a tile of one fixed height, so every tile that
     * was not that height left them overlapping or floating.
     */
    private fun stackTiles() {
        val own = chip.layoutParams as? FrameLayout.LayoutParams ?: return
        val top = own.topMargin + own.height + dp(12)

        (peerChip.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.topMargin == top && it.marginEnd == own.marginEnd) return@let

            it.topMargin = top
            it.marginEnd = own.marginEnd
            peerChip.layoutParams = it
        }

        (peerSwap.layoutParams as? FrameLayout.LayoutParams)?.let {
            val end = own.marginEnd + own.width - dp(20)

            if (it.topMargin == top + dp(6) && it.marginEnd == end) return@let

            it.topMargin = top + dp(6)
            it.marginEnd = end
            peerSwap.layoutParams = it
        }
    }

    /**
     * A renderer showing one track, held back until it has a picture of it.
     *
     * `SurfaceViewRenderer` is a black rectangle until a frame has been decoded
     * into it, and the track it will arrive on exists long before that — from
     * the moment the call is negotiated, because the warm transceivers make one
     * for every source. Putting the renderer up with the track is therefore
     * putting up a black hole and waiting, which is what the other side looked
     * like for the first seconds of every call.
     *
     * The web has the same wait and says so: `.bm-call-small-video` is
     * `opacity: 0` until its `<video>` fires `playing`. This is that, told by
     * the frames themselves — a sink of our own alongside the renderer, so the
     * answer is about this track on this renderer rather than about whichever
     * one happened to be first since the surface was made.
     */
    private inner class Reveal(
        private val name: String,
        private val view: () -> SurfaceViewRenderer,
        private val onPainted: () -> Unit
    ) : VideoSink {
        private var showing: VideoTrack? = null

        /** When the wait started, so the log says how long it was. */
        private var since = 0L

        /**
         * The track still owed a first frame, read from the thread delivering
         * them — so it is never cached in one, and it is carried through the
         * hop back rather than re-read there. A frame that arrives as the
         * tracks are being swapped would otherwise answer for the one that
         * replaced it, and reveal a renderer that is still empty.
         */
        @Volatile
        private var awaited: VideoTrack? = null

        /** Whether there is a picture rather than the promise of one. */
        var painted = false
            private set

        fun show(track: VideoTrack?) {
            if (showing === track) return

            // A room's track can be disposed by the SDK before it is let go of
            // here — the other side switching its camera off unsubscribes it —
            // and a sink taken off a disposed track throws, on the main thread.
            detach(showing)

            showing = track
            painted = false
            awaited = track
            since = System.currentTimeMillis()

            try {
                track?.addSink(view())
                track?.addSink(this)
            } catch (e: IllegalStateException) {
                CallsLog.write("stage $name: the track was disposed before it could be shown")

                showing = null
                awaited = null
            }
        }

        private fun detach(track: VideoTrack?) {
            try {
                track?.removeSink(view())
                track?.removeSink(this)
            } catch (e: IllegalStateException) {
                // Disposed already, and with it every sink it had.
            }
        }

        override fun onFrame(frame: VideoFrame?) {
            // Once, and never anything that can throw: this runs on the thread
            // delivering the frames, and an exception on it comes back through
            // the JNI bridge as an abort — see `buildStage`.
            val track = awaited ?: return

            awaited = null

            try {
                handler.post {
                    if (painted || showing !== track) return@post

                    painted = true

                    CallsLog.write("$name painted after ${System.currentTimeMillis() - since}ms")

                    onPainted()
                }
            } catch (e: Throwable) {
                CallsLog.write("first frame: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * The web lets either small tile be dragged anywhere in the call - a
     * `.bm-draggable-handle` over the whole of it, `cursor: move`. This is the
     * same thing said in the phone's language.
     *
     * The tile is kept on the screen, because one dragged off it is one nobody
     * can get back. The peer's tile also answers a tap by swapping what is on
     * the stage, so a touch only becomes a drag once it has moved further than
     * the system's own slop - below that the click listener still gets it.
     */
    private fun draggable(target: View, extra: View? = null) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        // A view that does not take ACTION_DOWN is not sent the rest of the
        // gesture, and a touch listener that declines the down only takes it
        // when the view itself is clickable. A `SurfaceViewRenderer` is not,
        // and neither tile was made so, which means ACTION_MOVE never arrived
        // and no drag could ever start — least of all on one's own picture,
        // which has no other button over it to carry the touch.
        //
        // Clickable is also what keeps the tap alive: the listener declines
        // the down, the view takes it, and a drag that consumes the up is what
        // stops that up turning into a click.
        target.isClickable = true

        val listener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.rawX
                    dragDownY = event.rawY
                    dragFromX = target.translationX
                    dragFromY = target.translationY
                    dragging = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragDownX
                    val dy = event.rawY - dragDownY

                    if (!dragging && kotlin.math.hypot(dx, dy) < slop) return@OnTouchListener false

                    dragging = true

                    val room = dp(8)
                    val maxX = (width - target.width - room - target.left).toFloat()
                    val minX = (room - target.left).toFloat()
                    val maxY = (height - target.height - room - target.top).toFloat()
                    val minY = (room - target.top).toFloat()

                    val x = (dragFromX + dx).coerceIn(minOf(minX, maxX), maxOf(minX, maxX))
                    val y = (dragFromY + dy).coerceIn(minOf(minY, maxY), maxOf(minY, maxY))

                    target.translationX = x
                    target.translationY = y
                    extra?.translationX = x
                    extra?.translationY = y

                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    wasDragging
                }

                else -> false
            }
        }

        target.setOnTouchListener(listener)
    }

    /// While a call is still ringing and there is no picture from the other
    /// side, one's own camera fills the screen - which is what the web does
    /// and what every phone does. Once the call connects the other person
    /// takes the stage and this drops back to the corner chip.
    private fun drawSelfStage() {
        val ringing = phase == "ringing_out" || phase == "ringing_in"
        val corner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && host.inCornerWindow
        val wanted =
            if (ringing && !inRoom && !corner && stageTrack() == null) localVideo else null

        selfReveal.show(wanted)

        selfStage.visibility = if (selfReveal.painted) View.VISIBLE else View.GONE
        selfScrim.visibility = if (selfReveal.painted) View.VISIBLE else View.GONE

        // The same face twice, once behind and once in the corner, is not
        // parity with anything - and this has to be total, or the chip stays
        // hidden for the rest of the call once the other side arrives.
        //
        // `corner` is the other thing that has to be total. Entering the corner
        // window puts the chip away, but every redraw after that put it back:
        // it is laid out for a whole screen, so in a window a tenth the size it
        // covered the picture and hung over the edge. The other side turning
        // its camera off is exactly such a redraw, which is where it showed.
        //
        // And a camera that has been opened is not yet a camera that has sent
        // a frame. The tile used to go up with the track and sit there black
        // until the first one arrived — every time the camera was turned on,
        // flipped, or reopened after a permission prompt.
        chip.visibility =
            if (wanted == null && chipReveal.painted && !inRoom && !corner) View.VISIBLE else View.GONE
    }

    override fun onScreenLocalVideo(track: VideoTrack?) {
        post {
            localVideo = track

            // Your own tile is already in the grid; the chip would be a second
            // copy of your face.
            chipReveal.show(if (inRoom) null else track)

            // `drawSelfStage()` owns whether the chip is up: while a call is
            // still ringing the same camera fills the screen instead.
            drawSelfStage()
        }
    }

    override fun onScreenRoster(participants: List<CallsRoomParticipant>) {
        post {
            inRoom = participants.isNotEmpty()
            roster = participants

            // The room is gone, and its menus with it.
            if (!inRoom) closePopover()

            // A viewer in broadcast mode has nothing to publish, so the
            // controls that would publish it are not drawn — the web hides
            // the same three.
            canPublish = participants.firstOrNull { it.isLocal }?.canPublish ?: true

            val locked = participants.firstOrNull { it.isLocal }?.micMuted ?: false

            // A moderator muting this side, or letting it unmute, is said for
            // a few seconds: the locked button alone said nothing of who did
            // it. Not the first word of it, a newcomer to a muted room.
            if (micLockKnown && locked != micLocked) showModeratorNotice(locked)

            micLocked = locked
            // Not from the tile a host draws for this side while the room is
            // joined, which knows no rule yet.
            micLockKnown = inRoom && phase != "connecting"
            handRaised = participants.firstOrNull { it.isLocal }?.handRaised ?: false

            applyPublishControls()
            applyRoomControls()

            // An audio room is a list of people on the web, not a wall of empty
            // rectangles. Only a group audio call: the two-person room a failed
            // direct call is moved into never reaches the grid at all.
            grid.audioRoom = isGroupCall && !isVideoCall && !cornerWindow
            grid.broadcastRoom = isGroupCall && callMode == "broadcast"

            // Pin for Me on a group video call's tiles. Someone who left is unpinned, so coming back they are a tile like
            // the others.
            grid.pinsOffered = isGroupCall && isVideoCall
            pinnedForMe = CallsSpotlight.kept(pinnedForMe, participants)
            grid.pinned = pinnedForMe

            chooseCornerFocus()

            grid.show(participants)

            // The stage is a two-person idea. Once there is a roster the grid
            // replaces all of it, avatar included.
            if (inRoom) {
                stage.visibility = View.GONE
                chip.visibility = View.GONE
                avatar.visibility = View.GONE
            }

            // The status moves out of the middle and into the strip above the
            // grid, and back again if this turns out to be a two-person room
            // after all.
            applyIdentityMetrics()

            // Here rather than only on the next state, because the roster is
            // what decides this is a room: without it the name and the clock
            // stay on screen until something else happens to redraw them.
            drawIdentity()

            statusLabel.text = statusText()

            // An open menu says what is on now: a person muted, a hand
            // lowered, someone gone (their menu closes). The grid's tiles are
            // bound by now, so a person's menu finds their ⋯ where it is.
            popover?.refresh()

            devices?.refresh()
            drawJoinNotice()
            drawHostToasts()

            // The roster is what makes the call a room.
            applyBarLayout()
            drawConnectionPill()
        }
    }

    /**
     * A moderator's toasts, from the room as it is now: news only while the call is up, and only for a moderator, as
     * the web draws them for its moderators alone. Under the call's own header in the top end corner.
     */
    private fun drawHostToasts() {
        val connected = inRoom && isGroupCall && phase == "in_progress"

        hostToasts.place(topInset() + dp(62))
        hostToasts.update(roster, connected, connected && CallsEngine.get(context).canModerate())
    }

    private fun applyPublishControls() {
        // A call that has not been picked up has nothing to publish, and the
        // dock is answer and decline only. This runs after the phase has had
        // its say and put the microphone back next to the answer control.
        if (phase == "ringing_in" && !isGroupCall) {
            muteButton?.visibility = View.GONE
            lockedMicButton?.visibility = View.GONE

            return
        }

        if (canPublish) {
            // Total both ways: the screen outlives the call it was built for,
            // and a control hidden for a room a person was a viewer in would
            // otherwise stay hidden for the next call.
            muteButton?.visibility = if (micLocked) View.GONE else View.VISIBLE
            lockedMicButton?.visibility = if (micLocked) View.VISIBLE else View.GONE

            return
        }

        muteButton?.visibility = View.GONE
        lockedMicButton?.visibility = View.GONE
        cameraButton?.visibility = View.GONE
        flipButton?.visibility = View.GONE
        shareButton?.visibility = View.GONE
    }

    /**
     * The controls a room has and a direct call does not, and the ones it takes
     * away. The web's own conditions, one for one.
     */
    private fun applyRoomControls() {
        // Before the dock is fitted, because it may have come or gone.
        drawHostControls()

        // A group *audio* room is an audio room. The camera in a direct audio
        // call can be switched on half way through and the web offers it; in a
        // room that would publish video into a room nobody is watching video
        // in, and the web offers no camera there at all.
        if (isGroupCall && !isVideoCall) {
            cameraButton?.visibility = View.GONE
            flipButton?.visibility = View.GONE
        }

        // Raising a hand is the one thing a viewer in a broadcast room can do,
        // and the only condition the web draws the button on.
        val asking = isGroupCall && callMode == "broadcast" && !canPublish &&
            !(roster.firstOrNull { it.isLocal }?.canAdmin ?: false)

        handButton?.visibility = if (asking) View.VISIBLE else View.GONE
        handButton?.isOn = handRaised
        handButton?.contentDescription = if (handRaised) {
            CallsTheme.string(context, "lower_hand", "Lower Hand")
        } else {
            CallsTheme.string(context, "raise_hand", "Raise Hand")
        }

        // A room is left rather than hung up, and the web says so with a door
        // rather than a handset. Both ways round, because the screen outlives
        // the call it was built for.
        endButton?.glyph = if (isGroupCall) "exit" else "hangup"

        if (isGroupCall) {
            endButton?.contentDescription = CallsTheme.string(context, "exit_chat", "Exit Chat")
        }

        // Until its room is up, a group call's dock is the web's: Show Chat and
        // the way out, as it joins and while it reconnects (the web offers the
        // rest under `connectionState === 'connected'`). The room takes no
        // microphone or camera meanwhile, so their buttons would do nothing.
        // Last, over what each rule above put back.
        if (isGroupCall && phase != "in_progress") {
            for (button in listOf(
                settingsButton, muteButton, lockedMicButton, cameraButton, flipButton, speakerButton, pipButton,
                shareButton, handButton, hostControlsButton,
            )) {
                button?.visibility = View.GONE
            }
        }

        drawAddPerson()
        fitControls()
    }

    /**
     * A group call has the web's group bar: the connection at its start, the
     * dock at its end, and the grid above it (`applyGridMetrics`). A call
     * between two has its dock in the middle, over the picture.
     */
    private val groupBar: Boolean
        get() = isGroupCall && inRoom

    private fun applyBarLayout() {
        val params = controls.layoutParams as? FrameLayout.LayoutParams ?: return
        val gravity = Gravity.BOTTOM or if (groupBar) Gravity.END else Gravity.CENTER_HORIZONTAL
        val end = if (groupBar) dp(BAR_INSET) else 0

        if (params.gravity == gravity && params.marginEnd == end) return

        params.gravity = gravity
        params.marginEnd = end
        controls.layoutParams = params

        fitControls()
        applyGridMetrics()
    }

    /**
     * A group call's connection at the start of its bar, as the web's: the
     * spinner while the room connects or reconnects, the dot once it is up.
     */
    private val pillWanted: Boolean
        get() = groupBar && (phase == "connecting" || phase == "reconnecting" || phase == "in_progress")

    private fun drawConnectionPill() {
        if (!pillWanted) {
            connectionPill.visibility = View.GONE
            connectionPill.reset()

            return
        }

        connectionPill.show(phase, roster.firstOrNull { it.isLocal }?.lost ?: false)
        placeConnectionPill()
    }

    /**
     * 14 from the bar's start and level with the dock, as the web's. Not where
     * a host's own dock leaves it no room beside it, or with no dock shown.
     */
    private fun placeConnectionPill() {
        if (!pillWanted) return

        val size = dp(CallsConnectionPill.SIZE_DP)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val x = if (rtl) width - dp(BAR_INSET) - size else dp(BAR_INSET)
        val room = if (rtl) x - dp(BAR_SPACING) >= controls.x + controls.width else x + size + dp(BAR_SPACING) <= controls.x

        connectionPill.x = x.toFloat()
        connectionPill.y = controls.y + (controls.height - size) / 2f
        // It steps aside for REC, which takes its place where the dock leaves no room beside both.
        connectionPill.visibility = if (controls.visibility == View.VISIBLE && room && !recordingTakesConnection) View.VISIBLE else View.INVISIBLE

        placeRecordingPill()
    }

    private val recordingShown: Boolean
        get() = groupBar && ::recordingPill.isInitialized && recordingPill.visibility == View.VISIBLE

    /** REC stands in the connection's place, which steps aside for it. */
    private val recordingTakesConnection: Boolean
        get() = recordingShown && recordingPlace == RECORDING_AT_START

    /**
     * What the dock leaves REC in the bar beside the connection's own room: its width and the gap before it after the
     * connection, what it takes more than the connection in the connection's place, and nothing over the bar.
     */
    private val recordingRoom: Int
        get() = if (!recordingShown) 0 else when (recordingPlace) {
            RECORDING_AFTER_CONNECTION -> recordingPill.width + dp(RECORDING_GAP)
            RECORDING_AT_START -> recordingPill.width - dp(CallsConnectionPill.SIZE_DP)
            else -> 0
        }

    /** The dock at its smallest, its controls at the 24dp [fitControls] steps them down to, with its own padding. */
    private fun dockNarrowest(): Int {
        val shown = (0 until controls.childCount)
            .map { controls.getChildAt(it) }
            .filterIsInstance<CallsRoundButton>()
            .filter { it.visibility != View.GONE }

        return shown.sumOf { dp(CallsRoundButton.strideFor(it.tone, 24)) } +
            dp(CallsDock.GAP_DP) * maxOf(0, shown.size - 1) + dp(CallsDock.PADDING_DP) * 2
    }

    /**
     * REC in the bar after the connection where the dock at its smallest leaves room for both; in the connection's place
     * where it leaves room for REC alone; at the top of the call where it leaves none: REC is never left out for the dock.
     * Decided from the dock at its smallest, never from its size now, which a redraw between two layouts reads before the
     * dock has fitted itself to the last place. Placed by hand, as the connection is.
     */
    private fun placeRecordingPill() {
        if (!::recordingPill.isInitialized || recordingPill.visibility != View.VISIBLE) {
            recordingPlace = RECORDING_AFTER_CONNECTION
            return
        }

        val size = dp(CallsConnectionPill.SIZE_DP)
        val pill = recordingPill.width
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val room = width - dp(BAR_INSET) * 2 - pill - dp(BAR_SPACING)
        val narrowest = dockNarrowest()
        val place = when {
            narrowest <= room - size - dp(RECORDING_GAP) -> RECORDING_AFTER_CONNECTION
            narrowest <= room -> RECORDING_AT_START
            else -> RECORDING_OVER_BAR
        }

        if (place != recordingPlace) {
            recordingPlace = place
            // The dock's room beside it changed with it, and the connection steps aside or comes back.
            post {
                fitControls()
                placeConnectionPill()
            }
        }

        val start = if (recordingPlace == RECORDING_AFTER_CONNECTION) dp(BAR_INSET) + size + dp(RECORDING_GAP) else dp(BAR_INSET)

        if (recordingPlace == RECORDING_OVER_BAR) {
            // The top of the call, centred, where the status line stands for a moment: just over the bar it covered the
            // name of the last tile.
            recordingPill.x = (width - pill) / 2f
            recordingPill.y = (topInset() + dp(14)).toFloat()
        } else {
            recordingPill.x = (if (rtl) width - start - pill else start).toFloat()
            recordingPill.y = controls.y + (controls.height - recordingPill.height) / 2f
        }
    }

    /**
     * Size the dock to the phone it is on.
     *
     * The stylesheet steps its controls down as the stage narrows and so does
     * iOS: 30 on a phone, 32 and 34 wider, with 5 between them. That is the
     * size this starts from. It comes down further only when the row will not
     * fit — a group video call shows nine of these, and the two on the ends
     * used to be cut off by the screen.
     *
     * Hanging up and answering are pills, so they take more width than they
     * do height and are measured that way.
     */
    private fun fitControls() {
        val shown = (0 until controls.childCount)
            .map { controls.getChildAt(it) }
            .filterIsInstance<CallsRoundButton>()
            .filter { it.visibility != View.GONE }

        if (shown.isEmpty()) return

        val gap = dp(CallsDock.GAP_DP)

        // What the dock has to fit inside: the screen, less the 12 either
        // side the row is held off the edges by, or in a group call's bar the
        // 14 at either end and the connection with its gap, and the dock's own
        // padding either side.
        val room = if (groupBar) {
            resources.displayMetrics.widthPixels - dp(BAR_INSET) * 2 - dp(CallsConnectionPill.SIZE_DP) - dp(BAR_SPACING) - recordingRoom
        } else {
            resources.displayMetrics.widthPixels - dp(12) * 2
        }
        val available = room - dp(CallsDock.PADDING_DP) * 2

        fun rowWidth(side: Int): Int =
            shown.sumOf { dp(CallsRoundButton.strideFor(it.tone, side)) } + gap * (shown.size - 1)

        var side = CallsRoundButton.diameterDp(widthDp())

        while (side > 24 && rowWidth(side) > available) side -= 2

        val diameter = dp(side)

        for ((index, child) in shown.withIndex()) {
            val width = dp(CallsRoundButton.strideFor(child.tone, side))
            val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue

            // The gap belongs to every control except the one at the front, so
            // it has to be compared as well as the size. Configure only appears
            // once the call connects, and the control it appears in front of
            // was given a zero margin back when it was the first one. Nothing
            // about its size changed when it stopped being first, so this
            // skipped it and the two sat touching.
            val start = if (index == 0) 0 else gap

            if (params.width == width &&
                params.height == diameter &&
                params.marginStart == start
            ) continue

            params.width = width
            params.height = diameter
            params.marginStart = start
            params.marginEnd = 0

            child.layoutParams = params
        }
    }

    /**
     * The identity block, in the shape the call it belongs to needs.
     *
     * A room draws the grid where the face would be, so the name and the clock
     * become one small line in the strip the grid leaves above it — inset past
     * the minimise control, which shares that strip.
     */
    private fun applyIdentityMetrics() {
        // A band is short, and a room is crowded: both want the identity
        // out of the way rather than filling the screen with it.
        val tight = inRoom || compact

        if (identityInRoom == tight) return

        identityInRoom = tight

        if (tight) {
            nameLabel.textSize = 17f
            statusLabel.textSize = 13f

            (nameLabel.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = dp(14)
                it.marginStart = dp(72)
                it.marginEnd = dp(72)
                nameLabel.layoutParams = it
            }

            (statusLabel.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = dp(38)
                statusLabel.layoutParams = it
            }

            return
        }

        nameLabel.textSize = 24f
        statusLabel.textSize = 15f

        (nameLabel.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = dp(250)
            it.marginStart = 0
            it.marginEnd = 0
            nameLabel.layoutParams = it
        }

        (statusLabel.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = dp(290)
            statusLabel.layoutParams = it
        }
    }

    override fun onScreenClose() {
        post {
            hideError()
            host.closeCallScreen()
        }
    }

    private fun applyState(state: JSONObject) {
        lastState = state
        phase = state.optString("phase", "idle")
        isVideoCall = state.optString("type") == "video"
        isGroupCall = state.optBoolean("group", false)
        callMode = state.optString("callMode", "participatory")
        canShareScreen = state.optBoolean("canShareScreen", true)
        waitingForPlace = state.optBoolean("waiting", false)

        val thread = state.optInt("threadId")

        if (thread != 0 && thread != lastNameThread) {
            lastNameThread = thread
            lastName = ""
        }

        val name = state.optString("peerName")

        if (name.isNotEmpty()) lastName = name

        val shown = name.ifEmpty { lastName }

        nameLabel.text =
            shown.ifEmpty { CallsTheme.string(context, "unknown_caller", "Unknown") }

        avatar.text = CallsAvatars.initials(shown)

        val local = state.optJSONObject("local")
        val isMuted = local?.optBoolean("muted", false) ?: false
        val hasCamera = local?.optBoolean("camera", false) ?: false

        // One's own camera is not mirrored, here or in the grid: you see
        // yourself the way the other side does, as the web shows it.
        drawSelfStage()

        // A control that says the opposite of what it does is worse than one
        // that says nothing, so these two follow the state they are in.
        muteButton?.contentDescription = if (isMuted) {
            CallsTheme.string(context, "enable_microphone", "Enable Microphone")
        } else {
            CallsTheme.string(context, "disable_microphone", "Disable Microphone")
        }

        cameraButton?.contentDescription = if (hasCamera) {
            CallsTheme.string(context, "disable_camera", "Disable Camera")
        } else {
            CallsTheme.string(context, "enable_camera", "Enable Camera")
        }

        muteButton?.isOn = isMuted
        // `isOn` is the stylesheet's `bm-enable-video`: the state where the
        // camera is *not* running and the button turns it back on.
        cameraButton?.isOn = !hasCamera

        // Nobody rings a room and nobody answers one, so a group call never
        // shows the answer control whatever the phase says.
        val ringingIn = phase == "ringing_in" && !isGroupCall

        // One dock throughout, as on iOS: answering joins the row and the
        // toggles step out of it, rather than a second row of its own
        // appearing while the first is hidden.
        answerButton?.visibility = if (ringingIn) View.VISIBLE else View.GONE

        // Both are offered whatever the call was placed as: a camera can be
        // switched on in the middle of an audio call, and the web offers it
        // there too. Turning it round is only worth offering once there is
        // something to turn.
        cameraButton?.visibility = if (ringingIn) View.GONE else View.VISIBLE
        muteButton?.visibility = if (ringingIn) View.GONE else View.VISIBLE
        speakerButton?.visibility = if (ringingIn) View.GONE else View.VISIBLE

        flipButton?.visibility = if (!ringingIn && local?.optBoolean("camera", false) == true) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // A call getting its connection back is still the call: the web keeps
        // every control while the other side reads as lost, and this took
        // four of them away every time the network blinked.
        val running = phase == "in_progress" || phase == "reconnecting"

        // Sharing a screen only makes sense once there is a call to share it
        // into.
        shareButton?.visibility = if (running && canShareScreen) View.VISIBLE else View.GONE
        // Host Controls is drawHostControls' to show, from applyRoomControls below.
        settingsButton?.visibility = if (running) View.VISIBLE else View.GONE

        // Only in a video call, or a group call with someone to show, only once
        // it is running, and only where the device has the feature at all.
        pipButton?.visibility = if (pipOffered) View.VISIBLE else View.GONE

        endButton?.contentDescription = if (ringingIn) {
            CallsTheme.string(context, "reject", "Reject")
        } else {
            CallsTheme.string(context, "end_call", "End Call")
        }

        applyPublishControls()
        applyRoomControls()
        shareButton?.isOn = local?.optBoolean("screen", false) ?: false

        // A room rule that flipped (everyone muted, the lock, broadcast mode)
        // turns over in an open Host Controls too.
        popover?.refresh()

        if (running) {
            // The engine has owned the start since the call went up, so the
            // figure is taken from there rather than from the first frame this
            // screen saw: a screen that timed itself started again from zero
            // every time the call came back, while the other side carried on.
            val started = System.currentTimeMillis() - state.optInt("durationMs", 0)

            if (connectedAt == 0L || Math.abs(started - connectedAt) > 1000L) {
                connectedAt = started
            }

            handler.removeCallbacks(tick)
            handler.post(tick)

            // A video call needs the screen for real, so this is where the
            // keyguard is asked to step aside.
            if (isVideoCall) host.dismissKeyguard()
        } else {
            connectedAt = 0L
            handler.removeCallbacks(tick)
            statusLabel.text = statusText()
        }

        // The screen outlives the call it was built for. A call that ended
        // with the dock tapped away would otherwise hand the next one a hidden
        // dock, with nothing on screen to say why or how to get it back — and
        // a call in trouble keeps ending it one tap away.
        if (phase != "in_progress") restoreControls()

        // A room's line comes and goes with what it has to say, a wait for a
        // place starting or ending included, not with the next roster alone.
        if (inRoom) drawIdentity()

        applyBarLayout()
        drawConnectionPill()

        // The other side's microphone. A silence with no explanation reads as
        // a broken call, and this is the difference between "they muted
        // themselves" and "something went wrong".
        val peer = state.optJSONObject("peer")
        val peerKnown = peer?.optBoolean("known", false) ?: false
        // "The other side" is a two-person idea. In a room each tile carries
        // its own struck-through microphone and there is no one person the
        // banner could be about.
        val peerMicOff = !inRoom && phase == "in_progress" && peerKnown &&
            !(peer?.optBoolean("microphone", false) ?: false)

        val cameraOn = peer?.optBoolean("camera", false) ?: false
        val screenOn = peer?.optBoolean("screen", false) ?: false

        if (peerKnown != peerStateKnown || cameraOn != peerCameraOn || screenOn != peerScreenOn) {
            // A share that has just started is what the person wants to see.
            if (screenOn && !peerScreenOn) swapped = false

            peerStateKnown = peerKnown
            peerCameraOn = cameraOn
            peerScreenOn = screenOn

            drawPeerVideo()

            // What is drawn decides what size is asked for, so the wish has to
            // be said again whenever it changes.
            viewChanged()
        }

        peerNotice.visibility = if (peerMicOff) View.VISIBLE else View.GONE

        // The web says "and camera" only where there is no picture to explain
        // it, and says the microphone alone in the corner of one there is.
        val peerHasPicture = (peer?.optBoolean("camera", false) ?: false) ||
            (peer?.optBoolean("screen", false) ?: false)

        peerNoticeText.text = if (!peerHasPicture && isVideoCall) {
            CallsTheme.string(
                context,
                "peer_mic_and_camera_off",
                "The microphone and camera not enabled at the other side"
            )
        } else {
            CallsTheme.string(
                context,
                "peer_mic_off",
                "The microphone is not enabled at the other side"
            )
        }

        placePeerNotice(peerHasPicture)

        // The connection is being got back.
        // `.bm-placeholder-participant-lost-message` is the web's answer: the
        // same glass chip, at the top and centred, with the call's clock still
        // running under the name — where this used to take the clock's place
        // whenever there was no picture over it. It names whose connection it
        // is: the other side's was said of this side's own bad network too.
        lostNotice.visibility =
            if (phase == "reconnecting" && !inRoom) View.VISIBLE else View.GONE

        lostNoticeText?.text = if (state.optString("trouble") == "self") {
            CallsTheme.string(context, "reconnecting_self", "Reconnecting...")
        } else {
            CallsTheme.string(context, "reconnecting", "The other side lost connection")
        }

        // The host's own pieces, for the phase now (CallsPieces).
        arrangeDock()
        drawStagePiece()

        // Device Settings says what is live now, the join notice goes once a device is on, and a moderator's toasts
        // start again from who is there after this side reconnects.
        devices?.refresh()

        // A recording: REC in the bar, the notice over the dock, the question to stay or leave over everything. Before
        // the join notice and the asks, whose cards wait while the notice shows.
        applyRecording(state)

        drawJoinNotice()
        drawHostToasts()

        // A moderator's asks: the card over the dock, the answers' toasts, and the asks answered by a device turned on.
        applyAsks(state)

        // Who a moderator put in the spotlight for everyone: the main tiles, ahead of this user's own pins.
        applySpotlight(state)

        // The question to stay or leave is answered before anything else, over whatever came up since.
        recordingQuestion?.bringToFront()

        if (phase == "idle") {
            hideDevices()
            question?.close()
            host.closeCallScreen()
        }
    }

    // The host's pieces (ourControls and stagePiece are declared above init, which buildControls runs in)

    /** The dock in the host's order, its own buttons among ours; hanging up stays last, whatever it answers. */
    private fun arrangeDock() {
        val arrange = CallsPiecesStore.current.controls ?: return
        if (ourControls.isEmpty()) return
        val end = ourControls.firstOrNull { it.id == "end" }
        val now = (0 until controls.childCount).map { controls.getChildAt(it) }
        val shown = (arrange(ourControls, phase, context).filter { it.id != "end" } + listOfNotNull(end)).map { keepDocked(it, now) }
        if (now == shown.map { it.view }) return
        controls.removeAllViews()
        for (control in shown) {
            (control.view.parent as? ViewGroup)?.removeView(control.view)
            controls.addView(control.view, LinearLayout.LayoutParams(0, 0))
        }
        fitControls()
    }

    /**
     * A host's own button the dock already shows under the same id, kept: the pieces run on every change of the call,
     * many a second while it connects, and a host that makes its button each time (as `CallControl.own` in `controls`
     * reads) would otherwise have the whole dock taken down and built again each time. The kept one takes the new
     * one's label, icon, latch and action.
     */
    private fun keepDocked(control: CallsControl, now: List<View>): CallsControl {
        val fresh = control.view as? CallsRoundButton ?: return control
        if (ourControls.any { it.view === fresh }) return control
        val docked = now.firstOrNull { it !== fresh && it.tag == fresh.tag && it.tag != null } as? CallsRoundButton ?: return control
        if (docked.tone != fresh.tone) return control
        docked.glyph = fresh.glyph
        docked.isOn = fresh.isOn
        docked.contentDescription = fresh.contentDescription
        docked.action = fresh.action
        return CallsControl(control.id, docked)
    }

    /** The host's view over the stage of a call between two, under the dock. */
    private fun drawStagePiece() {
        val own = if (isGroupCall) null else CallsPiecesStore.current.stage?.invoke(phase, context)
        if (own === stagePiece) return
        stagePiece?.let { removeView(it) }
        stagePiece = own
        if (own == null) return
        (own.parent as? ViewGroup)?.removeView(own)
        addView(own, indexOfChild(controls), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }


    /** The host's own status line, where it gives one (CallsPieces). */
    private fun pieceStatus(): String? =
        CallsPiecesStore.current.status?.invoke(phase, ((System.currentTimeMillis() - connectedAt) / 1000).toInt().coerceAtLeast(0))

    private fun statusText(): String = pieceStatus() ?: when {
        // The mesh is keeping the room's join waiting for a place in the call,
        // which the web's call screen says in place of "Connecting...".
        waitingForPlace && (phase == "connecting" || (isGroupCall && phase != "in_progress")) ->
            CallsTheme.string(context, "waiting_for_place", "Waiting for a free place in the call...")

        // A room is joined, not dialled: it has no ring and nothing is
        // "calling". The web's group call says it is connecting with the
        // spinner at the start of its bar alone (`connectionPill`), and its
        // status line is for the call's notices: nothing to say here.
        isGroupCall && phase != "in_progress" -> ""

        else -> statusForPhase()
    }

    private fun statusForPhase(): String = when (phase) {
        "ringing_out" -> CallsTheme.string(context, "calling", "Calling…")
        // The web's card names the kind of call that is coming in, and so does
        // this. `incoming_call` stays in the table for app binaries older than
        // 10 Sep, which still ask for it.
        "ringing_in" -> if (isVideoCall) {
            CallsTheme.string(context, "incoming_video_call", "Incoming Video Call")
        } else {
            CallsTheme.string(context, "incoming_audio_call", "Incoming Audio Call")
        }
        "connecting" -> CallsTheme.string(context, "connecting", "Connecting...")
        // The clock goes on through trouble, as the web's does; the chip says
        // what the trouble is.
        "reconnecting", "in_progress" -> durationText()
        else -> ""
    }

    /**
     * `Locale.ROOT` for the same reason the byte counts use it: the clock the
     * web and iOS draw is `5:03` whatever the reader's language, and
     * `String.format` with no locale hands an Arabic or Persian phone its own
     * digits — `٥:٠٣` beside the browser's.
     */
    private fun durationText(): String {
        val seconds = ((System.currentTimeMillis() - connectedAt) / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60

        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60)
        }
    }




    /**
     * Android refuses a window flatter than 2.39:1 or narrower than 1:2.39, and
     * throws rather than clamping, so a very wide screen share is brought
     * inside the range instead of taking the call down.
     */
    private fun ratioWithin(width: Int, height: Int): Rational {
        val value = width.toFloat() / height.toFloat()

        return when {
            value < 1f / 2.39f -> Rational(100, 239)
            value > 2.39f -> Rational(239, 100)
            else -> Rational(width, height)
        }
    }


    /** In the corner there is room for the picture and nothing else. */
    /**
     * The pieces whose size is a fraction of the box rather than a constant.
     *
     * One's own picture is a 104 by 152 tile because that is a sensible corner
     * of a phone; in a band about 357dp tall it is nearly half the height, and
     * it ran off the edge. The control row stands on the dock's floor above
     * the navigation bar, which a band does not have under it — the page owns
     * everything below the box.
     */
    private fun applyBoxMetrics() {
        (chip.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = if (compact) dp(72) else dp(104)
            it.topMargin = if (compact) dp(10) else dp(48)
            it.marginEnd = if (compact) dp(10) else dp(16)

            chip.layoutParams = it
        }

        // Only the width is a metric. The height comes from the picture's own
        // orientation, or one of the two sizes is a lie and the fill crops the
        // difference away.
        (peerChip.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = if (compact) dp(72) else dp(104)
            peerChip.layoutParams = it
        }

        sizeTile(peerChip)
        sizeTile(chip)

        (controls.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = if (compact) dp(8) else bottomInset + dp(DOCK_FLOOR)
            controls.layoutParams = it
        }

        // A band has the dock's chat button, which says Hide Chat there, and the whole picture: a tap anywhere takes
        // the call back. That only works because the tap is answered *here*: this view fills the band, so a listener
        // on the band behind it never sees one.
    }

    /** The consent dialog's answer, which only the host sees. */
    fun setSharingScreen(sharing: Boolean) {
        shareButton?.isOn = sharing
    }

    /**
     * The shape a corner window should be cut to, so it follows the picture
     * rather than the phone it is on.
     */
    val cornerAspect: Rational?
        get() = if (inRoom) {
            grid.aspectOf(cornerFocus)?.let { Rational((CallsPipFocus.windowAspect(it) * 1000).toInt(), 1000) }
        } else {
            pictureRatio
        }

    /** Whether there is anything worth putting in a corner window yet. */
    val canEnterCorner: Boolean
        get() = (phase == "in_progress" || phase == "reconnecting") && (if (inRoom) cornerFocus != null else stageReveal.painted)

    /**
     * The window went away or came back. Stopped is "not shown" unless the
     * corner window is up, and the other side should stop paying for a picture
     * nobody can see.
     */
    fun setWindowStopped(value: Boolean) {
        stopped = value
        viewChanged()
    }

    fun setCornerWindow(inPictureInPictureMode: Boolean) {
        val chrome = if (inPictureInPictureMode) View.GONE else View.VISIBLE

        // A group call's window is one person of it, their tile over everything
        // the call draws around its grid, its notices included. A window this
        // small has no room for them, and a window takes no press.
        cornerWindow = inPictureInPictureMode
        grid.audioRoom = isGroupCall && !isVideoCall && !cornerWindow
        grid.solo = if (cornerWindow) cornerFocus else null
        grid.translationZ = if (cornerWindow && inRoom) dp(64).toFloat() else 0f

        // The dock carries the answer control now, so hiding it while a call
        // rings in would leave nothing to answer with.
        controls.visibility = chrome
        placeConnectionPill()

        // Not straight to visible on the way out: over a picture these stay
        // away whether the window is in the corner or not.
        drawIdentity()
        chip.visibility =
            if (inPictureInPictureMode || inRoom || !chipReveal.painted) View.GONE else View.VISIBLE

        // The avatar stands in for a camera that is off, and it is drawn for
        // the whole screen: a 108dp circle held 120dp down from the top. A
        // corner window is about that tall in total, so when the other side
        // turned its camera off with the call in the corner, the circle
        // arrived far too big and half of it below the bottom edge. In the
        // corner it is sized to the window and centred in it, like the
        // picture it is standing in for.
        (avatar.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (inPictureInPictureMode) {
                it.width = dp(48)
                it.height = dp(48)
                it.topMargin = 0
                it.gravity = Gravity.CENTER
            } else {
                it.width = dp(108)
                it.height = dp(108)
                it.topMargin = dp(120)
                it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }

            avatar.layoutParams = it
        }

        avatar.textSize = if (inPictureInPictureMode) 15f else 34f

        applyGridMetrics()
    }

    /**
     * The grid clears what is drawn around it, and nothing more.
     *
     * Only the strip above it, and only while something is drawn there. That
     * was a constant 80dp for the name and the clock, which a room stopped
     * drawing — so every group call opened with an empty band above the first
     * tile and the tiles 140 pixels shorter each for nothing.
     *
     * **A group call's tiles stop above the bar**, as the web's do: its grid is
     * the stage less the bar (group-video-grid.tsx), with 8 pixels of the
     * bar's padding and the tile's own gutter between the last tile and the
     * dock. The bar never goes away there, so nothing moves the tiles. A
     * call between two keeps its picture under the dock, which a tap puts away.
     *
     * The navigation bar is not ours to draw over either. The dock has always
     * been lifted clear of it, and the grid is told too, or the last tile runs
     * off under the bar.
     */
    private fun applyGridMetrics() {
        val params = grid.layoutParams as? FrameLayout.LayoutParams ?: return

        val strip = nameLabel.visibility == View.VISIBLE || statusLabel.visibility == View.VISIBLE
        val top = if (strip && !cornerWindow) dp(80) else 0
        val bottom = if (cornerWindow && inRoom) {
            0
        } else if (groupBar) {
            val floor = (controls.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: bottomInset
            val dock = controls.height.takeIf { it > 0 } ?: controls.measuredHeight

            floor + dock + dp(BAR_GAP)
        } else {
            bottomInset
        }

        if (params.topMargin == top && params.bottomMargin == bottom) return

        params.setMargins(0, top, 0, bottom)
        grid.layoutParams = params
    }





}
