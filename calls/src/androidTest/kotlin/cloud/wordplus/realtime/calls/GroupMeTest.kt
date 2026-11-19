package cloud.wordplus.realtime.calls

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.Keyring
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import io.livekit.android.room.participant.Participant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `group.me`, this app's own user as the others see them, as the browser's `self` (realtime-client group-call.ts:341). The
 * Swift package's GroupMeTests' twin. With no server: the room is told who it is as the media server's join would.
 */
@RunWith(AndroidJUnit4::class)
class GroupMeTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun group() = GroupCall(ApplicationProvider.getApplicationContext(), StandIn().calls, "meeting:7", null, null, microphone = false, camera = false)

    private fun joined(call: GroupCall, identity: String = "user_34", metadata: String? = """{"name":"Ben"}""") {
        call.liveKitRoom.localParticipant.identity = Participant.Identity(identity)
        call.liveKitRoom.localParticipant.metadata = metadata
    }

    @Test
    fun nobodyUntilConnected() = onMain {
        val call = group()
        assertNull(call.me)
        call.leave()
    }

    @Test
    fun thisUserAsTheOthersSeeThem() = onMain {
        val call = group()
        joined(call)
        val me = call.me
        assertNotNull(me)
        assertEquals("user_34", me!!.identity)
        assertEquals("34", me.user)
        assertEquals("Ben", me.info.jsonObject["name"]?.jsonPrimitive?.content)
        assertFalse(me.camera)
        assertFalse(me.microphone)
        assertFalse(me.speaking)
        assertFalse(me.optimizing)
        call.leave()
    }

    @Test
    fun anIdentityNotOfAUserHasNoUser() = onMain {
        val call = group()
        joined(call, identity = "ingest", metadata = null)
        val me = call.me
        assertNull(me?.user)
        assertEquals(JsonNull, me?.info)
        call.leave()
    }

    @Test
    fun thisUserIsNotAmongThePeople() = onMain {
        val call = group()
        joined(call)
        assertEquals(emptyList<GroupPerson>(), call.people)
        call.leave()
    }

    @Test
    fun nobodyOnceItHasEnded() = onMain {
        val call = group()
        joined(call)
        call.leave()
        assertNull(call.me)
    }

    // `own`, what the call screen draws this user's tile from: a tile drew "?" until the room had connected, as it had no name.

    private fun token(claims: String) =
        "eyJhbGciOiJIUzI1NiJ9." + Base64.encodeToString(claims.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) + ".c2lnbmF0dXJl"

    private fun tokenWith(metadata: String) = token(JsonObject(mapOf("sub" to JsonPrimitive("user_34"), "metadata" to JsonPrimitive(metadata))).toString())

    private fun name(info: JsonElement) = (info as? JsonObject)?.get("name")?.jsonPrimitive?.content

    @Test
    fun ownIsTheTokensBeforeTheRoomConnects() = onMain {
        val call = group()
        call.namedBy(tokenWith("""{"name":"Ben","avatar":"/ben.svg"}"""))
        assertNull(call.me)
        assertEquals("user_34", call.own.first)
        assertEquals("Ben", name(call.own.second))
        assertEquals("/ben.svg", (call.own.second as JsonObject)["avatar"]?.jsonPrimitive?.content)
        call.leave()
    }

    @Test
    fun ownOpensWhatTheAppsServerSealed() = onMain {
        val keyring = Keyring()
        keyring.add(listOf("d:20261109:" + "ab".repeat(32)))
        val rt = Realtime(RealtimeOptions(app = "app_test", endpoint = "http://127.0.0.1:9/api/realtime", keyring = keyring))
        val call = GroupCall(ApplicationProvider.getApplicationContext(), RealtimeCalls.of(ApplicationProvider.getApplicationContext(), rt), "meeting:7", null, null, microphone = false, camera = false)
        val sealed = keyring.sealJson(JsonObject(mapOf("name" to JsonPrimitive("Ben"))), "d:20261109")!!
        call.namedBy(tokenWith(JsonPrimitive(sealed).toString()))
        assertEquals("Ben", name(call.own.second))
        call.leave()
    }

    @Test
    fun ownIsTheRoomsOnceItHasConnected() = onMain {
        val call = group()
        call.namedBy(tokenWith("""{"name":"From the token"}"""))
        joined(call, identity = "user_34", metadata = """{"name":"From the room"}""")
        assertEquals("From the room", name(call.own.second))
        call.leave()
    }

    @Test
    fun ownKeepsTheTokensNameWhileTheRoomHasNoMetadataYet() = onMain {
        val call = group()
        call.namedBy(tokenWith("""{"name":"Ben"}"""))
        joined(call, identity = "user_34", metadata = "")
        assertEquals("Ben", name(call.own.second))
        call.leave()
    }

    @Test
    fun ownWithATokenThatCantBeReadIsNobodyYet() = onMain {
        val call = group()
        call.namedBy("not a token")
        assertEquals("", call.own.first)
        assertEquals(JsonNull, call.own.second)
        call.namedBy(token("not json"))
        assertEquals(JsonNull, call.own.second)
        call.namedBy(tokenWith("{not json"))
        assertEquals("user_34", call.own.first)
        assertEquals(JsonNull, call.own.second)
        call.leave()
    }

    // `can`, what this app may do: the room says nothing until it has joined, and the room's object forgets once it is over.

    @Test
    fun mayDoWhatTheRuleLetsInUntilTheRoomSays() = onMain {
        val call = group()
        assertTrue(call.can.publish)
        assertTrue(call.can.subscribe)
        call.leave()
    }

    @Test
    fun mayDoNothingOnceItHasEnded() = onMain {
        val call = group()
        joined(call)
        call.leave()
        assertFalse(call.can.publish)
        assertFalse(call.can.subscribe)
    }
}
