# BlokQR — Scanner de codes QR sécurisé par conception

Bouclier mobile contre le **quishing**, le **QRLjacking**, le **phishing conditionnel**,
les **deep links malveillants** et les **QR dynamiques** détournés.

> **Principe directeur — le sas d'isolation (« air-gap »)**
> Le téléphone décode le code en local mais **n'ouvre, ne résout et ne contacte jamais**
> la cible. Il transmet uniquement la chaîne décodée (ou, au palier réputation, un simple
> préfixe de hash) à un service d'analyse qui effectue toutes les opérations risquées et
> renvoie un **verdict signé cryptographiquement**.

---

## Structure du dépôt

```
blokqr/
├── backend/          Service d'analyse « BlokQR » (FastAPI / Python, testé)
│   └── app/
│       ├── analyzers/   classification, lexical, redirections, sandbox,
│       │                threat-intel, réputation k-anonyme, contexte temporel
│       │                + capability-URL, intelligence de domaine (anti-AiTM), consensus
│       ├── scoring/     moteur de score → Safe / Dangerous / Malicious / Unknown
│       ├── security/    anti-SSRF, normalisation + BLAKE3, signature HYBRIDE des verdicts,
│       │                PQC (ML-DSA/SLH-DSA/ML-KEM), manifeste de clés, enveloppe ML-KEM
│       └── api/         /health /pubkey /manifest /pq-pubkey /v1/analyze /v1/reputation
├── android/          Application NATIVE (Kotlin / Compose / CameraX / ML Kit)
├── deploy/           Docker / docker-compose
└── docs/             Proposition de projet v3 (Word) + générateur
```

## Architecture d'analyse à trois paliers

| Palier | Où | Donnée transmise | Rôle |
|--------|----|------------------|------|
| 0 | Appareil | rien | décodage, normalisation, analyse lexicale, empreintes |
| 1 | Cloud (IP non journalisée) | prefixes de hash | réputation k-anonyme (l'URL reste secrète) |
| 2 | Cloud (sur consentement) | URL (à la demande) | redirections + rendu en bac à sable + capture |

L'IA d'usurpation (TFLite) s'exécute SUR L'APPAREIL, à partir de la capture renvoyée :
le contenu de la page n'est jamais transmis à un tiers d'analyse.

## Taxonomie de verdict

| Statut | Couleur | Ouverture |
|--------|---------|-----------|
| Safe | vert #00C853 | bac à sable, ou navigateur avec avertissement léger |
| Dangerous | ambre #FFAB00 | bloquée ; forçage uniquement dans le navigateur isolé |
| Malicious | rouge #D50000 | aucune ; signalement communautaire optionnel |
| Unknown | gris | fail-closed : pas d'ouverture directe, inspection isolée uniquement |

## Chaîne de confiance post-quantique (opérationnelle)

| Primitive | Rôle | Standard |
|-----------|------|----------|
| ML-DSA-65 | seconde signature de chaque verdict (hybride avec Ed25519) | FIPS 204 |
| SLH-DSA-128s | signature de la racine de confiance + manifeste de clés (rotation) | FIPS 205 |
| ML-KEM-768 | enveloppe de confidentialité hybride (X25519+ML-KEM) des requêtes | FIPS 203 |

Le client n'épingle QUE la racine SLH-DSA ; il récupère le manifeste signé,
le vérifie, et fait confiance aux clés de verdict courantes (rotation sans MAJ
de l'app). Serveur : PQClean (`pqcrypto`). Appareil : BouncyCastle.

## Durcissements clés (v3)
- **Bac à sable** : suppression de `--no-sandbox`, furtivité anti-headless,
  sortie via proxy, détection du gating anti-bot, profil unique + escalade.
- **Vie privée** : détection des capability-URL (liens personnels non envoyés
  en analyse profonde sans consentement).
- **Anti-AiTM** : intelligence de domaine (eTLD+1, sosies, homoglyphes).
- **Contexte** : consensus communautaire k-anonyme (détecte le détournement
  dès le premier scan de la victime) ; hachage sur domaine enregistrable (zéro FP CDN).
- **Intégrité** : `report_sha256` lié à la signature ; `expires_at` (anti-rejeu).
- **Fail-closed** : analyse incomplète => verdict « Unknown », pas d'ouverture directe.

## Démarrage rapide (backend)

```bash
cd backend
pip install -r requirements.txt --break-system-packages
python scripts/generate_keys.py        # génère la seed Ed25519
cp .env.example .env                    # renseigner clés/seed
uvicorn app.main:app --host 0.0.0.0 --port 8000
pytest -q                               # 26 tests
```

Endpoints : GET /health, /pubkey, /manifest (manifeste signé SLH-DSA),
/pq-pubkey (clé passerelle ML-KEM) ; POST /v1/analyze, /v1/reputation.

## Démarrage rapide (Android)

Voir `android/README.md`. En résumé : ouvrir `android/` dans Android Studio,
renseigner `Config.kt` (URL du service, RACINE SLH-DSA épinglée, empreinte TLS),
puis compiler.

## Notes d'honnêteté technique
- Vie privée : k-anonymat (préfixes de hash) + IP non journalisée. OHTTP est OPTIONNEL (phase 2) et exige un relais tiers indépendant — voir le guide de déploiement §10.
- Le modèle TFLite d'usurpation est un point d'intégration (non fourni entraîné).
- L'application Android est prête à ouvrir dans Android Studio mais n'a pas été
  compilée dans l'environnement de génération.
