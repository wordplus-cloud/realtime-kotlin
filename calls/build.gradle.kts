// Calls between an app's users on WordPlus Cloud: the call engine (its peer, codecs, shapes and media), driven by the
// SDK's own session and ring layer on the realtime connection, so a phone and a browser call each other.
plugins {
    id("com.android.library") version "8.13.0"
    kotlin("android") version "2.1.21"
    // The API reference (./gradlew dokkaGenerate, into build/dokka/html), which docs.wordplus.cloud serves.
    id("org.jetbrains.dokka") version "2.0.0"
    // Maven Central, below.
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "cloud.wordplus"
version = "1.0.0"

// Maven Central, from the release workflow of the public repository (.github/workflows/release.yml): signed with the key it
// hands in, and ./gradlew publishToMavenLocal without one.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("cloud.wordplus", "realtime-calls", version.toString())
    pom {
        name.set("WordPlus Cloud realtime calls")
        description.set("Calls, group calls and live streams on WordPlus Cloud, for Android apps.")
        inceptionYear.set("2026")
        url.set("https://docs.wordplus.cloud/android/")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("wordplus")
                name.set("WordPlus")
                email.set("support@wordplus.cloud")
                url.set("https://www.wordplus.cloud")
            }
        }
        scm {
            url.set("https://github.com/wordplus-cloud/realtime-kotlin")
            connection.set("scm:git:https://github.com/wordplus-cloud/realtime-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/wordplus-cloud/realtime-kotlin.git")
        }
    }
}

dokka {
    moduleName.set("realtime-calls")
    pluginsConfiguration.html { footerMessage.set("WordPlus Cloud") }
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
    }
}

android {
    namespace = "cloud.wordplus.realtime.calls"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // The live stream's state from its chat socket's events, on a device or an emulator (LiveStreamTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("cloud.wordplus.realtime.WordPlusModulesApi")
    }
}

dependencies {
    api("cloud.wordplus:realtime:1.0.0")
    // The same libwebrtc LiveKit's Android SDK runs on.
    api("io.github.webrtc-sdk:android-prefixed:150.7871.01")
    // The rooms on the mesh a group call, a call someone was added to, the fallback and a live stream run in: the
    // WordPlus RTC client for Android, LiveKit's Android SDK 2.29.1 with WordPlus Mesh's patch (vendor/rtc-client-android,
    // published with this release by rtc-client/).
    implementation("cloud.wordplus:rtc-client-android:1.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Telecom, which makes a call a call to the system (SystemCalls): its audio routes, a cellular call holding it, and
    // a car or a watch answering it.
    implementation("androidx.core:core-telecom:1.0.0")

    // The calls' plain rules (CameraSwitch) on the JVM, with no device.
    testImplementation("junit:junit:4.13.2")
    // org.json as it runs on a device, for what a quality reading says (CallQualityWatch): android.jar's copy there is stubs.
    testImplementation("org.json:json:20250517")
    // What the compiled classes do when a suspending function returns nothing (SuspendUnitTest).
    testImplementation("org.ow2.asm:asm-tree:9.8")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
