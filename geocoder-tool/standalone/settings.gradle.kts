// Standalone Gradle build for :geocoder-tool.
//
// Why: the repo's root build includes the Android application, so any Gradle
// invocation from the root needs an Android SDK even when it only wants the
// geocoder. The map server's deployment image has a JVM but no Android SDK, so
// it builds the geocoder through this settings file instead:
//
//   ./gradlew -p geocoder-tool/standalone installDist
//
// The project reuses the exact same build script, sources, resources, and
// version catalog as the root build — no duplicated tool logic.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "curveMaps-geocoder-standalone"

include(":geocoder-tool")
project(":geocoder-tool").projectDir = file("..")
