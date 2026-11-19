package cloud.wordplus.realtime.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import cloud.wordplus.realtime.calls.CallsScreenSupport
import cloud.wordplus.realtime.ui.calls.CallsMicProbe
import cloud.wordplus.realtime.ui.calls.CallsPermissions
import cloud.wordplus.realtime.ui.calls.CallsTheme

/**
 * The phone's microphones as a call hears them: the built-in one, a wired headset's and a Bluetooth headset's. On Android
 * the microphone follows the call's audio route, so choosing one is choosing the route that carries it.
 */
internal object CallMicrophones {
    fun list(context: Context): List<Pair<String, String>> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        val found = LinkedHashMap<String, String>()
        found["builtin"] = CallsTheme.string(context, "microphone_builtin", "Phone microphone")
        for (device in audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET ->
                    found.putIfAbsent("wired", CallsTheme.string(context, "wired", "Headphones"))
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> found.putIfAbsent(
                    "bluetooth",
                    device.productName?.toString()?.takeIf { it.isNotBlank() } ?: CallsTheme.string(context, "bluetooth", "Bluetooth"),
                )
            }
        }
        return found.toList()
    }

    fun current(context: Context): String = chosen(list(context).map { it.first }, CallsScreenSupport.audioRoute(context))

    /** The one the route carries, the built-in one where the route has none of its own. */
    internal fun chosen(ids: List<String>, route: String): String = when (route) {
        "bluetooth" -> if ("bluetooth" in ids) "bluetooth" else "builtin"
        "wired" -> if ("wired" in ids) "wired" else "builtin"
        else -> "builtin"
    }

    fun choose(context: Context, id: String, video: Boolean) {
        val route = routeFor(id, CallsScreenSupport.audioRoute(context), video) ?: return
        CallsScreenSupport.setAudioRoute(context, route)
    }

    /** A headset is its own route. The phone's own microphone is off a headset, to the speaker on a video call and the earpiece on a voice one. */
    internal fun routeFor(id: String, route: String, video: Boolean): String? = when {
        id == "bluetooth" || id == "wired" -> id
        route == "bluetooth" || route == "wired" -> if (video) "speaker" else "earpiece"
        else -> null
    }
}

/**
 * The microphone for a device check, only while the call does not send it. In a call between two a muted microphone is a
 * disabled track whose audio device may still be recording: then its level is read and nothing more is opened. Otherwise
 * (a room unpublishes a muted microphone, and its audio device stops) the microphone opened for the check alone, and closed
 * before the call sends it.
 */
internal class CallMicrophoneCheck {
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    fun start(context: Context, recordsAlready: Boolean, level: (Float) -> Unit): Boolean {
        stop()
        if (!CallsPermissions.microphoneGranted(context)) return false
        if (recordsAlready && CallsScreenSupport.microphoneRecorded(context) != null) {
            val reading = object : Runnable {
                override fun run() {
                    level(CallsScreenSupport.microphoneRecorded(context) ?: 0f)
                    handler.postDelayed(this, 50L)
                }
            }
            poll = reading
            handler.post(reading)
            return true
        }
        return CallsMicProbe.start(context, level)
    }

    fun stop() {
        poll?.let { handler.removeCallbacks(it) }
        poll = null
        CallsMicProbe.stop()
    }
}
