package cloud.wordplus.realtime.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cloud.wordplus.realtime.ConnectionState
import cloud.wordplus.realtime.MemoryNodeStore
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.calls.Call
import cloud.wordplus.realtime.calls.CallState
import cloud.wordplus.realtime.calls.startCall
import kotlinx.coroutines.Dispatchers
import cloud.wordplus.realtime.ui.calls.CallsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/** An app's own screen, in the app's own task as an app's is by default. */
class OwnTaskHost : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Host")
    }
}

/** The same with `taskAffinity=""` in the manifest, as Flutter's template has its only activity, so the call screen starts a task of its own. */
class EmptyAffinityHost : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Host")
    }
}

/**
 * The ready-made call screen closing as it opens: a call declined by the other side within a moment of the screen showing,
 * at a range of delays, and the app in front afterwards rather than the launcher. Against a local realtime server and
 * media server with a browser as the other side, as calls/LiveCallsTest: `node scripts/token-calls-rig.mjs`, its address as
 * the runner's `rig` argument. Without it this is skipped.
 */
@RunWith(AndroidJUnit4::class)
class CallScreenCloseTest {
    @get:Rule
    val allowed: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val rig: String? = arguments.getString("rig")
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()

    private suspend fun request(path: String, user: String? = null, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$rig/$path")
        if (user != null) builder.header("X-User", user)
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { JSONObject(it.body!!.string()) }
    }

    private suspend fun page(user: String, run: String): JSONObject {
        val answer = request("page", body = JSONObject().put("as", user).put("run", run))
        if (answer.has("error")) throw AssertionError(answer.getString("error"))
        return answer
    }

    private fun shell(command: String): String {
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(out.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }.also { out.close() }
    }

    /** Which app the system has in front: the package of its resumed activity. */
    private fun front(): String {
        val found = Regex("(?:topResumedActivity|ResumedActivity)[:=] *ActivityRecord\\{\\S+ \\S+ ([^/ ]+)/([^\\s}]+)").find(shell("dumpsys activity activities"))
        return found?.let { it.groupValues[1] + "/" + it.groupValues[2] } ?: "nothing"
    }

    private suspend fun closingAsItOpens(host: Class<out Activity>, delays: List<Long>): List<Pair<Long, String>> {
        val info = request("api/info")
        val rt = Realtime(RealtimeOptions(app = info.getString("app"), endpoint = "$rig/api/realtime", headers = { mapOf("X-User" to "42") }, server = info.getString("server"), nodeStore = MemoryNodeStore()))
        rt.connect()
        while (rt.state != ConnectionState.Connected) delay(100)
        // Answered by a browser, which is reached first so that a slow page isn't part of the delay.
        page("34", "window.rings = []; window.incoming = null; return null")

        val fronts = ArrayList<Pair<Long, String>>()
        for (wait in delays) {
            val screen = instrumentation.startActivitySync(Intent(context, host).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            val before = front()
            val call: Call = withContext(Dispatchers.Main) {
                rt.startCall(context, request("api/call-token?to=34&type=video", "42").getString("token")).also { CallScreen.show(context, it, name = "Ben") }
            }
            // Whether the call screen came up at all before the call was over, and when it went.
            val opened = System.currentTimeMillis()
            var shownFor = 0L
            val watching = CoroutineScope(Dispatchers.Default).launch {
                while (true) {
                    if (CallsActivity.showing) shownFor = System.currentTimeMillis() - opened
                    delay(20)
                }
            }
            // A wait below 0 ends the call here at once, before its screen can be shown, as a decline that arrives first does.
            if (wait < 0) withContext(Dispatchers.Main) { call.end() }
            else page("34", "await until(() => window.incoming); await new Promise((done) => setTimeout(done, $wait)); await window.incoming.decline(); window.incoming = null; return null")
            val declinedAt = System.currentTimeMillis() - opened
            val end = System.currentTimeMillis() + 20_000
            while (call.state != CallState.ENDED && System.currentTimeMillis() < end) delay(50)
            // Whatever is going to happen on the way out has happened by then, on a slow machine too.
            delay(3000)
            watching.cancel()
            val after = front()
            Log.i("CallClose", "${host.simpleName} declined $wait ms after the ring arrived, ${declinedAt} ms after the screen was asked for, which was up until $shownFor ms: before $before, after $after")
            fronts.add(wait to after)
            screen.finish()
            instrumentation.waitForIdleSync()
            delay(500)
        }
        rt.close()
        return fronts
    }

    /** The delays, from the runner's `delays` argument: at once most often, as that is when it went wrong. */
    private val delays get() = (arguments.getString("delays") ?: "0,0,0,0,0,0,0,0,0,0,50,100,250,500,1000").split(",").map { it.toLong() }

    private fun assertInFront(host: Class<out Activity>, fronts: List<Pair<Long, String>>) {
        val wanted = context.packageName + "/" + host.name.removePrefix(context.packageName)
        assertEquals(fronts.map { it.first to wanted }, fronts)
    }

    @Test
    fun theAppIsInFrontOfACallDeclinedAsItsScreenOpens() = runBlocking {
        assumeTrue("the runner's rig argument: scripts/token-calls-rig.mjs", rig != null)
        val host = OwnTaskHost::class.java
        assertInFront(host, closingAsItOpens(host, delays))
    }

    @Test
    fun theAppIsInFrontWithAnEmptyAffinityToo() = runBlocking {
        assumeTrue("the runner's rig argument: scripts/token-calls-rig.mjs", rig != null)
        val host = EmptyAffinityHost::class.java
        assertInFront(host, closingAsItOpens(host, delays))
    }

    /** A call over before its screen was ever shown, which is when the launcher came in front: ended here, so it always is. */
    @Test
    fun theAppIsInFrontOfACallOverBeforeItsScreenShows() = runBlocking {
        assumeTrue("the runner's rig argument: scripts/token-calls-rig.mjs", rig != null)
        val fronts = listOf(EmptyAffinityHost::class.java, OwnTaskHost::class.java).associateWith { host -> closingAsItOpens(host, List(8) { -1L }) }
        fronts.forEach { (host, seen) -> assertInFront(host, seen) }
    }
}
