package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * A live stream's state from Easy Stream's chat socket (LiveStream.kt),
 * with no cloud: the hello, the chat, the counts and the stage as the events say. The Swift package's LiveStreamTests'
 * twin. Going live against a real cloud is the native proof's. `./gradlew connectedDebugAndroidTest` with a device or an
 * emulator.
 */
@RunWith(AndroidJUnit4::class)
class LiveStreamTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun stream(): LiveStream {
        val rt = Realtime(RealtimeOptions(app = "app_test", endpoint = "https://shop.example/api/realtime"))
        return LiveStream(rt.calls(ApplicationProvider.getApplicationContext()), "show:1")
    }

    private val hello = """
        {"stream": {"id": "show:1", "state": "live", "started_at": 1760000000000, "ended_at": null},
         "you": {"identity": "u:34", "role": "viewer", "name": "Ben", "chat": "write", "mod": false, "stage": true},
         "viewers": 120, "peak": 134, "slow": 0,
         "history": [
           {"id": "m1", "from": {"id": "u:42", "name": "Ann", "avatar": "https://shop.example/ann.png", "role": "host"}, "text": "Hi!", "at": 1760000001000},
           {"id": "m2", "from": {"id": "a:0123456789abcdef", "name": "Guest", "avatar": null, "role": "viewer"}, "text": "Hello", "at": 1760000002000}
         ],
         "hands": [], "pinned": {"id": "m1", "from": {"id": "u:42", "name": "Ann", "avatar": null, "role": "host"}, "text": "Hi!", "at": 1760000001000},
         "layout": {"mode": "spotlight", "identity": "u:42"}, "timeout": null, "reactions": true}
    """.trimIndent()

    @Test
    fun aChoiceOfQualityIsKeptAndToldAndNoLayersBeforeThePicture() = onMain {
        val stream = stream()
        var changes = 0
        stream.onChange { changes++ }

        assertEquals(LiveStreamQuality.AUTO, stream.quality)
        assertEquals(emptyList<LiveStreamLayer>(), stream.qualities())
        assertNull(stream.chosenLayer(null))

        stream.setQuality(LiveStreamQuality.LOW)
        assertEquals(LiveStreamQuality.LOW, stream.quality)
        assertEquals(1, changes)
        stream.setQuality(LiveStreamQuality.LOW)
        assertEquals(1, changes)
        assertEquals("low", stream.stats().events.last().detail)
    }

    /**
     * A stream with a seat in its room, which nothing can connect to: `media` says whether it tried. As the browser's
     * `video` option (realtime-client calls.ts:239, streams/client.ts:752-762).
     */
    private fun seated(video: Boolean): LiveStream {
        val rt = Realtime(RealtimeOptions(app = "app_test", endpoint = "https://shop.example/api/realtime"))
        val s = LiveStream(rt.calls(ApplicationProvider.getApplicationContext()), "show:1", video)
        s.seats = JSONObject().put("media", JSONObject().put("url", "wss://nowhere.invalid").put("token", "t"))
        return s
    }

    private val ended = """{"stream": {"id": "show:1", "state": "ended"}, "you": {"identity": "u:34", "role": "viewer"}}"""
    private val live = """{"stream": {"id": "show:1", "state": "live"}}"""

    private fun moment() = Thread.sleep(200)

    @Test
    fun aStreamTakesThePictureByDefault() {
        val s = onMainGet { seated(true) }
        assertTrue(s.video)
        onMain {
            s.apply("hello", JSONObject(ended))
            s.apply("hello", JSONObject(live))
        }
        moment()
        onMain {
            assertFalse(s.media == LiveStreamMedia.OFF)
            s.leave()
        }
    }

    @Test
    fun aStreamJoinedWithoutThePictureDoesNotFetchIt() {
        val s = onMainGet { seated(false) }
        assertFalse(s.video)
        onMain {
            s.apply("hello", JSONObject(ended))
            s.apply("hello", JSONObject(live))
        }
        moment()
        onMain {
            assertEquals(LiveStreamMedia.OFF, s.media)
            assertEquals("the chat and the state go on", LiveStreamState.LIVE, s.state)
            s.leave()
        }
    }

    @Test
    fun thePictureComesOnceAsked() {
        val s = onMainGet { seated(false) }
        onMain {
            s.apply("hello", JSONObject(ended))
            s.apply("hello", JSONObject(live))
            s.watch()
            assertTrue(s.video)
        }
        moment()
        onMain {
            assertFalse(s.media == LiveStreamMedia.OFF)
            s.leave()
        }
    }

    @Test
    fun askedBeforeItIsLiveThePictureComesWithIt() {
        val s = onMainGet { seated(false) }
        onMain {
            s.apply("hello", JSONObject(ended))
            s.watch()
        }
        moment()
        onMain {
            assertEquals("nothing to fetch yet", LiveStreamMedia.OFF, s.media)
            s.apply("hello", JSONObject(live))
        }
        moment()
        onMain {
            assertFalse(s.media == LiveStreamMedia.OFF)
            s.leave()
        }
    }

    private fun <T> onMainGet(block: () -> T): T {
        var value: T? = null
        onMain { value = block() }
        return value!!
    }

    @Test
    fun theHelloSetsTheStreamTheUserAndTheChat() = onMain {
        val s = stream()
        var changes = 0
        s.onChange { changes++ }
        s.apply("hello", JSONObject(hello))

        assertEquals(LiveStreamState.LIVE, s.state)
        assertEquals(1_760_000_000_000L, s.startedAt)
        assertEquals(LiveStreamRole.VIEWER, s.role)
        assertTrue(s.canWrite)
        assertFalse(s.isModerator)
        assertEquals(listOf(120, 134), listOf(s.viewers, s.peak))
        assertEquals(listOf("Hi!", "Hello"), s.messages.map { it.text })
        assertEquals(listOf("42", null), s.messages.map { it.user })
        assertEquals(LiveStreamRole.HOST, s.messages.first().role)
        assertEquals("https://shop.example/ann.png", s.messages.first().avatar)
        assertNull(s.messages[1].avatar)
        assertEquals("m1", s.pinned?.id)
        assertEquals(LiveStreamLayout(LiveStreamLayout.Mode.SPOTLIGHT, "u:42"), s.layout)
        assertTrue(s.reactionsOn)
        assertTrue(changes > 0)
        s.leave()
    }

    @Test
    fun theChatAsItGoes() = onMain {
        val s = stream()
        val heard = ArrayList<String>()
        s.onMessage { heard.add(it.text) }
        s.apply("hello", JSONObject(hello))

        val m3 = JSONObject("""{"id": "m3", "from": {"id": "u:7", "name": "Cara", "avatar": null, "role": "viewer"}, "text": "Nice", "at": 1760000003000}""")
        s.apply("chat", m3)
        s.apply("chat", m3)
        assertEquals("a message heard twice is kept once", listOf("m1", "m2", "m3"), s.messages.map { it.id })
        assertEquals(listOf("Nice"), heard)

        s.apply("chat_deleted", JSONObject("""{"id": "m1"}"""))
        assertEquals(listOf("m2", "m3"), s.messages.map { it.id })
        assertNull("a deleted message is unpinned", s.pinned)

        s.apply("pinned", JSONObject("""{"message": {"id": "m3", "from": {"id": "u:7", "name": "Cara", "avatar": null, "role": "viewer"}, "text": "Nice", "at": 1}}"""))
        assertEquals("m3", s.pinned?.id)
        s.apply("cleared", JSONObject())
        assertTrue(s.messages.isEmpty())
        assertNull(s.pinned)
        s.leave()
    }

    @Test
    fun theCountsTheRulesAndTheStage() = onMain {
        val s = stream()
        s.apply("hello", JSONObject(hello))

        s.apply("viewers", JSONObject("""{"n": 150, "peak": 160}"""))
        assertEquals(listOf(150, 160), listOf(s.viewers, s.peak))
        s.apply("reacting", JSONObject("""{"on": false}"""))
        assertFalse(s.reactionsOn)
        s.apply("slow", JSONObject("""{"seconds": 30}"""))
        assertEquals(30, s.slow)
        s.apply("layout", JSONObject("""{"mode": "grid", "identity": null}"""))
        assertEquals(LiveStreamLayout(LiveStreamLayout.Mode.GRID, null), s.layout)

        s.apply("timeout", JSONObject().put("until", System.currentTimeMillis() + 600_000))
        assertFalse("kept from writing while timed out", s.canWrite)
        s.apply("timeout", JSONObject("""{"until": null}"""))
        assertTrue(s.canWrite)

        s.apply("state", JSONObject("""{"id": "show:1", "state": "ended", "started_at": 1760000000000, "ended_at": 1760000900000}"""))
        assertEquals(LiveStreamState.ENDED, s.state)
        s.leave()
    }

    @Test
    fun reactionsAreHeardAsTheirCounts() = onMain {
        val s = stream()
        val heard = ArrayList<Map<String, Int>>()
        s.onReactions { heard.add(it) }
        s.apply("reactions", JSONObject("""{"❤️": 2, "heart": 1, "🔥": 1, "🎉": 1, "wow": 0}"""))
        s.apply("reactions", JSONObject())
        // By emoji, a name sent before emoji as its emoji, and any emoji the site offers.
        assertEquals(listOf(mapOf("❤️" to 3, "🔥" to 1, "🎉" to 1)), heard)
        s.leave()
    }

    @Test
    fun aBanTakesTheAppOutOfTheStream() = onMain {
        val s = stream()
        val ended = ArrayList<String>()
        s.onEnded { ended.add(it) }
        s.apply("hello", JSONObject(hello))
        s.apply("banned", JSONObject())
        s.apply("kicked", JSONObject())
        assertEquals("banned", s.endReason)
        assertEquals(listOf("banned"), ended)
    }

    @Test
    fun aHostKeepsTheChat() = onMain {
        val s = stream()
        s.apply("hello", JSONObject(hello.replace(""""role": "viewer", "name": "Ben"""", """"role": "host", "name": "Ben"""")))
        assertEquals(LiveStreamRole.HOST, s.role)
        assertTrue(s.isModerator)
        s.leave()
    }

    @Test
    fun theAppsUserInTheCloudsNames() {
        assertEquals("7", LiveStream.userOf("u:7"))
        assertEquals("a place in the room", "7", LiveStream.userOf("u:7.0a1b2c3d"))
        assertEquals("a user id with a dot", "ann.smith", LiveStream.userOf("u:ann.smith"))
        assertNull("a visitor", LiveStream.userOf("a:0123456789abcdef"))
        assertNull(LiveStream.userOf("ingest"))
    }

    @Test
    fun theMainPictureAsTheWebsPlayerPicksIt() = onMain {
        val s = stream()
        fun tile(id: String, role: LiveStreamRole = LiveStreamRole.GUEST, camera: Boolean = true, screen: Boolean = false, speaking: Boolean = false) =
            LiveStreamTile(id, null, id, null, role, local = false, camera = camera, screen = screen, microphone = true, speaking = speaking, encoder = false)
        s.tiles = listOf(tile("u:42.0a1b2c3d", LiveStreamRole.HOST), tile("u:7.11112222", speaking = true))
        assertEquals("the host's", "u:42.0a1b2c3d", s.mainTile?.identity)

        s.apply("layout", JSONObject("""{"mode": "speaker", "identity": null}"""))
        assertEquals("the one speaking", "u:7.11112222", s.mainTile?.identity)

        s.apply("layout", JSONObject("""{"mode": "spotlight", "identity": "u:7"}"""))
        assertEquals("the spotlight's, by the person's name", "u:7.11112222", s.mainTile?.identity)

        s.tiles = s.tiles + tile("u:9.33334444", screen = true)
        s.apply("layout", JSONObject("""{"mode": "grid", "identity": null}"""))
        assertEquals("a shared screen", "u:9.33334444", s.mainTile?.identity)

        s.tiles = listOf(tile("u:42.0a1b2c3d", LiveStreamRole.HOST, camera = false))
        assertNull("nobody sends a picture", s.mainTile)
        s.leave()
    }

    @Test
    fun theStageAsItsEventsSayIt() = onMain {
        val s = stream()
        s.apply("hello", JSONObject(hello.replace(""""hands": []""", """"hands": [{"identity": "u:7", "name": "Cara", "at": 1760000004000}]""")))
        assertEquals("the hello's hands", listOf(LiveStreamHand("7", "Cara", 1_760_000_004_000L)), s.hands)

        // The queue is an array, which the socket's handler hands on as `list`.
        s.apply("hands", JSONObject().put("list", JSONArray("""[{"identity": "u:7", "name": "Cara", "at": 1760000004000}, {"identity": "u:9", "name": "Dan", "at": 1760000005000}]""")))
        assertEquals(listOf("7", "9"), s.hands.map { it.user })

        s.apply("invited", JSONObject())
        assertTrue(s.invited)
        assertFalse(s.handRaised)
        s.apply("unstaged", JSONObject())
        assertFalse("a viewer who never came on stage stays off it", s.onStage)
        s.leave()
    }

    /** As the player of the web offers it (realtime-ui streams/controls.tsx): signed in, not the host, live, taking guests. */
    @Test
    fun whoMayAskToComeOnStage() = onMain {
        fun may(change: (String) -> String = { it }): Boolean {
            val s = stream()
            s.apply("hello", JSONObject(change(hello)))
            val answer = s.canAskStage
            s.leave()
            return answer
        }
        assertTrue(may())
        assertTrue("a cloud that does not say takes guests", may { it.replace(""", "stage": true""", "") })
        assertFalse(may { it.replace(""""stage": true""", """"stage": false""") })
        assertFalse("a visitor", may { it.replace(""""identity": "u:34"""", """"identity": "a:0123456789abcdef"""") })
        assertFalse(may { it.replace(""""role": "viewer", "name": "Ben"""", """"role": "host", "name": "Ben"""") })
        assertFalse(may { it.replace(""""state": "live"""", """"state": "idle"""") })
    }

    @Test
    fun sayingNoToTheInvitationTellsNobodyAndItMayComeAgain() = onMain {
        val s = stream()
        s.apply("hello", JSONObject(hello))
        val asked = mutableListOf<String>()
        s.asking = { event, _ -> asked.add(event); JSONObject().put("ok", true) }
        s.declineStage()
        s.apply("invited", JSONObject())
        assertTrue(s.invited)
        s.declineStage()
        assertFalse(s.invited)
        assertEquals(emptyList<String>(), asked)
        s.apply("invited", JSONObject())
        assertTrue(s.invited)
        s.leave()
    }

    /**
     * What each act that returns nothing resumes its caller with once it has really waited for the chat's answer: Unit, and
     * not the answer, which an app on a newer Kotlin casts to Unit and throws on (SuspendUnitTest has why). Called as the
     * compiled classes are, to see the value itself: Kotlin here would not look at it.
     */
    @Test
    fun anActThatReturnsNothingResumesItsCallerWithNothing() {
        val s = onMainGet { stream() }
        val acts = listOf<Pair<String, List<Any?>>>(
            "send" to listOf("hi"), "invite" to listOf("34"), "unstage" to listOf("34"), "layout" to listOf(LiveStreamLayout.Mode.GRID, null),
            "deleteMessage" to listOf("m1"), "pin" to listOf("m1"), "unpin" to emptyList(), "timeout" to listOf("34", 60), "setSlow" to listOf(5),
            "clearChat" to emptyList(), "remove" to listOf("34"), "kick" to listOf("34"), "ban" to listOf("34"),
        )
        for ((name, args) in acts) {
            val answer = CompletableDeferred<JSONObject?>()
            s.asking = { _, _ -> answer.await() }
            var resumedWith: Result<Any?>? = null
            val act = LiveStream::class.java.methods.first { it.name == name && it.parameterCount == args.size + 1 }
            val made = act.invoke(s, *args.toTypedArray(), Continuation<Any?>(EmptyCoroutineContext) { resumedWith = it })
            assertSame(name, COROUTINE_SUSPENDED, made)
            answer.complete(JSONObject().put("ok", true))
            assertSame(name, Unit, resumedWith?.getOrThrow())
        }
        onMain { s.leave() }
    }

    /** The cloud takes the guest off first (realtime-client streams/client.ts:3182). When the word is its own, it hears nothing back. */
    @Test
    fun leavingTheStageTellsTheCloudAndItsOwnWordDoesNot() {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        lateinit var s: LiveStream
        onMain {
            s = stream()
            s.apply("hello", JSONObject(hello))
            s.asking = { event, payload -> asked.add(event + " " + payload.optString("identity")); JSONObject().put("ok", true) }
            s.leaveStage()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("a viewer never on stage has nothing to leave", emptyList<String>(), asked.toList())

        onMain {
            s.onStage = true
            s.leaveStage()
            assertFalse(s.onStage)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(listOf("unstage u:34"), asked.toList())

        onMain {
            s.onStage = true
            s.apply("unstaged", JSONObject())
            assertFalse(s.onStage)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(listOf("unstage u:34"), asked.toList())
        onMain { s.leave() }
    }

    @Test
    fun aViewerOffStageSendsNothing() = onMain {
        val s = stream()
        s.apply("hello", JSONObject(hello))
        val refused = runBlocking { runCatching { s.preview() }.exceptionOrNull() }
        assertEquals("not_host", (refused as? CallException)?.code)
        assertFalse(s.camera)
        s.leave()
    }
}
