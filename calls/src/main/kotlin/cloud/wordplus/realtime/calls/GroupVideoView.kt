package cloud.wordplus.realtime.calls

import android.content.Context
import android.widget.FrameLayout
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer

/** Which of a person's pictures in a group call: their camera, or the screen they share. */
enum class GroupVideoSource { CAMERA, SCREEN }

/**
 * A group call's picture, drawn for an app's own screen: someone's camera or shared screen (`show(identity, call)`), or
 * this app's own camera (`showOwn(call)`), filling the view. What `CallScreen`'s grid draws, for an app that draws its
 * own grid instead. Call it again when the call's `onPeople` says what someone sends
 * changed: a camera turned off shows nothing. `release()` once the view leaves the screen for good.
 *
 * ```kotlin
 * call.onPeople { call.people.forEach { person -> tiles[person.identity]?.show(person.identity, call) } }
 * ```
 */
class GroupVideoView(context: Context) : FrameLayout(context) {
    private val renderer = SurfaceViewRenderer(context)
    private var shown: VideoTrack? = null
    private var initialized = false

    init {
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Whether the picture fills the view, cropped (true, the default), or fits inside it, as a shared screen should. */
    var fills: Boolean = true
        set(value) {
            field = value
            if (initialized) renderer.setScalingType(scaling())
        }

    /** Whether the picture is drawn as a mirror, as one's own camera usually is. */
    var mirrored: Boolean = false
        set(value) {
            field = value
            renderer.setMirror(value)
        }

    /** Someone's camera or shared screen (`GroupPerson.identity`), or nothing for null or a picture they don't send. */
    fun show(identity: String?, call: GroupCall, source: GroupVideoSource = GroupVideoSource.CAMERA) {
        attach(identity?.let { call.videoTrack(it, source) })
    }

    /** This app's own camera while it is sent into the call. */
    fun showOwn(call: GroupCall) {
        attach(call.ownCameraTrack)
    }

    /** Draws nothing. */
    fun clear() = attach(null)

    /** Lets the renderer go. */
    fun release() {
        shown?.removeRenderer(renderer)
        shown = null
        if (initialized) renderer.release()
        initialized = false
    }

    private fun scaling() = if (fills) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT

    private fun attach(track: VideoTrack?) {
        if (shown === track) return
        if (!initialized && track != null) {
            renderer.init(CallMedia.get(context).eglBase.eglBaseContext, null)
            renderer.setScalingType(scaling())
            renderer.setMirror(mirrored)
            initialized = true
        }
        shown?.removeRenderer(renderer)
        shown = track
        track?.addRenderer(renderer)
    }
}
