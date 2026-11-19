package cloud.wordplus.realtime.ui.calls

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The permissions a call needs, asked for by the call screen.
 *
 * The web view used to ask on the app's behalf: `getUserMedia` triggers
 * Capacitor's own prompt. The media is captured natively now, so nothing asks
 * unless this does — and a call that opens a microphone it was never granted
 * is a call with no sound and no explanation.
 */
object CallsPermissions {
    const val REQUEST_CODE = 7311

    fun microphoneGranted(context: Context): Boolean = granted(context, Manifest.permission.RECORD_AUDIO)

    fun cameraGranted(context: Context): Boolean = granted(context, Manifest.permission.CAMERA)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Everything this call still needs. Asked in one prompt, because two in a
     * row on a ringing phone is worse than one.
     */
    fun missing(context: Context, video: Boolean): Array<String> {
        val wanted = mutableListOf<String>()

        if (!microphoneGranted(context)) wanted.add(Manifest.permission.RECORD_AUDIO)
        if (video && !cameraGranted(context)) wanted.add(Manifest.permission.CAMERA)

        // Answering from a Bluetooth headset needs this on 31 and later, and
        // it is refused silently rather than loudly when it is not there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return wanted.toTypedArray()
    }

    /**
     * What the prompt was last put up for: a call, and whether it wanted the
     * camera. The screen asks with every state it is sent, so a permission
     * refused once — the Bluetooth one, say — put the prompt back up every
     * few seconds for the rest of the call.
     */
    private var askedFor = ""

    /**
     * Once per call, unless [force]d by the person pressing a control that
     * needs what is missing — the camera button on an audio call, the
     * microphone button while the microphone is not allowed.
     *
     * Per call by the engine's key for it, not by the call's id: a group
     * call's id is its room's key and the same for every call held in that
     * conversation, so one prompt dismissed in a group call was the last
     * this process ever put up for that conversation.
     */
    fun request(activity: Activity, video: Boolean, force: Boolean = false): Boolean {
        val wanted = missing(activity, video)

        if (wanted.isEmpty()) return true

        val asking = CallsEngine.get(activity).currentCallKey() + ":" + video

        if (!force && asking == askedFor) return false

        askedFor = asking

        activity.requestPermissions(wanted, REQUEST_CODE)

        return false
    }
}
