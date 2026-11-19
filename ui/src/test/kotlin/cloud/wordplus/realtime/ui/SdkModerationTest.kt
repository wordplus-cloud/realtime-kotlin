package cloud.wordplus.realtime.ui

import cloud.wordplus.realtime.calls.ModerationAct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A host on the SDK's group calls, as the web's SDK screens have one (realtime-ui's use-group-call.ts): the person menu's
 * acts as the SDK's calls take them, and the user a tile is. Run on the JVM: `./gradlew testDebugUnitTest` in ui/.
 */
class SdkModerationTest {
    @Test
    fun theSdksActsAreTheWebs() {
        assertEquals(
            mapOf(
                "remove_participant" to ModerationAct.REMOVE,
                "mute_participant" to ModerationAct.LISTEN,
                "unmute_participant" to ModerationAct.SPEAK,
                "mute_mic" to ModerationAct.MUTE,
            ),
            SdkModeration.acts,
        )
        // What the SDK's calls carry none of answers false.
        assertNull(SdkModeration.acts["lock_room"])
        assertNull(SdkModeration.acts["pin_participant"])
    }

    @Test
    fun theUserATileIs() {
        assertEquals("42", SdkModeration.user("user_42"))
        assertEquals("auth0|5f7c8e", SdkModeration.user("user_auth0|5f7c8e"))
        assertNull(SdkModeration.user("user_"))
        assertNull(SdkModeration.user("ingest"))
    }
}
