package cloud.wordplus.realtime.ui

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.ui.calls.CallsConfigStore
import cloud.wordplus.realtime.ui.calls.CallsConnectionPill
import cloud.wordplus.realtime.ui.calls.CallsDevicePanel
import cloud.wordplus.realtime.ui.calls.CallsDock
import cloud.wordplus.realtime.ui.calls.CallsEngine
import cloud.wordplus.realtime.ui.calls.CallsOptimizingSaid
import cloud.wordplus.realtime.ui.calls.CallsOptimizingView
import cloud.wordplus.realtime.ui.calls.CallsStage
import cloud.wordplus.realtime.ui.calls.CallsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview (CallScreenPreviewActivity): every state draws
 * the real stage with no call, ringing in offers Answer and Reject, a call offers End Call, dark leaves the app's own
 * look as it was once closed, and the list names every state. `./gradlew connectedDebugAndroidTest`, as PiecesTest.
 */
@RunWith(AndroidJUnit4::class)
class PreviewTest {
    private fun said(view: View): List<String> =
        (if (view.visibility == View.VISIBLE) listOfNotNull(view.contentDescription?.toString()) else emptyList()) +
            ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { said(group.getChildAt(it)) } } ?: emptyList())

    /** The words on screen: every text view's text, under no hidden view. */
    private fun texts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()

        return listOfNotNull((view as? TextView)?.text?.toString()) +
            ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { texts(group.getChildAt(it)) } } ?: emptyList())
    }

    private fun stage(view: View): CallsStage? =
        view as? CallsStage ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { stage(group.getChildAt(it)) } }

    private fun <T> shown(shown: CallScreenPreviewActivity.Shown?, look: (CallScreenPreviewActivity) -> T): T {
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), shown)
        return ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var result: T? = null
            scenario.onActivity { result = look(it) }
            result!!
        }
    }

    @Test
    fun everyStateDrawsTheStage() {
        for (state in CallScreenPreviewActivity.Shown.entries) {
            val (words, names) = shown(state) { activity ->
                val root = activity.window.decorView
                assertNotNull("$state draws the stage", stage(root))
                said(root) to texts(root)
            }
            val group = state.isGroup
            if (state == CallScreenPreviewActivity.Shown.RINGING) {
                assertTrue("ringing in offers to answer: $words", "Answer" in words)
                assertTrue("ringing in offers to reject: $words", "Reject" in words)
            } else if (group) {
                // A group call is left, not ended, and draws its people. Host Controls is a moderator's alone, and
                // nobody in the preview moderates.
                assertTrue("$state offers Exit Chat: $words", "Exit Chat" in words)
                val alone = state == CallScreenPreviewActivity.Shown.GROUP_JOINING || state == CallScreenPreviewActivity.Shown.GROUP_WAITING
                val people = if (alone) listOf("You") else listOf("You", "Ann", "Ben", "Cara")
                assertTrue("$state draws its people: $names", people.all { it in names })
                assertFalse("$state offers Host Controls to nobody who moderates: $words", "Host Controls" in words)
            } else {
                assertTrue("$state offers End Call: $words", "End Call" in words)
            }
        }
    }

    /**
     * The web's group call while its room connects: the grid, no "Connecting..." over it, the spinner before the dock,
     * and the dock Show Chat and Exit Chat alone. Connected, the spinner is gone and the dock is whole.
     */
    @Test
    fun aGroupCallBeingJoinedSaysSoBeforeItsDock() {
        val (said, words) = shown(CallScreenPreviewActivity.Shown.GROUP_JOINING) { said(it.window.decorView) to texts(it.window.decorView) }
        assertFalse("nothing says Connecting... over the grid: $words", "Connecting..." in words)
        assertTrue("the dock starts with the connection: $said", said.any { it.startsWith("Connection Status (Quality)") })
        assertTrue("the way out is there: $said", "Exit Chat" in said)
        for (control in listOf("Enable Microphone", "Disable Microphone", "Enable Camera", "Disable Camera", "Speaker", "Device Settings")) {
            assertFalse("$control waits for the room: $said", control in said)
        }

        assertTrue("the bar says it connects: $said", "Connection Status (Quality), Connecting..." in said)

        val offered = shown(CallScreenPreviewActivity.Shown.GROUP) { said(it.window.decorView) }
        assertTrue("a connected call keeps its connection, as a dot: $offered", "Connection Status (Quality)" in offered)
        assertFalse("a connected call still turns its spinner: $offered", offered.any { it.startsWith("Connection Status (Quality),") })
        assertTrue("a connected call has its microphone: $offered", "Disable Microphone" in offered || "Enable Microphone" in offered)
    }

    private fun everything(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { everything(group.getChildAt(it)) } } ?: emptyList())

    /**
     * Device Settings: the camera in use is a field the width of its row, a finger's height, and a press on it opens the
     * system's own menu, as `.wplus-call-device-current`, where a line of quiet text read as a caption and people did not
     * find where to choose a device. No list is drawn in the panel, so it does not grow.
     */
    @Test
    fun aDeviceInUseIsAFieldThatOpensTheSystemsMenu() {
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), CallScreenPreviewActivity.Shown.VIDEO)

        ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()

            var tall = 0

            scenario.onActivity { activity -> stage(activity.window.decorView)!!.showDevices(null) }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val panel = everything(activity.window.decorView).filterIsInstance<CallsDevicePanel>().first()
                val field = everything(panel).first { it.contentDescription == "Camera: Front Camera" }
                val row = field.parent as View

                assertEquals("the field is not a finger's height", 40 * density, field.height.toFloat(), 1f)
                assertEquals("the field is not the width of its row", row.width - 16 * density, field.width.toFloat(), 1f)

                tall = panel.height
                assertTrue("the field opens nothing", field.performClick())
            }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val panel = everything(activity.window.decorView).filterIsInstance<CallsDevicePanel>().first()
                val words = everything(panel).filterIsInstance<TextView>().map { it.text.toString() }

                assertFalse("the other camera is drawn in the panel: $words", "Back Camera" in words)
                assertEquals("the panel moved under its menu", tall, panel.height)
                panel.close()
            }
        }
    }

    /**
     * A choice in that menu reaches the call, and the field says it once the menu has gone. The one in use asks nothing
     * of the call. What the call says while the menu is open leaves the panel as it is, as the menu hangs from one of its
     * views, and Back closes the menu alone.
     */
    @Test
    fun aChoiceInTheSystemsMenuReachesTheCall() {
        val engine = CallsEngine.get(ApplicationProvider.getApplicationContext())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val facing = mutableListOf<Boolean>()
        val microphones = mutableListOf<String>()
        var microphone = "phone"
        var cameraFront = true

        engine.driver = object : CallsEngine.Driver {
            override val callId = "preview"
            override fun state() = CallScreenPreviewActivity.state(CallScreenPreviewActivity.Shown.VIDEO).apply {
                getJSONObject("local").put("front", cameraFront)
            }
            override fun answer() {}
            override fun decline() {}
            override fun end() {}
            override fun setMuted(muted: Boolean) {}
            override fun setCamera(on: Boolean) {}
            override fun flipCamera() {}
            override fun setSpeaker(on: Boolean) {}
            override fun setMinimised(minimised: Boolean) {}
            override fun permissionsSettled() {}
            override fun trafficBytes(): Pair<Long, Long>? = null
            override fun microphones() = listOf("phone" to "Phone", "headset" to "Headset")
            override fun microphone() = microphone
            override fun setMicrophone(id: String) {
                microphones.add(id)
                microphone = id
            }
            override fun setCameraFacing(front: Boolean) {
                facing.add(front)
                cameraFront = front
            }
        }

        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), CallScreenPreviewActivity.Shown.VIDEO)

        try {
            ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
                lateinit var panel: CallsDevicePanel

                fun field(name: String): View? = everything(panel).firstOrNull { it.contentDescription == name }

                fun until(what: String, met: (CallScreenPreviewActivity) -> Boolean) {
                    val end = SystemClock.uptimeMillis() + 20_000
                    var done = false

                    while (!done && SystemClock.uptimeMillis() < end) {
                        scenario.onActivity { done = met(it) }
                        if (!done) SystemClock.sleep(50)
                    }

                    assertTrue(what, done)
                    instrumentation.waitForIdleSync()
                }

                fun open(name: String): PopupMenu {
                    var menu: PopupMenu? = null

                    scenario.onActivity {
                        assertTrue("$name opens nothing", field(name)!!.performClick())
                        menu = panel.menu
                    }

                    // The system's own window, which takes the focus from the call's.
                    until("the menu of $name did not come up") { !it.hasWindowFocus() }

                    return menu!!
                }

                fun gone() = until("the menu stayed") { panel.menu == null && it.hasWindowFocus() }

                until("the call did not come up") { it.hasWindowFocus() }

                // Following the call, as the call screen does and the preview has no call to.
                scenario.onActivity {
                    val stage = stage(it.window.decorView)!!

                    stage.attach()
                    stage.showDevices(null)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { panel = everything(it.window.decorView).filterIsInstance<CallsDevicePanel>().first() }

                val camera = open("Camera: Front Camera")

                scenario.onActivity {
                    val items = (0 until camera.menu.size()).map { index -> camera.menu.getItem(index) }

                    assertEquals(listOf("Front Camera", "Back Camera"), items.map { item -> item.title.toString() })
                    assertEquals("the camera in use is not the one marked", listOf(true, false), items.map { item -> item.isChecked })

                    val anchor = field("Camera: Front Camera")!!

                    microphone = "headset"
                    panel.refresh()
                    assertTrue("the panel was drawn again under its menu", anchor.isAttachedToWindow)
                    microphone = "phone"

                    camera.menu.performIdentifierAction(1, 0)
                }
                gone()

                scenario.onActivity {
                    assertEquals("the choice did not reach the call", listOf(false), facing)
                    assertNotNull("the field does not say the choice", field("Camera: Back Camera"))
                }

                val first = open("Microphone: Phone")

                scenario.onActivity { first.menu.performIdentifierAction(1, 0) }
                gone()

                scenario.onActivity {
                    assertEquals("the choice did not reach the call", listOf("headset"), microphones)
                    assertNotNull("the field does not say the choice", field("Microphone: Headset"))
                }

                val again = open("Microphone: Headset")

                scenario.onActivity { again.menu.performIdentifierAction(1, 0) }
                gone()

                scenario.onActivity { assertEquals("the one in use was asked for again", listOf("headset"), microphones) }

                open("Camera: Back Camera")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                gone()

                scenario.onActivity {
                    assertTrue("Back closed Device Settings with the menu", panel.isAttachedToWindow)
                    panel.close()
                    stage(it.window.decorView)!!.detach()
                }
            }
        } finally {
            engine.driver = null
        }
    }

    /**
     * The web's group bar on a phone: its tiles stop above the dock, 8 from the screen's edges and from each other,
     * the connection at the bar's start and the dock at its end. A tap leaves the bar where it is.
     */
    @Test
    fun aGroupCallsTilesStopAboveItsBar() {
        shown(CallScreenPreviewActivity.Shown.GROUP) { activity ->
            val stage = stage(activity.window.decorView)!!
            val dp = activity.resources.displayMetrics.density
            val origin = IntArray(2).also { stage.getLocationOnScreen(it) }
            fun box(view: View): RectF {
                val at = IntArray(2).also { view.getLocationOnScreen(it) }
                val left = (at[0] - origin[0]).toFloat()
                val top = (at[1] - origin[1]).toFloat()
                return RectF(left, top, left + view.width, top + view.height)
            }
            val near = 2f

            val dock = box(everything(stage).first { it is CallsDock })
            val badge = everything(stage).first { it is CallsConnectionPill }
            val started = RectF(badge.x, badge.y, badge.x + badge.width, badge.y + badge.height)
            val tiles = everything(stage)
                .filter { it.javaClass.simpleName == "Tile" && it.visibility == View.VISIBLE && it.width > 0 }
                .map(::box)
                .sortedBy { it.top }

            assertEquals("every person has a tile", 4, tiles.size)
            for (tile in tiles) {
                assertTrue("a tile runs under the dock: $tile over $dock", tile.bottom <= dock.top - 9 * dp + near)
                assertEquals("a tile is 8 from the start: $tile", 8 * dp, tile.left, near)
                assertEquals("a tile is 8 from the end: $tile", stage.width - 8 * dp, tile.right, near)
            }
            for ((above, below) in tiles.zipWithNext()) {
                assertEquals("the tiles are 8 apart", 8 * dp, below.top - above.bottom, near)
            }
            // Cells are whole pixels, so the rows can leave a pixel each short of the floor.
            assertEquals("the last tile ends where the bar starts", dock.top - 9 * dp, tiles.last().bottom, near + tiles.size)

            assertEquals("the bar starts with the connection", View.VISIBLE, badge.visibility)
            assertEquals("the connection is 14 from the start", 14 * dp, started.left, near)
            assertEquals("the dock is 14 from the end", stage.width - 14 * dp, dock.right, near)
            assertTrue("the dock leaves the connection its room", started.right + 10 * dp <= dock.left + near)
            assertEquals("the two are level", dock.centerY(), started.centerY(), near)

            // The web's controls on a phone: 30 across, hanging up 16 wider, 5 apart, 6 in from the dock's edge.
            val row = everything(stage).first { it is CallsDock } as ViewGroup
            val buttons = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.map(::box)
            assertTrue("the dock has controls", buttons.isNotEmpty())
            for (button in buttons) assertEquals("a control is not the web's 30: $button", 30 * dp, button.height(), near)
            assertEquals("leaving is not 16 wider than a control", 46 * dp, buttons.last().width(), near)
            for ((before, after) in buttons.zipWithNext()) {
                assertEquals("the controls are not 5 apart", 5 * dp, after.left - before.right, near)
            }
            assertEquals("the first control is not 6 in from the dock's edge", 6 * dp, buttons.first().left - dock.left, near)
            assertEquals("the dock is not the web's 42 tall", 42 * dp, dock.height(), near)

            stage.performClick()
            assertEquals("a tap put a group call's bar away", 1f, everything(stage).first { it is CallsDock }.alpha)
        }
    }

    @Test
    fun aCameraChangingCodecIsSaidOnItsTileAndNowhereElse() {
        val words = "Optimizing video quality..."
        val changing = shown(CallScreenPreviewActivity.Shown.OPTIMIZING) { said(it.window.decorView) }
        assertEquals("one tile says it: $changing", 1, changing.count { it == words })
        val still = shown(CallScreenPreviewActivity.Shown.GROUP) { said(it.window.decorView) }
        assertFalse("no tile says it with no camera changing", words in still)
    }

    @Test
    fun aCameraChangingCodecIsToldOncePerPersonOnAnyTile() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val said = CallsOptimizingSaid()
        val first = CallsOptimizingView(context).apply { this.said = said }
        val moved = CallsOptimizingView(context).apply { this.said = said }
        val words = (0 until first.childCount).map { first.getChildAt(it) }.filterIsInstance<TextView>().single()

        // The view says it itself, once, and the words are no second voice.
        first.show(true, null, "ann")
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, first.accessibilityLiveRegion)
        assertEquals("Optimizing video quality...", first.contentDescription)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, words.importantForAccessibility)

        // A tile too small for the words still says it.
        val small = (60 * context.resources.displayMetrics.density).toInt()
        first.measure(View.MeasureSpec.makeMeasureSpec(small, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(small, View.MeasureSpec.EXACTLY))
        first.layout(0, 0, small, small)
        assertEquals(View.GONE, words.visibility)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, first.accessibilityLiveRegion)

        // Ann's tile in another place during the same change: quiet.
        moved.show(true, null, "ann")
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, moved.accessibilityLiveRegion)

        // Down, nothing to say; her change over, her next one is told again.
        first.show(false, null)
        assertNull(first.contentDescription)
        said.keep(emptySet())
        first.show(true, null, "ann")
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, first.accessibilityLiveRegion)
    }

    @Test
    fun darkGoesBackToTheAppsOwnLook() {
        val ours = CallsConfigStore.config
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), CallScreenPreviewActivity.Shown.VIDEO)
        ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val dark = activity.window.decorView.findViewWithContentDescription("Dark")
                dark.performClick()
                assertTrue(CallsConfigStore.config.darkMode)
            }
        }
        assertEquals(ours, CallsConfigStore.config)
        assertFalse(CallsConfigStore.config.darkMode)
    }

    @Test
    fun theNamesOfTheWebCountAndTheOldOnesStill() {
        // @wordplus/realtime-ui 1.0.6 named its tokens `--wplus-…`, and the guides call these the web's names.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ours = CallsConfigStore.config
        try {
            CallScreen.configure(CallScreen.Appearance(tokens = mapOf(
                "--wplus-color-accent" to "37, 99, 235",
                "--wplus-call-canvas" to "248, 250, 252",
                "--bm-call-tile" to "226, 232, 240",
            )))
            assertEquals(Color.rgb(37, 99, 235), CallsTheme.accent(context))
            assertEquals(Color.rgb(248, 250, 252), CallsTheme.stage(context))
            assertEquals(Color.rgb(226, 232, 240), CallsTheme.tile(context))

            // Both given, the new name wins.
            CallScreen.configure(CallScreen.Appearance(tokens = mapOf("--wplus-color-accent" to "37, 99, 235", "--bm-color-accent" to "1, 2, 3")))
            assertEquals(Color.rgb(37, 99, 235), CallsTheme.accent(context))
        } finally {
            CallsConfigStore.config = ours
        }
    }

    @Test
    fun theAppsTokensColourTheStageTilesControlsAndDock() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ours = CallsConfigStore.config
        try {
            CallScreen.configure(CallScreen.Appearance(tokens = mapOf(
                "--bm-call-canvas" to "248, 250, 252",
                "--bm-call-tile" to "226, 232, 240",
                "--bm-call-btn" to "#2563eb",
                "--bm-call-dock" to "255, 255, 255",
            )))
            assertEquals(Color.rgb(248, 250, 252), CallsTheme.stage(context))
            assertEquals(Color.rgb(226, 232, 240), CallsTheme.tile(context))
            assertEquals(Color.rgb(37, 99, 235), CallsTheme.control(context))
            assertEquals(Color.rgb(255, 255, 255), CallsTheme.dockGlass(context))

            // On the real stage: its ground is the app's.
            val ground = shown(CallScreenPreviewActivity.Shown.VIDEO) { activity -> (stage(activity.window.decorView)?.background as? ColorDrawable)?.color }
            assertEquals(Color.rgb(248, 250, 252), ground)

            // Without them, the stage stays near-black whatever the palette's other colours.
            CallScreen.configure(CallScreen.Appearance(tokens = mapOf("--bm-color-accent" to "37, 99, 235")))
            assertEquals(Color.rgb(13, 15, 20), CallsTheme.stage(context))
        } finally {
            CallsConfigStore.config = ours
        }
    }

    @Test
    fun theListNamesEveryState() {
        val count = shown(null) { activity ->
            val list = activity.window.decorView.let { root -> generateSequence(listOf(root)) { views -> views.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.ifEmpty { null } }.flatten().firstNotNullOf { it as? ScrollView } }
            (list.getChildAt(0) as ViewGroup).childCount
        }
        assertEquals(CallScreenPreviewActivity.Shown.entries.size, count)
    }
}

private fun View.findViewWithContentDescription(text: String): View {
    val found = ArrayList<View>()
    findViewsWithText(found, text, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
    return found.first()
}
