package cloud.wordplus.realtime.calls

import android.Manifest
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
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Group calls against the real thing: a local realtime server and media server, an app's server with no calls rule, and a
 * browser on the web SDK as the other side, as `node scripts/token-calls-rig.mjs` keeps them up. Given its address as the
 * runner's `rig` argument, its ports reversed into the emulator (the script says how). Without it these are skipped.
 */
@RunWith(AndroidJUnit4::class)
class LiveCallsTest {
    @get:Rule
    val allowed: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val rig: String? = InstrumentationRegistry.getArguments().getString("rig")
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val opened = ArrayList<Realtime>()

    @Before
    fun theRigIsThere() {
        assumeTrue("the runner's rig argument: scripts/token-calls-rig.mjs", rig != null)
        // What the room client says, down to its SDP: the runner's `verbose` argument.
        if (InstrumentationRegistry.getArguments().getString("verbose") != null) LiveKit.loggingLevel = LoggingLevel.VERBOSE
    }

    @After
    fun closeAll() = opened.forEach { it.close() }

    /** A measure, into the log and into what `am instrument -r` prints, as `live=`. */
    private fun say(line: String) {
        Log.i("LiveCalls", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("live", line) })
    }

    private suspend fun request(path: String, user: String? = null, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$rig/$path")
        if (user != null) builder.header("X-User", user)
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { answer -> JSONObject(answer.body!!.string()) }
    }

    private suspend fun token(path: String, user: String, vararg ask: Pair<String, String>): String =
        request(path + "?" + ask.joinToString("&") { (key, value) -> key + "=" + java.net.URLEncoder.encode(value, "UTF-8") }, user).getString("token")

    /** Runs an async function's body on the browser signed in as [user]: its answer. */
    private suspend fun page(user: String, run: String): JSONObject {
        val answer = request("page", body = JSONObject().put("as", user).put("run", run))
        if (answer.has("error")) throw AssertionError(answer.getString("error"))
        return answer
    }

    private suspend fun connect(user: String): Realtime {
        val info = request("api/info")
        val rt = Realtime(RealtimeOptions(app = info.getString("app"), endpoint = "$rig/api/realtime", headers = { mapOf("X-User" to user) }, server = info.getString("server"), nodeStore = MemoryNodeStore()))
        opened.add(rt)
        rt.connect()
        rt.calls(context)
        until("connected as $user") { rt.state == ConnectionState.Connected && rt.user == user }
        return rt
    }

    private suspend fun until(what: String, seconds: Int = 45, done: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (done()) return
            delay(100)
        }
        throw AssertionError("timed out on: $what")
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun unique(name: String) = name + ":" + System.currentTimeMillis()

    /** What the browser signed in as [host] has had of [user]: bytes of their microphone and camera, null with no track of it. */
    private suspend fun bytes(host: String, user: String): Pair<Long?, Long?> {
        val had = page(host, "return await window.bytesOf($user)").getJSONObject("value")
        fun read(key: String) = if (had.isNull(key)) null else had.getLong(key)
        return read("microphone") to read("camera")
    }

    /**
     * Someone who joined as a listener is let on to speak, put back and let on again: what the host's browser gets of what
     * they send, counted there, after each let-on. Anything that doesn't arrive is named at the end.
     */
    private suspend fun listenerLetOn(microphone: Boolean, camera: Boolean, rounds: Int) {
        val name = unique("class")
        page("42", "window.group = await rt.joinGroupCall(await getToken('/api/group-call-token', { call: '$name', admin: '1' })); return null")
        val ben = connect("34")
        val group = ben.joinGroupCall(context, token("api/group-call-token", "34", "call" to name, "publish" to "0"))
        until("connected") { group.state == GroupCallState.CONNECTED }
        assertFalse("a listener by their token", group.can.publish)
        until("the host's browser sees them") { page("42", "return window.group.people().some((p) => String(p.user) === '34')").getBoolean("value") }

        val failures = ArrayList<String>()
        for (round in 1..rounds) {
            page("42", "await group.letSpeak(34); return null")
            until("let on, round $round") { group.can.publish }
            onMain {
                if (microphone) group.microphone = true
                if (camera) group.camera = true
            }
            val tracks = runCatching {
                until("their tracks at the browser, round $round", 25) {
                    bytes("42", "34").let { (!microphone || it.first != null) && (!camera || it.second != null) }
                }
            }
            // A camera's first frames take a while on an emulator, a busy one most: from when what was let on starts to arrive.
            runCatching {
                until("their first bytes at the browser, round $round", 20) {
                    bytes("42", "34").let { (!microphone || (it.first ?: 0) > 0) && (!camera || (it.second ?: 0) > 0) }
                }
            }
            val before = bytes("42", "34")
            delay(5000)
            val after = bytes("42", "34")
            val sound = (after.first ?: 0) - (before.first ?: 0)
            val picture = (after.second ?: 0) - (before.second ?: 0)
            say("round $round: tracks ${if (tracks.isSuccess) "came" else "never came"}, in 5 s the browser had $sound bytes of microphone and $picture of camera")
            if (microphone && sound <= 0) failures.add("round $round: no microphone")
            if (camera && picture <= 0) failures.add("round $round: no camera")

            page("42", "await group.makeListener(34); return null")
            until("back to listening, round $round") { !group.can.publish && !group.microphone && !group.camera }
            until("nothing of theirs left at the browser, round $round", 25) { bytes("42", "34").let { it.first == null && it.second == null } }
        }
        onMain { group.leave() }
        page("42", "group.leave(); return null")
        assertEquals(emptyList<String>(), failures)
    }

    /** How many times round, three unless the runner's `rounds` argument says: what goes wrong doesn't go wrong every time. */
    private val rounds = InstrumentationRegistry.getArguments().getString("rounds")?.toInt() ?: 3

    @Test
    fun aListenerLetOnSendsTheirMicrophoneAndCameraEveryTime() = runBlocking { listenerLetOn(microphone = true, camera = true, rounds = rounds) }

    @Test
    fun aListenerLetOnSendsTheirMicrophoneEveryTime() = runBlocking { listenerLetOn(microphone = true, camera = false, rounds = rounds) }

    @Test
    fun aListenerLetOnSendsTheirCameraEveryTime() = runBlocking { listenerLetOn(microphone = false, camera = true, rounds = rounds) }

    /**
     * A host's list of people says what someone else may do as it changes, with nothing sent by them: how long it took here
     * after letSpeak and makeListener came back.
     */
    @Test
    fun aRemotePersonsPublishFollowsTheHost() = runBlocking {
        val name = unique("class")
        val ann = connect("42")
        val group = ann.joinGroupCall(context, token("api/group-call-token", "42", "call" to name, "admin" to "1"))
        until("connected") { group.state == GroupCallState.CONNECTED }
        page("34", "window.group = await rt.joinGroupCall(await getToken('/api/group-call-token', { call: '$name', publish: '0' })); return null")
        until("the listener in the room") { group.people.any { it.user == "34" } }
        assertFalse("a listener by their token", group.people.first { it.user == "34" }.publish)

        var told = 0
        group.onPeople { told++ }
        val lags = ArrayList<Long>()
        for (round in 1..5) {
            told = 0
            val allowed = System.currentTimeMillis()
            group.letSpeak("34")
            until("publish true, round $round", 15) { group.people.first { it.user == "34" }.publish }
            val on = System.currentTimeMillis() - allowed
            val toldOn = told
            delay(1000)

            told = 0
            val barred = System.currentTimeMillis()
            group.makeListener("34")
            until("publish false, round $round", 15) { !group.people.first { it.user == "34" }.publish }
            val off = System.currentTimeMillis() - barred
            say("round $round: publish true $on ms after asking, onPeople $toldOn times, publish false $off ms after asking, onPeople $told times")
            lags.add(on)
            lags.add(off)
            delay(1000)
        }
        onMain { group.leave() }
        page("34", "group.leave(); return null")
        assertTrue("every change within 3 s: $lags", lags.all { it < 3000 })
    }

    /** This app may do nothing in a call that is over, whatever it could do while in it. */
    @Test
    fun whatAppMayDoOnceACallIsOver() = runBlocking {
        val ann = connect("42")
        val group = ann.joinGroupCall(context, token("api/group-call-token", "42", "call" to unique("meeting"), "admin" to "1"))
        until("connected") { group.state == GroupCallState.CONNECTED }
        assertTrue(group.can.publish)
        assertTrue(group.can.subscribe)
        assertEquals("User 42", group.me?.info?.jsonObject?.get("name")?.jsonPrimitive?.content)

        onMain { group.leave() }
        assertFalse("just left", group.can.publish)
        assertFalse("just left", group.can.subscribe)
        delay(2000)
        assertFalse("and later", group.can.publish)
        assertFalse("and later", group.can.subscribe)
    }
}
