package cloud.wordplus.realtime.ui.calls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.graphics.PathParser

/**
 * The same glyphs the messenger draws in a browser, and the same ones the iOS
 * call screen draws: one registry of Lucide path data on a 24 grid, split on
 * `|` into subpaths.
 *
 * This screen used to draw emoji - a microphone, a speaker, a pair of
 * scissors-looking arrows - so the same call looked like a different product
 * on Android than it did in the browser or on an iPhone. The strings below are
 * lifted from `CallsIcon.swift`, which lifts them from `icon.tsx`; a glyph
 * that changes in one place has to change in all three, and being identical is
 * how that stays visible.
 */
object CallsIcons {
    const val phone = "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.12.94.34 1.86.66 2.74a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.34-1.34a2 2 0 0 1 2.11-.45c.88.32 1.8.54 2.74.66A2 2 0 0 1 22 16.92Z"
    const val mic = "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3zM19 11v1a7 7 0 0 1-14 0v-1M12 19v3M8 22h8"
    const val micOff = "M12 19v3|M8 22h8|m2 2 20 20|M18.89 13.23A7.12 7.12 0 0 0 19 12v-2|M5 10v2a7 7 0 0 0 12 5|M15 9.34V5a3 3 0 0 0-5.68-1.33|M9 9v3a3 3 0 0 0 5.12 2.12"
    const val video = "m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5|M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-10a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"
    const val videoOff = "M10.66 6H14a2 2 0 0 1 2 2v2.5l5.248-3.494A.5.5 0 0 1 22 7.435v8.13a.5.5 0 0 1-.752.43L16 13.5|M16 16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2|m2 2 20 20"
    const val pip = "M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4|M12 13h8a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"
    const val screenShare = "M13 3H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-3|M8 21h8|M12 17v4|m17 8 5-5|M17 3h5v5"
    const val screenShareOff = "M13 3H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-3|M8 21h8|M12 17v4|m22 3-5 5|m17 3 5 5"
    const val settings = "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915|M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
    const val switchCamera = "M11 19H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5|M13 5h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-5|M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z|m18 22-3-3 3-3|m6 2 3 3-3 3"
    const val speaker = "M11 5 6 9H2v6h4l5 4V5zM15.5 8.5a5 5 0 0 1 0 7M19 5a9 9 0 0 1 0 14"
    const val speakerOff = "M11 5 6 9H2v6h4l5 4V5z|m23 9-6 6|m17 9 6 6"
    const val users = "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2|M16 3.128a4 4 0 0 1 0 7.744|M22 21v-2a4 4 0 0 0-3-3.87|M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"
    const val flip = "M12 3v18|M9 7 4 12l5 5V7zM15 7l5 5-5 5V7z"
    const val alert = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z|M12 8v4|M12 16h.01"
    const val wifiOff = "M12 20h.01|M8.5 16.429a5 5 0 0 1 7 0|M5 12.859a10 10 0 0 1 5.17-2.69|M19 12.859a10 10 0 0 0-2.007-1.523|M2 8.82a15 15 0 0 1 4.177-2.643|M22 8.82a15 15 0 0 0-11.288-3.764|m2 2 20 20"
    const val swapVideos = "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8|M21 3v5h-5|M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16|M8 16H3v5"
    const val chevronDown = "m6 9 6 6 6-6"
    const val raiseHand = "M7 11V5a2 2 0 1 1 4 0v6M11 11V3a2 2 0 1 1 4 0v8M15 11V5a2 2 0 1 1 4 0v9a8 8 0 0 1-8 8H9a4 4 0 0 1-4-4l-2-5 1.5-1.5L7 13"

    /** A microphone a moderator keeps off: a speaker crossed out, the web's `mute`. */
    const val silenced = "M11 4.702a.7.7 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.7.7 0 0 0 11 19.298z|m16.5 14.5 5-5|m16.5 9.5 5 5"

    /** The moderation menus' glyphs, the web's own (icon.tsx): Host Controls is the shield. */
    const val shield = "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z|m9 12 2 2 4-4"
    const val padlock = "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zM7 11V7a5 5 0 0 1 10 0v4"
    const val megaphone = "m3 11 18-5v12L3 14v-3z|M11.6 16.8a3 3 0 1 1-5.8-1.6"
    const val pin = "M12 17v5M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"
    const val pinOff = "M12 17v5|M15 9.34V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H7.89|m2 2 20 20|M9 9v1.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h11"
    const val arrowUp = "M12 19V5|m5 12 7-7 7 7"
    const val arrowDown = "M12 5v14|m19 12-7 7-7-7"
    const val userX = "M2 21a8 8 0 0 1 13.292-6|M10 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10z|m17 17 5 5|m22 17-5 5"

    /** A tile's ⋯, which opens its person's menu for a moderator: the web's `more`. */
    const val more = "M12 13a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm0-7a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm0 14a1 1 0 1 1 0-2 1 1 0 0 1 0 2z"

    /**
     * Leaving a room, which is what ending a group call is — the web's
     * `bm-exit-room` uses this glyph rather than the handset.
     */
    const val logout = "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4|m16 17 5-5-5-5|M21 12H9"

    /** Device Settings, the join notice, a moderator's toasts and Host Controls' switches: the web's own. */
    const val play = "M6 3l14 9-14 9V3z"
    const val check = "M20 6 9 17l-5-5"
    const val close = "M18 6 6 18M6 6l12 12"
    const val bell = "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M13.7 21a2 2 0 0 1-3.4 0"
    const val userPlus = "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2|M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z|M19 8v6|M22 11h-6"

    /** Show Chat / Hide Chat, the dock's button for the conversation beside the call: the web's `thread`. */
    const val thread = "M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"

    /** A recording: the web's `record`, a ring round a dot, the dot a small ring its stroke fills. */
    const val record = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z|M12 10.8a1.2 1.2 0 1 0 0 2.4 1.2 1.2 0 0 0 0-2.4z"

    /** The spotlight for everyone: the web's `star`, on a spotlighted tile's label and the moderator's rows. */
    const val star = "m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"

    private val cache = HashMap<String, Path>()

    /** The glyph on the 24 grid, unscaled. Cached: a dock redraws often. */
    @Synchronized
    fun path(data: String): Path {
        cache[data]?.let { return it }

        val built = Path()

        for (piece in data.split("|")) {
            if (piece.isBlank()) continue

            try {
                built.addPath(PathParser.createPathFromPathData(piece))
            } catch (e: Exception) {
                // A glyph that will not parse is a glyph that is not drawn.
                // It is not a reason to take the call screen down.
            }
        }

        cache[data] = built

        return built
    }
}

/** One glyph, stroked, sized to its own bounds. */
class CallsIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val scaled = Path()
    private val matrix = Matrix()

    private var data = ""
    private var drawnWidth = 0
    private var drawnHeight = 0

    var glyphColor: Int = Color.WHITE
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    /**
     * How much of the view the glyph fills, 1 being all of it.
     *
     * A dock control wants half: that is the proportion iOS constrains its
     * own glyph to, and what the web's dock draws. It belongs here, as a
     * scale on the path, rather than in a margin round this view — a button
     * only learns its size during layout, and mutating a child's LayoutParams
     * from `onSizeChanged` lands a pass late and leaves the glyphs at
     * assorted sizes. That is exactly how the flip control came out a third
     * of its neighbours with a stroke to match.
     */
    var fill: Float = 1f
        set(value) {
            if (field == value) return

            field = value
            drawnWidth = 0

            rescale()
            invalidate()
        }

    fun set(data: String) {
        if (data == this.data) return

        this.data = data
        drawnWidth = 0

        rescale()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rescale()
    }

    private fun rescale() {
        if (width <= 0 || height <= 0 || data.isEmpty()) return
        if (drawnWidth == width && drawnHeight == height) return

        drawnWidth = width
        drawnHeight = height

        // The same 24 grid and the same 1.85 stroke as the iOS screen, so a
        // control is the same weight on both phones.
        val side = minOf(width, height).toFloat() * fill
        val scale = side / 24f

        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - side) / 2f, (height - side) / 2f)

        CallsIcons.path(data).transform(matrix, scaled)

        paint.strokeWidth = 1.85f * scale
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (data.isEmpty()) return

        rescale()

        canvas.drawPath(scaled, paint)
    }
}
