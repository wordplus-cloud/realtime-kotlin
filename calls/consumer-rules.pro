# The call engine is reached through reflection by nothing but the node's codec policy's numbers, which CameraSwitch.tune
# reads by name (the WordPlus RTC client for Android has them from 1.0.6 on); libwebrtc keeps its own rules.
-keepclassmembers class io.livekit.android.room.MeshCodecPolicy {
    *** getTune();
}
