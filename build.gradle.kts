// WordPlus Cloud's realtime SDK for Android and other Kotlin apps: rooms, events with
// acks, presence, live counts, history, who is online and what the app's server sends the user, on the same realtime
// servers and protocol as the browser's, sealed end to end. A plain Kotlin/JVM library, which Android apps take as it is.
plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    `java-library`
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
    coordinates("cloud.wordplus", "realtime", version.toString())
    pom {
        name.set("WordPlus Cloud realtime")
        description.set("The realtime SDK of WordPlus Cloud for Kotlin and Android apps: rooms, events with acks, presence, live counts, history, who is online and what the app server sends the user.")
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
    moduleName.set("realtime")
    pluginsConfiguration.html { footerMessage.set("WordPlus Cloud") }
    dokkaSourceSets.main {
        includes.from("Module.md")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // The live tests read where the API demo runs (realtime-js: node test/native/serve.mjs).
    environment("WORDPLUS_SHOP", System.getenv("WORDPLUS_SHOP") ?: "")
    environment("WORDPLUS_CONTROL", System.getenv("WORDPLUS_CONTROL") ?: "")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
