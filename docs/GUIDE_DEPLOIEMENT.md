# Guide de déploiement — BlokQR v3

Service d'analyse (FastAPI/Python + Chromium headless) + épinglage de l'application Android.
Cible de référence : **Ubuntu 24.04 LTS** (OVHcloud / IONOS / VPS), **Docker** + **Docker Compose**.

---

## 0. Quel hébergement choisir : VPS ou serveur dédié ?

**Recommandation : un VPS suffit largement — inutile de prendre un serveur dédié.** À l'échelle d'un produit solo / petit volume, la charge (FastAPI + un détonateur Chromium) tient confortablement sur un VPS de 4 vCPU / 8 Go. Un serveur dédié serait surdimensionné et plus coûteux pour démarrer.

**Une exigence technique importante : choisir un VPS en virtualisation KVM**, pas un VPS « conteneur » (OpenVZ/LXC). Le détonateur a besoin de `/dev/shm`, de seccomp, du bac à sable Chromium et — idéalement — de gVisor ; ces mécanismes sont bridés sur les VPS conteneurisés. Les VPS OVHcloud sont en KVM : bon choix.

> BlokQR tourne sur son **propre VPS**, séparé de Blokpass. C'est le scénario sain : Blokpass est un coffre *zero-knowledge*, et faire cohabiter un détonateur de contenu hostile avec un coffre de secrets créerait un risque de pivot. Un VPS dédié à BlokQR (quelques euros par mois) règle la question.

---

## 1. Spécifications du VPS

Le facteur dimensionnant est le **détonateur Chromium** (rendu + exécution JS, avec un second profil en cas d'escalade). Pour un usage personnel à petit/moyen volume :

| Ressource | Minimum | Recommandé | Remarque |
|---|---|---|---|
| **CPU** | 2 vCPU | **4 vCPU** | rendu Chromium très « bursty » ; le 2ᵉ profil double brièvement la charge |
| **RAM** | 4 Go | **8 Go** | ~300–700 Mo par instance Chromium + `/dev/shm` 512 Mo + 2 workers + OS + gVisor |
| **Disque** | 25 Go | **40 Go NVMe/SSD** | image Playwright ~2 Go, couches Docker, journaux |
| **Réseau** | 1 IPv4 publique | + IPv6 | egress généreux (le détonateur télécharge des pages) |
| **OS** | Ubuntu 24.04 LTS | **Ubuntu 24.04 LTS** ou Debian 12 | LTS, Docker + gVisor bien supportés |

**Équivalents OVHcloud** : un VPS ~4 vCPU / 8 Go (gamme Comfort/Elite, **KVM**), ou une instance Public Cloud `b3-8` (4 vCPU / 8 Go). Démarrer en 4 Go est possible à faible concurrence ; passer à 8 Go dès plusieurs analyses simultanées.

**Important — virtualisation KVM** : prenez un VPS KVM (les VPS OVHcloud le sont), pas OpenVZ/LXC, sinon le bac à sable Chromium et gVisor seront bridés (voir §0). Sur un VPS KVM, gVisor utilise sa plateforme `systrap`/`ptrace` (fonctionne, léger surcoût) si la virtualisation imbriquée n'est pas disponible.

Prévoir aussi :
- un **nom de domaine** pointant vers le serveur (ex. `api.blokqr.com`), enregistrements DNS `A`/`AAAA` créés ;
- ports **80** et **443** ouverts en entrée ; **22** restreint à vos IP ;
- l'archive du projet : `blokqr.zip`.

## 1bis. Choix cryptographiques — faut-il OpenSSL 3.6.2 ?

**Non, et il ne faut pas dimensionner le serveur autour d'OpenSSL 3.6.2.** Deux raisons :

1. **BlokQR ne dépend pas de l'OpenSSL système pour sa cryptographie.** Les primitives post-quantiques proviennent de **PQClean** (`pqcrypto`, côté serveur) et de **BouncyCastle** (côté Android) — aucune n'utilise OpenSSL. Les primitives classiques (Ed25519, X25519, ChaCha20-Poly1305, HKDF) sont fournies par **PyCA `cryptography`**, qui **embarque sa propre copie d'OpenSSL** dans la roue Python : la version d'OpenSSL installée sur l'hôte est donc sans effet sur l'application.

2. **OpenSSL 3.6.2 est une branche non-LTS, en fin de support en novembre 2026** — un mauvais choix pour un serveur déployé aujourd'hui. Si une version récente d'OpenSSL devait être retenue, ce serait **3.5 LTS** (support jusqu'en 2030, prise en charge complète de ML-KEM/ML-DSA/SLH-DSA et du TLS hybride), surtout pas 3.6.x. OpenSSL 4.0 (avril 2026) est la plus récente mais rompt la compatibilité ABI — à éviter pour l'instant.

**Le seul endroit où la crypto système compte est la terminaison TLS au bord.** Recommandation : terminer le TLS avec **Caddy ≥ 2.10**, qui active **par défaut** l'échange de clés hybride **x25519mlkem768** (via `crypto/tls` de Go 1.24) — le canal résiste donc déjà au « harvest-now, decrypt-later », **sans aucune dépendance à OpenSSL**. Ce guide utilise Caddy (§5a) pour cette raison. Si vous préfériez nginx pour le TLS, utilisez **OpenSSL 3.5 LTS** côté hôte (et non 3.6.2).

---

## 2. Arborescence cible

```
/opt/blokqr/
├── backend/
│   ├── app/                 # service (analyzers, security, scoring, api…)
│   ├── scripts/generate_keys.py
│   ├── keys/
│   │   └── slhdsa_root.json # racine de confiance SLH-DSA (générée, NON commitée)
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── .env.example
│   └── .env                 # créé à l'étape 4 (secrets)
├── deploy/
│   ├── docker-compose.yml          # API seule (derrière proxy)
│   ├── docker-compose.caddy.yml    # surcouche reverse proxy TLS (serveur dédié)
│   ├── Caddyfile                   # config du proxy (domaine)
│   └── harden-egress.sh            # cloisonnement réseau du détonateur
└── android/                 # app cliente (compilée séparément dans Android Studio)
```

---

## 3. Étape 1 — Préparation du serveur

```bash
# 3.1 Mise à jour
sudo apt update && sudo apt -y upgrade

# 3.2 Utilisateur de service non privilégié
sudo adduser --disabled-password --gecos "" blokqr
sudo usermod -aG sudo blokqr        # facultatif (admin)

# 3.3 Pare-feu (UFW)
sudo apt -y install ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp                 # idéalement : limiter à votre IP
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable

# 3.4 Protection anti-bruteforce SSH
sudo apt -y install fail2ban
sudo systemctl enable --now fail2ban

# 3.5 Docker + Compose (dépôt officiel)
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker blokqr       # exécuter docker sans sudo (reconnexion requise)

# Vérification
docker --version && docker compose version
```

---

## 4. Étape 2 — Récupération du projet

```bash
sudo mkdir -p /opt/blokqr && sudo chown blokqr:blokqr /opt/blokqr
# Transférez blokqr.zip sur le serveur (scp depuis votre poste) :
#   scp blokqr.zip blokqr@VOTRE_SERVEUR:/opt/
sudo apt -y install unzip
cd /opt && unzip blokqr.zip -d /tmp/qrs && \
  rsync -a /tmp/qrs/blokqr/ /opt/blokqr/ && rm -rf /tmp/qrs
cd /opt/blokqr
```

---

## 5… (suite) — Génération des clés, configuration, déploiement

### Étape 3 — Matériel cryptographique

La racine **SLH-DSA** doit être générée **une fois** et persistée hors du conteneur (le conteneur tourne en lecture seule). Générez-la dans un environnement Python disposant des dépendances (le plus simple : à l'intérieur d'un conteneur jetable basé sur l'image du projet).

```bash
cd /opt/blokqr/backend
mkdir -p keys

# Construire l'image (sert aussi à générer les clés)
docker build -t blokqr:3.0.0 .

# Générer seed Ed25519 + racine SLH-DSA (écrite dans ./keys), afficher les valeurs
docker run --rm \
  -v "$PWD/keys:/app/keys" \
  -e SPHINCS_ROOT_KEY_PATH=keys/slhdsa_root.json \
  blokqr:3.0.0 python scripts/generate_keys.py | tee /tmp/qrs-keys.txt
```

La sortie contient :
- `VERDICT_SIGNING_SEED_B64=…` → à reporter dans `.env` (étape 4) ;
- `PINNED_SLHDSA_ROOT_PUBKEY_B64=…` → à épingler dans l'app Android (étape 8) ;
- le fichier `keys/slhdsa_root.json` (racine privée). **Sauvegardez-le hors ligne** ; ne le committez jamais. En production durcie, conservez la clé privée racine en HSM/KMS.

```bash
chmod 600 keys/slhdsa_root.json
```

### Étape 4 — Configuration (`.env`)

```bash
cd /opt/blokqr/backend
cp .env.example .env
nano .env
```

Renseignez au minimum :

```ini
ENVIRONMENT=production
DEBUG=false
CORS_ALLOWED_ORIGINS=["https://api.blokqr.com"]
RATE_LIMIT_PER_MINUTE=30

ENABLE_DYNAMIC_SANDBOX=true
DYNAMIC_TIMEOUT_SECONDS=15
BLOCK_PRIVATE_NETWORKS=true

# Collez la valeur générée à l'étape 3 :
VERDICT_SIGNING_SEED_B64=<votre_seed>
ENABLE_PQ_SIGNATURE=true

# Racine SLH-DSA (montée en lecture seule par compose) :
SPHINCS_ROOT_KEY_PATH=keys/slhdsa_root.json
ENABLE_PQ_ENVELOPE=true

# Politique de sécurité :
VERDICT_TTL_SECONDS=120
FAIL_CLOSED=true

# Flux de réputation (optionnel mais recommandé) :
GOOGLE_SAFE_BROWSING_API_KEY=
URLHAUS… / THREAT_FEED_PATH=     # voir §10

# Sortie du détonateur via proxy résidentiel (optionnel, recommandé) :
EGRESS_PROXY_URL=
SANDBOX_DISABLE_CHROMIUM_SANDBOX=false   # NE PAS activer
```

> Permissions : `chmod 600 .env`.

---

### Étape 5a — Déploiement (VPS dédié BlokQR)

Avec reverse proxy Caddy intégré (TLS automatique Let's Encrypt).

```bash
cd /opt/blokqr/deploy
# Adapter le domaine
sed -i 's/api.blokqr.com/api.blokqr.com/' Caddyfile

# Démarrer API + proxy TLS
docker compose -f docker-compose.yml -f docker-compose.caddy.yml up -d --build

# Suivre l'obtention du certificat
docker compose -f docker-compose.yml -f docker-compose.caddy.yml logs -f caddy
```

Le service est alors joignable sur `https://api.blokqr.com`.

---

### Étape 5b — Déploiement sur SERVEUR PARTAGÉ avec Blokpass (scénario B)

Ici on **ne lance pas** Caddy dans le compose (un reverse proxy existe déjà pour Blokpass). On démarre l'API seule, qui n'écoute que sur `127.0.0.1:8000`, puis on ajoute un sous-domaine au proxy existant.

```bash
cd /opt/blokqr/deploy
docker compose -f docker-compose.yml up -d --build   # API sur 127.0.0.1:8000 uniquement
```

**Si le proxy existant est nginx** — ajoutez un vhost dédié :

```nginx
# /etc/nginx/sites-available/blokqr.conf
server {
    listen 443 ssl http2;
    server_name api.blokqr.com;

    ssl_certificate     /etc/letsencrypt/live/api.blokqr.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.blokqr.com/privkey.pem;

    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 60s;          # l'analyse dynamique peut être longue
    }
}
```

```bash
sudo certbot --nginx -d api.blokqr.com   # certificat dédié
sudo ln -s /etc/nginx/sites-available/blokqr.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

**Si le proxy existant est Caddy** — ajoutez un bloc au Caddyfile de l'hôte :

```
api.blokqr.com {
    reverse_proxy 127.0.0.1:8000
}
```

> Blokpass et BlokQR restent ainsi des conteneurs distincts, sur des sous-domaines distincts, derrière le même proxy — sans partage de processus ni de secrets.

---

## 6… Étapes finales

### Étape 6 — Vérifications

```bash
# Santé
curl -s https://api.blokqr.com/health

# Manifeste signé SLH-DSA (doit contenir les clés de verdict + ML-KEM)
curl -s https://api.blokqr.com/manifest | python3 -m json.tool

# Clé de passerelle ML-KEM (enveloppe de confidentialité)
curl -s https://api.blokqr.com/pq-pubkey

# Analyse d'un domaine sosie -> doit renvoyer "malicious" + signature
curl -s -X POST https://api.blokqr.com/v1/analyze \
  -H "Content-Type: application/json" \
  -d '{"raw_payload":"https://www.paypa1.com/login","client_nonce":"test-nonce-1234"}' \
  | python3 -m json.tool | head -30
```

Le verdict renvoyé contient `signature_ed25519_b64`, `signature_mldsa65_b64`, `report_sha256` et `expires_at` : la chaîne de confiance hybride est active.

### Étape 7 — (Optionnel) tests automatisés

```bash
docker run --rm -v "$PWD/../backend:/app" -w /app blokqr:3.0.0 \
  python -m pytest -q     # 26 tests
```

### Étape 8 — Épinglage côté Android

L'application n'épingle que **deux** valeurs : la racine SLH-DSA et l'empreinte TLS.

```bash
# (a) Racine SLH-DSA : déjà affichée à l'étape 3 (PINNED_SLHDSA_ROOT_PUBKEY_B64)
#     ou relisible dans le manifeste :
curl -s https://api.blokqr.com/manifest | python3 -c "import sys,json;print(json.load(sys.stdin)['root_pub_b64'])"

# (b) Empreinte SPKI du certificat TLS (épinglage OkHttp)
echo | openssl s_client -servername api.blokqr.com -connect api.blokqr.com:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64
```

Dans `android/app/src/main/java/com/blokqr/app/Config.kt` :

```kotlin
const val API_BASE_URL = "https://api.blokqr.com"
const val PINNED_SLHDSA_ROOT_PUBKEY_B64 = "<root_pub_b64 de (a)>"
const val CERT_PIN_SHA256 = "sha256/<empreinte de (b)>"
```

Puis compilez l'APK dans Android Studio (`Build > Generate Signed Bundle / APK`).

---

## 7. Étape 9 — Cloisonnement du détonateur (recommandé)

Empêche le conteneur d'analyse de joindre le réseau privé (donc Blokpass), tout en gardant l'accès Internet.

```bash
cd /opt/blokqr/deploy
sudo bash harden-egress.sh            # détecte le sous-réseau du conteneur
sudo iptables -L DOCKER-USER -n --line-numbers   # vérifier

# Persistance des règles au redémarrage
sudo apt -y install iptables-persistent
sudo netfilter-persistent save
```

### 9.2 (Recommandé) runtime gVisor pour le détonateur

gVisor (`runsc`) interpose un noyau applicatif entre Chromium et le noyau hôte — la meilleure défense contre une évasion.

```bash
# Installation gVisor
curl -fsSL https://gvisor.dev/archive.key | sudo gpg --dearmor -o /usr/share/keyrings/gvisor-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/gvisor-archive-keyring.gpg] https://storage.googleapis.com/gvisor/releases release main" \
  | sudo tee /etc/apt/sources.list.d/gvisor.list > /dev/null
sudo apt update && sudo apt -y install runsc
sudo runsc install            # enregistre le runtime auprès de Docker
sudo systemctl restart docker
```

Puis, dans `deploy/docker-compose.yml`, ajoutez au service `blokqr` :

```yaml
    runtime: runsc
```

et relancez `docker compose up -d`.

---

## 8. Exploitation

```bash
# Logs
docker compose -f docker-compose.yml logs -f blokqr

# Mise à jour du code (nouvelle archive)
cd /opt/blokqr/deploy
docker compose -f docker-compose.yml up -d --build

# Sauvegarde de la racine de confiance (À NE PAS PERDRE : sinon ré-épinglage Android)
cp /opt/blokqr/backend/keys/slhdsa_root.json ~/backup-slhdsa_root.$(date +%F).json
```

**Rotation des clés de verdict** (Ed25519/ML-DSA) : il suffit de redémarrer le service — les clés de verdict sont éphémères et republiées via `/manifest`, signé par la même racine SLH-DSA. **Aucune mise à jour de l'app n'est nécessaire.** Ne changez la racine SLH-DSA que si elle est compromise (cela impose un ré-épinglage Android).

---

## 9. Dépannage

| Symptôme | Cause probable | Remède |
|---|---|---|
| `/health` OK mais `/v1/analyze` lent/timeout | Chromium en cours de rendu | augmenter `proxy_read_timeout` ; `DYNAMIC_TIMEOUT_SECONDS` |
| Chromium crash « /dev/shm » | shm trop petit | déjà fixé (`/dev/shm:512m`) ; augmenter si besoin |
| OOM / conteneur tué | RAM insuffisante | passer à 4 Go ; baisser `--workers` ; `always_dual_profile=false` |
| 429 Too Many Requests | rate limit | ajuster `RATE_LIMIT_PER_MINUTE` |
| Manifeste vide de clés ML-DSA | `ENABLE_PQ_SIGNATURE=false` | repasser à `true` |
| Le service ne démarre pas (racine SLH-DSA) | `keys/slhdsa_root.json` absent/non monté | régénérer (étape 3) ; vérifier le volume `:ro` |
| Plus aucune résolution DNS après §9 | règle egress trop large | vérifier que les règles DNS (53) sont bien en tête de `DOCKER-USER` |
| Android rejette le verdict | racine SLH-DSA ou pin TLS erronés | re-extraire (étape 8) ; vérifier l'interop ML-DSA |

---

## 10. Confidentialité : modèle en couches, et OHTTP en option

### 10.1 Pourquoi le relais OHTTP n'est PAS nécessaire pour démarrer

OHTTP (Oblivious HTTP, RFC 9458) repose sur **trois rôles séparés** : le **client** (l'app), un **relais** qui voit l'IP mais pas le contenu (chiffré), et une **passerelle** (votre service) qui voit le contenu mais pas l'IP. La garantie de vie privée n'existe **que si le relais et la passerelle sont opérés par deux parties indépendantes**.

Conséquence directe : **si vous hébergez vous-même le relais ET la passerelle, OHTTP n'apporte AUCUN bénéfice** — vous verriez à la fois l'IP et le contenu. Auto-héberger un relais pour un projet solo est donc inutile et complexe (il faut implémenter HPKE, l'encodage Binary HTTP RFC 9292, la publication de la config de clés). On l'écarte du démarrage : c'est exactement le risque d'« effondrement » à éviter.

### 10.2 Ce qui protège réellement la vie privée (déjà en place)

Le levier le plus fort n'est pas le relais, c'est la **minimisation des données**, déjà implémentée :

- **Réputation : k-anonymat.** Le client n'envoie que des **préfixes de 4 octets** de hachages ; un préfixe étant partagé par d'innombrables URL, le serveur **n'apprend jamais quelle URL** vous vérifiez (même mécanique que Safe Browsing v5).
- **Minimisation d'IP.** Le service ne journalise pas l'IP du client (`LOG_CLIENT_IP=false`) ; configurez aussi le proxy pour ne pas conserver d'IP.
- **Palier profond : sur consentement**, et **jamais pour les liens personnels** (capability-URL).

Le lien sensible « IP ↔ URL » est donc déjà rompu côté URL par le k-anonymat ; le résiduel (IP ↔ préfixes) est de faible sensibilité et réduit encore par la non-journalisation.

### 10.3 Activer OHTTP plus tard (phase 2), sans auto-héberger de relais

1. **Consommer Safe Browsing v5** côté client : Google opère déjà le relais (mode passerelle OHTTP). Vous obtenez le masquage d'IP **sans opérer de relais**. Renseignez `GOOGLE_SAFE_BROWSING_API_KEY` et basculez la source de réputation du client.
2. **Brancher un relais commercial** (Fastly oblivious relay, Cloudflare) devant la passerelle : (a) le service expose une ressource passerelle OHTTP (HPKE + BHTTP) et publie sa config de clés ; (b) vous contractez avec l'opérateur du relais ; (c) l'app envoie les requêtes encapsulées au relais, qui les transmet à votre passerelle. Le relais voit l'IP, la passerelle voit la requête — jamais les deux à la fois.

Tant que la phase 2 n'est pas en place, le modèle 10.2 est pleinement opérationnel pour lancer.

## 11. Annexes


- **Flux de réputation réels** : renseignez `GOOGLE_SAFE_BROWSING_API_KEY`, ou déposez un export URLhaus/PhishTank et pointez `THREAT_FEED_PATH` dessus (monté en lecture seule). Côté client, la source primaire recommandée reste la passerelle **Safe Browsing v5 via OHTTP**.
- **Variante sans Docker (systemd)** : créer un venv (`python3 -m venv`), `pip install -r requirements.txt && playwright install --with-deps chromium`, puis une unité `systemd` lançant `uvicorn app.main:app` en utilisateur `blokqr`, derrière le même reverse proxy. Le durcissement (isolation, egress) reste indispensable et plus délicat à obtenir qu'avec un conteneur — d'où la préférence pour Docker + gVisor.
