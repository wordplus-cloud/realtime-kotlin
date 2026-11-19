package cloud.wordplus.realtime.ui.calls

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.os.Build
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * What a tile or the stage shows while its person's camera changes codec
 * ([CallsCameraSwitch]): their picture, blurred and dimmed, behind a slow
 * ring and the words, so a second without video reads as the call working on
 * it rather than as the camera going off. The web's tile and the iOS app's
 * `CallsOptimizingView` draw the same.
 *
 * It fades in and out rather than cutting, and TalkBack hears it once,
 * politely, through a live region of its own: the view's, not the words',
 * which are gone from a tile too small for them, so a small tile says it too,
 * and the words are no second voice for it. Once a change per person where a
 * grid shares its [said] among its tiles, since a person's tile can move to
 * another one while their camera changes.
 */
class CallsOptimizingView(context: Context) : FrameLayout(context) {
    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    /** A face shrunk to a few pixels and drawn back large is only colour: enough to say whose tile it is. */
    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }

    private val veil = View(context).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)) }

    private val ring = Ring(context)

    private val words = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.argb(235, 255, 255, 255))
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 2
        // The view says it, once: the words only show it.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    var isUp = false
        private set

    /**
     * Who has been told, by person, shared by a grid's tiles; null for a view
     * of its own, which says itself once each time it comes up.
     */
    var said: CallsOptimizingSaid? = null

    private var picture: Bitmap? = null

    init {
        setBackgroundColor(CallsTheme.TILE)
        alpha = 0f
        visibility = View.GONE
        isClickable = false
        isFocusable = false

        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(veil, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ring, LayoutParams(0, 0))
        addView(words, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top

        backdrop.layout(0, 0, width, height)
        veil.layout(0, 0, width, height)

        // A ring that keeps to the tile: a quarter of its shorter side at most,
        // so a small grid tile still has room for the words.
        val side = min(width, height).toFloat()
        val diameter = max(dp(18f), min(dp(36f), side * 0.22f)).toInt()
        val showsWords = side >= dp(96f)
        val labelHeight = if (showsWords) dp(36f).toInt() else 0
        val gap = if (showsWords) dp(10f).toInt() else 0
        val top0 = (height - (diameter + gap + labelHeight)) / 2

        ring.layout((width - diameter) / 2, top0, (width + diameter) / 2, top0 + diameter)

        words.visibility = if (showsWords) View.VISIBLE else View.GONE
        val pad = dp(8f).toInt()
        words.layout(pad, top0 + diameter + gap, width - pad, top0 + diameter + gap + labelHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val labelHeight = dp(36f).toInt()

        words.measure(
            MeasureSpec.makeMeasureSpec(max(0, width - dp(16f).toInt()), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(labelHeight, MeasureSpec.EXACTLY)
        )
    }

    /** Up with [face] behind it (null: the tile's own colour), or down. [key] is whose camera it is, for [said]. */
    fun show(up: Boolean, face: Bitmap?, key: String? = null) {
        val text = CallsTheme.string(context, "optimizing_video", "Optimizing video quality...")

        if (face !== picture) {
            picture = face
            backdrop.setImageBitmap(face?.let { blurred(it) })
            backdrop.visibility = if (face != null) View.VISIBLE else View.GONE
        }

        if (up == isUp) return

        isUp = up
        animate().cancel()

        if (up) {
            // Said as it comes up, by the view's own live region, whatever the
            // tile's size: not where another tile of the same person said it.
            val first = key?.let { said?.first(it) } ?: true
            accessibilityLiveRegion =
                if (first) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
            visibility = View.VISIBLE
            contentDescription = text
            words.text = text
            ring.spin(true)

            animate().alpha(1f).setDuration(250).start()
        } else {
            // Nothing to say as it goes.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            contentDescription = null

            animate().alpha(0f).setDuration(350).withEndAction {
                if (isUp) return@withEndAction

                visibility = View.GONE
                ring.spin(false)
                words.text = ""
            }.start()
        }
    }

    /** A soft blur that costs nothing on any version: the picture at a few pixels, filtered back up. */
    private fun blurred(face: Bitmap): Bitmap? = try {
        Bitmap.createScaledBitmap(face, 12, 12, true)
    } catch (e: Throwable) {
        null
    }

    /** A faint full circle with a short bright arc turning round it, eased so it breathes rather than whirs. */
    private class Ring(context: Context) : View(context) {
        private val stroke = 2.5f * context.resources.displayMetrics.density

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(46, 255, 255, 255)
        }

        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(230, 255, 255, 255)
        }

        private val bounds = RectF()
        private var angle = 0f

        private val turn = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
        }

        fun spin(on: Boolean) {
            // Nothing turns where the person asked for no animations: the
            // animator says so itself (the system's animator duration scale).
            val animating = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

            if (on && animating) {
                if (!turn.isStarted) turn.start()
            } else {
                turn.cancel()
                angle = 0f
                invalidate()
            }
        }

        override fun onDetachedFromWindow() {
            turn.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val inset = stroke / 2
            bounds.set(inset, inset, width - inset, height - inset)

            canvas.drawOval(bounds, track)
            canvas.drawArc(bounds, angle - 90f, 100f, false, arc)
        }
    }
}

/**
 * Whose camera changing codec has been said, by person, for a grid's tiles to
 * share: said once a change, whichever of them shows it, until the grid sees
 * that person's change over ([keep]). On the main thread.
 */
class CallsOptimizingSaid {
    private val said = HashSet<String>()

    /** Whether [key] is still to be told of, counted as told from now. */
    fun first(key: String): Boolean = said.add(key)

    /** Only these are still changing: anyone else is told of their next change. */
    fun keep(keys: Set<String>) {
        said.retainAll(keys)
    }
}
