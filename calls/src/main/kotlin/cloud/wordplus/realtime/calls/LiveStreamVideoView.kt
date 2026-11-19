package cloud.wordplus.realtime.calls

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.VideoSinkVisibility
import io.livekit.android.room.track.video.ViewVisibility
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer

/**
 * A live stream's picture: the stage's main one as the stream goes (the spotlight's, a shared screen, the host's), or one
 * tile's (`show(stream, tile)`), fitting the view. A host sees their own camera here from `preview()` on. Its sound plays
 * by itself while the stream is watched. `release()` once the view leaves the screen for good.
 *
 * ```kotlin
 * val stream = rt.calls(context).stream("show:42")
 * player.show(stream)
 * ```
 */
class LiveStreamVideoView(context: Context) : FrameLayout(context) {
    private val renderer = SurfaceViewRenderer(context)
    private var shown: VideoTrack? = null
    private var initialized = false
    private var stream: LiveStream? = null
    private var tile: String? = null
    private var shownTile: LiveStreamTile? = null
    private var visibility: ChosenSize? = null

    init {
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Whether the picture fills the view, cropped, or fits inside it (the default: a stream's picture is seen whole). */
    var fills: Boolean = false
        set(value) {
            field = value
            if (initialized) renderer.setScalingType(scaling())
        }

    /** Whether a picture is drawn now. */
    val showsPicture: Boolean get() = shown != null

    /** Draws this view over another of them, as a camera in the corner of the picture. Before it joins the window. */
    fun overTheOther() {
        renderer.setZOrderMediaOverlay(true)
    }

    /** The stream's main picture, or the tile `identity`'s (`LiveStreamTile.identity`), following it as it changes. */
    fun show(stream: LiveStream, tile: String? = null) {
        if (this.stream !== stream) this.stream?.hide(this)
        this.stream = stream
        this.tile = tile
        stream.show(this)
    }

    /** Draws nothing, and stops following the stream. */
    fun clear() {
        stream?.hide(this)
        stream = null
        attach(null, false)
    }

    /** Lets the renderer go. */
    fun release() {
        clear()
        if (initialized) renderer.release()
        initialized = false
    }

    internal fun draw(stream: LiveStream) {
        val wanted = tile
        val shownTile = if (wanted != null) stream.tiles.firstOrNull { it.identity == wanted } else stream.mainTile
        this.shownTile = shownTile
        attach(stream.videoTrack(shownTile), shownTile?.local == true)
        visibility?.notifyChanged()
    }

    /** A viewer's choice of layer (`setQuality`) as the size this view asks for: a picture comes at the size its view asks. */
    private fun chosenSize(): Track.Dimensions? = stream?.chosenLayer(shownTile)?.let { Track.Dimensions(it.width, it.height) }

    private fun scaling() = if (fills) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT

    private fun attach(track: VideoTrack?, mirrored: Boolean) {
        if (initialized) renderer.setMirror(mirrored)
        if (shown === track) return
        if (!initialized && track != null) {
            renderer.init(CallMedia.get(context).eglBase.eglBaseContext, null)
            renderer.setScalingType(scaling())
            renderer.setMirror(mirrored)
            initialized = true
        }
        shown?.removeRenderer(renderer)
        shown = track
        visibility = null
        if (track is RemoteVideoTrack && track.autoManageVideo) {
            val chosen = ChosenSize(renderer) { chosenSize() }
            visibility = chosen
            track.addRenderer(renderer, chosen)
        } else {
            track?.addRenderer(renderer)
        }
    }

    /** The view's own visibility and size, but for the size of a layer the viewer chose. */
    private class ChosenSize(view: View, private val chosen: () -> Track.Dimensions?) : VideoSinkVisibility() {
        private val own = ViewVisibility(view)

        init {
            own.addObserver { _, _ -> notifyChanged() }
        }

        override fun isVisible(): Boolean = own.isVisible()

        override fun size(): Track.Dimensions = chosen() ?: own.size()

        override fun close() {
            own.close()
            super.close()
        }
    }
}
