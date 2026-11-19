package cloud.wordplus.realtime.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import cloud.wordplus.realtime.calls.CallException
import cloud.wordplus.realtime.calls.LiveStream
import cloud.wordplus.realtime.calls.LiveStreamMessage
import cloud.wordplus.realtime.calls.LiveStreamReaction
import cloud.wordplus.realtime.calls.LiveStreamState
import cloud.wordplus.realtime.calls.LiveStreamVideoView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cloud.wordplus.realtime.ui.calls.CallsIconView
import cloud.wordplus.realtime.ui.calls.CallsIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A live stream drawn ready for its viewers, as the web player draws it: the picture, Live and how many watch over it, the
 * stream's title, the chat with a field to write in, and a heart for reactions, which float up the picture as everyone
 * sends them. A signed-in viewer asks to come on stage from here, answers the invitation, and once on it has their camera,
 * their microphone and a way off. Optional, as the module is: an app that draws its own uses `LiveStream` and
 * `LiveStreamVideoView` alone. The host goes live from the studio on the web, or with the stream's own calls (`preview()`, `goLive()`).
 *
 * ```kotlin
 * val stream = rt.calls(context).stream("show:42")
 * LiveStreamScreen.open(activity, stream, title = "The new mugs")     // full screen, over the activity
 * ```
 *
 * Or as a view of the app's own layout. Closing it leaves the stream (`leavesOnClose`), and `onClose` puts it away.
 */
class LiveStreamScreen @JvmOverloads constructor(
    context: Context,
    val stream: LiveStream,
    val title: String? = null,
    appearance: Appearance = Appearance(),
) : LinearLayout(context) {

    /**
     * The screen's words and its colour. `strings` are its words in the user's language, by key: `live`, `watching`
     * (`%d` the count), `starts_soon`, `ended`, `say_something`, `send`, `read_only`, `pinned`, `react`, `close`,
     * `picture`, `no_picture`, `not_sent` (`%s` the code), `ask_stage`, `lower_hand`, `invited_title`, `invited_text`,
     * `not_now`, `join_stage`, `leave_stage`, `camera`, `microphone`, `stage_failed` (`%s` the code). The English is the
     * default for any left out.
     */
    data class Appearance(val strings: Map<String, String> = emptyMap(), val accent: Int = 0xFF2563EB.toInt())

    var appearance: Appearance = appearance
        set(value) {
            field = value
            styled()
        }

    /** Whether closing the screen leaves the stream (true), or keeps it for the app. */
    var leavesOnClose = true

    /** What closing does besides: the dialog of `open()` is dismissed. */
    var onClose: (() -> Unit)? = null

    internal val picture = LiveStreamVideoView(context)
    internal val note = TextView(context)
    internal val badge = TextView(context)
    internal val viewers = TextView(context)
    internal val close = CallsIconView(context)
    internal val titleView = TextView(context)
    internal val pinned = TextView(context)
    internal val chat = ListView(context)
    internal val field = EditText(context)
    internal val send = CallsIconView(context)
    internal val heart = CallsIconView(context)
    internal val failure = TextView(context)

    /** What a viewer does about the stage, under the title: asks, or once on it, their camera, microphone and a way off. */
    internal val stageRow = LinearLayout(context)
    internal val hand = StagePill(context)
    internal val cameraButton = StagePill(context)
    internal val microphoneButton = StagePill(context)
    internal val leaveStage = StagePill(context)

    /** The host asks them on. A card rather than a dialog, so that the picture and the chat go on beside it. */
    internal val invitation = LinearLayout(context)
    internal val invitedTitle = TextView(context)
    internal val invitedText = TextView(context)
    internal val notNow = StagePill(context)
    internal val joinStage = StagePill(context)

    /** This guest's own camera, in a corner of the picture. */
    internal val own = LiveStreamVideoView(context).apply { overTheOther() }

    /** The camera and microphone still to be allowed, and whether the system was asked for them. */
    internal var missingDevices: () -> List<String> = {
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }
    internal var askedForDevices = false
    private var acting = false
    private val floats = FrameLayout(context)
    private val stage = object : FrameLayout(context) {
        // The picture at 16:9 of the width, no more than 60% of the height the screen has.
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val most = (rootView?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 6 / 10
            val height = minOf(width * 9 / 16, most)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private var shown: List<LiveStreamMessage> = emptyList()
    private var sending = false
    private var scope: CoroutineScope? = null
    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val ink = if (dark) Color.WHITE else 0xFF111827.toInt()
    private val muted = if (dark) 0xFF9CA3AF.toInt() else 0xFF6B7280.toInt()
    private val surface = if (dark) 0xFF111111.toInt() else Color.WHITE
    private val raised = if (dark) 0xFF1F2937.toInt() else 0xFFF3F4F6.toInt()
    private val heartColor = 0xFFEC4566.toInt()

    private val messages = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(3), 0, dp(3))
            }
            view.text = line(shown[position])
            return view
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(surface)
        // Clear of the status bar, the cutout and the navigation bar, and above the keyboard: a window drawn edge to edge
        // (every app on Android 15 and later) would otherwise have Live and Close under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        build()
        styled()
        picture.show(stream)
        own.show(stream, tile = "local")
        stream.onChange { render() }
        stream.onReactions { counts -> counts.flatMap { (emoji, n) -> List(n) { emoji } }.take(6).forEach { float(it) } }
        render()
    }

    private fun text(key: String): String = appearance.strings[key] ?: ENGLISH[key] ?: key

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    // Building it

    private fun build() {
        stage.setBackgroundColor(Color.BLACK)
        picture.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        note.setTextColor(Color.WHITE)
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        note.typeface = Typeface.DEFAULT_BOLD
        note.gravity = Gravity.CENTER
        badge.setTextColor(Color.WHITE)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.background = rounded(0xFFDC2626.toInt(), 4)
        badge.setPadding(dp(7), dp(3), dp(7), dp(3))
        viewers.setTextColor(Color.WHITE)
        viewers.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        viewers.typeface = Typeface.DEFAULT_BOLD
        viewers.background = rounded(0x8C000000.toInt(), 4)
        viewers.setPadding(dp(7), dp(3), dp(7), dp(3))
        close.set(ICON_X)
        close.glyphColor = Color.WHITE
        close.fill = 0.5f
        close.background = rounded(0x8C000000.toInt(), 16)
        close.isClickable = true
        close.isFocusable = true
        close.setOnClickListener { closed() }

        stage.addView(picture, FrameLayout.LayoutParams(MATCH, MATCH))
        own.fills = true
        stage.addView(own, FrameLayout.LayoutParams(dp(72), dp(96), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
        stage.addView(note, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) })
        stage.addView(floats, FrameLayout.LayoutParams(MATCH, MATCH))
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge)
            addView(viewers, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        }
        stage.addView(top, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        stage.addView(close, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        addView(stage, LayoutParams(MATCH, WRAP))

        val below = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        titleView.setTextColor(ink)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.text = title
        titleView.visibility = if (title.isNullOrEmpty()) GONE else VISIBLE
        below.addView(titleView)
        buildStage(below)
        pinned.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        pinned.background = rounded(raised, 8)
        pinned.setPadding(dp(8), dp(6), dp(8), dp(6))
        below.addView(pinned, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        chat.adapter = messages
        chat.divider = null
        chat.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        chat.isStackFromBottom = false
        below.addView(chat, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        failure.setTextColor(muted)
        failure.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        failure.visibility = GONE
        below.addView(failure)

        val composer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        field.setSingleLine()
        field.imeOptions = EditorInfo.IME_ACTION_SEND
        field.setTextColor(ink)
        field.setHintTextColor(muted)
        field.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEND || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                sent()
                true
            } else false
        }
        composer.addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
        heart.set(ICON_HEART)
        heart.glyphColor = heartColor
        heart.fill = 0.6f
        heart.isClickable = true
        heart.isFocusable = true
        heart.setOnClickListener {
            stream.react(LiveStreamReaction.HEART)
            float(LiveStreamReaction.HEART.emoji)
        }
        composer.addView(heart, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        send.set(ICON_SEND)
        send.fill = 0.55f
        send.isClickable = true
        send.isFocusable = true
        send.setOnClickListener { sent() }
        composer.addView(send, LinearLayout.LayoutParams(dp(40), dp(40)))
        below.addView(composer, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(below, LayoutParams(MATCH, 0, 1f))
    }

    /** The pieces of the stage, drawn as the buttons of the web player are: a pill with its icon, tinted while it is on. */
    private fun buildStage(below: LinearLayout) {
        stageRow.orientation = HORIZONTAL
        stageRow.gravity = Gravity.CENTER_VERTICAL
        for (pill in listOf(hand, cameraButton, microphoneButton, leaveStage)) {
            stageRow.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        below.addView(stageRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        hand.setOnClickListener { handPressed() }
        cameraButton.setOnClickListener { cameraPressed() }
        microphoneButton.setOnClickListener { microphonePressed() }
        leaveStage.setOnClickListener { leaveStagePressed() }
        leaveStage.paint(0xFFDC2626.toInt(), raised)
        for (pill in listOf(leaveStage, notNow, joinStage)) pill.icon.visibility = GONE
        // Their icons alone: with its words each, three pills do not fit the width of a phone.
        for (pill in listOf(cameraButton, microphoneButton)) pill.iconOnly()

        invitation.orientation = VERTICAL
        invitation.background = rounded(raised, 10)
        invitation.setPadding(dp(12), dp(10), dp(12), dp(10))
        invitedTitle.setTextColor(ink)
        invitedTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        invitedTitle.typeface = Typeface.DEFAULT_BOLD
        invitation.addView(invitedTitle)
        invitedText.setTextColor(muted)
        invitedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        invitation.addView(invitedText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        val answers = LinearLayout(context).apply { orientation = HORIZONTAL }
        answers.addView(notNow, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        answers.addView(joinStage)
        invitation.addView(answers, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        notNow.paint(ink, surface)
        notNow.setOnClickListener { notNowPressed() }
        joinStage.setOnClickListener { joinPressed() }
        below.addView(invitation, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    private fun styled() {
        badge.text = text("live").uppercase()
        send.glyphColor = appearance.accent
        send.contentDescription = text("send")
        heart.contentDescription = text("react")
        close.contentDescription = text("close")
        invitedTitle.text = text("invited_title")
        invitedText.text = text("invited_text")
        notNow.label.text = text("not_now")
        joinStage.label.text = text("join_stage")
        joinStage.paint(Color.WHITE, appearance.accent)
        leaveStage.label.text = text("leave_stage")
        cameraButton.contentDescription = text("camera")
        microphoneButton.contentDescription = text("microphone")
        render()
    }

    // Drawing it

    internal fun render() {
        val live = stream.state == LiveStreamState.LIVE
        badge.visibility = if (live) VISIBLE else GONE
        viewers.visibility = if (live) VISIBLE else GONE
        viewers.text = String.format(text("watching"), stream.viewers)
        note.text = when (stream.state) {
            LiveStreamState.IDLE -> text("starts_soon")
            LiveStreamState.ENDED -> text("ended")
            else -> ""
        }
        note.visibility = if (note.text.isEmpty()) GONE else VISIBLE
        picture.contentDescription = if (picture.showsPicture) text("picture") else text("no_picture")
        heart.visibility = if (live && stream.reactionsOn) VISIBLE else GONE
        renderStage()
        val writes = stream.canWrite
        field.isEnabled = writes && !sending
        send.isEnabled = writes && !sending
        send.alpha = if (send.isEnabled) 1f else 0.4f
        field.hint = if (writes) text("say_something") else text("read_only")
        val message = stream.pinned
        if (message != null) {
            pinned.text = line(message, text("pinned") + ": ")
            pinned.visibility = VISIBLE
        } else {
            pinned.visibility = GONE
        }
        if (stream.messages.map { it.id } != shown.map { it.id }) {
            shown = stream.messages
            messages.notifyDataSetChanged()
        }
    }

    /** As the web has it (realtime-ui streams/controls.tsx): on stage their devices and a way off, else the asking. */
    private fun renderStage() {
        val ended = stream.state == LiveStreamState.ENDED
        val on = stream.onStage && !ended
        val asks = !on && !ended && !stream.invited && stream.canAskStage
        hand.visibility = if (asks) VISIBLE else GONE
        cameraButton.visibility = if (on) VISIBLE else GONE
        microphoneButton.visibility = if (on) VISIBLE else GONE
        leaveStage.visibility = if (on) VISIBLE else GONE
        stageRow.visibility = if (asks || on) VISIBLE else GONE
        hand.label.text = if (stream.handRaised) text("lower_hand") else text("ask_stage")
        hand.icon.set(CallsIcons.raiseHand)
        hand.paint(if (stream.handRaised) Color.WHITE else ink, if (stream.handRaised) appearance.accent else raised)
        ViewCompat.setStateDescription(hand, if (stream.handRaised) "up" else "down")
        cameraButton.icon.set(if (stream.camera) CallsIcons.video else CallsIcons.videoOff)
        cameraButton.paint(ink, raised)
        ViewCompat.setStateDescription(cameraButton, if (stream.camera) "on" else "off")
        microphoneButton.icon.set(if (stream.microphone) CallsIcons.mic else CallsIcons.micOff)
        microphoneButton.paint(ink, raised)
        ViewCompat.setStateDescription(microphoneButton, if (stream.microphone) "on" else "off")
        invitation.visibility = if (stream.invited && !on && !ended) VISIBLE else GONE
        for (pill in listOf(hand, cameraButton, microphoneButton, leaveStage, notNow, joinStage)) {
            pill.isEnabled = !acting
            pill.alpha = if (acting) 0.5f else 1f
        }
        own.visibility = if (on && stream.camera) VISIBLE else GONE
    }

    private fun line(message: LiveStreamMessage, prefix: String = ""): CharSequence {
        val out = SpannableStringBuilder(prefix)
        out.setSpan(ForegroundColorSpan(muted), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = out.length
        out.append(message.name)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(ink), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val text = out.length
        out.append("  ").append(message.text)
        out.setSpan(ForegroundColorSpan(ink), text, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    // What the person does

    private fun sent() {
        val words = field.text.toString().trim()
        if (words.isEmpty() || sending) return
        sending = true
        failure.visibility = GONE
        render()
        val running = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        running.launch {
            try {
                stream.send(words)
                field.setText("")
            } catch (e: CallException) {
                failure.text = String.format(text("not_sent"), e.code)
                failure.visibility = VISIBLE
            }
            sending = false
            render()
        }
    }

    /** Something done about the stage, one at a time, and what the cloud or the device refused said under the chat. */
    private fun act(work: suspend () -> Unit) {
        if (acting) return
        acting = true
        failure.visibility = GONE
        render()
        val running = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        running.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refused((e as? CallException)?.code ?: "devices")
            }
            acting = false
            render()
        }
    }

    private fun refused(code: String) {
        failure.text = String.format(text("stage_failed"), code)
        failure.visibility = VISIBLE
    }

    internal fun handPressed() {
        val up = !stream.handRaised
        act { stream.hand(up) }
    }

    /** Android asks for the camera and microphone from an activity, and a window coming back into focus says it answered. */
    internal fun joinPressed() {
        val missing = missingDevices()
        if (missing.isEmpty()) {
            joinNow()
            return
        }
        val activity = activity()
        if (activity == null || askedForDevices) {
            askedForDevices = false
            devicesRefused()
            return
        }
        askedForDevices = true
        activity.requestPermissions(missing.toTypedArray(), REQUEST_DEVICES)
    }

    private fun joinNow() = act { stream.joinStage() }

    private fun devicesRefused() {
        stream.declineStage()
        refused("devices")
        render()
    }

    private fun activity(): Activity? {
        var at = context
        while (at is ContextWrapper) {
            if (at is Activity) return at
            at = at.baseContext
        }
        return null
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || !askedForDevices) return
        askedForDevices = false
        if (missingDevices().isEmpty()) joinNow() else devicesRefused()
    }

    internal fun notNowPressed() {
        stream.declineStage()
    }

    internal fun cameraPressed() {
        val on = !stream.camera
        act { stream.setCamera(on) }
    }

    internal fun microphonePressed() {
        val on = !stream.microphone
        act { stream.setMicrophone(on) }
    }

    internal fun leaveStagePressed() {
        stream.leaveStage()
    }

    internal fun closed() {
        if (leavesOnClose) stream.leave()
        onClose?.invoke()
    }

    /** A reaction floating up the picture, from its lower right: the emoji that was sent. */
    private fun float(emoji: String) {
        if (floats.height <= 0) return
        val size = dp(28)
        val view = TextView(context).apply {
            text = emoji
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val x = floats.width - dp(36) - Random.nextInt(dp(24))
        view.x = x.toFloat()
        view.y = (floats.height - dp(40)).toFloat()
        floats.addView(view, FrameLayout.LayoutParams(size, size))
        val from = view.y
        val to = floats.height * 0.25f
        val drift = Random.nextInt(-dp(20), dp(8))
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            addUpdateListener {
                val t = it.animatedValue as Float
                view.y = from + (to - from) * t
                view.x = x + drift * t
                view.alpha = 1f - t
                if (t >= 1f) floats.removeView(view)
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope?.cancel()
        scope = null
        acting = false
    }

    /** A pill with its icon and its words, as the stage has them. */
    internal class StagePill(context: Context) : LinearLayout(context) {
        val icon = CallsIconView(context)
        val label = TextView(context)
        private val radius = 18 * resources.displayMetrics.density

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            val density = resources.displayMetrics.density
            setPadding((12 * density).toInt(), (7 * density).toInt(), (14 * density).toInt(), (7 * density).toInt())
            addView(icon, LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply { marginEnd = (6 * density).toInt() })
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            label.typeface = Typeface.DEFAULT_BOLD
            addView(label)
        }

        fun iconOnly() {
            label.visibility = GONE
            (icon.layoutParams as LayoutParams).marginEnd = 0
            setPadding(paddingTop + paddingTop, paddingTop, paddingTop + paddingTop, paddingBottom)
        }

        fun paint(ink: Int, fill: Int) {
            icon.glyphColor = ink
            label.setTextColor(ink)
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius
            }
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REQUEST_DEVICES = 7312

        // Lucide's glyphs on its 24 grid, as the call screen draws its own.
        internal const val ICON_X = "M18 6 6 18|m6 6 12 12"
        internal const val ICON_HEART = "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"
        internal const val ICON_SEND = "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z|m21.854 2.147-10.94 10.939"

        internal val ENGLISH = mapOf(
            "live" to "Live",
            "watching" to "%d watching",
            "starts_soon" to "The stream starts soon",
            "ended" to "This stream has ended",
            "say_something" to "Say something…",
            "send" to "Send",
            "read_only" to "Chat is read-only for you",
            "pinned" to "Pinned",
            "react" to "Send a heart",
            "close" to "Close",
            "picture" to "The picture of the stream",
            "no_picture" to "No picture yet",
            "not_sent" to "Not sent (%s)",
            "ask_stage" to "Ask to join on stage",
            "lower_hand" to "Lower hand",
            "invited_title" to "The host invites you on stage",
            "invited_text" to "Your camera and microphone will be live for everyone watching.",
            "not_now" to "Not now",
            "join_stage" to "Join stage",
            "leave_stage" to "Leave stage",
            "camera" to "Camera",
            "microphone" to "Microphone",
            "stage_failed" to "You could not join the stage (%s)",
        )

        /**
         * Opens the screen over `activity`, full screen, in a dialog of its own, which closing (or Back) dismisses after
         * leaving the stream; `onClose` hears it. Answers the dialog, for the app to dismiss itself.
         */
        @JvmStatic
        @JvmOverloads
        fun open(activity: Activity, stream: LiveStream, title: String? = null, appearance: Appearance = Appearance(), onClose: (() -> Unit)? = null): Dialog {
            val screen = LiveStreamScreen(activity, stream, title, appearance)
            screen.onClose = onClose
            val dialog = object : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(screen)
                    window?.setLayout(MATCH, MATCH)
                    @Suppress("DEPRECATION")
                    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
            val own = screen.onClose
            screen.onClose = {
                own?.invoke()
                dialog.dismiss()
            }
            dialog.setOnCancelListener { screen.closed() }
            dialog.show()
            return dialog
        }
    }
}
