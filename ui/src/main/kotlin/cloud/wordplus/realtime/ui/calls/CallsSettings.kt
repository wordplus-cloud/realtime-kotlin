package cloud.wordplus.realtime.ui.calls

import android.Manifest
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The dock's settings button, in every call: Device Settings, as the web draws it (realtime-ui's device-panel.tsx), on
 * the call rather than over it ([CallsDevicePanel]).
 */
object CallsSettings {
    fun show(stage: CallsStage) {
        stage.showDevices(null)
    }

    /**
     * The same shape the web's `filesize` gives, so the two screens read alike:
     * powers of 1024, one decimal above a kilobyte.
     *
     * `Locale.ROOT` because "the same shape" has to mean the same separator
     * too. `String.format` with no locale takes the device's, and on a phone
     * set to ru-UA this read `204,3 KB` beside the browser's `204.3 KB` and
     * the other phone's — iOS's `String(format:)` is unlocalised unless it is
     * handed a locale, so Android was the only one of the three that moved.
     */
    internal fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0

        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }

        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}

/**
 * The user's device settings over the call: the camera (in a call between two, and a group video call), the
 * microphone and the speaker, each tried out on its own before it goes live and only for the user. The camera's Preview
 * opens a picture in its row, the microphone's Test a level, the speaker's Test plays three notes. Each then says in
 * words whether the others see or hear it, and a field opens its devices in the system's own menu. Going live is the
 * dock's camera and microphone alone: nothing here sends anything, and nothing opens a device before a press. `start`, the press that opened it, runs the
 * camera's preview and the microphone's test from the start (`both`), or the microphone's alone (`microphone`).
 *
 * It lies over the whole stage: a press outside the card closes it, as Done does, and the tests stop with it.
 */
class CallsDevicePanel(context: Context, private val stage: CallsStage, start: String?) : FrameLayout(context) {
    private val engine = CallsEngine.get(context)
    private val density = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    private fun dp(value: Int): Int = (value * density).toInt()
    private fun dpf(value: Float): Float = value * density

    private val frame = FrameLayout(context)
    private val scroll = ScrollView(context)
    private val stack = LinearLayout(context)

    private var testCamera = start == "both"
    private var testMicrophone = start == "both" || start == "microphone"
    private var playing = false

    /** A device's menu while it is open: the panel is not drawn again under it, as its anchor is one of the views a draw replaces. */
    internal var menu: PopupMenu? = null
        private set

    private var closed = false

    /** Asked for here, so a refusal is said as one rather than as a device not tried yet. */
    private var askedCamera = false
    private var askedMicrophone = false

    private var cameraTrack: VideoTrack? = null
    private var cameraFront = true
    private var microphoneOpen = false

    /** The level as it is drawn: it rises at once and falls away over a few readings, `max(heard, shown * 0.8)`. */
    private var shown = 0f

    private val meter = CallsLevelMeter(context)
    private var preview: TextureViewRenderer? = null
    private var drawn = ""

    /** Called once the panel has gone, whichever way it went. */
    var onClose: (() -> Unit)? = null

    /** The call's own level while it sends the microphone, twenty times a second, and the rest once a second. */
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return

            if (microphoneOn()) {
                engine.microphoneLevel()?.let { heard(it) }
            }

            if (ticks++ % 20 == 0) refresh()

            main.postDelayed(this, 50L)
        }
    }

    init {
        // Clear behind it, as the web's: the call is still the thing to look at. A press off the card is the cancel.
        isClickable = true
        setOnClickListener { close() }

        frame.background = GradientDrawable().apply {
            cornerRadius = dpf(16f)
            setColor(Color.rgb(22, 24, 30))
            setStroke(max(1, dp(1)), Color.argb(23, 255, 255, 255))
        }
        frame.clipToOutline = true
        frame.elevation = dpf(18f)
        // The card's own presses stay on the card.
        frame.isClickable = true

        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.isVerticalScrollBarEnabled = false
        stack.orientation = LinearLayout.VERTICAL
        stack.setPadding(dp(8), dp(8), dp(8), dp(8))

        scroll.addView(stack, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(frame, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        ViewCompat.setAccessibilityPaneTitle(frame, string("device_settings", "Device Settings"))

        // Both from the start: one prompt for the two where neither is allowed yet.
        if (start != null) askFor(camera = testCamera && video(), microphone = testMicrophone)
    }

    /** Back closes it, as Done does, and leaves the call as it is. */
    private val back = CallsBack(this) { close() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        back.attach()
        refresh()
        main.post(tick)

        frame.alpha = 0f
        frame.translationY = dpf(4f)
        frame.animate().alpha(1f).translationY(0f).setDuration(140).start()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = back.handles(event) || super.dispatchKeyEvent(event)

    override fun onDetachedFromWindow() {
        back.detach()
        stopTests()
        main.removeCallbacks(tick)
        menu?.setOnDismissListener(null)
        menu?.dismiss()
        menu = null
        preview?.release()
        preview = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val bottom = stage.devicesClearance()
        val top = stage.topClearance() + dp(14)

        // 340 wide, never closer than 14 to either edge; no taller than the stage above the dock, scrolling past that.
        // Set in place: assigning the params would ask for another layout from inside this one.
        val shownWidth = min(dp(340), width - dp(28))

        (frame.layoutParams as LayoutParams).let {
            it.width = shownWidth
            it.bottomMargin = bottom
        }

        stack.measure(MeasureSpec.makeMeasureSpec(shownWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        val room = max(dp(160), height - bottom - top)

        scroll.layoutParams.height = if (stack.measuredHeight > room) room else ViewGroup.LayoutParams.WRAP_CONTENT

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun close() {
        if (closed) return

        closed = true
        stopTests()
        main.removeCallbacks(tick)

        (parent as? ViewGroup)?.removeView(this)
        onClose?.invoke()
    }

    // What the call says now

    private fun local() = stage.lastState.optJSONObject("local")

    private fun video(): Boolean = !stage.groupCall || stage.videoCall

    private fun cameraOn(): Boolean = local()?.optBoolean("camera", false) == true

    private fun microphoneOn(): Boolean = local()?.optBoolean("muted", true) == false && stage.mayPublish && !stage.micHeld

    private fun granted(permission: String): Boolean = when (permission) {
        Manifest.permission.CAMERA -> CallsPermissions.cameraGranted(context)
        else -> CallsPermissions.microphoneGranted(context)
    }

    /**
     * Refused: not allowed after it was asked for here and the system's prompt has gone (this window has its focus
     * back), or refused before, which the system says by offering a reason to ask again.
     */
    private fun blocked(permission: String, asked: Boolean): Boolean {
        if (granted(permission)) return false

        val activity = stage.hostActivity

        return (asked && activity.hasWindowFocus()) || activity.shouldShowRequestPermissionRationale(permission)
    }

    private fun askFor(camera: Boolean, microphone: Boolean) {
        val missingCamera = camera && !granted(Manifest.permission.CAMERA)
        val missingMicrophone = microphone && !granted(Manifest.permission.RECORD_AUDIO)

        if (!missingCamera && !missingMicrophone) return

        if (missingCamera) askedCamera = true
        if (missingMicrophone) askedMicrophone = true

        val wanted = mutableListOf<String>()

        if (missingMicrophone) wanted.add(Manifest.permission.RECORD_AUDIO)
        if (missingCamera) wanted.add(Manifest.permission.CAMERA)

        stage.hostActivity.requestPermissions(wanted.toTypedArray(), CallsPermissions.REQUEST_CODE)
    }

    /** Everything the panel shows, from the call as it is now: drawn again only where something changed. */
    fun refresh() {
        if (closed) return

        val cameraOn = cameraOn()
        val microphoneOn = microphoneOn()

        // Gone live from the dock: the test is over, and does not open the device again once it is turned off.
        if (cameraOn) testCamera = false
        if (microphoneOn) testMicrophone = false

        val cameraBlocked = blocked(Manifest.permission.CAMERA, askedCamera)
        val microphoneBlocked = blocked(Manifest.permission.RECORD_AUDIO, askedMicrophone)
        val cameraTestable = video() && !cameraOn && !cameraBlocked && engine.cameraTestAvailable()
        val microphoneTestable = !microphoneOn && !microphoneBlocked && !stage.micHeld && engine.microphoneTestAvailable()

        val front = local()?.optBoolean("front", true) ?: true

        // A test runs while it is wanted, allowed and possible, and the camera's faces the way the call's would.
        if (testCamera && cameraTestable && granted(Manifest.permission.CAMERA)) {
            if (cameraTrack == null || cameraFront != front) startCamera(front)
        } else {
            stopCamera()
        }

        if (testMicrophone && microphoneTestable && granted(Manifest.permission.RECORD_AUDIO)) {
            if (!microphoneOpen) startMicrophone()
        } else {
            stopMicrophone()
        }

        val traffic = engine.trafficBytes()
        val said = listOf(
            cameraOn, microphoneOn, cameraBlocked, microphoneBlocked, cameraTestable, microphoneTestable, testCamera,
            testMicrophone, cameraTrack != null, microphoneOpen, front, playing, stage.micHeld, stage.mayPublish,
            engine.audioRoute(), engine.audioRoutes(), engine.microphone(), engine.microphones(), traffic,
            stage.lastState.optString("peerName"), engine.microphoneLevel() != null,
        ).joinToString("|")

        if (said == drawn || menu != null) return

        drawn = said
        draw(cameraOn, microphoneOn, cameraBlocked, microphoneBlocked, cameraTestable, microphoneTestable, front, traffic)
    }

    private fun draw(
        cameraOn: Boolean,
        microphoneOn: Boolean,
        cameraBlocked: Boolean,
        microphoneBlocked: Boolean,
        cameraTestable: Boolean,
        microphoneTestable: Boolean,
        front: Boolean,
        traffic: Pair<Long, Long>?,
    ) {
        stack.removeAllViews()
        (meter.parent as? ViewGroup)?.removeView(meter)

        stack.addView(TextView(context).apply {
            text = string("device_settings", "Device Settings")
            textSize = 14f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(4), dp(6), dp(8))
        })

        val peer = if (stage.groupCall) null else stage.lastState.optString("peerName").ifEmpty { null }

        fun toPeer(key: String, fallback: String): String = string(key, fallback).replace("%s", peer ?: "").replace("%1\$s", peer ?: "")

        if (video()) {
            val cameraState = if (cameraOn) {
                State(true, if (peer != null) toPeer("person_sees_you", "%s sees you") else string("everyone_sees_you", "Everyone sees you"))
            } else {
                State(false, string("nobody_sees_you", "Nobody sees you"))
            }

            val cameraPreview = if (testCamera && cameraTestable && cameraTrack != null) previewBox(front) else null

            stack.addView(row(
                icon = if (cameraOn) CallsIcons.video else CallsIcons.videoOff,
                label = string("device_camera", "Camera"),
                devices = listOf("front" to string("front_camera", "Front Camera"), "back" to string("back_camera", "Back Camera")),
                selected = if (front) "front" else "back",
                choose = { engine.setCameraFacing(it == "front") },
                live = cameraOn,
                status = if (cameraBlocked) note(Tone.WARNING, CallsIcons.alert, string("camera_denied", "No permission to access the camera. Please allow it in the app settings.")) else null,
                preview = cameraPreview,
                state = cameraState,
                action = if (cameraTestable) testButton(testCamera, string("device_preview", "Preview")) {
                    testCamera = !testCamera
                    if (testCamera) askFor(camera = true, microphone = false)
                    refresh()
                } else null,
            ))
        }

        val microphoneStatus: View?
        val microphoneState: State

        if (microphoneOn) {
            microphoneStatus = if (engine.microphoneLevel() != null) meterLine(null) else null
            microphoneState = State(true, if (peer != null) toPeer("person_hears_you", "%s hears you") else string("everyone_hears_you", "Everyone hears you"))
        } else {
            microphoneStatus = when {
                microphoneBlocked -> note(Tone.WARNING, CallsIcons.alert, string("microphone_denied", "No permission to access the microphone. Please allow it in the app settings."))
                microphoneOpen -> meterLine(string("speak_to_test", "Speak to test. Nobody hears it."))
                stage.micHeld && stage.mayPublish -> note(Tone.OFF, CallsIcons.silenced, string("moderator_muted_you", "A moderator muted your microphone."))
                stage.mutedTalking -> note(Tone.TALKING, CallsIcons.micOff, string("you_are_muted", "You are muted"))
                else -> null
            }
            microphoneState = State(false, string("nobody_hears_you", "Nobody hears you"))
        }

        val microphones = engine.microphones()

        stack.addView(row(
            icon = if (microphoneOn) CallsIcons.mic else CallsIcons.micOff,
            label = string("device_microphone", "Microphone"),
            devices = microphones,
            selected = engine.microphone(),
            choose = { engine.setMicrophone(it) },
            live = microphoneOn,
            status = microphoneStatus,
            preview = null,
            state = microphoneState,
            action = if (microphoneTestable) testButton(testMicrophone, null) {
                testMicrophone = !testMicrophone
                if (testMicrophone) askFor(camera = false, microphone = true)
                refresh()
            } else null,
        ))

        // A route's label is its key where the phone has no name for it (a headset's own name stays as it is), said
        // in the user's words, or in English where there are none.
        val routes = engine.audioRoutes().map { (route, label) ->
            route to CallsTheme.string(context, label, ROUTE_NAMES[label] ?: label)
        }

        stack.addView(row(
            icon = CallsIcons.speaker,
            label = string("speaker", "Speaker"),
            devices = routes,
            selected = engine.audioRoute(),
            choose = { engine.setRoute(it) },
            live = false,
            status = null,
            preview = null,
            state = null,
            action = testButton(playing, null) {
                if (playing) return@testButton

                playing = true
                refresh()

                CallsTone.devices {
                    playing = false
                    refresh()
                }
            },
        ))

        // A call between two: how much it has received and sent, under the devices.
        if (traffic != null) {
            stack.addView(TextView(context).apply {
                text = string("downloaded", "Downloaded") + ": " + CallsSettings.size(traffic.first) + "     " +
                    string("uploaded", "Uploaded") + ": " + CallsSettings.size(traffic.second)
                textSize = 11.5f
                setTextColor(Color.argb(140, 255, 255, 255))
                setPadding(dp(8), dp(6), dp(8), dp(2))
                contentDescription = string("statistics", "Statistics") + ". " + text
            })
        }

        stack.addView(TextView(context).apply {
            text = string("device_done", "Done")
            textSize = 13f
            typeface = CallsTheme.SEMIBOLD
            gravity = Gravity.CENTER
            setTextColor(Color.argb(242, 255, 255, 255))
            background = GradientDrawable().apply {
                cornerRadius = dpf(19f)
                setColor(Color.argb(26, 255, 255, 255))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(6) })

        requestLayout()
    }

    // The tests

    private fun startCamera(front: Boolean) {
        stopCamera()

        val track = engine.startCameraTest(front) ?: run {
            testCamera = false
            return
        }

        val renderer = preview ?: TextureViewRenderer(context).also {
            it.init(CallsMedia.get(context).eglBase.eglBaseContext, null)
            it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            preview = it
        }

        // Not mirrored, the front camera neither, as the web's preview and the call's own tile: the user sees what
        // the others would.
        renderer.setMirror(false)
        track.addSink(renderer)

        cameraTrack = track
        cameraFront = front
    }

    private fun stopCamera() {
        val track = cameraTrack ?: return

        cameraTrack = null

        preview?.let { renderer ->
            try {
                track.removeSink(renderer)
            } catch (e: Exception) {
                // The track went with the call.
            }
            renderer.clearImage()
        }

        engine.stopCameraTest()
    }

    private fun startMicrophone() {
        shown = 0f
        meter.level = 0f

        microphoneOpen = engine.startMicrophoneTest { heard -> main.post { if (microphoneOpen) heard(heard) } }

        if (!microphoneOpen) testMicrophone = false
    }

    private fun stopMicrophone() {
        if (!microphoneOpen) return

        microphoneOpen = false
        engine.stopMicrophoneTest()
        shown = 0f
        meter.level = 0f
    }

    private fun heard(level: Float) {
        shown = max(level, shown * 0.8f)
        meter.level = if (shown < 0.01f) 0f else shown
    }

    private fun stopTests() {
        stopCamera()
        stopMicrophone()
    }

    // The rows

    private class State(val live: Boolean, val text: String)

    private enum class Tone { WARNING, OFF, TALKING }

    /**
     * One device: its glyph and name with its test on the end of that line, under them a field with the device in use,
     * a line under it, the camera's preview while it is tested, and the line that says whether the others see or hear it.
     */
    private fun row(
        icon: String,
        label: String,
        devices: List<Pair<String, String>>,
        selected: String,
        choose: (String) -> Unit,
        live: Boolean,
        status: View?,
        preview: View?,
        state: State?,
        action: View?,
    ): View {
        val accent = CallsTheme.accent(context)

        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // The height the test gives the line, so the row does not move as the test goes once the device is live.
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(38)
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }

        head.addView(CallsIconView(context).apply {
            set(icon)
            glyphColor = if (live) accent else Color.argb(153, 255, 255, 255)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)))

        head.addView(TextView(context).apply {
            text = label
            textSize = 13f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })

        if (action != null) {
            head.addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginStart = dp(8) })
        }

        holder.addView(head)

        val current = devices.firstOrNull { it.first == selected } ?: devices.firstOrNull()

        if (current != null) holder.addView(field(label, devices, current, choose))

        val under = {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                bottomMargin = dp(8)
            }
        }

        if (status != null) holder.addView(status, under())

        if (preview != null) {
            holder.addView(preview, under().apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
        }

        if (state != null) holder.addView(stateLine(state))

        return holder
    }

    /**
     * The device in use, `.wplus-call-device-current`: a field the width of the row with its arrow on the end, which
     * opens the others. A line of quiet text here read as a caption under the name, and people did not find where to
     * choose a device. With nothing else to choose it is plain text, level with the name over it. 40 tall where the
     * web's is 32, for a finger.
     */
    private fun field(label: String, devices: List<Pair<String, String>>, current: Pair<String, String>, choose: (String) -> Unit): View {
        val opens = devices.size > 1

        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "$label: ${current.second}"
        }

        line.addView(TextView(context).apply {
            text = current.second
            textSize = if (opens) 13f else 12f
            setTextColor(if (opens) Color.argb(235, 255, 255, 255) else Color.argb(153, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (!opens) {
            line.setPaddingRelative(dp(32), 0, dp(8), dp(8))
            return line
        }

        line.setPaddingRelative(dp(10), 0, dp(8), 0)
        line.background = GradientDrawable().apply {
            cornerRadius = dpf(8f)
            setColor(Color.argb(15, 255, 255, 255))
            setStroke(max(1, dp(1)), Color.argb(41, 255, 255, 255))
        }
        line.isClickable = true
        line.isFocusable = true
        line.setOnClickListener { openMenu(line, devices, current.first, choose) }

        line.addView(CallsIconView(context).apply {
            set(CallsIcons.chevronDown)
            glyphColor = Color.argb(179, 255, 255, 255)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(6) })

        line.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            bottomMargin = dp(8)
        }

        return line
    }

    /** The system's own menu of the devices, in the settings' own dark whatever the phone is set to, the one in use marked. */
    private fun openMenu(anchor: View, devices: List<Pair<String, String>>, selected: String, choose: (String) -> Unit) {
        val popup = PopupMenu(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault), anchor)

        devices.forEachIndexed { index, (_, name) -> popup.menu.add(0, index, index, name) }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.getItem(devices.indexOfFirst { it.first == selected }.coerceAtLeast(0)).isChecked = true

        popup.setOnMenuItemClickListener { item ->
            val id = devices[item.itemId].first

            if (id != selected) choose(id)

            true
        }

        popup.setOnDismissListener {
            menu = null
            refresh()
        }

        menu = popup
        popup.show()
    }

    /** A device's Test, or the camera's Preview, on the end of its row, which turns into Stop while it runs. */
    private fun testButton(running: Boolean, label: String?, toggle: () -> Unit): View {
        val accent = CallsTheme.accent(context)
        val ink = if (running) accent else Color.argb(235, 255, 255, 255)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(11), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpf(14f)
                setColor(if (running) CallsTheme.withAlpha(accent, 64) else Color.argb(26, 255, 255, 255))
            }
            isClickable = true
            isFocusable = true

            addView(CallsIconView(context).apply {
                set(if (running) CallsIcons.close else CallsIcons.play)
                glyphColor = ink
            }, LinearLayout.LayoutParams(dp(12), dp(12)))

            val words = if (running) string("device_stop", "Stop") else label ?: string("device_test", "Test")

            addView(TextView(context).apply {
                text = words
                textSize = 12f
                typeface = CallsTheme.MEDIUM
                setTextColor(ink)
                maxLines = 1
                includeFontPadding = false
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })

            contentDescription = words

            setOnClickListener { toggle() }
        }
    }

    /** Where the level would be: a device the phone refuses, a microphone a moderator holds, or talking while muted. */
    private fun note(tone: Tone, icon: String, words: String): View {
        val warning = CallsTheme.warning(context)
        val recording = CallsTheme.recording(context)

        val ground = when (tone) {
            Tone.WARNING -> CallsTheme.withAlpha(warning, 36)
            Tone.OFF -> CallsTheme.withAlpha(recording, 41)
            Tone.TALKING -> CallsTheme.withAlpha(warning, 56)
        }
        val ink = when (tone) {
            Tone.WARNING -> Color.argb(230, 255, 255, 255)
            Tone.OFF -> Color.rgb(255, 170, 160)
            Tone.TALKING -> Color.WHITE
        }
        val glyph = when (tone) {
            Tone.OFF -> Color.rgb(255, 170, 160)
            else -> warning
        }

        val shape = GradientDrawable().apply {
            cornerRadius = dpf(6f)
            setColor(ground)
        }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = shape

            addView(CallsIconView(context).apply {
                set(icon)
                glyphColor = glyph
            }, LinearLayout.LayoutParams(dp(13), dp(13)).apply { topMargin = dp(2) })

            addView(TextView(context).apply {
                text = words
                textSize = 11.5f
                setTextColor(ink)
                if (tone == Tone.TALKING) typeface = CallsTheme.SEMIBOLD
                setLineSpacing(0f, 1.1f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        }

        // Talking while muted pulses, as the web's, where the system lets things move.
        if (tone == Tone.TALKING && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            android.animation.ValueAnimator.ofArgb(ground, CallsTheme.withAlpha(warning, 97), ground).apply {
                duration = 1200
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { shape.setColor(it.animatedValue as Int) }
                view.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = start()
                    override fun onViewDetachedFromWindow(v: View) = cancel()
                })
            }
        }

        return view
    }

    /**
     * The microphone's level, and for the private test that nobody hears it: under the meter, where the web's line
     * wraps it on a card this narrow, rather than squeezed into a column beside it.
     */
    private fun meterLine(words: String?): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        line.addView(meter, LinearLayout.LayoutParams(meter.wide(), dp(18)))

        if (words != null) {
            line.addView(TextView(context).apply {
                text = words
                textSize = 11.5f
                setTextColor(Color.argb(153, 255, 255, 255))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        return line
    }

    /** Whether the others see or hear it: a hollow dot while nobody does, a red LIVE once they do. */
    private fun stateLine(state: State): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(8), 0, dp(8), dp(8))
        }

        if (state.live) {
            line.addView(TextView(context).apply {
                text = string("device_live", "Live").uppercase()
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(5), 0, dp(5), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dpf(4f)
                    setColor(CallsTheme.recording(context))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(16)))
        } else {
            line.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(max(1, dpf(1.5f).toInt()), Color.argb(128, 255, 255, 255))
                }
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
        }

        line.addView(TextView(context).apply {
            text = state.text
            textSize = 12f
            setTextColor(if (state.live) Color.argb(242, 255, 255, 255) else Color.argb(179, 255, 255, 255))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })

        return line
    }

    /** The camera's test, 16:9 with rounded corners under the row, and over it that it is the user's alone. */
    private fun previewBox(front: Boolean): View {
        val box = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)

                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY)
                )
            }
        }

        box.background = GradientDrawable().apply {
            cornerRadius = dpf(10f)
            setColor(Color.BLACK)
        }
        box.clipToOutline = true

        preview?.let { renderer ->
            (renderer.parent as? ViewGroup)?.removeView(renderer)
            box.addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        val badge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpf(11f)
                setColor(Color.argb(153, 0, 0, 0))
            }

            addView(CallsIconView(context).apply {
                set(CallsIcons.padlock)
                glyphColor = Color.argb(235, 255, 255, 255)
            }, LinearLayout.LayoutParams(dp(12), dp(12)))

            addView(TextView(context).apply {
                text = string("only_you_see_this", "Only you see this")
                textSize = 11f
                typeface = CallsTheme.SEMIBOLD
                setTextColor(Color.argb(235, 255, 255, 255))
                maxLines = 1
                includeFontPadding = false
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        }

        box.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(22), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(8)
            marginStart = dp(8)
        })

        return box
    }

    private fun string(key: String, fallback: String): String = CallsTheme.string(context, key, fallback)

    companion object {
        /** The English for the route keys the engines hand over as labels. */
        private val ROUTE_NAMES = mapOf(
            "earpiece" to "Earpiece",
            "speaker" to "Speaker",
            "bluetooth" to "Bluetooth",
            "wired" to "Headphones",
        )
    }
}

/** The microphone's level as an input meter: eight segments lighting from the left as it hears more. */
class CallsLevelMeter(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(41, 255, 255, 255) }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CallsTheme.success(context) }
    private val box = RectF()

    var level: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Eight of 10 with 3 between them. */
    fun wide(): Int = ((STEPS.size * 10 + (STEPS.size - 1) * 3) * density).toInt()

    override fun onDraw(canvas: Canvas) {
        val width = 10 * density
        val height = 5 * density
        val gap = 3 * density
        val top = (this.height - height) / 2
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

        STEPS.forEachIndexed { index, step ->
            val start = index * (width + gap)
            val left = if (rtl) this.width - start - width else start

            box.set(left, top, left + width, top + height)
            canvas.drawRoundRect(box, 2 * density, 2 * density, if (level > step) on else off)
        }
    }

    companion object {
        /** Where each segment lights, from a quiet room (one) to someone speaking up (all eight): the web's. */
        val STEPS = floatArrayOf(0.25f, 0.34f, 0.44f, 0.53f, 0.62f, 0.71f, 0.81f, 0.9f)
    }
}

/**
 * A moderator's ask that this user turn on a device, over the dock in the join notice's place (the web's
 * TurnOnAskPrompt, [CallsAsks]): who asks for what, Turn On, Preview (Test Microphone for the microphone alone) and Not
 * Now; where the system refuses the device, how to allow it, Try Again and Open Settings. Drawn once for what it says:
 * the stage puts a new one in its place when that changes.
 */
class CallsAskCard(
    context: Context,
    val content: Content,
    primary: () -> Unit,
    secondary: () -> Unit,
    notNow: () -> Unit,
) : LinearLayout(context) {
    data class Content(val title: String, val steps: List<String>, val camera: Boolean, val primary: String, val secondary: String?)

    private val density = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    private val primaryButton: TextView

    /** Turn On waits while the system is asked and the device opens. */
    var busy = false
        set(value) {
            field = value
            primaryButton.isEnabled = !value
            primaryButton.alpha = if (value) 0.6f else 1f
        }

    init {
        orientation = HORIZONTAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(Color.argb(245, 22, 24, 30))
            setStroke(max(1, dp(1)), Color.argb(23, 255, 255, 255))
        }
        elevation = 12 * density
        isClickable = true
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE

        val accent = CallsTheme.accent(context)

        val icon = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CallsTheme.withAlpha(accent, 46))
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(CallsIconView(context).apply {
                set(if (content.camera) CallsIcons.video else CallsIcons.mic)
                glyphColor = accent
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }

        val body = LinearLayout(context).apply { orientation = VERTICAL }

        body.addView(TextView(context).apply {
            text = content.title
            textSize = 13.5f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
        })

        for (step in content.steps) {
            body.addView(TextView(context).apply {
                text = step
                textSize = 12f
                setTextColor(Color.argb(158, 255, 255, 255))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }

        fun pill(words: String, fill: Int, ink: Int, press: () -> Unit) = TextView(context).apply {
            text = words
            textSize = 13f
            typeface = CallsTheme.SEMIBOLD
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(ink)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(fill)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { press() }
        }

        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        primaryButton = pill(content.primary, accent, CallsTheme.color(context, "text-on-accent", Color.WHITE), primary)
        buttons.addView(primaryButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))

        content.secondary?.let { words ->
            buttons.addView(
                pill(words, Color.argb(31, 255, 255, 255), Color.WHITE, secondary),
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(8) }
            )
        }

        buttons.addView(
            pill(CallsTheme.string(context, "ask_not_now", "Not Now"), Color.TRANSPARENT, Color.argb(184, 255, 255, 255), notNow),
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(8) }
        )

        body.addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        addView(icon, LayoutParams(dp(32), dp(32)))
        addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }
}

/**
 * The prompt of a group call's join, over the dock (the web's JoinPrompt): the microphone, and on a video call the
 * camera, are off and the dock's buttons turn them on, with Preview, or Test Microphone on an audio call, which opens
 * Device Settings with it running, and a ×. Where the host keeps the user's choice ([never]), Do Not Show Again beside
 * Preview puts it away for good, × for this call alone. It covers nothing of the call and opens nothing by itself.
 */
class CallsJoinNotice(
    context: Context,
    video: Boolean,
    preview: () -> Unit,
    dismiss: () -> Unit,
    never: (() -> Unit)? = null
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    private val text = LinearLayout(context)
    private val button: TextView
    private val neverButton: TextView?
    private val actions = LinearLayout(context)
    private val cross = FrameLayout(context)
    private val top = LinearLayout(context)

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(8), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(Color.argb(245, 22, 24, 30))
            setStroke(max(1, dp(1)), Color.argb(23, 255, 255, 255))
        }
        elevation = 12 * density
        isClickable = true
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE

        val recording = CallsTheme.recording(context)
        val accent = CallsTheme.accent(context)

        val icon = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CallsTheme.withAlpha(recording, 46))
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(CallsIconView(context).apply {
                set(CallsIcons.micOff)
                glyphColor = recording
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }

        text.orientation = VERTICAL
        text.addView(TextView(context).apply {
            this.text = if (video) {
                CallsTheme.string(context, "join_both_off", "Microphone and camera are off")
            } else {
                CallsTheme.string(context, "join_microphone_off", "Microphone is off")
            }
            textSize = 13.5f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
        })
        text.addView(TextView(context).apply {
            this.text = if (video) {
                CallsTheme.string(context, "join_turn_both_on", "Turn them on with the buttons below when you are ready.")
            } else {
                CallsTheme.string(context, "join_turn_one_on", "Turn it on with the button below when you are ready.")
            }
            textSize = 12f
            setTextColor(Color.argb(158, 255, 255, 255))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })

        button = TextView(context).apply {
            this.text = if (video) {
                CallsTheme.string(context, "device_preview", "Preview")
            } else {
                CallsTheme.string(context, "test_microphone", "Test Microphone")
            }
            textSize = 13f
            typeface = CallsTheme.SEMIBOLD
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(CallsTheme.color(context, "text-on-accent", Color.WHITE))
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(accent)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { preview() }
        }

        neverButton = never?.let { press ->
            TextView(context).apply {
                this.text = CallsTheme.string(context, "join_never", "Do Not Show Again")
                textSize = 13f
                typeface = CallsTheme.SEMIBOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(Color.argb(184, 255, 255, 255))
                setPadding(dp(14), 0, dp(14), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(Color.TRANSPARENT)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { press() }
            }
        }

        actions.orientation = HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))
        neverButton?.let { actions.addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(6) }) }

        cross.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            isClickable = true
            isFocusable = true
            contentDescription = CallsTheme.string(context, "dismiss", "Dismiss")
            addView(CallsIconView(context).apply {
                set(CallsIcons.close)
                glyphColor = Color.argb(153, 255, 255, 255)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            setOnClickListener { dismiss() }
        }

        top.orientation = HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(icon, LayoutParams(dp(32), dp(32)))
        top.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })

        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        arrange(wide = true)
    }

    /**
     * One row where it fits; on a narrow call (under 520) the buttons under the words, level with them, and the × at
     * the top end, as the web's narrow layout has them.
     */
    private var wide: Boolean? = null

    private fun arrange(wide: Boolean) {
        if (this.wide == wide) return

        this.wide = wide

        (actions.parent as? ViewGroup)?.removeView(actions)
        (cross.parent as? ViewGroup)?.removeView(cross)

        if (wide) {
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
            top.addView(cross, LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
        } else {
            top.gravity = Gravity.TOP
            top.addView(cross, LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
            addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(44)
                topMargin = dp(8)
            })
        }
    }

    /** The buttons one under the other where the row under the words cannot hold both, as the web's wrap. */
    private fun fit(width: Int) {
        val never = neverButton ?: return
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        button.measure(free, free)
        never.measure(free, free)

        val room = minOf(dp(560), width - dp(24)) - paddingLeft - paddingRight - dp(44)
        val stacked = wide == false && button.measuredWidth + dp(6) + never.measuredWidth > room
        val orientation = if (stacked) VERTICAL else HORIZONTAL

        if (actions.orientation == orientation) return

        actions.orientation = orientation
        never.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply {
            if (stacked) topMargin = dp(4) else marginStart = dp(6)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = (parent as? View)?.width ?: 0
        val width = if (parentWidth > 0) parentWidth else resources.displayMetrics.widthPixels

        arrange(width / density >= 520)
        fit(width)

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * Notes played on the call's own audio route: a voice-communication stream follows the route the call is on (the
 * earpiece, the speaker, a headset) rather than taking the media one, so it neither fights the call nor goes somewhere
 * else.
 */
object CallsTone {
    private const val RATE = 24_000

    /** Device Settings' speaker test: three rising notes, about a second. */
    fun devices(done: () -> Unit) {
        play(listOf(523.25, 659.25, 783.99), spacing = 0.22, length = 0.5, peak = 0.18, done = done)
    }

    /** A raised hand, for a moderator: two soft rising notes. */
    fun hand() {
        play(listOf(660.0, 880.0), spacing = 0.14, length = 0.3, peak = 0.08, done = {})
    }

    private fun play(notes: List<Double>, spacing: Double, length: Double, peak: Double, done: () -> Unit) {
        val main = Handler(Looper.getMainLooper())

        Thread {
            var track: AudioTrack? = null

            try {
                val total = ((spacing * (notes.size - 1) + length + 0.05) * RATE).toInt()
                val mixed = DoubleArray(total)

                notes.forEachIndexed { index, frequency ->
                    val start = (index * spacing * RATE).toInt()
                    val count = (length * RATE).toInt()

                    for (i in 0 until count) {
                        val t = i.toDouble() / RATE
                        // A 20 ms rise to the peak, then an exponential fall to near nothing at the note's end.
                        val envelope = if (t < 0.02) peak * (t / 0.02) else peak * exp(-(t - 0.02) / ((length - 0.02) / 7.0))

                        if (start + i < total) mixed[start + i] += envelope * sin(2 * PI * frequency * t)
                    }
                }

                val samples = ShortArray(total) { (mixed[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()

                Thread.sleep((total * 1000L / RATE) + 100L)
            } catch (e: Exception) {
                // No sound is no reason to stop the call.
            } finally {
                try {
                    track?.release()
                } catch (e: Exception) {
                }

                main.post(done)
            }
        }.start()
    }
}

/**
 * The microphone opened only for this side, for Device Settings' test: its level read twenty times a second and nothing
 * else done with it. An engine opens it only while the call does not send the microphone, and closes it before the
 * call does.
 */
object CallsMicProbe {
    private const val RATE = 16_000

    @Volatile
    private var running: Thread? = null

    /** On the decibel scale the web reads a microphone on: -60 dB is nothing, -10 dB all of it. */
    fun level(rms: Double): Float =
        if (rms > 0) ((20 * log10(rms) + 60) / 50).coerceIn(0.0, 1.0).toFloat() else 0f

    /** 16-bit samples as they come off an audio device, little-endian. */
    fun level(data: ByteArray, length: Int = data.size): Float {
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)

        return level(buffer)
    }

    fun level(buffer: ByteBuffer): Float {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = shorts.remaining()

        if (count == 0) return 0f

        var sum = 0.0

        for (i in 0 until count) {
            val v = shorts.get(i) / 32768.0
            sum += v * v
        }

        return level(sqrt(sum / count))
    }

    /** True once it is listening. `level` is called on the probe's own thread. */
    @Suppress("MissingPermission")
    fun start(context: Context, level: (Float) -> Unit): Boolean {
        stop()

        if (!CallsPermissions.microphoneGranted(context)) return false

        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        if (minimum <= 0) return false

        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minimum, RATE / 10 * 2))
        } catch (e: Exception) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return false
        }

        val reader = Thread {
            // 50 ms at a time: the twenty readings a second the web takes.
            val chunk = ByteArray(RATE / 20 * 2)

            try {
                while (running === Thread.currentThread()) {
                    val read = record.read(chunk, 0, chunk.size)

                    if (read < 0) break
                    if (read > 0) level(level(chunk, read))
                }
            } finally {
                try {
                    record.stop()
                } catch (e: Exception) {
                }
                record.release()
            }
        }

        running = reader
        reader.start()

        return true
    }

    fun stop() {
        val reader = running ?: return

        running = null

        try {
            reader.join(300L)
        } catch (e: InterruptedException) {
        }
    }
}

/**
 * REC, at the start of a group call's bar after its connection, level with the dock and clear of every tile (the web's
 * RecordingPill): a red pill with a dot that breathes, REC and the time while it records, for everyone in the call, or
 * for its moderators alone where they alone know of it. A moderator's is a button: Stop after a line, which asks first.
 * Starting, it says so to a moderator; stopped, "Saving recording…" for a few seconds. On a phone held upright REC and
 * the word Stop go, as the web's narrow bar drops them.
 */
class CallsRecordingPill(context: Context) : LinearLayout(context) {
    companion object {
        const val HEIGHT_DP = 28
        const val SAVING_MS = 4000L
    }

    var onStop: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private val ground = GradientDrawable().apply { cornerRadius = HEIGHT_DP / 2f * density }
    private val spinner = android.widget.ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.argb(224, 255, 255, 255))
    }
    private val dot = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
    }
    private val mark = label(CallsTheme.string(context, "rec_mark", "REC"), CallsTheme.SEMIBOLD).apply { letterSpacing = 0.04f }
    private val time = label("", CallsTheme.MEDIUM).apply { fontFeatureSettings = "tnum" }
    private val line = View(context).apply { setBackgroundColor(Color.argb(115, 255, 255, 255)) }
    private val square = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = 1.5f * density
            setColor(Color.WHITE)
        }
    }
    private val stopWord = label(CallsTheme.string(context, "rec_stop", "Stop"), CallsTheme.SEMIBOLD)
    private val stop = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val words = label("", CallsTheme.MEDIUM)
    private var breath: android.animation.ObjectAnimator? = null
    private var stoppable = false

    private fun label(text: String, face: android.graphics.Typeface): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        typeface = face
        maxLines = 1
        includeFontPadding = false
        setTextColor(Color.WHITE)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ground
        visibility = View.GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        stop.addView(line, LayoutParams(maxOf(1, dp(1)), dp(16)).apply { marginStart = dp(2) })
        stop.addView(square, LayoutParams(dp(7), dp(7)).apply { marginStart = dp(7) })
        stop.addView(stopWord, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })

        addView(spinner, LayoutParams(dp(14), dp(14)))
        addView(dot, LayoutParams(dp(7), dp(7)))
        addView(mark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(stop, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(words, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener { if (stoppable) onStop?.invoke() }
    }

    /** The gap after each shown piece but the last, the web's flex gap. */
    private fun space(gap: Int) {
        val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility == View.VISIBLE }

        for ((index, child) in shown.withIndex()) {
            val params = child.layoutParams as LayoutParams
            val start = if (index == 0) 0 else gap

            if (params.marginStart != start) {
                params.marginStart = start
                child.layoutParams = params
            }
        }
    }

    /** What it says now: `saving` while "Saving recording…" stands after a stop, `narrow` on a stage under 600 wide. */
    fun show(recording: CallsRecording?, saving: Boolean, narrow: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (recording == null || !recording.pillShown) return hide()

        if (recording.state == "on") {
            val since = if (recording.since > 0) recording.since * 1000 else nowMillis
            val said = CallsRecording.elapsed((nowMillis - since) / 1000)

            ground.setColor(CallsTheme.recording(context))
            ground.setStroke(0, Color.TRANSPARENT)
            time.text = said
            spinner.visibility = View.GONE
            words.visibility = View.GONE
            dot.visibility = View.VISIBLE
            time.visibility = View.VISIBLE
            mark.visibility = if (narrow) View.GONE else View.VISIBLE
            stop.visibility = if (recording.can) View.VISIBLE else View.GONE
            stopWord.visibility = if (narrow) View.GONE else View.VISIBLE
            setPadding(dp(if (narrow) 9 else 10), 0, dp(if (narrow) 9 else 10), 0)
            space(dp(if (narrow) 5 else 6))
            stoppable = recording.can
            isClickable = recording.can
            isFocusable = recording.can

            contentDescription = if (recording.can) {
                CallsTheme.string(context, "rec_stop_label", "Recording, %s. Stop recording").replace("%s", said)
            } else {
                CallsTheme.string(context, "rec_recorded", "This call is being recorded.")
            }

            breathe(true)
        } else if ((recording.state == "starting" && recording.can) || (recording.state == "stopping" && saving)) {
            ground.setColor(Color.argb(107, 0, 0, 0))
            ground.setStroke(maxOf(1, dp(1)), CallsTheme.DOCK_HAIRLINE)
            dot.visibility = View.GONE
            mark.visibility = View.GONE
            time.visibility = View.GONE
            stop.visibility = View.GONE
            spinner.visibility = View.VISIBLE
            words.visibility = View.VISIBLE
            words.text = if (recording.state == "starting") {
                CallsTheme.string(context, "rec_starting", "Starting recording…")
            } else {
                CallsTheme.string(context, "rec_saving", "Saving recording…")
            }
            setPadding(dp(10), 0, dp(10), 0)
            space(dp(6))
            stoppable = false
            isClickable = false
            isFocusable = false
            contentDescription = words.text
            breathe(false)
        } else {
            return hide()
        }

        visibility = View.VISIBLE
    }

    private fun hide() {
        visibility = View.GONE
        stoppable = false
        isClickable = false
        breathe(false)
    }

    /** The dot's breath, 1.6 s a cycle; none where the phone has animations off, as animators then do nothing. */
    private fun breathe(on: Boolean) {
        if (!on) {
            breath?.cancel()
            breath = null
            dot.alpha = 1f
            return
        }

        if (breath != null) return

        breath = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
            duration = 800
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onDetachedFromWindow() {
        breathe(false)
        super.onDetachedFromWindow()
    }
}

/**
 * The notice to everyone else in the call, in the join notice's place over the dock (the web's RecordingNotice): who
 * started recording, or that it records to someone who joined since, and who can watch it. Got It puts it away for this
 * recording, Do Not Show Again for good, REC alone telling them from then on.
 */
class CallsRecordingNotice(
    context: Context,
    val content: Content,
    seen: () -> Unit,
    never: () -> Unit,
) : LinearLayout(context) {
    data class Content(val title: String, val note: String)

    private val density = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    init {
        orientation = HORIZONTAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(Color.argb(245, 22, 24, 30))
            setStroke(max(1, dp(1)), Color.argb(23, 255, 255, 255))
        }
        elevation = 12 * density
        isClickable = true
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE

        val red = CallsTheme.recording(context)
        val accent = CallsTheme.accent(context)

        val icon = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CallsTheme.withAlpha(red, 46))
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(CallsIconView(context).apply {
                set(CallsIcons.record)
                glyphColor = red
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }

        val body = LinearLayout(context).apply { orientation = VERTICAL }

        body.addView(TextView(context).apply {
            text = content.title
            textSize = 13.5f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
            ViewCompat.setAccessibilityHeading(this, true)
        })

        if (content.note.isNotEmpty()) {
            body.addView(TextView(context).apply {
                text = content.note
                textSize = 12f
                setTextColor(Color.argb(158, 255, 255, 255))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }

        fun pill(words: String, fill: Int, ink: Int, press: () -> Unit) = TextView(context).apply {
            text = words
            textSize = 13f
            typeface = CallsTheme.SEMIBOLD
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(ink)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(fill)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { press() }
        }

        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        buttons.addView(
            pill(CallsTheme.string(context, "rec_got_it", "Got It"), accent, CallsTheme.color(context, "text-on-accent", Color.WHITE), seen),
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(32))
        )
        buttons.addView(
            pill(CallsTheme.string(context, "rec_never", "Do Not Show Again"), Color.TRANSPARENT, Color.argb(184, 255, 255, 255), never),
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(8) }
        )

        body.addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        addView(icon, LayoutParams(dp(32), dp(32)))
        addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }
}
