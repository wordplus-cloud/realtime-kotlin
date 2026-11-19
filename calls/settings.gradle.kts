// Calls for cloud.wordplus:realtime, as an Android library of its own (cloud.wordplus:realtime-calls), so an app that
// only uses rooms carries no WebRTC. The core comes from this checkout (includeBuild below).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // The room client, cloud.wordplus:rtc-client-android, from this checkout's copy (an app takes it from Maven
        // Central).
        maven {
            url = settingsDir.resolve("../vendor/rtc-client-android").toURI()
            content { includeModule("cloud.wordplus", "rtc-client-android") }
        }
        google()
        mavenCentral()
        // The room client, LiveKit's Android SDK, pulls a few artifacts from JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "realtime-calls"

includeBuild("..")
