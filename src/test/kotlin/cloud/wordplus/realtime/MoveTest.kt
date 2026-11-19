package cloud.wordplus.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A move between nodes: `node test/native/serve.mjs` runs two realtime nodes and drains
 * the first on POST /drain to its control port (WORDPLUS_CONTROL). The client knows the first from its node cache and
 * has the second as its realtime server: as the local nodes have plain-HTTP names, which the server lists to nobody, a
 * drain moves its clients to their server, as the fleet's do when no other node is ready. The client moves, its rooms
 * joined there before the switch, and nothing the app's server sends meanwhile is lost. It runs after LiveTest (test
 * classes run by name, junit-platform.properties), as the first node is gone afterwards.
 */
class MoveTest {
    @Test
    fun movesToAnotherNodeWhenItsNodeDrainsLosingNothing() = runBlocking<Unit> {
        val base = shopUrl()
        val control = System.getenv("WORDPLUS_CONTROL").orEmpty().trimEnd('/')
        assumeTrue(control.isNotEmpty(), "WORDPLUS_CONTROL is not set: run `node test/native/serve.mjs` in realtime-js")

        val nodes = (Shop.get(control)["nodes"] as JsonArray).mapNotNull { it.text }
        assertEquals(2, nodes.size)
        val (first, second) = nodes

        fun signIn(person: Int): Pair<Realtime, String> {
            val answer = Shop.post("$base/api/token", """{"as":$person}""").second
            val token = answer["token"].text!!
            val store = MemoryNodeStore()
            store.save(second, NodeCache(listOf(CachedNode(first, System.currentTimeMillis() / 1000.0 + 3600))))
            val rt = Realtime(RealtimeOptions.fromConfig(answer["realtime"]!!, base, headers = { mapOf("Authorization" to "Bearer $token") }, nodeStore = store, server = second))
            rt.connect()
            return rt to token
        }

        val (ben, _) = signIn(42)
        val (ann, annToken) = signIn(34)
        try {
            val order = ben.join("order:1042")
            val bens = ben.join("chat:34:42")
            val anns = ann.join("chat:34:42")
            order.ready()
            bens.ready()
            anns.ready()

            val moves = AtomicInteger()
            val moved = CompletableDeferred<Unit>()
            listOf(ben, ann).forEach { rt -> rt.on("moved") { _, _ -> if (moves.incrementAndGet() == 2) moved.complete(Unit) } }

            val heard = Collections.synchronizedList(ArrayList<String>())
            order.on("updated") { data, _ -> heard.add(data["status"].text ?: "") }

            // The app's server keeps sending while the node drains.
            Shop.post("$control/drain", "{}")
            val sent = listOf("packed", "shipped", "delivered", "placed", "packed")
            for (status in sent) {
                Shop.post("$base/orders/1042/status", """{"status":"$status"}""", mapOf("Authorization" to "Bearer $annToken"))
                delay(600)
            }

            moved.within(20)

            // Every update, once each, in order: from the first node, or from the second's history at the join there.
            val deadline = System.currentTimeMillis() + 5_000
            while (heard.size < sent.size && System.currentTimeMillis() < deadline) delay(100)
            assertEquals(sent, heard.toList())

            // On the second node now, a message goes across as before.
            val text = "After the move"
            val got = listen<Unit> { done -> bens.on("message") { data, _ -> if (data["text"].text == text) done(Unit) } }
            anns.emitWithAck("message", JsonObject(mapOf("from" to JsonPrimitive(34), "text" to JsonPrimitive(text))))
            got.within()
            assertEquals(RoomState.Joined, bens.state)
        } finally {
            ben.close()
            ann.close()
        }
    }
}
