import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.blokqr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blokqr.app"
        minSdk = 26                 // Android 8.0 — requis par CameraX/ML Kit et Ed25519 moderne
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // --- Signature de release (CI via secrets, ou keystore.properties local) ---
    val keystorePropsFile = rootProject.file("keystore.properties")
    val envKeystore = System.getenv("KEYSTORE_FILE")
    val hasKeystore = keystorePropsFile.exists() || envKeystore != null
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                if (keystorePropsFile.exists()) {
                    val kp = Properties().apply { load(keystorePropsFile.inputStream()) }
                    storeFile = file(kp.getProperty("storeFile"))
                    storePassword = kp.getProperty("storePassword")
                    keyAlias = kp.getProperty("keyAlias")
                    keyPassword = kp.getProperty("keyPassword")
                } else {
                    storeFile = file(envKeystore!!)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Empêche les conflits de licences/duplication des dépendances natives.
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    // --- Jetpack Compose (UI déclarative + animations) ----------------------
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // --- CameraX (flux caméra fluide) ---------------------------------------
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // --- ML Kit Barcode Scanning (on-device, multi-format) ------------------
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // --- Réseau : OkHttp + épinglage de certificat --------------------------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    // --- Vérification de signature Ed25519 (verdicts) -----------------------
    // Tink fournit une vérification Ed25519 robuste et multi-API.
    implementation("com.google.crypto.tink:tink-android:1.14.1")

    // --- Cryptographie post-quantique sur l'appareil (FIPS 203/204/205) -----
    // BouncyCastle fournit ML-DSA-65 (FIPS 204), SLH-DSA (FIPS 205) et
    // ML-KEM-768 (FIPS 203), utilisés pour la vérification hybride des verdicts,
    // l'épinglage de la racine de confiance et l'enveloppe de confidentialité.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // --- IA embarquée : classification d'usurpation (TensorFlow Lite) -------
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // --- Persistance locale (empreintes de contexte temporel) ---------------
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Tâches d'arrière-plan + notifications ------------------------------
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
