# --- Sérialisation kotlinx ------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.blokqr.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.blokqr.app.model.**$$serializer { *; }
-keepclassmembers class com.blokqr.app.model.** {
    *** Companion;
}

# --- CRYPTO POST-QUANTIQUE : NE PAS supprimer/obfusquer (CRITIQUE) ---------
# BouncyCastle charge des algorithmes par réflexion ; R8 casserait ML-DSA /
# SLH-DSA / ML-KEM sans ces règles -> la vérification hybride échouerait.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.pqc.** { *; }

# --- Tink (Ed25519) -------------------------------------------------------
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**

# --- OkHttp / Okio --------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- TensorFlow Lite ------------------------------------------------------
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
