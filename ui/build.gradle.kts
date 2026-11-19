// The call screen on the SDK's calls: the ring, the call between two and the group call's grid, with their controls, in
// an activity of its own. The Calls* files (cloud.wordplus.realtime.ui.calls) are the screen; CallsHost.kt answers what
// they ask of an app, and CallScreen drives them from cloud.wordplus:realtime-calls.
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
    coordinates("cloud.wordplus", "realtime-ui", version.toString())
    pom {
        name.set("WordPlus Cloud realtime UI")
        description.set("The call screen and the live stream screen of WordPlus Cloud, drawn ready, for Android apps.")
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
    moduleName.set("realtime-ui")
    pluginsConfiguration.html { footerMessage.set("WordPlus Cloud") }
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        // The screen's own files are the module's insides: CallScreen is its API.
        perPackageOption {
            matchingRegex.set("cloud\\.wordplus\\.realtime\\.ui\\.calls.*")
            suppress.set(true)
        }
    }
}

android {
    namespace = "cloud.wordplus.realtime.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // The screen's pieces on a device with no call (src/androidTest): ./gradlew connectedDebugAndroidTest.
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
    api("cloud.wordplus:realtime-calls:1.0.0")
    // The room client the calls run on (calls/build.gradle.kts), which the screens draw with.
    implementation("cloud.wordplus:rtc-client-android:1.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // The screens' plain decisions, on the JVM (src/test): ./gradlew testDebugUnitTest.
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
