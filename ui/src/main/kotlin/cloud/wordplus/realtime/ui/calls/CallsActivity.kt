package cloud.wordplus.realtime.ui.calls

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.util.Rational
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import livekit.org.webrtc.VideoTrack
import org.json.JSONObject

/**
 * A window for the call to be seen in when the app is not already in front.
 *
 * The call itself is [CallsStage], a view — it used to be this activity, and
 * the band over the conversation was a second, smaller call built beside it.
 * Two of everything, and handing a call between them tore down one renderer to
 * build another.
 *
 * What is left here is the part a view cannot do: showing over a locked screen
 * and turning the display on, which is what the full-screen intent of a ringing
 * notification opens, plus the handful of things the system will only grant an
 * Activity — permissions, screen-capture consent, the corner window and the
 * keyguard.
 */
class CallsActivity : Activity(), CallsStage.Host {
    companion object {
        const val EXTRA_CALL_ID = "bm_call_id"

        /**
         * Open this and go straight to the corner window.
         *
         * A corner window is a window's to ask for, and while the call is in
         * the page's box there is no window of its own to ask. Leaving the app
         * — or the corner control on the box — brings this one up for exactly
         * as long as it takes to ask, behind the app-switch animation.
         */
        const val EXTRA_CORNER = "bm_call_corner"

        /** How long the corner waits for the picture it is to be cut to. */
        private const val CORNER_WAIT_MS = 1_500L

        private var current: CallsActivity? = null

        /**
         * Whether the call screen is on screen right now.
         *
         * The incoming ring is a full-screen-intent notification, and the whole
         * job of that intent is to put this screen in front of the person. When
         * it is already in front there is nothing for it to do, so all it adds
         * is a banner over the very thing it would have opened.
         */
        @Volatile
        var showing = false
            private set

        fun open(context: Context, callId: String, corner: Boolean = false) {
            val intent = Intent(context, CallsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CORNER, corner)
            }

            context.startActivity(intent)
        }

        /** The call put aside, as the dock's Show Chat puts it: minimised, and the app in front again with the call going on. */
        fun putAway() {
            current?.let { activity ->
                activity.runOnUiThread {
                    CallsEngine.get(activity).setMinimised(true)
                    activity.closeCallScreen()
                }
            }
        }

        fun close() {
            current?.let { activity ->
                activity.runOnUiThread {
                    activity.finish()

                    // No window animation on the way out. Whatever is beneath
                    // is already carrying the picture, so sliding this away
                    // only puts a moving black rectangle over the thing it is
                    // revealing.
                    @Suppress("DEPRECATION")
                    activity.overridePendingTransition(0, 0)
                }
            }
        }
    }

    private lateinit var stage: CallsStage

    /** When the corner was first asked for, so the wait for it is bounded. */
    private var askedCornerAt = 0L

    // MARK: What the stage cannot do for itself

    override val activity: Activity get() = this

    override val inCornerWindow: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    /**
     * The microphone and, for a video call, the camera.
     *
     * Asked here rather than when the media starts, because this is the only
     * place with an Activity to put the prompt on and a call that opens a
     * microphone it was never granted is a call with no sound and no
     * explanation. But never while the call is still ringing: a dialog then
     * lands on top of the answer button. Answering moves the phase on and the
     * stage asks again.
     */
    override fun requestCallPermissions(state: JSONObject) {
        if (state.optString("phase") == "ringing_in") return

        CallsPermissions.request(this, state.optString("type") == "video")
    }

    override fun startScreenCapture() {
        CallsScreenCapture.requestFrom(this)
    }

    override fun enterCornerWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!stage.canEnterCorner || inCornerWindow) return

        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(stage.cornerAspect ?: Rational(9, 16))
                    .build()
            )
        } catch (e: Exception) {
            // The device or the policy said no; the call carries on in front.
        }
    }

    /** A group call's window took the shape of whom it follows now. */
    override fun cornerShapeChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !inCornerWindow) return

        val aspect = stage.cornerAspect ?: return

        try {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(aspect).build())
        } catch (e: Exception) {
            // The window keeps its shape, and the tile fits the picture in it.
        }
    }

    /**
     * A video call wants the keyguard out of the way once it is answered: the
     * camera is useless behind it.
     */
    override fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return

        val manager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return

        manager.requestDismissKeyguard(this, null)
    }

    /**
     * Out of the call window and back to the conversation.
     *
     * Moving the app's own task to the front, which is not the same as
     * launching it: the launch intent carries ACTION_MAIN and
     * RESET_TASK_IF_NEEDED, so it brings the app back at its *root* — the
     * inbox — and the band only draws on the conversation the call belongs to.
     * The messages list came up with no call in it, which is a worse fault
     * than the one it replaced.
     *
     * Never this window's own task, which it has when the app's activity has
     * an affinity of its own, as Flutter's template gives it
     * (`taskAffinity=""`): raising that one raises nothing, and a call over
     * before this window was ever shown, declined within a moment of ringing,
     * left the launcher in front once it finished.
     */
    override fun closeCallScreen() {
        try {
            val own = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.appTasks
                ?.firstOrNull { task ->
                    val base = task.taskInfo?.baseActivity
                    base?.packageName == packageName && base.className != CallsActivity::class.java.name
                }

            if (own != null) {
                own.moveToFront()
            } else {
                // Nothing of ours to raise — a cold start into a call from a
                // notification. The launcher is the only way back then, root
                // and all.
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
            }
        } catch (e: Exception) {
            // The system refused it. Finishing still gets this out of the way.
        }

        finish()
    }

    // MARK: The window

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOverLockScreen()

        stage = CallsStage(this, this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(stage)

        showStatusBar()
        watchInsets()

        current = this

        stage.attach()
    }

    /**
     * The clock stays, the way it does on an iPhone in a call.
     *
     * The activity's theme is `Theme.NoTitleBar.Fullscreen`, which hides the
     * status bar outright — so a call was the one screen in the app with no
     * time, no battery and no signal on it, while the same call on iOS keeps
     * all three. The stage simply runs underneath it now.
     *
     * The bar's own icons are left light: the stage is near-black whatever the
     * palette says.
     */
    private fun showStatusBar() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, stage).apply {
            show(WindowInsetsCompat.Type.statusBars())
            isAppearanceLightStatusBars = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }
    }

    /**
     * Keep the dock clear of the system bars.
     *
     * The window is fullscreen, so it reaches behind the navigation bar and
     * nothing insets anything for us — iOS gets this from `safeAreaLayoutGuide`
     * and there is no equivalent here. Read rather than assumed, because a
     * gesture bar, a three-button bar and a notch are three different numbers,
     * and they change when the phone is turned.
     */
    private fun watchInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(stage) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            stage.applyBottomInset(bars.bottom)

            insets
        }

        ViewCompat.requestApplyInsets(stage)
    }

    /**
     * A ringing phone is usually locked. This is what puts the call in front
     * of the person without them unlocking it first; answering an audio call
     * needs no more than this, and a video call asks for the keyguard once it
     * is answered.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Back on screen, so the call is not minimised any more — however the
     * person got here, the dock's Show Chat or the notification that undoes
     * it. Left set, the engine would go on reporting a minimised call and
     * `setPhase` would refuse to reopen this screen the next time it mattered.
     */
    override fun onResume() {
        super.onResume()

        CallsEngine.get(this).setMinimised(false)

        askedCornerAt = 0L

        enterCornerAsAsked()
    }

    /**
     * Opened only to ask for the corner, which needs two things this window
     * does not have the instant it is created.
     *
     * It has to be resumed — the request is refused otherwise, which is why it
     * is asked from here — and it has to have a picture: the corner is cut to
     * the shape of what is on the stage, and this stage was made a moment ago
     * and has not been sent a frame yet. Asking anyway is refused, and the
     * person who pressed Home is left looking at a full-screen call instead of
     * the home screen.
     *
     * So it waits for the picture it was opened to show, and not for long: an
     * audio call never gets one, and the corner is not for it.
     */
    private fun enterCornerAsAsked() {
        if (intent?.getBooleanExtra(EXTRA_CORNER, false) != true) return

        if (askedCornerAt == 0L) askedCornerAt = System.currentTimeMillis()

        if (!stage.canEnterCorner) {
            if (System.currentTimeMillis() - askedCornerAt > CORNER_WAIT_MS) {
                intent.removeExtra(EXTRA_CORNER)

                return
            }

            stage.postDelayed({ enterCornerAsAsked() }, 80)

            return
        }

        // Cleared once it is asked, so coming back out of the corner does not
        // put the call straight into it again.
        intent.removeExtra(EXTRA_CORNER)

        enterCornerWindow()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != CallsScreenCapture.REQUEST_CODE) return

        CallsEngine.get(this).startScreenShare(resultCode, data)

        stage.setSharingScreen(resultCode == RESULT_OK)
    }

    /** Leaving with a call up puts it in the corner, as a phone call does. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        enterCornerWindow()
    }

    override fun onPictureInPictureModeChanged(
        inPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)

        stage.setCornerWindow(inPictureInPictureMode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)

        if (requestCode != CallsPermissions.REQUEST_CODE) return

        // Whatever was granted, the call carries on with what it has: a video
        // call with the camera refused is still an audio call, and refusing
        // the microphone is answered by the engine, not by a dead screen.
        CallsEngine.get(this).permissionsSettled()

        // A moderator's ask whose Turn On asked for them says what came of it.
        CallsAsks.onPermissions?.invoke()
    }

    override fun onStart() {
        super.onStart()

        showing = true
        stage.setWindowStopped(false)
    }

    override fun onStop() {
        super.onStop()

        showing = false
        stage.setWindowStopped(true)
    }

    override fun onDestroy() {
        stage.detach()

        if (current === this) current = null

        // The page may have been holding the call when this window borrowed it
        // for the corner. Its box never went anywhere and its renderers were
        // never released — only the listening moved, and it moves back.
        CallsFrame.reattach()

        // And if it was not — this window swiped out of the recents list, say —
        // then nothing is showing the call, and the bar at the top of the
        // messenger is the only way back to it.
        CallsEngine.get(this).keepAWayBack()

        super.onDestroy()
    }

    /**
     * Asked for the corner again while this window was already up. The extra
     * lives on the intent, so the new one has to replace the old.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent != null) setIntent(intent)
    }

    /** The call keeps running; the chat is what comes back. */
    override fun onBackPressed() {
        CallsEngine.get(this).setMinimised(true)

        finish()
    }
}

/** What the call screen is told, so the engine does not hold an Activity. */
interface CallsScreenListener {
    fun onScreenState(state: JSONObject)
    /** Everyone in a room call, for the grid. */
    fun onScreenRoster(participants: List<CallsRoomParticipant>)
    fun onScreenRemoteVideo(source: CallsSource, track: VideoTrack?)
    fun onScreenLocalVideo(track: VideoTrack?)
    fun onScreenClose()

    /**
     * The other side of a two-person room is changing its camera's codec
     * ([CallsCameraSwitch]): up or down, as the room holds it until the
     * frame that ends it, and their picture, which the placeholder is drawn
     * over, blurred.
     */
    fun onScreenPeerOptimizing(optimizing: Boolean, avatar: String?) {}

    /**
     * What of the other side's stopped arriving in a two-person room while
     * they are still in it ([CallsStallWatch]), or nothing: their picture
     * blurred, and "Video interrupted" over a camera that alone stopped.
     */
    fun onScreenPeerStalled(stalled: CallsStalled?) {}

    /**
     * Something went wrong, said where a call can be read.
     *
     * The page turns the same message into a toast and the call screen covers
     * the page, so this is the only surface that can show it while a call is
     * up.
     */
    fun onScreenError(message: String)

    /**
     * Something asked to unmute a microphone a moderator keeps off, and it was
     * not: the screen says why, as a press on its locked button does.
     */
    fun onScreenMicHeld() {}

    /**
     * How big each of the other side's pictures is drawn here, so each is sent
     * at the size it is shown at. Zero means "not shown", which switches that
     * source off at the far end.
     *
     * Every surface that shows a call has to answer this, not just the full
     * screen. The engine used to ask `screenListener as? CallsActivity`, so
     * when the call moved to the band nothing answered — and the activity had
     * just reported zero on its way out, correctly, for itself. The far end
     * switched its camera off and never heard otherwise, which is the picture
     * going black on the other side the moment the messages list came up.
     */
    fun viewHint(): JSONObject
}
