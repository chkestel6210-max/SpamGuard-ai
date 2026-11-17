// Configuration du module "app" : c'est ici qu'on dit à Gradle
// comment compiler l'application Android.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Nom du paquet Java/Kotlin : unique sur le Play Store.
    namespace = "com.kestel.spamguard"

    compileSdk = 34

    defaultConfig {
        applicationId = "com.kestel.spamguard"
        // Android 10 minimum : nécessaire pour le filtrage d'appels (CallScreeningService).
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Bibliothèques de base Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines : pour lancer des requêtes réseau sans bloquer l'interface
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp : le client HTTP qui appelle l'API de l'IA
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager : mise à jour hebdomadaire de la base offline en arrière-plan
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
