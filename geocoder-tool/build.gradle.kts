import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// NOTE: no jvmToolchain(17) here on purpose. Gradle's Homebrew auto-detection
// resolves JAVA_HOME=/opt/homebrew/opt/openjdk@17 to the keg root, which lacks
// lib/jmods, so Kotlin fails with "No class roots are found in the JDK path".
// :app has the same setup (compileOptions + jvmTarget, no toolchain) and works:
// the daemon JVM's java.home resolves through the bin/java symlink to the
// complete libexec/openjdk.jdk bundle.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.organicmoto.geocoder.tool.GeocoderIndexBuilderKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.named<JavaExec>("run") {
    // Resolve relative output paths (e.g. "app/src/main/assets/geocoder") from
    // the repo root, matching the documented pipeline command.
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(libs.osmosis)
    implementation(libs.osmosis.core)
    testImplementation(libs.junit4)
}
