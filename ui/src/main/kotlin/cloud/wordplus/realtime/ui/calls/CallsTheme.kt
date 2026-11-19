package cloud.wordplus.realtime.ui.calls

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * The colours and the words the call screen uses.
 *
 * Both come from the site: the palette chosen in the Design tab and the
 * strings in the person's language, handed over in `configure`. A phone that
 * has never been configured still has to draw something, so every lookup has a
 * fallback that reads as a call screen.
 */
object CallsTheme {
    /**
     * The stage is deliberately near-black whatever the palette says: it is
     * what a remote camera is seen against, and a light one washes the picture
     * out.
     */
    val STAGE = Color.rgb(13, 15, 20)
    val DANGER = Color.rgb(232, 61, 71)
    val ANSWER = Color.rgb(46, 184, 89)

    /**
     * The dock's own colours, as the call stylesheet has them and as the iOS
     * screen reads them: a control is a wash of white over the stage, an
     * active one is nearly solid white, and the row they sit in is a pane of
     * dark glass with a hairline round it.
     */
    val CONTROL = Color.argb(31, 255, 255, 255)
    val CONTROL_ACTIVE = Color.argb(235, 255, 255, 255)
    val DOCK_GLASS = Color.argb(128, 0, 0, 0)
    val DOCK_HAIRLINE = Color.argb(20, 255, 255, 255)

    /**
     * A colour from the site's own palette.
     *
     * `configure` has carried `designTokens` since the first package and this
     * side never read them — the call screen was a hardcoded blue while iOS
     * followed whatever the Design tab said. The stylesheet keys them by the
     * custom property's own name, so both spellings are tried, prefixed first.
     */
    fun color(context: Context, token: String, fallback: Int): Int {
        val config = CallsConfigStore.current(context) ?: return fallback
        val tokens = if (config.darkMode) config.designTokensDark else config.designTokens

        // The call screen's own tokens (`--wplus-call-canvas`) are found as `call-canvas` too, as the web names them. The
        // names before @wordplus/realtime-ui 1.0.6, `--bm-…`, still count, under the new ones.
        for (name in listOf("--wplus-color-$token", "--wplus-$token", "--bm-color-$token", "--bm-$token", token)) {
            val value = tokens[name] ?: config.designTokens[name] ?: continue

            parse(value)?.let { return it }
        }

        return fallback
    }

    fun accent(context: Context): Int = color(context, "accent", Color.rgb(33, 120, 245))

    /** The red a switched-off microphone is drawn in. */
    fun recording(context: Context): Int = color(context, "recording", Color.rgb(232, 65, 65))

    /** A token colour at a given opacity, keeping whatever the token resolved to. */
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun success(context: Context): Int = color(context, "success", Color.rgb(34, 197, 94))

    /** The presence dot, from the token the stylesheet draws it with. */
    fun online(context: Context): Int = color(context, "online", Color.rgb(67, 228, 67))

    fun warning(context: Context): Int = color(context, "warning", Color.rgb(245, 158, 11))

    /**
     * The tile a grid draws people on: the stylesheet's wash of white over the
     * stage, composited to an opaque colour because the presence dot's ring
     * has to be the colour behind it, and a see-through ring is not a ring.
     */
    val TILE: Int = washed(STAGE)

    private fun washed(stage: Int): Int {
        val wash = 0.08f
        fun mix(channel: Int) = (channel * (1 - wash) + 255 * wash).toInt()

        return Color.rgb(mix(Color.red(stage)), mix(Color.green(stage)), mix(Color.blue(stage)))
    }

    /**
     * The stage, near-black whatever the site's palette says, unless an app sets the web's own token for it,
     * `--bm-call-canvas`, which no site's palette carries; the tile a grid draws people on (`--bm-call-tile`, else a
     * wash over the stage); a control (`--bm-call-btn`); and the glass behind the dock (`--bm-call-dock`). As iOS reads
     * them.
     */
    fun stage(context: Context): Int = color(context, "call-canvas", STAGE)

    fun tile(context: Context): Int = color(context, "call-tile", washed(stage(context)))

    fun control(context: Context): Int = color(context, "call-btn", CONTROL)

    fun dockGlass(context: Context): Int = color(context, "call-dock", DOCK_GLASS)

    fun string(context: Context, key: String, fallback: String): String =
        CallsConfigStore.current(context)?.strings?.get(key) ?: fallback

    /**
     * The two weights the stylesheet sets the call's small text in, 500 and 600. Android can ask for 600 by number
     * only from API 28; below that the medium face stands in for it, which is the nearest the system font has.
     */
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val SEMIBOLD: Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 600, false) else MEDIUM

    /**
     * Whether the microphone and camera controls wear the state they are in or
     * the action they perform — the site's "Invert Mute/Hide Icons" setting,
     * which the app used to ignore. The default is the state, which is what
     * the web draws with the setting off and what this dock has always drawn.
     */
    fun showsState(context: Context): Boolean =
        CallsConfigStore.current(context)?.iconsReverted ?: true


    /**
     * `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()/rgba()` and the bare triplet
     * WordPress actually stores — `37, 99, 235`, so that a rule can write
     * `rgba(var(--bm-color-accent), 0.5)`.
     */
    private fun parse(value: String): Int? {
        val text = value.trim().lowercase()

        if (text.startsWith("#")) {
            var hex = text.drop(1)

            if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")

            if (hex.length != 6 && hex.length != 8) return null

            val number = hex.toLongOrNull(16) ?: return null

            return if (hex.length == 8) {
                Color.argb(
                    (number and 0xff).toInt(),
                    ((number shr 24) and 0xff).toInt(),
                    ((number shr 16) and 0xff).toInt(),
                    ((number shr 8) and 0xff).toInt()
                )
            } else {
                Color.rgb(
                    ((number shr 16) and 0xff).toInt(),
                    ((number shr 8) and 0xff).toInt(),
                    (number and 0xff).toInt()
                )
            }
        }

        val inner = if (text.startsWith("rgb")) text.substringAfter('(').substringBefore(')') else text
        val parts = inner.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }

        if (parts.size < 3) return null

        val red = parts[0].toFloatOrNull() ?: return null
        val green = parts[1].toFloatOrNull() ?: return null
        val blue = parts[2].toFloatOrNull() ?: return null
        val alpha = if (parts.size > 3) parts[3].toFloatOrNull() ?: 1f else 1f

        return Color.argb((alpha * 255).toInt(), red.toInt(), green.toInt(), blue.toInt())
    }
}

/**
 * What a control on the dock is for, which is what it is coloured by.
 *
 * The same six the iOS screen has, which are the same six the stylesheet
 * draws. A toggle that latches goes to the site's accent; the microphone goes
 * white with a red glyph, because a muted microphone is a warning and not a
 * selection; hanging up and answering are their own colours and never change.
 */
enum class CallsButtonTone {
    CONTROL,
    MEDIUM,
    LATCH,
    WARNING,
    DANGER,
    ANSWER
}

/**
 * A control on the call screen, the shape and colour the web and iOS draw.
 *
 * Drawn rather than themed: the plugin's own rule is that host styling
 * outranks ours everywhere it can reach, and a call screen is the one place
 * that must look the same on every phone.
 *
 * Not always a circle. Hanging up and answering are pills — wider than they
 * are tall by a fixed amount — so they cannot be mistaken for one of the
 * toggles beside them, which is what the stylesheet does and what iOS copies.
 */
class CallsRoundButton(
    context: Context,
    glyph: String,
    tone: CallsButtonTone = CallsButtonTone.CONTROL,
    // A variable so the dock can keep a host's button and take the action its pieces gave anew (CallsStage.arrangeDock).
    internal var action: () -> Unit
) : FrameLayout(context) {
    companion object {
        /**
         * The stylesheet steps its controls down as the stage narrows, and a
         * phone is always the narrowest of them: `--wplus-call-btn` is 34, 32
         * on a stage of 719 or less and 30 on one of 559 or less. In dp, which
         * is what a point is here.
         *
         * These were a fixed 64dp — nearly double, with 16dp between them
         * where the web leaves 5 — so the same dock was a different object on
         * the two phones.
         */
        fun diameterDp(widthDp: Int): Int = when {
            widthDp <= 559 -> 30
            widthDp <= 719 -> 32
            else -> 34
        }

        /** Answering and hanging up are pills 16 wider than a disc, as the web's; everything else is a disc. */
        fun strideFor(tone: CallsButtonTone, diameter: Int): Int = when (tone) {
            CallsButtonTone.DANGER, CallsButtonTone.ANSWER -> diameter + 16
            else -> diameter
        }
    }

    private val icon = CallsIconView(context)
    private val shape = GradientDrawable().apply { this.shape = GradientDrawable.RECTANGLE }

    /**
     * Which glyph this control wears. A variable rather than a constructor
     * value because one of them changes with the call: hanging up on a person
     * is a handset, leaving a room is a door.
     */
    var glyph: String = glyph
        set(value) {
            if (field == value) return
            field = value
            restyle()
        }

    var isOn: Boolean = false
        set(value) {
            field = value
            restyle()
        }

    /**
     * What the control is for, and so its colours. A variable for Host Controls, which is latched while its menu is
     * open and amber while a room rule holds, as the web's `.bm-host-controls` is; every other control keeps the tone
     * it was built with.
     */
    var tone: CallsButtonTone = tone
        set(value) {
            if (field == value) return
            field = value
            restyle()
        }

    init {
        // The glyph fills half the button at whatever size the button ends
        // up, which is the proportion iOS uses and the web's dock draws.
        //
        // It used to be a fixed 18dp inset per side: right at the 64dp these
        // are built at, wrong at every other size — and `fitControls` shrinks
        // them whenever the dock is crowded. A group video call shows eight,
        // they came down to 48dp and the glyph came down with them to 12dp,
        // a quarter of the button rather than a half, which reads as a dot
        // and not an icon. Under 36dp there would have been no glyph at all.
        //
        // The proportion is a scale on the path rather than a size on this
        // child, because a button only learns its size during layout and
        // resizing a child from there lands a pass late — that route left
        // the glyphs at assorted sizes and the flip control at a third of
        // its neighbours.
        icon.fill = 0.5f

        // A pill is wider than it is tall, so the glyph is squared on the
        // shorter side and centred rather than stretched across the width.
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        background = shape

        isClickable = true

        // A drawn control is a bare FrameLayout as far as the system is
        // concerned. Saying it is focusable is what puts it in the
        // accessibility tree beside its label, so TalkBack can reach it and a
        // test can find it by name rather than by pixel.
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        setOnClickListener {
            // A press that does nothing feels broken, so it is answered even
            // when the call is in a state the button cannot change. The
            // stylesheet presses its controls to 0.94 and so does iOS.
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

            animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }.start()

            action()
        }

        restyle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Half the height, always — which rounds a disc and rounds the ends of
        // a pill. Set from the size rather than from a constant, because
        // `fitControls` shrinks the row on a crowded dock and a fixed radius
        // leaves the small ones square-shouldered.
        shape.cornerRadius = h / 2f
    }

    private fun restyle() {
        val accent = CallsTheme.accent(context)
        val control = CallsTheme.control(context)

        val ground = when (tone) {
            CallsButtonTone.CONTROL -> control
            CallsButtonTone.MEDIUM -> if (isOn) CallsTheme.CONTROL_ACTIVE else control
            CallsButtonTone.LATCH -> if (isOn) accent else control
            CallsButtonTone.WARNING -> if (isOn) CallsTheme.warning(context) else control
            CallsButtonTone.DANGER -> CallsTheme.recording(context)
            CallsButtonTone.ANSWER -> CallsTheme.success(context)
        }

        shape.setColor(ground)

        icon.glyphColor = when (tone) {
            // A switched-off microphone or camera is a warning, not a
            // selection: white ground, red glyph. This drew it black, which
            // reads as "chosen" rather than "off".
            CallsButtonTone.MEDIUM -> if (isOn) CallsTheme.recording(context) else Color.WHITE
            CallsButtonTone.LATCH -> if (isOn) CallsTheme.color(context, "text-on-accent", Color.WHITE) else Color.WHITE
            CallsButtonTone.WARNING -> if (isOn) Color.rgb(17, 19, 24) else Color.WHITE
            else -> Color.WHITE
        }

        icon.set(symbol())

        // The handset tilted into the hang-up glyph, the same turn the
        // stylesheet gives it and the same one the iOS screen uses.
        icon.rotation = if (glyph == "hangup") 135f else 0f
    }

    /**
     * The web's own glyphs, by way of `CallsIcons` - the same registry the
     * iOS call screen draws from. This used to be a table of emoji, which is
     * why an Android call looked like a different product from the same call
     * in a browser.
     */
    private fun symbol(): String = when (glyph) {
        // `isOn` is muted here and camera-off below, so a control that shows
        // the state it is in draws the crossed-out glyph exactly when it is
        // on, and one that shows the action it performs draws the other. The
        // site decides which through "Invert Mute/Hide Icons"; these two were
        // the only controls in the app that ignored it.
        "mic" -> if (isOn == CallsTheme.showsState(context)) CallsIcons.micOff else CallsIcons.mic
        "camera" -> if (isOn == CallsTheme.showsState(context)) CallsIcons.videoOff else CallsIcons.video
        "flip" -> CallsIcons.switchCamera
        // Crossed out while the earpiece has it, which is the state the
        // control is offering to change. It drew the same glyph either way.
        "speaker" -> if (isOn) CallsIcons.speaker else CallsIcons.speakerOff
        "share" -> if (isOn) CallsIcons.screenShareOff else CallsIcons.screenShare
        // Host Controls, a moderator's menu of the room's rules.
        "host" -> CallsIcons.shield
        "settings" -> CallsIcons.settings
        "pip" -> CallsIcons.pip
        "hangup" -> CallsIcons.phone
        "answer" -> CallsIcons.phone
        "raisehand" -> CallsIcons.raiseHand
        // The microphone while a moderator keeps it off.
        "silenced" -> CallsIcons.silenced
        // A room is left rather than hung up, and the web says so with a door
        // rather than a handset.
        "exit" -> CallsIcons.logout
        // Show Chat / Hide Chat: the conversation beside the call.
        "chat" -> CallsIcons.thread
        // A host's own button (CallsPieces) may wear any icon: its path data, as these are written.
        else -> if (glyph.startsWith("M") || glyph.startsWith("m")) glyph else ""
    }
}

/**
 * The translucent row the controls sit in.
 *
 * The dock is a pane of dark glass with a hairline round it on the web and on
 * iOS. Android drew the controls loose on the stage with nothing behind them,
 * which is most of why the same call looked like a different app on the two
 * phones — over a bright picture the glyphs had nothing to sit against at all.
 *
 * The web's spacing: 5 between the controls (`GAP_DP`, which the stage's
 * fitting gives each control as its margin) and 5 inside a hairline it draws
 * outside its padding, which a stroke draws inside, so 6 here.
 */
class CallsDock(context: Context) : LinearLayout(context) {
    companion object {
        const val GAP_DP = 5
        const val PADDING_DP = 6
    }

    private val shape = GradientDrawable().apply {
        this.shape = GradientDrawable.RECTANGLE
        setColor(CallsTheme.dockGlass(context))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        val density = resources.displayMetrics.density
        val padding = (PADDING_DP * density).toInt()

        shape.setStroke(maxOf(1, (density).toInt()), CallsTheme.DOCK_HAIRLINE)

        setPadding(padding, padding, padding, padding)

        background = shape
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 26 in the stylesheet, which CSS clamps to half the height on a row
        // this short. Clamped here for the same reason.
        shape.cornerRadius = minOf(26f * resources.displayMetrics.density, h / 2f)
    }
}

/**
 * A group call's connection, the web's connection badge (`.wplus-connection-quality`) as a narrow call draws it at the
 * start of its bar: a 28dp glass disc with a spinner while the room connects or reconnects, and a 7dp dot once it is
 * up, green, or red while this side's connection reads lost. It sits back at 72% and comes forward for three seconds
 * after a change, as the web's does.
 */
class CallsConnectionPill(context: Context) : FrameLayout(context) {
    private val body = FrameLayout(context)
    private val spinner = ProgressBar(context)
    private val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val dotView = View(context).apply { background = dot; visibility = View.GONE }
    private var phase = ""
    private val settle = Runnable { body.animate().alpha(0.72f).setDuration(200).start() }

    init {
        val density = resources.displayMetrics.density

        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        // The web's glass at 0.42, under its hairline.
        body.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(107, 0, 0, 0))
            setStroke(maxOf(1, density.toInt()), CallsTheme.DOCK_HAIRLINE)
        }
        body.alpha = 0.72f

        // The web's spinner is 13 pixels.
        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(Color.argb(224, 255, 255, 255))

        body.addView(spinner, LayoutParams((14 * density).toInt(), (14 * density).toInt(), Gravity.CENTER))
        body.addView(dotView, LayoutParams((7 * density).toInt(), (7 * density).toInt(), Gravity.CENTER))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** `connecting`, `reconnecting` or `in_progress`, and whether this side's connection reads lost. */
    fun show(phase: String, lost: Boolean = false) {
        dot.setColor(if (lost) CallsTheme.recording(context) else CallsTheme.success(context))

        if (phase == this.phase) return

        this.phase = phase

        val connected = phase == "in_progress"

        spinner.visibility = if (connected) View.GONE else View.VISIBLE
        dotView.visibility = if (connected) View.VISIBLE else View.GONE

        // Connected, the label says it all: the web's own words for the state are not translated.
        val status = CallsTheme.string(context, "connection_status", "Connection Status (Quality)")
        val state = when (phase) {
            "in_progress" -> null
            "reconnecting" -> CallsTheme.string(context, "reconnecting_self", "Reconnecting...")
            else -> CallsTheme.string(context, "connecting", "Connecting...")
        }

        contentDescription = if (state == null) status else "$status, $state"

        removeCallbacks(settle)
        body.animate().cancel()
        body.alpha = 1f
        postDelayed(settle, 3000)
    }

    /** Put away with its call: the next one starts from its first state, which comes forward like any change. */
    fun reset() {
        removeCallbacks(settle)
        body.animate().cancel()
        phase = ""
        spinner.visibility = View.VISIBLE
        dotView.visibility = View.GONE
        body.alpha = 0.72f
    }

    companion object {
        const val SIZE_DP = 28
    }
}
