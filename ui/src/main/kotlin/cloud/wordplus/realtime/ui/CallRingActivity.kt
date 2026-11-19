package cloud.wordplus.realtime.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * The app's own ringing view (`CallScreen.Pieces.ringing`), full screen while a call rings in, over the lock screen as
 * our call screen is. It closes when the call is answered (the call screen then shows it), declined, or over.
 */
class CallRingActivity : Activity() {
    companion object {
        private var shown: WeakReference<CallRingActivity>? = null

        internal fun open(context: Context) {
            context.startActivity(Intent(context, CallRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** The ringing view taken away, where it is on. */
        internal fun finishShown() {
            shown?.get()?.finish()
            shown = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val view = CallScreen.ringView(this)
        if (view == null) {
            finish()
            return
        }
        shown = WeakReference(this)
        setContentView(view)
    }

    override fun onDestroy() {
        if (shown?.get() === this) shown = null
        super.onDestroy()
    }
}
