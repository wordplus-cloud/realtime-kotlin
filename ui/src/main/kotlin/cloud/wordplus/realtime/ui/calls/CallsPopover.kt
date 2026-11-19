package cloud.wordplus.realtime.ui.calls

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.max
import kotlin.math.min

/**
 * A moderator's menu as the web draws it (realtime-ui's moderation-panels.tsx, `.bm-call-popover` and
 * `.bm-call-moderation` in _call-stage.scss): a card beside the button that opened it, on the side asked where it fits
 * or the side with more room, aligned to the button's start edge or held to the stage's end edge as the dock is, no
 * taller than that side's room (the rest scrolls), and placed again as the button moves, a tile pinned or someone made
 * a speaker. Its rows say what they do and, for the room's rules, whether they are on.
 *
 * It lies over the whole stage, and only its card takes a touch. A press outside the card closes it and still reaches
 * what it was on, as on the web, except the button that opened it, whose own press closes it: the press is turned down
 * here, so the stage hands it to whatever is under it.
 *
 * The button is asked for again each frame rather than held, because the grid reuses its tiles as people move about
 * it: a person's ⋯ is a different view once someone else starts talking, and the menu follows the person, not the view
 * that was theirs when it opened.
 */
class CallsPopover(
    context: Context,
    /** The view the card sits beside, asked again each frame: null when it is gone, which closes the menu. */
    private val anchor: () -> View?,
    private val side: Side,
    private val align: Align,
    /** 248 for a person's menu and 340 for the room's, as the web's, never wider than the stage allows. */
    widthDp: Int,
    /** The room's menu, whose rows are taller and wear their glyph on a tile of its own. */
    private val room: Boolean,
    /** Asked again on [refresh]: null when there is nothing left to show, a person who left. */
    private val content: () -> Pair<Head, List<Entry>>?,
) : FrameLayout(context) {
    enum class Side { ABOVE, BELOW }
    enum class Align { START, STAGE_END }
    enum class Tone { DANGER, WARNING, NEUTRAL }

    /** One thing that is on for a person, as their menu's header says it. */
    class Pill(val label: String, val icon: String, val tone: Tone)

    sealed class Head {
        /** The room's menu: a badge and a title. */
        class Room(val icon: String, val title: String) : Head()

        /** A person's: their picture, their name, and what is on for them now. */
        class Person(val name: String, val avatar: String, val state: List<Pill>) : Head()
    }

    /** What the menu lists under its head: rows, and the lines between groups of them. */
    sealed interface Entry

    class Row(
        val key: String,
        val icon: String,
        val label: String,
        val note: String? = null,
        /** A rule the room keeps, drawn with a switch that says whether it is on; null for an action. */
        val on: Boolean? = null,
        val danger: Boolean = false,
        /** What the person asked for: promoting someone whose hand is up. */
        val highlight: Boolean = false,
        /**
         * Something that can't be done now (a fifth person in the spotlight): dimmed, its press doing nothing, its note
         * saying why.
         */
        val disabled: Boolean = false,
        /** The row's action, which calls `done` once its request is answered: the row spins until then. */
        val run: (done: () -> Unit) -> Unit,
    ) : Entry

    object Divider : Entry

    private val density = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    private val gap = dp(6)
    private val edge = dp(8)

    // A menu held to the stage's end edge keeps the dock's own clearance from it.
    private val dockEdge = dp(14)

    private val wanted = dp(widthDp)
    private val padding = dp(if (room) 8 else 6)

    private val card = FrameLayout(context)
    private val scroll = ScrollView(context)
    private val stack = LinearLayout(context)
    private val busy = mutableSetOf<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Where the card is, in this view, and the button's rectangle it was placed for. */
    private val placed = Rect()
    private var placedFor: Rect? = null
    private var isAbove = false
    private var opened = false
    private var closing = false

    /** The system bars' share of the stage at its top and its bottom, which the card keeps clear of. */
    private var safeTop = 0
    private var safeBottom = 0

    /** Called once the menu is gone, whichever way it went. */
    var onClose: (() -> Unit)? = null

    /** Whether it has closed, or is fading out on its way. */
    val isClosed: Boolean get() = closing

    /**
     * The button's place checked each frame while the menu is open, as a display link does on iOS: a tile pinned, a
     * speaker promoted or the dock re-fitted moves it, and none of them says so to a view outside it.
     */
    private val follow = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (closing) return

            val now = anchor()

            if (now == null || !now.isAttachedToWindow || parent == null) {
                close()
                return
            }

            if (rectOf(now) != placedFor) requestLayout()

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        card.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(247, 22, 24, 30))
            setStroke(max(1, dp(1)), Color.argb(23, 255, 255, 255))
        }
        // The rows' own backgrounds stop at the card's rounded corners, and the shadow is the card's outline.
        card.clipToOutline = true
        card.elevation = dp(18).toFloat()

        // It does not bounce: there is nothing past either end of a menu.
        scroll.overScrollMode = OVER_SCROLL_NEVER

        stack.orientation = LinearLayout.VERTICAL
        stack.setPadding(padding, padding, padding, padding)

        scroll.addView(stack, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Over the whole of `host`, its card beside the button and placed again every frame the button moves, clear of
     * `top` and `bottom`, the system bars' share of the host. False, and nothing added, when there is nothing to show.
     */
    fun place(host: ViewGroup, top: Int, bottom: Int): Boolean {
        safeTop = top
        safeBottom = bottom

        fill()

        if (closing) return false

        host.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        return true
    }

    /** The rows again, from what is on now: after a request is answered, or the room or the person changed. */
    fun refresh() {
        if (closing) return

        val before = drawn

        fill()

        // Placed again only when its rows changed, and so perhaps its height.
        if (drawn == before || closing) return

        placedFor = null
        requestLayout()
    }

    fun close() {
        if (closing) return

        closing = true
        Choreographer.getInstance().removeFrameCallback(follow)

        val host = parent as? ViewGroup

        if (host != null && isAttachedToWindow) {
            card.animate().cancel()
            card.animate().alpha(0f).setDuration(100).withEndAction { host.removeView(this) }.start()
        } else {
            host?.removeView(this)
        }

        onClose?.invoke()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!closing) Choreographer.getInstance().postFrameCallback(follow)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(follow)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Fading out, it is already gone as far as a finger is concerned.
        if (closing) return false

        if (event.actionMasked == MotionEvent.ACTION_DOWN && !placed.contains(event.x.toInt(), event.y.toInt())) {
            // Outside the card: the menu goes, and the press reaches what it was on. The button that opened it is
            // left to close it, or it would close here and open again under the same press.
            val now = anchor()
            val onAnchor = now != null && now.isAttachedToWindow && rectOf(now).contains(event.x.toInt(), event.y.toInt())

            if (!onAnchor) post { close() }

            return false
        }

        // In the card, the press is the card's, whatever is under it: a press on the head is not a press on a tile.
        super.dispatchTouchEvent(event)

        return true
    }

    // Placing

    /** A view's rectangle in this one, which lies over the whole stage. */
    private fun rectOf(view: View): Rect {
        val mine = IntArray(2)
        val theirs = IntArray(2)

        getLocationInWindow(mine)
        view.getLocationInWindow(theirs)

        val left = theirs[0] - mine[0]
        val top = theirs[1] - mine[1]

        return Rect(left, top, left + view.width, top + view.height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        setMeasuredDimension(width, height)
        measureCard(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        card.layout(placed.left, placed.top, placed.right, placed.bottom)

        if (opened || closing) return

        // In from the side it opened on, as the web's does; the system's animator scale takes it away for someone
        // who asked for less motion.
        opened = true
        card.alpha = 0f
        card.translationY = (if (isAbove) 4 else -4) * density
        card.scaleX = 0.98f
        card.scaleY = 0.98f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(140).setInterpolator(DecelerateInterpolator()).start()
    }

    /**
     * Above or below the button, by the web's rule: the side asked for while the menu fits there or that side has
     * the more room, else the other; then no taller than the room on the side it took, and never under 80.
     */
    private fun measureCard(width: Int, height: Int) {
        val a = anchor()?.takeIf { it.isAttachedToWindow }?.let { rectOf(it) } ?: placedFor ?: return

        val stageTop = safeTop
        val stageBottom = height - safeBottom
        val shownWidth = min(wanted, width - edge * 2).coerceAtLeast(0)

        stack.measure(
            MeasureSpec.makeMeasureSpec(shownWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val full = stack.measuredHeight

        val above = a.top - stageTop - gap - edge
        val below = stageBottom - a.bottom - gap - edge
        val up = if (side == Side.ABOVE) full <= above || above >= below else full > below && above > below
        val roomShown = max(dp(80), if (up) above else below)
        val shown = min(full, roomShown)

        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

        val left = when (align) {
            Align.STAGE_END -> if (rtl) dockEdge else max(edge, width - shownWidth - dockEdge)
            Align.START -> {
                val asked = if (rtl) a.right - shownWidth else a.left
                max(edge, min(width - shownWidth - edge, asked))
            }
        }

        val top = if (up) a.top - gap - shown else a.bottom + gap

        isAbove = up
        placedFor = a
        placed.set(left, top, left + shownWidth, top + shown)

        card.measure(
            MeasureSpec.makeMeasureSpec(shownWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(shown, MeasureSpec.EXACTLY)
        )
    }

    // Drawing

    /**
     * What the rows said when they were last drawn: the roster comes again many times a second while someone talks,
     * and the menu is drawn again only when what it says has changed.
     */
    private var drawn = ""

    private fun fill() {
        val (head, entries) = content() ?: run {
            close()
            return
        }

        val said = fingerprint(head, entries)

        if (said == drawn) return

        drawn = said
        stack.removeAllViews()

        add(headView(head))

        for (entry in entries) {
            add(
                when (entry) {
                    is Row -> rowView(entry)
                    is Divider -> divider()
                }
            )
        }
    }

    private fun add(view: View) {
        val spacing = dp(if (room) 4 else 2)

        stack.addView(
            view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (stack.childCount > 0) topMargin = spacing
            }
        )
    }

    private fun fingerprint(head: Head, entries: List<Entry>): String {
        val parts = mutableListOf<String>()

        when (head) {
            is Head.Room -> parts.add("room|${head.icon}|${head.title}")
            is Head.Person -> parts.add("person|${head.name}|${head.avatar}|" + head.state.joinToString(";") { "${it.label},${it.tone}" })
        }

        for (entry in entries) {
            when (entry) {
                is Divider -> parts.add("-")
                is Row -> parts.add(
                    listOf(
                        entry.key, entry.icon, entry.label, entry.note ?: "", entry.on.toString(),
                        entry.danger.toString(), entry.highlight.toString(), entry.disabled.toString(), (entry.key in busy).toString()
                    ).joinToString("|")
                )
            }
        }

        return parts.joinToString("\n")
    }

    private fun headView(head: Head): View {
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val spacing = dp(if (room) 14 else 10)
        val accent = CallsTheme.accent(context)

        when (head) {
            is Head.Room -> {
                val badge = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(CallsTheme.withAlpha(accent, 51))
                    }
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }

                badge.addView(
                    CallsIconView(context).apply {
                        set(head.icon)
                        glyphColor = accent
                    },
                    LayoutParams(dp(18), dp(18), Gravity.CENTER)
                )

                line.addView(badge, LinearLayout.LayoutParams(dp(34), dp(34)))
                line.addView(
                    titleLabel(head.title),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = spacing }
                )

                // TalkBack hears the menu open by its title.
                ViewCompat.setAccessibilityPaneTitle(card, head.title)
            }

            is Head.Person -> {
                // Their picture, round, with their initial until it loads, and for good when there is none.
                val picture = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(31, 255, 255, 255))
                    }
                    clipToOutline = true
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }

                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                val initial = TextView(context).apply {
                    text = head.name.firstOrNull()?.uppercase() ?: ""
                    textSize = 14f
                    typeface = CallsTheme.SEMIBOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }

                picture.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                picture.addView(initial, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

                if (head.avatar.isNotEmpty()) {
                    CallsAvatars.load(context, head.avatar) { bitmap ->
                        if (bitmap == null) return@load

                        image.setImageBitmap(bitmap)
                        initial.visibility = View.GONE
                    }
                }

                val who = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.START
                }

                who.addView(
                    titleLabel(head.name),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                )

                if (head.state.isNotEmpty()) {
                    val pills = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    head.state.forEachIndexed { index, pill ->
                        pills.addView(
                            pillView(pill),
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(20)).apply {
                                if (index > 0) marginStart = dp(4)
                            }
                        )
                    }

                    who.addView(
                        pills,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4)
                        }
                    )
                }

                line.addView(picture, LinearLayout.LayoutParams(dp(32), dp(32)))
                line.addView(
                    who,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = spacing }
                )

                ViewCompat.setAccessibilityPaneTitle(card, head.name)
            }
        }

        // Top, start, bottom (to the rule under it) and end.
        val inset = if (room) intArrayOf(6, 10, 12, 10) else intArrayOf(8, 8, 10, 8)

        holder.addView(
            line,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(inset[0])
                marginStart = dp(inset[1])
                marginEnd = dp(inset[3])
            }
        )

        holder.addView(
            View(context).apply {
                setBackgroundColor(Color.argb(18, 255, 255, 255))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, max(1, dp(1))).apply {
                topMargin = dp(inset[2])
                bottomMargin = dp(4)
            }
        )

        return holder
    }

    private fun titleLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        typeface = CallsTheme.SEMIBOLD
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun pillView(pill: Pill): View {
        val ground: Int
        val ink: Int

        when (pill.tone) {
            Tone.DANGER -> {
                ground = CallsTheme.withAlpha(CallsTheme.recording(context), 51)
                ink = DANGER_INK
            }
            Tone.WARNING -> {
                ground = CallsTheme.withAlpha(CallsTheme.warning(context), 51)
                ink = CallsTheme.warning(context)
            }
            Tone.NEUTRAL -> {
                ground = Color.argb(26, 255, 255, 255)
                ink = Color.argb(199, 255, 255, 255)
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(6), 0, dp(8), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(ground)
            }

            addView(
                CallsIconView(context).apply {
                    set(pill.icon)
                    glyphColor = ink
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(11), dp(11))
            )

            addView(
                TextView(context).apply {
                    text = pill.label
                    textSize = 11f
                    typeface = CallsTheme.SEMIBOLD
                    setTextColor(ink)
                    maxLines = 1
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(4)
                }
            )
        }
    }

    private fun divider(): View = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        addView(
            View(context).apply { setBackgroundColor(Color.argb(20, 255, 255, 255)) },
            LayoutParams(LayoutParams.MATCH_PARENT, max(1, dp(1))).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        )
    }

    private fun rowView(row: Row): View {
        val view = CallsPopoverRow(context, row, room, row.key in busy)

        view.onPress = press@{
            if (row.disabled || row.key in busy) return@press

            busy.add(row.key)
            refresh()

            row.run {
                // The answer may come on the engine's own thread: the rows are this one's.
                val settle = Runnable {
                    busy.remove(row.key)
                    refresh()
                }

                if (Looper.myLooper() == Looper.getMainLooper()) settle.run() else main.post(settle)
            }
        }

        return view
    }

    companion object {
        /** The red a menu says danger in, lightened to be read on the card: Remove from Call, Muted by a moderator. */
        internal val DANGER_INK = Color.rgb(255, 150, 140)
    }
}

/**
 * One row: its glyph (on a tile of its own in the room's menu), what it does and, below, how; and for a rule of the
 * room, its switch. One element to TalkBack, a switch for a rule and a button for an action.
 */
private class CallsPopoverRow(
    context: Context,
    row: CallsPopover.Row,
    room: Boolean,
    busy: Boolean,
) : LinearLayout(context) {
    var onPress: (() -> Unit)? = null

    private val ground = GradientDrawable()
    private val rest: Int

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        val accent = CallsTheme.accent(context)
        val warning = CallsTheme.warning(context)
        val on = row.on == true

        orientation = HORIZONTAL
        // In the room's menu the text starts level with the tile's top and the switch level with the title; a
        // person's rows are one line each, centred.
        gravity = if (room) Gravity.TOP else Gravity.CENTER_VERTICAL
        minimumHeight = dp(36)

        rest = if (row.highlight) CallsTheme.withAlpha(warning, 36) else Color.TRANSPARENT
        ground.cornerRadius = dp(if (room) 11 else 9).toFloat()
        ground.setColor(rest)
        background = ground

        // Top, start, bottom and end.
        val inset = if (room) intArrayOf(10, 10, 12, 10) else intArrayOf(6, 8, 6, 8)

        setPaddingRelative(dp(inset[1]), dp(inset[0]), dp(inset[3]), dp(inset[2]))

        val glyphColor = when {
            row.danger -> CallsPopover.DANGER_INK
            row.highlight -> warning
            on -> accent
            else -> Color.argb(179, 255, 255, 255)
        }

        val iconSize = dp(if (room) 34 else 20)
        val icon = FrameLayout(context)

        if (room) {
            icon.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (on) CallsTheme.withAlpha(accent, 51) else Color.argb(18, 255, 255, 255))
            }
        }

        icon.addView(
            if (busy) CallsPopoverSpinner(context, glyphColor) else CallsIconView(context).apply {
                set(row.icon)
                this.glyphColor = glyphColor
            },
            FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
        )

        addView(icon, LayoutParams(iconSize, iconSize))

        val text = LinearLayout(context).apply { orientation = VERTICAL }

        text.addView(
            TextView(context).apply {
                this.text = row.label
                textSize = 13f
                typeface = CallsTheme.MEDIUM
                setTextColor(if (row.danger) CallsPopover.DANGER_INK else Color.WHITE)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        if (!row.note.isNullOrEmpty()) {
            text.addView(
                TextView(context).apply {
                    this.text = row.note
                    textSize = 11.5f
                    setTextColor(Color.argb(140, 255, 255, 255))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (room) 3 else 2) }
            )
        }

        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(if (room) 14 else 10) })

        if (row.on != null) {
            addView(CallsPopoverSwitch(context, on), LayoutParams(dp(34), dp(20)).apply { marginStart = dp(14) })
        }

        for (index in 0 until childCount) {
            getChildAt(index).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        contentDescription = listOfNotNull(row.label, row.note?.takeIf { it.isNotEmpty() }).joinToString(". ")
        isFocusable = true
        isClickable = true
        // A row waiting for its answer takes no second press, and one that can't be done now none at all: dimmed, as the
        // web's `aria-disabled` row.
        isEnabled = !busy && !row.disabled
        if (row.disabled) alpha = 0.55f

        val rule = row.on

        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)

                info.className = if (rule != null) "android.widget.Switch" else "android.widget.Button"

                if (rule != null) {
                    info.isCheckable = true
                    info.isChecked = rule
                }
            }
        })

        setOnClickListener {
            flash()
            onPress?.invoke()
        }
    }

    /** The press shown for a moment, as the web's row lights under the pointer. */
    private fun flash() {
        val pressed = Color.argb(20, 255, 255, 255)

        ground.setColor(pressed)

        ValueAnimator.ofArgb(pressed, rest).apply {
            startDelay = 50
            duration = 250
            addUpdateListener { ground.setColor(it.animatedValue as Int) }
        }.start()
    }
}

/** On or off, for a rule of the room: `.bm-call-moderation-switch`, 34 by 20 with a 14 knob. Drawn, not pressed. */
private class CallsPopoverSwitch(context: Context, on: Boolean) : FrameLayout(context) {
    init {
        val density = resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (on) CallsTheme.accent(context) else Color.argb(51, 255, 255, 255))
        }

        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        val knob = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = 1.5f * density
        }

        // From the start edge, which is the right one in a right-to-left language.
        addView(knob, LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(3)
            marginStart = dp(if (on) 17 else 3)
        })
    }
}

/**
 * A row waiting for its answer: a faint ring with a short arc turning round it, in the row's own colour. It stands
 * still where the person asked for no animations, which still says the row is busy.
 */
private class CallsPopoverSpinner(context: Context, color: Int) : View(context) {
    private val stroke = 2f * resources.displayMetrics.density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        this.color = CallsTheme.withAlpha(color, 51)
    }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private val bounds = RectF()
    private var angle = 0f

    private val turn = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        val animating = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

        if (animating) turn.start()
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
