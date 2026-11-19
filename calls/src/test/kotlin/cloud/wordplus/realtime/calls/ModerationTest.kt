package cloud.wordplus.realtime.calls

import io.livekit.android.events.DisconnectReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * A host's acts in a group call, through the app's calls route, as the browser's group calls send them
 * (@wordplus/realtime-client 952f54f, its test/sdk-calls.test.ts) and the server packages take them (`moderate`): the
 * request's body, the answer, the refusals by their code ([CallsRoute]), and the host as the room's token names it
 * ([GroupRoom]). Run on the JVM: `./gradlew testDebugUnitTest` in calls/.
 */
class ModerationTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun code(run: () -> Unit): String? = try {
        run()
        null
    } catch (e: CallException) {
        e.code
    }

    @Test
    fun theSpotlightNamesUsersAsTheActsTakeThem() {
        assertEquals(listOf("34", "auth0|5f7c8e"), GroupRoom.users(listOf("user_34", "user_auth0|5f7c8e", "not-a-user")))
    }

    @Test
    fun aConnectionIsRatedInTheWordsOfACallBetweenTwo() {
        assertEquals(CallConnectionQuality.EXCELLENT, CallConnectionQuality.of(io.livekit.android.room.participant.ConnectionQuality.EXCELLENT))
        assertEquals(CallConnectionQuality.GOOD, CallConnectionQuality.of(io.livekit.android.room.participant.ConnectionQuality.GOOD))
        assertEquals(CallConnectionQuality.POOR, CallConnectionQuality.of(io.livekit.android.room.participant.ConnectionQuality.POOR))
        assertEquals(CallConnectionQuality.LOST, CallConnectionQuality.of(io.livekit.android.room.participant.ConnectionQuality.LOST))
        assertEquals(CallConnectionQuality.UNKNOWN, CallConnectionQuality.of(io.livekit.android.room.participant.ConnectionQuality.UNKNOWN))
        assertEquals(CallConnectionQuality.UNKNOWN, CallConnectionQuality.of(null))
        assertEquals(CallConnectionQuality.UNKNOWN, GroupPerson(identity = "user_1", user = "1", info = kotlinx.serialization.json.JsonNull, camera = true, microphone = true, speaking = false).quality)
    }

    @Test
    fun aCallsNameIsFilledInSoEitherSideNamesTheSameCall() {
        assertEquals("support:34:42", CallName.named("support:{pair}", "42", "34"))
        assertEquals("support:34:42", CallName.named("support:{pair}", "34", "42"))
        // As numbers when both are, 9 before 10, and as text otherwise.
        assertEquals("support:9:10", CallName.named("support:{pair}", "10", "9"))
        assertEquals("support:user_a:user_b", CallName.named("support:{pair}", "user_b", "user_a"))
        assertEquals("from:42:to:34", CallName.named("from:{me}:to:{id}", "42", "34"))
        // A name with no template is as it is, with no user wanted.
        assertEquals("support:34:42", CallName.named("support:34:42", null, "34"))
        assertEquals("ring:34", CallName.named("ring:{id}", null, "34"))
        assertEquals("no_identity", code { CallName.named("support:{pair}", null, "34") })
    }

    @Test
    fun aHostsActsAreAskedOfTheCallsRoute() {
        // An id of digits as the number, any other as its text, as the relay and the server packages read them.
        assertEquals(json("""{"moderate":"meeting:7","act":"mute","user":42}"""), CallsRoute.moderation("meeting:7", ModerationAct.MUTE, "42"))
        assertEquals(json("""{"moderate":"meeting:7","act":"speak","user":"auth0|5f7c8e"}"""), CallsRoute.moderation("meeting:7", ModerationAct.SPEAK, "auth0|5f7c8e"))
        assertEquals(json("""{"moderate":"meeting:7","act":"listen","user":"042"}"""), CallsRoute.moderation("meeting:7", ModerationAct.LISTEN, "042"))
        assertEquals(json("""{"moderate":"meeting:7","act":"remove","user":42}"""), CallsRoute.moderation("meeting:7", ModerationAct.REMOVE, "42"))
    }

    @Test
    fun theAnswerSaysWhetherItChangedAnything() {
        assertTrue(CallsRoute.done(CallsRoute.answer(200, json("""{"done":true}"""))))
        assertFalse(CallsRoute.done(CallsRoute.answer(200, json("""{"done":false}"""))))
        // An answer that says nothing of it, or not as a boolean.
        assertEquals("server", code { CallsRoute.done(CallsRoute.answer(200, json("""{"ok":true}"""))) })
        assertEquals("server", code { CallsRoute.done(CallsRoute.answer(200, json("""{"done":"yes"}"""))) })
    }

    @Test
    fun aRefusalComesBackByItsCode() {
        assertEquals("forbidden", code { CallsRoute.answer(200, json("""{"denied":"forbidden"}""")) })
        assertEquals("not_moderated", code { CallsRoute.answer(200, json("""{"denied":"not_moderated"}""")) })
        assertEquals("forbidden", code { CallsRoute.answer(403, null) })
        assertEquals("forbidden", code { CallsRoute.answer(401, json("""{"code":"no_user"}""")) })
        assertEquals("bad_request", code { CallsRoute.answer(403, json("""{"denied":"bad_request"}""")) })
        assertEquals("auth_failed", code { CallsRoute.answer(500, null) })
        assertEquals("auth_failed", code { CallsRoute.answer(200, json("[1]")) })
        // A grant passes as it came: authorize() asks for its token.
        assertEquals(JsonPrimitive("t"), CallsRoute.answer(200, json("""{"token":"t"}"""))["token"])
    }

    @Test
    fun aHostSpotlightsSomeoneThemselvesTooAndEveryoneOutWithNobodyNamed() {
        // As @wordplus/realtime-client 1.0.64 (9e757f5) and the server packages take them (realtime-js 6ecdf02).
        assertEquals(json("""{"moderate":"meeting:7","act":"spotlight","user":42}"""), CallsRoute.moderation("meeting:7", ModerationAct.SPOTLIGHT, "42"))
        assertEquals(json("""{"moderate":"meeting:7","act":"spotlight","user":1}"""), CallsRoute.moderation("meeting:7", ModerationAct.SPOTLIGHT, "1"))
        assertEquals(json("""{"moderate":"meeting:7","act":"unspotlight","user":42}"""), CallsRoute.moderation("meeting:7", ModerationAct.UNSPOTLIGHT, "42"))
        assertEquals(json("""{"moderate":"meeting:7","act":"unspotlight","user":null}"""), CallsRoute.moderation("meeting:7", ModerationAct.UNSPOTLIGHT, null))
        // A fifth person: the route's refusal, for the call screen to say.
        assertEquals("spotlight_full", code { CallsRoute.answer(200, json("""{"denied":"spotlight_full"}""")) })
    }

    @Test
    fun theSpotlightIsTheRoomsMetadata() {
        // Strings alone, once each, in their order, at most four, as the browser's spotlightOf() reads them.
        assertEquals(listOf("user_4", "user_9", "user_2", "user_5"), GroupRoom.spotlight("""{"spotlight":["user_4","user_9","user_2","user_4","",5,"user_5","user_6"]}"""))
        assertEquals(emptyList<String>(), GroupRoom.spotlight("""{"spotlight":[],"other":1}"""))
        assertEquals(emptyList<String>(), GroupRoom.spotlight("""{"spotlight":"user_4"}"""))
        assertEquals(emptyList<String>(), GroupRoom.spotlight("""{"layout":"grid"}"""))
        assertEquals(emptyList<String>(), GroupRoom.spotlight("not json"))
        assertEquals(emptyList<String>(), GroupRoom.spotlight("[]"))
        assertEquals(emptyList<String>(), GroupRoom.spotlight(""))
        assertEquals(emptyList<String>(), GroupRoom.spotlight(null))
    }

    @Test
    fun aRoomEndsWithWhatTheAppsServerDid() {
        // As the browser's group calls say them (@wordplus/realtime-client a738a57, disconnectedBy).
        assertEquals("removed", GroupRoom.endReason(DisconnectReason.PARTICIPANT_REMOVED))
        assertEquals("closed", GroupRoom.endReason(DisconnectReason.ROOM_DELETED))
        assertEquals("disconnected", GroupRoom.endReason(DisconnectReason.SERVER_SHUTDOWN))
        assertEquals("disconnected", GroupRoom.endReason(DisconnectReason.UNKNOWN_REASON))
    }

    @Test
    fun aGroupCallIsRecordedAndStoppedThroughTheCallsRoute() {
        // As @wordplus/realtime-client 1.0.65 (c44a6fb) asks it and the server packages take it (realtime-js dd6e20d).
        assertEquals(json("""{"call":"meeting:7","record":"start"}"""), CallsRoute.record("meeting:7", "start", null))
        assertEquals(json("""{"call":"meeting:7","record":"stop","recording":"rec_0123456789"}"""), CallsRoute.record("meeting:7", "stop", "rec_0123456789"))
        assertEquals(
            GroupRecording("rec_0123456789", "recording"),
            CallsRoute.recording(CallsRoute.answer(200, json("""{"recording":{"id":"rec_0123456789","state":"recording"}}"""))),
        )
        // An answer that tells of no recording, and WordPlus Cloud's refusals, by their code.
        assertEquals("server", code { CallsRoute.recording(CallsRoute.answer(200, json("""{"recording":{"state":"recording"}}"""))) })
        assertEquals("server", code { CallsRoute.recording(CallsRoute.answer(200, json("""{"ok":true}"""))) })
        assertEquals("no_credits", code { CallsRoute.answer(200, json("""{"denied":"no_credits"}""")) })
        assertEquals("already_recording", code { CallsRoute.answer(200, json("""{"denied":"already_recording"}""")) })
    }

    @Test
    fun theHostIsTheRoomTokensAdmin() {
        fun token(claims: String) = "eyJhbGciOiJIUzI1NiJ9." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".c2lnbmF0dXJl"

        assertTrue(GroupRoom.admin(token("""{"sub":"user_1","video":{"room":"meeting:7","roomJoin":true,"roomAdmin":true}}""")))
        assertFalse(GroupRoom.admin(token("""{"sub":"user_2","video":{"room":"meeting:7","roomJoin":true}}""")))
        assertFalse(GroupRoom.admin(token("""{"sub":"user_2","video":{"roomAdmin":"true"}}""")))
        assertFalse(GroupRoom.admin(token("""{"sub":"user_2"}""")))
        assertFalse(GroupRoom.admin("not a token"))
        assertFalse(GroupRoom.admin("a.b.c"))
        assertEquals(GroupPermissions(publish = true, subscribe = true), GroupPermissions(publish = true, subscribe = true, admin = false))
    }

    @Test
    fun whoIsOfferedRecordingIsTheCallsRoutesAnswerOrTheTokens() {
        // A group call by its name: the room's answer says it, as the server packages answer `record` for whoever the rule
        // answers `record`, else its host, and the browser's group calls read it (sdk-calls/group-call.ts, mayRecord).
        assertTrue(CallsRoute.mayRecord(org.json.JSONObject("""{"token":"t","record":true}""")))
        assertFalse(CallsRoute.mayRecord(org.json.JSONObject("""{"token":"t"}""")))
        assertFalse(CallsRoute.mayRecord(org.json.JSONObject("""{"token":"t","record":false}""")))
        assertFalse(CallsRoute.mayRecord(org.json.JSONObject("""{"token":"t","record":"true"}""")))

        // Someone the rule lets record is offered it, host or not, and nobody else, though the token makes them the host.
        assertTrue(GroupRoom.offersRecording(moderated = true, encrypted = false, proofs = null, mayRecord = true))
        assertFalse(GroupRoom.offersRecording(moderated = true, encrypted = false, proofs = null, mayRecord = false))
        // Never media the recorder can't read, nor a room the app's server doesn't name.
        assertFalse(GroupRoom.offersRecording(moderated = true, encrypted = true, proofs = null, mayRecord = true))
        assertFalse(GroupRoom.offersRecording(moderated = false, encrypted = false, proofs = null, mayRecord = true))

        // A group call by a token goes by the token's proof alone.
        val recorder = Json.parseToJsonElement("""{"until":1794253804,"proof":"07R40JVIBI1OiE1XJ4pwOLxf3ChMrgYBZe4fkWCv3Co"}""") as kotlinx.serialization.json.JsonObject
        assertTrue(GroupRoom.offersRecording(moderated = true, encrypted = false, proofs = GroupProofs(recorder = recorder), mayRecord = false))
        assertFalse(GroupRoom.offersRecording(moderated = true, encrypted = false, proofs = GroupProofs(host = recorder), mayRecord = true))
    }
}
