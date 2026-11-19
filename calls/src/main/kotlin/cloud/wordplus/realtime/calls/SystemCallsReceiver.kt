package cloud.wordplus.realtime.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Decline on a ring's notification, and Hang up on a call's (SystemCalls). They arrive with the app in any state: not on
 * screen, or started by a push with nothing else running.
 */
class SystemCallsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(SystemCalls.EXTRA_CALL) ?: return
        CallLog.write("notification action=${intent.action} call=$id")
        when (intent.action) {
            SystemCalls.ACTION_DECLINE -> SystemCalls.declineFromSystem(id)
            SystemCalls.ACTION_HANGUP -> SystemCalls.hangUpFromSystem(id)
        }
    }
}
