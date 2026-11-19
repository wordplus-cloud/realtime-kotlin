package cloud.wordplus.realtime.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The presence parts are fed by whoever draws them: none imports the rooms or the calls, so none can read a room. On the
 * JVM, where the sources are at hand. `./gradlew testDebugUnitTest` in ui/.
 */
class PresencePartsTest {
    private val sources = File("src/main/kotlin/cloud/wordplus/realtime/ui")

    @Test
    fun noneOfThemReadsARoom() {
        for (name in listOf("Presence.kt", "OnlineList.kt")) {
            val text = File(sources, name).readText()
            val ours = text.lines().filter { it.startsWith("import cloud.wordplus.") }
            assertEquals(
                "$name takes from the call screen's look alone: $ours",
                emptyList<String>(),
                ours.filterNot { it.startsWith("import cloud.wordplus.realtime.ui.calls.Calls") },
            )
            for (word in listOf("Room", "Realtime", "GroupCall", "RealtimeCalls")) {
                assertFalse("$name takes no $word", text.contains(": $word"))
            }
        }
    }
}
