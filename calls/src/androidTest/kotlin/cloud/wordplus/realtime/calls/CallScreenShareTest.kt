package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screens in a call between two: the one the other side shares, as the browser's `remote.screen` (realtime-client
 * calls.ts:660, 694). The Swift package's CallScreenShareTests, its twin. With no server: the route and the socket are
 * stand-ins, and the test's app has no microphone to open.
 */
@RunWith(AndroidJUnit4::class)
class CallScreenShareTest {
    @Test
    fun theOtherSidesScreenIsDrawnApartFromTheirCamera() = runBlocking {
        val s = StandIn()
        mainSettled()
        val call = s.calls.start("support:34:42", "34", CallType.AUDIO)
        assertNull(call.remoteScreen)

        val factory = CallMedia.get(ApplicationProvider.getApplicationContext()).factory
        val screen = factory.createVideoTrack("their-screen", factory.createVideoSource(true))
        call.session.onPeerTrack(CallSource.SCREEN_SHARE, screen)
        mainSettled()

        assertEquals("their-screen", call.remoteScreen?.rtcTrack?.id())
        assertNull(call.remoteCamera)

        call.session.onPeerTrack(CallSource.SCREEN_SHARE, null)
        mainSettled()
        assertNull(call.remoteScreen)
        withContext(Dispatchers.Main) { call.end() }
    }
}
