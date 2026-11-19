package cloud.wordplus.realtime.calls

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telecom.DisconnectCause
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import cloud.wordplus.realtime.ConnectionState
import cloud.wordplus.realtime.Keyring
import cloud.wordplus.realtime.Realtime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Calls as Android knows them: a ring on a locked phone and in a closed app, Answer and Decline on the notification,
 * calls that go on in the background, Telecom's audio routes, a cellular call holding this one, and a car or a watch
 * answering it.
 *
 *     // Application.onCreate: a push may have started the process with nothing else running
 *     SystemCalls.start(this, SystemCalls.Options(name = "Example Shop", screen = MainActivity::class.java)) {
 *         Session.realtime()   // the signed-in user's connection, or null
 *     }
 *
 *     // The app's FirebaseMessagingService
 *     override fun onNewToken(token: String) = SystemCalls.pushToken(token)
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         if (SystemCalls.push(this, message.data)) return      // a call, or a call called off
 *     }
 *
 * - **A closed app rings:** the phone's FCM token goes with the user's line (`rt.calls(context)`), and a call that rings
 *   them rings it through a high-priority FCM message, which WordPlus Cloud sends with the app's Firebase service
 *   account (the account's Apps page). The ring is a CallStyle notification, with the ring screen over a locked phone
 *   (its full-screen intent) and Telecom told of the call. Then the app's closure gives a connection, the line is held
 *   on it, and the ring goes on as one that came over the line.
 * - **An open app** rings on its own screen (`onIncoming`, or the call screen of realtime-ui), the notification quiet
 *   under it. Answered on either, it is answered once.
 * - **Every call is Telecom's,** placed or answered, a group call too, and runs with a foreground service of the
 *   `phoneCall` type, so it goes on with the app in the background.
 * - **The caller's name** comes from the ring's `info`, sealed by the app's server with the day's key: the day's keys
 *   are kept on the phone, encrypted with a key of Android's keystore, so a push opens it offline. When it cannot, the
 *   app's name shows until the connection opens it.
 *
 * What the app adds: Firebase, and the notification permission asked for on Android 13 and later. From Android 14 the
 * ring over a locked screen is an access the person grants: `canRingFullScreen` and `openFullScreenSettings`.
 */
object SystemCalls {
    /**
     * How the system shows the app's calls. `name` is shown for a caller whose name is not known yet. `screen` is the
     * app's activity a call answered from the notification opens: the app shows the call there (the call screen of
     * realtime-ui comes up by itself). `icon` is the notification's small icon.
     */
    data class Options(
        val name: String,
        val screen: Class<out Activity>,
        val icon: Int = android.R.drawable.sym_call_incoming,
    )

    private const val CHANNEL_CALLS = "wordplus_calls_v1"
    private const val CHANNEL_ONGOING = "wordplus_call_ongoing"
    internal const val NOTIFICATION_INCOMING = 7401
    internal const val NOTIFICATION_ONGOING = 7402
    internal const val ACTION_ANSWER = "cloud.wordplus.realtime.calls.ANSWER"
    internal const val ACTION_RING = "cloud.wordplus.realtime.calls.RING"
    internal const val ACTION_DECLINE = "cloud.wordplus.realtime.calls.DECLINE"
    internal const val ACTION_HANGUP = "cloud.wordplus.realtime.calls.HANGUP"
    internal const val EXTRA_CALL = "cloud.wordplus.realtime.calls.CALL"
    private const val PREFS = "cloud.wordplus.realtime.calls"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var options: Options? = null
    private var connection: (suspend () -> Realtime?)? = null
    private lateinit var context: Context
    private var callsManager: CallsManager? = null
    private val answeredHandlers = ArrayList<(IncomingCall.Answered) -> Unit>()
    private val entries = LinkedHashMap<String, Entry>()
    private var started = 0
    /** Rings of the app's own: what the app does with one answered or declined, and the one whose call it is placing. */
    private var appAnswer: (suspend (AppRing) -> Call?)? = null
    private var appDecline: ((AppRing) -> Unit)? = null
    private var adopting: Entry? = null

    /**
     * A ring of the app's own: one its server sends in a push of its own form, not a call of the user's line (the WordPlus
     * app's visitors calling a site's team, whose site rings the team's phones). The system shows it as any ring, and
     * answering it is the app's: it places the call, which then goes on as this ring.
     */
    class AppRing(
        /** The same for every phone it rings, and for the push that calls it off. */
        val id: String,
        /** What the ring says: the caller. */
        val name: String,
        val video: Boolean,
        /** Past this, in milliseconds, it rings no more. */
        val until: Long,
        /** The address Telecom keeps for it. */
        val address: String,
    )

    /**
     * Rings an app ring, from the app's FirebaseMessagingService: the notification and the ring screen over a locked phone
     * first, within the ten seconds Android gives, and Telecom told.
     */
    fun ringApp(ring: AppRing) {
        main.post { pushedApp(ring) }
    }

    /**
     * An app ring answered from the notification, the ring screen, a car or a watch: the app places its call
     * (`rt.call`), which takes the ring's place for the system, and gives it back, or null when it could not, which ends the
     * ring as failed.
     */
    fun onAppAnswer(handler: suspend (AppRing) -> Call?) {
        main.post { appAnswer = handler }
    }

    /** An app ring declined, before it became a call. */
    fun onAppDecline(handler: (AppRing) -> Unit) {
        main.post { appDecline = handler }
    }

    /** An app ring is over before this phone answered it: another phone took it, or the caller called off. */
    fun endAppRing(id: String, answeredElsewhere: Boolean = false) {
        main.post {
            val entry = entries[id] ?: return@post
            if (entry.app == null || entry.call != null || entry.appAnswering) return@post
            end(entry, if (answeredElsewhere) 11 else DisconnectCause.REMOTE)
        }
    }

    /** What the system knows of each call, by our id (a ring's, which the call answered from it keeps). On the main thread. */
    internal class Entry(val id: String, var name: String, var video: Boolean, val outgoing: Boolean) {
        var incoming: IncomingCall? = null
        var call: Call? = null
        var group: GroupCall? = null
        /** The ring a push brought, before the line brings it, and when it came. */
        var push: PushRing? = null
        var pushedAt = 0L
        var answerOnArrival = false
        var declineOnArrival = false
        var answering: CompletableDeferred<IncomingCall.Answered>? = null
        var answeredInApp = false
        var endedHere = false
        var control: CallControlScope? = null
        var job: Job? = null
        var connectedReported = false
        var heldMicrophone: Boolean? = null
        var heldCamera: Boolean? = null
        var ended = false
        /** The ring screen over the lock screen, told when the ring ends. */
        var ringScreen: (() -> Unit)? = null
        /** A ring of the app's own, and whether the app is answering it. */
        var app: AppRing? = null
        var appAnswering = false
    }

    /** Whether `start` was called: the app's calls go through the system. */
    val isStarted: Boolean get() = options != null

    /**
     * Starts the system's side of the app's calls for its life. Call it in `Application.onCreate`, since a push can start
     * the process with nothing else running. `connection` gives the signed-in user's connection for a call that woke the
     * app, or null when nobody is signed in: the call then rings no further.
     */
    fun start(context: Context, options: Options, connection: suspend () -> Realtime?) {
        if (this.options != null) return
        this.context = context.applicationContext
        this.options = options
        this.connection = connection
        CallLog.attach(this.context)
        createChannels()
        // Telecom is API 26. A device that will not register still takes calls through the notification: it just has
        // no system routes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callsManager = runCatching {
                CallsManager(this.context).apply { registerAppWithTelecom(CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING) }
            }.getOrNull()
        }
        // Whether the app is on screen: a ring then rings on its own screen, the notification quiet under it.
        (this.context as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started = maxOf(0, started - 1) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        CallLog.write("system calls started: telecom=${callsManager != null}")
    }

    /**
     * A call answered from the notification or the ring screen, a car or a watch, once it is on: the call between two,
     * or the group call it rang into. A call answered from the app's own screen comes back from `accept()` alone.
     */
    fun onAnswered(handler: (IncomingCall.Answered) -> Unit) {
        main.post { answeredHandlers.add(handler) }
    }

    /** The app's FCM token, from `FirebaseMessagingService.onNewToken`: the user's calls ring this phone from now on. */
    fun pushToken(token: String) {
        main.post {
            if (!isStarted || token == storedToken()) return@post
            prefs().edit().putString("token", token).apply()
            RealtimeCalls.refreshLines()
        }
    }

    /**
     * A message from FCM, from `FirebaseMessagingService.onMessageReceived`: true when it was a call's, a ring or a ring
     * called off, which this has handled. Any other is the app's.
     */
    fun push(context: Context, data: Map<String, String>): Boolean {
        val wp = data["wp"]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
        return when (wp.optString("op")) {
            "incoming" -> {
                val ring = PushRing.of(wp) ?: return true
                main.post { pushed(ring) }
                true
            }
            "ring_over" -> {
                val id = wp.optString("id")
                val reason = wp.optString("reason", "ended")
                main.post { ringOver(id, reason) }
                true
            }
            else -> false
        }
    }

    /**
     * Whether a ring may come up over a locked screen. From Android 14 that is an access the person grants (Google Play
     * gives it at install to an app whose listing declares calling as a core function): without it a locked phone only
     * rings, with nothing on its screen to answer from.
     */
    fun canRingFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return runCatching { context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true }.getOrDefault(true)
    }

    /** Opens the page where the person allows the ring over a locked screen, or the app's notification settings. */
    fun openFullScreenSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // The line.

    /** What a line brings of this phone, so its user's calls ring it: null until the app gave its FCM token. */
    internal fun linePush(): JsonObject? {
        if (!isStarted) return null
        val token = storedToken() ?: return null
        return JsonObject(mapOf(
            "device" to JsonPrimitive(device()),
            "platform" to JsonPrimitive("android"),
            "token" to JsonPrimitive(token),
            "topic" to JsonPrimitive(context.packageName),
        ))
    }

    /** What `unline` brings at sign-out: this phone rings for nobody any more. */
    internal fun unlinePush(): JsonObject? =
        if (!isStarted || storedToken() == null) null else JsonObject(mapOf("device" to JsonPrimitive(device())))

    /**
     * The line was held, with what still rings: a ring a push brought that is not among them is over, its push having
     * come for a call answered, declined or given up on before the app could hold its line.
     */
    internal fun lineHeld(ringing: Set<String>, askedAt: Long, keyring: Keyring) {
        main.post {
            if (!isStarted) return@post
            DayKeys.keep(context, keyring.entries("d:"))
            for (entry in entries.values.toList()) {
                if (entry.push != null && entry.incoming == null && entry.id !in ringing && entry.pushedAt < askedAt) {
                    CallLog.write("the ring ${entry.id} a push brought no longer rings")
                    end(entry, DisconnectCause.REMOTE)
                }
            }
        }
    }

    /** The install's own id, made once: what the line names this phone by. */
    private fun device(): String {
        prefs().getString("device", null)?.let { return it }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs().edit().putString("device", id).apply()
        return id
    }

    private fun storedToken(): String? = prefs().getString("token", null)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // A ring.

    /**
     * A ring came over a line, on the main thread. With a push's ring waiting for it, it is that call: what the
     * notification asked for meanwhile is done now. A ring during a call is answered busy, as the system takes one call at
     * a time: true then, and the app is not told of it. True too for a ring answered from the notification already.
     */
    internal fun ringArrived(incoming: IncomingCall): Boolean {
        if (!isStarted) return false
        val entry = entries[incoming.id]

        if (busy && entry?.answering == null) {
            CallLog.write("incoming ${incoming.id} refused busy")
            scope.launch { runCatching { incoming.decline(busy = true) } }
            entry?.let { end(it, DisconnectCause.MISSED) }
            return true
        }

        if (entry == null) {
            // A ring over the line with the app off screen: the notification rings it, as a push would.
            if (started == 0) ring(Entry(incoming.id, incoming.info.name() ?: options!!.name, incoming.type == CallType.VIDEO, false).also { it.incoming = incoming })
            return false
        }
        if (entry.incoming != null && entry.incoming !== incoming) return false
        entry.incoming = incoming
        entry.push = null
        incoming.info.name()?.let { if (it != entry.name) rename(entry, it) }
        if (entry.declineOnArrival) {
            scope.launch { runCatching { incoming.decline() } }
            return true
        }
        if (entry.answerOnArrival) {
            answerFromSystem(entry)
            return true
        }
        return false
    }

    /** A ring is over, for the reason the relay gave, on the main thread: the system's ends too, unless it was answered. */
    internal fun ringOver(id: String, reason: String) {
        val entry = entries[id] ?: return
        if (entry.call != null || entry.group != null || entry.answering != null) return
        end(entry, causeOf(reason))
    }

    /** This side declined the ring. */
    internal fun declined(id: String) {
        main.post { entries[id]?.let { end(it, DisconnectCause.REJECTED) } }
    }

    /**
     * Answering a ring from the app: the same answer as the notification's, when the system rings it too, so it is
     * answered once. Null when the system has no ring for it: the app answers it itself, and the system learns of the
     * call once it is answered.
     */
    internal suspend fun answer(incoming: IncomingCall): IncomingCall.Answered? {
        if (!isStarted) return null
        val entry = entries[incoming.id]?.takeIf { !it.ended } ?: return null
        entry.incoming = incoming
        entry.answeredInApp = true
        answerFromSystem(entry)
        return entry.answering?.await()
    }

    /** Answered from the notification, the ring screen, a car or a watch: the ring's answer, and the app told once it is on. */
    internal fun answerFromSystem(id: String) {
        val entry = entries[id] ?: return
        answerFromSystem(entry)
    }

    private fun answerFromSystem(entry: Entry) {
        entry.app?.let { return answerApp(entry, it) }
        val incoming = entry.incoming
        if (incoming == null) {
            entry.answerOnArrival = true
            ensureConnection()
            return
        }
        if (entry.answering != null) return
        val answering = CompletableDeferred<IncomingCall.Answered>()
        entry.answering = answering
        clearIncoming()
        entry.ringScreen?.invoke()
        tellAnswered(entry)
        scope.launch {
            try {
                val answered = incoming.acceptNow()
                answering.complete(answered)
                if (!entry.answeredInApp) answeredHandlers.toList().forEach { it(answered) }
            } catch (e: Exception) {
                answering.completeExceptionally(e)
                end(entry, DisconnectCause.ERROR)
            }
        }
    }

    /** Declined from the notification or the ring screen. */
    internal fun declineFromSystem(id: String) {
        val entry = entries[id] ?: return
        entry.app?.let { app ->
            if (entry.call == null && !entry.appAnswering) appDecline?.invoke(app)
            end(entry, DisconnectCause.REJECTED)
            return
        }
        val incoming = entry.incoming
        if (incoming != null) {
            scope.launch { runCatching { incoming.decline() } }
        } else {
            entry.declineOnArrival = true
            ensureConnection()
        }
        end(entry, DisconnectCause.REJECTED)
    }

    /** Hung up from the ongoing call's notification. */
    internal fun hangUpFromSystem(id: String) {
        val entry = entries[id] ?: return
        entry.endedHere = true
        entry.call?.end()
        entry.group?.leave()
    }

    // Calls placed and answered.

    /** A call the app placed: Telecom's, with the foreground service. On the main thread. */
    internal fun outgoing(call: Call) {
        if (!isStarted || call.state == CallState.ENDED) return
        // The call an app ring became: it goes on as that ring, the system's same call, not a second one.
        adopting?.let { ring ->
            adopting = null
            adopt(ring, call)
            return
        }
        val entry = Entry(call.id, call.peerInfo.name() ?: "", call.type == CallType.VIDEO, true)
        entry.call = call
        remember(entry)
        addTelecom(entry, "wordplus:" + call.peer, incoming = false)
        track(entry, call)
        startService(entry)
    }

    /** A group call the app joined: a conversation rather than a person, with an address of its own. */
    internal fun outgoing(group: GroupCall, type: CallType) {
        if (!isStarted || group.state == GroupCallState.ENDED) return
        val entry = Entry("group-" + System.nanoTime(), "", type == CallType.VIDEO, true)
        entry.group = group
        remember(entry)
        addTelecom(entry, "wordplus-group:" + type.wire + ":" + group.name, incoming = false)
        track(entry, group)
        startService(entry)
    }

    /** A ring answered: the call or group call it became goes on as the system's. On the main thread. */
    internal fun answered(id: String, answered: IncomingCall.Answered, incoming: IncomingCall) {
        if (!isStarted) return
        val entry = entries[id] ?: Entry(id, incoming.info.name() ?: "", incoming.type == CallType.VIDEO, false).also {
            // Answered from the app with no ring of the system's: Telecom is told of it now, as answered.
            it.incoming = incoming
            remember(it)
            addTelecom(it, "wordplus:" + incoming.from, incoming = true)
            tellAnswered(it)
        }
        when (answered) {
            is IncomingCall.Answered.ToCall -> {
                entry.call = answered.call
                track(entry, answered.call)
            }
            is IncomingCall.Answered.ToGroup -> {
                entry.group = answered.group
                track(entry, answered.group)
            }
        }
        startService(entry)
    }

    /** Someone was added: the call goes on as a group call, the same call for the system. */
    internal fun moved(call: Call, group: GroupCall) {
        val entry = entries.values.firstOrNull { it.call === call } ?: return
        entry.call = null
        entry.group = group
        track(entry, group)
    }

    /** This side hangs up or leaves: the system's call ends as this side's own. */
    internal fun endingHere(call: Call? = null, group: GroupCall? = null) {
        entries.values.firstOrNull { (call != null && it.call === call) || (group != null && it.group === group) }?.endedHere = true
    }

    /** Whether a call is on here, so a ring is answered busy. */
    internal val busy: Boolean
        get() = entries.values.any { !it.ended && (it.call != null || it.group != null || it.answering != null || it.appAnswering) }

    // Rings of the app's own.

    /** An app ring: on screen and in Telecom at once, and over at its end. On the main thread. */
    private fun pushedApp(ring: AppRing) {
        if (!isStarted) return
        CallLog.write("push app ring=${ring.id} video=${ring.video} until=${(ring.until - System.currentTimeMillis()) / 1000}s")
        val known = entries[ring.id]
        if (known != null && !known.ended) return
        if (ring.until <= System.currentTimeMillis() || busy) return
        val entry = Entry(ring.id, ring.name.ifEmpty { options!!.name }, ring.video, false)
        entry.app = ring
        entry.pushedAt = System.currentTimeMillis()
        ring(entry)
        main.postDelayed({
            if (entries[entry.id] === entry && entry.call == null && !entry.appAnswering) end(entry, DisconnectCause.MISSED)
        }, maxOf(1000L, ring.until - System.currentTimeMillis()))
    }

    /** An app ring answered from the system: the app places its call, which goes on as this ring. */
    private fun answerApp(entry: Entry, app: AppRing) {
        if (entry.appAnswering || entry.call != null) return
        entry.appAnswering = true
        clearIncoming()
        entry.ringScreen?.invoke()
        tellAnswered(entry)
        adopting = entry
        scope.launch {
            val call = runCatching { appAnswer?.invoke(app) }.getOrNull()
            if (adopting === entry) adopting = null
            if (call == null) {
                entry.appAnswering = false
                end(entry, DisconnectCause.ERROR)
            } else if (entry.call == null && !entry.ended) {
                adopt(entry, call)
            }
        }
    }

    /** The call an app ring became takes its place: the same call for Telecom, with the foreground service. */
    private fun adopt(entry: Entry, call: Call) {
        entry.call = call
        track(entry, call)
        startService(entry)
    }

    private fun track(entry: Entry, call: Call) {
        call.onState { state ->
            if (entry.call !== call) return@onState
            if (state == CallState.CONNECTED && !entry.connectedReported) {
                entry.connectedReported = true
                setActive(entry)
            }
        }
        call.onEnded { reason ->
            if (reason == "group" || entry.call !== call) return@onEnded
            end(entry, if (entry.endedHere) DisconnectCause.LOCAL else causeOf(reason))
        }
    }

    private fun track(entry: Entry, group: GroupCall) {
        group.onState { state ->
            if (entry.group !== group) return@onState
            if (state == GroupCallState.CONNECTED && !entry.connectedReported) {
                entry.connectedReported = true
                setActive(entry)
            }
        }
        group.onEnded { reason ->
            if (entry.group !== group) return@onEnded
            end(entry, if (entry.endedHere) DisconnectCause.LOCAL else causeOf(reason))
        }
    }

    // A push.

    /**
     * A ring from FCM. Ten seconds is all Android gives a high-priority message to put a call on screen, so the
     * notification goes up first, and everything that can wait (the connection, the line) happens after.
     */
    private fun pushed(ring: PushRing) {
        if (!isStarted) return
        CallLog.write("push call=${ring.id} video=${ring.video} until=${(ring.until - System.currentTimeMillis()) / 1000}s")
        val known = entries[ring.id]
        if (known != null && !known.ended) return
        if (ring.until <= System.currentTimeMillis()) return
        if (busy) {
            // A ring during a call: answered busy as it reaches the line, and no ring of the system's at all.
            CallLog.write("push call=${ring.id} refused busy")
            ensureConnection()
            return
        }

        val entry = Entry(ring.id, callerName(ring, DayKeys.load(context)) ?: options!!.name, ring.video, false)
        entry.push = ring
        entry.pushedAt = System.currentTimeMillis()
        RealtimeCalls.ringing(ring.id)?.let { entry.incoming = it }
        ring(entry)

        // Unanswered at the ring's end: the system's ring stops then, as the caller's does.
        main.postDelayed({
            if (entries[entry.id] === entry && entry.call == null && entry.group == null && entry.answering == null) end(entry, DisconnectCause.MISSED)
        }, maxOf(1000L, ring.until - System.currentTimeMillis()))

        if (entry.incoming == null) ensureConnection()
    }

    /** The connection the app gives, and its line held again, which brings what rings. */
    private fun ensureConnection() {
        val connection = connection ?: return
        scope.launch {
            val rt = runCatching { connection() }.getOrNull()
            if (rt == null) {
                CallLog.write("no one signed in: the ring goes no further")
                for (entry in entries.values.toList()) if (entry.push != null && entry.incoming == null) end(entry, DisconnectCause.ERROR)
                return@launch
            }
            val calls = rt.calls(context)
            if (rt.state != ConnectionState.Connected) rt.connect()
            calls.refreshLine()
        }
    }

    /** The caller's name from the ring's info, opened with the day's keys kept from the last line: null when none opens. */
    internal fun callerName(ring: PushRing, keys: List<String>): String? {
        val info = ring.info ?: return null
        val keyring = Keyring().apply { add(keys) }
        return (keyring.openJson(info) ?: info).name()
    }

    // The system's side.

    /** The ring: the notification, the ring screen over a locked phone, and Telecom told. */
    private fun ring(entry: Entry) {
        remember(entry)
        postIncoming(entry)
        addTelecom(entry, entry.app?.address ?: ("wordplus:" + (entry.push?.from ?: entry.incoming?.from ?: "")), incoming = true)
    }

    private fun remember(entry: Entry) {
        entries[entry.id] = entry
    }

    private fun addTelecom(entry: Entry, address: String, incoming: Boolean) {
        val manager = callsManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val attributes = CallAttributesCompat(
            displayName = entry.name.ifEmpty { options!!.name },
            address = Uri.parse(address),
            direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = if (entry.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        entry.job = scope.launch {
            try {
                manager.addCall(
                    attributes,
                    onAnswer = { main.post { answerFromSystem(entry) } },
                    onDisconnect = { main.post { endFromSystem(entry) } },
                    onSetActive = { main.post { held(entry, false) } },
                    onSetInactive = { main.post { held(entry, true) } },
                ) {
                    // Open as long as the call is: the app's own answer and hang-up are told to the system from here.
                    entry.control = this
                    launch { isMuted.collect { muted -> main.post { mutedBySystem(entry, muted) } } }
                }
            } catch (e: Exception) {
                // Telecom refused it, a permission or too many calls: the notification still carries the call.
                CallLog.write("telecom refused call=${entry.id}: ${e.message}")
            }
        }
    }

    /** Telecom hears the call was answered, and makes it active. */
    private fun tellAnswered(entry: Entry) {
        val control = entry.control ?: return
        control.launch {
            runCatching { control.answer(if (entry.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }
        }
    }

    /**
     * Telecom hears the call is up. A call added as outgoing stays in Telecom's dialing state until told, and Telecom
     * disconnects it itself after about two minutes there (seen on a Pixel on Android 16).
     */
    private fun setActive(entry: Entry) {
        val control = entry.control ?: return
        control.launch { runCatching { control.setActive() } }
    }

    /** Ended from the system: Telecom disconnected it. */
    private fun endFromSystem(entry: Entry) {
        if (entry.ended) return
        entry.endedHere = true
        when {
            entry.call != null -> entry.call?.end()
            entry.group != null -> entry.group?.leave()
            entry.incoming?.state == IncomingCall.State.RINGING -> declineFromSystem(entry.id)
            // An app ring declined from a car or a watch, before it became a call.
            entry.app != null && !entry.appAnswering -> declineFromSystem(entry.id)
        }
        end(entry, DisconnectCause.LOCAL)
    }

    /** A cellular call holds this one: the microphone and camera stop, and letting it go gives back what was on. */
    private fun held(entry: Entry, held: Boolean) {
        if (held && entry.heldMicrophone == null) {
            entry.heldMicrophone = entry.call?.microphone ?: entry.group?.microphone ?: false
            entry.heldCamera = entry.call?.camera ?: entry.group?.camera ?: false
            entry.call?.microphone = false
            entry.call?.camera = false
            entry.group?.microphone = false
            entry.group?.camera = false
        } else if (!held) {
            val microphone = entry.heldMicrophone ?: return
            val camera = entry.heldCamera ?: false
            entry.heldMicrophone = null
            entry.heldCamera = null
            entry.call?.microphone = microphone
            entry.call?.camera = camera
            entry.group?.microphone = microphone
            entry.group?.camera = camera
        }
    }

    /** Muted from a headset or the system's call UI: what the app's button does, the track stopped. */
    private fun mutedBySystem(entry: Entry, muted: Boolean) {
        entry.call?.let { if (it.microphone == muted) it.microphone = !muted }
        entry.group?.let { if (it.microphone == muted) it.microphone = !muted }
    }

    /** The call or ring is over: Telecom told why, the notifications gone, and the service with the last call. */
    private fun end(entry: Entry, cause: Int) {
        if (entry.ended) return
        entry.ended = true
        if (entries[entry.id] === entry) entries.remove(entry.id)
        entry.ringScreen?.invoke()
        val control = entry.control
        val job = entry.job
        entry.control = null
        entry.job = null
        if (control != null) {
            scope.launch {
                try {
                    control.disconnect(DisconnectCause(cause))
                } catch (e: Exception) {
                    // Telecom had let it go already.
                } finally {
                    job?.cancel()
                }
            }
        } else {
            job?.cancel()
        }
        clearIncoming()
        if (!busy) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ONGOING)
            SystemCallsService.stop(context)
        }
    }

    private fun rename(entry: Entry, name: String) {
        entry.name = name
        if (entry.incoming?.state == IncomingCall.State.RINGING || entry.push != null) postIncoming(entry)
    }

    /** Why a call or a ring ended, as the relay and the calls say it, in Telecom's terms for the call log. */
    internal fun causeOf(reason: String): Int = when (reason) {
        "answered_elsewhere" -> 11 // DisconnectCause.ANSWERED_ELSEWHERE, API 29
        "rejected", "declined", "declined_elsewhere", "busy" -> DisconnectCause.REJECTED
        "no_answer", "timeout", "unanswered" -> DisconnectCause.MISSED
        "failed", "connection_failed", "room_failed", "room_lost", "group_failed", "not_started", "auth_failed", "forbidden" -> DisconnectCause.ERROR
        else -> DisconnectCause.REMOTE
    }

    // The notifications.

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_CALLS) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_CALLS, context.getString(R.string.wordplus_calls_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                // A ring is a ring, not a notification: the usage picks the ringtone stream and its volume, keeps it
                // ringing rather than chiming once, and is part of what decides how much of it a locked screen shows.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            // A call in progress belongs in the status bar, not in a banner over the screen that started it.
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.wordplus_calls_ongoing_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    private fun postIncoming(entry: Entry) {
        val options = options ?: return
        val caller = Person.Builder().setName(entry.name.ifEmpty { options.name }).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(options.icon)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, receiverIntent(ACTION_DECLINE, entry.id), activityIntent(ACTION_ANSWER, entry.id)))
            // The whole ring on a locked screen, the name included: a call that cannot be seen to be from someone is a
            // call that cannot be decided about.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // On screen, the app rings itself: the ring stays answerable from the shade, without the banner over it.
        if (started > 0) {
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_MAX).setFullScreenIntent(activityIntent(ACTION_RING, entry.id), true)
        }
        notify(NOTIFICATION_INCOMING, builder.build())
    }

    /** The running call's notification, which its foreground service carries, with Hang up. */
    internal fun ongoingNotification(id: String): Notification {
        createChannels()
        val options = options
        val entry = entries[id]
        val caller = Person.Builder().setName(entry?.name?.ifEmpty { null } ?: options?.name ?: "").setImportant(true).build()
        val open = options?.let {
            PendingIntent.getActivity(context, 0, Intent(context, it.screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(options?.icon ?: android.R.drawable.sym_call_outgoing)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, receiverIntent(ACTION_HANGUP, id)))
            .setContentIntent(open)
            .build()
    }

    private fun startService(entry: Entry) {
        SystemCallsService.start(context, entry.id)
    }

    private fun clearIncoming() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_INCOMING)
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // The notification permission was refused: Telecom still has the call, and an app on screen rings it.
            CallLog.write("the ring could not be posted: notifications are not allowed")
        }
    }

    private fun receiverIntent(action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, (action + id).hashCode(),
        Intent(context, SystemCallsReceiver::class.java).setAction(action).putExtra(EXTRA_CALL, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The ring screen: shown over a locked phone (ACTION_RING), or answering and opening the app (ACTION_ANSWER). */
    private fun activityIntent(action: String, id: String): PendingIntent = PendingIntent.getActivity(
        context, (action + id).hashCode(),
        Intent(context, SystemCallsActivity::class.java).setAction(action).putExtra(EXTRA_CALL, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** For the ring screen: the ring by its id, the app's screen, and a way to hear when it is over. */
    internal fun entry(id: String): Entry? = entries[id]

    internal fun appScreen(): Intent? = options?.let { Intent(context, it.screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }

    private fun JsonElement.name(): String? = ((this as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}

/**
 * A ring as its push carries it (better-messages-server's inc/calls/p2p/sdk-push.ts): `wp`, what the line's `incoming`
 * says, the caller's info still sealed by the app's server.
 */
internal class PushRing(val id: String, val call: String, val from: String, val video: Boolean, val until: Long, val info: JsonElement?, val group: String?) {
    companion object {
        fun of(wp: JSONObject): PushRing? {
            if (wp.optString("op") != "incoming") return null
            val id = wp.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val now = System.currentTimeMillis()
            // The relay's own ring limit, held to a minute: a phone ringing for ten minutes is the worse fault.
            val until = minOf(wp.optDouble("until", (now + 45_000).toDouble()).toLong(), now + 60_000)
            val info = wp.opt("info")?.takeIf { it != JSONObject.NULL }?.let { Json.parseToJsonElement(if (it is String) JSONObject.quote(it) else it.toString()) }
            return PushRing(
                id = id,
                call = wp.optString("call"),
                from = wp.opt("from")?.toString() ?: "",
                video = wp.optString("type") != "audio",
                until = until,
                info = info,
                group = wp.optString("group").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * The day's keys, kept on the phone encrypted with a key of Android's keystore that never leaves it: what opens the
 * caller's name in a push before anything connects. The three newest days are kept.
 */
internal object DayKeys {
    private const val ALIAS = "cloud.wordplus.realtime.day-keys"
    private const val PREFS = "cloud.wordplus.realtime.calls.keys"

    fun keep(context: Context, entries: List<String>) {
        if (entries.isEmpty()) return
        val kept = (load(context) + entries).toSortedSet().toList().takeLast(3)
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.doFinal(JSONArray(kept).toString().toByteArray())
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("keys", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .apply()
        }
    }

    fun load(context: Context): List<String> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = Base64.decode(prefs.getString("iv", null) ?: return emptyList(), Base64.NO_WRAP)
        val sealed = Base64.decode(prefs.getString("keys", null) ?: return emptyList(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val array = JSONArray(String(cipher.doFinal(sealed)))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
