# BlokQR — Guide Android (build, configuration, CI/CD)

> Le backend est déjà en ligne sur `api.blokqr.com`. L'app Android se construit
> avec le SDK Android + Gradle — **pas sur le VPS** : le VPS héberge l'API,
> l'app tourne sur les téléphones. La CI produit un **artefact** (APK/AAB).

## 1. Deux chemins
- **Local (Android Studio)** — indispensable au début : émulateur, logcat,
  débogage de l'interop post-quantique ; génère aussi le Gradle wrapper.
- **CI (GitHub Actions)** — automatise ensuite les builds signés.

**Ordre :** build local + validation interop PQ → CI → distribution.

## 2. Prérequis
Android Studio (SDK Platform 35, Build-Tools 35, JDK 17, AGP 8.5.2 / Gradle 8.9),
un appareil Android 8.0+ (API 26) ou un émulateur.

## 3. Configuration (maintenant, backend en ligne)
```bash
bash deploy/extract-android-pins.sh api.blokqr.com
```
Reporter dans `app/src/main/java/com/blokqr/app/Config.kt` :
```kotlin
const val API_BASE_URL = "https://api.blokqr.com"
const val PINNED_SLHDSA_ROOT_PUBKEY_B64 = "<racine SLH-DSA>"
const val CERT_PIN_SHA256 = "sha256/<empreinte>"
```
Valeurs publiques (épinglage) : OK à committer. Le keystore reste secret.

## 4. Premier build + JALON CRITIQUE (interop PQ)
```bash
./gradlew assembleDebug
```
Scanner un QR de `https://www.paypa1.com/login` → attendu : verdict **Dangereux**
+ signature vérifiée (Ed25519 **et** ML-DSA-65). Si la partie PQ échoue :
mettre `REQUIRE_PQ_VERIFICATION=false` pour isoler, puis aligner l'encodage
ML-DSA (brut FIPS, contexte vide) entre PQClean (serveur) et BouncyCastle (app).

## 5. CI/CD — GitHub Actions
Workflow fourni : `.github/workflows/android.yml` (debug à chaque push ; APK/AAB
signés sur tag `v*` + Release GitHub).

Keystore (une fois) :
```bash
keytool -genkeypair -v -keystore release.keystore -alias blokqr -keyalg RSA -keysize 4096 -validity 10000
```
Secrets GitHub (Settings > Secrets > Actions) : `KEYSTORE_BASE64`
(`base64 -w0 release.keystore`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Build signé :
```bash
git tag v1.0.0 && git push origin v1.0.0
```

## 6. Minification (déjà sécurisée)
R8 est actif en release ; `proguard-rules.pro` conserve BouncyCastle, Tink,
OkHttp et kotlinx.serialization pour ne pas casser la crypto. Re-tester la
vérification PQ après le premier build de release.

## 7. Distribution
Play Store (AAB, via piste de test interne d'abord), APK auto-hébergé sur le VPS
(`https://blokqr.com/download`, sideload), ou Release GitHub pour les testeurs.

## 8. Dépannage
- « Signature ML-DSA-65 invalide » → interop d'encodage (§4).
- Crypto KO seulement en release → règles R8 (proguard-rules.pro).
- `./gradlew` introuvable en CI → wrapper non commité (le workflow le génère).
- CertPin échoue → re-extraire l'empreinte (certificat renouvelé).
