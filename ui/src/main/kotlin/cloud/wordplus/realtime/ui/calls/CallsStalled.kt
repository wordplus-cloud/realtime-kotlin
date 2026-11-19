package cloud.wordplus.realtime.ui.calls

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * What a tile or the stage shows over a picture that stopped arriving while
 * its sender is still in the call ([CallsStallWatch]): the last frame stays,
 * blurred and dimmed so it doesn't pass for live, as the web's
 * `bm-call-video-stalled` draws it, and over a camera that alone stopped,
 * "Video interrupted" in the glass chip the web's paused notice wears. The
 * stage of a call between two says why as well: "Their video stopped
 * arriving. It comes back by itself." It comes back sharp with the next frame
 * the room hears. The iOS app's `CallsStalledView` draws the same.
 *
 * A `SurfaceView` draws outside the view tree, so nothing laid over it can
 * blur it: its last frame is copied off the surface instead ([PixelCopy]), a
 * few pixels across, and drawn back over it large, which is the blur, softened
 * further where the system can (Android 12 on). It lies over its [source]
 * wherever that is, a dragged corner tile included, and never takes a touch.
 */
class CallsStalledView(context: Context, private val main: Boolean = false) : FrameLayout(context) {
    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    private val handler = Handler(Looper.getMainLooper())

    private val still = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
    }

    /** Dimmed as the web's `brightness(0.62)`, darker still where no frame could be copied. */
    private val dim = View(context)

    /** The web's paused notice: a 45% veil under its chip. */
    private val veil = View(context).apply {
        setBackgroundColor(Color.argb(115, 0, 0, 0))
        alpha = 0f
    }

    private val icon = CallsIconView(context).apply {
        set(CallsIcons.videoOff)
        glyphColor = Color.WHITE
    }

    private val title = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        maxLines = if (main) 3 else 2
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val why = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.argb(217, 255, 255, 255))
        visibility = if (main) View.VISIBLE else View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** `.bm-call-paused-notice`: 6 by 10 of padding and a 16dp glyph on a tile, 10 by 14 and 22dp on the stage. */
    private val chip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        alpha = 0f
        setPadding(dp(if (main) 14f else 10f), dp(if (main) 10f else 6f), dp(if (main) 14f else 10f), dp(if (main) 10f else 6f))
        background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(Color.argb(140, 0, 0, 0))
            setStroke(1, Color.argb(20, 255, 255, 255))
        }

        val glyph = dp(if (main) 22f else 16f)

        addView(icon, LinearLayout.LayoutParams(glyph, glyph))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(why)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(if (main) 10f else 6f)
            }
        )
    }

    /** The picture this lies over, a sibling. */
    private var source: SurfaceView? = null

    var isBlurred = false
        private set

    private var noticed = false

    /** Which copy is wanted: an older one landing after a newer ask, or after the blur went, is dropped. */
    private var copying = 0

    /** Where the source was last laid out, to follow one that moves. */
    private var placed: android.graphics.Rect? = null

    private val follow = ViewTreeObserver.OnPreDrawListener {
        if (isBlurred && sourceRect() != placed) requestLayout()
        true
    }

    init {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        addView(still)
        addView(dim)
        addView(veil)
        addView(chip)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(follow)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        super.onDetachedFromWindow()
    }

    /** The source's rectangle in this view, the whole of it without one. */
    private fun sourceRect(): android.graphics.Rect {
        val from = source

        if (from == null || from.parent !== parent || from.width <= 0 || from.height <= 0) {
            return android.graphics.Rect(0, 0, width, height)
        }

        val left = (from.x - x).toInt()
        val top = (from.y - y).toInt()

        return android.graphics.Rect(left, top, left + from.width, top + from.height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        setMeasuredDimension(width, height)

        val box = sourceRect().takeIf { source != null } ?: android.graphics.Rect(0, 0, width, height)
        val exactly = { size: Int -> MeasureSpec.makeMeasureSpec(max(0, size), MeasureSpec.EXACTLY) }

        for (view in listOf(still, dim, veil)) view.measure(exactly(box.width()), exactly(box.height()))

        // at most 420 wide on the stage, the tile's width less 16 on a tile, and the glyph alone where the words don't fit
        val most = if (main) min(box.width() - dp(32f), dp(420f)) else box.width() - dp(16f)
        val words = most - chip.paddingLeft - chip.paddingRight - dp(if (main) 32f else 22f) >= dp(48f)

        title.visibility = if (words) View.VISIBLE else View.GONE
        why.visibility = if (words && main) View.VISIBLE else View.GONE

        chip.measure(
            MeasureSpec.makeMeasureSpec(max(0, most), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val box = sourceRect()

        placed = box

        for (view in listOf(still, dim, veil)) {
            if (view.measuredWidth != box.width() || view.measuredHeight != box.height()) {
                view.measure(
                    MeasureSpec.makeMeasureSpec(max(0, box.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(max(0, box.height()), MeasureSpec.EXACTLY)
                )
            }

            view.layout(box.left, box.top, box.right, box.bottom)
        }

        val chipLeft = box.left + (box.width() - chip.measuredWidth) / 2
        val chipTop = box.top + (box.height() - chip.measuredHeight) / 2

        chip.layout(chipLeft, chipTop, chipLeft + chip.measuredWidth, chipTop + chip.measuredHeight)
    }

    /**
     * Blurred, over [from], or sharp again, and "Video interrupted" over it
     * or not: only over a blurred picture.
     */
    fun show(blurred: Boolean, notice: Boolean, from: SurfaceView?) {
        val notice = blurred && notice
        val words = CallsTheme.string(context, "video_interrupted", "Video interrupted")
        val because = CallsTheme.string(context, "video_interrupted_why", "Their video stopped arriving. It comes back by itself.")

        title.text = words
        why.text = because

        if (notice != noticed) {
            noticed = notice

            // the web's chip is a status: said once as it comes up, politely
            chip.contentDescription = if (notice) (if (main) "$words. $because" else words) else null
            chip.importantForAccessibility = if (notice) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            chip.accessibilityLiveRegion = if (notice) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE

            chip.animate().cancel()
            veil.animate().cancel()
            chip.animate().alpha(if (notice) 1f else 0f).setDuration(300).start()
            veil.animate().alpha(if (notice) 1f else 0f).setDuration(300).start()

            requestLayout()
        }

        // another picture under it now: its own last frame
        val moved = blurred && from !== source

        if (moved) source = from

        if (blurred == isBlurred) {
            if (moved) {
                copy()
                requestLayout()
            }

            return
        }

        isBlurred = blurred
        animate().cancel()

        if (blurred) {
            visibility = View.VISIBLE
            alpha = 0f
            copy()
            requestLayout()

            animate().alpha(1f).setDuration(300).start()
        } else {
            copying++

            animate().alpha(0f).setDuration(200).withEndAction {
                if (isBlurred) return@withEndAction

                visibility = View.GONE
                source = null
                still.setImageDrawable(null)
                still.visibility = View.GONE
            }.start()
        }
    }

    /** The source's last frame, a few pixels across, behind the dim; the dim alone, darker, where it can't be had. */
    private fun copy() {
        val asked = ++copying
        val from = source

        still.visibility = View.GONE
        dim.setBackgroundColor(Color.argb(190, 0, 0, 0))

        if (from == null || from.width <= 0 || from.height <= 0 || !from.holder.surface.isValid) return

        val across = 32
        val wide = from.width >= from.height
        val bitmap = Bitmap.createBitmap(
            if (wide) across else max(1, across * from.width / from.height),
            if (wide) max(1, across * from.height / from.width) else across,
            Bitmap.Config.ARGB_8888
        )

        try {
            PixelCopy.request(from, bitmap, { result ->
                if (asked != copying || !isBlurred || result != PixelCopy.SUCCESS) return@request

                still.setImageBitmap(bitmap)
                still.visibility = View.VISIBLE

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val radius = dp(12f).toFloat()

                    still.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                }

                dim.setBackgroundColor(Color.argb(97, 0, 0, 0))
            }, handler)
        } catch (e: IllegalArgumentException) {
            // the surface went between the look and the ask
        }
    }
}
