package cloud.wordplus.realtime.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cloud.wordplus.realtime.ui.calls.CallsAddPerson
import cloud.wordplus.realtime.ui.calls.CallsAddPersonStore
import cloud.wordplus.realtime.ui.calls.CallsConfig
import cloud.wordplus.realtime.ui.calls.CallsConfigStore
import cloud.wordplus.realtime.ui.calls.CallsRoomParticipant
import cloud.wordplus.realtime.ui.calls.CallsStage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every state of the call screen with no call: calling,
 * ringing, connecting, a call by voice and by video, the microphone and camera off, the other side lost, this side
 * reconnecting, a group call being joined, and a group call by video and by voice, each in light and dark and right to left. The app's pieces
 * (`CallScreen.pieces`) and look (`CallScreen.configure`) are drawn as in a call, and an app that copied the screen
 * (`./gradlew wordplusUiAdd`) has this file in its copy, so the preview draws the copy.
 *
 * `CallScreen.preview(context)` lists the states; `CallScreen.preview(context, CallScreenPreviewActivity.Shown.VIDEO)`
 * shows one.
 */
class CallScreenPreviewActivity : Activity() {
    /** A state the screen can be in. */
    enum class Shown(val title: String) {
        CALLING("Calling"),
        RINGING("Ringing in"),
        CONNECTING("Connecting"),
        VOICE("In a call by voice"),
        VIDEO("In a call by video"),
        OFF("Microphone and camera off"),
        PEER_LOST("The other side lost"),
        RECONNECTING("This side reconnecting"),
        GROUP_JOINING("Group call being joined"),
        GROUP("Group call by video"),
        GROUP_VOICE("Group call by voice"),
        OPTIMIZING("A camera changing codec"),
        ADD_PERSON("Add Person"),
        GROUP_WAITING("Group call waiting for a place"),
        RECORDING("Group call being recorded");

        /** A group call, whose grid the stage draws. */
        val isGroup: Boolean get() = this == GROUP_JOINING || this == GROUP || this == GROUP_VOICE || this == OPTIMIZING || this == GROUP_WAITING || this == RECORDING
    }

    private var dark = false
    private var rightToLeft = false
    private var ours: CallsConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shown = intent.getStringExtra(EXTRA_SHOWN)?.let { name -> Shown.entries.firstOrNull { it.name == name } }
        if (shown == null) list() else draw(shown)
    }

    override fun onDestroy() {
        // The app's own look back as it was.
        ours?.let { CallsConfigStore.config = it }
        CallsAddPersonStore.current = null
        super.onDestroy()
    }

    private fun list() {
        title = "Call screen"
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (shown in Shown.entries) {
            column.addView(TextView(this).apply {
                text = shown.title
                textSize = 17f
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isClickable = true
                setOnClickListener { startActivity(intent(this@CallScreenPreviewActivity, shown)) }
            })
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun draw(shown: Shown) {
        title = shown.title
        if (ours == null) ours = CallsConfigStore.config
        CallsConfigStore.config = ours!!.copy(darkMode = dark)

        val activity = this
        val stage = CallsStage(this, object : CallsStage.Host {
            override val activity: Activity get() = activity
            override val inCornerWindow: Boolean get() = false
            override fun requestCallPermissions(state: JSONObject) {}
            override fun startScreenCapture() {}
            override fun enterCornerWindow() {}
            override fun dismissKeyguard() {}
            override fun closeCallScreen() {}
        })
        stage.layoutDirection = if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val root = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(switches(shown), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        setContentView(root)

        CallsAddPersonStore.current = if (shown == Shown.ADD_PERSON) addPerson() else null
        stage.onScreenState(state(shown))
        // A group call being joined has this side alone, as a host draws it until the room reports anyone.
        if (shown.isGroup) {
            stage.onScreenRoster(if (shown == Shown.GROUP_JOINING || shown == Shown.GROUP_WAITING) people().take(1) else people(optimizing = shown == Shown.OPTIMIZING))
        }
        // REC stands in the bar a roster brings, as a call's next state draws it.
        if (shown == Shown.RECORDING) stage.post { stage.onScreenState(state(shown)) }
        if (shown == Shown.ADD_PERSON) stage.post { stage.showAddPerson() }
    }

    /** Dark and right to left, which draw the state again. */
    private fun switches(shown: Shown): View {
        fun switch(label: String, on: Boolean, flip: () -> Unit) = TextView(this).apply {
            text = label
            contentDescription = label
            setTextColor(if (on) Color.WHITE else Color.LTGRAY)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener {
                flip()
                draw(shown)
            }
        }
        return LinearLayout(this).apply {
            setBackgroundColor(0x66000000)
            addView(switch("Dark", dark) { dark = !dark })
            addView(switch("RTL", rightToLeft) { rightToLeft = !rightToLeft })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal const val EXTRA_SHOWN = "cloud.wordplus.realtime.ui.preview.shown"

        internal fun intent(context: Context, shown: Shown?) = Intent(context, CallScreenPreviewActivity::class.java).apply {
            if (shown != null) putExtra(EXTRA_SHOWN, shown.name)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** The state as the call screen hands it to the screen in a call (CallScreen's screenState). */
        fun state(shown: Shown): JSONObject {
            val phase = when (shown) {
                Shown.CALLING -> "ringing_out"
                Shown.RINGING -> "ringing_in"
                Shown.CONNECTING, Shown.GROUP_JOINING, Shown.GROUP_WAITING -> "connecting"
                Shown.PEER_LOST, Shown.RECONNECTING -> "reconnecting"
                else -> "in_progress"
            }
            val group = shown.isGroup
            val video = shown != Shown.VOICE && shown != Shown.GROUP_VOICE
            val off = shown == Shown.OFF
            val known = phase == "in_progress" || phase == "reconnecting"
            return JSONObject()
                .put("phase", phase)
                .put("trouble", when (shown) { Shown.PEER_LOST -> "peer"; Shown.RECONNECTING -> "self"; else -> JSONObject.NULL })
                .put("threadId", JSONObject.NULL)
                .put("messageId", JSONObject.NULL)
                .put("type", if (video) "video" else "audio")
                .put("group", group)
                .put("waiting", shown == Shown.GROUP_WAITING)
                .put("recording", if (shown == Shown.RECORDING) {
                    JSONObject().put("state", "on").put("id", "preview").put("since", System.currentTimeMillis() / 1000 - 65).put("can", false).put("tell", "notice")
                } else {
                    JSONObject.NULL
                })
                .put("mode", JSONObject.NULL)
                .put("durationMs", if (known) 83_000 else 0)
                .put("peerName", if (group) "Daily meeting" else "Ann")
                .put("peer", JSONObject().put("camera", false).put("microphone", !off).put("screen", false).put("known", known))
                .put("local", JSONObject().put("muted", off).put("camera", false).put("screen", false).put("front", true).put("route", ""))
                .put("minimised", false)
                .put("callMode", if (group) "participatory" else JSONObject.NULL)
                .put("roomLocked", false)
                .put("roomMuted", false)
                .put("canModerate", false)
                .put("handRaised", false)
                .put("online", JSONArray())
                .put("peerOnline", true)
                .put("canShareScreen", true)
        }

        /** Whom Add Person offers with no call: someone not rung yet, someone ringing, someone who was busy. */
        fun addPerson() = CallsAddPerson(
            people = { done ->
                done(listOf(
                    CallsAddPerson.Person("43", "Dana", ""),
                    CallsAddPerson.Person("44", "Eli", ""),
                    CallsAddPerson.Person("45", "Finn", ""),
                ))
            },
            add = { _, done -> done(null) },
            rings = {
                listOf(
                    CallsAddPerson.Ring("ring-44", "44", "ringing", true),
                    CallsAddPerson.Ring("ring-45", "45", "busy", false),
                )
            },
            cancel = { _, done -> done(null) },
        )

        /**
         * A group call's people with no pictures: this side, someone speaking, someone muted, someone else. With
         * [optimizing], the one speaking is changing their camera's codec, under the tile's placeholder.
         */
        fun people(optimizing: Boolean = false): List<CallsRoomParticipant> {
            fun person(identity: String, name: String, local: Boolean = false, speaking: Boolean = false, muted: Boolean = false) =
                CallsRoomParticipant(
                    identity = identity, name = name, avatar = "", isLocal = local, video = null, screen = null,
                    speaking = speaking, audioLevel = if (speaking) 0.6f else 0f, muted = muted, micMuted = false,
                    handRaised = false, isPinned = false, canPublish = true, canAdmin = false, mode = "participant",
                )
            return listOf(
                person("me", "You", local = true),
                person("ann", "Ann", speaking = true).copy(optimizing = optimizing),
                person("ben", "Ben", muted = true),
                person("cara", "Cara"),
            )
        }
    }
}
