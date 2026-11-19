package cloud.wordplus.realtime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cloud.wordplus.realtime.ui.calls.CallsIconView
import cloud.wordplus.realtime.ui.calls.CallsIcons
import cloud.wordplus.realtime.ui.calls.CallsTheme
import kotlin.math.roundToInt

// Who is online: realtime-ui's OnlineList, PersonRow, RowAction and OnlineCount (components/presence/online-list.tsx).
// Views only: whoever draws them gives the people, their rows and what may be done with them. An app feeds them from a
// presence room, as docs/android/presence.md shows. None of them reads a room or a connection.

/**
 * A title with how many are listed, an optional close button, and the rows, which scroll. With nobody in it, the line it
 * says instead. It takes the width and the height the app gives it.
 */
class OnlineList @JvmOverloads constructor(
    context: Context,
    count: Int = 0,
    rows: List<View> = emptyList(),
    title: String? = null,
    empty: String? = null,
    onClose: (() -> Unit)? = null,
) : LinearLayout(context) {
    /**
     * How many are listed, for the title and for the empty line. The rows do not set it, as a room may count more than it
     * lists.
     */
    var count: Int = count
        set(value) {
            field = value
            draw()
        }

    /** The rows: a [PersonRow] each, or the app's own. */
    var rows: List<View> = rows
        set(value) {
            field = value
            drawRows()
        }

    /** The title before the count: `Online` by default (`online`). */
    var title: String? = title
        set(value) {
            field = value
            draw()
        }

    /** What it says with nobody in it: `No users online` by default (`online_empty`). */
    var empty: String? = empty
        set(value) {
            field = value
            draw()
        }

    /** A close button in the title, where the list can be closed. */
    var onClose: (() -> Unit)? = onClose
        set(value) {
            field = value
            draw()
        }

    private val head = TextView(context)
    private val close = RowAction(context, CallsIcons.close, "") { this.onClose?.invoke() }
    private val line = View(context)
    private val scroll = ScrollView(context)
    private val stack = LinearLayout(context)
    private val emptyLine = TextView(context)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /** The title, as it is drawn. */
    internal val titleShown: String get() = head.text.toString()

    /** The empty line, while it shows. */
    internal val emptyShown: String? get() = if (emptyLine.visibility == View.VISIBLE) emptyLine.text.toString() else null

    /** The close button, while it shows. */
    internal val closeShown: RowAction? get() = if (close.visibility == View.VISIBLE) close else null

    /** Where the rows scroll, for a test. */
    internal val scrollShown: ScrollView get() = scroll

    init {
        orientation = VERTICAL

        head.textSize = 11f
        head.typeface = Typeface.DEFAULT_BOLD
        head.maxLines = 1
        head.ellipsize = TextUtils.TruncateAt.END
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) head.isAccessibilityHeading = true

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(12), dp(10), dp(4), dp(10))
            minimumHeight = dp(36)
            addView(head, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, maxOf(1, resources.displayMetrics.density.roundToInt() / 2)))

        stack.orientation = VERTICAL
        stack.setPadding(dp(4), dp(4), dp(4), dp(4))
        scroll.isFillViewport = true
        scroll.addView(stack, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        emptyLine.textSize = 12f
        emptyLine.gravity = Gravity.CENTER
        emptyLine.setPadding(dp(22), dp(20), dp(22), dp(20))

        addView(emptyLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        draw()
        drawRows()
    }

    private fun draw() {
        val words = title ?: CallsTheme.string(context, "online", "Online")
        head.text = "$words ($count)".uppercase()
        head.setTextColor(PresenceColors.textTertiary(context))
        line.setBackgroundColor(PresenceColors.border(context))
        close.visibility = if (onClose == null) View.GONE else View.VISIBLE
        close.contentDescription = CallsTheme.string(context, "close", "Close")
        emptyLine.text = empty ?: CallsTheme.string(context, "online_empty", "No users online")
        emptyLine.setTextColor(PresenceColors.textTertiary(context))
        emptyLine.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    private fun drawRows() {
        stack.removeAllViews()
        rows.forEach { row ->
            (row.parent as? LinearLayout)?.removeView(row)
            stack.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
}

/** A person's row: the avatar with its dot, the name, a line under it, and what may be done with them at its end. */
class PersonRow @JvmOverloads constructor(
    context: Context,
    avatar: View,
    name: String,
    sub: String? = null,
    actions: List<View> = emptyList(),
) : LinearLayout(context) {
    /** The avatar: an [Avatar], or the app's own. */
    var avatar: View = avatar
        set(value) {
            removeView(field)
            field = value
            addView(value, 0, avatarParams())
        }

    /** The name. */
    var name: String = name
        set(value) {
            field = value
            draw()
        }

    /** The line under the name (`You`, a role). */
    var sub: String? = sub
        set(value) {
            field = value
            draw()
        }

    /** What may be done with the person, at the end of the row: a [RowAction] each, or the app's own. */
    var actions: List<View> = actions
        set(value) {
            field = value
            drawActions()
        }

    private val nameLabel = TextView(context)
    private val subLabel = TextView(context)
    private val actionsRow = LinearLayout(context)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun avatarParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }

    /** The line under the name, while it shows. */
    internal val subShown: String? get() = if (subLabel.visibility == View.VISIBLE) subLabel.text.toString() else null

    /** The name, as it is drawn. */
    internal val nameShown: String get() = nameLabel.text.toString()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(8), dp(6), dp(8), dp(6))

        nameLabel.textSize = 14f
        nameLabel.typeface = CallsTheme.SEMIBOLD
        nameLabel.maxLines = 1
        nameLabel.ellipsize = TextUtils.TruncateAt.END

        subLabel.textSize = 12f
        subLabel.maxLines = 1
        subLabel.ellipsize = TextUtils.TruncateAt.END

        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(nameLabel)
            addView(subLabel)
        }

        actionsRow.orientation = HORIZONTAL
        actionsRow.gravity = Gravity.CENTER_VERTICAL

        addView(avatar, avatarParams())
        addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actionsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        draw()
        drawActions()
    }

    private fun draw() {
        nameLabel.text = name
        nameLabel.setTextColor(PresenceColors.textPrimary(context))
        subLabel.text = sub
        subLabel.setTextColor(PresenceColors.textTertiary(context))
        subLabel.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun drawActions() {
        actionsRow.removeAllViews()
        actions.forEach { actionsRow.addView(it) }
        actionsRow.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
    }
}

/**
 * Something to do with a person, at the end of their row (a call): an icon, and what it does for a screen reader. `icon` a
 * path in a 24 grid, as Lucide's are ([icons] has ours by name), `label` what it does.
 */
class RowAction(context: Context, icon: String, label: String, private val action: () -> Unit) : FrameLayout(context) {
    companion object {
        /** Ours by name, for `icon`: the icons of the call screen's dock. */
        val icons: Map<String, String> = CallScreen.CallControl.icons
    }

    private val glyph = CallsIconView(context)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    init {
        contentDescription = label
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }

        glyph.set(icon)
        addView(glyph, LayoutParams(dp(16), dp(16), Gravity.CENTER))

        // A 40 dp target round the web's 28 dp button, as a finger needs: drawn only while it is held, with nothing to see
        // otherwise.
        val held = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(PresenceColors.hover(context))
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(held, dp(6)))
            addState(intArrayOf(), ColorDrawable(0))
        }
        draw()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY)
        super.onMeasure(side, side)
    }

    private fun draw() {
        glyph.glyphColor = if (isPressed) CallsTheme.accent(context) else PresenceColors.textTertiary(context)
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        draw()
    }
}

/** A live count: nothing at 0, `99+` past 99. */
class OnlineCount @JvmOverloads constructor(context: Context, count: Int = 0) : TextView(context) {
    var count: Int = count
        set(value) {
            field = value
            draw()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    init {
        textSize = 9.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = dp(15)
        minimumWidth = dp(15)
        height = dp(15)
        setPadding(dp(4), 0, dp(4), 0)
        draw()
    }

    private fun draw() {
        val said = said(count)
        text = said ?: ""
        setTextColor(CallsTheme.color(context, "text-on-accent", Color.WHITE))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(CallsTheme.accent(context))
        }
        contentDescription = said
        visibility = if (said == null) View.GONE else View.VISIBLE
    }

    companion object {
        /** What it draws, null at nothing. */
        internal fun said(count: Int): String? = if (count <= 0) null else if (count > 99) "99+" else count.toString()
    }
}
