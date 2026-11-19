package cloud.wordplus.realtime.calls

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * What keeps a call alive while the app is not on screen (SystemCalls).
 *
 * Android freezes an ordinary process the moment it goes to the background, which for a call means the audio stops and
 * the connection dies. A foreground service of the `phoneCall` type is how a call says it is one, and its notification
 * is the ongoing call's, with Hang up. With the phone at the ear the screen goes off, or a cheek ends the call.
 */
class SystemCallsService : Service() {
    companion object {
        private const val EXTRA_CALL = "cloud.wordplus.realtime.calls.SERVICE_CALL"

        fun start(context: Context, id: String) {
            val intent = Intent(context, SystemCallsService::class.java).putExtra(EXTRA_CALL, id)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { CallLog.write("the call's service could not start: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SystemCallsService::class.java))
        }
    }

    private var proximity: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = SystemCalls.ongoingNotification(intent?.getStringExtra(EXTRA_CALL) ?: "")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            try {
                startForeground(SystemCalls.NOTIFICATION_ONGOING, notification, type)
            } catch (first: Exception) {
                // Android 14 refuses a microphone or camera service started from the background, which is where a call
                // answered from a push starts. `phoneCall` is allowed there, and carries the microphone and the camera
                // for an app holding MANAGE_OWN_CALLS.
                try {
                    startForeground(SystemCalls.NOTIFICATION_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } catch (second: Exception) {
                    // Nothing left to try: the call runs while the app is on screen.
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        } else {
            startForeground(SystemCalls.NOTIFICATION_ONGOING, notification)
        }

        if (proximity == null) {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                proximity = runCatching { power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "WordPlus:call").apply { acquire(60 * 60 * 1000L) } }.getOrNull()
            }
        }

        // Started again without its call, it would be a notification for a call that is not happening.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { proximity?.takeIf { it.isHeld }?.release() }
        proximity = null
        super.onDestroy()
    }
}
