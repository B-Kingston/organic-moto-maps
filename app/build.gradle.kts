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

tasks.register("validateTileAsset") {
    val archive = file("src/main/assets/tiles/queensland.pmtiles")
    val archiveHash = file("src/main/assets/tiles/queensland.pmtiles.sha256")
    doLast {
        if (!archive.isFile || archive.length() == 0L || !archiveHash.isFile || archiveHash.length() == 0L) {
            throw GradleException(
                "Missing app/src/main/assets/tiles/queensland.pmtiles or its SHA-256 sidecar. " +
                    "Build them with tools/tiles/build-tiles.sh before assembling the APK."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("validateGeocoderAsset", "validateTileAsset")
}

dependencies {
    implementation(libs.maplibre.android.sdk)
    implementation(libs.graphhopper.core)
    implementation(libs.slf4j.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
