// The WordPlus RTC client for Android (cloud.wordplus:rtc-client-android), the room client under the calls, on Maven
// Central with each release, as it was built: vendor/rtc-client-android's AAR and sources, and its POM's dependencies,
// under WordPlus's coordinates and signature, so an app that takes realtime-calls finds it there. It is LiveKit's
// Android SDK 2.29.1 with WordPlus Mesh's patch (the patch and LiveKit's licence beside it in vendor/), Apache License
// 2.0. scripts/rtc-client.sh copies a new build in; its VERSION is this publication's version.
//
// ./gradlew publishAllPublicationsToCheckRepository writes what a release would publish into build/check.
plugins {
    base
    id("com.vanniktech.maven.publish.base") version "0.34.0"
}

val client = rootDir.resolve("../vendor/rtc-client-android")
val clientVersion = client.resolve("VERSION").readText().trim()
val built = client.resolve("cloud/wordplus/rtc-client-android/$clientVersion")
// LiveKit's release the client is built on, which its patch is named after.
val upstream = client.list().orEmpty().firstNotNullOf { Regex("rtc-client-android-(.+)\\.patch").matchEntire(it)?.groupValues?.get(1) }

group = "cloud.wordplus"
version = clientVersion

// Maven Central takes a Javadoc jar beside each artifact. The client's API is LiveKit's, documented there, so this
// one is empty.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "rtc-client-android"
            artifact(built.resolve("rtc-client-android-$clientVersion.aar"))
            artifact(built.resolve("rtc-client-android-$clientVersion-sources.jar")) { classifier = "sources" }
            artifact(javadocJar)
            pom {
                packaging = "aar"
                // The build's own dependencies, as its POM lists them.
                withXml {
                    val from = groovy.xml.XmlParser(false, false).parse(built.resolve("rtc-client-android-$clientVersion.pom"))
                    val into = asNode().appendNode("dependencies")
                    fun copy(node: groovy.util.Node, parent: groovy.util.Node) {
                        val copied = parent.appendNode(node.name())
                        for (child in node.children()) {
                            if (child is groovy.util.Node) copy(child, copied) else copied.setValue(child.toString())
                        }
                    }
                    for (list in from.get("dependencies") as groovy.util.NodeList) {
                        for (dependency in (list as groovy.util.Node).children()) {
                            if (dependency is groovy.util.Node) copy(dependency, into)
                        }
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "check"
            url = uri(layout.buildDirectory.dir("check"))
        }
    }
}

// Maven Central, from the release workflow of the public repository (.github/workflows/release.yml), as the other
// builds: signed with the key it hands in.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    pom {
        name.set("WordPlus RTC client for Android")
        description.set("LiveKit's Android SDK $upstream with WordPlus Mesh's changes: the room client under cloud.wordplus:realtime-calls.")
        inceptionYear.set("2026")
        url.set("https://docs.wordplus.cloud/android/")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("livekit")
                name.set("LiveKit")
                url.set("https://github.com/livekit/client-sdk-android")
            }
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
