package cloud.wordplus.realtime.calls

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The ring over a locked phone, and the Answer of the notification (SystemCalls).
 *
 * - **ACTION_RING**, the notification's full-screen intent: who calls, with Answer and Decline, shown over the lock
 *   screen with the screen turned on. It goes once the ring is over.
 * - **ACTION_ANSWER**, the notification's Answer: answered here, then the app's own screen opens with the call. An
 *   activity, not a receiver, as Android 12 and later no longer let a receiver a notification started open one.
 *
 * Plain views, as the call screen's own are: the call itself is the app's to show (the call screen of realtime-ui comes
 * up by itself).
 */
class SystemCallsActivity : Activity() {
    private var id: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        handle(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        id?.let { SystemCalls.entry(it)?.ringScreen = null }
        super.onDestroy()
    }

    private fun handle(intent: android.content.Intent) {
        val id = intent.getStringExtra(SystemCalls.EXTRA_CALL)
        val entry = id?.let { SystemCalls.entry(it) }
        if (id == null || entry == null || entry.ended) return finish()
        this.id = id
        when (intent.action) {
            SystemCalls.ACTION_ANSWER -> answer(id)
            else -> showRing(entry)
        }
    }

    private fun answer(id: String) {
        SystemCalls.answerFromSystem(id)
        SystemCalls.appScreen()?.let { startActivity(it) }
        finish()
    }

    private fun showRing(entry: SystemCalls.Entry) {
        entry.ringScreen = { runOnUiThread { finish() } }
        val dp = resources.displayMetrics.density
        val name = TextView(this).apply {
            text = entry.name
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val kind = TextView(this).apply {
            text = getString(if (entry.video) R.string.wordplus_calls_incoming_video else R.string.wordplus_calls_incoming_audio)
            textSize = 16f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button(R.string.wordplus_calls_decline, Color.rgb(220, 53, 69)) {
                SystemCalls.declineFromSystem(entry.id)
                finish()
            })
            addView(button(R.string.wordplus_calls_answer, Color.rgb(25, 135, 84)) { answer(entry.id) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(17, 24, 39))
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (48 * dp).toInt())
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (120 * dp).toInt() })
            addView(kind)
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (64 * dp).toInt() })
        })
    }

    private fun button(label: Int, color: Int, action: () -> Unit): Button {
        val dp = resources.displayMetrics.density
        return Button(this).apply {
            text = getString(label)
            contentDescription = getString(label)
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * dp
                setColor(color)
            }
            minWidth = (132 * dp).toInt()
            minHeight = (56 * dp).toInt()
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (12 * dp).toInt()
                marginEnd = (12 * dp).toInt()
            }
        }
    }

    /** Over the keyguard and with the screen on, as a ring is. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Answering takes the person to the app, which a locked phone asks to unlock for.
        if (intent?.action == SystemCalls.ACTION_ANSWER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
    }
}
