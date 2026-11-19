package cloud.wordplus.realtime.ui.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotlight for Everyone and Pin for Me on the call screens, as the web's calls/spotlight.tsx has them: who is in the
 * spotlight and who is on the main tiles, and the pins that wait for a spotlight to end. Run on the JVM:
 * `./gradlew testDebugUnitTest` in ui/.
 */
class CallsSpotlightTest {
    private fun person(id: Int, local: Boolean = false, pinned: Boolean = false, admin: Boolean = false, publish: Boolean = true) =
        CallsRoomParticipant(
            identity = "user_$id", name = if (local) "Me" else "Person $id", avatar = "", isLocal = local, video = null, screen = null,
            speaking = false, audioLevel = 0f, muted = true, micMuted = false, handRaised = false, isPinned = pinned,
            canPublish = publish, canAdmin = admin, mode = "participant",
        )

    @Test
    fun theSpotlightInItsOrderAndAnOlderAppsPin() {
        val people = listOf(person(1, local = true), person(2), person(3, pinned = true), person(4))

        // The room's order, those in the call alone, once each; then anyone an older app pinned for everyone.
        assertEquals(listOf("user_4", "user_2", "user_3"), CallsSpotlight.spotlit(people, listOf("user_4", "user_9", "user_2", "user_4")))
        assertEquals(listOf("user_3"), CallsSpotlight.spotlit(people, emptyList()))
        assertEquals(emptyList<String>(), CallsSpotlight.spotlit(listOf(person(1)), emptyList()))
        assertEquals(4, CallsSpotlight.MOST)
        assertEquals("Up to four people can be in the spotlight.", CallsSpotlight.FULL)
    }

    @Test
    fun aSpotlightComesFirstAndThePinsWaitForIt() {
        val people = listOf(person(1, local = true), person(2), person(3), person(4, admin = true), person(5, publish = false))

        // The spotlight alone, the pins and a broadcast's speakers waiting.
        assertEquals(listOf("user_3"), CallsSpotlight.main(people, listOf("user_3"), listOf("user_2"), true))
        // Nobody in it: this user's pins in the order pinned, someone gone no longer one.
        assertEquals(listOf("user_3", "user_2"), CallsSpotlight.main(people, emptyList(), listOf("user_3", "user_7", "user_2"), false))
        // In broadcast mode a pin alone, the speakers only without one, a viewer not.
        assertEquals(listOf("user_3"), CallsSpotlight.main(people, emptyList(), listOf("user_3"), true))
        assertEquals(listOf("user_1", "user_2", "user_3", "user_4"), CallsSpotlight.main(people, emptyList(), emptyList(), true))
        assertEquals(emptyList<String>(), CallsSpotlight.main(people, emptyList(), emptyList(), false))
        // A spotlight of someone who left is nobody's: the pins show.
        assertEquals(listOf("user_2"), CallsSpotlight.main(people, listOf("user_9"), listOf("user_2"), false))
    }

    @Test
    fun pinForMeOnlyWhereAPinShows() {
        // Nobody in the spotlight: offered for anyone.
        assertTrue(CallsSpotlight.pinOffered("user_2", emptyList(), emptyList()))
        // Someone in it: not offered, but a tile already pinned keeps its Unpin for Me.
        assertFalse(CallsSpotlight.pinOffered("user_2", emptyList(), listOf("user_3")))
        assertTrue(CallsSpotlight.pinOffered("user_2", listOf("user_2"), listOf("user_3")))
    }

    @Test
    fun pinsOnAndOffAndForThoseStillThere() {
        assertEquals(listOf("user_2"), CallsSpotlight.toggled(emptyList(), "user_2"))
        assertEquals(listOf("user_3"), CallsSpotlight.toggled(listOf("user_2", "user_3"), "user_2"))
        assertEquals(listOf("user_3", "user_2"), CallsSpotlight.toggled(listOf("user_3"), "user_2"))
        assertEquals(listOf("user_3"), CallsSpotlight.kept(listOf("user_2", "user_3"), listOf(person(1, local = true), person(3))))
    }
}
