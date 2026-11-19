package cloud.wordplus.realtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A connection on a namespace the app names ([RealtimeOptions.namespace]), against realtime nodes played by the test
 * ([FakeNodes]): the namespace and its handshake on every socket, a room joined and its events, a drop and the connection
 * back from the node cache with the room joined again from its last event, a drain's move to the realtime server and its
 * offer of a node, make before break, and the nodes a node lists kept for the next launch. With no namespace named, the
 * connection is `/channels`' as before.
 */
class NamespaceTest {
    private val nodes = FakeNodes()
    private val server = "https://realtime.test"
    private val cached = "https://node-a.test"
    private val store = MemoryNodeStore().apply { save(server, NodeCache(listOf(CachedNode(cached, now() + 3600)))) }
    private val connections = ArrayList<Realtime>()

    private fun now() = System.currentTimeMillis() / 1000.0

    private fun connection(namespace: String = RealtimeOptions.CHANNELS, auth: kotlinx.serialization.json.JsonElement? = null): Realtime {
        val rt = Realtime(
            RealtimeOptions(
                app = "s1500000007",
                endpoint = "https://routes.test/api/realtime",
                server = server,
                grants = listOf(PublicGrant("lobby-grant", now() + 3600, listOf("lobby:{n}"))),
                nodeStore = store,
                httpClient = nodes,
                namespace = namespace,
                auth = auth,
            ),
        )
        connections.add(rt)
        return rt
    }

    @AfterTest
    fun closeAll() {
        connections.forEach { it.close() }
        nodes.stop()
    }

    @Test
    fun aRoomOnANamedNamespaceJoinsHearsReconnectsAndMoves() {
        val rt = connection("/messenger", json("""{"sid":"site-9","uid":7}"""))
        val heard = Collections.synchronizedList(ArrayList<String>())
        val moved = AtomicInteger()
        rt.on("moved") { _, _ -> moved.incrementAndGet() }
        rt.connect()
        val room = rt.join("lobby:1")
        room.on("message") { data, _ -> heard.add(data["text"].text ?: "") }

        // The node the cache knows first, on the namespace named, with its handshake and `rt`, which tells the server it moves.
        val first = assertNotNull(nodes.client("node-a.test", 1), "the cached node is tried first")
        assertEquals("/messenger", first.namespace)
        assertEquals(json("""{"sid":"site-9","uid":7,"rt":1}"""), first.auth)
        eventually { room.state == RoomState.Joined }
        val join = assertNotNull(first.asked("join").firstOrNull())
        assertEquals("lobby:1", join["room"].text)
        assertEquals("lobby-grant", join["token"].text)
        assertEquals(rt.tab, join["tab"].text)
        assertTrue(nodes.routesAsked.none { it.endsWith("/keys") }, "on another namespace nothing is asked before connecting")

        first.emit("e", json("""{"room":"lobby:1","event":"message","data":{"text":"one"},"id":"1-0"}"""))
        eventually { heard == listOf("one") }
        assertEquals(listOf("one"), heard.toList())

        // Dropped: back on the cached node, the same namespace, the room joined again from the last event it had.
        first.drop()
        val again = assertNotNull(nodes.client("node-a.test", 2, 10_000), "it reconnects")
        assertEquals("/messenger", again.namespace)
        assertEquals(json("""{"sid":"site-9","uid":7,"rt":1}"""), again.auth)
        eventually { again.asked("join").isNotEmpty() }
        assertEquals("1-0", again.asked("join").first()["since"].text)
        eventually { room.state == RoomState.Joined }
        again.emit("e", json("""{"room":"lobby:1","event":"message","data":{"text":"two"},"id":"2-0"}"""))
        eventually { heard == listOf("one", "two") }
        assertEquals(listOf("one", "two"), heard.toList())

        // A drain with no node ready: to the realtime server, the room joined there before the switch, as on `/channels`.
        again.emit("migrate", json("""{"target":null,"spread":0,"reason":"drain"}"""))
        val atServer = assertNotNull(nodes.client("realtime.test", 1), "it moves")
        assertEquals("/messenger", atServer.namespace)
        assertEquals(JsonPrimitive(1), (atServer.auth as JsonObject)["rt"])
        eventually { moved.get() == 1 }
        assertEquals(1, moved.get())
        assertEquals("2-0", atServer.asked("join").first()["since"].text, "held on the new socket before it took over")
        atServer.emit("e", json("""{"room":"lobby:1","event":"message","data":{"text":"three"},"id":"3-0"}"""))
        eventually { heard == listOf("one", "two", "three") }
        assertEquals(listOf("one", "two", "three"), heard.toList())
        eventually(6_000) { again.closed }
        assertTrue(again.closed, "the old socket goes once the new one has taken over")

        // On the realtime server itself, its offer of a node: straight there, the room with it.
        atServer.emit("migrate", json("""{"target":"https://node-b.test","spread":0,"reason":"direct","nodes":[{"url":"https://node-b.test","ttl":120}]}"""))
        val direct = assertNotNull(nodes.client("node-b.test", 1), "it takes the offer")
        assertEquals("/messenger", direct.namespace)
        eventually { moved.get() == 2 }
        assertEquals("3-0", direct.asked("join").firstOrNull()?.get("since").text)
        assertEquals(listOf("https://node-b.test"), store.load(server).nodes.map { it.url }, "the node offered is kept for the next launch")

        // The nodes a node lists, kept under the realtime server's address.
        direct.emit("nodes", json("""{"nodes":[{"url":"https://ws-a.de.example.test","ttl":120}]}"""))
        eventually { store.load(server).nodes.map { it.url } == listOf("https://ws-a.de.example.test") }
        assertEquals(listOf("https://ws-a.de.example.test"), store.load(server).nodes.map { it.url })
    }

    @Test
    fun withNoNamespaceNamedItIsTheChannelsAsBefore() {
        val rt = connection()
        assertEquals("/channels", rt.options.namespace)
        rt.connect()
        val socket = assertNotNull(nodes.client("node-a.test", 1))
        assertEquals("/channels", socket.namespace)
        assertEquals(json("""{"site":"s1500000007","rt":3}"""), socket.auth)
        assertTrue(nodes.routesAsked.any { it.endsWith("/keys") }, "with no keys given, the keys route is asked first, as before")
    }

    @Test
    fun aHandshakeKeepsAnRtOfItsOwnAndOneNotAnObjectGoesAsItIs() {
        val options = RealtimeOptions(app = "s1", endpoint = "https://routes.test/api/realtime", namespace = "/", auth = json("""{"sid":"site-9","rt":2}"""))
        assertEquals(json("""{"sid":"site-9","rt":2}"""), options.handshake)
        assertEquals(json("""["site-9",7,"key"]"""), options.copy(auth = json("""["site-9",7,"key"]""")).handshake)
        assertEquals(json("""{"site":"s1","rt":3}"""), options.copy(auth = null).handshake)
    }
}
