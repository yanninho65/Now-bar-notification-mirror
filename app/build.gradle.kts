plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yann.nowbarmirror"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yann.nowbarmirror"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            System.getenv("DEBUG_KEYSTORE_PATH")?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Requis par l'onglet Sport (MainActivity, MatchesAdapter) — voir README.md, section
    // "Fusion avec Sport Watch Complication".
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.13.0")

    // Onglet Sport (voir README.md, section "Fusion avec Sport Watch Complication") :
    // lifecycleScope (MainActivity), coroutines (ApiOverrideFollowService, SportsDbApi,
    // LiveTennisApi), et l'envoi des scores à la montre via la Wear Data Layer API (WatchSync).
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
