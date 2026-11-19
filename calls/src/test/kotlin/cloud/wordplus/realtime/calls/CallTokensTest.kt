package cloud.wordplus.realtime.calls

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tokens from the app's own server, as the browser's calls read them (@wordplus/realtime-client's sdk-calls/calls.ts): what
 * they hold, the refusals by their code, and the requests they lead to. test/resources/call-tokens.json was made by
 * @wordplus/realtime's own build (`callToken()`, `groupCallToken()`, `streamToken()`) with the test keys of its
 * spec/vectors.json, and its `authorizeCall()` took every request in it, so these say the two speak the same protocol. Run on
 * the JVM: `./gradlew testDebugUnitTest` in calls/. TokenCallsTest has the same going through the calls and the socket, on
 * the emulator.
 */
class CallTokensTest {
    private val fixtures = Json.parseToJsonElement(javaClass.getResource("/call-tokens.json")!!.readText()).jsonObject

    private fun fixture(name: String) = fixtures.getValue(name).jsonObject
    private fun tokenOf(name: String) = fixture(name).getValue("token").jsonPrimitive.content
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun json(request: JSONObject) = Json.parseToJsonElement(request.toString())

    /** A token as the app's server writes one: base64url of JSON. */
    private fun made(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun code(run: () -> Unit): String? = try {
        run()
        null
    } catch (e: CallException) {
        e.code
    }

    private val link = GroupOf("g-1", "support:17", CallType.VIDEO, JSONArray("[42,34]"), "member-proof")

    @Test
    fun aCallsTokenHoldsWhatTheServerPutInIt() {
        val made = CallTokens.call(tokenOf("call"))!!
        assertEquals("support:17", made.call)
        assertEquals("34", made.to)
        assertEquals(CallType.AUDIO, made.type)
        assertEquals(listOf("42", "34"), made.pair)
        assertTrue(made.member.isNotEmpty())
        assertNull(made.group)
        assertNull(made.ring)
        assertEquals(made.token, CallTokens.open(tokenOf("call"))!!["token"]!!.jsonPrimitive.content)
        // What a Call reads of a grant, in the shapes it reads them.
        assertEquals(made.token, made.grant.optString("token"))
        assertTrue(made.grant.optString("key").isNotEmpty())
        assertTrue(made.grant.optString("media_key").isNotEmpty())
        assertTrue(made.grant.optDouble("exp") > 0)
        assertEquals(made.member, made.grant.optString("member"))
        assertEquals("[42,34]", made.grant.getJSONArray("pair").toString())
    }

    @Test
    fun aCallsTokenTakesAnIdOfTextAndAVideoCallByDefault() {
        val made = CallTokens.call(tokenOf("text_call"))!!
        assertEquals(listOf("user_2abc", "34"), made.pair)
        assertEquals("34", made.to)
        assertEquals(CallType.VIDEO, made.type)
        assertTrue(made.call.startsWith("call:"))
        assertEquals(CallType.VIDEO, CallTokens.call(made("""{"token":"t","call":"c","id":"i","member":"m","to":34,"pair":[42,34],"type":"other"}"""))!!.type)
    }

    @Test
    fun whatIsNoTokenIsRefused() {
        val whole = """{"token":"t","call":"c","id":"i","member":"m","to":34,"pair":[42,34]}"""
        assertNotNull(CallTokens.call(made(whole)))
        val no = listOf(
            "", "not a token", "34", "user_2abc", "auth0|5f7c", "a=b", "aaaaa",
            made("not json"), made("[1]"), made("null"), made("7"), made("{}"),
            made("""{"call":"c","id":"i","member":"m","to":34,"pair":[42,34]}"""),
            made("""{"token":"t","id":"i","member":"m","to":34,"pair":[42,34]}"""),
            made("""{"token":"t","call":"c","member":"m","to":34,"pair":[42,34]}"""),
            made("""{"token":"t","call":"c","id":"i","to":34,"pair":[42,34]}"""),
            made("""{"token":"t","call":"c","id":"i","member":"m","pair":[42,34]}"""),
            made("""{"token":"t","call":"c","id":"i","member":"m","to":0,"pair":[42,34]}"""),
            made("""{"token":"t","call":"c","id":"i","member":"m","to":"two words","pair":[42,34]}"""),
            made("""{"token":"t","call":"c","id":"i","member":"m","to":34}"""),
            made("""{"token":"t","call":"c","id":"i","member":"m","to":34,"pair":[42]}"""),
            made("""{"token":"t","call":"c","id":"i","member":"m","to":34,"pair":[42,34,56]}"""),
            made("""{"token":"t","call":"c","id":"i","member":"m","to":34,"pair":[42,0]}"""),
            made("""{"token":7,"call":"c","id":"i","member":"m","to":34,"pair":[42,34]}"""),
            // A group call's or a stream's token is no call's.
            tokenOf("group_guest"), tokenOf("stream"),
        )
        for (token in no) {
            assertNull(token, CallTokens.call(token))
            assertEquals(token, "bad_token", code { CallTokens.toStart(token, null) })
        }
    }

    @Test
    fun aCallsTokenStartsForTheUserItWasMadeFor() {
        assertEquals("support:17", CallTokens.toStart(tokenOf("call"), "42").call)
        assertEquals(listOf("user_2abc", "34"), CallTokens.toStart(tokenOf("text_call"), "user_2abc").pair)
        // The person it rings is no caller, nor is anyone else.
        assertEquals("forbidden", code { CallTokens.toStart(tokenOf("call"), "34") })
        assertEquals("forbidden", code { CallTokens.toStart(tokenOf("call"), "56") })
        assertEquals("forbidden", code { CallTokens.toStart(tokenOf("text_call"), "user_other") })
        // No user named yet: the realtime server holds the token to its own.
        assertEquals("support:17", CallTokens.toStart(tokenOf("call"), null).call)
    }

    @Test
    fun aGroupCallsTokenHoldsWhatItsPersonMayDo() {
        val host = CallTokens.group(tokenOf("group_host"))!!
        assertEquals("class:7", host.room)
        assertEquals(CallType.AUDIO, host.type)
        assertEquals("42", host.user)
        assertNotNull(host.mediaKey)
        assertEquals(setOf("until", "proof"), host.proofs.host!!.keys)
        assertEquals(setOf("until", "proof"), host.proofs.recorder!!.keys)
        assertFalse(host.proofs.host!!.getValue("until").jsonPrimitive.isString)

        val guest = CallTokens.group(tokenOf("group_guest"))!!
        assertEquals("user_2abc", guest.user)
        assertEquals(CallType.VIDEO, guest.type)
        assertNull(guest.mediaKey)
        assertNull(guest.proofs.host)
        assertNull(guest.proofs.recorder)

        val recorder = CallTokens.group(tokenOf("group_recorder"))!!
        assertNull(recorder.proofs.host)
        assertNotNull(recorder.proofs.recorder)
    }

    @Test
    fun aGroupCallsTokenIsRefusedAsTheBrowsersIs() {
        for (token in listOf("nope", "34", made("{}"), made("""{"token":"t"}"""), made("""{"room":"r"}"""), made("""{"token":7,"room":"r"}"""), tokenOf("call"), tokenOf("stream"))) {
            assertEquals(token, "bad_token", code { CallTokens.toJoin(token, null) })
        }
        assertEquals("class:7", CallTokens.toJoin(tokenOf("group_host"), "42").room)
        assertEquals("class:7", CallTokens.toJoin(tokenOf("group_guest"), "user_2abc").room)
        assertEquals("class:7", CallTokens.toJoin(tokenOf("group_host"), null).room)
        assertEquals("forbidden", code { CallTokens.toJoin(tokenOf("group_host"), "34") })
        assertEquals("forbidden", code { CallTokens.toJoin(tokenOf("group_guest"), "42") })
        // One that names nobody is for nobody, once this connection has a user.
        assertEquals("forbidden", code { CallTokens.toJoin(made("""{"token":"t","room":"r"}"""), "42") })
    }

    @Test
    fun aStreamsTokenHoldsItsName() {
        assertEquals("show:7", CallTokens.toWatch(tokenOf("stream")).name)
        assertEquals("show:7", CallTokens.toWatch(tokenOf("stream_visitor")).name)
        assertEquals(CallTokens.open(tokenOf("stream"))!!["token"]!!.jsonPrimitive.content, CallTokens.toWatch(tokenOf("stream")).token)
        for (token in listOf("nope", made("{}"), made("""{"token":"t"}"""), made("""{"name":"n"}"""), tokenOf("call"), tokenOf("group_host"))) {
            assertEquals(token, "bad_token", code { CallTokens.toWatch(token) })
        }
    }

    @Test
    fun aStreamUsesTheTokenItJoinedWithOnceThenAsksForAnother() = runBlocking {
        var asked = 0
        val tokens = CallTokens.tokens("first") { "new-${++asked}" }
        assertEquals("first", tokens())
        assertEquals(0, asked)
        assertEquals("new-1", tokens())
        assertEquals("new-2", tokens())
    }

    @Test
    fun aStreamGivenOneTokenExpiresWhenItWouldNeedAnother() = runBlocking {
        val tokens = CallTokens.tokens("only") { throw CallException("expired") }
        assertEquals("only", tokens())
        try {
            tokens()
            throw AssertionError("no refusal")
        } catch (e: CallException) {
            assertEquals("expired", e.code)
        }
    }

    @Test
    fun theRingsProofIsTakenOutOfTheCallersInfo() {
        val told = CallTokens.ringInfo(fixture("call").getValue("info"))
        assertEquals(json("""{"name":"Ann"}"""), told.info)
        assertEquals(fixture("call").getValue("answer").jsonObject.getValue("ring").jsonPrimitive.content, told.ring)
    }

    @Test
    fun aRingsInfoWithNothingElseIsNoInfo() {
        assertEquals(RingInfo(JsonNull, "proof"), CallTokens.ringInfo(json("""{"_ring":"proof"}""")))
    }

    @Test
    fun anInfoWithoutTheProofStaysAsItIs() {
        for (info in listOf("""{"name":"Ann"}""", """{"_ring":7,"name":"Ann"}""", """{"_ring":null}""", """"sealed"""", "null", "[1]")) {
            assertEquals(info, RingInfo(json(info), null), CallTokens.ringInfo(json(info)))
        }
    }

    @Test
    fun answeringCarriesTheRingsProofAsTheServerTakesIt() {
        for (name in listOf("call", "text_call")) {
            val fixture = fixture(name).getValue("answer").jsonObject
            fun text(key: String) = fixture.getValue(key).jsonPrimitive.content
            val request = CallTokens.answering(text("call"), text("id"), text("from"), if (text("type") == "audio") CallType.AUDIO else CallType.VIDEO, text("ring"))
            assertEquals(fixture, json(request))
        }
    }

    @Test
    fun answeringWithNoProofAsksWhatItAlwaysAsked() {
        assertEquals(
            json("""{"call":"support:34:42","id":"r-1","role":"callee","from":34,"type":"audio"}"""),
            json(CallTokens.answering("support:34:42", "r-1", "34", CallType.AUDIO, null)),
        )
        assertEquals(
            json("""{"call":"c","id":"r-1","role":"callee","from":"auth0|5f7c","type":"video"}"""),
            json(CallTokens.answering("c", "r-1", "auth0|5f7c", CallType.VIDEO, null)),
        )
    }

    @Test
    fun aCallerRenewsWithItsProofOfBeingInTheCall() {
        for (name in listOf("call", "text_call")) {
            assertEquals(fixture(name).getValue("renewal"), json(CallTokens.renewal(CallTokens.call(tokenOf(name))!!)))
        }
    }

    @Test
    fun aRefIsWhatTheServerTakesToRingSomeoneIn() {
        for (name in listOf("call", "text_call")) {
            val made = CallTokens.call(tokenOf(name))!!
            val of = GroupOf(made.id, made.call, made.type, made.grant.opt("pair"), made.member)
            assertEquals(fixture(name).getValue("ref").jsonPrimitive.content, CallTokens.ref(of))
        }
    }

    @Test
    fun aRefLeavesOutWhatTheCallHasNot() {
        fun opened(ref: String) = json(String(Base64.getUrlDecoder().decode(ref)))
        assertEquals(json("""{"id":"g","name":"n","type":"audio"}"""), opened(CallTokens.ref(GroupOf("g", "n", CallType.AUDIO, null, null))))
        assertEquals(json("""{"id":"g","name":"n","type":"video"}"""), opened(CallTokens.ref(GroupOf("g", "n", CallType.VIDEO, JSONObject.NULL, null))))
        assertEquals(json("""{"id":"g","name":"n","type":"video","pair":["a b",3],"member":"m"}"""), opened(CallTokens.ref(GroupOf("g", "n", CallType.VIDEO, JSONArray("""["a b",3]"""), "m"))))
    }

    @Test
    fun aRefIsNoTokenToStartOrAdd() {
        val ref = CallTokens.ref(link)
        assertNotNull(CallTokens.open(ref))
        assertNull(CallTokens.call(ref))
    }

    @Test
    fun aTokenThatRingsSomeoneInReadsItsRingAndCall() {
        val made = CallTokens.call(tokenOf("ring_in"))!!
        assertEquals("56", made.to)
        assertEquals(CallTokens.call(tokenOf("call"))!!.id, made.group)
        assertEquals(CallTokens.call(tokenOf("call"))!!.pair, made.pair)
        assertNotNull(made.ring)
        assertEquals(fixture("ring_in").getValue("answer").jsonObject.getValue("ring").jsonPrimitive.content, made.ring)
    }

    @Test
    fun addTellsATokenFromAUserId() {
        val call = CallTokens.call(tokenOf("call"))!!
        val of = GroupOf(call.id, call.call, call.type, call.grant.opt("pair"), call.member)
        // A token that rings into this call.
        assertEquals("56", CallTokens.toRing(tokenOf("ring_in"), of)!!.to)
        // Anything that doesn't open as a call's token is a user id, base64url in its shape or not.
        for (user in listOf("43", "34", "user_2abc", "auth0|5f7c", "e30", "abcd", made("""{"name":"Ann"}"""))) {
            assertNull(user, CallTokens.toRing(user, of))
        }
    }

    @Test
    fun addRefusesATokenOfAnotherCall() {
        assertEquals("forbidden", code { CallTokens.toRing(tokenOf("ring_in"), link) })
        // One that starts a call rings into none.
        val call = CallTokens.call(tokenOf("call"))!!
        assertEquals("forbidden", code { CallTokens.toRing(tokenOf("call"), GroupOf(call.id, call.call, call.type, null, null)) })
    }

    @Test
    fun aHostsActsCarryTheProofAsTheServerTakesThem() {
        val fixture = fixture("group_host")
        val host = CallTokens.group(tokenOf("group_host"))!!.proofs.host
        assertEquals(fixture.getValue("moderate"), CallsRoute.moderation("class:7", ModerationAct.MUTE, "34", host))
        // With none, as a rule's host asks.
        assertEquals(json("""{"moderate":"class:7","act":"mute","user":34}"""), CallsRoute.moderation("class:7", ModerationAct.MUTE, "34"))
        assertEquals(
            JsonObject(mapOf("moderate" to JsonPrimitive("class:7"), "act" to JsonPrimitive("unspotlight"), "user" to JsonNull, "host" to host!!)),
            CallsRoute.moderation("class:7", ModerationAct.UNSPOTLIGHT, null, host),
        )
    }

    @Test
    fun aRecordingCarriesTheProofAsTheServerTakesIt() {
        val fixture = fixture("group_host")
        val recorder = CallTokens.group(tokenOf("group_host"))!!.proofs.recorder
        assertEquals(fixture.getValue("record"), CallsRoute.record("class:7", "start", null, recorder))
        assertEquals(fixture.getValue("stop"), CallsRoute.record("class:7", "stop", "rec123456", recorder))
        assertEquals(json("""{"call":"class:7","record":"start"}"""), CallsRoute.record("class:7", "start", null))
    }

    private fun assertEquals(expected: JsonElement, actual: JsonElement) = org.junit.Assert.assertEquals(expected, actual)
}
