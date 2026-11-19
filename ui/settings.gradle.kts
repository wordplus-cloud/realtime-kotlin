// Better Messages' call screen for cloud.wordplus:realtime-calls, as an Android library of its own
// (cloud.wordplus:realtime-ui), so an app that draws its own screens carries none of it. The core and the calls come
// from this checkout (includeBuild below).
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

rootProject.name = "realtime-ui"

includeBuild("..")
includeBuild("../calls")
