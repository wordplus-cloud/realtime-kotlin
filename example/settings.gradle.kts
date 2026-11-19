// The API demo as an Android app, on cloud.wordplus:realtime from this checkout (includeBuild below, an app of your
// own takes it from Maven Central).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // The room client under the calls, cloud.wordplus:rtc-client-android, from this checkout's copy (an app of
        // your own takes it from Maven Central).
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

rootProject.name = "example-shop"

includeBuild("..")
includeBuild("../calls")
includeBuild("../ui")
include(":app")
