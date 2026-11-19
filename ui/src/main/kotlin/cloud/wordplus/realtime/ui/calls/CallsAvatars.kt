package cloud.wordplus.realtime.ui.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Faces for the people with their cameras off.
 *
 * Small, because the alternative is a whole image library for one square
 * picture per person. It is cached by URL and fetched once: a call redraws its
 * grid on every active-speaker change, and re-fetching an avatar each time
 * would be a request every few hundred milliseconds.
 */
object CallsAvatars {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val waiting = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, url: String, done: (Bitmap?) -> Unit) {
        if (url.isEmpty()) {
            done(null)
            return
        }

        cache[url]?.let {
            done(it)
            return
        }

        // Two tiles asking for the same avatar at once is normal — the grid
        // rebinds every tile on every roster change, and two people can share
        // a picture. They all wait on the one request and all get the answer:
        // dropping the second caller leaves that tile showing a letter for the
        // rest of the call.
        val first: Boolean

        synchronized(waiting) {
            val list = waiting.getOrPut(url) { mutableListOf() }

            first = list.isEmpty()
            list.add(done)
        }

        if (!first) return

        // The application's: an SVG is drawn by a web view kept a while after, which must not keep a screen alive.
        val app = context.applicationContext

        pool.execute {
            val fetched = fetch(url)

            picture(app, fetched?.first, fetched?.second) { bitmap ->
                if (bitmap != null) cache[url] = bitmap

                val callbacks = synchronized(waiting) { waiting.remove(url) ?: mutableListOf() }

                // No avatar is initials on a disc, which is a perfectly good
                // tile. It must never be a broken call.
                main.post { callbacks.forEach { it(bitmap) } }
            }
        }
    }

    /** The avatar's bytes and their type, or null. */
    private fun fetch(url: String): Pair<ByteArray, String?>? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection

            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true

            val bytes = connection.inputStream.use { it.readBytes() }

            bytes to connection.contentType
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The picture in an avatar's bytes, on any thread.
     *
     * A photo decodes as it is. An SVG, which is what letter-avatar
     * generators serve for a site's every member, has no decoder in Android
     * and came back as no picture at all: initials where the web shows the
     * avatar. A web view draws it instead ([CallsSvgPictures]).
     */
    fun picture(context: Context, data: ByteArray?, type: String?, done: (Bitmap?) -> Unit) {
        if (data == null || data.isEmpty()) return done(null)

        BitmapFactory.decodeByteArray(data, 0, data.size)?.let { return done(it) }

        if (!isSvg(data, type)) return done(null)

        main.post { CallsSvgPictures.draw(context, data, done) }
    }

    /**
     * An SVG by its type, or by its first kilobyte where a server calls it
     * something else (`text/xml`, `application/octet-stream`).
     */
    fun isSvg(data: ByteArray, type: String?): Boolean {
        if (type?.lowercase()?.startsWith("image/svg") == true) return true

        return String(data, 0, min(1024, data.size), Charsets.UTF_8).lowercase().contains("<svg")
    }

    /**
     * What stands in for a picture: the first letters of the first two words
     * of the name, on every avatar of the call screens.
     */
    fun initials(name: String): String {
        val letters = name.split(" ").filter { it.isNotEmpty() }.take(2).map { it.first().uppercase() }

        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }
}

/**
 * SVG avatars drawn to pictures by one web view kept off screen, on the main
 * thread, one at a time.
 *
 * The web view draws the SVG on a canvas, as the web's call screens show it,
 * and hands back a PNG. It is made for the first SVG and let go a little
 * after the last, so a call with no SVG avatars carries none of it.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
internal object CallsSvgPictures {
    /**
     * Pixels on a side: the largest avatar on screen, the 108-point disc of a
     * call with one person, at three pixels to the point.
     */
    const val SIDE = 324

    /** How long the web view is kept after its last picture. */
    private const val KEEP_MS = 15_000L

    /** How long one picture may take before it is given up as none. */
    private const val GIVE_UP_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var ready = false
    private val jobs = ArrayDeque<Pair<ByteArray, (Bitmap?) -> Unit>>()
    private var current: ((Bitmap?) -> Unit)? = null
    private var currentId = 0

    private val release = Runnable { if (jobs.isEmpty() && current == null) drop() }
    private val giveUp = Runnable { finish(currentId, null) }

    /** What the page calls with each picture, on a thread of the web view's own. */
    private class Bridge {
        @JavascriptInterface
        fun done(id: Int, png: String) {
            val bitmap = try {
                val bytes = Base64.decode(png, Base64.DEFAULT)

                if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }

            main.post { finish(id, bitmap) }
        }
    }

    fun draw(context: Context, svg: ByteArray, done: (Bitmap?) -> Unit) {
        jobs.addLast(svg to done)
        main.removeCallbacks(release)

        if (view != null) return next()

        // A device whose web view is missing, or being updated, throws here: the avatars stay initials.
        val web = try {
            WebView(context)
        } catch (e: Throwable) {
            return fail()
        }

        web.settings.javaScriptEnabled = true
        // The SVG comes as data: whatever it points at elsewhere is not fetched.
        web.settings.blockNetworkLoads = true
        web.addJavascriptInterface(Bridge(), "wplusSvgPictures")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView, url: String?) {
                if (page !== view) return

                ready = true
                next()
            }

            override fun onRenderProcessGone(page: WebView, detail: RenderProcessGoneDetail?): Boolean {
                if (page === view) fail()

                return true
            }
        }

        view = web
        ready = false
        web.loadDataWithBaseURL(null, "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
    }

    private fun next() {
        val web = view ?: return

        if (!ready || current != null) return

        val job = jobs.removeFirstOrNull() ?: run {
            main.postDelayed(release, KEEP_MS)
            return
        }

        current = job.second
        currentId += 1
        main.postDelayed(giveUp, GIVE_UP_MS)

        val svg = Base64.encodeToString(job.first, Base64.NO_WRAP)

        web.evaluateJavascript(
            """
            (function (id, svg, side) {
                var said = false;
                var say = function (png) { if (!said) { said = true; wplusSvgPictures.done(id, png); } };
                var picture = new Image();
                picture.onload = function () {
                    try {
                        var width = picture.naturalWidth || side, height = picture.naturalHeight || side;
                        var scale = Math.max(side / width, side / height);
                        var canvas = document.createElement('canvas');
                        canvas.width = side;
                        canvas.height = side;
                        canvas.getContext('2d').drawImage(picture, (side - width * scale) / 2, (side - height * scale) / 2, width * scale, height * scale);
                        say(canvas.toDataURL('image/png').split(',')[1]);
                    } catch (e) {
                        say('');
                    }
                };
                picture.onerror = function () { say(''); };
                picture.src = 'data:image/svg+xml;base64,' + svg;
            })($currentId, '$svg', $SIDE);
            """.trimIndent(),
            null
        )
    }

    private fun finish(id: Int, bitmap: Bitmap?) {
        if (id != currentId) return

        val done = current ?: return

        current = null
        main.removeCallbacks(giveUp)
        done(bitmap)
        next()
    }

    /** The web view is gone: everyone waiting gets initials, and the next SVG makes a new one. */
    private fun fail() {
        val waiting = jobs.map { it.second } + listOfNotNull(current)

        jobs.clear()
        current = null
        main.removeCallbacks(giveUp)
        drop()
        waiting.forEach { it(null) }
    }

    private fun drop() {
        main.removeCallbacks(release)
        view?.destroy()
        view = null
        ready = false
    }
}
