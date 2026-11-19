package cloud.wordplus.realtime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wordplus.realtime.ui.calls.CallsAvatars
import cloud.wordplus.realtime.ui.calls.CallsSvgPictures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The avatars of the call screens (CallsAvatars): an SVG, which Android has no decoder for and letter-avatar generators
 * serve, comes out a picture drawn by a web view with its colours where the SVG put them, known by its type or by its
 * bytes; bytes that are no picture are none; and the initials that stand in for a picture. The Swift package's
 * AvatarsTests' twin.
 */
@RunWith(AndroidJUnit4::class)
class AvatarsTest {
    /** A letter avatar as one such generator writes it: a square of colour, two letters on it. */
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" """ +
        """width="100px" height="100px" viewBox="0 0 100 100" style="user-select: none;"><rect fill="#c86ec8" cx="50" """ +
        """width="100" height="100" cy="50" r="50"/><text x="50%" y="50%" style="color: #fff; line-height: 1;""" +
        """font-family: -apple-system, sans-serif;" alignment-baseline="middle" text-anchor="middle" font-size="50" """ +
        """font-weight="400" dy=".1em" dominant-baseline="middle" fill="#fff">IN</text></svg>"""

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun picture(data: ByteArray, type: String?): Bitmap? {
        val decided = CountDownLatch(1)
        var bitmap: Bitmap? = null

        CallsAvatars.picture(context, data, type) {
            bitmap = it
            decided.countDown()
        }

        assertTrue("the picture is decided", decided.await(30, TimeUnit.SECONDS))

        return bitmap
    }

    private fun near(colour: Int, red: Int, green: Int, blue: Int) =
        abs(Color.red(colour) - red) <= 6 && abs(Color.green(colour) - green) <= 6 && abs(Color.blue(colour) - blue) <= 6

    @Test
    fun anSvgIsDrawnToAPicture() {
        val image = picture(svg.toByteArray(), "image/svg+xml")

        assertNotNull("an SVG avatar is a picture", image)
        assertEquals("drawn as large as the largest avatar on screen", CallsSvgPictures.SIDE, image!!.width)
        assertEquals(CallsSvgPictures.SIDE, image.height)
        assertTrue("its colour in the corner: ${Integer.toHexString(image.getPixel(4, 4))}", near(image.getPixel(4, 4), 200, 110, 200))

        // The letters are white, somewhere across the middle of it.
        val middle = CallsSvgPictures.SIDE / 2
        val across = (middle - 60 until middle + 60 step 2).map { image.getPixel(it, middle + 4) }
        assertTrue("its letters are drawn", across.any { near(it, 255, 255, 255) })
    }

    @Test
    fun anSvgIsKnownByItsBytesUnderAnotherType() {
        assertNotNull("a server's other word for it is no matter", picture(svg.toByteArray(), "application/octet-stream"))
        assertNotNull("nor an XML prolog", picture(("<?xml version=\"1.0\"?>\n$svg").toByteArray(), null))
    }

    @Test
    fun oneSvgAfterAnotherIsEachDrawn() {
        val decided = CountDownLatch(2)
        val drawn = mutableListOf<Int?>()

        CallsAvatars.picture(context, svg.toByteArray(), "image/svg+xml") {
            drawn.add(it?.getPixel(4, 4))
            decided.countDown()
        }
        CallsAvatars.picture(context, svg.replace("#c86ec8", "#3a7bd5").toByteArray(), "image/svg+xml") {
            drawn.add(it?.getPixel(4, 4))
            decided.countDown()
        }

        assertTrue("both are decided", decided.await(30, TimeUnit.SECONDS))
        assertTrue("the first in its colour", drawn[0] != null && near(drawn[0]!!, 200, 110, 200))
        assertTrue("the second in its own", drawn[1] != null && near(drawn[1]!!, 58, 123, 213))
    }

    @Test
    fun whatIsNoPictureIsNone() {
        assertNull("a page of an error is no avatar", picture("<html><body>Not found</body></html>".toByteArray(), "text/html"))
        assertNull("nor is nothing", picture(ByteArray(0), "image/png"))
        assertNull("nor an SVG cut short", picture("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect".toByteArray(), "image/svg+xml"))
    }

    @Test
    fun initials() {
        assertEquals("AL", CallsAvatars.initials("ann lee"))
        assertEquals("two letters at most", "AL", CallsAvatars.initials("Ann Lee Cole"))
        assertEquals("A", CallsAvatars.initials("Ann"))
        assertEquals("?", CallsAvatars.initials(""))
        assertEquals("?", CallsAvatars.initials("  "))
    }
}
