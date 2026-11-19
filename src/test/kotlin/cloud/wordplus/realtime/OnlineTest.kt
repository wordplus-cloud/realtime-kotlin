package cloud.wordplus.realtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement

/**
 * A watch that fails says so, as the browser's does (realtime-client rooms.ts, Online's `send`), where it was passed over
 * and a screen showed everyone offline with no word why.
 */
class OnlineTest {
    private val callbacks = Executors.newSingleThreadExecutor()
    private val rt = Realtime(RealtimeOptions(app = "s1", endpoint = "http://127.0.0.1:9/api/realtime", callbackExecutor = callbacks))
    private val socket = FakeSocket(rt)

    private fun watched(answer: JsonElement?): Pair<List<String>, Int> {
        socket.answer = { _, _ -> answer }
        val errors = Collections.synchronizedList(ArrayList<String>())
        val changes = Collections.synchronizedList(ArrayList<String>())
        rt.online.on("error") { errors.add(it["code"].text ?: "") }
        rt.online.on("change") { changes.add("change") }
        rt.online.watch(listOf("34"))
        val done = CountDownLatch(1)
        rt.queue.execute { rt.online.again(socket) { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        callbacks.submit {}.get(5, TimeUnit.SECONDS)
        return errors.toList() to changes.size
    }

    @Test
    fun aRefusedWatchSaysWhy() {
        val (errors, changes) = watched(json("""{"error":"no_identity"}"""))
        assertEquals(listOf("no_identity"), errors)
        assertEquals(0, changes)
    }

    @Test
    fun aWatchNotAnsweredSaysSo() {
        assertEquals(listOf("timeout"), watched(null).first)
    }

    @Test
    fun anAnswerWithoutACodeIsTheServers() {
        assertEquals(listOf("server"), watched(json("""{"ok":false}""")).first)
    }

    @Test
    fun aWatchAnsweredTellsTheStatesAndNoError() {
        val (errors, changes) = watched(json("""{"ok":true,"online":[{"id":"34","online":true}]}"""))
        assertEquals(emptyList(), errors)
        assertEquals(1, changes)
        assertTrue(rt.online.has("34"))
    }
}
