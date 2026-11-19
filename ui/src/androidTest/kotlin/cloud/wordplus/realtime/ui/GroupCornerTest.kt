package cloud.wordplus.realtime.ui

import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wordplus.realtime.ui.calls.CallsGrid
import cloud.wordplus.realtime.ui.calls.CallsStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A group call's corner window on the real stage, with the screen told it is the window as the system would tell it (the
 * system's window itself is not opened here). `./gradlew connectedDebugAndroidTest`, as PiecesTest.
 */
@RunWith(AndroidJUnit4::class)
class GroupCornerTest {
    /** What a screen reader finds, under no hidden view. */
    private fun said(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()

        return listOfNotNull(view.contentDescription?.toString()) +
            ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { said(group.getChildAt(it)) } } ?: emptyList())
    }

    private fun texts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()

        return listOfNotNull((view as? TextView)?.text?.toString()) +
            ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { texts(group.getChildAt(it)) } } ?: emptyList())
    }

    private fun <T : View> find(view: View, type: Class<T>): T? =
        if (type.isInstance(view)) type.cast(view) else (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it), type) }
        }

    private inline fun <reified T : View> find(view: View): T? = find(view, T::class.java)

    private fun <T> shown(shown: CallScreenPreviewActivity.Shown, look: (CallScreenPreviewActivity) -> T): T {
        val intent = CallScreenPreviewActivity.intent(ApplicationProvider.getApplicationContext(), shown)
        return ActivityScenario.launch<CallScreenPreviewActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var result: T? = null
            scenario.onActivity { result = look(it) }
            result!!
        }
    }

    private val hasWindows: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ApplicationProvider.getApplicationContext<android.content.Context>()
            .packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @Test
    fun aGroupVideoCallOffersTheWindowAndAVoiceCallDoesNot() {
        val video = shown(CallScreenPreviewActivity.Shown.GROUP) { said(it.window.decorView) }
        val voice = shown(CallScreenPreviewActivity.Shown.GROUP_VOICE) { said(it.window.decorView) }
        if (hasWindows) assertTrue("$video", "Picture In Picture" in video)
        assertFalse("$voice", "Picture In Picture" in voice)
    }

    @Test
    fun theWindowIsOnePersonWithNothingAroundThem() {
        shown(CallScreenPreviewActivity.Shown.GROUP) { activity ->
            val root = activity.window.decorView
            val stage = find<CallsStage>(root)!!
            val grid = find<CallsGrid>(root)!!
            assertTrue(stage.canEnterCorner)
            assertTrue(listOf("You", "Ann", "Ben", "Cara").all { it in texts(grid) })

            stage.setCornerWindow(true)
            // The one speaking, by name, alone over the call.
            assertEquals("ann", grid.solo)
            assertTrue("${texts(grid)}", "Ann" in texts(grid))
            assertFalse("${texts(grid)}", "Ben" in texts(grid))
            assertTrue(grid.translationZ > 0f)
            assertFalse("Exit Chat" in said(root))

            // And back, everyone with it.
            stage.setCornerWindow(false)
            assertNull(grid.solo)
            assertTrue(listOf("You", "Ann", "Ben", "Cara").all { it in texts(grid) })
            assertEquals(0f, grid.translationZ, 0f)
            assertTrue("Exit Chat" in said(root))
        }
    }

    @Test
    fun theWindowFollowsThePins() {
        shown(CallScreenPreviewActivity.Shown.GROUP) { activity ->
            val root = activity.window.decorView
            val stage = find<CallsStage>(root)!!
            val grid = find<CallsGrid>(root)!!
            stage.setCornerWindow(true)
            stage.togglePinForMe("ben")
            assertEquals("ben", grid.solo)
            stage.togglePinForMe("ben")
            assertEquals("ann", grid.solo)
            stage.setCornerWindow(false)
        }
    }
}
