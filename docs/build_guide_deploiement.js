/* Génère le guide de déploiement BlokQR v3 (serveur dédié) en .docx */
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, LevelFormat, HeadingLevel, BorderStyle, WidthType,
  ShadingType, PageNumber, Header, Footer, TableOfContents, PageBreak,
  VerticalAlign,
} = require("docx");

const NAVY = "0B2545", BLUE = "2E75B6", LIGHT = "D5E8F0", GREY = "5A6B7B";
const CODEBG = "F2F4F7", OKG = "1B7A33", WARN = "9A6A00";
const CW = 9360;

const P = (text, o = {}) => new Paragraph({
  spacing: { after: o.after ?? 120, before: o.before ?? 0, line: 276 }, alignment: o.align,
  children: [new TextRun({ text, font: "Arial", size: o.size ?? 22, bold: o.bold, italics: o.italics, color: o.color ?? "222222" })] });
const Runs = (runs, o = {}) => new Paragraph({ spacing: { after: o.after ?? 120, line: 276 },
  children: runs.map(r => new TextRun({ font: "Arial", size: 22, ...r })) });
const H1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun({ text: t })] });
const H2 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun({ text: t })] });
const Bullet = (text, o = {}) => new Paragraph({ numbering: { reference: "bullets", level: 0 },
  spacing: { after: 80, line: 276 },
  children: [...(o.lead ? [new TextRun({ text: o.lead, bold: true, font: "Arial", size: 22, color: NAVY })] : []),
    new TextRun({ text, font: "Arial", size: 22, color: "222222" })] });

// Bloc de code : police mono, fond gris, lignes séparées.
const Code = (lines) => new Paragraph({
  shading: { type: ShadingType.CLEAR, fill: CODEBG },
  spacing: { before: 60, after: 120, line: 240 },
  border: { left: { style: BorderStyle.SINGLE, size: 18, color: BLUE, space: 8 } },
  children: lines.flatMap((ln, i) => [
    new TextRun({ text: ln, font: "Consolas", size: 18, color: "1A1A1A", break: i === 0 ? 0 : 1 }),
  ]),
});

const cell = (text, { w, fill, bold, color, align } = {}) => new TableCell({
  width: { size: w, type: WidthType.DXA },
  shading: fill ? { fill, type: ShadingType.CLEAR } : undefined,
  margins: { top: 70, bottom: 70, left: 110, right: 110 }, verticalAlign: VerticalAlign.CENTER,
  children: [new Paragraph({ alignment: align, children: [new TextRun({ text, font: "Arial", size: 19, bold, color: color ?? "222222" })] })] });
const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border, insideHorizontal: border, insideVertical: border };
const Trow = (cells) => new TableRow({ children: cells });
const T = (rows) => new Table({ width: { size: CW, type: WidthType.DXA }, borders, rows });

const c = [];

// Couverture
c.push(
  new Paragraph({ spacing: { before: 2600 }, alignment: AlignmentType.CENTER,
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: BLUE, space: 8 } },
    children: [new TextRun({ text: "BLOKQR", font: "Arial", size: 68, bold: true, color: NAVY })] }),
  new Paragraph({ spacing: { before: 240 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Guide de déploiement — VPS dédié", font: "Arial", size: 30, color: BLUE })] }),
  new Paragraph({ spacing: { before: 120 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Spécifications · choix cryptographiques · installation pas à pas · durcissement", font: "Arial", size: 21, italics: true, color: GREY })] }),
  new Paragraph({ spacing: { before: 2400 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Version 3.0 — Ubuntu 24.04 LTS / Docker", font: "Arial", size: 24, bold: true, color: NAVY })] }),
  new Paragraph({ spacing: { before: 120 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Document confidentiel — usage interne", font: "Arial", size: 18, color: GREY })] }),
  new Paragraph({ children: [new PageBreak()] }),
  H1("Sommaire"),
  new TableOfContents("Sommaire", { hyperlink: true, headingStyleRange: "1-2" }),
  new Paragraph({ children: [new PageBreak()] }),
);

// 1. Spécifications
c.push(
  H1("1. Spécifications — VPS recommandé"),
  Runs([{ text: "Recommandation : un VPS suffit — inutile de prendre un serveur dédié.", bold: true, color: NAVY }, { text: " À l'échelle d'un produit solo, la charge (FastAPI + un détonateur Chromium) tient sur un VPS 4 vCPU / 8 Go." }]),
  Runs([{ text: "Exigence : virtualisation KVM", bold: true, color: NAVY }, { text: " — pas un VPS conteneur (OpenVZ/LXC), sinon le bac à sable Chromium et gVisor sont bridés. Les VPS OVHcloud sont en KVM." }]),
  P("Le facteur dimensionnant est le détonateur Chromium (rendu + exécution JavaScript, avec un second profil en cas d'escalade). Pour un usage personnel à petit ou moyen volume :"),
  T([
    Trow([cell("Ressource", { w: 1700, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Minimum", { w: 1700, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Recommandé", { w: 2000, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Remarque", { w: 3960, fill: NAVY, bold: true, color: "FFFFFF" })]),
    Trow([cell("CPU", { w: 1700, fill: LIGHT, bold: true }), cell("2 vCPU", { w: 1700 }), cell("4 vCPU", { w: 2000 }),
      cell("rendu Chromium très « bursty » ; le 2ᵉ profil double brièvement la charge", { w: 3960 })]),
    Trow([cell("RAM", { w: 1700, fill: LIGHT, bold: true }), cell("4 Go", { w: 1700 }), cell("8 Go", { w: 2000 }),
      cell("~300–700 Mo / instance Chromium + /dev/shm 512 Mo + workers + OS + gVisor", { w: 3960 })]),
    Trow([cell("Disque", { w: 1700, fill: LIGHT, bold: true }), cell("25 Go", { w: 1700 }), cell("40 Go NVMe/SSD", { w: 2000 }),
      cell("image Playwright ~2 Go, couches Docker, journaux", { w: 3960 })]),
    Trow([cell("Réseau", { w: 1700, fill: LIGHT, bold: true }), cell("1 IPv4", { w: 1700 }), cell("IPv4 + IPv6", { w: 2000 }),
      cell("egress généreux (le détonateur télécharge des pages)", { w: 3960 })]),
    Trow([cell("OS", { w: 1700, fill: LIGHT, bold: true }), cell("Ubuntu 24.04", { w: 1700 }), cell("Ubuntu 24.04 LTS / Debian 12", { w: 2000 }),
      cell("LTS ; Docker + gVisor bien supportés", { w: 3960 })]),
  ]),
  Runs([{ text: "Équivalents OVHcloud : ", bold: true, color: NAVY },
    { text: "un VPS ~4 vCPU / 8 Go (gamme Comfort/Elite, KVM), ou une instance Public Cloud b3-8. Démarrer en 4 Go est possible à faible concurrence ; viser 8 Go dès plusieurs analyses simultanées. Sur VPS KVM, gVisor utilise sa plateforme systrap/ptrace si la virtualisation imbriquée manque (fonctionne, léger surcoût)." }], { before: 120 }),
);

// 2. Crypto / OpenSSL
c.push(
  H1("2. Choix cryptographiques — faut-il OpenSSL 3.6.2 ?"),
  Runs([{ text: "Non — et il ne faut pas dimensionner le serveur autour d'OpenSSL 3.6.2.", bold: true, color: NAVY }]),
  P("Deux raisons :"),
  Bullet("Les primitives post-quantiques viennent de PQClean (pqcrypto, serveur) et de BouncyCastle (Android) : aucune n'utilise OpenSSL. Les primitives classiques (Ed25519, X25519, ChaCha20-Poly1305, HKDF) viennent de PyCA cryptography, qui embarque sa PROPRE copie d'OpenSSL dans la roue Python. La version d'OpenSSL de l'hôte est donc sans effet sur l'application.", { lead: "Indépendance : " }),
  Bullet("OpenSSL 3.6.2 est une branche NON-LTS, en fin de support en novembre 2026 — mauvais choix pour un serveur déployé aujourd'hui. Si une version récente devait être retenue, ce serait 3.5 LTS (support jusqu'en 2030, ML-KEM/ML-DSA/SLH-DSA et TLS hybride), surtout pas 3.6.x. OpenSSL 4.0 (avril 2026) rompt la compatibilité ABI — à éviter pour l'instant.", { lead: "Cycle de vie : " }),
  Runs([{ text: "Recommandation : ", bold: true, color: NAVY },
    { text: "terminer le TLS avec Caddy ≥ 2.10, qui active PAR DÉFAUT l'échange de clés hybride x25519mlkem768 (via crypto/tls de Go 1.24). Le canal résiste donc déjà au « harvest-now, decrypt-later », sans aucune dépendance à OpenSSL. Si vous préférez nginx pour le TLS, utilisez OpenSSL 3.5 LTS côté hôte (et non 3.6.2)." }]),
);

// 3. Préparation serveur
c.push(
  H1("3. Préparation du serveur"),
  P("Mise à jour, utilisateur de service, pare-feu, fail2ban, Docker."),
  Code(["sudo apt update && sudo apt -y upgrade",
    "sudo adduser --disabled-password --gecos \"\" blokqr",
    "",
    "# Pare-feu",
    "sudo apt -y install ufw",
    "sudo ufw default deny incoming && sudo ufw default allow outgoing",
    "sudo ufw allow 22/tcp   # idéalement restreint à votre IP",
    "sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw enable",
    "",
    "# Anti-bruteforce SSH",
    "sudo apt -y install fail2ban && sudo systemctl enable --now fail2ban"]),
  P("Docker + Compose (dépôt officiel) :"),
  Code(["sudo install -m 0755 -d /etc/apt/keyrings",
    "curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \\",
    "  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg",
    "echo \"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \\",
    "  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable\" | \\",
    "  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null",
    "sudo apt update && sudo apt -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin",
    "sudo usermod -aG docker blokqr   # reconnexion requise"]),
);

// 4. Récupération
c.push(
  H1("4. Récupération du projet"),
  Code(["sudo mkdir -p /opt/blokqr && sudo chown blokqr:blokqr /opt/blokqr",
    "# Transfert depuis votre poste : scp blokqr.zip blokqr@SERVEUR:/opt/",
    "sudo apt -y install unzip rsync",
    "cd /opt && unzip blokqr.zip -d /tmp/qrs",
    "rsync -a /tmp/qrs/blokqr/ /opt/blokqr/ && rm -rf /tmp/qrs",
    "cd /opt/blokqr"]),
);

// 5. Clés
c.push(
  H1("5. Génération du matériel cryptographique"),
  P("La racine SLH-DSA est générée une fois et persistée hors du conteneur (qui tourne en lecture seule). On la génère via un conteneur jetable basé sur l'image du projet."),
  Code(["cd /opt/blokqr/backend && mkdir -p keys",
    "docker build -t blokqr:3.0.0 .",
    "docker run --rm -v \"$PWD/keys:/app/keys\" \\",
    "  -e SPHINCS_ROOT_KEY_PATH=keys/slhdsa_root.json \\",
    "  blokqr:3.0.0 python scripts/generate_keys.py | tee /tmp/qrs-keys.txt",
    "chmod 600 keys/slhdsa_root.json"]),
  Bullet("VERDICT_SIGNING_SEED_B64 → à reporter dans .env (étape 6).", { lead: "Sortie : " }),
  Bullet("PINNED_SLHDSA_ROOT_PUBKEY_B64 → à épingler dans l'app Android (étape 9).", { lead: "Sortie : " }),
  Runs([{ text: "Sauvegardez keys/slhdsa_root.json hors ligne", bold: true, color: NAVY },
    { text: " : sa perte impose un ré-épinglage de l'application. Ne la committez jamais ; en production durcie, clé privée en HSM/KMS." }]),
);

// 6. Config
c.push(
  H1("6. Configuration (.env)"),
  Code(["cd /opt/blokqr/backend && cp .env.example .env && nano .env"]),
  P("Valeurs essentielles :"),
  Code(["ENVIRONMENT=production",
    "DEBUG=false",
    "CORS_ALLOWED_ORIGINS=[\"https://api.blokqr.com\"]",
    "RATE_LIMIT_PER_MINUTE=30",
    "ENABLE_DYNAMIC_SANDBOX=true",
    "BLOCK_PRIVATE_NETWORKS=true",
    "VERDICT_SIGNING_SEED_B64=<votre_seed>",
    "ENABLE_PQ_SIGNATURE=true",
    "SPHINCS_ROOT_KEY_PATH=keys/slhdsa_root.json",
    "ENABLE_PQ_ENVELOPE=true",
    "VERDICT_TTL_SECONDS=120",
    "FAIL_CLOSED=true",
    "SANDBOX_DISABLE_CHROMIUM_SANDBOX=false   # NE PAS activer"]),
  P("Puis : chmod 600 .env"),
);

// 7. Déploiement
c.push(
  H1("7. Déploiement (Caddy + TLS hybride PQ)"),
  P("Sur serveur dédié, on lance l'API et le reverse proxy Caddy (≥ 2.10), qui obtient le certificat TLS automatiquement et active x25519mlkem768 par défaut."),
  Code(["cd /opt/blokqr/deploy",
    "# Le Caddyfile cible déjà api.blokqr.com ; vérifier que le DNS pointe vers le VPS",
    "docker compose -f docker-compose.yml -f docker-compose.caddy.yml up -d --build",
    "docker compose -f docker-compose.yml -f docker-compose.caddy.yml logs -f caddy"]),
  P("Le service est joignable sur https://api.blokqr.com. L'API n'est jamais exposée directement (écoute sur 127.0.0.1 ; seul Caddy est public)."),
);

// 8. Durcissement détonateur
c.push(
  H1("8. Durcissement du détonateur"),
  P("Même sur serveur dédié, on isole le bac à sable : cloisonnement egress (interdit les destinations privées) et, recommandé, runtime gVisor (noyau applicatif entre Chromium et l'hôte)."),
  Code(["cd /opt/blokqr/deploy",
    "sudo bash harden-egress.sh",
    "sudo iptables -L DOCKER-USER -n --line-numbers   # vérifier",
    "sudo apt -y install iptables-persistent && sudo netfilter-persistent save"]),
  P("gVisor (recommandé) :"),
  Code(["curl -fsSL https://gvisor.dev/archive.key | sudo gpg --dearmor -o /usr/share/keyrings/gvisor-archive-keyring.gpg",
    "echo \"deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/gvisor-archive-keyring.gpg] https://storage.googleapis.com/gvisor/releases release main\" | \\",
    "  sudo tee /etc/apt/sources.list.d/gvisor.list > /dev/null",
    "sudo apt update && sudo apt -y install runsc",
    "sudo runsc install && sudo systemctl restart docker",
    "# Puis ajouter  runtime: runsc  au service blokqr et relancer docker compose up -d"]),
);

// 9. Vérifs + Android
c.push(
  H1("9. Vérifications et épinglage Android"),
  Code(["curl -s https://api.blokqr.com/health",
    "curl -s https://api.blokqr.com/manifest | python3 -m json.tool",
    "curl -s https://api.blokqr.com/pq-pubkey",
    "curl -s -X POST https://api.blokqr.com/v1/analyze -H 'Content-Type: application/json' \\",
    "  -d '{\"raw_payload\":\"https://www.paypa1.com/login\",\"client_nonce\":\"test-1234\"}' | python3 -m json.tool | head -30"]),
  P("Le verdict doit contenir signature_ed25519_b64, signature_mldsa65_b64, report_sha256 et expires_at."),
  P("Épinglage Android — l'app n'épingle que la racine SLH-DSA et l'empreinte TLS :"),
  Code(["# Racine SLH-DSA",
    "curl -s https://api.blokqr.com/manifest | python3 -c \"import sys,json;print(json.load(sys.stdin)['root_pub_b64'])\"",
    "# Empreinte SPKI du certificat TLS",
    "echo | openssl s_client -servername api.blokqr.com -connect api.blokqr.com:443 2>/dev/null \\",
    "  | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \\",
    "  | openssl dgst -sha256 -binary | openssl enc -base64"]),
  P("Dans android/.../Config.kt : API_BASE_URL, PINNED_SLHDSA_ROOT_PUBKEY_B64, CERT_PIN_SHA256, puis compiler l'APK dans Android Studio."),
);

// 10. Exploitation + dépannage
c.push(
  H1("10. Confidentialité : modèle en couches, OHTTP optionnel"),
  H2("10.1 Le relais OHTTP n'est pas nécessaire pour démarrer"),
  P("OHTTP (RFC 9458) repose sur trois rôles séparés : le client, un relais (voit l'IP, pas le contenu chiffré) et une passerelle (voit le contenu, pas l'IP). La vie privée n'existe QUE si relais et passerelle sont opérés par deux parties indépendantes."),
  Runs([{ text: "Donc : si vous hébergez vous-même le relais ET la passerelle, OHTTP n'apporte AUCUN bénéfice", bold: true, color: NAVY }, { text: " (vous verriez IP et contenu). Auto-héberger un relais pour un projet solo est inutile et complexe (HPKE, Binary HTTP RFC 9292, publication de config de clés). On l'écarte du démarrage — c'est le risque d'« effondrement » à éviter." }]),
  H2("10.2 Ce qui protège vraiment (déjà en place)"),
  Bullet("le client n'envoie que des préfixes de 4 octets de hachages ; le serveur n'apprend jamais l'URL (même mécanique que Safe Browsing v5).", { lead: "Réputation k-anonyme : " }),
  Bullet("le service ne journalise pas l'IP du client (LOG_CLIENT_IP=false) ; configurer aussi le proxy en ce sens.", { lead: "Minimisation d'IP : " }),
  Bullet("sur consentement, et jamais pour les liens personnels (capability-URL).", { lead: "Palier profond : " }),
  P("Le lien « IP ↔ URL » est déjà rompu côté URL par le k-anonymat ; le résiduel est de faible sensibilité et réduit par la non-journalisation."),
  H2("10.3 Activer OHTTP plus tard (phase 2), sans auto-héberger de relais"),
  Bullet("Google opère déjà le relais (mode passerelle OHTTP) ; on obtient le masquage d'IP sans opérer de relais. Renseigner GOOGLE_SAFE_BROWSING_API_KEY et basculer la source de réputation du client.", { lead: "Consommer Safe Browsing v5 : " }),
  Bullet("Fastly oblivious relay / Cloudflare devant la passerelle : (a) le service expose une ressource passerelle OHTTP (HPKE + BHTTP) et publie sa config de clés ; (b) on contracte avec l'opérateur du relais ; (c) l'app envoie des requêtes encapsulées au relais qui les transmet à la passerelle. Le relais voit l'IP, la passerelle voit la requête — jamais les deux.", { lead: "Relais commercial : " }),
  H1("11. Exploitation et dépannage"),
  P("Rotation des clés de verdict : un simple redémarrage suffit (clés éphémères republiées via /manifest signé par la racine SLH-DSA) — aucune mise à jour de l'app. Ne changer la racine SLH-DSA que si elle est compromise."),
  Code(["docker compose -f docker-compose.yml logs -f blokqr   # journaux",
    "docker compose -f docker-compose.yml up -d --build            # mise à jour",
    "cp backend/keys/slhdsa_root.json ~/backup-slhdsa.$(date +%F).json  # sauvegarde"]),
  T([
    Trow([cell("Symptôme", { w: 3800, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Remède", { w: 5560, fill: NAVY, bold: true, color: "FFFFFF" })]),
    Trow([cell("/v1/analyze lent / timeout", { w: 3800, fill: LIGHT }),
      cell("augmenter proxy timeout et DYNAMIC_TIMEOUT_SECONDS", { w: 5560 })]),
    Trow([cell("Chromium crash /dev/shm", { w: 3800, fill: LIGHT }),
      cell("déjà fixé (/dev/shm:512m) ; augmenter si besoin", { w: 5560 })]),
    Trow([cell("OOM / conteneur tué", { w: 3800, fill: LIGHT }),
      cell("passer à 8 Go ; baisser --workers ; always_dual_profile=false", { w: 5560 })]),
    Trow([cell("Plus de DNS après §8", { w: 3800, fill: LIGHT }),
      cell("vérifier que les règles DNS (port 53) sont en tête de DOCKER-USER", { w: 5560 })]),
    Trow([cell("Android rejette le verdict", { w: 3800, fill: LIGHT }),
      cell("re-vérifier racine SLH-DSA + pin TLS ; valider l'interop ML-DSA", { w: 5560 })]),
  ]),
);

const doc = new Document({
  creator: "BlokQR",
  styles: {
    default: { document: { run: { font: "Arial", size: 22, color: "222222" } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 30, bold: true, font: "Arial", color: NAVY },
        paragraph: { spacing: { before: 320, after: 160 }, outlineLevel: 0,
          border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: BLUE, space: 4 } } } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 25, bold: true, font: "Arial", color: BLUE },
        paragraph: { spacing: { before: 200, after: 120 }, outlineLevel: 1 } },
    ],
  },
  numbering: { config: [
    { reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "•",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] },
  ]},
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840 },
      margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
    headers: { default: new Header({ children: [ new Paragraph({ alignment: AlignmentType.RIGHT,
      border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "CCCCCC", space: 4 } },
      children: [new TextRun({ text: "BlokQR — Guide de déploiement v3.0", font: "Arial", size: 16, color: GREY })] }) ] }) },
    footers: { default: new Footer({ children: [ new Paragraph({ alignment: AlignmentType.CENTER,
      children: [ new TextRun({ text: "Page ", font: "Arial", size: 16, color: GREY }),
        new TextRun({ children: [PageNumber.CURRENT], font: "Arial", size: 16, color: GREY }) ] }) ] }) },
    children: c,
  }],
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("/home/claude/blokqr/docs/BlokQR_Guide_Deploiement_v3.docx", buffer);
  console.log("OK guide de déploiement v3 généré");
});
