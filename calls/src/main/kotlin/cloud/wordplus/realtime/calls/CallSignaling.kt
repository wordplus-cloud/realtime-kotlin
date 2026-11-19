package cloud.wordplus.realtime.calls

import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * How a call's signalling reaches the relay, and the relay's answers and events come back: the one seam between the
 * SDK's call engine and what carries it, as the web's `calls/transport.ts`. The SDK's own calls take the realtime
 * connection's `call` request, [P2PCallTransport] takes Better Messages' `p2pCall`, and an app may carry them its own
 * way, to a relay that answers the same ops: `join`, `signal`, `sync`, `ice_servers`, `alive`, `pulse`, `report`,
 * `trace`, `failed` and `leave`, as the realtime server's does.
 *
 * Hand one to [RealtimeCalls.call]: the call runs on the SDK's engine, and shows on its call screen, as any other. The
 * engine calls the transport on the main thread, and reads [connected] there. The transport may answer on any thread:
 * `ack`, the events and the reconnects are taken to the main thread in the order they are called.
 */
interface CallTransport {
    /**
     * Whether a request sent now would go out. While it is false the engine holds what it would send, a signal up to the
     * relay's 45 seconds of grace, and joins again once [onReconnect] says the transport is back.
     */
    val connected: Boolean

    /**
     * One op to the relay. [payload] is an object holding `op`, `call_id` and `session`, and the op's own fields. [ack],
     * when given, wants the relay's answer (`{ "ok": true, … }`, or `{ "ok": false, "error": … }`), or null when none
     * came: the engine gives up on an answer after 8 seconds and asks again as often as the op deserves, so the transport
     * needs no timer of its own. With no [ack], the op wants no answer.
     */
    fun send(payload: JsonElement, ack: ((JsonElement?) -> Unit)?)

    /**
     * The relay's events for one session of one call, each an object with `op` (`ready`, `signal`, `peer_lost`,
     * `peer_back`, `fallback` or `ended`), `call_id` and `session`. Several calls, and several sessions of one, may share
     * a transport, so it hands each only its own. Answers what stops listening.
     */
    fun listen(callId: String, session: String, handler: (JsonElement) -> Unit): () -> Unit

    /**
     * Called each time the transport is back after a drop, or on another connection, such as after a move to another
     * node: the engine joins the call again there, and the relay replays what it missed. Answers what stops it.
     */
    fun onReconnect(listener: () -> Unit): () -> Unit
}

/** The engine's JSON as the transport's, and back: the engine keeps org.json's, the SDK's API kotlinx.serialization's. */
internal fun JSONObject.toJsonElement(): JsonElement = Json.parseToJsonElement(toString())

internal fun JsonElement.toJSONObject(): JSONObject? = (this as? JsonObject)?.let { runCatching { JSONObject(it.toString()) }.getOrNull() }

/**
 * Whether a connection that still says it is connected answers anything, and how long its answers take, as the web's
 * `calls/signaling.ts` (`SOCKET_STALL`). Stalled: a request has waited [STALL_MS] and nothing at all has been answered
 * meanwhile. The connection only finds out at its ping timeout, up to three quarters of a minute later, and until then
 * whatever this side sends sits in it: a Firefox caller's socket stalled for 47 s on 31 Oct 2026 while its audio kept
 * flowing, and the ICE restart it made meanwhile arrived after the connection had come back on its own. A slow
 * connection that does answer, however late each answer, is not stalled: the clock runs from the last answer, a late
 * one included. A stall, once seen, lasts until something is answered: the requests that found it time out and are
 * tried again, or given up, and that is not the connection coming back.
 */
internal class CallSocketWatch {
    companion object {
        const val STALL_MS = 5_000L
        const val ROUND_TRIPS_KEPT = 5
    }

    private val inflight = HashMap<Int, Long>()
    private var seq = 0
    private var lastAnswerAt = 0L
    private var stalledSince = 0L
    private val roundTrips = ArrayDeque<Long>()

    /** A request on the wire; [settled] takes it off. */
    @Synchronized
    fun sent(at: Long = System.currentTimeMillis()): Int {
        seq++
        inflight[seq] = at
        return seq
    }

    /** Answered, or given up on ([answered] false: timed out). */
    @Synchronized
    fun settled(id: Int, answered: Boolean, at: Long = System.currentTimeMillis()) {
        val sentAt = inflight.remove(id)
        if (!answered) {
            // Timed out with nothing answered since it went: the stall it waited through is one, whether or not
            // anyone asked meanwhile.
            if (sentAt != null && stalledSince == 0L) {
                val since = maxOf(sentAt, lastAnswerAt)
                if (at - since >= STALL_MS) stalledSince = since
            }
            return
        }
        lastAnswerAt = at
        stalledSince = 0L
        if (sentAt != null) {
            roundTrips.addLast(at - sentAt)
            while (roundTrips.size > ROUND_TRIPS_KEPT) roundTrips.removeFirst()
        }
    }

    /** How long nothing has been answered with a request waiting, once that is [STALL_MS] or more; 0 otherwise. */
    @Synchronized
    fun stalledForMs(now: Long = System.currentTimeMillis()): Long {
        if (stalledSince == 0L) {
            val oldest = inflight.values.minOrNull()
            if (oldest != null) {
                val since = maxOf(oldest, lastAnswerAt)
                if (now - since >= STALL_MS) stalledSince = since
            }
        }
        return if (stalledSince != 0L) now - stalledSince else 0L
    }

    /** The longest of the latest round trips: 0 before any. */
    val roundTripMs: Long
        @Synchronized get() = roundTrips.maxOrNull() ?: 0L
}

/**
 * A call's requests to the relay, each tried as often as it deserves, as the web's `calls/signaling.ts`: a signal is
 * the negotiation itself and must arrive, a pulse comes round again in two seconds. Everything on the main thread.
 */
internal class CallSignaling(private val transport: CallTransport, private val callId: String, onMessage: (JSONObject) -> Unit) {
    companion object {
        /** How long an answer is waited for before the request is tried again. */
        private const val ACK_TIMEOUT_MS = 8_000L

        /** How long a signal, or the word that the direct path failed, sits out a socket that is down. */
        private const val OFFLINE_WAIT = 45

        /** How long a signal waits for the one before it to be answered, once that one is on the wire ([signal]). */
        private const val SIGNAL_PIPELINE_MS = 250L

        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun randomId(length: Int): String = String(CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }

    /** 16 characters from [A-Za-z0-9], the shape the web uses; the relay names it back in every message. */
    val session = randomId(16)

    private val main = Handler(Looper.getMainLooper())
    private var stopListening: (() -> Unit)? = null
    var isClosed = false
        private set

    /**
     * Signals go in their order. To a relay whose join says it handles them in order (`ordered`), each goes once the
     * one before is on the wire, which a connected socket keeps in order; to any other, once the one before is
     * answered, as before, since one before 30 Oct 2026 relayed two that came together the other way round. Waiting
     * for each answer, a side that heard the relay late sent one signal a round trip: six seconds behind, a caller's
     * answer to the other side's restart sat behind its own candidates until the restart was given up (the web engine,
     * 30 Oct 2026). A quick answer still goes first, up to [SIGNAL_PIPELINE_MS], and a signal whose answer is lost is
     * sent again by itself, which the relay drops by its nonce. While the socket is down they wait for it in order. The
     * web does the same.
     */
    private val signals = ArrayDeque<(() -> Unit) -> Unit>()
    private var signalling = false
    private var ordered = false

    init {
        // A transport may hand its events over on any thread: they reach the session on the main one, in their order.
        stopListening = transport.listen(callId, session) { message ->
            main.post { if (!isClosed) message.toJSONObject()?.let(onMessage) }
        }
    }

    val connected: Boolean get() = transport.connected

    /** What the connection has answered, for a stall and the round trip ([CallSocketWatch]). */
    private val socketWatch = CallSocketWatch()

    /** How long the connection has sat on a request with nothing at all answered, once that is long enough to count;
     *  0 otherwise, and 0 for a connection that is down, which the session reads first. */
    fun stalledForMs(now: Long = System.currentTimeMillis()): Long =
        if (isClosed || !connected) 0L else socketWatch.stalledForMs(now)

    /** The longest of the latest round trips to the relay and back: 0 before any. */
    val roundTripMs: Long get() = socketWatch.roundTripMs

    fun join(epoch: Int, lastId: String?, resume: Boolean, done: (JSONObject?) -> Unit) {
        val payload = JSONObject().put("op", "join").put("epoch", epoch).put("last_id", lastId ?: JSONObject.NULL)
        if (resume) payload.put("resume", true)
        request(payload, 3, 0) { reply ->
            if (reply?.optBoolean("ordered", false) == true) ordered = true
            done(reply)
        }
    }

    fun signal(generation: Int, body: JSONObject, done: (JSONObject?) -> Unit) {
        val payload = JSONObject().put("op", "signal").put("generation", generation).put("nonce", randomId(16)).put("body", body)
        signals.addLast { next ->
            var released = false
            val release = {
                if (!released) {
                    released = true
                    next()
                }
            }
            val onSent: (() -> Unit)? = if (ordered) ({ main.postDelayed({ release() }, SIGNAL_PIPELINE_MS) }) else null
            request(payload, 5, OFFLINE_WAIT, onSent) { reply ->
                done(reply)
                release()
            }
        }
        pumpSignals()
    }

    private fun pumpSignals() {
        if (signalling || signals.isEmpty()) return
        signalling = true
        val task = signals.removeFirst()
        task {
            signalling = false
            pumpSignals()
        }
    }

    /** Asked for on the way into an ICE restart, which must not wait on it: one attempt. */
    fun iceServers(done: (JSONObject?) -> Unit) = request(JSONObject().put("op", "ice_servers"), 1, 0, done)

    fun alive(done: (JSONObject?) -> Unit) = request(JSONObject().put("op", "alive"), 1, 0, done)

    /** This side's counters, and its quality reading when one is due ([CallQualityWatch]). */
    fun pulse(body: JSONObject, quality: JSONObject? = null, done: (JSONObject?) -> Unit) =
        request(JSONObject().put("op", "pulse").put("pulse", body).apply { quality?.let { put("quality", it) } }, 1, 0, done)

    fun report(report: JSONObject) = request(JSONObject().put("op", "report").put("report", report), 1, 0) {}

    fun trace(lines: List<String>, done: (JSONObject?) -> Unit) = request(JSONObject().put("op", "trace").put("lines", JSONArray(lines)), 3, 0, done)

    fun failed(reason: String, done: (JSONObject?) -> Unit) = request(JSONObject().put("op", "failed").put("reason", reason), 3, OFFLINE_WAIT, done)

    fun leave(reason: String) = request(JSONObject().put("op", "leave").put("reason", reason), 2, 0) {}

    /** A leave sent once with no wait, for a session that has handed off: the relay takes it either way. */
    fun leaveNow(reason: String) {
        transport.send(JSONObject().put("op", "leave").put("reason", reason).put("call_id", callId).put("session", session).toJsonElement(), null)
    }

    fun close() {
        isClosed = true
        stopListening?.invoke()
        stopListening = null
        signals.clear()
    }

    /** One request, tried `attempts` times; with `offlineWait`, the seconds the socket is down count only after that. */
    private fun request(payload: JSONObject, attempts: Int, offlineWait: Int, done: (JSONObject?) -> Unit) =
        request(payload, attempts, offlineWait, null, done)

    /** The same, with [onSent] told once, when it first goes out on a connected socket. */
    private fun request(payload: JSONObject, attempts: Int, offlineWait: Int, onSent: (() -> Unit)?, done: (JSONObject?) -> Unit) {
        payload.put("call_id", callId).put("session", session)
        val leaving = payload.optString("op") == "leave"
        var waited = 0
        var told = false

        fun attempt(n: Int) {
            if (isClosed && !leaving) return done(null)
            if (!connected) {
                when {
                    waited < offlineWait -> {
                        waited++
                        main.postDelayed({ attempt(n) }, 1_000)
                    }
                    n < attempts -> main.postDelayed({ attempt(n + 1) }, 1_000)
                    else -> done(null)
                }
                return
            }
            var settled = false
            val onWire = socketWatch.sent()
            val timer = Runnable {
                if (!settled) {
                    settled = true
                    socketWatch.settled(onWire, false)
                    if (n < attempts) attempt(n + 1) else done(null)
                }
            }
            main.postDelayed(timer, ACK_TIMEOUT_MS)
            transport.send(payload.toJsonElement()) { answer ->
                // On the main thread, whichever the transport answered on.
                main.post {
                    val reply = answer?.toJSONObject()
                    // Late or not: an answer is the connection carrying answers again.
                    socketWatch.settled(onWire, reply != null)
                    if (settled || reply == null) return@post
                    settled = true
                    main.removeCallbacks(timer)
                    done(reply)
                }
            }
            if (!told && onSent != null) {
                told = true
                onSent()
            }
        }

        attempt(1)
    }
}
