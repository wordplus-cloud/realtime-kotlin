// The Gradle plugin cloud.wordplus.realtime-ui: wordplusUiAdd copies
// the call screen's code into the app module it is applied to, and wordplusUiDiff, wordplusUiUpdate and
// wordplusUiAccept keep the copy up to date with ours. It carries the screen's sources as they are in ../ui at its build,
// at the library's version, so the copy is of the same code the app took before.
plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.1.21"
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
    coordinates("cloud.wordplus", "realtime-ui-tools", version.toString())
    pom {
        name.set("WordPlus Cloud realtime UI tools")
        description.set("The Gradle plugin that copies the call screen code into an app module, and keeps the copy up to date.")
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("realtimeUi") {
            id = "cloud.wordplus.realtime-ui"
            implementationClass = "cloud.wordplus.realtime.uitools.WordPlusUiPlugin"
            displayName = "WordPlus Cloud realtime UI: the call screen's code in your app"
        }
    }
}

// The screen's sources, as the copy has them: Better Messages' screen files and the SDK's CallScreen, with a list.
val screen = layout.buildDirectory.dir("generated/screen")
// Its instrumented tests come with it, under androidTest/, for the module's src/androidTest/kotlin.
val embedScreen by tasks.registering {
    val from = file("../ui/src/main/kotlin")
    val tests = file("../ui/src/androidTest/kotlin")
    inputs.dir(from)
    inputs.dir(tests)
    outputs.dir(screen)
    doLast {
        val root = screen.get().asFile.resolve("wordplus-ui/call-screen")
        root.deleteRecursively()
        val paths = mutableListOf<String>()
        for ((dir, prefix) in listOf(from to "", tests to "androidTest/")) {
            for (file in dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }) {
                val path = prefix + file.relativeTo(dir).invariantSeparatorsPath
                root.resolve(path).apply { parentFile.mkdirs() }.writeText(file.readText())
                paths += path
            }
        }
        root.resolve("files.txt").writeText(paths.joinToString("\n") + "\n")
        root.resolve("version.txt").writeText(project.version.toString())
    }
}

sourceSets.main {
    resources.srcDir(embedScreen.map { screen.get() })
}

tasks.test {
    useJUnitPlatform()
}
