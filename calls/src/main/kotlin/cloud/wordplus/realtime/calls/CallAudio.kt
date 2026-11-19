package cloud.wordplus.realtime.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * The phone's audio, put into call mode and taken back out.
 *
 * Telecom does this for a call it has registered, and where it has, this
 * changes nothing it did not already do. It exists for the two cases Telecom
 * does not cover: API 24 and 25, where there is no Telecom to register with,
 * and a device that refused the registration. Without it the call plays at
 * media volume through the speaker while the person holds the phone to their
 * ear, and the microphone is captured with none of the call-mode processing.
 */
internal object CallAudio {
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var held = false

    fun startCall(context: Context, video: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        val starting = !held

        if (starting) {
            previousMode = audio.mode
            held = true
        }

        audio.mode = AudioManager.MODE_IN_COMMUNICATION

        // A video call is held away from the face, so it starts on the
        // speaker; an audio call starts at the earpiece and the person moves
        // it if they want to. Where it starts, only: a rebuilt connection and
        // a hand-off to the room come through here too, and they are the same
        // call — putting the sound back on the speaker there undid the
        // earpiece the person had chosen, with the button still saying so.
        if (starting) {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = video
        }

        requestFocus(audio)
    }

    fun setSpeaker(context: Context, on: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = on
    }

    fun currentRoute(context: Context): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: return "earpiece"

        @Suppress("DEPRECATION")
        return when {
            audio.isBluetoothScoOn || audio.isBluetoothA2dpOn -> "bluetooth"
            audio.isWiredHeadsetOn -> "wired"
            audio.isSpeakerphoneOn -> "speaker"
            else -> "earpiece"
        }
    }

    /**
     * Where the sound can go, as the phone reports it now.
     *
     * The earpiece and the speaker are always there; a headset or a car is
     * there when it is plugged in or paired. Returned as `route to label` so
     * the screen can list them without knowing anything about Android's
     * device types, and so a device that appears mid-call — someone putting
     * their earbuds in — simply shows up the next time the list is asked for.
     */
    fun outputs(context: Context): List<Pair<String, String>> {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return listOf("earpiece" to "earpiece", "speaker" to "speaker")

        val found = linkedMapOf<String, String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (device in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val route = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "wired"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
                    else -> continue
                }

                // The product name is what the person recognises — "Pixel
                // Buds" rather than "bluetooth" — but only for the things
                // that have one.
                val label = if (route == "bluetooth" || route == "wired") {
                    device.productName?.toString()?.takeIf { it.isNotBlank() } ?: route
                } else {
                    route
                }

                found.putIfAbsent(route, label)
            }
        }

        // A phone with no earpiece is a tablet, and one that reported nothing
        // at all is a device this API did not answer for; either way the
        // speaker is always somewhere to send it.
        found.putIfAbsent("speaker", "speaker")

        return found.map { it.key to it.value }
    }

    /**
     * Send the call to one of the outputs [outputs] listed.
     *
     * Bluetooth needs its own switch — the phone will not route a call to a
     * headset just because one is paired — and turning it off is what makes
     * the earpiece and the speaker reachable again.
     */
    fun setRoute(context: Context, route: String) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        @Suppress("DEPRECATION")
        when (route) {
            "bluetooth" -> {
                audio.isSpeakerphoneOn = false
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
            }

            "speaker" -> {
                audio.stopBluetoothSco()
                audio.isBluetoothScoOn = false
                audio.isSpeakerphoneOn = true
            }

            else -> {
                audio.stopBluetoothSco()
                audio.isBluetoothScoOn = false
                audio.isSpeakerphoneOn = false
            }
        }
    }

    fun endCall(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        @Suppress("DEPRECATION")
        audio.stopBluetoothSco()

        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false

        // Back to whatever the phone was doing before the call, not to a
        // guess: leaving it in communication mode makes every other app on
        // the phone sound wrong until it is restarted.
        if (held) {
            audio.mode = previousMode
            held = false
        }

        abandonFocus(audio)
    }

    private fun requestFocus(audio: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()

            focusRequest = request
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonFocus(audio: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }
}
