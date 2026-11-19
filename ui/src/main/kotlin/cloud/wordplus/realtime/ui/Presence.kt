package cloud.wordplus.realtime.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import cloud.wordplus.realtime.ui.calls.CallsAvatars
import cloud.wordplus.realtime.ui.calls.CallsConfigStore
import cloud.wordplus.realtime.ui.calls.CallsIconView
import cloud.wordplus.realtime.ui.calls.CallsIcons
import cloud.wordplus.realtime.ui.calls.CallsTheme
import kotlin.math.max
import kotlin.math.roundToInt

// A person's face with their status dot, and the typing and activity line: realtime-ui's presence parts
// (components/presence/avatar-face.tsx and presence.tsx) under their names. Views only: whoever draws them says who is
// online and passes the people, so an app feeds them from a presence room, from its own list, or from anything else. None
// of them reads a room or a connection. Their words are `CallScreen.Appearance.strings` and their colours its tokens.

/** A person's status, as the dot and the label say it. */
enum class Presence {
    ONLINE, BUSY, AWAY, OFFLINE;

    /** What a screen reader says of it: `Online` … in the app's words (`presence_online` …), English otherwise. */
    internal fun said(context: Context): String = when (this) {
        ONLINE -> CallsTheme.string(context, "presence_online", "Online")
        BUSY -> CallsTheme.string(context, "presence_busy", "Do not disturb")
        AWAY -> CallsTheme.string(context, "presence_away", "Away")
        OFFLINE -> CallsTheme.string(context, "presence_offline", "Offline")
    }
}

/** The colours of these parts, by the tokens of the web, each with the web's own light or dark where no token says. */
internal object PresenceColors {
    fun of(context: Context, presence: Presence): Int = when (presence) {
        Presence.ONLINE -> CallsTheme.color(context, "online", Color.rgb(61, 165, 18))
        Presence.BUSY -> CallsTheme.color(context, "busy", Color.rgb(220, 53, 69))
        Presence.AWAY -> CallsTheme.color(context, "away", Color.rgb(245, 158, 11))
        Presence.OFFLINE -> CallsTheme.color(context, "offline", Color.rgb(156, 163, 175))
    }

    private fun night(context: Context): Boolean =
        CallsConfigStore.current(context)?.darkMode == true ||
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun either(context: Context, token: String, light: Int, dark: Int): Int =
        CallsTheme.color(context, token, if (night(context)) dark else light)

    fun surface(context: Context): Int = either(context, "surface", Color.rgb(255, 255, 255), Color.rgb(15, 23, 42))
    fun textPrimary(context: Context): Int = either(context, "text-primary", Color.rgb(17, 24, 39), Color.rgb(241, 245, 249))
    fun textTertiary(context: Context): Int = either(context, "text-tertiary", Color.rgb(107, 114, 128), Color.rgb(148, 163, 184))
    fun border(context: Context): Int = either(context, "border", Color.rgb(229, 231, 235), Color.rgb(51, 65, 95))
    fun hover(context: Context): Int = either(context, "bg-hover", Color.rgb(243, 244, 246), Color.rgb(30, 41, 70))
    fun activity(context: Context): Int = either(context, "accent-hover", Color.rgb(29, 78, 216), Color.rgb(59, 130, 246))
}

/** The status dot, in the colour of its status with a ring of what is behind it: `presence` null draws it as offline. */
class PresenceDot @JvmOverloads constructor(
    context: Context,
    presence: Presence?,
    private val sizeDp: Int = 10,
    ringColor: Int? = null,
    color: Int? = null,
) : View(context) {
    var presence: Presence? = presence
        set(value) {
            field = value
            draw()
        }

    /** The ring round it: the surface by default. Transparent for none. */
    var ringColor: Int? = ringColor
        set(value) {
            field = value
            draw()
        }

    /** A status of the app's own in its colour, while the person is not offline. */
    var color: Int? = color
        set(value) {
            field = value
            draw()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /** The colour it draws, for a test. */
    internal val colorShown: Int get() = fill.color

    init {
        draw()
    }

    private fun draw() {
        val shown = presence ?: Presence.OFFLINE
        fill.color = (if (shown != Presence.OFFLINE) color else null) ?: PresenceColors.of(context, shown)
        ring.color = ringColor ?: PresenceColors.surface(context)
        contentDescription = shown.said(context)
        invalidate()
    }

    // Two dp outside the dot, as the web's box shadow, inside this view so that nothing clips it.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = dp(sizeDp + 4)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val middle = width / 2f
        canvas.drawCircle(middle, middle, middle, ring)
        canvas.drawCircle(middle, middle, middle - dp(2), fill)
    }
}

/** The shape of a face: a circle by default, as the call screen draws them. */
enum class AvatarShape {
    CIRCLE, ROUNDED, SQUARE;

    internal fun radiusDp(sizeDp: Int): Float = when (this) {
        CIRCLE -> sizeDp / 2f
        ROUNDED -> minOf(8f, sizeDp / 2f)
        SQUARE -> minOf(2f, sizeDp / 2f)
    }
}

/**
 * A person's letters and the colours they are drawn in, where the app gives them: the first letters of their name on the
 * blank of the face otherwise.
 */
data class AvatarLetters(val text: String, val background: Int, val color: Int)

/**
 * A person's face: their picture, or their letters, with their status dot at its corner. A picture that does not load
 * leaves the letters. A photo and an SVG both draw, as on the call screen.
 */
class Avatar @JvmOverloads constructor(
    context: Context,
    name: String? = null,
    picture: String? = null,
    private val sizeDp: Int = 36,
    shape: AvatarShape = AvatarShape.CIRCLE,
    presence: Presence? = null,
    showOffline: Boolean = false,
    dotSizeDp: Int? = null,
    presenceColor: Int? = null,
    presenceRing: Int? = null,
    letters: AvatarLetters? = null,
    decorative: Boolean = false,
) : FrameLayout(context) {
    var name: String? = name
        set(value) {
            field = value
            draw()
        }

    /** The picture's address. */
    var picture: String? = picture
        set(value) {
            field = value
            load()
        }

    var presence: Presence? = presence
        set(value) {
            field = value
            drawDot()
        }

    /** The dot for an offline person too, where no dot would say nothing. */
    var showOffline: Boolean = showOffline
        set(value) {
            field = value
            drawDot()
        }

    var shape: AvatarShape = shape
        set(value) {
            field = value
            draw()
        }

    /** The status of the app's own in its colour. */
    var presenceColor: Int? = presenceColor
        set(value) {
            field = value
            dot?.color = value
        }

    /** The ring round the dot: the surface by default. */
    var presenceRing: Int? = presenceRing
        set(value) {
            field = value
            dot?.ringColor = value
        }

    var letters: AvatarLetters? = letters
        set(value) {
            field = value
            draw()
        }

    /** The name is beside the face already: nothing for a screen reader to read twice. */
    var decorative: Boolean = decorative
        set(value) {
            field = value
            drawLabel()
        }

    private val dotSizeDp = dotSizeDp ?: max(6, (sizeDp * 0.28f).roundToInt())
    private val face = Face(context)
    private var dot: PresenceDot? = null

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    /** The words the face draws with no picture. */
    internal val lettersShown: String get() = letters?.text ?: CallsAvatars.initials(name ?: "")

    /** The dot, for a test. */
    internal val dotShown: PresenceDot? get() = dot

    /** The face itself, for a test: at the start of the line, the dot at its end. */
    internal val faceShown: View get() = face

    init {
        clipChildren = false
        clipToPadding = false
        addView(face, LayoutParams(dp(sizeDp.toFloat()), dp(sizeDp.toFloat()), Gravity.TOP or Gravity.START))
        draw()
        load()
        drawDot()
    }

    private fun draw() {
        face.radius = shape.radiusDp(sizeDp) * resources.displayMetrics.density
        face.ground = letters?.background ?: PresenceColors.hover(context)
        face.ink = letters?.color ?: PresenceColors.textTertiary(context)
        face.words = lettersShown
        face.invalidate()
        drawLabel()
    }

    private fun drawLabel() {
        importantForAccessibility = if (decorative) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_YES
        // The name, and the status where a dot says one.
        contentDescription = if (decorative) null else listOfNotNull(name, drawnDot?.said(context)).joinToString(", ")
    }

    /** The status the dot says, while it shows. */
    private val drawnDot: Presence?
        get() = presence?.takeIf { it != Presence.OFFLINE || showOffline }

    private fun drawDot() {
        val shown = drawnDot
        when {
            shown == null -> {
                dot?.let { removeView(it) }
                dot = null
            }
            dot != null -> dot?.presence = shown
            else -> {
                val added = PresenceDot(context, shown, dotSizeDp, presenceRing, presenceColor)
                added.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(added, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
                dot = added
            }
        }
        // At the corner the line ends on, a fifth of the dot and its ring out of the face, which leaves room for them.
        val out = if (shown == null) 0 else dp(dotSizeDp * 0.2f + 2f)
        (face.layoutParams as LayoutParams).apply {
            marginEnd = out
            bottomMargin = out
        }
        face.requestLayout()
        drawLabel()
    }

    private fun load() {
        val wanted = picture ?: ""
        face.picture = null
        face.invalidate()
        if (wanted.isEmpty()) return
        CallsAvatars.load(context, wanted) { bitmap ->
            // Another picture was asked for since.
            if (picture == wanted && bitmap != null) {
                face.picture = bitmap
                face.invalidate()
            }
        }
    }

    /** The picture, or the letters on their ground, in the shape. */
    private class Face(context: Context) : View(context) {
        var radius = 0f
        var ground = Color.TRANSPARENT
        var ink = Color.WHITE
        var words = ""
        var picture: Bitmap? = null

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = CallsTheme.SEMIBOLD
        }
        private val box = RectF()
        private val scale = Matrix()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            box.set(0f, 0f, width.toFloat(), height.toFloat())

            val bitmap = picture
            if (bitmap != null) {
                // As `object-fit: cover`.
                val by = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                scale.setScale(by, by)
                scale.postTranslate((width - bitmap.width * by) / 2f, (height - bitmap.height * by) / 2f)
                fill.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(scale) }
                canvas.drawRoundRect(box, radius, radius, fill)
                fill.shader = null
                return
            }

            fill.color = ground
            canvas.drawRoundRect(box, radius, radius, fill)
            text.color = ink
            text.textSize = height * 0.38f
            canvas.drawText(words, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2f, text)
        }
    }
}

/** Three dots that bounce in turn, still where the phone is asked to reduce motion. */
class TypingDots @JvmOverloads constructor(context: Context, small: Boolean = false) : LinearLayout(context) {
    private val dots: List<View>
    private var bounce: ValueAnimator? = null

    /** The colour of the dots: the line's. */
    var color: Int = Color.GRAY
        set(value) {
            field = value
            dots.forEach { (it.background as GradientDrawable).setColor(value) }
        }

    /** Whether they are moving, for a test. */
    internal val moving: Boolean get() = bounce?.isRunning == true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val density = resources.displayMetrics.density
        val side = ((if (small) 4 else 5) * density).roundToInt()
        val gap = ((if (small) 2 else 3) * density).roundToInt()
        // Room for the point and a half it travels either side of the line, which clipping there would cut off.
        val travel = (2 * density).roundToInt()
        setPadding(0, travel, 0, travel)
        clipToPadding = false
        dots = List(3) { index ->
            View(context).apply {
                alpha = 0.7f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(this@TypingDots.color)
                }
                addView(this, LayoutParams(side, side).apply { if (index > 0) marginStart = gap })
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A phone asked to reduce motion runs its animators at no time at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return

        val density = resources.displayMetrics.density
        // The web's: 1.2 seconds, a point and a half either side of the line, dimmer below it, each 0.15 after the last.
        bounce = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { animator ->
                val at = animator.animatedValue as Float
                dots.forEachIndexed { index, dot ->
                    val own = ((at - index * 0.125f) + 1f) % 1f
                    val high = EASE.getInterpolation(if (own < 0.5f) own * 2f else (1f - own) * 2f)
                    dot.translationY = (1.5f - 3f * high) * density
                    dot.alpha = 0.35f + 0.65f * high
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        bounce?.cancel()
        bounce = null
        super.onDetachedFromWindow()
    }

    private companion object {
        val EASE = AccelerateDecelerateInterpolator()
    }
}

/** Someone in the line: their name, or none for the activity alone. */
data class ActivityEntry(val name: String? = null, val activity: ActivityIndicator.Activity = ActivityIndicator.Activity.TYPING)

/**
 * The line that says who is typing, recording or sending a file: every activity gets its own part ("Anna, Bob typing,
 * Carol recording"), up to two names are spelled out and more become a count, and the icon is the one activity's, or the
 * typing dots once they mix. Hide it while nobody is: with nobody given it says "typing" alone, as the web's does.
 */
class ActivityIndicator @JvmOverloads constructor(
    context: Context,
    entries: List<ActivityEntry> = emptyList(),
    activity: Activity = Activity.TYPING,
    names: List<String> = emptyList(),
    compact: Boolean = false,
) : LinearLayout(context) {
    /** What someone is doing right now, as the line says it. Under this name, as `Activity` alone is Android's. */
    enum class Activity { TYPING, RECORDING, UPLOADING }

    /** Who, each with what they do. Empty, the line is [names] doing [activity]. */
    var entries: List<ActivityEntry> = entries
        set(value) {
            field = value
            draw()
        }

    var activity: Activity = activity
        set(value) {
            field = value
            draw()
        }

    var names: List<String> = names
        set(value) {
            field = value
            draw()
        }

    /** The smaller line. */
    var compact: Boolean = compact
        set(value) {
            field = value
            draw()
        }

    private val holder = FrameLayout(context)
    private val label = TextView(context)
    private var drawnIcon: Activity? = null
    private var drawnCompact: Boolean? = null

    /** The icon's view, for a test. */
    internal val iconView: View? get() = holder.getChildAt(0)

    /** The words, for a test. */
    internal val saidNow: String get() = label.text.toString()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.typeface = CallsTheme.MEDIUM
        addView(holder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        draw()
    }

    private val list: List<ActivityEntry>
        get() = when {
            entries.isNotEmpty() -> entries
            names.isNotEmpty() -> names.map { ActivityEntry(it, activity) }
            else -> listOf(ActivityEntry(null, activity))
        }

    private fun draw() {
        val list = list
        val said = said(context, list)
        val colour = PresenceColors.activity(context)
        label.text = said
        label.setTextColor(colour)
        label.textSize = if (compact) 12f else 14f
        contentDescription = said

        val icon = iconActivity(list)
        if (icon == drawnIcon && compact == drawnCompact) {
            (iconView as? TypingDots)?.color = colour
            return
        }
        drawnIcon = icon
        drawnCompact = compact

        holder.removeAllViews()
        val side = if (icon == Activity.UPLOADING) dp(12) else FrameLayout.LayoutParams.WRAP_CONTENT
        holder.addView(iconOf(icon, colour), FrameLayout.LayoutParams(side, side, Gravity.CENTER_VERTICAL))
    }

    private fun iconOf(activity: Activity, colour: Int): View = when (activity) {
        Activity.TYPING -> TypingDots(context, compact).apply { color = colour }
        Activity.RECORDING -> LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val red = CallsTheme.recording(context)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(red)
                }
            }, LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(4) })
            addView(glyph(CallsIcons.mic, red), LayoutParams(dp(12), dp(12)))
        }
        Activity.UPLOADING -> glyph(PAPERCLIP, colour)
    }

    private fun glyph(data: String, colour: Int): View = CallsIconView(context).apply {
        set(data)
        glyphColor = colour
    }

    companion object {
        /** The web's paperclip (realtime-ui's primitives/icons/chat.ts). */
        private const val PAPERCLIP = "m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"

        /**
         * What the line says, as realtime-ui's ActivityIndicator groups it. `%s` in the app's words (`typing_named`,
         * `recording_named`, `uploading_named`) is who, the words alone (`typing`, `recording`, `uploading`) for one person
         * with no name.
         */
        internal fun said(context: Context, list: List<ActivityEntry>): String =
            groups(list).joinToString(", ") { (activity, count, named) ->
                val who = if (named.isNotEmpty() && named.size <= 2) named.joinToString(", ") else count.toString()
                val withWho = named.isNotEmpty() || count > 1
                when (activity) {
                    Activity.RECORDING ->
                        if (withWho) CallsTheme.string(context, "recording_named", "%s recording").replace("%s", who)
                        else CallsTheme.string(context, "recording", "recording")
                    Activity.UPLOADING ->
                        if (withWho) CallsTheme.string(context, "uploading_named", "%s sending file").replace("%s", who)
                        else CallsTheme.string(context, "uploading", "sending file")
                    Activity.TYPING ->
                        if (withWho) CallsTheme.string(context, "typing_named", "%s typing").replace("%s", who)
                        else CallsTheme.string(context, "typing", "typing")
                }
            }

        /** The icon of the line: the one activity's, the typing dots once they mix. */
        internal fun iconActivity(list: List<ActivityEntry>): Activity =
            groups(list).singleOrNull()?.first ?: Activity.TYPING

        private fun groups(list: List<ActivityEntry>): List<Triple<Activity, Int, List<String>>> =
            Activity.entries.mapNotNull { activity ->
                val members = list.filter { it.activity == activity }
                if (members.isEmpty()) null
                else Triple(activity, members.size, members.map { (it.name ?: "").trim() }.filter { it.isNotEmpty() })
            }
    }
}
