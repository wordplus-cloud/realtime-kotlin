package cloud.wordplus.realtime.ui.calls

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One person, one cell.
 *
 * This used to give a screen sharer two — a comment claimed the web did the
 * same, and it does not: `group-video-grid-item.tsx` renders one tile per
 * participant and puts the screen in it, with their camera inset in the
 * corner. Two tiles for one person also cost a place in a grid that has very
 * few of them on a phone.
 */
private data class Cell(val person: CallsRoomParticipant)

/**
 * A call with more than two people in it.
 *
 * Two shapes, because the web has two. A **video** room is a wall of pictures
 * laid out by `group-video-grid.tsx`'s own rules; an **audio** room has no
 * pictures to show, so the web draws a list instead — a row per person, avatar,
 * name and their microphone — and fifty tall empty rectangles would be a worse
 * answer to the same question.
 *
 * Tiles are reused rather than rebuilt: a renderer is an EGL surface, and
 * churning them on every roster change is how a grid starts to flicker.
 */
class CallsGrid(context: Context, private val egl: EglBase) : FrameLayout(context) {
    /** The ⋯ on a tile, or a long-press on it: a moderator's menu for that person. */
    var onPerson: ((CallsRoomParticipant) -> Unit)? = null

    /** The pin on a tile: Pin for Me for that person, anyone's ([CallsSpotlight]). */
    var onPin: ((CallsRoomParticipant) -> Unit)? = null

    /**
     * Who a moderator put in the spotlight for everyone, by identity, in order: the main tiles, each labeled Spotlight
     * ([CallsSpotlight]). Drawn again at once.
     */
    var spotlight: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            if (shownPeople.isNotEmpty()) show(shownPeople)
        }

    /** This user's own pins (Pin for Me), by identity: the main tiles while nobody is in the spotlight. */
    var pinned: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            if (shownPeople.isNotEmpty()) show(shownPeople)
        }

    /**
     * Whether a tile offers Pin for Me: a group video call's tiles, its pin shown once the tile is tapped and while it is
     * pinned, as the web's on a phone.
     */
    var pinsOffered = false
        set(value) {
            if (field == value) return
            field = value
            rebind()
        }

    /**
     * The one person drawn, in a group call's corner window ([CallsPipFocus]), by identity: their tile fills the grid. Null
     * for everyone, and the grid's own order.
     */
    var solo: String? = null
        set(value) {
            if (field == value) return
            field = value
            offset = 0f
            if (shownPeople.isNotEmpty()) show(shownPeople)
        }

    /** The shape of someone's picture, once a frame has said: null before, and while they send none. */
    fun aspectOf(identity: String?): Float? =
        tiles.firstOrNull { it.person?.identity == identity && (it.person?.video != null || it.person?.screen != null) }
            ?.pictureAspect?.takeIf { it > 0f }

    /** Who the grid was last given. */
    private var shownPeople: List<CallsRoomParticipant> = emptyList()

    /** Whose menu is open, by identity: their tile is outlined. */
    var moderating: String? = null
        set(value) {
            if (field == value) return
            field = value
            tiles.forEach { it.moderating = value != null && it.person?.identity == value }
        }

    /**
     * Where a person's menu opens: the ⋯ on their tile while it shows, else the tile; null when they have no tile.
     * Asked again as the grid moves people about, so the menu stays with the person rather than with a tile.
     */
    fun manageAnchor(identity: String): View? {
        val tile = tiles.firstOrNull { it.person?.identity == identity } ?: return null

        return if (tile.manage.visibility == View.VISIBLE) tile.manage else tile
    }

    /** A plain tap anywhere on the grid, which belongs to the screen this is
     *  drawn on rather than to any tile. */
    var onTap: (() -> Unit)? = null

    companion object {
        /**
         * What a tile is shaped for when nothing has said otherwise.
         *
         * Every camera in a call publishes at it and a laptop's shared screen
         * is near enough, so it stood in for every picture here. A phone
         * sharing its own screen is about 9:19.5 — taller than it is wide —
         * and there is no figure that covers both, so this is now the fallback
         * and the tiles measure what they are actually drawing: see
         * `Tile.pictureAspect`.
         */
        const val TILE_RATIO = 1.77f

        /** The shortest a strip, or the narrowest a column, is allowed to be.
         *  The web's `minSecondaryHeight`. */
        const val MIN_STRIP = 100

        /** The web's gutters: its grid's padding, and each tile's inside its cell. */
        const val GUTTER = 4
    }

    /** Draw the list rather than the wall. */
    var audioRoom = false
        set(value) {
            if (field == value) return
            field = value
            tiles.forEach { it.isRow = value }
            offset = 0f
            requestLayout()
        }

    /**
     * Whether the room is in broadcast mode. It decides who is pulled out
     * large: the web's grid puts whoever may publish in the primary row while
     * a room is broadcasting, and pinned participants at any time.
     */
    var broadcastRoom = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class Tile(context: Context, private val egl: EglBase) : FrameLayout(context) {
        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        private fun sp(value: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

        /** The initial's size in pixels, set only when it changes: it is asked on every measure. */
        private fun initialSize(px: Float) {
            if (initial.textSize != px) initial.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        }

        /**
         * An EGL surface each, and an audio room holds fifty people. A tile
         * that never shows a picture never makes one: the renderer arrives
         * with the first track and not before.
         */
        private var renderer: SurfaceViewRenderer? = null

        /**
         * Someone sharing their screen is still a face worth seeing, so the
         * web insets their camera in the corner of the same tile
         * (`.bm-small-video`: 72dp tall, 8dp in, a hairline of white).
         */
        private var insetRenderer: SurfaceViewRenderer? = null

        /**
         * The picture once it has loaded, as its background, and until then, or for good where there is none or it
         * can't be drawn, the initials on a disc of the accent ([disc]), as the call screen of one person draws them.
         * The bare letter on the tile read as a picture that had failed.
         */
        val initial = TextView(context).apply {
            textSize = 28f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = disc()
        }

        private fun disc() = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CallsTheme.accent(context))
        }

        val label = TextView(context).apply {
            textSize = 12f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        /**
         * The glass pill behind the name on a tile, over a picture too, as the
         * web's `.bm-info-name` is: who is who stays readable on any video.
         */
        val namePill = View(context).apply {
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(Color.argb(140, 0, 0, 0))
            }
        }

        /**
         * The tile whose menu is open, outlined in the accent
         * (`.bm-moderating`): the menu itself may sit away from the tile.
         */
        val outline = View(context).apply {
            visibility = View.GONE
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), CallsTheme.accent(context))
            }
        }

        /** Whether this tile's menu is open. */
        var moderating = false
            set(value) {
                if (field == value) return
                field = value
                outline.visibility = if (value) View.VISIBLE else View.GONE
            }

        /**
         * A raised hand breathes, as the web's chip does (`bm-call-hand`: 1 to
         * 1.12 and back over a second and a half), for as long as it is up.
         */
        private var breathing: ObjectAnimator? = null

        /**
         * The web draws each of these as a *chip* — a coloured disc with a
         * stroked glyph in it — rather than a bare mark: a red one for a
         * microphone that is off, a translucent green one for one that is on,
         * and an amber one for a raised hand.
         */
        val micChip = FrameLayout(context).apply { visibility = View.GONE }
        val mic = CallsIconView(context).apply { set(CallsIcons.micOff) }
        val handChip = FrameLayout(context).apply { visibility = View.GONE }
        val hand = CallsIconView(context).apply { set(CallsIcons.raiseHand) }

        /**
         * The ⋯ that opens the person's menu, on every tile a moderator has
         * something to do with: the web's `.bm-manage-participant`, always
         * shown where there is no hover to reveal it. A long-press opens the
         * same menu.
         */
        val manage = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            contentDescription = CallsTheme.string(context, "manage_participant", "Manage Participant")
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 0, 0, 0))
                setStroke(1, Color.argb(36, 255, 255, 255))
            }
        }
        val more = CallsIconView(context).apply {
            set(CallsIcons.more)
            glyphColor = Color.WHITE
        }
        var onManage: (() -> Unit)? = null

        /**
         * Pin for Me, anyone's, beside the ⋯: the web's `.wplus-pin-for-me`, on a phone shown for a moment once the tile
         * is tapped, and latched in the accent while the tile is pinned, as a pin waiting for a spotlight to end is pinned
         * all the same.
         */
        private val pinGround = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val pin = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            background = pinGround
        }
        val pinIcon = CallsIconView(context).apply { set(CallsIcons.pin) }
        var onPin: (() -> Unit)? = null
        private var tapped = false
        private var tappedFor: String? = null
        private val tappedEnds = Runnable { showTapped(false) }
        private var pinsOffered = false
        private var pinnedHere = false

        /**
         * A spotlighted tile's label, beside the name, for everyone: the web's `.wplus-call-spotlight-label`, a star and
         * the word on the accent.
         */
        val spotlightPill = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = false
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(CallsTheme.withAlpha(CallsTheme.accent(context), 235))
            }
        }
        val spotlightStar = CallsIconView(context).apply {
            set(CallsIcons.star)
            glyphColor = CallsTheme.color(context, "text-on-accent", Color.WHITE)
        }
        val spotlightLabel = TextView(context).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(CallsTheme.color(context, "text-on-accent", Color.WHITE))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            includeFontPadding = false
        }
        private var spotlit = false

        /** `.bm-viewer-badge`: a glass pill with the word as it is written. */
        val viewer = TextView(context).apply {
            textSize = 11f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.argb(209, 255, 255, 255))
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.argb(140, 0, 0, 0))
            }
        }

        /**
         * Over the whole tile while its person is away for a moment and held
         * for a return: the web's `.bm-call-reconnecting`, a 45% black veil
         * with the word in the middle.
         */
        val reconnecting = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.argb(115, 0, 0, 0))
            visibility = View.GONE
        }

        /**
         * Over the picture while the person's camera changes codec
         * ([CallsCameraSwitch]), until the room hears the frame that ends
         * it: the web's "Optimizing video quality..." tile, in place of a
         * camera that looks turned off for a second.
         */
        val optimizing = CallsOptimizingView(context)

        /**
         * Over the picture, and over the camera in the corner, while it
         * stopped arriving ([CallsStallWatch]): its last frame blurred, and
         * "Video interrupted" over a camera that alone stopped.
         */
        val stalled = CallsStalledView(context)
        val insetStalled = CallsStalledView(context)

        /** The person's picture as it loaded, for the placeholder's backdrop. */
        private var face: Bitmap? = null

        var isRow = false
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        private var track: VideoTrack? = null
        private var insetTrack: VideoTrack? = null

        var person: CallsRoomParticipant? = null
            private set

        /** Whether this tile is drawing a shared screen. The grid reads it:
         *  a sharing row takes the stage, and the plain ones stay rows. */
        var showsScreen = false
            private set

        private var avatarUrl = ""

        /**
         * Width over height of the picture on show, once a frame has said —
         * zero until then, which means "no better answer than `TILE_RATIO`".
         *
         * The grid reads it too. A shape is not known when the tile is built:
         * it arrives with the first frame, and for a phone turned over
         * mid-call it arrives again. The iOS tile learns the same thing from
         * the SDK's `didUpdateDimensions`.
         */
        var pictureAspect = 0f
            private set

        private fun renderer(): SurfaceViewRenderer {
            renderer?.let { return it }

            val made = SurfaceViewRenderer(context).apply {
                init(egl.eglBaseContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}

                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        // Guarded: this runs on libwebrtc's delivery thread, and
                        // a throw returns through its JNI bridge, which aborts
                        // the process rather than reporting anything.
                        try {
                            val sideways = rotation == 90 || rotation == 270
                            val wide = if (sideways) height else width
                            val tall = if (sideways) width else height

                            if (wide <= 0 || tall <= 0) return

                            val ratio = wide.toFloat() / tall.toFloat()

                            post {
                                if (pictureAspect == ratio) return@post

                                pictureAspect = ratio
                                requestLayout()
                            }
                        } catch (e: Throwable) {
                            CallsLog.write("tile resolution callback: ${e.javaClass.simpleName}")
                        }
                    }
                })
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

                // See the stage in `CallsActivity`: the scaler pins the
                // surface to the incoming frame size and reallocates it on
                // every resolution change, which shows as the picture going
                // narrow for a moment and snapping back.
                setEnableHardwareScaler(false)
            }

            renderer = made

            addView(made, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            return made
        }

        private fun insetRenderer(): SurfaceViewRenderer {
            insetRenderer?.let { return it }

            val made = SurfaceViewRenderer(context).apply {
                init(egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(true)
            }

            insetRenderer = made

            addView(made, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            // After it: a surface clears whatever was drawn before it where it lies.
            insetStalled.bringToFront()

            return made
        }

        init {
            micChip.addView(mic)
            handChip.addView(hand)

            // over the renderer, which always goes in first
            addView(stalled)
            addView(insetStalled)
            addView(initial)
            addView(optimizing)
            addView(namePill)
            addView(label)
            addView(handChip)
            addView(micChip)
            addView(viewer)
            addView(reconnecting)
            addView(outline)

            // A clickable child of a clickable tile: the press on the ⋯ is the
            // ⋯'s, and neither the tile's tap (the dock) nor its long-press
            // hears it.
            manage.addView(more)
            manage.setOnClickListener { onManage?.invoke() }
            addView(manage)

            // The same for the pin: its press is Pin for Me's alone.
            spotlightPill.addView(spotlightStar)
            spotlightPill.addView(spotlightLabel)
            addView(spotlightPill)
            pin.addView(pinIcon)
            pin.setOnClickListener { onPin?.invoke() }
            addView(pin)
        }

        /**
         * The web's tapped tile on a phone: its pin shows for four seconds, and a second tap puts it away. A group video
         * call's alone (`setPins`).
         */
        fun tapped() {
            if (pinsOffered) showTapped(!tapped)
        }

        private fun showTapped(on: Boolean) {
            removeCallbacks(tappedEnds)
            tapped = on
            tappedFor = if (on) person?.identity else null

            if (on) postDelayed(tappedEnds, 4000)

            drawPin()
            requestLayout()
        }

        /** Whether Pin for Me is offered here and this tile is pinned, from the grid. */
        fun setPins(offered: Boolean, pinned: Boolean) {
            pinsOffered = offered && !isRow
            pinnedHere = pinned

            // A tap was for the person it was on: the tile handed to someone else as the grid reorders forgets it.
            if (tapped && (!pinsOffered || tappedFor != person?.identity)) showTapped(false)

            drawPin()
        }

        /**
         * The pin, shown once the tile is tapped, while it is pinned, or always for TalkBack, which has no tap to show it
         * with; latched in the accent while pinned.
         */
        private fun drawPin() {
            val talkBack = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager)?.isTouchExplorationEnabled == true
            val shown = pinsOffered && (tapped || pinnedHere || talkBack)

            if ((pin.visibility == View.VISIBLE) != shown) {
                pin.visibility = if (shown) View.VISIBLE else View.GONE
                requestLayout()
            }

            pinGround.setColor(if (pinnedHere) CallsTheme.accent(context) else Color.argb(140, 0, 0, 0))
            pinGround.setStroke(1, if (pinnedHere) Color.TRANSPARENT else Color.argb(36, 255, 255, 255))
            pinIcon.set(if (pinnedHere) CallsIcons.pinOff else CallsIcons.pin)
            pinIcon.glyphColor = if (pinnedHere) CallsTheme.color(context, "text-on-accent", Color.WHITE) else Color.WHITE
            pin.contentDescription = if (pinnedHere) {
                CallsTheme.string(context, "unpin_for_me", "Unpin for Me")
            } else {
                CallsTheme.string(context, "pin_for_me", "Pin for Me")
            }
            pin.isSelected = pinnedHere
        }

        /** Labeled Spotlight, for everyone: a video tile alone, as an audio roster has no main tiles. */
        fun setSpotlit(on: Boolean) {
            val next = on && !isRow

            spotlightLabel.text = CallsTheme.string(context, "spotlight", "Spotlight")

            if (next == spotlit) return

            spotlit = next
            spotlightPill.visibility = if (next) View.VISIBLE else View.GONE
            requestLayout()
        }

        /** The hand's breath on or off; nothing moves where the person asked for no animations. */
        private fun breathe(on: Boolean) {
            val animating = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

            if (on && animating) {
                if (breathing != null) return

                breathing = ObjectAnimator.ofPropertyValuesHolder(
                    handChip,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
                ).apply {
                    duration = 750
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }

                return
            }

            breathing?.cancel()
            breathing = null
            handChip.scaleX = 1f
            handChip.scaleY = 1f
        }

        override fun onDetachedFromWindow() {
            // Bound again on the way back, which starts it again if the hand is still up.
            breathe(false)
            super.onDetachedFromWindow()
        }

        /**
         * The placeholder over a camera changing codec: up while the room
         * holds the person's camera as changing (the frame that ends it is the
         * room's to hear, so a renderer that stalls brings nothing back), and
         * never in a row or over a shared screen, which are not the camera.
         * Said to TalkBack once a change per person, wherever their tile goes
         * meanwhile.
         */
        private fun applyOptimizing() {
            val who = person

            if (who == null || isRow || showsScreen) {
                optimizing.show(false, null)
                return
            }

            optimizing.show(who.optimizing, face, who.identity)
        }

        /**
         * Whether this tile can be seen.
         *
         * The renderers report to the SDK through `ViewVisibility`, which
         * recalculates on `onVisibilityChanged` — so hiding the renderer of a
         * tile that has been dragged away is what actually stops the stream,
         * not merely the drawing.
         */
        fun setShown(shown: Boolean) {
            val wanted = if (shown) View.VISIBLE else View.INVISIBLE

            renderer?.let { if (track != null && it.visibility != wanted) it.visibility = wanted }
            insetRenderer?.let { if (insetTrack != null && it.visibility != wanted) it.visibility = wanted }
        }

        fun bind(cell: Cell) {
            val who = cell.person

            // A shared screen is what the person meant to be looked at, so it
            // takes the tile — and their camera goes in the corner of the same
            // tile rather than being lost, exactly as the web's item does.
            val sharing = who.screen != null
            val shown = if (sharing) who.screen else who.video

            person = who
            showsScreen = sharing

            if (track !== shown) {
                renderer?.let { current -> track?.removeRenderer(current) }
                track = shown

                shown?.addRenderer(renderer())
            }

            renderer?.let {
                // The web fits, it does not crop. `.bm-group-call-input-video
                // -inner video` is `object-fit: contain`, and the `cover`
                // variant beside it is behind a `.bm-cover-video` class
                // nothing ever sets — so a grid tile letterboxes and shows
                // the whole frame. Filling here cut the sides off every
                // portrait phone camera in the grid.
                it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

                it.visibility = if (shown != null) View.VISIBLE else View.GONE
            }

            val inset = if (sharing && !isRow) who.video else null

            if (insetTrack !== inset) {
                insetRenderer?.let { current -> insetTrack?.removeRenderer(current) }
                insetTrack = inset

                inset?.addRenderer(insetRenderer())
            }

            insetRenderer?.let {
                it.visibility = if (inset != null) View.VISIBLE else View.GONE
            }

            handChip.visibility = if (who.handRaised) View.VISIBLE else View.GONE
            breathe(who.handRaised)

            // Someone who may not publish is a viewer, and the web says so on
            // the tile rather than leaving an unexplained empty square.
            val isViewer = !who.canPublish

            label.text = who.name
            namePill.visibility = if (who.name.isEmpty()) View.GONE else View.VISIBLE

            // The list says it both ways — the web's audio row draws a
            // microphone whether it is on or off, so silence is never the
            // absence of a mark.
            //
            // A microphone a moderator keeps off wears the red chip of one
            // that is off with a speaker crossed out in it, on every tile and
            // row, so a moderator sees whom they muted: the web's
            // MicLockedChip.
            val held = who.micMuted && !isViewer
            val off = who.muted || held

            if (isRow) {
                micChip.visibility = if (isViewer) View.GONE else View.VISIBLE
                mic.set(if (held) CallsIcons.silenced else if (off) CallsIcons.micOff else CallsIcons.mic)
                mic.glyphColor = if (off) Color.WHITE else CallsTheme.success(context)

                micChip.background = disc(
                    if (off) {
                        withAlpha(CallsTheme.recording(context), 235)
                    } else {
                        withAlpha(CallsTheme.success(context), 56)
                    }
                )
            } else {
                micChip.visibility = if (off) View.VISIBLE else View.GONE
                mic.set(if (held) CallsIcons.silenced else CallsIcons.micOff)
                mic.glyphColor = Color.WHITE
                micChip.background = disc(withAlpha(CallsTheme.recording(context), 235))
            }

            manage.visibility = if (CallsModeration.moderates(context, who)) View.VISIBLE else View.GONE

            hand.glyphColor = Color.rgb(17, 19, 24)
            handChip.background = disc(CallsTheme.warning(context))

            // Their media stopped arriving while they are still in the call
            // ([CallsStallWatch]): their camera's last frame blurred, and
            // their screen's too when nothing of theirs arrives, which the
            // tile says as "Reconnecting..." as for someone away. "Video
            // interrupted" only where the tile shows their camera, a still
            // screen sending nothing, and not under the placeholder of a
            // camera changing codec, which says why already.
            val stall = if (who.isLocal || who.reconnecting) null else who.stalled

            stalled.show(
                shown != null && (stall == CallsStalled.ALL || (stall == CallsStalled.VIDEO && !sharing)),
                stall == CallsStalled.VIDEO && !sharing && !who.optimizing,
                renderer
            )
            insetStalled.show(inset != null && stall != null, false, insetRenderer)

            // Away for a moment: the veil, and no microphone mark under it,
            // which would say they muted themselves.
            reconnecting.visibility = if (who.reconnecting || stall == CallsStalled.ALL) View.VISIBLE else View.GONE
            reconnecting.text = CallsTheme.string(context, "reconnecting_self", "Reconnecting...")

            if (who.reconnecting) micChip.visibility = View.GONE

            viewer.visibility = if (isViewer) View.VISIBLE else View.GONE
            // Two words for the same rule, because the web uses two: someone
            // who cannot publish is a viewer of a video room and a listener to
            // an audio one.
            viewer.text = if (isRow) {
                CallsTheme.string(context, "listener", "Listener")
            } else {
                CallsTheme.string(context, "viewer", "Viewer")
            }

            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(CallsTheme.tile(context))
            }

            // The ring is the foreground, not the background: a SurfaceView
            // fills the tile and a background is drawn beneath it, so the ring
            // would only ever appear on the people with their cameras off.
            foreground = if (who.speaking) {
                GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.TRANSPARENT)

                    // The web scales its glow with how loudly the person is
                    // talking, clamped so a whisper is still visible and a
                    // shout does not take over the tile. Same curve here.
                    val level = (who.audioLevel * 100f * 5f).coerceIn(15f, 90f) / 100f
                    val width = (1 + level * 3) * resources.displayMetrics.density

                    setStroke(width.toInt().coerceAtLeast(1), CallsTheme.accent(context))
                }
            } else {
                null
            }

            // In a row the avatar always shows: the screen sits above the info
            // line rather than in place of it.
            val covered = if (isRow) false else shown != null

            initial.visibility = if (covered) View.GONE else View.VISIBLE
            // The letter only while there is no picture to stand in for: a roster comes many times a second while
            // someone talks, and setting it each time put it back over a picture loaded on the first.
            initial.text = if (face != null && avatarUrl == who.avatar) "" else CallsAvatars.initials(who.name)

            if (avatarUrl != who.avatar) {
                avatarUrl = who.avatar
                initial.background = disc()
                face = null

                CallsAvatars.load(context, who.avatar) { bitmap ->
                    if (avatarUrl != who.avatar || bitmap == null) return@load

                    initial.text = ""
                    // Round, like every other avatar in the product: a plain
                    // BitmapDrawable is a square.
                    initial.background = BitmapDrawable(resources, circular(bitmap))

                    // The placeholder's backdrop is the same face, blurred.
                    face = bitmap
                    if (optimizing.isUp) applyOptimizing()
                }
            }

            applyOptimizing()

            // The host's own view over the tile, where it gives one (CallsPieces): the same view again stays.
            val own = CallsPiecesStore.current.tile?.invoke(who, context)
            if (own !== piece) {
                piece?.let { removeView(it) }
                piece = own
                if (own != null) {
                    (own.parent as? ViewGroup)?.removeView(own)
                    addView(own)
                }
            }

            applyOptimizing()

            requestLayout()
        }

        /** The host's view over this tile (CallsPieces), on top and filling it. */
        private var piece: View? = null

        private fun disc(color: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        /**
         * The picture, square-cropped and masked to a circle. Done once, when
         * it loads, rather than with a shader that would have to be rescaled
         * for every tile size the grid puts it in.
         */
        private fun circular(source: Bitmap): Bitmap {
            val size = min(source.width, source.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val left = (source.width - size) / 2
            val top = (source.height - size) / 2

            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

            canvas.drawBitmap(
                source,
                Rect(left, top, left + size, top + size),
                Rect(0, 0, size, size),
                paint
            )

            return out
        }

        private fun withAlpha(color: Int, alpha: Int): Int = CallsTheme.withAlpha(color, alpha)

        /** The info line of a row. What sits above it is the picture, and how
         *  much room that gets is the grid's to decide — see `listFrames`. */
        fun rowHeight(): Int = dp(ROW_HEIGHT)

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val height = MeasureSpec.getSize(heightSpec)

            setMeasuredDimension(width, height)

            place(width, height) { view, rect ->
                view.measure(
                    MeasureSpec.makeMeasureSpec(rect.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height().coerceAtLeast(0), MeasureSpec.EXACTLY)
                )
            }
            piece?.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            place(right - left, bottom - top) { view, rect ->
                view.layout(rect.left, rect.top, rect.right, rect.bottom)
            }
            piece?.layout(0, 0, right - left, bottom - top)
        }

        /**
         * The picture's own rectangle inside the tile, centred — the tile's
         * whole area until a frame has told us its shape.
         *
         * This is where a grid tile letterboxes, and it has to be here rather
         * than in the renderer's scaling type, which was already set to
         * `SCALE_ASPECT_FIT` and had no effect at all.
         * `SurfaceViewRenderer` consults that only in its own `onMeasure`, and
         * this tile overrides `onMeasure` to hand every child an EXACTLY spec
         * at the full tile size — so the renderer never gets to choose. The
         * same shape of mistake was on the call stage and on the stage's
         * layout params before it; measuring the child is the only place the
         * decision actually lives.
         *
         * Measured against the iPhone on one call, the same two portrait
         * cameras: iOS pillarboxed and showed the whole frame, this filled and
         * cut the top and bottom off both.
         */
        private fun fitted(width: Int, height: Int): Rect = fitted(Rect(0, 0, width, height))

        /** The same, inside a box that does not start at the tile's corner —
         *  the picture band of a row sits above the info line. */
        private fun fitted(box: Rect): Rect {
            val width = box.width()
            val height = box.height()

            if (pictureAspect <= 0f || width <= 0 || height <= 0) return box

            val shape = width.toFloat() / height.toFloat()

            if (kotlin.math.abs(shape - pictureAspect) < 0.001f) return box

            return if (pictureAspect > shape) {
                // Wider than the box: full width, bars above and below.
                val tall = (width / pictureAspect).toInt()
                val top = box.top + (height - tall) / 2

                Rect(box.left, top, box.right, top + tall)
            } else {
                // Taller than the box: full height, bars either side.
                val wide = (height * pictureAspect).toInt()
                val left = box.left + (width - wide) / 2

                Rect(left, box.top, left + wide, box.bottom)
            }
        }

        /**
         * Every child's frame, worked out once and used by both passes — a
         * tile measured at one size and laid out at another wraps its label
         * against the wrong width.
         */
        private fun place(width: Int, height: Int, apply: (View, Rect) -> Unit) {
            if (width <= 0 || height <= 0) return

            if (isRow) placeRow(width, height, apply) else placeTile(width, height, apply)

            apply(reconnecting, Rect(0, 0, width, height))
            apply(optimizing, Rect(0, 0, width, height))
            apply(outline, Rect(0, 0, width, height))
        }

        private fun placeTile(width: Int, height: Int, apply: (View, Rect) -> Unit) {
            val picture = fitted(width, height)

            renderer?.let { apply(it, picture) }
            apply(stalled, picture)

            // `.bm-small-video`: eight points in from the bottom-right corner,
            // no taller than 72, and never more than a third of the tile.
            val tall = min(dp(72), height / 3)
            val wide = min(tall * 16 / 9, width / 3)
            val corner = Rect(width - wide - dp(8), height - tall - dp(8), width - dp(8), height - dp(8))

            insetRenderer?.let { apply(it, corner) }
            apply(insetStalled, corner)

            // `width: min(38%, 96px)` of the tile, as the web's avatar is, and
            // never taller than half of it, which a wide, low tile would be.
            val size = max(dp(32), minOf(dp(96), (width * 0.38f).toInt(), (height * 0.5f).toInt()))
            val avatar = Rect(
                (width - size) / 2,
                (height - size) / 2,
                (width + size) / 2,
                (height + size) / 2
            )

            // The initials stand in for the picture, so they go with its size
            // rather than spilling out of a small one.
            initialSize(size * 0.36f)
            apply(initial, avatar)

            // Where the stylesheet puts them, which is not where these were.
            //
            //   .bm-group-call-input-video-inner-info  bottom: 0; padding: 8px;
            //                                          padding-inline-end: 40px
            //   .bm-caller-status                      bottom: 8px; end: 8px
            //   .bm-hand-raised-status                 top: 8px; end: 8px
            //   .bm-viewer-badge                       top: 8px; end: 8px
            //
            // So the name runs along the bottom from the start edge, and the
            // crossed-out microphone sits at the end of the same line with the
            // name held clear of it. This had the name centred on the bottom
            // edge — where the dock lands on it — and the microphone alone in
            // the top corner.
            val chip = dp(24)
            val pad = dp(8)

            label.gravity = Gravity.START or Gravity.CENTER_VERTICAL

            // The name in its glass pill, `.bm-info-name`: 9 in at the sides,
            // 22 tall, as wide as the name and no wider than the room the
            // microphone leaves it.
            var start = pad
            var room = (width - pad * 2 - chip - pad).coerceAtLeast(0)

            // A spotlighted tile says so before the name, 6 from it: the star, 4, the word, padded 7 and 8.
            if (spotlit) {
                val star = dp(12)
                val word = ceil(spotlightLabel.paint.measureText(spotlightLabel.text.toString())).toInt()
                val wide = min(room, word + star + dp(4) + dp(15))
                val top = height - pad - dp(22)

                apply(spotlightPill, Rect(start, top, start + wide, height - pad))
                apply(spotlightStar, Rect(dp(7), (dp(22) - star) / 2, dp(7) + star, (dp(22) + star) / 2))
                apply(spotlightLabel, Rect(dp(7) + star + dp(4), 0, (wide - dp(8)).coerceAtLeast(dp(7) + star + dp(4)), dp(22)))

                start += wide + dp(6)
                room = (room - wide - dp(6)).coerceAtLeast(0)
            }

            val named = min(room, ceil(label.paint.measureText(label.text.toString())).toInt() + dp(18))
            val pill = Rect(start, height - pad - dp(22), start + named, height - pad)

            apply(namePill, pill)
            apply(label, Rect(pill.left + dp(9), pill.top, (pill.right - dp(9)).coerceAtLeast(pill.left + dp(9)), pill.bottom))

            applyChip(
                micChip,
                mic,
                Rect(width - chip - pad, height - chip - pad, width - pad, height - pad),
                apply
            )

            applyChip(handChip, hand, Rect(width - chip - pad, pad, width - pad, pad + chip), apply)

            if (viewer.visibility != View.GONE) {
                val wide = min(dp(110), viewerWidth())

                // Under the hand, which shares the top end corner with it —
                // the stylesheet drops one of them to `top: 40px` when both
                // are up.
                apply(viewer, Rect(width - wide - pad, pad + chip + pad, width - pad, pad + chip + pad + dp(18)))
            }

            // `.bm-admin-controls`: 30 points, eight in from the top start
            // corner, the hand's corner being the other one.
            if (manage.visibility != View.GONE) {
                applyChip(manage, more, Rect(pad, pad, pad + dp(30), pad + dp(30)), apply)
            }

            // Pin for Me after the ⋯, 6 from it, so a moderator's ⋯ stays where it always is.
            if (pin.visibility != View.GONE) {
                val at = if (manage.visibility != View.GONE) pad + dp(30) + dp(6) else pad

                applyChip(pin, pinIcon, Rect(at, pad, at + dp(30), pad + dp(30)), apply)
            }
        }

        /** `[ avatar ] name … [ listener ] [ hand ] [ mic ]`, over the screen. */
        private fun placeRow(width: Int, height: Int, apply: (View, Rect) -> Unit) {
            val info = (height - dp(ROW_HEIGHT)).coerceAtLeast(0)
            val middle = info + dp(ROW_HEIGHT) / 2

            // The picture at its own shape rather than stretched across the
            // row. A phone's shared screen is taller than it is wide and would
            // otherwise sit as a narrow strip down the middle of a black band
            // the full width of the row.
            val picture = fitted(Rect(0, 0, width, info))

            renderer?.let { apply(it, picture) }
            apply(stalled, picture)
            insetRenderer?.let { apply(it, Rect(0, 0, 0, 0)) }
            apply(insetStalled, Rect(0, 0, 0, 0))

            val size = dp(40)
            val avatar = Rect(dp(10), middle - size / 2, dp(10) + size, middle + size / 2)

            initialSize(size * 0.36f)
            apply(initial, avatar)

            var right = width - dp(10)
            val chip = dp(28)

            // The ⋯ last on the row, after the microphone, as the web's caller
            // actions end the audio row.
            if (manage.visibility != View.GONE) {
                applyChip(manage, more, Rect(right - chip, middle - chip / 2, right, middle + chip / 2), apply)
                right -= chip + dp(6)
            }

            if (micChip.visibility != View.GONE) {
                applyChip(micChip, mic, Rect(right - chip, middle - chip / 2, right, middle + chip / 2), apply)
                right -= chip + dp(6)
            }

            if (handChip.visibility != View.GONE) {
                applyChip(handChip, hand, Rect(right - chip, middle - chip / 2, right, middle + chip / 2), apply)
                right -= chip + dp(6)
            }

            if (viewer.visibility != View.GONE) {
                val wide = min(dp(110), viewerWidth())

                apply(viewer, Rect(right - wide, middle - dp(9), right, middle + dp(9)))
                right -= wide + dp(6)
            }

            val nameLeft = avatar.right + dp(10)

            // Set here as well as in the wall, because the two share one label
            // and whichever laid out last would otherwise decide for both. A
            // row's name stands on the row itself, as the web's roster has it,
            // with no pill behind it.
            label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            apply(namePill, Rect(0, 0, 0, 0))

            apply(label, Rect(nameLeft, middle - dp(10), right.coerceAtLeast(nameLeft), middle + dp(10)))
        }

        private fun viewerWidth(): Int {
            viewer.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )

            return viewer.measuredWidth
        }

        /**
         * A disc with the glyph inset, the way the stylesheet's call chip is
         * drawn — the icon view fills whatever it is given, so the padding has
         * to be a frame rather than a margin.
         */
        private fun applyChip(chip: View, icon: View, rect: Rect, apply: (View, Rect) -> Unit) {
            apply(chip, rect)

            val inset = if (rect.height() <= dp(24)) dp(5) else dp(6)

            apply(icon, Rect(inset, inset, rect.width() - inset, rect.height() - inset))
        }

        /**
         * A released renderer must also leave the view tree.
         *
         * Dropping the reference is not enough: `renderer()` adds the surface
         * as a child, and a `SurfaceViewRenderer` that has been released but
         * is still attached keeps its surface callbacks. `renderer()` then
         * builds a second one beside it, and the tile ends up stacked with
         * dead surfaces — five of them were running on the test phone, each
         * reporting "Frames received: 0" every four seconds.
         *
         * They are not merely idle. The moment a frame reaches one, it draws
         * with a shader program its context no longer owns:
         *
         *     FATAL EXCEPTION: EglThread
         *     glUseProgram: GLES20 error: 1282
         *
         * which is on the WebRTC render thread and so kills the process. That
         * is the crash a group call died on the first time a camera came on.
         */
        fun release() {
            renderer?.let {
                track?.removeRenderer(it)
                it.release()
                removeView(it)
            }

            insetRenderer?.let {
                insetTrack?.removeRenderer(it)
                it.release()
                removeView(it)
            }

            renderer = null
            insetRenderer = null
            track = null
            insetTrack = null
            breathe(false)
            optimizing.show(false, null)
            stalled.show(false, false, null)
            insetStalled.show(false, false, null)
        }

        companion object {
            const val ROW_HEIGHT = 56
        }
    }

    private val tiles = mutableListOf<Tile>()
    private var cells = listOf<Cell>()

    /**
     * How many of them are the web's "primary" row — pinned, or speaking in a
     * broadcast. They are the first cells, so a count is all the layout needs.
     */
    private var primaryCount = 0

    /**
     * Whose camera changing codec TalkBack has heard of, shared by every tile:
     * a person's tile moves to another place in the grid as the order changes,
     * and the same change is said once, not once a tile.
     */
    private val optimizingSaid = CallsOptimizingSaid()

    /**
     * How far the scrollable part has been dragged. The web pages its overflow
     * sideways; this drags, which is the gesture that belongs here — and either
     * way each tile keeps the size the web would have drawn it at.
     */
    private var offset = 0f
    private var maxOffset = 0f
    private var horizontal = false
    private var dragging = false
    private var moved = false
    private var lastTouch = 0f

    /** Redraw for whoever is in the room now. */
    fun show(participants: List<CallsRoomParticipant>) {
        visibility = if (participants.isEmpty()) View.GONE else View.VISIBLE
        shownPeople = participants

        if (participants.isEmpty()) {
            release()
            optimizingSaid.keep(emptySet())
            return
        }

        val built: List<Cell>
        val alone = solo?.let { one -> participants.firstOrNull { it.identity == one } }

        if (alone != null) {
            built = listOf(Cell(alone))
            primaryCount = 0
        } else if (audioRoom) {
            // The web's own order for a list: whoever is sharing first, then
            // everyone with a microphone on, then the rest. A row that jumps
            // about is unreadable, so within each group the roster's order is
            // kept as it is.
            val sharing = participants.filter { it.screen != null }
            val talking = participants.filter { it.screen == null && !it.muted }
            val quiet = participants.filter { it.screen == null && it.muted }

            built = (sharing + talking + quiet).map { Cell(it) }
            primaryCount = 0
        } else {
            // The web's own split (spotlight.tsx's mainKeys). Whoever a
            // moderator put in the spotlight goes in the primary row, in the
            // order they were spotlighted; while nobody is, this user's own
            // pins, and anyone who may publish while the room is broadcasting
            // — those are the people the room is there to watch.
            val main = CallsSpotlight.main(participants, spotlight, pinned, broadcastRoom)
            val primary = main.mapNotNull { identity -> participants.firstOrNull { it.identity == identity } }
            val taken = main.toSet()
            val rest = participants.filter { !taken.contains(it.identity) }

            // And its order for the rest: anyone with a picture first, then
            // whoever is talking, then everybody else.
            // A camera changing codec is still a picture: its tile keeps its
            // place under the placeholder rather than dropping down the grid
            // for the second the camera is away.
            val pictured = { p: CallsRoomParticipant -> p.video != null || p.screen != null || p.optimizing }
            val seen = rest.filter { pictured(it) }
            val heard = rest.filter { !pictured(it) && it.speaking }
            val quiet = rest.filter { !pictured(it) && !it.speaking }

            // And your own tile last, wherever the ordering above left it.
            // The web moves it to the end of the page for the same reason —
            // you know what you look like, and the screen is for the people
            // you are talking to. It arrives first in the roster, so without
            // this a call between two people put you on top and them
            // underneath, which is the wrong way round on every platform.
            //
            // Not out of the primary row: a pinned tile, or a speaker in a
            // broadcast room, is what the room came to watch and stays where
            // it is. The web guards the same way.
            val ordered = seen + heard + quiet

            built = (primary + ordered.filterNot { it.isLocal } + ordered.filter { it.isLocal })
                .map { Cell(it) }

            primaryCount = primary.size
        }

        cells = built

        while (tiles.size < cells.size) {
            val tile = Tile(context, egl)

            tile.isRow = audioRoom
            tile.optimizing.said = optimizingSaid

            // A tile is long-clickable so it can be moderated, and a
            // long-clickable view consumes every plain tap on its way past.
            // So the tap the screen behind this one wants — the one that puts
            // the dock away and brings it back — never arrived once the grid
            // was drawn over it, and hiding the dock in a group call was a
            // one-way door.
            // In a group video call the tap also shows the tile's Pin for Me
            // for a while (`Tile.tapped`).
            tile.setOnClickListener {
                tile.tapped()
                onTap?.invoke()
            }

            tile.setOnLongClickListener {
                tile.person?.let { person -> onPerson?.invoke(person) }
                true
            }

            tile.onManage = { tile.person?.let { person -> onPerson?.invoke(person) } }
            tile.onPin = { tile.person?.let { person -> onPin?.invoke(person) } }

            tiles.add(tile)
            addView(tile)
        }

        // Anyone who left takes their surface with them.
        while (tiles.size > cells.size) {
            val tile = tiles.removeAt(tiles.size - 1)

            removeView(tile)
            tile.release()
        }

        rebind()

        // Whoever's camera is no longer changing may be told of the next change.
        optimizingSaid.keep(participants.filter { it.optimizing }.map { it.identity }.toSet())
    }

    /**
     * Redraw what is already there, for what changes while nobody joins or
     * leaves.
     */
    private fun rebind() {
        // Labeled Spotlight, for everyone, as the main tiles are drawn.
        val spotlitOrder = CallsSpotlight.spotlit(cells.map { it.person }, spotlight)
        val spotlit = spotlitOrder.toSet()

        cells.forEachIndexed { index, cell ->
            if (index < tiles.size) {
                tiles[index].isRow = audioRoom
                tiles[index].bind(cell)
                // Tiles are reused as people come and go: the outline, the label and the pin follow the person.
                tiles[index].moderating = moderating != null && cell.person.identity == moderating
                tiles[index].setSpotlit(cell.person.identity in spotlit)
                // Pin for Me only where a pin shows (CallsSpotlight.pinOffered): not under a spotlight, a pinned tile's Unpin kept.
                val offered = pinsOffered && solo == null && CallsSpotlight.pinOffered(cell.person.identity, pinned, spotlitOrder)
                tiles[index].setPins(offered, cell.person.identity in pinned)
            }
        }

        requestLayout()
    }

    /**
     * How wide one tile is in the strip under a pinned speaker.
     *
     * Not the strip divided by the people in it. A strip is one tile tall and
     * the web lays it out by how many tiles fit across it, paging sideways
     * past that:
     *
     *     itemWidth = stripHeight × ratio
     *     columns   = ceil(width / itemWidth)
     *
     * So one person beside a pinned speaker gets a third of the width, not
     * all of it, and the strip looks the same whether one person is in it or
     * nine.
     *
     * The ratio is the widest picture in the strip rather than 16:9, because a
     * cell has to hold whatever lands in it — a screen shared from a laptop is
     * wider than a camera, and one shared from a phone is narrower.
     */
    private fun stripWidth(width: Int, height: Int, ratio: Float): Int {
        val item = height * ratio

        if (item <= 0f) return width

        val columns = ceil(width / item).toInt().coerceAtLeast(1)

        return width / columns
    }

    /**
     * The widest picture among these tiles, and `TILE_RATIO` when none of them
     * has said what shape it is yet.
     *
     * The widest, because a cell has to hold whichever picture lands in it and
     * the widest is the one that needs the most room. The web works the same
     * figure out under the same name, `maxRatio`.
     */
    private fun widest(of: List<Tile>): Float =
        of.fold(TILE_RATIO) { carried, tile -> max(carried, tile.pictureAspect) }

    /**
     * The tallest — the smallest ratio — for the band a pinned tile is drawn
     * in, because that is the shape the band has to make room for. The web's
     * `minRatio`. A tile that has not reported is left out of the reckoning
     * rather than counted as 16:9.
     */
    private fun tallest(of: List<Tile>): Float =
        of.fold(TILE_RATIO) { carried, tile ->
            if (tile.pictureAspect > 0f) min(carried, tile.pictureAspect) else carried
        }

    /**
     * The arrangement that makes the pictures biggest.
     *
     * Every column count is a candidate. Each one implies a number of rows,
     * and so a cell of `width / columns` by `height / rows`; the picture
     * letterboxed into that cell — which is what the renderer draws — has an
     * area. The largest area wins. One loop, no special cases, and it answers
     * for any number of people on any screen.
     *
     * The picture, not a 16:9 one: `ratio` is what is actually being drawn,
     * because a phone sharing its screen publishes something taller than it is
     * wide and a cell shaped for a camera has no room for it.
     *
     * It is why two people stack on a phone held upright and sit side by side
     * on its side, without either being written down: at 1440 by 2600 one
     * column of two fits a 1424-wide picture and two columns of one fit a
     * 714-wide one, and turning the phone reverses it exactly.
     *
     * What it replaces was `ceil(sqrt(count))` walked down until the tiles
     * cleared a minimum, and then `if (count == 2) one column` on top of that
     * — at every size, which is what the web says too. That is why a phone
     * turned on its side showed two very wide letterboxes with a small avatar
     * in each.
     *
     * The minimum stays as a ceiling on how many tiles may share a screen: a
     * phone showing nine faces is showing nine thumbnails. Anything past what
     * fits scrolls, as it did before.
     */
    private fun wallShape(count: Int, width: Int, height: Int, ratio: Float): Pair<Int, Int> {
        if (count < 1 || width <= 0 || height <= 0) return 1 to 1

        val minHeight = dp(MIN_STRIP)
        val minWidth = (minHeight * ratio).toInt().coerceAtLeast(1)

        val mostColumns = (width / minWidth).coerceIn(1, count)
        val mostRows = (height / minHeight).coerceAtLeast(1)

        var best = 1 to count
        var bestArea = -1f

        for (columns in 1..mostColumns) {
            val needed = ceil(count / columns.toDouble()).toInt()
            val rows = min(needed, mostRows)

            val cellWidth = width / columns.toFloat()
            val cellHeight = height / rows.toFloat()

            // The picture inside the cell, letterboxed the way the renderer
            // letterboxes it. The cell's own area would pick the arrangement
            // with the least dead space rather than the biggest faces.
            val drawn = min(cellWidth, cellHeight * ratio)
            val area = drawn * (drawn / ratio)

            if (area > bestArea) {
                bestArea = area
                best = columns to rows
            }
        }

        return best
    }

    /**
     * Every frame, worked out once and used by both the measure and the layout
     * pass, because a tile measured at the parent's size and laid out smaller
     * wraps its label against the wrong width.
     */
    private fun frames(width: Int, height: Int): List<Rect> {
        if (tiles.isEmpty() || width <= 0 || height <= 0) return emptyList()

        return if (audioRoom) listFrames(width, height) else wallFrames(width, height)
    }

    /**
     * The web's `group-video-grid.tsx`, on a phone.
     *
     * Two bands. Whoever is pinned — or, in a broadcast, whoever may speak —
     * fills a single column down everything but the last hundred points;
     * everybody else shares that strip, which drags sideways. With nobody
     * pinned in an ordinary room there is no primary band at all and the rest
     * take the whole area, which drags down.
     */
    private fun wallFrames(width: Int, height: Int): List<Rect> {
        val out = mutableListOf<Rect>()
        val strip = dp(100)
        val primary = min(primaryCount, tiles.size)
        val rest = tiles.size - primary

        // The web's grid is padded 4 on top and either side, and each tile 4
        // more inside its cell: 8 from the edges and between tiles, and the
        // last row flush with the grid's floor, where the bar's own padding
        // takes over.
        val gutter = dp(GUTTER)
        val area = Rect(gutter, gutter, (width - gutter).coerceAtLeast(gutter), height + gutter)
        var box = Rect(area)
        var beside = false

        if (primary > 0) {
            // A picture taller than it is wide cannot be handed the whole
            // width: the band is then mostly the ground either side of it.
            // Somebody sharing a phone is exactly that — about 9:19.5 — and
            // the stack was the only division this had, so pinning their share
            // drew it as a narrow strip down the middle of a very wide band
            // with everyone else squashed into a hundred points underneath.
            //
            // A tall band stands beside the rest instead, keeping its own
            // shape and taking only the width that shape needs; the others
            // have what is left, as a column rather than a strip. Only on a
            // screen wider than it is tall — a phone turned on its side —
            // only when there is room for a tile either side of the division,
            // and only when there is anybody to stand beside: with everyone
            // pinned the band is the whole call and takes the whole screen.
            // The web divides on the same four tests.
            val shape = tallest(tiles.take(primary))
            val minStrip = (dp(MIN_STRIP) * TILE_RATIO).toInt()

            beside = shape < 1f && rest > 0 && area.width() > area.height() && area.width() > minStrip * 2

            // `isMobile()` in the web: the primary row is one column of them,
            // however many there are.
            val tall = (if (beside || rest == 0) area.height() else area.height() - strip) / primary

            val wide = if (beside) {
                min(max((area.height().toFloat() / primary * shape).toInt(), minStrip), area.width() - minStrip)
            } else {
                area.width()
            }

            for (index in 0 until primary) {
                out.add(inset(Rect(area.left, area.top + index * tall, area.left + wide, area.top + (index + 1) * tall)))
            }

            box = if (beside) {
                Rect(area.left + wide, area.top, area.right, area.bottom)
            } else {
                Rect(area.left, area.bottom - strip, area.right, area.bottom)
            }
        }

        if (rest == 0) {
            horizontal = false
            maxOffset = 0f
            offset = 0f

            return out
        }

        val others = tiles.drop(primary)

        if (primary > 0 && !beside) {
            // One row running off to the right, which is where the web's own
            // strip puts the overflow — `overflow-x: auto`, no pager.
            val cellWidth = stripWidth(box.width(), box.height(), widest(others))

            horizontal = true
            maxOffset = (rest * cellWidth - box.width()).coerceAtLeast(0).toFloat()
            offset = offset.coerceIn(0f, maxOffset)

            for (index in 0 until rest) {
                val x = box.left + index * cellWidth - offset.toInt()

                out.add(inset(Rect(x, box.top, x + cellWidth, box.bottom)))
            }
        } else {
            horizontal = false

            // Beside a tall band this is a column and not a strip, so it is
            // laid out as a wall too — which is what the web does with it.
            val (columns, rows) = wallShape(rest, box.width(), box.height(), widest(others))
            val cellWidth = box.width() / columns
            val cellHeight = box.height() / rows
            val drawn = ceil(rest / columns.toDouble()).toInt()

            maxOffset = (drawn * cellHeight - box.height()).coerceAtLeast(0).toFloat()
            offset = offset.coerceIn(0f, maxOffset)

            for (index in 0 until rest) {
                // From the box's own corner: with nobody pinned that is the
                // origin, but a column standing beside a tall band starts
                // where the band ends.
                val x = box.left + (index % columns) * cellWidth
                val y = box.top + (index / columns) * cellHeight - offset.toInt()

                out.add(inset(Rect(x, y, x + cellWidth, y + cellHeight)))
            }
        }

        return out
    }

    /**
     * One row per person down the page, the web's ten-point margin and
     * eight-point gap, dragging when there are more people than fit.
     *
     * Somebody sharing their screen takes the stage.
     *
     * An audio call has no pictures in it, so this is a list of rows — and a
     * share used to be squeezed into the sharer's row as a band of at most 320
     * points, drawn as a 16:9 picture whatever it actually was. That was
     * tolerable while a share was a laptop's. A phone's is about 9:19.5, and
     * the same band drew it as a sliver a seventh of the row wide with the
     * rest of the row black, under a screenful of list nothing was using.
     *
     * So a sharing row takes the height the roster does not need, the way the
     * web's `flex: 1 1 auto` does, and keeps a floor of 45% of the screen when
     * the roster is long enough to want all of it — the list drags rather than
     * squeezing the thing everyone is looking at. Two people sharing at once
     * halve the stage between them, which falls out of the arithmetic rather
     * than being written down.
     */
    private fun listFrames(width: Int, height: Int): List<Rect> {
        val out = mutableListOf<Rect>()
        val margin = dp(10)
        val gap = dp(8)
        val wide = (width - margin * 2).coerceAtLeast(0)

        horizontal = false

        val sharing = tiles.count { it.showsScreen }
        val rows = tiles.sumOf { if (it.showsScreen) 0 else it.rowHeight() }
        val gaps = (tiles.size - 1).coerceAtLeast(0) * gap
        val spare = height - margin * 2 - rows - gaps

        val line = tiles.firstOrNull()?.rowHeight() ?: dp(56)

        val stage = if (sharing > 0) {
            max(spare / sharing, max((height * 0.45f).toInt(), line + dp(60)))
        } else {
            0
        }

        var top = margin

        for (tile in tiles) {
            val tall = if (tile.showsScreen) stage else tile.rowHeight()

            out.add(Rect(margin, top, margin + wide, top + tall))

            top += tall + gap
        }

        maxOffset = (top - gap + margin - height).coerceAtLeast(0).toFloat()
        offset = offset.coerceIn(0f, maxOffset)

        val shift = offset.toInt()

        return out.map { Rect(it.left, it.top - shift, it.right, it.bottom - shift) }
    }

    /**
     * The gap between tiles, taken out of the cell rather than added to it, so
     * the arithmetic above stays the web's.
     */
    private fun inset(rect: Rect): Rect {
        val gap = dp(GUTTER)

        return Rect(rect.left + gap, rect.top + gap, rect.right - gap, rect.bottom - gap)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = MeasureSpec.getSize(heightSpec)

        setMeasuredDimension(width, height)

        frames(width, height).forEachIndexed { index, rect ->
            if (index >= tiles.size) return@forEachIndexed

            tiles[index].measure(
                MeasureSpec.makeMeasureSpec(rect.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rect.height().coerceAtLeast(0), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (tiles.isEmpty()) return

        val width = right - left
        val height = bottom - top
        val visible = Rect(0, 0, width, height)

        frames(width, height).forEachIndexed { index, rect ->
            if (index >= tiles.size) return@forEachIndexed

            tiles[index].layout(rect.left, rect.top, rect.right, rect.bottom)

            // Stop paying for the tiles nobody can see. A renderer that is not
            // VISIBLE tells the SDK through `ViewVisibility`, which drops the
            // subscription rather than merely skipping the draw.
            tiles[index].setShown(Rect.intersects(visible, rect))
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (maxOffset <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch = if (horizontal) event.x else event.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val now = if (horizontal) event.x else event.y

                if (abs(now - lastTouch) > ViewConfiguration.get(context).scaledTouchSlop) {
                    dragging = true
                    moved = true
                    lastTouch = now

                    return true
                }
            }
        }

        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (maxOffset <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch = if (horizontal) event.x else event.y
                dragging = true
                moved = false

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false

                val now = if (horizontal) event.x else event.y

                if (abs(now - lastTouch) > ViewConfiguration.get(context).scaledTouchSlop) moved = true

                offset = (offset - (now - lastTouch)).coerceIn(0f, maxOffset)
                lastTouch = now

                requestLayout()

                return true
            }

            MotionEvent.ACTION_UP -> {
                // A press that never moved is a tap, and the tap on a call
                // belongs to the screen behind this: it is what puts the dock
                // away and brings it back.
                //
                // Swallowed here, hiding the dock over a grid tall enough to
                // scroll was a one-way door. The tiles grow into the room the
                // dock gave up, which is the point of hiding it — but growing
                // is what makes the grid scrollable, and a scrollable grid
                // claims every press from the moment it goes down. The tap
                // that would have brought the controls back was eaten by the
                // scroller they had just created, and there was no other way
                // to reach them.
                if (!moved) onTap?.invoke()

                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> dragging = false
        }

        return dragging
    }

    fun release() {
        tiles.forEach {
            removeView(it)
            it.release()
        }

        tiles.clear()
        cells = emptyList()
        primaryCount = 0
        offset = 0f
        maxOffset = 0f
    }
}

/**
 * Whom a group call's corner window shows, as the web's pipFocus: someone in the spotlight or pinned (`first`), else a
 * screen someone else shares, else whoever speaks loudest, else who spoke last while they are still there, else the first
 * with a picture, the first other, the user. Their tile draws them, by face where they send no picture.
 */
internal object CallsPipFocus {
    data class Person(
        val identity: String,
        val isLocal: Boolean = false,
        val screen: Boolean = false,
        val camera: Boolean = false,
        val speaking: Boolean = false,
        val level: Float = 0f,
    )

    fun person(who: CallsRoomParticipant) = Person(
        who.identity, who.isLocal, who.screen != null, who.video != null, who.speaking, who.audioLevel
    )

    /**
     * A video call has a window all through, an audio call only while someone else shares a screen, which is all it would
     * show. A call of nobody has none.
     */
    fun offered(people: List<Person>, video: Boolean): Boolean =
        people.isNotEmpty() && (video || people.any { !it.isLocal && it.screen })

    fun pick(people: List<Person>, first: List<String>, last: String?): String? {
        fun loudest(among: List<Person>): Person? = among.filter { it.speaking }.maxByOrNull { it.level }

        val others = people.filter { !it.isLocal }
        val ahead = first.distinct().mapNotNull { key -> people.firstOrNull { it.identity == key } }

        if (ahead.isNotEmpty()) {
            return (loudest(ahead) ?: ahead.firstOrNull { it.identity == last } ?: ahead.first()).identity
        }

        return (others.firstOrNull { it.screen }
            ?: loudest(others)
            ?: others.firstOrNull { it.identity == last }
            ?: others.firstOrNull { it.camera }
            ?: others.firstOrNull()
            ?: people.firstOrNull())?.identity
    }

    /** The shape Android's window can be: it refuses a ratio past 2.39 either way. */
    fun windowAspect(aspect: Float): Float = aspect.coerceIn(0.42f, 2.38f)
}
