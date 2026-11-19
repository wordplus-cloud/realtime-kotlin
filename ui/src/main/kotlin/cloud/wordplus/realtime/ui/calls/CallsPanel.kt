package cloud.wordplus.realtime.ui.calls

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat

/** One line in a panel. */
data class CallsPanelRow(
    val title: String,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    /** A second, quieter line saying what the row does, as the web's moderation menus say it. */
    val note: String? = null,
    val run: () -> Unit
)

/**
 * The call's own way of asking a question.
 *
 * Configure and Participants were `AlertDialog`s — which work, and look like
 * what they are: a piece of Android put on top of the call rather than a piece
 * of the call. They did not look built into the call natively.
 *
 * This is the same list drawn on the call screen itself, in the language the
 * rest of it already speaks — the glass the peer notice and the error card are
 * made of, the card radius, the dock's own width. Nothing is presented over
 * the stage, so nothing can take it away from the renderer.
 */
class CallsPanel(context: Context) : FrameLayout(context) {
    private val card = LinearLayout(context)
    private var onDismiss: (() -> Unit)? = null

    /** The second line of the header, kept so a moving figure can be replaced. */
    private var detail: TextView? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        // Clear rather than dimmed: the web does not dim behind its Configure
        // panel either, and the call is the thing worth looking at. Clickable
        // so the tap that closes it stops here instead of reaching the stage,
        // which would toggle the dock on the way past.
        isClickable = true
        setOnClickListener { onDismiss?.invoke() }

        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            cornerRadius = 14f * resources.displayMetrics.density
            setColor(Color.argb(209, 0, 0, 0))
            setStroke(dp(1), Color.argb(20, 255, 255, 255))
        }

        addView(
            card,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        )
    }

    /** How far above the bottom the card sits — the dock's clearance plus a gap. */
    fun placeAbove(bottomMargin: Int) {
        (card.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = bottomMargin
            card.layoutParams = it
        }
    }

    fun fill(title: String, message: String?, rows: List<CallsPanelRow>, dismiss: () -> Unit) {
        onDismiss = dismiss

        card.removeAllViews()
        card.addView(header(title, message))

        for (row in rows) {
            card.addView(separator())
            card.addView(line(row))
        }
    }

    private fun header(title: String, message: String?): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        holder.addView(TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            text = title
        })

        detail = null

        if (!message.isNullOrEmpty()) {
            val line = TextView(context).apply {
                textSize = 13f
                setTextColor(Color.argb(166, 255, 255, 255))
                setPadding(0, dp(4), 0, 0)
                text = message
            }

            holder.addView(line)
            detail = line
        }

        return holder
    }

    /**
     * A new second line for a card already on screen.
     *
     * The byte counts behind the gear are sampled every two seconds and the
     * web counts them up as they arrive. Written once when the card opened,
     * they would sit at whatever they read at that moment.
     */
    fun update(message: String?) {
        detail?.text = message ?: ""
    }

    private fun separator(): View = View(context).apply {
        setBackgroundColor(Color.argb(20, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun line(row: CallsPanelRow): View = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(if (row.destructive) CallsTheme.recording(context) else Color.WHITE)

        // A tick rather than a separate control: the list is both what the
        // choices are and which one is in use.
        val title = if (row.selected) "✓  ${row.title}" else row.title
        val note = row.note

        if (note.isNullOrEmpty()) {
            text = title
        } else {
            val shown = SpannableStringBuilder(title).append('\n')
            val start = shown.length
            shown.append(note)
            shown.setSpan(AbsoluteSizeSpan(12, true), start, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            shown.setSpan(ForegroundColorSpan(Color.argb(153, 255, 255, 255)), start, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = shown
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setLineSpacing(dp(2).toFloat(), 1f)
        }

        minHeight = dp(48)
        isClickable = true

        setOnClickListener {
            onDismiss?.invoke()
            row.run()
        }

        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}

/**
 * Add Person as a host gives it (realtime-ui's `people` and `addPerson`, with the rings so far): none, and the dock has no
 * such button, so a host that knows nothing of it draws nothing new. On the main thread.
 */
class CallsAddPerson(
    /** Whom the user may bring in, asked as the panel opens. */
    val people: (done: (List<Person>) -> Unit) -> Unit,
    /** Rings them in: `done` with null, or the error to show. */
    val add: (Person, done: (String?) -> Unit) -> Unit,
    /** The rings so far, asked each time the panel draws: a person's last one is what their row says. */
    val rings: () -> List<Ring>,
    /** Calls a ring off: `done` with null, or the error to show. */
    val cancel: (String, done: (String?) -> Unit) -> Unit,
) {
    data class Person(val id: String, val name: String, val avatar: String)

    /** Someone rung into the call: `ringing`, then `answered`, `rejected`, `busy`, `no_answer` or `cancelled`. */
    data class Ring(val id: String, val to: String, val state: String, val canCancel: Boolean)
}

object CallsAddPersonStore {
    @Volatile
    var current: CallsAddPerson? = null
}

/**
 * Add Person (realtime-ui's add-person.tsx): whom the user may bring in, as the call settings' card, on the glass of the
 * panel above. A press rings them. The card then stays and their row says how the ring stands, with Cancel while it rings,
 * where the web closes it: the row is the one place a phone says so. A failure closes it and the screen says why.
 */
class CallsAddPersonPanel(
    context: Context,
    private val close: () -> Unit,
    private val failed: (String) -> Unit,
) : FrameLayout(context) {
    private val card = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val rows = LinearLayout(context)

    /** Null until the host answers. */
    private var people: List<CallsAddPerson.Person>? = null
    private var adding: String? = null
    private val cancelling = mutableSetOf<String>()

    /** What the rows were last drawn from. */
    private data class Drawn(
        val people: List<CallsAddPerson.Person>?,
        val rings: List<CallsAddPerson.Ring>,
        val adding: String?,
        val cancelling: Set<String>,
    )

    private var drawn: Drawn? = null

    private val back = CallsBack(this) { close() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        // Clickable so the tap that closes it stops here instead of reaching the stage, as the panel above.
        isClickable = true
        setOnClickListener { close() }

        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        card.background = GradientDrawable().apply {
            cornerRadius = 14f * resources.displayMetrics.density
            setColor(Color.argb(209, 0, 0, 0))
            setStroke(dp(1), Color.argb(20, 255, 255, 255))
        }

        card.addView(TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = CallsTheme.SEMIBOLD
            setPadding(dp(16), dp(14), dp(16), dp(10))
            text = CallsTheme.string(context, "add_person", "Add Person")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        })

        rows.orientation = LinearLayout.VERTICAL
        scroll.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(
            card,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(48)
            }
        )
    }

    /** How far above the bottom the card sits, as the panel above. */
    fun placeAbove(bottomMargin: Int) {
        (card.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = bottomMargin
            card.layoutParams = it
        }
    }

    /** Asks the host whom it may offer, and draws what it has meanwhile. */
    fun open() {
        draw()

        CallsAddPersonStore.current?.people?.invoke { found ->
            people = found
            draw()
        }
    }

    /**
     * Who is offered and how each ring stands, again: the rings change while it is open. Only where something did, as the
     * call draws this with each of its changes, and rows made again would drop a finger on one.
     */
    fun draw() {
        val people = people
        val rings = CallsAddPersonStore.current?.rings?.invoke() ?: emptyList()
        val now = Drawn(people, rings, adding, cancelling.toSet())

        if (now == drawn) return

        drawn = now
        rows.removeAllViews()

        if (people == null) {
            rows.addView(line(CallsTheme.string(context, "add_person_loading", "Loading...")))
            return
        }

        if (people.isEmpty()) {
            rows.addView(line(CallsTheme.string(context, "add_person_empty", "There is no one else to add")))
            return
        }

        for (person in people) {
            rows.addView(separator())
            rows.addView(row(person, rings.lastOrNull { it.to == person.id }))
        }
    }

    private fun line(words: String): View = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.argb(166, 255, 255, 255))
        setPadding(dp(16), dp(2), dp(16), dp(14))
        text = words
    }

    private fun separator(): View = View(context).apply {
        setBackgroundColor(Color.argb(20, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun row(person: CallsAddPerson.Person, ring: CallsAddPerson.Ring?): View {
        val ringing = ring?.state == "ringing"
        val note = ring?.let { said(context, it.state) } ?: ""

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            isEnabled = adding == null && !ringing
            contentDescription = if (note.isEmpty()) person.name else person.name + ", " + note
            setOnClickListener { add(person) }
        }

        row.addView(CallsAddPersonFace(context, person), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            this.text = person.name
        })
        if (note.isNotEmpty()) {
            text.addView(TextView(context).apply {
                textSize = 12f
                setTextColor(Color.argb(153, 255, 255, 255))
                this.text = note
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (adding == person.id) {
            row.addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
        } else if (ring != null && ringing && ring.canCancel) {
            row.addView(TextView(context).apply {
                textSize = 13f
                typeface = CallsTheme.SEMIBOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 15 * resources.displayMetrics.density
                    setColor(Color.argb(26, 255, 255, 255))
                }
                val word = CallsTheme.string(context, "cancel", "Cancel")
                this.text = word
                contentDescription = word + " " + person.name
                isClickable = true
                isEnabled = ring.id !in cancelling
                setOnClickListener { cancel(ring.id) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)))
        }

        return row
    }

    private fun add(person: CallsAddPerson.Person) {
        val host = CallsAddPersonStore.current ?: return
        if (adding != null) return

        adding = person.id
        draw()

        host.add(person) { error ->
            adding = null
            if (error != null) failed(error) else draw()
        }
    }

    private fun cancel(ring: String) {
        val host = CallsAddPersonStore.current ?: return

        cancelling.add(ring)
        draw()

        host.cancel(ring) { error ->
            cancelling.remove(ring)
            if (error != null) failed(error) else draw()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        back.attach()
    }

    override fun onDetachedFromWindow() {
        back.detach()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = back.handles(event) || super.dispatchKeyEvent(event)

    companion object {
        /**
         * How a ring stands, in the app's words: `add_person_ringing`, `add_person_answered`, `add_person_rejected`,
         * `add_person_busy`, `add_person_no_answer`, `add_person_cancelled`.
         */
        fun said(context: Context, state: String): String = when (state) {
            "ringing" -> CallsTheme.string(context, "add_person_ringing", "Ringing…")
            "answered" -> CallsTheme.string(context, "add_person_answered", "Answered")
            "rejected" -> CallsTheme.string(context, "add_person_rejected", "Declined")
            "busy" -> CallsTheme.string(context, "add_person_busy", "Busy")
            "no_answer" -> CallsTheme.string(context, "add_person_no_answer", "No answer")
            "cancelled" -> CallsTheme.string(context, "add_person_cancelled", "Cancelled")
            else -> ""
        }
    }
}

/** A person's face in the panel above: their picture, or their letters. */
private class CallsAddPersonFace(context: Context, person: CallsAddPerson.Person) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = CallsTheme.SEMIBOLD
    }
    private val letters = CallsAvatars.initials(person.name)
    private var picture: Bitmap? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        CallsAvatars.load(context, person.avatar) {
            picture = it
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val radius = width / 2f
        val bitmap = picture

        if (bitmap != null) {
            val by = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val scale = Matrix().apply {
                setScale(by, by)
                postTranslate((width - bitmap.width * by) / 2f, (height - bitmap.height * by) / 2f)
            }
            fill.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(scale) }
            canvas.drawCircle(radius, radius, radius, fill)
            return
        }

        fill.shader = null
        fill.color = Color.argb(31, 255, 255, 255)
        canvas.drawCircle(radius, radius, radius, fill)
        words.textSize = height * 0.39f
        canvas.drawText(letters, radius, radius - (words.descent() + words.ascent()) / 2f, words)
    }
}

/**
 * The system's Back, for a card drawn on the call while it is up: the back callback where the app takes Back that way
 * (API 33 and later, predictive back), and the key where it does not ([handles]). Back then closes the card and nothing
 * else: the call stays as it is.
 */
internal class CallsBack(private val view: View, private val close: () -> Unit) {
    private var callback: Any? = null

    fun attach() {
        view.isFocusableInTouchMode = true
        view.requestFocus()

        if (Build.VERSION.SDK_INT < 33 || callback != null) return

        val dispatcher = view.findOnBackInvokedDispatcher() ?: return
        val answer = OnBackInvokedCallback { close() }

        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, answer)
        callback = answer
    }

    fun detach() {
        val answer = callback ?: return

        callback = null

        if (Build.VERSION.SDK_INT >= 33) {
            view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(answer as OnBackInvokedCallback)
        }
    }

    /** True where `event` is Back (or Escape), which closes the card as it goes up. */
    fun handles(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_ESCAPE) return false

        if (event.action == KeyEvent.ACTION_UP) close()

        return true
    }
}

/**
 * A question on the call, as the messenger's confirm dialog asks it: a centred card over a dimmed call with its title,
 * a line under it, and two buttons, Cancel and the answer, the answer filled red where it is a danger. Drawn on the
 * call, never as a system dialog. Cancel, a press outside the card and the system's back answer no.
 *
 * A recording's questions (realtime-ui's recording.tsx, CallDialog) add the recording's red badge before the title, a
 * note under the line, the start's facts as rows, and their own word for No ("Leave Call"), whose press alone calls
 * [no]. The question to everyone else in the call is answered before anything else: with [dismissible] false a press
 * outside it and the system's back do nothing.
 */
class CallsConfirm(
    context: Context,
    title: String,
    message: String,
    answer: String,
    danger: Boolean,
    private val yes: () -> Unit,
    cancel: String? = null,
    note: String? = null,
    facts: List<Pair<String, String>> = emptyList(),
    badge: String? = null,
    private val dismissible: Boolean = true,
    private val no: (() -> Unit)? = null,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val card = LinearLayout(context)
    private var closed = false

    /** Called once it has gone, whichever way. */
    var onClose: (() -> Unit)? = null

    private fun dp(value: Int): Int = (value * density).toInt()

    init {
        setBackgroundColor(Color.argb(128, 0, 0, 0))
        isClickable = true
        setOnClickListener { if (dismissible) close() }

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(20), dp(20), dp(16))
        card.isClickable = true
        card.elevation = 24 * density
        card.background = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(Color.rgb(22, 24, 30))
            setStroke(maxOf(1, dp(1)), Color.argb(23, 255, 255, 255))
        }

        val heading = TextView(context).apply {
            text = title
            textSize = 16f
            typeface = CallsTheme.SEMIBOLD
            setTextColor(Color.WHITE)
        }

        if (badge != null) {
            // The title with the recording's red badge before it, as the web's dialog head.
            val red = CallsTheme.recording(context)
            val disc = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(46, Color.red(red), Color.green(red), Color.blue(red)))
                }
                addView(CallsIconView(context).apply {
                    set(badge)
                    glyphColor = red
                }, LayoutParams(dp(16), dp(16), Gravity.CENTER))
            }

            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(disc, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            })
        } else {
            card.addView(heading)
        }

        card.addView(TextView(context).apply {
            text = message
            textSize = 13.5f
            setTextColor(Color.argb(184, 255, 255, 255))
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (badge != null) 10 else 8) })

        // The start's facts, a label and its value on each row (the web's `wplus-call-dialog-facts`).
        facts.forEachIndexed { index, (label, value) ->
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 12.5f
                    setTextColor(Color.argb(140, 255, 255, 255))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply {
                    text = value
                    textSize = 12.5f
                    typeface = CallsTheme.MEDIUM
                    gravity = Gravity.END
                    setTextColor(Color.argb(224, 255, 255, 255))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (index == 0) 12 else 6)
            })
        }

        if (!note.isNullOrEmpty()) {
            card.addView(TextView(context).apply {
                text = note
                textSize = 12f
                setTextColor(Color.argb(140, 255, 255, 255))
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        buttons.addView(button(cancel ?: CallsTheme.string(context, "cancel", "Cancel"), Color.argb(26, 255, 255, 255), Color.WHITE) {
            close()
            no?.invoke()
        })
        buttons.addView(
            button(
                answer,
                if (danger) CallsTheme.recording(context) else CallsTheme.accent(context),
                if (danger) Color.WHITE else CallsTheme.color(context, "text-on-accent", Color.WHITE)
            ) {
                close()
                yes()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) }
        )

        card.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = dp(24)
            marginEnd = dp(24)
        })

        ViewCompat.setAccessibilityPaneTitle(card, title)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 360 at most, as the messenger's dialog, centred on a wider call.
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val side = maxOf(dp(24), (width - dp(360)) / 2)

        (card.layoutParams as LayoutParams).let {
            it.marginStart = side
            it.marginEnd = side
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // A question that must be answered keeps Back from anything under it, and does nothing with it.
    private val back = CallsBack(this) { if (dismissible) close() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        back.attach()

        alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        animate().alpha(1f).setDuration(140).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    override fun onDetachedFromWindow() {
        back.detach()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = back.handles(event) || super.dispatchKeyEvent(event)

    private fun button(words: String, ground: Int, ink: Int, press: () -> Unit): TextView = TextView(context).apply {
        text = words
        textSize = 13f
        typeface = CallsTheme.SEMIBOLD
        gravity = Gravity.CENTER
        maxLines = 1
        minWidth = dp(84)
        setTextColor(ink)
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(ground)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { press() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
    }

    fun close() {
        if (closed) return

        closed = true
        (parent as? ViewGroup)?.removeView(this)
        onClose?.invoke()
    }
}
