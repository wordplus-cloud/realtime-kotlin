package cloud.wordplus.realtime.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.calls.Call
import cloud.wordplus.realtime.calls.CallException
import cloud.wordplus.realtime.calls.CallPath
import cloud.wordplus.realtime.calls.CallState
import cloud.wordplus.realtime.calls.CallTransport
import cloud.wordplus.realtime.calls.CallType
import cloud.wordplus.realtime.calls.calls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A relay of the test's own for one call between two people, each side reaching it through a [CallTransport] an app could
 * write: what Better Messages' calls would carry over its socket, carried here in the process. It lives in this build, not
 * the calls library's, so the compiler holds it to the library's public API, as an app's code is held. It answers the
 * ops as the realtime server's relay does for a direct call (inc/calls/p2p/relay.ts): a join with this side's role and
 * who the other is, `ready` to both once both are in, each signal to the other side with an id of its own and those sent
 * before a side joined replayed in its join, and a leave told to the other as `ended`. Every op it heard is kept.
 */
class LoopRelay(val callId: String, caller: String, callee: String) {
    inner class Side(val user: String) : CallTransport {
        @Volatile private var up = true
        @Volatile var session: String? = null
            private set
        private val handlers = ConcurrentHashMap<String, (JsonElement) -> Unit>()
        private val reconnects = CopyOnWriteArrayList<() -> Unit>()
        internal val waiting = ArrayList<JsonObject>()

        /** The ops this side asked, in order. */
        val asked = CopyOnWriteArrayList<JsonObject>()

        override val connected: Boolean get() = up

        // Answered on the relay's own thread, as a socket's answers come: the engine takes them to the main thread.
        override fun send(payload: JsonElement, ack: ((JsonElement?) -> Unit)?) {
            queue.execute { ack?.invoke(handle(payload as JsonObject, this)) }
        }

        override fun listen(callId: String, session: String, handler: (JsonElement) -> Unit): () -> Unit {
            val key = "$callId:$session"
            handlers[key] = handler
            return { handlers.remove(key, handler) }
        }

        override fun onReconnect(listener: () -> Unit): () -> Unit {
            reconnects.add(listener)
            return { reconnects.remove(listener) }
        }

        /** The transport drops, and is back a moment later, as a connection that moved to another node. */
        fun dropAndComeBack() {
            up = false
            queue.schedule({
                up = true
                reconnects.forEach { it() }
            }, 300, TimeUnit.MILLISECONDS)
        }

        internal fun joined(session: String) {
            this.session = session
        }

        internal fun deliver(message: JsonObject) {
            val key = "${(message["call_id"] as JsonPrimitive).content}:${(message["session"] as JsonPrimitive).content}"
            handlers[key]?.invoke(message)
        }
    }

    val caller = Side(caller)
    val callee = Side(callee)
    private val queue = Executors.newSingleThreadScheduledExecutor()
    private var signals = 0
    private var readied = false

    private fun other(side: Side) = if (side === caller) callee else caller

    /** An op, on the relay's thread: its answer, as the realtime server's relay gives it. */
    private fun handle(payload: JsonObject, side: Side): JsonElement {
        side.asked.add(payload)
        val session = (payload["session"] as? JsonPrimitive)?.content
        if ((payload["call_id"] as? JsonPrimitive)?.content != callId || session == null) return buildJsonObject {
            put("ok", false)
            put("error", "no_call")
        }
        val peer = other(side)
        return when ((payload["op"] as? JsonPrimitive)?.content) {
            "join" -> {
                side.joined(session)
                val replay = JsonArray(side.waiting.toList()).also { side.waiting.clear() }
                val present = peer.session != null
                if (present && !readied) {
                    readied = true
                    queue.execute {
                        for (one in listOf(caller, callee)) {
                            one.deliver(buildJsonObject {
                                put("op", "ready")
                                put("call_id", callId)
                                put("session", one.session)
                                put("generation", 1)
                                put("peer", buildJsonObject { put("user_id", other(one).user) })
                            })
                        }
                    }
                }
                buildJsonObject {
                    put("ok", true)
                    put("mode", "p2p")
                    put("ordered", true)
                    put("generation", 1)
                    put("ice_servers", JsonArray(emptyList()))
                    put("role", if (side === caller) "impolite" else "polite")
                    put("peer", buildJsonObject {
                        put("user_id", peer.user)
                        put("present", present)
                    })
                    put("replay", replay)
                }
            }
            "signal" -> {
                signals++
                val fields = mapOf(
                    "op" to JsonPrimitive("signal"),
                    "call_id" to JsonPrimitive(callId),
                    "generation" to (payload["generation"] ?: JsonPrimitive(1)),
                    "body" to (payload["body"] ?: JsonNull),
                    "id" to JsonPrimitive("$signals-0"),
                )
                val theirs = peer.session
                if (theirs != null) peer.deliver(JsonObject(fields + ("session" to JsonPrimitive(theirs)))) else peer.waiting.add(JsonObject(fields))
                buildJsonObject { put("ok", true) }
            }
            "leave" -> {
                peer.session?.let { theirs ->
                    peer.deliver(buildJsonObject {
                        put("op", "ended")
                        put("call_id", callId)
                        put("session", theirs)
                        put("reason", (payload["reason"] as? JsonPrimitive)?.content ?: "hangup")
                    })
                }
                buildJsonObject { put("ok", true) }
            }
            "ice_servers" -> buildJsonObject {
                put("ok", true)
                put("ice_servers", JsonArray(emptyList()))
            }
            // alive, pulse, report, trace, sync, failed: taken.
            else -> buildJsonObject {
                put("ok", true)
                put("mode", "p2p")
            }
        }
    }
}

/**
 * A call between two people carried end to end by a transport of the test's own ([LoopRelay]): both sides join, the relay
 * carries their offer, answer and candidates, the direct connection comes up between them on this device, a drop of one
 * side's transport has it join again, and a hang-up ends the call for the other side. No server; the app holds no
 * microphone permission, so nothing is sent.
 */
@RunWith(AndroidJUnit4::class)
class TransportCallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A connection with no server, for each side. */
    private fun connection() = Realtime(RealtimeOptions(app = "s1500000007", endpoint = "http://127.0.0.1:9/api/realtime", server = "https://nowhere.invalid"))

    /** Until `done` holds on the main thread, where a call's state lives, or the timeout. */
    private suspend fun until(timeoutMs: Long = 20_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!withContext(Dispatchers.Main) { done() } && System.currentTimeMillis() < deadline) delay(50)
    }

    @Test
    fun aCallCarriedByATransportOfTheAppsOwnConnectsAndEnds() = runBlocking {
        val relay = LoopRelay("4711", "34", "42")
        val ann = connection().calls(context)
        val heard = CopyOnWriteArrayList<Call>()
        ann.onCall { heard.add(it) }

        val calling = ann.call(over = relay.caller, id = "4711", role = Call.Role.CALLER, peer = "42", peerInfo = buildJsonObject { put("name", "Ben") }, type = CallType.AUDIO, signalKey = "the call's own key", roomServer = "wss://mesh.invalid")
        assertEquals("a caller rings until the other side joins", CallState.RINGING, withContext(Dispatchers.Main) { calling.state })
        assertEquals("42", calling.peer)
        until { heard.isNotEmpty() }
        assertTrue("onCall hears it, so the call screen does", heard.first() === calling)

        val answering = connection().calls(context).call(over = relay.callee, id = "4711", role = Call.Role.CALLEE, peer = "34", type = CallType.AUDIO, signalKey = "the call's own key")
        until { calling.state == CallState.CONNECTED && answering.state == CallState.CONNECTED }
        assertEquals("the direct connection is up for the caller", CallState.CONNECTED, withContext(Dispatchers.Main) { calling.state })
        assertEquals("and for the callee", CallState.CONNECTED, withContext(Dispatchers.Main) { answering.state })
        until(5_000) { calling.path != null }
        assertEquals(CallPath.DIRECT, withContext(Dispatchers.Main) { calling.path })

        // The relay carried the negotiation, sealed with the call's key, as it never sees it.
        val signals = relay.caller.asked.filter { (it["op"] as? JsonPrimitive)?.content == "signal" }
        assertFalse(signals.isEmpty())
        assertTrue("no signal goes in the clear", signals.all { (it["body"] as? JsonObject)?.get("sealed") == JsonPrimitive(1) })

        // One side's transport drops and comes back: it joins the call again there, and the call goes on.
        val joins = relay.callee.asked.count { (it["op"] as? JsonPrimitive)?.content == "join" }
        relay.callee.dropAndComeBack()
        until { relay.callee.asked.count { (it["op"] as? JsonPrimitive)?.content == "join" } > joins }
        val rejoin = relay.callee.asked.last { (it["op"] as? JsonPrimitive)?.content == "join" }
        assertEquals("joined again as the same session", JsonPrimitive(true), rejoin["resume"])
        until(5_000) { answering.state == CallState.CONNECTED }
        assertEquals(CallState.CONNECTED, withContext(Dispatchers.Main) { answering.state })

        // A hang-up: the caller leaves, and the relay tells the callee.
        val ended = CopyOnWriteArrayList<String>()
        withContext(Dispatchers.Main) {
            answering.onEnded { ended.add(it) }
            calling.end()
        }
        until { ended.isNotEmpty() }
        assertEquals("hangup", withContext(Dispatchers.Main) { calling.endReason })
        assertEquals(listOf("hangup"), ended.toList())
        assertTrue("nothing of the SDK's own transport goes on another", relay.caller.asked.none { it.containsKey("token") || it.containsKey("tab") })
    }

    @Test
    fun aCallOnATransportOfItsOwnAddsNobodyAndARefusedJoinThrowsItsCode() = runBlocking {
        val relay = LoopRelay("4712", "34", "42")
        val calls = connection().calls(context)
        val call = calls.call(over = relay.caller, id = "4712", role = Call.Role.CALLER, peer = "42", type = CallType.AUDIO, signalKey = "k")
        try {
            call.add("43")
            fail("added someone")
        } catch (e: CallException) {
            assertEquals("not_supported", e.code)
        }
        withContext(Dispatchers.Main) { call.end() }

        try {
            calls.call(over = relay.caller, id = "another", role = Call.Role.CALLER, peer = "42", type = CallType.AUDIO, signalKey = "k")
            fail("joined a call the relay doesn't have")
        } catch (e: CallException) {
            assertEquals("no_call", e.code)
        }
    }
}
