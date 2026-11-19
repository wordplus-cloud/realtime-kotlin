package cloud.wordplus.realtime.calls

import android.content.Context
import io.livekit.android.ConnectOptions
import io.livekit.android.room.MeshApp
import io.livekit.android.room.MeshWait
import io.livekit.android.room.MeshCaps
import io.livekit.android.room.MeshLayer
import io.livekit.android.room.MeshSettingClass
import io.livekit.android.room.MeshSettings
import io.livekit.android.room.network.DefaultReconnectPolicy
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.BackupVideoCodec
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CustomVideoPreset
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoPreset169
import kotlin.time.Duration.Companion.milliseconds
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RtpParameters

/**
 * What a group call sends, by the media server's settings for its clients (the room client's [MeshSettings], from the
 * mesh's settings document): each key as the server gives it, and where it says nothing the schema's default, which is
 * what the browsers send. Before the join, the settings this device last had from the same server, else the defaults.
 */
internal object GroupShapes {
    /** This module's version, which a group call's join names to the media server (`rtc_app`, "realtime-kotlin/…"). */
    const val VERSION = "1.0.0"

    /** The settings a connect to [server] starts with: the ones its last call ended with, at most 7 days old. */
    fun cached(context: Context, server: String): MeshSettings = MeshSettings.cached(context, server) ?: MeshSettings.NONE

    /**
     * The microphone at `mic.kbps`, with `mic.dtx` and `mic.red`. Never RED into an encrypted room, which the web's room
     * client leaves RED out of by itself: the media server can't unwrap a payload it can't read, and the room client
     * sends RED wherever it is asked to.
     *
     * And with `resend`, the room client's word that its node may ask the microphone again for what its upload lost, in
     * RED too, where a node otherwise leaves a lost packet to RED's spare copy, which restores one lost alone and no run
     * of them. What is sent again goes up the same upload: a call's microphone says so because the call watches it
     * ([GroupCall]'s reviewMicCeiling) and makes that room, as the browsers' engine does (realtime-client's micPublish).
     */
    fun microphone(encrypted: Boolean, settings: MeshSettings) = AudioTrackPublishDefaults(
        audioBitrate = (settings.int("mic.kbps") ?: 64) * 1000,
        dtx = settings.bool("mic.dtx") ?: true,
        red = (settings.bool("mic.red") ?: true) && !encrypted,
        resend = true,
    )

    /** The microphone's processing: `mic.noiseSuppression`, `mic.echoCancellation`, `mic.autoGainControl`. */
    fun microphoneCapture(settings: MeshSettings) = LocalAudioTrackOptions(
        noiseSuppression = settings.bool("mic.noiseSuppression") ?: true,
        echoCancellation = settings.bool("mic.echoCancellation") ?: true,
        autoGainControl = settings.bool("mic.autoGainControl") ?: true,
    )

    /** The camera opened at `camera.captureHeight` lines, 16:9, and `camera.captureFps`. */
    fun cameraCapture(settings: MeshSettings): VideoCaptureParameter {
        val lines = settings.int("camera.captureHeight") ?: 1080
        val preset = when {
            lines < 450 -> VideoPreset169.H360
            lines < 630 -> VideoPreset169.H540
            lines < 900 -> VideoPreset169.H720
            else -> VideoPreset169.H1080
        }
        return VideoCaptureParameter(preset.capture.width, preset.capture.height, settings.int("camera.captureFps") ?: 30)
    }

    /**
     * The camera by the lines it is captured at, the shorter side, so a phone held upright counts the same. From 720 lines
     * the top layer at `camera.topKbps.<codec>.<1080|720>` and `camera.fps`, and under it the layers of
     * `camera.ladder.1080` or `camera.ladder.720` (each at its own frame rate, where the library's own lower layers run at
     * 20, which a tile shows as stutter). Below 720 lines, the library's own layers. `camera.degradation` says what the
     * encoder gives up first.
     */
    fun camera(codec: String, backup: String?, lines: Int, settings: MeshSettings): VideoTrackPublishDefaults {
        val degradation = degradation(settings.word("camera.degradation"))
        // an SVC codec's layers as `camera.svcMode` says them; any other codec goes out in simulcast
        val svc = svcMode(codec, settings)
        if (lines < 720) {
            return VideoTrackPublishDefaults(
                videoCodec = codec,
                scalabilityMode = svc,
                backupCodec = backup?.let { BackupVideoCodec(codec = it) },
                degradationPreference = degradation,
            )
        }
        val full = lines >= 1080
        val size = if (full) "1080" else "720"
        val fps = settings.int("camera.fps") ?: 30
        fun topFor(name: String): VideoEncoding {
            val own = if (name == "vp8") (if (full) 4000 else 2000) else (if (full) 3500 else 1800)
            return VideoEncoding((settings.int("camera.topKbps.$name.$size") ?: own) * 1000, fps)
        }
        val ladder = settings.layers(if (full) "camera.ladder.1080" else "camera.ladder.720") ?: emptyList()
        // only the layers under the top one: a ladder's layer as tall as the camera would stand in for it
        val layers = ladder.filter { it.height < lines }.map { layer(it) }
        return VideoTrackPublishDefaults(
            videoEncoding = topFor(codec),
            videoCodec = codec,
            scalabilityMode = svc,
            backupCodec = backup?.let { BackupVideoCodec(codec = it, encoding = topFor(it)) },
            degradationPreference = degradation,
            simulcastLayers = layers,
        )
    }

    /** The scalability mode of a camera in an SVC codec (VP9, AV1), `camera.svcMode`; null for any other codec. */
    fun svcMode(codec: String, settings: MeshSettings): String? =
        if (codec.lowercase() == "vp9" || codec.lowercase() == "av1") settings.word("camera.svcMode") ?: "L3T3_KEY" else null

    /**
     * The camera's publish settings in one string (`camera.*` of the publish class), to tell whether a running camera
     * went out under the ones in force now.
     */
    fun cameraPrint(settings: MeshSettings): String =
        settings.values.filter { it.key.startsWith("camera.") && MeshSettings.keyClass(it.key) == MeshSettingClass.PUBLISH }
            .map { "${it.key}=${it.value}" }.sorted().joinToString(";")

    /** Whether a change touches what a camera or screen takes at its next publish: what `applyNow` re-publishes for. */
    fun touchesPublish(keys: Set<String>): Boolean = keys.any { MeshSettings.keyClass(it) == MeshSettingClass.PUBLISH }

    /**
     * The tallest camera this device sends in the codec it encodes it in, `camera.maxHeight.hw` or `camera.maxHeight.sw`,
     * as a cap for the room client ([io.livekit.android.room.Room.setProductCameraCaps]): none when the capture is no
     * taller.
     */
    fun cameraCap(hardware: Boolean, lines: Int, settings: MeshSettings): MeshCaps {
        val most = settings.int(if (hardware) "camera.maxHeight.hw" else "camera.maxHeight.sw") ?: lines
        return MeshCaps(height = if (most < lines) most else 0)
    }

    /**
     * That cap with the ceiling a step of the picture puts on the camera for the sound's sake ([CallPictureSteps]): the
     * stricter of each, and the cap alone at step 0.
     */
    fun cameraCap(cap: MeshCaps, step: Int): MeshCaps {
        val caps = CallPictureSteps.caps(step) ?: return cap
        return cap.combined(MeshCaps(height = caps.height, fps = caps.fps, kbps = caps.kbps))
    }

    /**
     * The camera's codec list for this device: the first of `camera.codecRules` whose given fields all match it (an
     * Android phone or tablet, no browser; a rule naming a GPU maker matches none, the chip's maker not being known
     * here), else `camera.codecs`.
     */
    fun cameraCodecs(settings: MeshSettings, tablet: Boolean): List<String>? {
        val device = if (tablet) "tablet" else "phone"
        settings.codecRules("camera.codecRules")?.firstOrNull { rule ->
            (rule.os == null || rule.os == "android") && rule.browser == null && rule.gpu == null &&
                (rule.device == null || rule.device == device)
        }?.let { return it.camera }
        return settings.names("camera.codecs")
    }

    /**
     * The connect's options the settings own: the signal connection's and the media connection's timeouts and how long
     * the join may wait for a place on the mesh. Media through TURN only (`ice.transportPolicy`) comes as LiveKit's own
     * force_relay with the join, which the room client takes itself.
     */
    fun connectOptions(settings: MeshSettings, iceServers: List<PeerConnection.IceServer>, caps: CallCodecCaps, onMeshWait: ((MeshWait) -> Unit)? = null) = ConnectOptions(
        iceServers = iceServers.takeIf { it.isNotEmpty() },
        meshSignalTimeoutMs = settings.int("connect.signalTimeoutMs")?.toLong(),
        meshPcTimeoutMs = settings.int("connect.pcTimeoutMs")?.toLong(),
        meshJoinBudgetMs = (settings.int("connect.joinBudgetMs") ?: 300_000).toLong(),
        meshHardwareDecoders = CallCodecs.hardwareDecoders(caps),
        onMeshWait = onMeshWait,
        meshHardwareEncoders = CallCodecs.hardwareEncoders(caps),
        meshApp = MeshApp("realtime-kotlin", VERSION),
    )

    /** The reconnect waits, `reconnect.delaysMs`. */
    fun reconnectPolicy(settings: MeshSettings): DefaultReconnectPolicy? =
        settings.reconnectDelaysMs?.takeIf { it.isNotEmpty() }?.let { delays -> DefaultReconnectPolicy(retryDelays = delays.map { it.milliseconds }) }

    /** The degradation preference a word names, the library's own (null) for `default`. */
    fun degradation(word: String?): RtpParameters.DegradationPreference? = when (word) {
        "balanced" -> RtpParameters.DegradationPreference.BALANCED
        "maintain-framerate" -> RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        "maintain-resolution" -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        else -> null
    }

    private fun layer(layer: MeshLayer): CustomVideoPreset {
        val width = 16 * layer.height / 9
        return CustomVideoPreset(VideoCaptureParameter(width + width % 2, layer.height, layer.fps), VideoEncoding(layer.kbps * 1000, layer.fps))
    }
}
