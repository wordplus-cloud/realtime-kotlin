package cloud.wordplus.realtime.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The presence parts (OnlineList, PersonRow, RowAction, OnlineCount, Avatar, PresenceDot, TypingDots and
 * ActivityIndicator), as realtime-ui's draw them, given plain values and no room. On a device or an emulator, as
 * PiecesTest. That none of them reads a room is PresencePartsTest's, on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class PresenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun onMain(run: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(run)

    @After
    fun reset() = onMain { CallScreen.configure(CallScreen.Appearance()) }

    private fun all(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } } ?: emptyList())

    /** The words on screen: every label's text, under no gone view. */
    private fun texts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own = (view as? TextView)?.text?.toString()
        return listOfNotNull(own) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { texts(group.getChildAt(it)) } } ?: emptyList())
    }

    /** `view` laid out in a box of this size and direction, in an app that takes right to left. */
    private fun laidOut(view: View, width: Int = 900, height: Int = 1500, rightToLeft: Boolean = false): View {
        context.applicationInfo.flags = context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val box = FrameLayout(context)
        box.layoutDirection = if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        box.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        box.layout(0, 0, width, height)
        return box
    }

    private fun xOf(view: View, inside: View): Int {
        var x = 0
        var on: View? = view
        while (on != null && on !== inside) {
            x += on.left
            on = on.parent as? View
        }
        return x
    }

    // OnlineCount

    @Test
    fun aCountDrawsNothingAtNoneAndNinetyNinePlusPastNinetyNine() = onMain {
        val count = OnlineCount(context)
        assertEquals("nothing at 0", View.GONE, count.visibility)
        assertNull(OnlineCount.said(0))
        assertNull(OnlineCount.said(-1))

        count.count = 1
        assertEquals(View.VISIBLE, count.visibility)
        assertEquals("1", count.text.toString())
        assertEquals("1", count.contentDescription)

        count.count = 99
        assertEquals("99", count.text.toString())
        count.count = 100
        assertEquals("99+", count.text.toString())

        count.count = 0
        assertEquals("and goes again", View.GONE, count.visibility)
    }

    // OnlineList

    @Test
    fun aListSaysItsTitleAndCountAndItsEmptyLineWithNobody() = onMain {
        val list = OnlineList(context)
        assertEquals("ONLINE (0)", list.titleShown)
        assertEquals("No users online", list.emptyShown)
        assertNull("no close button where the list can't be closed", list.closeShown)

        list.title = "In the shop"
        list.empty = "Nobody is in the shop"
        assertEquals("IN THE SHOP (0)", list.titleShown)
        assertEquals("Nobody is in the shop", list.emptyShown)

        list.rows = listOf(PersonRow(context, Avatar(context, "Ann", sizeDp = 32), "Ann"))
        list.count = 1
        assertEquals("IN THE SHOP (1)", list.titleShown)
        assertNull("the empty line goes with someone in it", list.emptyShown)
        assertTrue(texts(list).contains("Ann"))

        list.rows = emptyList()
        list.count = 0
        assertFalse("a row gone is gone", texts(list).contains("Ann"))
    }

    @Test
    fun aListClosesWhereItIsToldHow() = onMain {
        var closed = 0
        val list = OnlineList(context, onClose = { closed++ })
        val close = list.closeShown
        assertNotNull(close)
        assertEquals("Close", close?.contentDescription)
        close?.performClick()
        assertEquals(1, closed)

        list.onClose = null
        assertNull(list.closeShown)
    }

    @Test
    fun aListScrollsWhatDoesNotFit() = onMain {
        val rows = (0 until 40).map { PersonRow(context, Avatar(context, "P$it", sizeDp = 32), "P$it") }
        val list = OnlineList(context, count = 40, rows = rows)
        val box = FrameLayout(context)
        box.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        box.layout(0, 0, 900, 600)
        val scroll = list.scrollShown
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        assertTrue("it stays in the room it is given", list.height <= 600)
    }

    // PersonRow and RowAction

    @Test
    fun aRowDrawsTheFaceTheNameTheLineUnderItAndWhatMayBeDone() = onMain {
        var called = 0
        val action = RowAction(context, RowAction.icons.getValue("phone"), "Call Ann") { called++ }
        val row = PersonRow(context, Avatar(context, "Ann", sizeDp = 32, decorative = true), "Ann", "support", listOf(action))
        assertEquals("Ann", row.nameShown)
        assertEquals("support", row.subShown)
        assertEquals("Call Ann", action.contentDescription)
        assertTrue(action.isClickable)

        action.performClick()
        assertEquals(1, called)

        row.sub = null
        assertNull("no line under the name without one", row.subShown)
        row.name = "Anna"
        assertEquals("Anna", row.nameShown)
    }

    @Test
    fun aRowActionTakesAFingerRoundItsSmallButton() = onMain {
        val action = RowAction(context, RowAction.icons.getValue("phone"), "Call") {}
        action.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        assertEquals((40 * context.resources.displayMetrics.density).toInt().toFloat(), action.measuredWidth.toFloat(), 1f)
    }

    @Test
    fun aRowEndsWhereTheLineEndsInEitherDirection() = onMain {
        fun at(rightToLeft: Boolean): Pair<Int, Int> {
            val avatar = Avatar(context, "Ann", sizeDp = 32)
            val action = RowAction(context, RowAction.icons.getValue("phone"), "Call") {}
            val row = PersonRow(context, avatar, "Ann", actions = listOf(action))
            val box = laidOut(row, rightToLeft = rightToLeft)
            return xOf(avatar, box) to xOf(action, box)
        }

        val leftToRight = at(false)
        assertTrue(leftToRight.first < leftToRight.second)
        val rightToLeft = at(true)
        assertTrue("the face at the start of the line, on the right: $rightToLeft", rightToLeft.first > rightToLeft.second)
    }

    // Avatar and PresenceDot

    @Test
    fun aFaceHasItsLettersWithNoPictureAndWhereThePictureDoesNotLoad() = onMain {
        assertEquals("AL", Avatar(context, "Ann Lee").lettersShown)
        assertEquals("A", Avatar(context, "ann").lettersShown)
        assertEquals("?", Avatar(context).lettersShown)
        assertEquals("an app's own letters", "Z", Avatar(context, "Ann", letters = AvatarLetters("Z", Color.BLACK, Color.WHITE)).lettersShown)
    }

    @Test
    fun aFaceWearsItsDotWhileThePersonIsNotOffline() = onMain {
        val avatar = Avatar(context, "Ann")
        assertNull("no status, no dot", avatar.dotShown)

        avatar.presence = Presence.ONLINE
        assertEquals(Presence.ONLINE, avatar.dotShown?.presence)
        assertEquals("Ann, Online", avatar.contentDescription)

        avatar.presence = Presence.BUSY
        assertEquals("the same dot, in the new status", Presence.BUSY, avatar.dotShown?.presence)
        assertEquals("Ann, Do not disturb", avatar.contentDescription)

        avatar.presence = Presence.OFFLINE
        assertNull("no dot for someone offline, where no dot says as much", avatar.dotShown)
        assertEquals("Ann", avatar.contentDescription)

        avatar.showOffline = true
        assertEquals("unless asked", Presence.OFFLINE, avatar.dotShown?.presence)
    }

    @Test
    fun aFaceBesideItsNameSaysNothing() = onMain {
        val avatar = Avatar(context, "Ann", presence = Presence.ONLINE, decorative = true)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, avatar.importantForAccessibility)
        assertNull(avatar.contentDescription)
    }

    @Test
    fun theDotSitsAtTheCornerTheLineEndsOn() = onMain {
        fun dotPastFace(rightToLeft: Boolean): Boolean {
            val avatar = Avatar(context, "Ann", sizeDp = 40, presence = Presence.ONLINE)
            val holder = FrameLayout(context)
            holder.addView(avatar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            laidOut(holder, rightToLeft = rightToLeft)
            return avatar.dotShown!!.left > avatar.faceShown.left
        }

        assertTrue(dotPastFace(false))
        assertFalse(dotPastFace(true))
    }

    @Test
    fun theDotsAreInTheTokensOfTheWebAndAnAppsOwnColourWhileNotOffline() = onMain {
        CallScreen.configure(CallScreen.Appearance(tokens = mapOf(
            "--wplus-color-online" to "1, 2, 3", "--wplus-color-busy" to "4, 5, 6", "--wplus-color-away" to "7, 8, 9", "--wplus-color-offline" to "10, 11, 12",
        )))
        assertEquals(Color.rgb(1, 2, 3), PresenceDot(context, Presence.ONLINE).colorShown)
        assertEquals(Color.rgb(4, 5, 6), PresenceDot(context, Presence.BUSY).colorShown)
        assertEquals(Color.rgb(7, 8, 9), PresenceDot(context, Presence.AWAY).colorShown)
        assertEquals(Color.rgb(10, 11, 12), PresenceDot(context, Presence.OFFLINE).colorShown)
        assertEquals("no status is drawn as offline", Color.rgb(10, 11, 12), PresenceDot(context, null).colorShown)

        assertEquals(Color.BLUE, PresenceDot(context, Presence.ONLINE, color = Color.BLUE).colorShown)
        assertEquals(Color.rgb(10, 11, 12), PresenceDot(context, Presence.OFFLINE, color = Color.BLUE).colorShown)
    }

    // TypingDots and ActivityIndicator

    @Test
    fun theLineSaysWhoAsTheWebGroupsThem() = onMain {
        fun said(vararg list: ActivityEntry) = ActivityIndicator.said(context, list.toList())
        val recording = ActivityIndicator.Activity.RECORDING
        val uploading = ActivityIndicator.Activity.UPLOADING

        assertEquals("nobody named", "typing", said(ActivityEntry()))
        assertEquals("Anna typing", said(ActivityEntry("Anna")))
        assertEquals("Anna, Bob typing", said(ActivityEntry("Anna"), ActivityEntry("Bob")))
        assertEquals("past two, a count", "3 typing", said(ActivityEntry("Anna"), ActivityEntry("Bob"), ActivityEntry("Cara")))
        assertEquals("2 typing", said(ActivityEntry(), ActivityEntry()))
        assertEquals("a name of nothing is no name", "typing", said(ActivityEntry(" ")))

        assertEquals("recording", said(ActivityEntry(activity = recording)))
        assertEquals("Carol recording", said(ActivityEntry("Carol", recording)))
        assertEquals("sending file", said(ActivityEntry(activity = uploading)))
        assertEquals("Dan sending file", said(ActivityEntry("Dan", uploading)))

        assertEquals(
            "each activity its own part, typing first",
            "Anna, Bob typing, Carol recording",
            said(ActivityEntry("Carol", recording), ActivityEntry("Anna"), ActivityEntry("Bob")),
        )
    }

    @Test
    fun theLineHasTheIconOfItsOneActivityAndTheDotsOnceTheyMix() = onMain {
        val recording = ActivityIndicator.Activity.RECORDING
        val uploading = ActivityIndicator.Activity.UPLOADING
        assertEquals(ActivityIndicator.Activity.TYPING, ActivityIndicator.iconActivity(listOf(ActivityEntry())))
        assertEquals(recording, ActivityIndicator.iconActivity(listOf(ActivityEntry(activity = recording))))
        assertEquals(uploading, ActivityIndicator.iconActivity(listOf(ActivityEntry(activity = uploading))))
        assertEquals(
            ActivityIndicator.Activity.TYPING,
            ActivityIndicator.iconActivity(listOf(ActivityEntry(activity = recording), ActivityEntry(activity = uploading))),
        )

        val line = ActivityIndicator(context, names = listOf("Anna"))
        assertTrue(line.iconView is TypingDots)
        assertEquals("Anna typing", line.contentDescription)
        assertEquals("Anna typing", line.saidNow)

        line.activity = recording
        assertFalse(line.iconView is TypingDots)
        assertEquals("Anna recording", line.contentDescription)

        line.entries = listOf(ActivityEntry("Bob"), ActivityEntry("Cara", uploading))
        assertTrue(line.iconView is TypingDots)
        assertEquals("Bob typing, Cara sending file", line.contentDescription)
    }

    @Test
    fun theWordsAreTheAppsWhereItGivesThem() = onMain {
        CallScreen.configure(CallScreen.Appearance(strings = mapOf(
            "typing" to "schreibt", "typing_named" to "%s schreibt", "recording_named" to "%s nimmt auf", "uploading" to "sendet",
            "online" to "Da", "online_empty" to "Niemand", "close" to "Schliessen", "presence_online" to "Anwesend",
        )))
        assertEquals("schreibt", ActivityIndicator.said(context, listOf(ActivityEntry())))
        assertEquals("Anna schreibt", ActivityIndicator.said(context, listOf(ActivityEntry("Anna"))))
        assertEquals("Anna nimmt auf", ActivityIndicator.said(context, listOf(ActivityEntry("Anna", ActivityIndicator.Activity.RECORDING))))
        assertEquals("sendet", ActivityIndicator.said(context, listOf(ActivityEntry(activity = ActivityIndicator.Activity.UPLOADING))))

        val list = OnlineList(context, onClose = {})
        assertEquals("DA (0)", list.titleShown)
        assertEquals("Niemand", list.emptyShown)
        assertEquals("Schliessen", list.closeShown?.contentDescription)
        assertEquals("Ann, Anwesend", Avatar(context, "Ann", presence = Presence.ONLINE).contentDescription)
    }

    @Test
    fun theDotsSayNothingTheLineSaysWhatTheyMean() = onMain {
        val dots = TypingDots(context)
        assertFalse("off a window nothing moves", dots.moving)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, dots.importantForAccessibility)
        assertFalse("the room they travel in does not cut them off", dots.clipToPadding)
    }
}
