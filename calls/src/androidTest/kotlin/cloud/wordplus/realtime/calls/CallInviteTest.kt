package cloud.wordplus.realtime.calls

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

/**
 * A ring into a group call, as the browser's (realtime-client invite.ts, calls.ts:379-384 and 531-548): it updates its
 * state in the relay's words, the app that rang can call it off, and everyone in the call hears of rings others started.
 * The Swift package's CallInviteTests' twin. With no server.
 */
@RunWith(AndroidJUnit4::class)
class CallInviteTest {
    private val link = GroupOf("g-1", "support:34:42", CallType.AUDIO, null, null)

    private fun standIn() = StandIn(route = { 200 to json("""{"token":"ring-token","ring":"ring-proof"}""") }).also { s ->
        s.answer = { event, _ ->
            when (event) {
                "group" -> json("""{"ok":true,"server":"wss://nowhere.invalid","token":"room-token"}""")
                "invite" -> json("""{"ok":true,"state":"ringing"}""")
                "uninvite" -> json("""{"ok":true,"state":"cancelled"}""")
                else -> json("""{"ok":true}""")
            }
        }
    }

    private fun told(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(
        (json("""{"op":"invite","id":"ring-9","group":"g-1","from":34,"to":43,"state":"ringing"}""") as JsonObject) + fields.toMap()
    )

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    @Test
    fun aRingAsksTheRouteThenTheServer() = runBlocking {
        val s = standIn()
        val invite = s.calls.invite(link, "43")
        assertEquals("43", invite.to)
        assertEquals("g-1", invite.group)
        assertNull(invite.from)
        assertEquals("ringing", invite.state)
        assertEquals("43", s.bodies.first().text("to"))
        assertEquals(false, s.bodies.first()["to"]!!.jsonPrimitive.isString)
        assertEquals("ring-token", s.asked("invite").first().text("token"))
        assertEquals("ring-proof", s.asked("invite").first().text("ring"))
    }

    @Test
    fun aStateTheServerDoesNotNameIsRinging() = runBlocking {
        val s = standIn()
        s.answer = { _, _ -> json("""{"ok":true,"state":"whatever"}""") }
        assertEquals("ringing", s.calls.invite(link, "43").state)
    }

    @Test
    fun aRingUpdatesItsStateOnce() {
        val invite = CallInvite("r", "g", to = "43", state = "ringing")
        val heard = ArrayList<String>()
        invite.onState { heard.add(it) }
        invite.update("ringing")
        invite.update("answered")
        invite.update("no_answer")
        assertEquals("a ring that ended stays ended, whichever word arrives last", "answered", invite.state)
        assertEquals(listOf("answered"), heard)
    }

    @Test
    fun aStateHandlerComesOff() {
        val invite = CallInvite("r", "g", to = "43", state = "ringing")
        var heard = 0
        val listener = invite.onState { heard++ }
        invite.off(listener)
        invite.update("busy")
        assertEquals(0, heard)
    }

    @Test
    fun theAppThatRangCallsItOff() = runBlocking {
        val s = standIn()
        val invite = s.calls.invite(link, "43")
        val heard = ArrayList<String>()
        invite.onState { heard.add(it) }
        assertTrue(invite.canCancel)
        invite.cancel()
        assertEquals("cancelled", invite.state)
        assertEquals(listOf("cancelled"), heard)
        assertEquals("ring-token", s.asked("uninvite").first().text("token"))
        assertEquals(s.rt.tab, s.asked("uninvite").first().text("tab"))
        assertFalse("a ring that ended has nothing to call off", invite.canCancel)
    }

    @Test
    fun aRefusalToCallItOffComesBackByItsCode() = runBlocking {
        val s = standIn()
        val invite = s.calls.invite(link, "43")
        s.answer = { _, _ -> json("""{"ok":false,"error":"not_ringing"}""") }
        assertRefused("not_ringing") { invite.cancel() }
        assertEquals("ringing", invite.state)
    }

    @Test
    fun aRingThatEndedHasNothingToCallOff() = runBlocking {
        val s = standIn()
        val invite = s.calls.invite(link, "43")
        invite.update("rejected")
        invite.cancel()
        assertEquals(0, s.asked("uninvite").size)
    }

    @Test
    fun aRingHeardCannotBeCalledOffHere() = runBlocking {
        val s = standIn()
        onMain { s.calls.heardInvite(told()) }
        val invite = s.calls.invites("g-1").first()
        assertEquals("34", invite.from)
        assertEquals("43", invite.to)
        assertRefused("forbidden") { invite.cancel() }
    }

    @Test
    fun aRingOthersStartedReachesTheGroupCall() = runBlocking {
        val s = standIn()
        val group = s.calls.joinGroup(link, "t", null, false, false)
        val heard = ArrayList<CallInvite>()
        onMain {
            group.onInvite { heard.add(it) }
            s.calls.heardInvite(told())
        }
        assertEquals(listOf("ring-9"), heard.map { it.id })
        assertEquals(listOf("ring-9"), group.invites.map { it.id })

        onMain { s.calls.heardInvite(told("state" to JsonPrimitive("busy"))) }
        assertEquals("the same ring is told once", 1, heard.size)
        assertEquals("busy", heard.first().state)
        onMain { group.leave() }
    }

    @Test
    fun aGroupCallsOwnRingIsToldToo() = runBlocking {
        val s = standIn()
        val group = s.calls.joinGroup(link, "t", null, false, false)
        val heard = ArrayList<String>()
        onMain { group.onInvite { heard.add(it.to) } }
        val invite = onMain { group.add("43") }
        assertEquals(listOf("43"), heard)
        assertSame(invite, group.invites.first())
        onMain { group.leave() }
    }

    @Test
    fun itsRingingHeardOnTheLineFirstIsTheSameRing() = runBlocking {
        val s = standIn()
        // The relay tells of the ring before the request is answered, as it may: with the id the route was asked for.
        s.answer = { event, _ ->
            if (event == "invite") {
                val id = s.bodies.last().text("id")!!
                val heard = CountDownLatch(1)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    s.calls.heardInvite(json("""{"op":"invite","id":"$id","group":"g-1","to":43,"state":"ringing"}""") as JsonObject)
                    heard.countDown()
                }
                heard.await()
                json("""{"ok":true,"state":"ringing"}""")
            } else json("""{"ok":true}""")
        }
        val invite = s.calls.invite(link, "43")
        assertEquals(1, s.calls.invites("g-1").size)
        assertSame(invite, s.calls.invites("g-1").first())
        // And it is this app's own to call off.
        s.answer = { _, _ -> json("""{"ok":true,"state":"cancelled"}""") }
        invite.cancel()
        assertEquals("cancelled", invite.state)
    }

    @Test
    fun ringsForAnotherCallStayApart() = runBlocking {
        val s = standIn()
        val group = s.calls.joinGroup(link, "t", null, false, false)
        var heard = 0
        onMain {
            group.onInvite { heard++ }
            s.calls.heardInvite(told("group" to JsonPrimitive("g-2")))
        }
        assertEquals(0, heard)
        assertEquals(0, group.invites.size)
        onMain { group.leave() }
    }

    @Test
    fun aRingBeforeTheGroupCallIsThereOnceItIs() = runBlocking {
        val s = standIn()
        onMain { s.calls.heardInvite(told()) }
        val group = s.calls.joinGroup(link, "t", null, false, false)
        assertEquals(listOf("ring-9"), group.invites.map { it.id })
        onMain { group.leave() }
    }

    @Test
    fun aGroupCallEndedForgetsItsRings() = runBlocking {
        val s = standIn()
        val group = s.calls.joinGroup(link, "t", null, false, false)
        onMain {
            s.calls.heardInvite(told())
            group.leave()
        }
        assertEquals(0, s.calls.invites("g-1").size)
        assertEquals(0, group.invites.size)
    }

    @Test
    fun aGroupCallThatIsNoCallsHasNoRings() = runBlocking {
        val s = standIn()
        s.answer = { _, _ -> json("""{"ok":true,"server":"wss://nowhere.invalid","token":"room-token"}""") }
        val group = s.calls.group("meeting:7", CallType.AUDIO)
        onMain { s.calls.heardInvite(told()) }
        assertEquals(0, group.invites.size)
        onMain { group.leave() }
    }

    @Test
    fun whatTheRelaySaysOtherwiseIsPassedOver() = runBlocking {
        val s = standIn()
        onMain {
            s.calls.heardInvite(told("state" to JsonPrimitive("accepted")))
            s.calls.heardInvite(told("to" to JsonPrimitive("two words")))
            s.calls.heardInvite(JsonObject(told().jsonObject - "group"))
            s.calls.heardInvite(JsonObject(told().jsonObject - "id"))
        }
        assertEquals(0, s.calls.invites("g-1").size)
    }

    @Test
    fun theLineTellsOfARing() {
        val s = standIn()
        s.calls.onLine(told())
        mainSettled()
        assertEquals(listOf("ring-9"), s.calls.invites("g-1").map { it.id })
    }
}
