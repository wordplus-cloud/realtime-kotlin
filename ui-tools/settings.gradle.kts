// `./gradlew wordplusUiAdd`: Better Messages' call screen copied into an app module, to change as far as it needs
// (cloud.wordplus.realtime-ui, a Gradle plugin of its own beside cloud.wordplus:realtime-ui, which it carries the sources of).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "realtime-ui-tools"
