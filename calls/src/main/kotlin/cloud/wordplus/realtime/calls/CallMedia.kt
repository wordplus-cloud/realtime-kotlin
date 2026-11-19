package cloud.wordplus.realtime.calls

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.Loggable
import livekit.org.webrtc.Logging
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/**
 * The camera and the microphone, and the one factory everything is built from.
 *
 * The factory is shared for the process's life: it owns the encoders, the
 * decoders and the audio device, and a second one would mean a second audio
 * device fighting the first over the same stream.
 */
internal class CallMedia private constructor(private val context: Context) {
    companion object {
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080
        private const val CAPTURE_FPS = 30

        @Volatile
        private var instance: CallMedia? = null

        fun get(context: Context): CallMedia =
            instance ?: synchronized(this) {
                instance ?: CallMedia(context.applicationContext).also { instance = it }
            }
    }

    val eglBase: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        // libwebrtc's own warnings go into the call's trace on the server, in
        // every build. A stall that happens inside the stack — an encoder
        // returning errors, frames thrown away for their timestamps, an
        // allocation that pauses a stream — is visible nowhere else, and
        // logcat needs the phone in hand. [CallLog] caps and collapses
        // them, so a stack that has gone wrong costs the phone a few lines a
        // minute rather than one per frame.
        CallLog.attach(context)

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setInjectableLogger(
                Loggable { message, _, tag -> CallLog.webrtc(if (message.startsWith("(")) message else "($tag): $message") },
                Logging.Severity.LS_WARNING
            )

        PeerConnectionFactory.initialize(options.createInitializationOptions())

        // Hardware echo cancellation and noise suppression where the device
        // has them; libwebrtc's own where it does not.
        //
        // What it records is read for its level too ([heard]): the meter of a device check while the call sends the
        // microphone. Nothing more is opened for it, and nothing it reads goes anywhere.
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { samples -> heard.samples(samples.data) }
            .createAudioDeviceModule()

        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** The direct path's microphone as its audio device records it, for its level. */
    val heard = CallMicLevel()

    // A device check's camera: a capture of its own, never handed to a peer.
    private var previewSource: VideoSource? = null
    private var previewCapturer: CameraVideoCapturer? = null
    private var previewHelper: SurfaceTextureHelper? = null
    private var previewTrack: VideoTrack? = null

    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var audioSource: AudioSource? = null

    var cameraTrack: VideoTrack? = null
        private set

    var microphoneTrack: AudioTrack? = null
        private set

    var usingFrontCamera = true
        private set

    /**
     * Whether frames are actually coming out of the camera. The track outlives
     * a stop — it is the same object the peer connection and the preview hold
     * — so its existence says nothing about whether the capture behind it is
     * running, and a start that trusted the track alone would hand back a
     * camera that has been switched off.
     */
    private var capturing = false

    var captureHeight = CAPTURE_HEIGHT
        private set

    /**
     * Told when the camera stops under the call: it would not open, another
     * app took it, or the system did while the app was away. The capture
     * reports these on its own thread and nobody was listening, so the flag
     * above stayed true over a camera that had never started — every later
     * start handed back the same dead track, the preview stayed black, and
     * the other side was told the camera was on.
     */
    var onCameraLost: ((String) -> Unit)? = null

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) = lost("error: ${error ?: "no reason given"}")

        override fun onCameraDisconnected() = lost("disconnected")

        override fun onCameraFreezed(error: String?) {
            CallLog.write("camera frozen: ${error ?: "no reason given"}")
        }

        override fun onCameraOpening(cameraName: String?) {}

        override fun onFirstFrameAvailable() {}

        override fun onCameraClosed() {}
    }

    private fun lost(why: String) {
        CallLog.write("camera lost: $why")

        capturing = false

        onCameraLost?.invoke(why)
    }

    val codecCaps: CallCodecCaps
        get() = CallCodecs.probe(context)

    fun startMicrophone(): AudioTrack {
        microphoneTrack?.let { return it }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        val source = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack("bm-audio", source)

        audioSource = source
        microphoneTrack = track

        return track
    }

    /**
     * Whether the microphone is heard.
     *
     * This is what mute is. Taking the track off the sender is not: a call
     * muted that way went on sending Opus at thirty-five kilobits a second,
     * measured across a mute on the device, and the other side went on hearing
     * the room — while the control read muted, the other side was told so, and
     * drew the crossed-out microphone over it.
     *
     * A disabled track is silence at the source, before any of it reaches a
     * sender — whichever section ends up carrying it, and however many times
     * the peer is rebuilt underneath. It is also what a browser does for the
     * same control, so both ends of a call behave the same way.
     */
    fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneTrack?.setEnabled(enabled)
    }

    /**
     * The microphone opened again, on the other side's word that it sends
     * nothing: a fresh source and a fresh track, for the sender to take in
     * place of the ones that went quiet. The old ones are disposed of, so
     * the audio device is asked for a capture it can start over rather than
     * one it believes is already running.
     */
    fun restartMicrophone(): AudioTrack {
        val wasEnabled = microphoneTrack?.enabled() ?: true

        microphoneTrack?.dispose()
        microphoneTrack = null

        audioSource?.dispose()
        audioSource = null

        val track = startMicrophone()

        track.setEnabled(wasEnabled)

        return track
    }

    fun startCamera(front: Boolean = true): VideoTrack? {
        cameraTrack?.let { if (capturing && front == usingFrontCamera) return it }

        val enumerator = Camera2Enumerator(context)
        val name = pickCamera(enumerator, front) ?: return null

        videoCapturer?.let {
            try {
                it.stopCapture()
            } catch (e: Exception) {
                // Already stopped; nothing to undo.
            }
            it.dispose()
        }

        val capturer = enumerator.createCapturer(name, cameraEvents) ?: return null

        val source = videoSource ?: factory.createVideoSource(false)
        val helper = surfaceHelper ?: SurfaceTextureHelper.create("bm-capture", eglBase.eglBaseContext)

        videoSource = source
        surfaceHelper = helper
        videoCapturer = capturer

        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        capturing = true

        usingFrontCamera = front
        captureHeight = CAPTURE_HEIGHT

        val track = cameraTrack ?: factory.createVideoTrack("bm-video", source)
        cameraTrack = track

        return track
    }

    fun flipCamera(): VideoTrack? = startCamera(!usingFrontCamera)

    fun stopCamera() {
        capturing = false

        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            // Already stopped.
        }
    }

    /**
     * The camera opened for a device check alone: a source, a capture and a track of its own that no peer is given. The
     * call's own camera is off whenever this runs, and [stopPreview] lets it go before the call opens its own.
     */
    fun startPreview(front: Boolean): VideoTrack? {
        stopPreview()

        val enumerator = Camera2Enumerator(context)
        val name = pickCamera(enumerator, front) ?: return null

        return try {
            val capturer = enumerator.createCapturer(name, null) ?: return null
            val source = factory.createVideoSource(false)
            val helper = SurfaceTextureHelper.create("wordplus-preview", eglBase.eglBaseContext)

            capturer.initialize(helper, context, source.capturerObserver)
            // A look in a small box: 720p is plenty, and lighter on a phone already in a call.
            capturer.startCapture(1280, 720, CAPTURE_FPS)

            previewCapturer = capturer
            previewSource = source
            previewHelper = helper

            factory.createVideoTrack("wordplus-preview", source).also { previewTrack = it }
        } catch (e: Exception) {
            CallLog.write("preview camera would not start: ${e.javaClass.simpleName}: ${e.message}")
            stopPreview()
            null
        }
    }

    fun stopPreview() {
        try {
            previewCapturer?.stopCapture()
        } catch (e: Exception) {
            // Already stopped.
        }

        previewCapturer?.dispose()
        previewCapturer = null

        previewTrack?.dispose()
        previewTrack = null

        previewHelper?.dispose()
        previewHelper = null

        previewSource?.dispose()
        previewSource = null
    }

    fun stopAll() {
        stopCamera()
        stopPreview()

        capturing = false

        videoCapturer?.dispose()
        videoCapturer = null

        surfaceHelper?.dispose()
        surfaceHelper = null

        videoSource?.dispose()
        videoSource = null

        audioSource?.dispose()
        audioSource = null

        cameraTrack = null
        microphoneTrack = null
    }

    private fun pickCamera(enumerator: Camera2Enumerator, front: Boolean): String? {
        val names = enumerator.deviceNames

        return names.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
            ?: names.firstOrNull()
    }
}

/**
 * How loud a microphone is, from 16-bit samples as its audio device records them: a room's published track hands them to
 * this as a sink, the direct path's audio device as its samples callback. On the decibel scale the web reads a microphone
 * on: -60 dB is nothing, -10 dB all of it. A reading older than half a second is silence: the recording stopped.
 */
internal class CallMicLevel : AudioTrackSink {
    @Volatile
    private var last = 0f

    @Volatile
    private var at = 0L

    fun level(): Float = if (System.currentTimeMillis() - at < 500L) last else 0f

    /** Whether the audio device is recording now. */
    fun recording(): Boolean = System.currentTimeMillis() - at < 300L

    fun samples(data: ByteArray) = read(ByteBuffer.wrap(data))

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        if (bitsPerSample == 16) read(audioData.duplicate())
    }

    private fun read(buffer: ByteBuffer) {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = shorts.remaining()
        var sum = 0.0
        for (i in 0 until count) {
            val v = shorts.get(i) / 32768.0
            sum += v * v
        }
        val rms = if (count == 0) 0.0 else sqrt(sum / count)
        last = if (rms > 0) ((20 * log10(rms) + 60) / 50).coerceIn(0.0, 1.0).toFloat() else 0f
        at = System.currentTimeMillis()
    }
}
