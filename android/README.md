# BlokQR — Application Android (native)

Application native Kotlin / Jetpack Compose / CameraX / ML Kit. Le téléphone
**décode** le code et **n'ouvre jamais** la cible : il transmet la chaîne brute
au service BlokQR, reçoit un **verdict signé**, et n'autorise l'ouverture que
dans le bac à sable intégré.

## Pré-requis
- Android Studio (Koala 2024.1+), JDK 17.
- minSdk 26 (Android 8.0), targetSdk 35.

## Ouvrir et compiler
1. Ouvrir le dossier `android/` dans Android Studio (« Open »).
2. Laisser Gradle synchroniser (téléchargement des dépendances).
3. Générer une **icône de lancement** via *File ▸ New ▸ Image Asset* (le
   manifeste référence `@mipmap/ic_launcher`).
4. Renseigner `app/.../Config.kt` :
   - `API_BASE_URL` : URL HTTPS du service BlokQR (ou du relais OHTTP) ;
   - `PINNED_VERDICT_PUBKEY_ED25519_B64` : valeur renvoyée par `GET /pubkey` ;
   - `CERT_PIN_SHA256` : empreinte SPKI du certificat TLS du service.
5. *Run* sur un appareil physique (la caméra n'est pas disponible sur certains
   émulateurs).

## Points d'intégration à finaliser
- **Modèle IA embarqué** : placer `phishing_classifier.tflite` dans
  `app/src/main/assets/`. Tant qu'il est absent, l'app fonctionne et s'appuie
  sur les signaux serveur (le classifieur se désactive proprement).
- **Relais OHTTP** : pour la vie privée maximale du palier de réputation,
  router `/v1/reputation` via un relais OHTTP opéré par un tiers indépendant.
- **Icône de lancement** : à générer (étape 3).

## Permissions
- `CAMERA` (obligatoire) ; `INTERNET` (service) ; `POST_NOTIFICATIONS` (analyse
  en arrière-plan) ; `ACCESS_COARSE_LOCATION` (optionnelle, contexte géographique
  — l'app fonctionne sans, et ne stocke qu'un géohash tronqué).

## Architecture du module
```
scanner/  CameraX + ML Kit (décodage on-device, aucune ouverture)
net/      OkHttp + épinglage de certificat + vérification de signature
crypto/   Normalisation d'URL (préfixes SHA-256) + vérif. Ed25519 (Tink)
data/     Historique local (contexte temporel) + géohash grossier
ai/       Classifieur d'usurpation TFLite (sur la capture, on-device)
work/     WorkManager + notification « Analyse en cours… »
ui/       Compose : scan / analyse / résultat / bac à sable
```

> L'application n'a pas été compilée dans l'environnement de génération de ce
> projet ; elle est livrée prête à ouvrir dans Android Studio.
