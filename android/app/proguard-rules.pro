# ============================================================================
# BlokQR - regles R8 (release) - version RESSERREE (a partir de 2.0.7).
#
# Objectif : relever les taux d'optimisation/obfuscation/reduction signales
# par Play SANS toucher aux chemins critiques. Principe : ne garder par regle
# explicite QUE ce qui est charge par REFLEXION ; tout ce qui est reference
# directement est conserve automatiquement par R8.
#
# Changements majeurs vs version precedente :
#  - BouncyCastle : keep limite au PROVIDER JCA (charge par noms de classes)
#    au lieu de la bibliotheque entiere.
#  - kotlinx.serialization : regles CONDITIONNELLES limitees aux classes
#    @Serializable (la lib >= 1.5 embarque en plus ses consumer rules).
#    Le keep global "Companion" sur com.blokqr.app.** est SUPPRIME : le code
#    applicatif redevient optimisable et obfuscable (important pour une app
#    de securite).
#  - ML Kit / Tink : blankets supprimes (consumer rules livrees dans les AAR).
# Chaque section indique le SYMPTOME en cas de casse et l'antidote.
# ============================================================================

# --- Attributs requis (reflexion, serialisation, annotations) --------------
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# --- kotlinx.serialization (regles officielles, conditionnees) -------------
# Symptome si casse : historique des scans/creations illisible, erreurs de
# (de)serialisation des reponses API. Antidote : rien a elargir normalement,
# ces regles + les consumer rules de la lib couvrent tous les cas documentes.
-dontnote kotlinx.serialization.**
# Serialiseurs generes des SEULES classes @Serializable.
-if @kotlinx.serialization.Serializable class **
-keep,includedescriptorclasses class <1>$$serializer { *; }
# Champ Companion des classes @Serializable.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# serializer() sur le Companion des classes @Serializable.
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
# serializer() des objets @Serializable (singletons).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Enums ------------------------------------------------------------------
# values()/valueOf() sont utilises par la serialisation et la reflexion.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Verdict est PERSISTE par son nom (verdict.name) dans l'historique local puis
# relu via Verdict.valueOf(...) : noms de constantes STABLES entre versions.
-keepclassmembers enum com.blokqr.app.model.Verdict { *; }

# --- CRYPTO POST-QUANTIQUE : BouncyCastle (CRITIQUE, keep RESSERRE) ---------
# Le provider JCA enregistre ses algorithmes par NOMS DE CLASSES (reflexion) :
# ces classes vivent sous jcajce.provider (classique) et pqc.jcajce.provider
# (ML-DSA / SLH-DSA / ML-KEM). Les couches basses (asn1, math, crypto) sont
# referencees DIRECTEMENT par ces Spi et survivent donc a l'elagage sans keep.
# SYMPTOME si casse : badge "verdict non verifie" sur TOUS les scans (fail-
# closed) et/ou echec total d'analyse (enveloppe ML-KEM). Test n1 ci-dessous.
# ANTIDOTE d'urgence : retablir  -keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# --- Tink (Ed25519) ---------------------------------------------------------
# Le AAR tink-android livre ses consumer rules ; on ne garde ici que la regle
# protobuf officielle (champs des messages generes, lus par reflexion).
# Symptome si casse : verification Ed25519 en echec -> badge "non verifie".
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**

# --- OkHttp / Okio ----------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- ML Kit (barcode + text-recognition) ------------------------------------
# Les artefacts com.google.mlkit livrent leurs consumer rules : les blankets
# precedents etaient redondants et bloquaient l'optimisation.
# Symptome si casse : scan camera/galerie ne detecte plus rien en release.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# --- TensorFlow Lite (runtime Play services) --------------------------------
# Facade Java mince (le moteur natif vient de Play services) : keep peu
# couteux, conserve tel quel par prudence (InterpreterApi via TfLite.initialize).
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# --- Suppression des logs en release (defense en profondeur) ----------------
# Retire tous les appels android.util.Log du binaire publie (CWE-532).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
}

# --- WebView : pont JS du bac a sable (telechargement blob:) -----------------
# Le JS appelle le pont expose "AndroidBlobBridge" : la signature annotee
# @JavascriptInterface doit survivre au renommage, sinon le telechargement
# blob echoue SILENCIEUSEMENT (marche en debug, casse en release).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.blokqr.app.ui.sandbox.** {
    @android.webkit.JavascriptInterface <methods>;
}

# --- WorkManager : analyse en arriere-plan -----------------------------------
# La WorkerFactory instancie les Workers par REFLEXION (nom de classe) :
# on conserve les sous-classes et leur constructeur (Context, WorkerParameters).
# Symptome si casse : l'analyse en arriere-plan ne demarre pas en release.
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**