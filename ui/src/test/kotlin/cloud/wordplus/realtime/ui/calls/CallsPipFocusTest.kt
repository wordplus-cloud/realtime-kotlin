package cloud.wordplus.realtime.ui.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whom a group call's corner window shows, as the web's pipFocus chooses (realtime-ui inc/calls/pip-focus.ts), and when
 * there is one, as its useGroupPip. The same cases as realtime-swift's GroupCornerTests. Run on the JVM.
 */
class CallsPipFocusTest {
    private val me = CallsPipFocus.Person("me", isLocal = true, camera = true)
    private val ann = CallsPipFocus.Person("ann", camera = true)
    private val ben = CallsPipFocus.Person("ben")

    private fun pick(vararg people: CallsPipFocus.Person, first: List<String> = emptyList(), last: String? = null) =
        CallsPipFocus.pick(people.toList(), first, last)

    @Test
    fun theSpotlightAndThePinsComeFirst() {
        val sharing = CallsPipFocus.Person("cara", screen = true)
        assertEquals("ben", pick(me, ann, ben, sharing, first = listOf("ben"), last = "ann"))
        // This user's own tile, where they are the one in the spotlight.
        assertEquals("me", pick(me, ann, first = listOf("me")))
        // Of several, the loudest while they speak, then who spoke last, then the first.
        val talking = CallsPipFocus.Person("ann", camera = true, speaking = true, level = 0.2f)
        val louder = CallsPipFocus.Person("ben", speaking = true, level = 0.6f)
        assertEquals("ben", pick(me, talking, louder, first = listOf("ann", "ben")))
        assertEquals("ben", pick(me, ann, ben, first = listOf("ann", "ben"), last = "ben"))
        assertEquals("ann", pick(me, ann, ben, first = listOf("ann", "ben")))
        // Someone who left is not kept ahead.
        assertEquals("ann", pick(me, ann, first = listOf("gone")))
    }

    @Test
    fun aScreenThenWhoSpeaksThenWhoSpokeLast() {
        val sharing = CallsPipFocus.Person("cara", screen = true)
        val talking = CallsPipFocus.Person("ben", speaking = true, level = 0.4f)
        assertEquals("cara", pick(me, talking, sharing))
        assertEquals("ben", pick(me, ann, talking))
        assertEquals("ben", pick(me, ann, ben, last = "ben"))
        // Who spoke last and has gone is nobody, and a share of this user's own is not what the window is for.
        assertEquals("ann", pick(me, ben, ann, last = "gone"))
        assertEquals("ben", pick(CallsPipFocus.Person("me", isLocal = true, screen = true), ben))
    }

    @Test
    fun theFirstWithAPictureTheFirstOtherThenTheUser() {
        assertEquals("ann", pick(me, ben, ann))
        assertEquals("ben", pick(me, ben))
        assertEquals("me", pick(me))
        assertNull(pick())
    }

    @Test
    fun aVideoCallHasItAllThroughAnAudioCallOnlyForAScreen() {
        assertTrue(CallsPipFocus.offered(listOf(me, ben), video = true))
        assertFalse(CallsPipFocus.offered(emptyList(), video = true))
        assertFalse(CallsPipFocus.offered(listOf(me, ben), video = false))
        assertTrue(CallsPipFocus.offered(listOf(me, CallsPipFocus.Person("cara", screen = true)), video = false))
        // This user's own share would show them what they are sharing.
        assertFalse(CallsPipFocus.offered(listOf(CallsPipFocus.Person("me", isLocal = true, screen = true), ben), video = false))
    }

    @Test
    fun aWindowIsAShapeTheSystemTakes() {
        assertEquals(2.38f, CallsPipFocus.windowAspect(4f), 0f)
        assertEquals(0.42f, CallsPipFocus.windowAspect(0.1f), 0f)
        assertEquals(1.5f, CallsPipFocus.windowAspect(1.5f), 0f)
    }
}
