plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yann.nowbarmirror.wear"
    compileSdk = 36

    defaultConfig {
        // DOIT rester identique à l'applicationId du module app/ (téléphone) :
        // la Wear Data Layer API n'échange des données qu'entre un téléphone
        // et une montre qui partagent le même nom de package ET la même clé
        // de signature (voir la note ci-dessous sur signingConfigs, et
        // README.md, section "Fusion avec Sport Watch Complication").
        applicationId = "com.yann.nowbarmirror"
        minSdk = 30 // Wear OS 4+
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Même keystore (même variable d'environnement DEBUG_KEYSTORE_PATH,
        // alimentée par le secret DEBUG_KEYSTORE_B64) que app/build.gradle.kts
        // — volontairement PAS un keystore séparé committé dans le repo comme
        // le faisait l'ancien projet Sport Watch Complication (wear/debug.keystore) :
        // la Wear Data Layer API exige que le téléphone et la montre soient
        // signés avec la MÊME clé, donc les deux modules doivent utiliser
        // exactement le même fichier de clé à chaque build CI.
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.3.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    // NEW 21/09/2026, écran de détail plein écran de la complication "Notification" (voir
    // NotificationDetailActivity.kt) : SwipeDismissFrameLayout (geste de balayage standard Wear OS
    // pour fermer un écran, comme les apps natives Galaxy Watch) + BoxInsetLayout (garde le contenu
    // dans la zone "sûre" d'un écran rond). Bibliothèque de Views classique (PAS Compose) pour
    // rester cohérent avec le reste de cette app (aucun module ici n'utilise Compose) et éviter
    // d'introduire le plugin compilateur Compose dans une CI qui ne peut pas être testée localement
    // (voir README, section Build : uniquement GitHub Actions puis installation manuelle).
    implementation("androidx.wear:wear:1.3.0")
}
