package cloud.wordplus.realtime.calls

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wordplus.realtime.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Calls, group calls and streams from a token of the app's own server, through the calls and the socket as the browser's go
 * (@wordplus/realtime-client's sdk-calls/calls.ts, its startCall, joinGroupCall, joinStream and addTo): the calls route is
 * asked for nothing a token already gave. CallTokensTest reads the tokens the server package really makes. With no server:
 * the route, the socket and the cloud's address are stand-ins.
 */
@RunWith(AndroidJUnit4::class)
class TokenCallsTest {
    private fun token(text: String) = Base64.encodeToString(text.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** A connection whose user is [user], as the app's server named them. */
    private fun identity(user: String) = Identity("eyJhbGciOiJIUzI1NiJ9." + token("""{"sub":"$user"}""") + ".c2lnbmF0dXJl", 4102444800.0)

    private val aCall = """"token":"signed-call","call":"call:abc","id":"call-1","to":34,"type":"audio","key":"signal-key","media_key":"media-key","pair":[42,34],"member":"member-proof""""
    private val callToken = token("""{$aCall,"exp":4102444800}""")
    private val ringToken = token("""{"token":"signed-ring","exp":4102444800,"call":"call:abc","id":"ring-1","to":56,"type":"audio","group":"call-1","pair":[42,34],"member":"member-proof","ring":"ring-proof"}""")
    private val link = GroupOf("call-1", "call:abc", CallType.AUDIO, JSONArray("[42,34]"), "member-proof")

    private val host = """{"until":4102444800,"proof":"host-proof"}"""
    private val recorder = """{"until":4102444800,"proof":"recorder-proof"}"""
    private val groupToken = token("""{"token":"signed-room","exp":4102444800,"room":"class:7","type":"audio","user":42,"host":$host,"recorder":$recorder}""")
    private val guestToken = token("""{"token":"signed-room","exp":4102444800,"room":"class:7","type":"video","user":42}""")

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    @Test
    fun aCallFromATokenRingsWithNothingAskedOfTheRoute() = runBlocking {
        val s = StandIn()
        val heard = ArrayList<Call>()
        s.calls.onCall { heard.add(it) }
        mainSettled()

        val call = s.calls.startCall(callToken)
        mainSettled()
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
        assertEquals("signed-call", s.asked("call").first().text("token"))
        assertEquals("call-1", call.id)
        assertEquals("call:abc", call.name)
        assertEquals("34", call.peer)
        assertEquals(CallType.AUDIO, call.type)
        assertEquals(Call.Role.CALLER, call.role)
        assertSame(call, heard.first())
        onMain { call.end() }
    }

    @Test
    fun theShortWayInStartsItToo() = runBlocking {
        val s = StandIn(identity = identity("42"))
        val call = s.rt.startCall(ApplicationProvider.getApplicationContext(), callToken)
        assertEquals("signed-call", s.asked("call").first().text("token"))
        onMain { call.end() }
    }

    @Test
    fun aCallsTokenIsRefusedBeforeAnythingIsAsked() = runBlocking {
        val s = StandIn(identity = identity("34"))
        assertRefused("forbidden") { s.calls.startCall(callToken) }
        assertRefused("bad_token") { s.calls.startCall("not a token") }
        assertRefused("bad_token") { s.calls.startCall("34") }
        assertEquals(emptyList<Any>(), s.asked("call"))
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
    }

    @Test
    fun aCallerRenewsThroughTheRouteWithItsProof() = runBlocking {
        val renewed = CountDownLatch(1)
        val s = StandIn(route = { request ->
            // The line goes on holding by itself.
            if (!request.containsKey("line")) renewed.countDown()
            200 to json("""{"token":"renewed","exp":4102444800}""")
        })
        // Running out now, so it is asked for again at the soonest, half a minute on.
        val call = s.calls.startCall(token("""{$aCall,"exp":${System.currentTimeMillis() / 1000}}"""))
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
        assertTrue("renewed within its time", renewed.await(40, TimeUnit.SECONDS))
        assertEquals(json("""{"call":"call:abc","id":"call-1","role":"caller","to":34,"type":"audio","member":"member-proof"}"""), s.bodies.first())
        onMain { call.end() }
    }

    @Test
    fun aCallsRefIsWhatTheAppSendsItsRoute() = runBlocking {
        val s = StandIn()
        val call = s.calls.startCall(callToken)
        val opened = String(Base64.decode(call.ref, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        assertEquals(json("""{"id":"call-1","name":"call:abc","type":"audio","pair":[42,34],"member":"member-proof"}"""), json(opened))
        onMain { call.end() }
    }

    @Test
    fun aCallIsAddedToByATokenOnceItIsUp() = runBlocking {
        val s = StandIn()
        val call = s.calls.startCall(callToken)
        assertRefused("not_connected") { onMain { call.add(ringToken) } }

        onMain { call.session.onConnection!!(CallConnection.CONNECTED) }
        val invite = onMain { call.add(ringToken) }
        assertEquals("56", invite.to)
        assertEquals("ring-1", invite.id)
        assertEquals("call-1", invite.group)
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
        val asked = s.asked("invite").first()
        assertEquals(s.rt.tab, asked.text("tab"))
        assertEquals("signed-ring", asked.text("token"))
        assertEquals("ring-proof", asked.text("ring"))

        // A token of another call, and nothing goes out.
        val other = token("""{"token":"other","call":"call:abc","id":"ring-2","to":56,"group":"call-9","pair":[42,34],"member":"m"}""")
        assertRefused("forbidden") { onMain { call.add(other) } }
        assertEquals(1, s.asked("invite").size)
        onMain { call.end() }
    }

    @Test
    fun aRingFromATokenIsCalledOffWithItsToken() = runBlocking {
        val s = StandIn()
        s.answer = { event, _ -> if (event == "uninvite") json("""{"ok":true,"state":"cancelled"}""") else json("""{"ok":true,"state":"ringing"}""") }
        val invite = s.calls.addTo(link, ringToken)
        assertTrue(invite.canCancel)
        onMain { invite.cancel() }
        assertEquals("signed-ring", s.asked("uninvite").first().text("token"))
        assertEquals("cancelled", invite.state)
    }

    @Test
    fun aUserIdStillAsksTheRule() = runBlocking {
        val s = StandIn(route = { 200 to json("""{"token":"ring-token","ring":"route-proof"}""") })
        val invite = s.calls.addTo(link, "43")
        assertEquals("43", invite.to)
        assertEquals("43", s.bodies.first().text("to"))
        assertEquals("call-1", s.bodies.first().text("group"))
        assertEquals("ring-token", s.asked("invite").first().text("token"))
        assertEquals("route-proof", s.asked("invite").first().text("ring"))
    }

    @Test
    fun aGroupCallMadeFromACallTakesATokenAndHasARef() = runBlocking {
        val s = StandIn()
        val group = s.calls.joinGroup(link, "t", null, false, false)
        val invite = onMain { group.add(ringToken) }
        assertEquals("56", invite.to)
        assertEquals("signed-ring", s.asked("invite").first().text("token"))
        val opened = String(Base64.decode(group.ref, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        assertEquals(json("""{"id":"call-1","name":"call:abc","type":"audio","pair":[42,34],"member":"member-proof"}"""), json(opened))
        onMain { group.leave() }
    }

    @Test
    fun aRingKeepsItsProofFromTheCallersInfo() = runBlocking {
        val s = StandIn()
        val message = JSONObject().put("id", "call-1").put("call", "call:abc").put("from", 42).put("type", "audio")
        val incoming = IncomingCall(s.calls, message, json("""{"name":"Ann","_ring":"ring-proof"}"""))
        assertEquals(json("""{"name":"Ann"}"""), incoming.info)
        assertEquals("ring-proof", incoming.ring)

        val answered = onMain { incoming.acceptNow() }
        assertEquals(json("""{"call":"call:abc","id":"call-1","role":"callee","from":42,"type":"audio","ring":"ring-proof"}"""), s.bodies.first())
        val call = (answered as IncomingCall.Answered.ToCall).call
        assertEquals(json("""{"name":"Ann"}"""), call.peerInfo)
        onMain { call.end() }
    }

    @Test
    fun aRingOnTheLineReachesTheAppWithoutItsProof() = runBlocking {
        val s = StandIn()
        val heard = CopyOnWriteArrayList<IncomingCall>()
        val arrived = CountDownLatch(1)
        s.calls.onIncoming {
            heard.add(it)
            arrived.countDown()
        }
        mainSettled()

        s.calls.onLine(json("""{"op":"incoming","id":"call-7","call":"call:abc","from":42,"type":"video","info":{"name":"Ann","_ring":"ring-proof"}}"""))
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertEquals(json("""{"name":"Ann"}"""), heard.first().info)
        assertEquals("ring-proof", heard.first().ring)
    }

    @Test
    fun aRingWithoutAProofAnswersAsItAlwaysDid() = runBlocking {
        val s = StandIn()
        val message = JSONObject().put("id", "call-1").put("call", "support:34:42").put("from", 34).put("type", "video")
        val incoming = IncomingCall(s.calls, message, json("""{"name":"Ann"}"""))
        assertNull(incoming.ring)
        val answered = onMain { incoming.acceptNow() }
        assertEquals(json("""{"call":"support:34:42","id":"call-1","role":"callee","from":34,"type":"video"}"""), s.bodies.first())
        onMain { (answered as IncomingCall.Answered.ToCall).call.end() }
    }

    @Test
    fun aGroupCallFromATokenJoinsWithNothingAskedOfTheRoute() = runBlocking {
        val s = StandIn()
        val heard = ArrayList<GroupCall>()
        s.calls.onGroup { heard.add(it) }
        mainSettled()

        val group = s.calls.joinGroupCall(groupToken)
        mainSettled()
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
        assertEquals("signed-room", s.asked("room").first().text("token"))
        assertEquals("class:7", group.name)
        assertSame(group, heard.first())
        assertNull("no ref: nobody is rung into a call joined by its token", group.ref)
        assertFalse(group.encrypted)
        assertRefused("not_a_call") { onMain { group.add("43") } }
        onMain { group.leave() }
    }

    @Test
    fun aHostFromATokenActsWithItsProof() = runBlocking {
        val s = StandIn(route = { 200 to json("""{"done":true}""") })
        val group = s.calls.joinGroupCall(groupToken)
        assertTrue(group.moderate(ModerationAct.MUTE, "34"))
        assertEquals(json("""{"moderate":"class:7","act":"mute","user":34,"host":$host}"""), s.bodies.last())
        onMain { group.leave() }
    }

    @Test
    fun aRecorderFromATokenRecordsWithItsProof() = runBlocking {
        val s = StandIn(route = { 200 to json("""{"recording":{"id":"rec-1","state":"recording"}}""") })
        val group = s.calls.joinGroupCall(groupToken)
        assertTrue(group.canRecord)
        assertEquals("rec-1", group.startRecording().id)
        assertEquals(json("""{"call":"class:7","record":"start","recorder":$recorder}"""), s.bodies.last())
        group.stopRecording("rec-1")
        assertEquals(json("""{"call":"class:7","record":"stop","recording":"rec-1","recorder":$recorder}"""), s.bodies.last())
        onMain { group.leave() }
    }

    @Test
    fun aGuestFromATokenIsOfferedNoRecordingAndCarriesNoProof() = runBlocking {
        val s = StandIn(route = { 200 to json("""{"denied":"forbidden"}""") })
        val group = s.calls.joinGroupCall(guestToken)
        assertFalse(group.canRecord)
        assertRefused("forbidden") { group.moderate(ModerationAct.MUTE, "34") }
        assertEquals(json("""{"moderate":"class:7","act":"mute","user":34}"""), s.bodies.last())
        onMain { group.leave() }
    }

    @Test
    fun aGroupCallsTokenIsRefusedBeforeAnythingIsAsked() = runBlocking {
        val s = StandIn(identity = identity("34"))
        assertRefused("forbidden") { s.calls.joinGroupCall(groupToken) }
        assertRefused("bad_token") { s.calls.joinGroupCall("nope") }
        assertRefused("bad_token") { s.calls.joinGroupCall(callToken) }
        assertEquals(emptyList<Any>(), s.asked("room"))
    }

    @Test
    fun theRealtimeServersRefusalOfAGroupCallsTokenComesBack() = runBlocking {
        val s = StandIn()
        s.answer = { _, _ -> json("""{"ok":false,"error":"expired"}""") }
        assertRefused("expired") { s.calls.joinGroupCall(groupToken) }
    }

    /** The cloud's join: what each grant was, and its answers in turn, the last for all that follow. */
    private class Cloud(vararg val answers: String) {
        val grants = CopyOnWriteArrayList<String>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            grants.add(JSONObject(body).getString("grant"))
            val answer = answers[minOf(grants.size, answers.size) - 1]
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private val joined = """{"ok":true,"you":{"identity":"u:42","role":"host"},"stream":{"id":"show:7","state":"idle"}}"""
    private val expired = """{"ok":false,"error":"expired"}"""

    private fun streamToken(grant: String) = token("""{"token":"$grant","exp":4102444800,"name":"show:7"}""")

    @Test
    fun aStreamFromATokenJoinsWithNothingAskedOfTheRoute() = runBlocking {
        val cloud = Cloud(joined)
        val s = StandIn(httpClient = cloud.client)
        var asked = 0
        val stream = onMain { s.calls.joinStream { asked++; streamToken("grant-$asked") } }
        assertEquals("show:7", stream.name)
        assertEquals(LiveStreamRole.HOST, stream.role)
        assertEquals(listOf("grant-1"), cloud.grants.toList())
        assertEquals(1, asked)
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
        onMain { stream.leave() }
    }

    @Test
    fun aStreamAsksForAnotherTokenWhenOneRanOut() = runBlocking {
        val cloud = Cloud(expired, joined)
        val s = StandIn(httpClient = cloud.client)
        var asked = 0
        val stream = onMain { s.calls.joinStream { asked++; streamToken("grant-$asked") } }
        assertEquals(listOf("grant-1", "grant-2"), cloud.grants.toList())
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
        onMain { stream.leave() }
    }

    @Test
    fun aStreamGivenOneTokenSaysExpiredWhenItNeedsAnother() = runBlocking {
        val cloud = Cloud(expired)
        val s = StandIn(httpClient = cloud.client)
        assertRefused("expired") { onMain { s.calls.joinStream(streamToken("only")) } }
        assertEquals(listOf("only"), cloud.grants.toList())
        assertEquals(emptyList<JsonObject>(), s.bodies.toList())
    }

    @Test
    fun aStreamsBadTokenIsRefusedBeforeAnythingIsAsked() = runBlocking {
        val cloud = Cloud(joined)
        val s = StandIn(httpClient = cloud.client)
        assertRefused("bad_token") { onMain { s.calls.joinStream("nope") } }
        assertRefused("bad_token") { onMain { s.calls.joinStream { "nope" } } }
        assertRefused("bad_token") { onMain { s.calls.joinStream(callToken) } }
        assertEquals(emptyList<String>(), cloud.grants.toList())
    }

    @Test
    fun aNewTokenThatIsNoTokenIsRefusedToo() = runBlocking {
        val cloud = Cloud(expired, joined)
        val s = StandIn(httpClient = cloud.client)
        var asked = 0
        assertRefused("bad_token") { onMain { s.calls.joinStream { if (asked++ == 0) streamToken("grant-1") else "nope" } } }
        assertEquals(listOf("grant-1"), cloud.grants.toList())
    }

    @Test
    fun aStreamNamedStillAsksTheRoute() = runBlocking {
        val cloud = Cloud(joined)
        val s = StandIn(route = { 200 to json("""{"token":"route-grant"}""") }, httpClient = cloud.client)
        val stream = onMain { s.calls.stream("show:7") }
        assertEquals(json("""{"stream":"show:7"}"""), s.bodies.first())
        assertEquals(listOf("route-grant"), cloud.grants.toList())
        onMain { stream.leave() }
    }
}
