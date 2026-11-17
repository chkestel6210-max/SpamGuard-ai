// Configuration du module "app" : c'est ici qu'on dit à Gradle
// comment compiler l'application Android.
import java.util.Base64

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
        versionCode = 7
        versionName = "0.2.5"
    }

    // ─── CLÉ DE SIGNATURE FIXE (leçon du 17/11) ───
    // Android REFUSE de mettre à jour une application si la signature de
    // l'APK change ("Application non installée"). Or, par défaut, chaque
    // machine génère sa propre clé "debug"... et les runners GitHub Actions
    // sont jetables : chaque build de la CI était donc signé avec une clé
    // différente. Impossible de mettre à jour par-dessus une version !
    //
    // Solution : UNE clé pour TOUS les builds, locaux comme CI. Elle est
    // stockée ici en base64 (app/spamguard.keystore.b64, ~3 Ko) et décodée
    // à chaque compilation. Pour une app personnelle open source c'est
    // acceptable ; une app bancaire garderait sa clé secrète, elle.
    val keystoreB64 = File(rootDir, "app/spamguard.keystore.b64")
    val keystoreFile = File(project.layout.buildDirectory.get().asFile, "spamguard.keystore")
    val stableKeyAvailable = if (keystoreB64.exists()) {
        keystoreFile.parentFile.mkdirs()
        keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreB64.readText().trim()))
        true
    } else {
        // Fichier absent (clone incomplet ?) : on retombe sur la clé debug
        // par défaut, pour ne pas empêcher un curieux de compiler.
        false
    }

    signingConfigs {
        if (stableKeyAvailable) {
            create("stable") {
                storeFile = keystoreFile
                storePassword = "spamguard2026"
                keyAlias = "spamguard"
                keyPassword = "spamguard2026"
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeyAvailable) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (stableKeyAvailable) signingConfig = signingConfigs.getByName("stable")
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
