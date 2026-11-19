package cloud.wordplus.realtime.calls

import android.content.Context
import cloud.wordplus.realtime.Realtime
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The short ways in, in the words the browser has them: `rt.call()`, `rt.groupCall()`, `rt.stream()`, and a stream's
 * `remove()` beside `kick()`. They only hand on, what they hand on to is tested on its own, and a real Context is the
 * emulator's: this says they are there, in these shapes, for an app to write.
 */
class ShortWaysTest {
    @Test
    fun theShortWaysInAreThereInTheShapesAnAppWrites() {
        val call: suspend Realtime.(Context, String, String, CallType) -> Call = Realtime::call
        val group: suspend Realtime.(Context, String, CallType) -> GroupCall = Realtime::groupCall
        val stream: suspend Realtime.(Context, String, Boolean) -> LiveStream = Realtime::stream
        val remove: suspend LiveStream.(String) -> Unit = LiveStream::remove
        val kick: suspend LiveStream.(String) -> Unit = LiveStream::kick

        listOf(call, group, stream, remove, kick).forEach { assertNotNull(it) }
    }

    @Test
    fun theWaysInWithATokenAreThereInTheShapesAnAppWrites() {
        val startCall: suspend Realtime.(Context, String) -> Call = Realtime::startCall
        val joinGroupCall: suspend Realtime.(Context, String) -> GroupCall = Realtime::joinGroupCall
        val joinStream: suspend Realtime.(Context, suspend () -> String) -> LiveStream = Realtime::joinStream
        val joinStreamVideo: suspend Realtime.(Context, Boolean, suspend () -> String) -> LiveStream = Realtime::joinStream
        val joinStreamOnce: suspend Realtime.(Context, String, Boolean) -> LiveStream = Realtime::joinStream
        val add: suspend Call.(String) -> CallInvite = Call::add
        val groupAdd: suspend GroupCall.(String) -> CallInvite = GroupCall::add
        val ref: Call.() -> String = Call::ref
        val groupRef: GroupCall.() -> String? = GroupCall::ref

        listOf(startCall, joinGroupCall, joinStream, joinStreamVideo, joinStreamOnce, add, groupAdd, ref, groupRef).forEach { assertNotNull(it) }
    }
}
