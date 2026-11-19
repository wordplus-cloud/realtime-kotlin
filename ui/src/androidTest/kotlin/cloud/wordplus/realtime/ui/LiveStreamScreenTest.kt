package cloud.wordplus.realtime.ui

import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.calls.LiveStream
import cloud.wordplus.realtime.calls.RealtimeCalls
import cloud.wordplus.realtime.calls.calls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live stream drawn ready (LiveStreamScreen), with no cloud: a stream whose events the test gives, as its chat socket
 * would, drawn as it goes. The stream is made and fed as the calls module does itself, which reaches past its
 * module's walls here (reflection): an app has no need to. The Swift package's LiveStreamScreenTests' twin.
 */
@RunWith(AndroidJUnit4::class)
class LiveStreamScreenTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun stream(): LiveStream {
        val rt = Realtime(RealtimeOptions(app = "app_test", endpoint = "https://shop.example/api/realtime"))
        val calls = rt.calls(ApplicationProvider.getApplicationContext())
        return LiveStream::class.java.getDeclaredConstructor(RealtimeCalls::class.java, String::class.java)
            .apply { isAccessible = true }
            .newInstance(calls, "show:1")
    }

    private fun LiveStream.give(event: String, data: String) {
        val apply = LiveStream::class.java.declaredMethods.first {
            (it.name == "apply" || it.name.startsWith("apply$")) && it.parameterTypes.contentEquals(arrayOf(String::class.java, JSONObject::class.java))
        }
        apply.isAccessible = true
        apply.invoke(this, event, JSONObject(data))
    }

    private fun hello(state: String, chat: String = "write") = """
        {"stream": {"id": "show:1", "state": "$state", "started_at": 1760000000000, "ended_at": null},
         "you": {"identity": "u:34", "role": "viewer", "name": "Ben", "chat": "$chat", "mod": false},
         "viewers": 120, "peak": 134, "slow": 0,
         "history": [
           {"id": "m1", "from": {"id": "u:42", "name": "Ann", "avatar": null, "role": "host"}, "text": "Hi!", "at": 1760000001000},
           {"id": "m2", "from": {"id": "u:7", "name": "Cara", "avatar": null, "role": "viewer"}, "text": "Hello", "at": 1760000002000}
         ],
         "hands": [], "pinned": null, "layout": {"mode": "grid", "identity": null}, "timeout": null, "reactions": true}
    """.trimIndent()

    private fun screen(s: LiveStream, strings: Map<String, String> = emptyMap()) =
        LiveStreamScreen(ApplicationProvider.getApplicationContext(), s, "The new mugs", LiveStreamScreen.Appearance(strings))

    @Test
    fun beforeTheStartItSaysSoAndShowsNoLiveBadge() = onMain {
        val s = stream()
        s.give("hello", hello("idle"))
        val screen = screen(s)

        assertEquals("The stream starts soon", screen.note.text.toString())
        assertEquals(View.VISIBLE, screen.note.visibility)
        assertEquals(View.GONE, screen.badge.visibility)
        assertEquals(View.GONE, screen.viewers.visibility)
        assertEquals("The new mugs", screen.titleView.text.toString())
    }

    @Test
    fun liveItShowsTheBadgeTheCountAndTheChatAsItGoes() = onMain {
        val s = stream()
        s.give("hello", hello("live"))
        val screen = screen(s)

        assertEquals(View.GONE, screen.note.visibility)
        assertEquals("LIVE", screen.badge.text.toString())
        assertEquals("120 watching", screen.viewers.text.toString())
        assertEquals(2, screen.chat.adapter.count)
        assertTrue(screen.field.isEnabled)
        assertEquals(View.VISIBLE, screen.heart.visibility)

        s.give("viewers", """{"n": 121, "peak": 134}""")
        s.give("chat", """{"id": "m3", "from": {"id": "u:9", "name": "Dan", "avatar": null, "role": "viewer"}, "text": "Nice mugs", "at": 1760000003000}""")
        assertEquals("121 watching", screen.viewers.text.toString())
        assertEquals(3, screen.chat.adapter.count)

        s.give("pinned", """{"message": {"id": "m1", "from": {"id": "u:42", "name": "Ann", "avatar": null, "role": "host"}, "text": "Hi!", "at": 1760000001000}}""")
        assertEquals(View.VISIBLE, screen.pinned.visibility)
        assertEquals("Pinned: Ann  Hi!", screen.pinned.text.toString())

        s.give("state", """{"id": "show:1", "state": "ended", "started_at": 1760000000000, "ended_at": 1760000900000}""")
        assertEquals("This stream has ended", screen.note.text.toString())
        assertEquals(View.GONE, screen.badge.visibility)
    }

    @Test
    fun aReadOnlyViewerCannotWrite() = onMain {
        val s = stream()
        s.give("hello", hello("live", chat = "read"))
        val screen = screen(s)

        assertFalse(screen.field.isEnabled)
        assertFalse(screen.send.isEnabled)
        assertEquals("Chat is read-only for you", screen.field.hint.toString())
    }

    @Test
    fun itsWordsInTheUserLanguage() = onMain {
        val s = stream()
        s.give("hello", hello("live"))
        val screen = screen(s, mapOf("live" to "Live", "watching" to "%d schauen zu", "say_something" to "Sag etwas…"))

        assertEquals("120 schauen zu", screen.viewers.text.toString())
        assertEquals("Sag etwas…", screen.field.hint.toString())
    }

    @Test
    fun closingLeavesTheStreamUnlessTheAppKeepsIt() = onMain {
        val s = stream()
        s.give("hello", hello("live"))
        val screen = screen(s)
        var closed = 0
        screen.onClose = { closed++ }

        screen.leavesOnClose = false
        screen.close.performClick()
        assertEquals(1, closed)
        assertNull(s.endReason)

        screen.leavesOnClose = true
        screen.close.performClick()
        assertEquals(2, closed)
        assertEquals("left", s.endReason)
    }

    /** Through the stream's own seams, past its module's walls as the rest here is: on stage, and what the chat answers. */
    private fun LiveStream.putOnStage(on: Boolean) {
        LiveStream::class.java.declaredMethods.first { it.name.startsWith("setOnStage") }.apply { isAccessible = true }.invoke(this, on)
    }

    private fun LiveStream.answering(answer: (String, JSONObject) -> JSONObject?) {
        // The stream takes a suspending answer, which a test may hold back.
        val asking: suspend (String, JSONObject) -> JSONObject? = { event, payload -> answer(event, payload) }
        LiveStream::class.java.declaredMethods.first { it.name.startsWith("setAsking") }.apply { isAccessible = true }.invoke(this, asking)
    }

    private fun settle(until: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        var done = false
        while (!done && System.currentTimeMillis() < end) {
            onMain { done = until() }
            if (!done) Thread.sleep(20)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun aSignedInViewerAsksToComeOnStageAndLowersTheirHand() {
        lateinit var s: LiveStream
        lateinit var screen: LiveStreamScreen
        val asked = mutableListOf<Boolean>()
        onMain {
            s = stream()
            s.give("hello", hello("live"))
            screen = screen(s)
            s.answering { event, payload ->
                assertEquals("hand", event)
                asked.add(payload.optBoolean("up"))
                JSONObject().put("ok", true)
            }
            assertEquals(View.VISIBLE, screen.stageRow.visibility)
            assertEquals(View.VISIBLE, screen.hand.visibility)
            assertEquals("Ask to join on stage", screen.hand.label.text.toString())
            assertEquals(View.GONE, screen.cameraButton.visibility)
            assertEquals(View.GONE, screen.leaveStage.visibility)
            screen.hand.performClick()
        }
        settle { s.handRaised }
        onMain {
            assertEquals("Lower hand", screen.hand.label.text.toString())
            screen.hand.performClick()
        }
        settle { !s.handRaised }
        onMain {
            assertEquals("Ask to join on stage", screen.hand.label.text.toString())
            assertEquals(listOf(true, false), asked)
        }
    }

    @Test
    fun noAskingForOneTheStreamWouldNotTake() = onMain {
        fun hidden(change: (String) -> String): Boolean {
            val s = stream()
            s.give("hello", change(hello("live")))
            val shown = screen(s)
            return shown.hand.visibility == View.GONE && shown.stageRow.visibility == View.GONE
        }
        assertFalse(hidden { it })
        assertTrue(hidden { it.replace(""""mod": false""", """"mod": false, "stage": false""") })
        assertTrue("a visitor", hidden { it.replace(""""identity": "u:34"""", """"identity": "a:0123456789abcdef"""") })
        assertTrue(hidden { it.replace(""""role": "viewer", "name": "Ben"""", """"role": "host", "name": "Ben"""") })
        assertTrue(hidden { it.replace(""""state": "live"""", """"state": "idle"""") })
    }

    @Test
    fun theInvitationAsksAndNotNowPutsItAway() = onMain {
        val s = stream()
        s.give("hello", hello("live"))
        val screen = screen(s)
        assertEquals(View.GONE, screen.invitation.visibility)

        s.give("invited", "{}")
        assertEquals(View.VISIBLE, screen.invitation.visibility)
        assertEquals("The host invites you on stage", screen.invitedTitle.text.toString())
        assertEquals("Your camera and microphone will be live for everyone watching.", screen.invitedText.text.toString())
        assertEquals("invited, there is nothing left to ask", View.GONE, screen.hand.visibility)

        screen.notNow.performClick()
        assertFalse(s.invited)
        assertEquals(View.GONE, screen.invitation.visibility)
        assertEquals(View.VISIBLE, screen.hand.visibility)
    }

    @Test
    fun aJoinThatFailsSaysWhyAndStaysAViewer() {
        lateinit var s: LiveStream
        lateinit var screen: LiveStreamScreen
        onMain {
            s = stream()
            s.give("hello", hello("live"))
            screen = screen(s)
            screen.missingDevices = { emptyList() }
            s.answering { _, _ -> JSONObject().put("ok", false).put("error", "not_invited") }
            s.give("invited", "{}")
            screen.joinStage.performClick()
        }
        settle { screen.failure.visibility == View.VISIBLE }
        onMain {
            assertEquals("You could not join the stage (not_invited)", screen.failure.text.toString())
            assertFalse(s.onStage)
            assertFalse("refused, the invitation is gone, as on the web", s.invited)
            assertEquals(View.GONE, screen.invitation.visibility)
            assertEquals(View.VISIBLE, screen.hand.visibility)
        }
    }

    /** With no activity to ask from, as with one whose system said no: back to watching, as the web is, and it says so. */
    @Test
    fun aJoinWithoutTheDevicesSaysSoAndAsksNobody() = onMain {
        val s = stream()
        s.give("hello", hello("live"))
        val screen = screen(s)
        screen.missingDevices = { listOf("android.permission.CAMERA") }
        s.answering { _, _ -> throw AssertionError("the cloud is not asked") }
        s.give("invited", "{}")

        screen.joinStage.performClick()
        assertEquals("You could not join the stage (devices)", screen.failure.text.toString())
        assertFalse(s.invited)
        assertEquals(View.GONE, screen.invitation.visibility)
    }

    @Test
    fun theWindowComingBackGoesOnWithTheDevicesAllowed() {
        lateinit var s: LiveStream
        lateinit var screen: LiveStreamScreen
        val asked = mutableListOf<String>()
        onMain {
            s = stream()
            s.give("hello", hello("live"))
            screen = screen(s)
            screen.missingDevices = { emptyList() }
            s.answering { event, _ -> asked.add(event); JSONObject().put("ok", false).put("error", "not_invited") }
            screen.askedForDevices = true
            screen.onWindowFocusChanged(true)
            assertFalse("it asks once", screen.askedForDevices)
        }
        settle { asked.isNotEmpty() }
        onMain { assertEquals(listOf("stage"), asked) }
    }

    @Test
    fun onStageItHasTheirDevicesAndAWayOff() {
        lateinit var s: LiveStream
        lateinit var screen: LiveStreamScreen
        val asked = mutableListOf<String>()
        onMain {
            s = stream()
            s.give("hello", hello("live"))
            screen = screen(s)
            s.answering { event, _ -> asked.add(event); JSONObject().put("ok", true) }
            s.putOnStage(true)
            screen.render()
            assertEquals(View.GONE, screen.hand.visibility)
            assertEquals(View.VISIBLE, screen.cameraButton.visibility)
            assertEquals(View.VISIBLE, screen.microphoneButton.visibility)
            assertEquals(View.VISIBLE, screen.leaveStage.visibility)
            assertEquals("Leave stage", screen.leaveStage.label.text.toString())
            assertEquals("Camera", screen.cameraButton.contentDescription.toString())
            assertEquals("Microphone", screen.microphoneButton.contentDescription.toString())
            assertEquals("no camera on, nothing of their own to show", View.GONE, screen.own.visibility)
            screen.leaveStage.performClick()
        }
        settle { asked.isNotEmpty() }
        onMain {
            screen.render()
            assertFalse(s.onStage)
            assertEquals(listOf("unstage"), asked)
            assertEquals(View.VISIBLE, screen.hand.visibility)
            assertEquals(View.GONE, screen.leaveStage.visibility)
        }
    }

    @Test
    fun anEndedStreamOffersNothingAboutTheStage() = onMain {
        val s = stream()
        s.give("hello", hello("live"))
        s.putOnStage(true)
        val screen = screen(s)
        assertEquals(View.VISIBLE, screen.stageRow.visibility)
        s.give("hello", hello("ended"))
        screen.render()
        assertEquals(View.GONE, screen.stageRow.visibility)
        assertEquals(View.GONE, screen.invitation.visibility)
    }

    @Test
    fun itsStageWordsInTheUserLanguage() = onMain {
        val s = stream()
        s.give("hello", hello("live"))
        val screen = screen(s, mapOf("ask_stage" to "Auf die Bühne", "join_stage" to "Beitreten"))
        assertEquals("Auf die Bühne", screen.hand.label.text.toString())
        assertEquals("Beitreten", screen.joinStage.label.text.toString())
    }
}
