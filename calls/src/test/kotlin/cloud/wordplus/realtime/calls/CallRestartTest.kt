package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the engine tells the other side's ICE restart from any other offer ([CallPeer.iceUfrag]), the same rule as the
 * realtime client's calls/peer.ts: before a first connection a restart the other side made is this side's attempt too,
 * so its own connect clock does not restart over it. A callee whose caller heard the relay five seconds late (30 Oct
 * 2026) and a caller that restarted on its own clock abandoned each other's checks until the call went to the room.
 */
class CallRestartTest {
    private fun offer(ufrag: String) =
        "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0 1\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=ice-ufrag:$ufrag\r\na=ice-pwd:p\r\na=mid:0\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\na=ice-ufrag:$ufrag\r\na=ice-pwd:p\r\na=mid:1\r\n"

    @Test
    fun theFirstSectionsUfragIsRead() {
        assertEquals("Ab3d", CallPeer.iceUfrag(offer("Ab3d")))
        assertEquals("x", CallPeer.iceUfrag("a=ice-ufrag:x\nm=audio 9\n"))
    }

    @Test
    fun aRestartChangesItAndARenegotiationDoesNot() {
        val held = CallPeer.iceUfrag(offer("Ab3d"))

        assertNotEquals(held, CallPeer.iceUfrag(offer("Zz90")))
        assertEquals(held, CallPeer.iceUfrag(offer("Ab3d")))
    }

    @Test
    fun aDescriptionWithoutOneHasNone() {
        assertNull(CallPeer.iceUfrag("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        assertNull(CallPeer.iceUfrag("a=ice-ufrag:\r\n"))
    }
}
