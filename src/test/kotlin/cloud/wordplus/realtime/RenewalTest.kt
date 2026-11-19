package cloud.wordplus.realtime

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tokens asked for again before they run out, as the browser's rooms() asks them (realtime-client rooms.ts,
 * scheduleRenewal and scheduleIdentity): the held rooms' in one request to the auth route, ten minutes before the first
 * runs out, and again after each answer, and the identity of the keys route the same. A local route stands in for the
 * app's server, and the renewals are hurried along.
 */
class RenewalTest {
    private val asked = Collections.synchronizedList(ArrayList<List<String>>())
    private lateinit var server: HttpServer
    private lateinit var rt: Realtime

    @BeforeTest
    fun start() {
        Realtime.renewFloorS = 0.2
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/realtime/auth") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString())
            asked.add((body["rooms"] as JsonArray).mapNotNull { it.text }.sorted())
            // Good for ten minutes and a third of a second: due again a third of a second after each answer.
            val answer = """{"token":"t${asked.size}","exp":${System.currentTimeMillis() / 1000.0 + 600.3}}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.start()
        rt = Realtime(RealtimeOptions(app = "s1", endpoint = "http://127.0.0.1:${server.address.port}/api/realtime"))
    }

    @AfterTest
    fun stop() {
        rt.close()
        server.stop(0)
        Realtime.renewFloorS = 30.0
    }

    @Test
    fun theHeldRoomsTokensAreAskedForAgainBeforeTheyRunOutInOneRequest() {
        rt.join("order:1")
        rt.join("chat:34:42")
        rt.queue.execute { listOf("order:1", "chat:34:42").forEach { rt.tokenFor(it) {} } }

        val deadline = System.currentTimeMillis() + 5_000
        while (asked.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(50)

        // The first ask, then the renewals, each asking for both rooms at once.
        assertTrue(asked.size >= 3, "the auth route was asked ${asked.size} times")
        asked.toList().forEach { assertEquals(listOf("chat:34:42", "order:1"), it) }
    }

    @Test
    fun aRoomLeftIsNotAskedForAgain() {
        rt.join("order:1")
        rt.join("chat:34:42")
        rt.queue.execute { listOf("order:1", "chat:34:42").forEach { rt.tokenFor(it) {} } }
        val deadline = System.currentTimeMillis() + 5_000
        while (asked.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        rt.leave("chat:34:42")

        while (asked.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(asked.size >= 3, "the auth route was asked ${asked.size} times")
        assertEquals(listOf("order:1"), asked.last())
    }

    /** The keys route, answering a new identity good for ten minutes and a third of a second each time it is asked. */
    private fun identities(): AtomicInteger {
        val times = AtomicInteger()
        server.createContext("/api/realtime/keys") { exchange ->
            exchange.requestBody.readBytes()
            val answer = """{"identity":{"token":"i${times.incrementAndGet()}","exp":${System.currentTimeMillis() / 1000.0 + 600.3}}}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        return times
    }

    private fun signedIn(exp: Double) = Realtime(RealtimeOptions(app = "s1", endpoint = "http://127.0.0.1:${server.address.port}/api/realtime", identity = Identity("i0", exp)))

    @Test
    fun theIdentityIsAskedForAgainBeforeItRunsOutAndAfterEachAnswer() {
        val times = identities()
        val signedIn = signedIn(System.currentTimeMillis() / 1000.0 + 600.3)
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (times.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(times.get() >= 2, "the keys route was asked ${times.get()} times")
        } finally {
            signedIn.close()
        }
    }

    @Test
    fun anIdentityWithNoEndIsLeftAndAClosedAppAsksNoMore() {
        val times = identities()
        val noEnd = signedIn(0.0)
        Thread.sleep(600)
        noEnd.close()
        assertEquals(0, times.get(), "an identity with no end was asked for")

        val closing = signedIn(System.currentTimeMillis() / 1000.0 + 600.3)
        closing.close()
        Thread.sleep(600)
        assertEquals(0, times.get(), "a closed app asked for its identity")
    }
}
