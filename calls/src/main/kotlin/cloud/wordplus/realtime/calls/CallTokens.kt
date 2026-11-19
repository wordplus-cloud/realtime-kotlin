package cloud.wordplus.realtime.calls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A call in a token from the app's own server (its `callToken()`), or a ring into a call that became a group call. */
internal class MadeCall(
    /** The call's token for the realtime server. */
    val token: String,
    val call: String,
    val id: String,
    val to: String,
    val type: CallType,
    /** The call's two people, its caller first, each as [CallsRoute.user] spells them. */
    val pair: List<String>,
    /** This user's proof of being in the call. */
    val member: String,
    /** The group call it rings into, for a token that rings someone in: the id of the call it was made from. */
    val group: String?,
    /** The proof that the person was rung, which the realtime server hands them with the ring. */
    val ring: String?,
    /** The token as it came, read as a [Call] reads the calls route's answer: its keys, its pair, when it runs out. */
    val grant: JSONObject,
)

/** A place in a group call in a token from the app's own server (its `groupCallToken()`). */
internal class MadeGroup(
    val token: String,
    val room: String,
    val type: CallType,
    /** Who it was made for, as [CallsRoute.user] spells them. */
    val user: String?,
    val mediaKey: String?,
    val proofs: GroupProofs,
)

/** A place in a live stream in a token from the app's own server (its `streamToken()`). */
internal class MadeStream(val token: String, val name: String)

/** What a token from the app's own server gave this user to host a group call or record it, which the calls route checks. */
internal data class GroupProofs(val host: JsonObject? = null, val recorder: JsonObject? = null)

/** A ring's info once it is open: what the app's server said about the caller, and the proof it carried for the calls route. */
internal data class RingInfo(val info: JsonElement, val ring: String?)

/**
 * The tokens of the app's own server, which decided by itself who may call or join (the server package's `callToken()`,
 * `groupCallToken()` and `streamToken()`): base64url of JSON, opaque to the app. They are read and refused as the browser's
 * calls read them (@wordplus/realtime-client's sdk-calls/calls.ts), and so are the requests they lead to.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object CallTokens {
    private val url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val written = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val SHAPE = Regex("^[A-Za-z0-9_-]+$")

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun typeOf(made: JsonObject) = if (made.text("type") == "audio") CallType.AUDIO else CallType.VIDEO

    /** A token from the app's server, read, or null for anything else. */
    fun open(token: String): JsonObject? {
        if (!SHAPE.matches(token)) return null
        return runCatching { Json.parseToJsonElement(String(url.decode(token))) as? JsonObject }.getOrNull()
    }

    /** A token from the app's `callToken()`, read, or null for anything else: a user id, so, for `add`. */
    fun call(token: String): MadeCall? {
        val made = open(token) ?: return null
        val inner = made.text("token") ?: return null
        val call = made.text("call") ?: return null
        val id = made.text("id") ?: return null
        val member = made.text("member") ?: return null
        val to = CallsRoute.user(made["to"]) ?: return null
        val pair = (made["pair"] as? JsonArray)?.takeIf { it.size == 2 }?.map { CallsRoute.user(it) ?: return null } ?: return null
        return MadeCall(inner, call, id, to, typeOf(made), pair, member, made.text("group"), made.text("ring"), JSONObject(made.toString()))
    }

    /** A token from the app's `groupCallToken()`, read, or null for anything else. */
    fun group(token: String): MadeGroup? {
        val made = open(token) ?: return null
        val inner = made.text("token") ?: return null
        val room = made.text("room") ?: return null
        val proofs = GroupProofs(made["host"] as? JsonObject, made["recorder"] as? JsonObject)
        return MadeGroup(inner, room, typeOf(made), CallsRoute.user(made["user"]), made.text("media_key")?.takeIf { it.isNotEmpty() }, proofs)
    }

    /** A token from the app's `streamToken()`, read, or null for anything else. */
    fun stream(token: String): MadeStream? {
        val made = open(token) ?: return null
        return MadeStream(made.text("token") ?: return null, made.text("name") ?: return null)
    }

    /** Whether a token made for [user] is for this connection's user, [me]: any is, while the app's server has named nobody. */
    private fun madeFor(user: String?, me: String?) = me == null || user == CallsRoute.user(JsonPrimitive(me))

    /** The call a token starts. Throws `bad_token`, or `forbidden` for a token made for another user. */
    fun toStart(token: String, me: String?): MadeCall {
        val made = call(token) ?: throw CallException("bad_token")
        if (!madeFor(made.pair[0], me)) throw CallException("forbidden")
        return made
    }

    /** The group call a token joins. Throws `bad_token`, or `forbidden` for a token made for another user. */
    fun toJoin(token: String, me: String?): MadeGroup {
        val made = group(token) ?: throw CallException("bad_token")
        if (!madeFor(made.user, me)) throw CallException("forbidden")
        return made
    }

    /** The live stream a token joins. Throws `bad_token`. */
    fun toWatch(token: String): MadeStream = stream(token) ?: throw CallException("bad_token")

    /**
     * What `add` was given, told apart as the browser tells it: the ring in a token that opens as a call's, or null for a
     * user id. Throws `forbidden` for a token that doesn't ring into this call.
     */
    fun toRing(who: String, link: GroupOf): MadeCall? {
        val made = call(who) ?: return null
        if (made.group != link.id) throw CallException("forbidden")
        return made
    }

    /** What a stream asks for each token it needs: the one it joined with, once, then [next]'s. */
    fun tokens(first: String, next: suspend () -> String): suspend () -> String {
        var unused: String? = first
        return { unused?.also { unused = null } ?: next() }
    }

    /**
     * What a caller started from a token asks the calls route, should the call outlast it: its proof of being in the call
     * says yes.
     */
    fun renewal(made: MadeCall): JSONObject = JSONObject().put("call", made.call).put("id", made.id).put("role", "caller")
        .put("to", RealtimeCalls.userValue(made.to)).put("type", made.type.wire).put("member", made.member)

    /**
     * What answering a call between two asks the calls route, with the proof the ring brought when it brought one: the
     * person called fetches no token of their own.
     */
    fun answering(call: String, id: String, from: String, type: CallType, ring: String?): JSONObject {
        val request = JSONObject().put("call", call).put("id", id).put("role", "callee").put("from", RealtimeCalls.userValue(from)).put("type", type.wire)
        ring?.let { request.put("ring", it) }
        return request
    }

    /**
     * A ring's info, once open: a call from a token of the app's server carries the proof that this user was rung inside
     * it (`_ring`), which is for the calls route alone and not part of what the app said about the caller.
     */
    fun ringInfo(opened: JsonElement): RingInfo {
        val info = opened as? JsonObject ?: return RingInfo(opened, null)
        val ring = info.text("_ring") ?: return RingInfo(opened, null)
        val rest = info - "_ring"
        return RingInfo(if (rest.isEmpty()) JsonNull else JsonObject(rest), ring)
    }

    /** What the app sends its route to ring someone into a call: the call, and the proof that this user is in it. */
    fun ref(link: GroupOf): String {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["id"] = JsonPrimitive(link.id)
        fields["name"] = JsonPrimitive(link.name)
        fields["type"] = JsonPrimitive(link.type.wire)
        (link.pair?.let { runCatching { Json.parseToJsonElement(it.toString()) }.getOrNull() } as? JsonArray)?.let { fields["pair"] = it }
        link.member?.let { fields["member"] = JsonPrimitive(it) }
        return written.encode(JsonObject(fields).toString().toByteArray())
    }
}
