package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Redundant audio on a direct call's microphone ([CallCodecs.redOrder]), as
 * the web's codecs.ts redOrder: RED first, then Opus, then the rest as they
 * were, and nothing changed where the build has no RED.
 */
class CallRedTest {
    @Test
    fun redLeadsThenOpusThenTheRestAsTheyWere() {
        val codecs = listOf("audio/opus", "audio/G722", "audio/red", "audio/PCMU", "audio/telephone-event")

        assertEquals(listOf("audio/red", "audio/opus", "audio/G722", "audio/PCMU", "audio/telephone-event"), CallCodecs.redOrder(codecs)?.map { codecs[it] })
    }

    @Test
    fun aBuildWithoutRedIsLeftAsItIs() {
        assertNull(CallCodecs.redOrder(listOf("audio/opus", "audio/PCMU")))
    }

    @Test
    fun theNameIsReadWhateverItsCase() {
        assertEquals(listOf(1, 0), CallCodecs.redOrder(listOf("audio/OPUS", "audio/RED")))
    }
}
