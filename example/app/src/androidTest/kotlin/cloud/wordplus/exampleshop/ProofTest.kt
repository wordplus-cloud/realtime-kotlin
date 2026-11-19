package cloud.wordplus.exampleshop

import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
import cloud.wordplus.realtime.calls.SystemCalls
import cloud.wordplus.realtime.ui.CallScreen
import java.io.ByteArrayOutputStream
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.HttpURLConnection
import java.net.URL

/**
 * The proof, end to end: this app signed in as Ben, and a browser signed in as
 * Ann on the same shop, the two nodes of the realtime server behind their load balancer. In realtime-js,
 * `node test/native/proof.mjs android` runs it: it starts the shop and the nodes (test/native/serve.mjs), opens, for Ann, her
 * page in Chromium, reverses the ports into the emulator, and does what this test asks of her at `proof`. Without
 * that, it is skipped.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProofTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * The microphone and camera allowed, on Android 12 and later the Bluetooth headsets a call may move to (Telecom's,
     * which the call screen asks for), and on 13 and later the notifications a call rings with, as the app asks for them,
     * so no prompt stands in the way of a call.
     */
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) +
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()) +
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())).toTypedArray(),
    )

    private val arguments = InstrumentationRegistry.getArguments()
    private val shop = arguments.getString("shop")
    private val proof = arguments.getString("proof")

    /**
     * A call that rings the app closed (SystemCalls): its push starts the ring, the notification with Answer and
     * Telecom told, and the app connects as Ben, holds his line and finds the call ringing there. Answer on the
     * notification answers it, the app opens with the call on the Better Messages call screen, connected, and Ann's
     * hang-up ends it. The push is the FCM data a FirebaseMessagingService hands `SystemCalls.push`, handed in here, into
     * the same entry point, as the emulator has no Firebase project of ours. A real push is checked on a phone.
     */
    @Test
    fun aaACallRingsTheAppClosed() {
        assumeTrue("Run by node test/native/proof.mjs android in realtime-js", shop != null && proof != null)
        val app = launch(reset = true)
        compose.onNodeWithTag("sign-in-42").performClick()
        waitFor(hasTestTag("order-live") and hasText("Live", substring = true))
        // Closed: the app has no connection now.
        app.close()

        // Ann calls Ben, his app closed: what the realtime server pushes for that ring.
        val id = askFor("ring", "to" to "Ben").getString("id")
        val now = System.currentTimeMillis()
        val wp = JSONObject().put("v", 1).put("op", "incoming").put("id", id).put("call", "support:34:42").put("from", 34)
            .put("type", "video").put("at", now).put("until", now + 40_000)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(SystemCalls.push(context, mapOf("wp" to wp.toString())))

        // The ring: its screen over the phone as it rings with the screen off, or the notification in the shade, with Answer
        // (by what it says to TalkBack: the notification's text wraps the word in direction marks and an icon).
        val answerLabel = By.desc(Pattern.compile("(?i)answer"))
        var answer = device.wait(Until.findObject(answerLabel), 8_000)
        if (answer == null) {
            device.openNotification()
            answer = device.wait(Until.findObject(answerLabel), 30_000)
        }
        if (answer == null) {
            val screen = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
            screen.chunked(3_000).forEach { Log.e("ProofTest", it) }
            throw AssertionError("No Answer on the ring")
        }
        answer.click()

        // The app opens with the call, connected, on the call screen.
        ask("wait", "connected" to "true")
        seen("End Call")
        Thread.sleep(2_000)
        ask("hangup")
        gone("End Call")
    }

    /**
     * Calls between this app and the browser of Ann, both ways, on the Better Messages
     * call screen (cloud.wordplus:realtime-ui): each rings, is answered, connects, and ends at the hang-up of either side.
     * Named to run before the shop test, whose node drains.
     */
    @Test
    fun aCallsBetweenThePhoneAndABrowser() {
        assumeTrue("Run by node test/native/proof.mjs android in realtime-js", shop != null && proof != null)
        val app = launch(reset = true)
        compose.onNodeWithTag("sign-in-42").performClick()
        waitFor(hasTestTag("order-live") and hasText("Live", substring = true))
        waitFor(hasTestTag("member-34") and state("online"))

        // Ben calls Ann: the screen rings out, her page rings, she answers, and her page counts the seconds. The screen is
        // an activity over that of the shop, so the state of the shop itself is read once the screen is put away.
        compose.onNodeWithTag("call-34").performScrollTo().performClick()
        seen("End Call")
        ask("answer")
        ask("wait", "connected" to "true")
        // Both sides tell the server how the call goes: what each sends and plays, on its pulse.
        ask("wait", "readings" to "true")

        // The screen put away, the call goes on in the shop bar, and comes back from it.
        press("Back to the shop")
        gone("End Call")
        waitFor(hasTestTag("call-state") and state("connected"))
        compose.onNodeWithTag("back-to-call").performClick()
        checksTheDevices()
        sharesTheScreen()
        Thread.sleep(2_000)
        press("End Call")
        ask("wait", "callOver" to "true")
        gone("End Call")
        waitFor(hasTestTag("call-ended") and state("hangup"))

        // Ann calls Ben: the screen rings with Answer, he answers, and her hang-up takes the screen away.
        ask("call", "to" to "Ben")
        press("Answer")
        ask("wait", "connected" to "true")
        Thread.sleep(3_000)
        ask("hangup")
        gone("End Call")
        waitFor(hasTestTag("call-ended") and state("hangup"))
        app.close()
    }

    /** The browsers out of any call or room an earlier test left them in. */
    @Before
    fun clearBrowsers() {
        if (shop != null && proof != null) ask("clear")
    }

    /**
     * Group calls, on the mesh: Ben calls Ann and adds Cara, whose browser rings
     * and answers, and all three go on in the call room, drawn as the Better Messages grid. Then the daily meeting, joined
     * from the app and both browsers. Run only with a mesh (MESH_URL for proof.mjs).
     */
    @Test
    fun bGroupCallsWithTwoBrowsers() {
        assumeTrue("Run by node test/native/proof.mjs android in realtime-js", shop != null && proof != null)
        assumeTrue("No mesh: MESH_URL for proof.mjs", arguments.getString("mesh") == "1")
        val app = launch(reset = true)
        compose.onNodeWithTag("sign-in-42").performClick()
        waitFor(hasTestTag("order-live") and hasText("Live", substring = true))
        waitFor(hasTestTag("member-34") and state("online"))

        // Ben calls Ann, then adds Cara from the screen's Add Person, the one person the shop server lists: her row says the
        // ring, the call becomes a group call under it, and all three meet in its room.
        compose.onNodeWithTag("call-34").performScrollTo().performClick()
        ask("answer")
        ask("wait", "connected" to "true")
        press("Add Person")
        press("Cara")
        seen("Cara, Ringing…")
        ask("answer", "as" to "cara")
        seen("Cara, Answered")
        // Off the card closes it. The screen is the group call's, its grid the three of them, its hang-up a room Exit
        // Chat, as on the web.
        device.click(device.displayWidth / 2, device.displayHeight / 10)
        gone("Cara, Answered")
        allThreeInTheRoom()
        press("Exit Chat")
        gone("Exit Chat")
        ask("leave")
        ask("leave", "as" to "cara")

        // The daily meeting: both browsers join, then the app, which shares its screen into it through the room.
        ask("meeting")
        ask("meeting", "as" to "cara")
        compose.onNodeWithTag("join-meeting").performScrollTo().performClick()
        allThreeInTheRoom()
        goesToTheCorner()
        checksTheDevices()
        sharesTheScreen()
        press("Exit Chat")
        gone("Exit Chat")
        ask("leave")
        ask("leave", "as" to "cara")
        app.close()
    }

    /**
     * The shop live show, on the mesh: Ann goes live in her browser, and
     * the app watches its picture, is counted among those watching, and talks with her in its chat. Her end reaches the
     * app. Named to run before the shop test, whose node drains.
     */
    @Test
    fun bLiveShowFromABrowser() {
        assumeTrue("Run by node test/native/proof.mjs android in realtime-js", shop != null && proof != null)
        assumeTrue("No mesh: MESH_URL for proof.mjs", arguments.getString("mesh") == "1")
        val app = launch(reset = true)
        compose.onNodeWithTag("sign-in-42").performClick()
        waitFor(hasTestTag("order-live") and hasText("Live", substring = true))

        ask("golive")
        compose.onNodeWithTag("watch-show").performScrollTo().performClick()
        // The screen drawn ready (LiveStreamScreen), over the shop: Live over the picture, and the picture itself.
        assertTrue("Live over the picture", onScreen(By.text("LIVE"), 30_000))
        assertTrue("the picture", onScreen(By.desc("The picture of the stream"), 30_000))
        // Counted among those watching: the page of Cara, and this app.
        ask("wait", "watching" to "2")
        // How it plays here, from the room client's counters: the picture came, at a size.
        val played = (ApplicationProvider.getApplicationContext<ShopApplication>()).shop.show
        val until = System.currentTimeMillis() + 30_000
        while ((played?.stats()?.video?.height ?: 0) == 0 && System.currentTimeMillis() < until) Thread.sleep(500)
        assertTrue("the stream says how it plays: ${played?.stats()}", (played?.stats()?.video?.height ?: 0) > 0)
        assertTrue("and how long it took to start", played?.stats()?.session?.startupMs != null)

        val stamp = (System.currentTimeMillis() / 1000 % 100_000).toString()
        val fromAnn = "From the browser $stamp"
        val fromBen = "From Android $stamp"
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000).text = fromBen
        device.findObject(By.desc("Send")).click()
        ask("wait", "showChat" to fromBen)
        ask("showSay", "text" to fromAnn)
        assertTrue("Ann's message", device.wait(Until.hasObject(By.textContains(fromAnn)), 10_000))

        ask("endShow")
        assertTrue("the end", device.wait(Until.hasObject(By.text("This stream has ended")), 20_000))
        device.findObject(By.desc("Close")).click()
        waitFor(hasTestTag("watch-show"))
        app.close()
    }

    @Test
    fun cTheShopWithABrowserAcrossAMove() {
        assumeTrue("Run by node test/native/proof.mjs android in realtime-js", shop != null && proof != null)
        var app = launch(reset = true)
        compose.onNodeWithTag("sign-in-42").performClick()

        // The order of Ben is live, and moves on when Ann marks it in her browser, his notice with it.
        waitFor(hasTestTag("order-live") and hasText("Live", substring = true))
        ask("status", "status" to "packed")
        waitFor(step("packed"))
        waitFor(hasTestTag("notice") and hasText("Your order #1042 is packed."))

        // Ann is in the shop and online. The page of Ann has Ben in the shop, online too.
        waitFor(hasTestTag("member-34") and state("online"))
        ask("wait", "member" to "Ben")
        ask("wait", "online" to "42")

        // The chat: Ann typing shows here, her message comes. The typing of Ben and message reach her page.
        ask("type")
        waitFor(hasTestTag("typing") and hasText("Ann is typing…"))
        val stamp = (System.currentTimeMillis() / 1000 % 100_000).toString()
        val fromAnn = "From the browser $stamp"
        val fromBen = "From Android $stamp"
        ask("say", "text" to fromAnn)
        waitFor(hasText(fromAnn))
        compose.onNodeWithTag("message-field").performScrollTo().performTextInput(fromBen)
        ask("wait", "typing" to "Ben is typing")
        compose.onNodeWithTag("send").performScrollTo().performClick()
        ask("wait", "chat" to fromBen)

        // Opened again, the app has the chat from the room history.
        app.close()
        app = launch(reset = false)
        waitFor(hasText(fromBen))
        waitFor(hasText(fromAnn))
        waitFor(hasTestTag("order-live") and hasText("Live", substring = true))

        // The node the app is on drains while Ann moves the order on: the app moves to the other node through the load
        // balancer, and goes on there.
        ask("drain", "status" to "shipped")
        waitFor(step("shipped"))
        ask("status", "status" to "delivered")
        waitFor(step("delivered"))
        val after = "After the move $stamp"
        ask("say", "text" to after)
        waitFor(hasText(after))
        app.close()
    }

    private fun launch(reset: Boolean): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("shop", shop).putExtra("reset", reset),
    )

    private fun step(name: String) = hasTestTag("step-$name") and state("now")

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    // Room for a busy machine, as the waits of the proof itself (test/native/proof.mjs in realtime-js).
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    /**
     * Waits until a view outside Compose is on the screen (the live stream drawn ready, over the shop), letting Compose
     * draw meanwhile: under the test rule its frames run only while the test waits on it, so a screen the shop opens from
     * its composition never opens during a wait of UiAutomator alone.
     */
    private fun onScreen(selector: BySelector, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (device.hasObject(selector)) return true
            Thread.sleep(250)
        }
        return false
    }

    /** Waits until a control of the call screen is on the screen, by what it says to TalkBack. */
    private fun seen(description: String, timeoutMs: Long = 30_000): UiObject2 =
        device.wait(Until.findObject(By.desc(description).clickable(true)), timeoutMs) ?: run {
            // What was on the screen instead, in logcat (ProofTest), for the run to say why.
            ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString().chunked(3_000).forEach { Log.e("ProofTest", it) }
            throw AssertionError("No \"$description\" on the call screen")
        }

    /** Presses a control of the call screen, once it is there. */
    private fun press(description: String, timeoutMs: Long = 30_000) {
        seen(description, timeoutMs).click()
    }

    /**
     * Device Settings on the call going on: the microphone tried on this side alone, and the camera too where the call has
     * one that is off. Muted for it, and back on after.
     */
    private fun checksTheDevices() {
        device.wait(Until.hasObject(By.desc(Pattern.compile("(Enable|Disable) Microphone")).clickable(true)), 30_000)
        val wasOn = device.hasObject(By.desc("Disable Microphone"))
        if (wasOn) press("Disable Microphone")
        seen("Enable Microphone")
        press("Device Settings")
        press("Test")
        assertTrue("the microphone is heard", device.wait(Until.hasObject(By.text("Speak to test. Nobody hears it.")), 10_000))
        press("Stop")
        if (device.wait(Until.hasObject(By.desc("Preview")), 3_000)) {
            press("Preview")
            seen("Stop")
            assertTrue(
                "the camera is shown",
                device.wait(Until.hasObject(By.clazz("android.view.TextureView")), 10_000) ||
                    device.wait(Until.hasObject(By.clazz("livekit.org.webrtc.TextureViewRenderer")), 1_000),
            )
            press("Stop")
        }
        device.pressBack()
        if (wasOn) press("Enable Microphone")
        seen(if (wasOn) "Disable Microphone" else "Enable Microphone")
    }

    /**
     * The dock's Picture In Picture on a group call: the system's corner window, which draws one person of it and no dock,
     * and the whole screen back. Only where the system has the window.
     */
    private fun goesToTheCorner() {
        if (!device.wait(Until.hasObject(By.desc("Picture In Picture")), 5_000)) return
        press("Picture In Picture")
        val deadline = System.currentTimeMillis() + 15_000
        while (!device.executeShellCommand("dumpsys activity activities").contains("mode=pinned")) {
            assertTrue("the system's window opens", System.currentTimeMillis() < deadline)
            Thread.sleep(250)
        }
        gone("Exit Chat")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CallScreen.restore() }
        seen("Exit Chat")
    }

    /** The dock's Share Screen: the system's dialog and its yes, the screen going out, and the dock stopping it. */
    private fun sharesTheScreen() {
        press("Share Screen")
        val systemUi = "com.android.systemui"
        device.wait(Until.hasObject(By.pkg(systemUi).clickable(true)), 15_000)
        device.findObject(By.res(systemUi, "screen_share_mode_options"))?.let { chooser ->
            chooser.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i).*(entire|whole|full) screen.*"))), 5_000)?.click()
            Thread.sleep(500)
        }
        device.wait(Until.findObject(By.pkg(systemUi).clickable(true).text(Pattern.compile("(?i)start now|start|share screen"))), 10_000).click()
        press("Back to the shop")
        waitFor(hasTestTag("sharing"), 30_000)
        compose.onNodeWithTag("back-to-call").performClick()
        press("Share Screen")
        press("Back to the shop")
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("sharing"), useUnmergedTree = false).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("back-to-call").performClick()
    }

    /**
     * The group call's screen is up, and this app and the two browsers are in its room: the shop's bar, under the screen,
     * counts the two others. The shop draws nothing while the screen covers it, so the screen is put away to read it.
     */
    private fun allThreeInTheRoom() {
        seen("Exit Chat", 40_000)
        press("Back to the shop")
        gone("Exit Chat")
        waitFor(hasTestTag("group-people") and state("2"), 40_000)
        compose.onNodeWithTag("back-to-call").performClick()
    }

    /** Waits until a control of the call screen is gone: the screen put away, or its call over. */
    private fun gone(description: String, timeoutMs: Long = 10_000) {
        assertTrue("\"$description\" is still on the screen", device.wait(Until.gone(By.desc(description)), timeoutMs))
    }

    /** Asks the browser of Ann to do something, or waits for it to see something. Fails unless it answers 200. */
    private fun ask(path: String, vararg body: Pair<String, String>) {
        askFor(path, *body)
    }

    /** The same, with what the browser answered. */
    private fun askFor(path: String, vararg body: Pair<String, String>): JSONObject {
        val connection = URL("$proof/$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(JSONObject(body.toMap()).toString().toByteArray()) }
        val status = connection.responseCode
        val answer = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }
        assertEquals("$path ${body.toMap()}: $answer", 200, status)
        return runCatching { JSONObject(answer ?: "{}") }.getOrDefault(JSONObject())
    }
}
