package cloud.wordplus.realtime.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.ConnectionState
import cloud.wordplus.realtime.MemoryNodeStore
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.calls.GroupCallState
import cloud.wordplus.realtime.calls.joinGroupCall
import cloud.wordplus.realtime.ui.calls.CallsAvatars
import cloud.wordplus.realtime.ui.calls.CallsEngine
import cloud.wordplus.realtime.ui.calls.CallsRoomParticipant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This user's own tile in a group call has their name from the first moment, where it drew "?" until the room had connected,
 * as the room knows nobody until then. Against a local realtime server and media server, as calls/LiveCallsTest:
 * `node scripts/token-calls-rig.mjs`, its address as the runner's `rig` argument. Without it this is skipped.
 */
@RunWith(AndroidJUnit4::class)
class GroupOwnTileTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val rig: String? = InstrumentationRegistry.getArguments().getString("rig")
    private val http = OkHttpClient()

    private suspend fun get(path: String, user: String? = null) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$rig/$path").apply { if (user != null) header("X-User", user) }.build()
        http.newCall(request).execute().use { JSONObject(it.body!!.string()) }
    }

    /** Who the screen was last told is on it, this user first. */
    @Suppress("UNCHECKED_CAST")
    private fun roster(): List<CallsRoomParticipant> =
        CallsEngine::class.java.getDeclaredField("roster").apply { isAccessible = true }.get(CallsEngine.get(context)) as List<CallsRoomParticipant>

    @Test
    fun aGroupCallsOwnTileHasItsNameFromTheFirstMoment() = runBlocking {
        assumeTrue("the runner's rig argument: scripts/token-calls-rig.mjs", rig != null)
        val info = get("api/info")
        val rt = Realtime(RealtimeOptions(app = info.getString("app"), endpoint = "$rig/api/realtime", headers = { mapOf("X-User" to "42") }, server = info.getString("server"), nodeStore = MemoryNodeStore()))
        rt.connect()
        while (rt.state != ConnectionState.Connected) delay(100)
        val token = get("api/group-call-token?call=meeting:" + System.currentTimeMillis(), "42").getString("token")

        val (state, tile, group) = withContext(Dispatchers.Main) {
            // Its room is told to connect on this thread, so it can't have when the screen first draws.
            val group = rt.joinGroupCall(context, token)
            CallScreen.show(context, group, "Meeting")
            Triple(group.state, roster().first(), group)
        }
        assertEquals(GroupCallState.CONNECTING, state)
        assertEquals("User 42", tile.name)
        assertEquals("U4", CallsAvatars.initials(tile.name))
        assertEquals("user_42", tile.identity)

        val end = System.currentTimeMillis() + 45_000
        while (group.state != GroupCallState.CONNECTED && System.currentTimeMillis() < end) delay(100)
        assertEquals(GroupCallState.CONNECTED, group.state)
        val connected = withContext(Dispatchers.Main) { roster().first() }
        assertEquals("User 42", connected.name)
        assertEquals("user_42", connected.identity)

        withContext(Dispatchers.Main) { group.leave() }
        rt.close()
    }
}
