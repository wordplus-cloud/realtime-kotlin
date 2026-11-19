package cloud.wordplus.realtime.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.org.webrtc.ScreenCapturerAndroid
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
import kotlin.coroutines.resume

/**
 * Sharing this phone's screen, on a call, a group call or a live stream.
 *
 * Android hands a screen over only once the person says so in the system's own dialog, so an activity of the app asks,
 * and the answer goes to `setScreenShare`:
 *
 * ```kotlin
 * startActivityForResult(ScreenShare.permissionIntent(this), SHARE)
 * // onActivityResult, with RESULT_OK:
 * scope.launch { call.setScreenShare(true, data) }
 * ```
 *
 * An answer is good for one share: stopping one and sharing again asks again.
 */
object ScreenShare {
    /** The system's dialog that asks for the screen, to start for a result from an activity. */
    fun permissionIntent(context: Context): Intent =
        context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
}

/**
 * The screen as a track a call between two carries. The capture is at the display's own size, and it goes out at the size
 * the other side says it shows it, which the call's encoding works out: a smaller capture would put a ceiling on that.
 */
internal class CallScreenCapture(private val context: Context) {
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null

    /** The person stopped it from outside the app, from the system's own notification. */
    var onStopped: (() -> Unit)? = null

    var track: VideoTrack? = null
        private set

    var captureHeight = 0
        private set

    /** Starts from the system's answer. Throws where the system refuses it. */
    fun start(permission: Intent): VideoTrack {
        val media = CallMedia.get(context)
        val capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            override fun onStop() {
                onStopped?.invoke()
            }
        })
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(it)
        }
        val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val height = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
        val source = media.factory.createVideoSource(true)
        val helper = SurfaceTextureHelper.create("call-screen", media.eglBase.eglBaseContext)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(width, height, 30)
        val track = media.factory.createVideoTrack("call-screen", source)
        this.capturer = capturer
        this.source = source
        this.helper = helper
        this.track = track
        captureHeight = height
        return track
    }

    fun stop() {
        onStopped = null
        runCatching { capturer?.stopCapture() } // Already stopped, or the system took it back.
        capturer?.dispose()
        capturer = null
        helper?.dispose()
        helper = null
        source?.dispose()
        source = null
        track = null
        captureHeight = 0
    }
}

/**
 * The notice the system shows while a screen is shared, which a foreground service must have. The room client's service
 * draws one of its own only when it is handed none, with no icon, and the system ends a process for that.
 */
internal object ScreenShareNotice {
    const val ID = 7313
    private const val CHANNEL = "cloud.wordplus.realtime.calls.screen_share"

    fun build(context: Context): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Sharing your screen")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** What the room client shares with: the system's answer, this notice, and what the system's own stop does. */
    fun roomParams(context: Context, permission: Intent, onStop: () -> Unit) =
        ScreenCaptureParams(permission, ID, build(context), onStop)
}

/**
 * What a share on a call between two runs under. `getMediaProjection` throws unless a foreground service of the
 * `mediaProjection` type is already running, and the system lets one start only once the person has said yes in its
 * dialog (Android 14). Group calls and streams share through the room client, which has a service of its own.
 */
class ScreenShareService : Service() {
    internal companion object {
        private var started: ((Boolean) -> Unit)? = null

        /** Starts it, and says whether it is running as that type, once it is. */
        suspend fun start(context: Context): Boolean = suspendCancellableCoroutine { continuation ->
            started = { running ->
                started = null
                if (continuation.isActive) continuation.resume(running)
            }
            // Not startForegroundService: the app is on screen, just back from the system's dialog, and a service started
            // that way and then refused its type would take the process down.
            val asked = runCatching { context.startService(Intent(context, ScreenShareService::class.java)) }
            if (asked.isFailure) {
                CallLog.write("the screen's service could not start: ${asked.exceptionOrNull()?.message}")
                started?.invoke(false)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ScreenShareNotice.ID, ScreenShareNotice.build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(ScreenShareNotice.ID, ScreenShareNotice.build(this))
            }
        }
        if (running.isFailure) {
            CallLog.write("the screen's service was refused: ${running.exceptionOrNull()?.message}")
            stopSelf()
        }
        started?.invoke(running.isSuccess)
        return START_NOT_STICKY
    }
}
