plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.security.MessageDigest

// SHA-256 of the packaged geocoder index, computed at build time so the app can
// detect a stale/foreign on-device cache by hashing only the cached file (the
// packaged asset is immutable and its hash is known — no per-load asset hash).
// Empty when the asset is absent (fresh clone); the app then reports the usual
// "asset missing" error at load time.
val geocoderSha256: String = run {
    val asset = file("src/main/assets/geocoder/geocoder.dat")
    if (!asset.isFile || asset.length() == 0L) {
        ""
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
        asset.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

android {
    namespace = "com.organicmoto.maps"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.organicmoto.maps"
        minSdk = 26
        targetSdk = 35
        // Packaging-time overrides for the local F-Droid test repo
        // (tools/fdroid-repo/publish.sh); defaults keep normal builds intact.
        versionCode = findProperty("moto.versionCode")?.toString()?.toIntOrNull() ?: 1
        versionName = findProperty("moto.versionName")?.toString() ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEOCODER_SHA256", "\"$geocoderSha256\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The geocoder index is generated from the selected OSM extract and is
// intentionally gitignored. Do not allow an APK to be built successfully while
// silently omitting the offline place-search feature.
tasks.register("validateGeocoderAsset") {
    val index = file("src/main/assets/geocoder/geocoder.dat")
    doLast {
        if (!index.isFile || index.length() == 0L) {
            throw GradleException(
                "Missing app/src/main/assets/geocoder/geocoder.dat. " +
                    "Build it with :geocoder-tool before assembling the APK."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("validateGeocoderAsset")
}

// MapLibre 13.5.0 publishes one rendering backend per artifact and the
// published default (`org.maplibre.gl:android-sdk`) is Vulkan-only: its
// RenderingEngine returns VULKAN and rejects any runtime switch. The Android
// emulator rasterizes Vulkan through gfxstream/llvmpipe in software (measured:
// `cmd gpu vkjson` reports llvmpipe for the dense Brisbane CBD style) while its
// OpenGL ES path is translated onto the host GPU (measured: "Android Emulator
// OpenGL ES Translator (Apple M4 Pro)"), so debug builds default to the
// genuine OpenGL artifact and release keeps the published Vulkan default.
// `-PmaplibreBackend=vulkan|opengl` forces one backend for both variants, which
// is how the A/B benchmark in tools/test/visual.py builds its baseline.
val maplibreBackendOverride: String? = (findProperty("maplibreBackend") as String?)?.lowercase()
fun maplibreBackendFor(variant: String): String = when (maplibreBackendOverride) {
    null -> if (variant == "debug") "opengl" else "vulkan"
    "opengl", "vulkan" -> maplibreBackendOverride
    else -> throw GradleException(
        "Unknown maplibreBackend '$maplibreBackendOverride'; use 'vulkan' or 'opengl'.",
    )
}

dependencies {
    add(
        "debugImplementation",
        if (maplibreBackendFor("debug") == "opengl") libs.maplibre.android.sdk.opengl else libs.maplibre.android.sdk,
    )
    add(
        "releaseImplementation",
        if (maplibreBackendFor("release") == "opengl") libs.maplibre.android.sdk.opengl else libs.maplibre.android.sdk,
    )
    implementation(libs.graphhopper.core)
    implementation(libs.slf4j.android)

    implementation(platform(libs.compose.bom))
    testImplementation(libs.kxml)
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Saved-route storage tests: pure JVM suites for the codec/projection/
    // similarity/repository logic, instrumented suites for the real SQLite.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Espresso 3.5 reflectively calls InputManager.getInstance(), removed on API 35.
    // Pin the API-35-compatible release for Compose and AndroidX test rules.
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.rules)
    testImplementation(libs.org.json)
}
