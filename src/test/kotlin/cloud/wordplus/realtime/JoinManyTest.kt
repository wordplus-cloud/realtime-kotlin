package cloud.wordplus.realtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rooms joined and left in one moment go in one request, as the browser's do since @wordplus/realtime-client 1.0.6
 * (rooms.ts, joinMany and leaveMany, better-messages-server 4fa3c70, inc/channels/socket.ts joinMany), and as
 * realtime-swift's: each token once, each room naming its own by index and answered as its own join, at most 100 a
 * request, a reconnect's and a move's rejoins too, and one room a request on a server from before it. An inbox joins the
 * rooms of its visible rows so.
 */
class JoinManyTest {
    private val callbacks = Executors.newSingleThreadExecutor()
    private val exp = System.currentTimeMillis() / 1000.0 + 3600

    /** Tokens by the rooms' first part, with no route to ask: `a:` and `c:` rooms one, `b:` rooms another. */
    private val rt = Realtime(RealtimeOptions(
        app = "s1", endpoint = "http://127.0.0.1:9/api/realtime",
        grants = listOf(PublicGrant("t-a", exp, listOf("a:{id}", "c:{id}")), PublicGrant("t-b", exp, listOf("b:{id}"))),
        callbackExecutor = callbacks,
    ))
    private val socket = FakeSocket(rt).also { rt.socketAsked = it }

    // Room for a busy machine: one moment longer than a test's joins take, however slowly they run.
    @BeforeTest
    fun longerMoment() {
        Realtime.togetherAfterMs = 150
    }

    @AfterTest
    fun usualMoment() {
        Realtime.togetherAfterMs = 10
    }

    private fun answered(make: (JsonElement) -> JsonElement?) {
        socket.answer = { event, data -> if (event == "join") make(data) else json("""{"ok":true}""") }
    }

    /** Each room named in a `join { rooms }` answered as joined, and a room alone. */
    private fun allJoined(data: JsonElement): JsonElement {
        val told = data.rooms.associate { (it["room"].text ?: "") to json("""{"ok":true}""") }
        return JsonObject(mapOf("ok" to JsonPrimitive(true), "rooms" to JsonObject(told)))
    }

    private fun settled(waitMs: Long = 400) {
        Thread.sleep(waitMs)
        rt.onQueue {}
        callbacks.submit {}.get(5, TimeUnit.SECONDS)
    }

    private val JsonElement?.rooms get() = (this?.get("rooms") as? JsonArray)?.toList() ?: emptyList()

    @Test
    fun roomsJoinedInOneMomentGoInOneRequestEachTokenOnce() {
        answered(::allJoined)
        val rooms = listOf("a:1", "b:1", "c:1").map { rt.join(it) }
        settled()

        assertEquals(1, socket.asked("join").size)
        val sent = socket.asked("join")[0]
        assertEquals(rt.tab, sent["tab"].text)
        val tokens = (sent["tokens"] as JsonArray).map { it.text }
        assertEquals(setOf("t-a", "t-b"), tokens.toSet())
        assertEquals(2, tokens.size)
        val named = sent.rooms.associate { (it["room"].text ?: "") to (tokens[(it["t"] as JsonPrimitive).intOrNull ?: -1] ?: "") }
        assertEquals(mapOf("a:1" to "t-a", "b:1" to "t-b", "c:1" to "t-a"), named)
        assertNull(sent["room"])
        rooms.forEach { assertEquals(RoomState.Joined, it.state) }
    }

    @Test
    fun aRoomAloneGoesAsItAlwaysDid() {
        answered { json("""{"ok":true}""") }
        val room = rt.join("a:1")
        settled()

        val sent = socket.asked("join")
        assertEquals(1, sent.size)
        assertEquals("a:1", sent[0]["room"].text)
        assertEquals("t-a", sent[0]["token"].text)
        assertNull(sent[0]["rooms"])
        assertEquals(RoomState.Joined, room.state)
    }

    @Test
    fun eachRoomIsAnsweredAsItsOwnJoin() {
        answered {
            json("""{"ok":true,"rooms":{
                "a:1":{"ok":true,"members":{"count":2,"list":[{"id":34,"info":{"name":"Ann"}},{"id":"x","info":null}]},"count":2},
                "a:2":{"ok":true,"missed":[{"event":"updated","data":{"n":1},"id":"5-0","at":5}],"gap":true},
                "b:1":{"error":"forbidden"}
            }}""")
        }
        val one = rt.join("a:1")
        val two = rt.join("a:2")
        val three = rt.join("b:1")
        val four = rt.join("c:1")
        val missed = Collections.synchronizedList(ArrayList<Boolean>())
        val errors = Collections.synchronizedList(ArrayList<String>())
        two.on("updated") { _, meta -> missed.add(meta.replayed) }
        two.on("error") { data, _ -> errors.add(data["code"].text ?: "") }
        settled()

        assertEquals(RoomState.Joined, one.state)
        assertEquals(setOf("34", "x"), one.members.keys)
        assertEquals(2, one.count)
        assertEquals(RoomState.Joined, two.state)
        assertEquals(listOf(true), missed.toList())
        assertEquals(listOf("gap"), errors.toList())
        assertEquals("forbidden", three.error)
        assertEquals(RoomState.Failed, three.state)
        assertEquals("server", four.error)
    }

    @Test
    fun aRoomJoinsFromTheLastEventItHad() {
        answered(::allJoined)
        val one = rt.join("a:1")
        rt.join("a:2")
        rt.onQueue { one.lastId = "9-1" }
        settled()

        val entries = socket.asked("join")[0].rooms
        assertEquals("9-1", entries.first { it["room"].text == "a:1" }["since"].text)
        assertNull(entries.first { it["room"].text == "a:2" }["since"])
    }

    @Test
    fun atMostAHundredARequest() {
        answered(::allJoined)
        // Off line as they are joined, then held at once, as at a reconnect.
        socket.isConnected = false
        val rooms = (1..250).map { rt.join("a:$it") }
        socket.isConnected = true
        val done = CountDownLatch(1)
        rt.queue.execute { rt.joinMany(rooms, socket) { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        settled()

        assertEquals(listOf(50, 100, 100), socket.asked("join").map { it.rooms.size }.sorted())
        rooms.forEach { assertEquals(RoomState.Joined, it.state) }
    }

    @Test
    fun aServerFromBeforeThisJoinsThemOneByOneAndIsNotAskedAgain() {
        // No `room` to read: as a server before better-messages-server 4fa3c70 answers.
        answered { data -> if (data["room"] == null) json("""{"error":"bad_request"}""") else json("""{"ok":true}""") }
        val rooms = listOf("a:1", "b:1").map { rt.join(it) }
        settled()

        assertEquals(1, socket.asked("join").count { it["rooms"] != null })
        assertEquals(setOf("a:1", "b:1"), socket.asked("join").mapNotNull { it["room"].text }.toSet())
        rooms.forEach { assertEquals(RoomState.Joined, it.state) }

        val more = listOf("a:2", "b:2").map { rt.join(it) }
        settled()
        assertEquals(1, socket.asked("join").count { it["rooms"] != null }, "asked for many again")
        more.forEach { assertEquals(RoomState.Joined, it.state) }
    }

    @Test
    fun aWholeRequestRefusedFailsItsRoomsWithItsCode() {
        answered { json("""{"error":"rate_limited"}""") }
        val rooms = listOf("a:1", "b:1").map { rt.join(it) }
        settled()
        rooms.forEach { assertEquals("rate_limited", it.error) }
    }

    @Test
    fun aRoomWhoseTokenRanOutIsAskedAgainAlone() {
        answered { data ->
            if (data["rooms"] == null) json("""{"ok":true}""")
            else json("""{"ok":true,"rooms":{"a:1":{"error":"expired"},"b:1":{"ok":true}}}""")
        }
        val rooms = listOf("a:1", "b:1").map { rt.join(it) }
        settled()

        assertEquals(listOf("a:1"), socket.asked("join").mapNotNull { it["room"].text })
        rooms.forEach { assertEquals(RoomState.Joined, it.state) }
    }

    @Test
    fun aRequestNotAnsweredLeavesItsRoomsJoiningAndFailsTheHold() {
        // A move switches sockets only once all of it is held (Connection.kt), so this keeps the old socket.
        answered { null }
        val rooms = listOf("a:1", "b:1").map { rt.join(it) }
        settled()
        rooms.forEach { assertEquals(RoomState.Joining, it.state) }

        var ok: Boolean? = null
        val done = CountDownLatch(1)
        rt.queue.execute {
            rt.joinMany(rooms, socket) {
                ok = it
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(false, ok)
    }

    @Test
    fun aReconnectsAndAMovesRejoinsGoInOneRequest() {
        // Off line: nothing goes as the rooms are joined.
        socket.isConnected = false
        val rooms = listOf("a:1", "b:1", "c:1").map { rt.join(it) }
        settled()
        assertTrue(socket.asked.isEmpty())

        val next = FakeSocket(rt)
        next.answer = { event, data -> if (event == "join") allJoined(data) else json("""{"ok":true}""") }
        var ok: Boolean? = null
        val done = CountDownLatch(1)
        rt.queue.execute {
            rt.holdAll(next) {
                ok = it
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(true, ok)
        assertEquals(1, next.asked("join").size)
        assertEquals(3, next.asked("join")[0].rooms.size)
        rooms.forEach { assertEquals(RoomState.Joined, it.state) }
    }

    @Test
    fun roomsLeftInOneMomentGoInOneRequest() {
        answered(::allJoined)
        listOf("a:1", "b:1").forEach { rt.join(it) }
        settled()
        listOf("a:1", "b:1").forEach { rt.leave(it) }
        settled()

        val left = socket.asked("leave")
        assertEquals(1, left.size)
        assertEquals(rt.tab, left[0]["tab"].text)
        assertEquals(setOf("a:1", "b:1"), left[0].rooms.mapNotNull { it.text }.toSet())
    }

    @Test
    fun aRoomLeftAloneGoesAsItAlwaysDid() {
        answered { json("""{"ok":true}""") }
        rt.join("a:1")
        settled()
        rt.leave("a:1")
        settled()
        assertEquals(listOf("a:1"), socket.asked("leave").mapNotNull { it["room"].text })
    }

    @Test
    fun aServerFromBeforeThisTakesLeavesOneByOne() {
        socket.answer = { event, data ->
            when {
                event == "leave" && data["rooms"] != null -> json("""{"error":"bad_request"}""")
                event == "join" -> allJoined(data)
                else -> json("""{"ok":true}""")
            }
        }
        listOf("a:1", "b:1").forEach { rt.join(it) }
        settled()
        listOf("a:1", "b:1").forEach { rt.leave(it) }
        settled()
        assertEquals(setOf("a:1", "b:1"), socket.asked("leave").mapNotNull { it["room"].text }.toSet())
    }

    @Test
    fun aRoomLeftBeforeItsJoinWentIsNotJoined() {
        answered(::allJoined)
        val kept = rt.join("a:1")
        rt.join("b:1")
        rt.leave("b:1")
        settled()

        assertEquals(listOf("a:1"), socket.asked("join").mapNotNull { it["room"].text })
        assertEquals(RoomState.Joined, kept.state)
    }

    @Test
    fun aRoomLeftAndJoinedAgainLeavesFirst() {
        answered(::allJoined)
        rt.join("a:1")
        settled()
        rt.leave("a:1")
        val again = rt.join("a:1")
        settled()

        assertEquals(listOf("join", "leave", "join"), socket.asked.map { it.first })
        assertEquals(RoomState.Joined, again.state)
    }
}
