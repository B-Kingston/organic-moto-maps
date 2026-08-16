plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.organicmoto.maps"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.organicmoto.maps"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
