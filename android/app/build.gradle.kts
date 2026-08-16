import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.blokqr.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.blokqr.app"
        minSdk = 29                 // Android 10 — recommandation MASVS/Play : TLS 1.3 par défaut, scoped storage, meilleurs défauts crypto
        targetSdk = 36
        versionCode = 22            // 2.0.8 — Doit rester STRICTEMENT superieur au plus haut versionCode deja envoye sur Play
        versionName = "2.0.9"
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
            // Symboles de debogage NATIFS (BouncyCastle, ML Kit, CameraX, etc.) :
            // rend les plantages natifs lisibles dans Play Console / Android Vitals
            // et leve l'avertissement « code natif sans symboles de debogage ».
            // "FULL" = noms de fonctions + numeros de ligne ("SYMBOL_TABLE" = noms seuls).
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Compose + BuildConfig (fusion des deux blocs buildFeatures precedents).
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Empeche les conflits de licences/duplication des dependances natives.
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    bundle {
        language {
            enableSplit = false
        }
    }
}
// jvmTarget migre de l'ancien `kotlinOptions { }` (deprecie) vers le DSL
// `compilerOptions` de l'extension Kotlin. Comportement identique (cible JVM 17),
// sans l'avertissement de depreciation.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    // --- Jetpack Compose (UI declarative + animations) ----------------------
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
	implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    // --- CameraX (flux camera fluide) ---------------------------------------
    // 1.4.x : bibliotheques natives alignees 16 Ko (libimage_processing_util_jni.so,
    // libsurface_util_jni.so). NE PAS redescendre en 1.3.x (non aligne -> rejet Play).
    // ⚠️ NE PAS ajouter androidx.camera:camera-mlkit-vision : ses composants ML Kit
    //    internes (vision-interfaces/common) entrent en conflit avec
    //    com.google.mlkit:barcode-scanning et cassent silencieusement la detection.
    //    La detection passe par BarcodeAnalyzer (ImageAnalysis classique).
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    // --- ML Kit Barcode Scanning (on-device, multi-format) ------------------
    // 17.3.0 / 16.0.1 : versions alignees 16 Ko. Si le diagnostic signale encore
    // un .so ML Kit, basculer sur les variantes Play services « unbundled »
    // (com.google.android.gms:play-services-mlkit-barcode-scanning / -text-recognition).
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // --- Reseau : OkHttp + epinglage de certificat --------------------------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    // --- Verification de signature Ed25519 (verdicts) -----------------------
    // Tink fournit une verification Ed25519 robuste et multi-API.
    implementation("com.google.crypto.tink:tink-android:1.14.1")
    // --- Cryptographie post-quantique sur l'appareil (FIPS 203/204/205) -----
    // BouncyCastle fournit ML-DSA-65 (FIPS 204), SLH-DSA (FIPS 205) et
    // ML-KEM-768 (FIPS 203), utilises pour la verification hybride des verdicts,
    // l'epinglage de la racine de confiance et l'enveloppe de confidentialite.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    // --- IA embarquee : classification d'usurpation (runtime TFLite Play services) ---
    // ⚠️ NE PAS revenir a org.tensorflow:tensorflow-lite : son libtensorflowlite_jni.so
    //    n'est PAS aligne 16 Ko (aucune version Maven ne l'est) -> rejet Play.
    //    Ici le moteur est fourni par Google Play services (aucun .so embarque) :
    //    PhishingClassifier utilise InterpreterApi + TfLite.initialize(FROM_SYSTEM_ONLY).
    implementation("com.google.android.gms:play-services-tflite-java:16.5.0")
    // await() sur les Task Google Play services (init TFLite asynchrone).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // --- Persistance locale (empreintes de contexte temporel) ---------------
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // --- Stockage chiffre du secret HMAC d'installation (Paquet 2/3) --------
    // EncryptedSharedPreferences : chiffrement AES-256 GCM via Android Keystore
    // (StrongBox si disponible). Sert a proteger install_id + hmac_secret_b64
    // emis par POST /v1/install, utilises pour signer chaque appel /v1/analyze*.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // --- Taches d'arriere-plan + notifications ------------------------------
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // --- Abonnement Pro : Google Play Billing v8 ----------------------------
    // ⚠️ Rester en 8.x : BlokQrBilling.kt utilise l'API v8. Billing 9.x change
    //    l'API et casserait la compilation.
    implementation("com.android.billingclient:billing-ktx:8.3.0")
    // --- Anti-abus : Google Play Integrity API ------------------------------
    // Attestation de l'appareil + de l'app au moment du provisionnement initial.
    implementation("com.google.android.play:integrity:1.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // --- Avis in-app (Google Play In-App Review) ---------------------------
    implementation("com.google.android.play:review-ktx:2.0.1")
    // ===== v2.0.0 — Générateur de codes (onglet Créer) =====
    // Encodeur QR / 2D / 1D, pur Java, hors-ligne (aucun .so -> pas de souci 16 Ko).
    implementation("com.google.zxing:core:3.5.3")
    // PrintHelper (impression du bitmap généré).
    implementation("androidx.print:print:1.0.0")
    // ===== v2.0.0 — Champ téléphone intelligent (hors-ligne) =====
    // Port Android de libphonenumber : métadonnées dans les assets (pur Java,
    // compatible R8, aucun .so). Le -keepresourcefiles ne concerne que DexGuard.
    implementation("io.michaelrocks:libphonenumber-android:9.0.30")
    // ===== v2.0.0 — Bouton « Ma position actuelle » (GPS, hors-ligne) =====
    // FusedLocationProviderClient (Play services « unbundled », aucun .so embarqué) ;
    // les coordonnées ne quittent jamais l'appareil.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // --- Tests unitaires (JVM) ----------------------------------------------
    testImplementation("junit:junit:4.13.2")
    // org.json est un stub non fonctionnel en test JVM : on fournit l'implementation
    // reelle, utilisee par VerdictBindingTest via VerdictVerifier.canonicalMatches.
    testImplementation("org.json:json:20240303")
}