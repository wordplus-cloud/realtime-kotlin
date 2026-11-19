package cloud.wordplus.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client against a real realtime server and a real app's server: @wordplus/realtime's Express example, signed in
 * as the shop's people, as `node test/native/serve.mjs` in realtime-js keeps them up. Set WORDPLUS_SHOP to its address
 * (http://localhost:17533); without it these are skipped.
 */
class LiveTest {
    class Session(val rt: Realtime, val token: String, val shop: JsonElement)

    private val opened = ArrayList<Realtime>()

    @AfterTest
    fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    /** Signs in as one of the shop's people (POST /api/token), and connects with the settings its server sent. */
    private fun signIn(person: Int): Session {
        val base = shopUrl()
        val answer = Shop.post("$base/api/token", """{"as":$person}""").second
        val token = answer["token"].text!!
        val options = RealtimeOptions.fromConfig(answer["realtime"]!!, base, headers = { mapOf("Authorization" to "Bearer $token") }, nodeStore = MemoryNodeStore())
        val rt = Realtime(options)
        opened.add(rt)
        rt.connect()
        return Session(rt, token, answer["shop"]!!)
    }

    private fun post(path: String, body: String, session: Session): Int =
        Shop.post(shopUrl() + "/" + path, body, mapOf("Authorization" to "Bearer ${session.token}", "X-Realtime-Tab" to session.rt.tab)).first

    @Test
    fun connectsWithTheKeysAndIdentityItsServerGaveAndJoinsItsOrder() = runBlocking<Unit> {
        val ben = signIn(42)
        assertEquals("42", ben.rt.user)
        val order = ben.rt.join(ben.shop["rooms"]["order"].text!!)
        order.ready()
        assertEquals(RoomState.Joined, order.state)

        // A room the app's server refuses Ben.
        val other = ben.rt.join("order:999")
        assertEquals("forbidden", assertFailsWith<RealtimeException> { other.ready() }.code)
    }

    @Test
    fun hearsTheServersEmitToItsRoomAndToItsUserOpened() = runBlocking<Unit> {
        val ben = signIn(42)
        val ann = signIn(34)
        val order = ben.rt.join(ben.shop["rooms"]["order"].text!!)
        order.ready()

        val updated = listen<Pair<JsonElement, EventMeta>> { done -> order.on("updated") { data, meta -> done(data to meta) } }
        val notice = listen<JsonElement> { done -> ben.rt.on("notice") { data, _ -> done(data) } }
        assertEquals(200, post("orders/1042/status", """{"status":"packed"}""", ann))

        val (data, meta) = updated.within()
        assertEquals("packed", data["status"].text)
        assertNotNull(meta.id)
        assertEquals("Your order #1042 is packed.", notice.within()["text"].text)
    }

    @Test
    fun chatsWithAcksTypingVolatileHistoryAndBinarySealedInTheRoom() = runBlocking<Unit> {
        val ben = signIn(42)
        val ann = signIn(34)
        val chatName = ben.shop["rooms"]["chat"].text!!
        val bens = ben.rt.join(chatName)
        val anns = ann.rt.join(chatName)
        bens.ready()
        anns.ready()

        val typing = listen<Pair<JsonElement, EventMeta>> { done -> bens.on("typing") { data, meta -> done(data to meta) } }
        anns.volatile.emit("typing", JsonObject(mapOf("on" to JsonPrimitive(true))))
        val (typed, typedMeta) = typing.within()
        assertEquals(true, (typed["on"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("34", typedMeta.from)

        val text = "From Kotlin " + UUID.randomUUID().toString().take(6)
        val heard = listen<Pair<JsonElement, EventMeta>> { done -> bens.on("message") { data, meta -> if (data["text"].text == text) done(data to meta) } }
        val photo = byteArrayOf(1, 2, 250.toByte())
        val ack = anns.emitWithAck("message", JsonObject(mapOf("from" to JsonPrimitive(34), "text" to JsonPrimitive(text), "photo" to photo.toJson())))
        assertTrue(ack.id.contains("-"))
        val (message, meta) = heard.within()
        assertEquals("34", meta.from)
        assertEquals(ack.id, meta.id)
        assertContentEquals(photo, message["photo"]!!.bytes())

        // Kept in the room's history, which Ben pages back.
        val page = bens.history(limit = 20)
        assertTrue(page.events.any { it.data["text"].text == text && it.meta.id == ack.id })

        // A name the room's rule doesn't allow.
        assertEquals("not_allowed", assertFailsWith<RealtimeException> { anns.emitWithAck("delete", JsonObject(mapOf("x" to JsonPrimitive(1)))) }.code)
    }

    @Test
    fun joinsSeveralRoomsInOneRequestLeavesThemAndJoinsThemAgain() = runBlocking<Unit> {
        val ben = signIn(42)
        val names = listOf(ben.shop["rooms"]["order"].text!!, ben.shop["rooms"]["chat"].text!!, "shop")
        withTimeout(15_000) { while (ben.rt.state != ConnectionState.Connected) delay(50) }

        // In one moment, on the connection as it is up: a real server takes them in one request, each answered.
        val rooms = names.map { ben.rt.join(it) }
        rooms.forEach { it.ready() }
        rooms.forEach { assertEquals(RoomState.Joined, it.state, it.name) }
        assertTrue(ben.rt.many, "the server answered bad_request to several rooms")

        // Left in one moment, and joined again in one.
        names.forEach { ben.rt.leave(it) }
        val again = names.map { ben.rt.join(it) }
        again.forEach { it.ready() }
        again.forEach { assertEquals(RoomState.Joined, it.state, it.name) }
        assertTrue(ben.rt.many, "the server answered bad_request to several rooms")

        // What goes to one of them comes, so it is in that room for real.
        val order = ben.rt.room(ben.shop["rooms"]["order"].text!!)!!
        val heard = listen<JsonElement> { hear -> order.on("updated") { data, _ -> hear(data) } }
        assertEquals(200, post("orders/1042/status", """{"status":"shipped"}""", signIn(34)))
        assertEquals("shipped", heard.within()["status"].text)
    }

    @Test
    fun listsWhoIsInTheShopAndWhoIsOnline() = runBlocking<Unit> {
        val ben = signIn(42)
        val shop = ben.rt.join("shop")
        shop.ready()
        assertEquals("Ben", shop.members["42"]["name"].text)

        val changes = ben.rt.online.watch(listOf("34"))
        val arrived = listen<JsonElement> { done -> shop.on("join") { data, _ -> if (data["id"].text == "34") done(data) } }
        val online = listen<JsonElement> { done -> changes.on("change") { if (it["id"].text == "34" && (it["online"] as? JsonPrimitive)?.booleanOrNull == true) done(it) } }

        val ann = signIn(34)
        ann.rt.join("shop").ready()
        assertEquals("Support", arrived.within(10)["info"]["role"].text)
        online.within(10)
        assertTrue(ben.rt.online.has("34"))

        val left = listen<JsonElement> { done -> shop.on("leave") { data, _ -> if (data["id"].text == "34") done(data) } }
        val offline = listen<JsonElement> { done -> changes.on("change") { if (it["id"].text == "34" && (it["online"] as? JsonPrimitive)?.booleanOrNull == false) done(it) } }
        ann.rt.close()
        left.within(10)
        assertNotNull((offline.within(10)["at"] as? JsonPrimitive)?.doubleOrNull)
        assertNotNull(ben.rt.online.lastSeen("34"))
    }

    @Test
    fun readsTheDirectoryOfUsersWhoConnected() = runBlocking<Unit> {
        val ann = signIn(34)
        ann.rt.join("shop").ready()
        val ben = signIn(42)
        ben.rt.join("shop").ready()

        // Ann's entry, sealed with the day's key, opened; nobody who never connected keeps one.
        val users = ben.rt.users(listOf("34", "9999"))
        assertEquals("Ann", users["34"]["name"].text)
        assertEquals("/avatars/ann.svg", users["34"]["avatar"].text)
        assertNull(users["9999"])
    }

    /** A request to the control port of serve.mjs (WORDPLUS_CONTROL), which calls the realtime API as the shop. */
    private fun control(path: String, body: String): JsonElement {
        val base = System.getenv("WORDPLUS_CONTROL").orEmpty()
        assumeTrue(base.isNotEmpty(), "WORDPLUS_CONTROL is not set: run `node test/native/serve.mjs` in realtime-js")
        return Shop.post(base.trimEnd('/') + "/" + path, body).second
    }

    @Test
    fun answersWhatTheServerOfTheAppAsksSealedWithTheKeyOfTheUser() = runBlocking<Unit> {
        val ben = signIn(42)
        ben.rt.join("shop").ready()
        ben.rt.answer("where") { data, _ -> JsonObject(mapOf("app" to JsonPrimitive("kotlin"), "n" to (data["n"] ?: JsonNull))) }

        val answer = control("ask", """{"user":"42","event":"where","data":{"n":5},"timeout":5000}""")
        val reply = (answer["replies"] as JsonArray).first()
        assertEquals("kotlin", reply["data"]["app"].text)
        assertEquals(5, (reply["data"]["n"] as? JsonPrimitive)?.intOrNull)

        // An event this app does not answer gets no reply.
        assertEquals(0, (control("ask", """{"user":"42","event":"unknown","timeout":1000}""")["replies"] as JsonArray).size)
    }

    @Test
    fun isSignedOutByTheServerOfTheAppAndAsksItsRoutesAgain() = runBlocking<Unit> {
        val ben = signIn(42)
        val shop = ben.rt.join("shop")
        shop.ready()

        val signedOut = listen<JsonElement> { done -> ben.rt.on("signed_out") { data, _ -> done(data) } }
        control("disconnect", """{"user":"42"}""")
        signedOut.within(10)

        // The room and the identity come back on tokens the shop signs after, as its rules still let Ben in.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && (shop.state != RoomState.Joined || ben.rt.user != "42")) delay(200)
        assertEquals(RoomState.Joined, shop.state)
        assertEquals("42", ben.rt.user)
    }
}

/** Where the API demo runs, or the test is skipped. */
internal fun shopUrl(): String {
    val value = System.getenv("WORDPLUS_SHOP").orEmpty()
    assumeTrue(value.isNotEmpty(), "WORDPLUS_SHOP is not set: run `node test/native/serve.mjs` in realtime-js")
    return value.trimEnd('/')
}

/** The shop's routes, as its pages call them. */
internal object Shop {
    private val http = HttpClient.newHttpClient()

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, JsonElement> {
        val request = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to (runCatching { Json.parseToJsonElement(response.body()) }.getOrNull() ?: JsonNull)
    }

    fun get(url: String): JsonElement {
        val response = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        return Json.parseToJsonElement(response.body())
    }
}

/** The first value an event brings, listened for before what makes it happens. */
internal fun <T> listen(register: ((T) -> Unit) -> Unit): CompletableDeferred<T> = CompletableDeferred<T>().also { got -> register { got.complete(it) } }

internal suspend fun <T> CompletableDeferred<T>.within(seconds: Long = 8): T = withTimeout(seconds * 1000) { await() }
