package cloud.wordplus.realtime.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A room publisher's offer, as the Android room client made them in a group call whose host made someone a listener and let
 * them speak again (LiveCallsTest): whether a camera's section is left open with no track of this app holding it, which a
 * new publish would take over and libwebrtc refuse ([RoomPublisher]). Run on the JVM: `./gradlew testDebugUnitTest` in calls/.
 */
class PublisherSectionsTest {
    private fun offer(vararg sections: String) = (listOf("v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1 2") + sections.flatMap { it.split("|") }).joinToString("\r\n") + "\r\n"

    private val data = "m=application 9 UDP/DTLS/SCTP webrtc-datachannel|a=mid:0"
    private fun audio(mid: Int, port: Int, direction: String) = "m=audio $port UDP/TLS/RTP/SAVPF 111 63|c=IN IP4 0.0.0.0|a=mid:$mid|a=$direction|a=rtpmap:111 opus/48000/2"
    private fun video(mid: Int, port: Int, direction: String) = "m=video $port UDP/TLS/RTP/SAVPF 96 97|c=IN IP4 0.0.0.0|a=mid:$mid|a=$direction|a=rtpmap:96 VP8/90000"

    @Test
    fun aCameraTakenDownAndNotYetClosedIsOpen() {
        // The offer that fell between the camera's track going and its transceiver stopping, and one made before it went.
        assertTrue(PublisherSections.unheldVideo(offer(data, audio(1, 9, "inactive"), video(2, 9, "inactive")), emptySet()))
        assertTrue(PublisherSections.unheldVideo(offer(data, audio(1, 9, "inactive"), video(2, 9, "sendonly")), emptySet()))
    }

    @Test
    fun aClosedOneIsNot() {
        assertFalse(PublisherSections.unheldVideo(offer(data, audio(1, 9, "inactive"), video(2, 0, "inactive")), emptySet()))
    }

    @Test
    fun aCameraStillPublishedIsNot() {
        assertFalse(PublisherSections.unheldVideo(offer(data, audio(1, 9, "sendonly"), video(2, 9, "sendonly")), setOf("2")))
        assertFalse(PublisherSections.unheldVideo(offer(data, audio(1, 9, "inactive"), audio(3, 9, "inactive"), video(4, 0, "inactive"), audio(5, 9, "sendonly"), video(6, 9, "sendonly")), setOf("6")))
        assertTrue(PublisherSections.unheldVideo(offer(data, video(4, 9, "inactive"), video(6, 9, "sendonly")), setOf("6")))
    }

    @Test
    fun aMicrophoneTakenDownIsNot() {
        assertFalse(PublisherSections.unheldVideo(offer(data, audio(1, 9, "inactive")), emptySet()))
    }

    @Test
    fun noSectionsAndLineEndsOfEitherKind() {
        assertFalse(PublisherSections.unheldVideo(offer(data), emptySet()))
        assertFalse(PublisherSections.unheldVideo("", emptySet()))
        assertTrue(PublisherSections.unheldVideo(offer(data, video(2, 9, "inactive")).replace("\r\n", "\n"), emptySet()))
    }
}
