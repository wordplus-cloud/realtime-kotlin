package cloud.wordplus.realtime.calls

import cloud.wordplus.realtime.ConnectionState
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.currentSocket
import cloud.wordplus.realtime.moduleQueue
import cloud.wordplus.realtime.onSocketEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Better Messages' signalling for its calls between two people: the `p2pCall` requests and events of its namespace of the
 * realtime server, the web's transport in Better Messages, on a connection opened there. The relay behind it is the one
 * the SDK's own calls reach on `/channels`, with the same ops; on Better Messages' namespace the socket is one user's, by
 * its handshake, so a request carries no token.
 *
 *     val rt = Realtime(RealtimeOptions(app = site, endpoint = siteRest, server = "https://cloud.better-messages.com",
 *                                       namespace = "/", auth = buildJsonObject { put("sid", site); put("uid", user); put("sk", key); put("v", "3.2.0") }))
 *     rt.connect()
 *     val call = rt.calls(context).call(over = P2PCallTransport(rt), id = messageId, role = Call.Role.CALLEE, peer = caller, signalKey = key,
 *                                       roomServer = siteMediaServer)
 *
 * The connection reconnects, moves between nodes and keeps the nodes it knows as on any namespace, and the call joins again
 * on each new socket. One transport serves every call of the connection, and lives as long as it does.
 *
 * @param rt a connection opened on Better Messages' namespace ([cloud.wordplus.realtime.RealtimeOptions.namespace], with
 * its handshake in `auth`).
 */
class P2PCallTransport(private val rt: Realtime) : CallTransport {
    private val handlers = HashMap<String, Pair<Any, (JsonElement) -> Unit>>()
    private val reconnects = LinkedHashMap<Any, () -> Unit>()

    init {
        rt.onSocketEvent("p2pCall") { relayed(it) }
        // Back after a drop, and on a new socket after a move: each call joins again there.
        rt.onState { state, _ -> if (state == ConnectionState.Connected) changed() }
        rt.on("moved") { _, _ -> changed() }
    }

    /** Whether the connection is up. */
    override val connected: Boolean get() = rt.state == ConnectionState.Connected

    /**
     * A `p2pCall` request on the socket the connection holds now: null to [ack] when there is none, or it doesn't answer
     * within 10 seconds.
     */
    override fun send(payload: JsonElement, ack: ((JsonElement?) -> Unit)?) {
        rt.moduleQueue.execute {
            val socket = rt.currentSocket
            if (socket == null) {
                ack?.invoke(null)
                return@execute
            }
            if (ack == null) return@execute socket.emit("p2pCall", payload)
            socket.emit("p2pCall", payload, 10_000, ack)
        }
    }

    /**
     * The `p2pCall` events of one session of one call: the relay sends every session's to the socket of its user, so the
     * call and the session in each say whose it is.
     */
    override fun listen(callId: String, session: String, handler: (JsonElement) -> Unit): () -> Unit {
        val key = "$callId:$session"
        val token = Any()
        synchronized(handlers) { handlers[key] = token to handler }
        return { synchronized(handlers) { if (handlers[key]?.first === token) handlers.remove(key) } }
    }

    /** Each time the connection is up again, after a drop or on another node. */
    override fun onReconnect(listener: () -> Unit): () -> Unit {
        val token = Any()
        synchronized(reconnects) { reconnects[token] = listener }
        return { synchronized(reconnects) { reconnects.remove(token) } }
    }

    private fun relayed(message: JsonElement) {
        val (call, session) = relayKey(message) ?: return
        val handler = synchronized(handlers) { handlers["$call:$session"]?.second }
        handler?.invoke(message)
    }

    private fun changed() {
        synchronized(reconnects) { reconnects.values.toList() }.forEach { it() }
    }
}

/** The call and the session an event of the relay names: a call id of digits is the same whether it came as a number or as text. */
internal fun relayKey(message: JsonElement): Pair<String, String>? {
    val fields = message as? JsonObject ?: return null
    val call = (fields["call_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { if (it.isString) it.content else it.longOrNull?.toString() ?: it.content } ?: return null
    val session = (fields["session"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
    return call to session
}
