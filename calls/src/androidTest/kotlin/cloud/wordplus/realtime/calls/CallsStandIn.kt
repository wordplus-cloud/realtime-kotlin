package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.Identity
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.moduleQueue
import cloud.wordplus.realtime.standInSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.util.concurrent.CopyOnWriteArrayList

/** The JSON of a text, for an answer. */
internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** What the main thread has waiting has run, the handlers it was given included. */
internal fun mainSettled() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

/**
 * A connection with no server: its calls route answers `route`, and its socket answers `answer`. `identity` names its
 * user, and `httpClient` stands in for the cloud's address.
 */
internal class StandIn(
    var route: (JsonObject) -> Pair<Int, JsonElement?> = { 200 to json("""{"token":"t"}""") },
    identity: Identity? = null,
    httpClient: OkHttpClient? = null,
) {
    val rt = Realtime(RealtimeOptions(app = "app_test", endpoint = "http://127.0.0.1:9/api/realtime", identity = identity, httpClient = httpClient))
    val calls = RealtimeCalls.of(ApplicationProvider.getApplicationContext(), rt)

    /** What the socket and the route were asked, in order, and when the route was. */
    val asked = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val bodies = CopyOnWriteArrayList<JsonObject>()
    val bodiesAt = CopyOnWriteArrayList<Long>()

    /** What the socket answers each with: null is no answer. */
    var answer: (String, JsonObject) -> JsonElement? = { event, _ ->
        when (event) {
            "room", "group" -> json("""{"ok":true,"server":"wss://nowhere.invalid","token":"room-token"}""")
            "call" -> json("""{"ok":true,"role":"polite"}""")
            else -> json("""{"ok":true}""")
        }
    }

    init {
        rt.standInSocket { event, payload, ack ->
            asked.add(event to (payload as JsonObject))
            if (ack != null) {
                val reply = answer(event, payload)
                rt.moduleQueue.execute { ack(reply) }
            }
        }
        calls.connectedAsked = true
        calls.routeAsked = { body ->
            val request = body as JsonObject
            // The line goes on holding by itself: the tests look at what the app asks.
            if (!request.containsKey("line")) {
                bodies.add(request)
                bodiesAt.add(System.currentTimeMillis())
            }
            route(request)
        }
    }

    fun asked(event: String): List<JsonObject> = asked.filter { it.first == event }.map { it.second }
}

/** That `body` throws the refusal `code`. */
internal suspend fun assertRefused(code: String, body: suspend () -> Unit) {
    try {
        body()
    } catch (e: CallException) {
        assertEquals(code, e.code)
        return
    }
    fail("no refusal")
}
