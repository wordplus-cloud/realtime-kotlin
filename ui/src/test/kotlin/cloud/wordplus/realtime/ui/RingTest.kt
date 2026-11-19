package cloud.wordplus.realtime.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ring's end takes its screen away, but for the answer this screen itself is making: the ring says it is over as it
 * is answered (@wordplus/realtime-client a738a57), and the call's screen comes with the answer. Run on the JVM:
 * `./gradlew testDebugUnitTest` in ui/.
 */
class RingTest {
    @Test
    fun theAnswerThisScreenIsMakingKeepsIt() {
        assertFalse(ringGoes("answered", answeringHere = true))
    }

    @Test
    fun anyOtherEndTakesItAway() {
        // The app answered through the API, or the system's screen did: the call's screen is shown as the answer comes.
        assertTrue(ringGoes("answered", answeringHere = false))
        for (reason in listOf("declined", "busy", "cancelled", "answered_elsewhere")) {
            assertTrue(ringGoes(reason, answeringHere = false))
            assertTrue(ringGoes(reason, answeringHere = true))
        }
    }
}
