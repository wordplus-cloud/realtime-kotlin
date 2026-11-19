package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.livekit.android.e2ee.E2EEState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What goes wrong in a group call that goes on, as the browser's `error` event says it (realtime-client
 * group-call.ts:254, 401). The Swift package's GroupErrorTests' twin. With no server.
 */
@RunWith(AndroidJUnit4::class)
class GroupErrorTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun group() = GroupCall(ApplicationProvider.getApplicationContext(), StandIn().calls, "meeting:7", null, null, microphone = false, camera = false)

    @Test
    fun whatCouldNotGoOutIsTold() {
        val call = group()
        val heard = ArrayList<GroupCallFailure>()
        onMain {
            call.onError { heard.add(it) }
            call.publishFailed("camera", IllegalStateException("taken"))
        }
        mainSettled()
        assertEquals(listOf("publish_failed"), heard.map { it.code })
        assertEquals(listOf<String?>("camera"), heard.map { it.source })
        assertNotNull(heard.first().message)
        assertEquals("the call goes on", GroupCallState.CONNECTING, call.state)
        onMain { call.leave() }
    }

    @Test
    fun aCameraThatCannotGoOutIsTold() = runBlocking {
        // The test's app may not use the camera, and there is no room to send it to.
        val call = group()
        val heard = ArrayList<GroupCallFailure>()
        withContext(Dispatchers.Main) {
            call.onError { heard.add(it) }
            withTimeoutOrNull(5_000) { call.publishCamera(true) }
        }
        mainSettled()
        assertEquals(listOf<String?>("camera"), heard.map { it.source })
        withContext(Dispatchers.Main) { call.leave() }
    }

    @Test
    fun aCameraStoppedThatWasNeverOnSaysNothing() = runBlocking {
        val call = group()
        var heard = 0
        withContext(Dispatchers.Main) {
            call.onError { heard++ }
            call.publishCamera(false)
        }
        mainSettled()
        assertEquals(0, heard)
        withContext(Dispatchers.Main) { call.leave() }
    }

    @Test
    fun onlyAFailureOfEncryptionIsToldOfATrack() {
        assertEquals(
            listOf(E2EEState.ENCRYPTION_FAILED, E2EEState.DECRYPTION_FAILED, E2EEState.INTERNAL_ERROR),
            E2EEState.values().filter { GroupRoom.encryptionFailed(it) },
        )
    }

    @Test
    fun nothingIsToldOnceItHasEndedOrOfAHandlerTakenOff() {
        val call = group()
        var heard = 0
        onMain {
            val listener = call.onError { heard++ }
            call.off(listener)
            call.publishFailed("camera", null)
        }
        mainSettled()
        assertEquals(0, heard)

        onMain {
            call.onError { heard++ }
            call.leave()
            call.publishFailed("camera", null)
        }
        mainSettled()
        assertEquals(0, heard)
    }
}
