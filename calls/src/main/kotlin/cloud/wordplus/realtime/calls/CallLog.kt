package cloud.wordplus.realtime.calls

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * One line of a call's account: to the call's trace on the relay while a call is up (`sink`, set by the session), and
 * to logcat in a debuggable app. Never a key, a token, SDP, an address or a name.
 */
internal object CallLog {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    @Volatile
    private var debuggable = false
    private var minute = 0L
    private var lines = 0

    fun attach(context: Context) {
        debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** Where the lines of the call that is up go, or null once it ends. */
    fun setSink(handler: ((String) -> Unit)?) {
        sink = handler
    }

    fun write(line: String) {
        if (debuggable) Log.d("WordPlusCalls", line)
        sink?.invoke(line)
    }

    /** A line of the session's own, which goes to the relay by itself: to logcat only. */
    fun debug(line: String) {
        if (debuggable) Log.d("WordPlusCalls", line)
    }

    /** libwebrtc's own warnings: at most twenty a minute, since a stack gone wrong writes one per frame. */
    fun webrtc(message: String) {
        val keep = synchronized(this) {
            val now = System.currentTimeMillis() / 60_000
            if (now != minute) {
                minute = now
                lines = 0
            }
            ++lines <= 20
        }
        if (keep) write("webrtc: " + message.trim())
    }
}
