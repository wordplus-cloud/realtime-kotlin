package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A ring answered or declined on this device says it is over, as the browser's do since @wordplus/realtime-client
 * a738a57 (calls.ts, Incoming), so a ring the app drew goes when the app answers through the API. The Swift package's
 * IncomingTests' twin. With no server: the route has nothing to answer, and the socket isn't there.
 */
@RunWith(AndroidJUnit4::class)
class IncomingCallTest {
    private val rt = Realtime(RealtimeOptions(app = "app_test", endpoint = "http://127.0.0.1:9/api/realtime"))
    private val calls = RealtimeCalls.of(ApplicationProvider.getApplicationContext(), rt)

    private fun ringing(over: MutableList<String>): IncomingCall {
        val message = JSONObject().put("id", "ring-1").put("call", "support:34:42").put("from", 34).put("type", "video")
        return IncomingCall(calls, message, JsonNull).also { incoming -> incoming.onOver { over.add(it) } }
    }

    @Test
    fun aRingAnsweredHereSaysItIsOverEvenWhenItsCallIsRefused() = runBlocking {
        val over = ArrayList<String>()
        val incoming = ringing(over)
        runCatching { incoming.accept() }
        assertEquals(IncomingCall.State.ANSWERED, incoming.state)
        assertEquals(listOf("answered"), over)
    }

    @Test
    fun aRingDeclinedHereSaysItIsOver() = runBlocking {
        val over = ArrayList<String>()
        val incoming = ringing(over)
        runCatching { incoming.decline() }
        assertEquals(IncomingCall.State.DECLINED, incoming.state)
        assertEquals(listOf("declined"), over)
    }

    @Test
    fun aRingAnsweredBusySaysSo() = runBlocking {
        val over = ArrayList<String>()
        runCatching { ringing(over).decline(busy = true) }
        assertEquals(listOf("busy"), over)
    }

    @Test
    fun aRingSaysItIsOverOnce() = runBlocking {
        val over = ArrayList<String>()
        val incoming = ringing(over)
        incoming.over("cancelled")
        runCatching { incoming.decline() }
        assertEquals(IncomingCall.State.OVER, incoming.state)
        assertEquals(listOf("cancelled"), over)

        val again = ArrayList<String>()
        val declined = ringing(again)
        runCatching { declined.decline() }
        declined.over("cancelled")
        assertEquals(listOf("declined"), again)
    }

    @Test
    fun aRingsHandlerTakenOffHearsNothing() {
        val over = ArrayList<String>()
        val message = JSONObject().put("id", "ring-2").put("call", "support:34:42").put("from", 34).put("type", "video")
        val incoming = IncomingCall(calls, message, JsonNull)
        incoming.off(incoming.onOver { over.add(it) })
        incoming.over("cancelled")
        assertEquals(emptyList<String>(), over)
    }

    @Test
    fun theCallsHandlersComeOffInTheOrderAsked() {
        val errors = ArrayList<String>()
        val taken = calls.onError { errors.add("taken $it") }
        calls.onError { errors.add("kept $it") }
        calls.off(taken)
        calls.fail("forbidden")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(listOf("kept forbidden"), errors)
    }

    @Test
    fun aGroupCallsHandlerTakenOffHearsNothing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ended = ArrayList<String>()
        val kept = ArrayList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val group = GroupCall(context, calls, "meeting:7", null, null, microphone = false, camera = false)
            group.off(group.onEnded { ended.add(it) })
            group.onEnded { kept.add(it) }
            group.finish("left")
        }
        assertEquals(emptyList<String>(), ended)
        assertEquals(listOf("left"), kept)
    }
}
