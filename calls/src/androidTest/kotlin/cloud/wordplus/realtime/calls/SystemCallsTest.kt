package cloud.wordplus.realtime.calls

import android.telecom.DisconnectCause
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wordplus.realtime.Keyring
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the system's ring is made of that runs with no call (SystemCalls.kt):
 * the push as the realtime server sends it (better-messages-server's sdk-push.ts), the caller's name opened offline with
 * the day's keys kept in the keystore, and why a call ended in Telecom's terms. The Swift package's SystemCallsTests'
 * twin. The ring itself, the notification and Telecom, is the native proof's (aaACallRingsTheAppClosed).
 */
@RunWith(AndroidJUnit4::class)
class SystemCallsTest {
    private fun ring(id: String = "ring-1", until: Long = System.currentTimeMillis() + 40_000, info: Any? = JSONObject().put("sealed", "by-the-app")): JSONObject {
        val wp = JSONObject().put("v", 1).put("op", "incoming").put("id", id).put("call", "support:34:12").put("from", 12)
            .put("type", "video").put("at", System.currentTimeMillis()).put("until", until)
        if (info != null) wp.put("info", info)
        return wp
    }

    @Test
    fun aPushReadsAsItsLineRings() {
        val read = PushRing.of(ring())!!
        assertEquals("ring-1", read.id)
        assertEquals("support:34:12", read.call)
        assertEquals("12", read.from)
        assertTrue(read.video)
        assertTrue(read.until - System.currentTimeMillis() in 38_000..41_000)

        // Text user ids as the app gave them, and an audio call.
        val audio = PushRing.of(ring().put("from", "auth0|5f7c").put("type", "audio"))!!
        assertEquals("auth0|5f7c", audio.from)
        assertFalse(audio.video)

        // A ring into a group call says which.
        assertEquals("call-1", PushRing.of(ring().put("group", "call-1"))!!.group)
    }

    @Test
    fun aPushOfAnythingElseIsNoRing() {
        assertNull(PushRing.of(JSONObject().put("op", "ring_over").put("id", "ring-1")))
        assertNull(PushRing.of(ring().put("id", "")))
        // What the app's FirebaseMessagingService hands over: not a call's, so the app's own.
        assertFalse(SystemCalls.push(ApplicationProvider.getApplicationContext(), mapOf("title" to "A message")))
    }

    @Test
    fun aRingRingsAMinuteAtMost() {
        val long = PushRing.of(ring(until = System.currentTimeMillis() + 600_000))!!
        assertTrue(long.until - System.currentTimeMillis() <= 60_000)
    }

    @Test
    fun theCallersNameOpensWithTheDayKeysKept() {
        val key = "d:20261016:" + "ab".repeat(32)
        val keyring = Keyring().apply { add(listOf(key)) }
        val sealed = keyring.sealJson(JsonObject(mapOf("name" to JsonPrimitive("Ann"))), "d:20261016")!!
        val pushed = PushRing.of(ring(info = sealed))!!

        assertEquals("Ann", SystemCalls.callerName(pushed, listOf(key)))
        // Without the day's key the name waits for the line.
        assertNull(SystemCalls.callerName(pushed, emptyList()))
        assertNull(SystemCalls.callerName(PushRing.of(ring(info = null))!!, listOf(key)))

        // Kept on the phone, encrypted with the keystore's key, the three newest days.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DayKeys.keep(context, listOf("d:20261014:" + "01".repeat(32), "d:20261015:" + "02".repeat(32), key))
        DayKeys.keep(context, listOf("d:20261013:" + "03".repeat(32)))
        val kept = DayKeys.load(context)
        assertEquals(listOf("d:20261014", "d:20261015", "d:20261016"), kept.map { it.substringBeforeLast(':') })
        assertNotNull(kept.firstOrNull { it == key })

        // The keyring hands a module its keys as the app's server gave them.
        assertEquals(listOf(key), keyring.entries("d:"))
        assertEquals(emptyList<String>(), keyring.entries("u:"))
    }

    @Test
    fun whyACallEndedIsSaidAsTelecomSaysIt() {
        assertEquals(11, SystemCalls.causeOf("answered_elsewhere"))
        assertEquals(DisconnectCause.REJECTED, SystemCalls.causeOf("rejected"))
        assertEquals(DisconnectCause.REJECTED, SystemCalls.causeOf("busy"))
        assertEquals(DisconnectCause.MISSED, SystemCalls.causeOf("no_answer"))
        assertEquals(DisconnectCause.ERROR, SystemCalls.causeOf("connection_failed"))
        assertEquals(DisconnectCause.REMOTE, SystemCalls.causeOf("hangup"))
    }

    /** A ring of the app's own form: the app hears it declined, and nothing of it is left after. */
    @Test
    fun anAppRingIsTheAppsToDecline() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        instrumentation.runOnMainSync { SystemCalls.start(context, SystemCalls.Options("Example Shop", android.app.Activity::class.java)) { null } }
        val declined = java.util.concurrent.CountDownLatch(1)
        SystemCalls.onAppDecline { if (it.id == "site#412") declined.countDown() }
        SystemCalls.ringApp(SystemCalls.AppRing("site#412", "Visitor 412", false, System.currentTimeMillis() + 30_000, "wordplus-app:412"))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { SystemCalls.declineFromSystem("site#412") }
        assertTrue(declined.await(5, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertFalse(SystemCalls.busy) }

        // Called off by the app: no decline, nothing left.
        SystemCalls.ringApp(SystemCalls.AppRing("site#413", "Visitor 413", true, System.currentTimeMillis() + 30_000, "wordplus-app:413"))
        instrumentation.waitForIdleSync()
        SystemCalls.endAppRing("site#413", answeredElsewhere = true)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertFalse(SystemCalls.busy) }
    }
}
