/* Génère la proposition de projet BlokQR (version réajustée). */
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, LevelFormat, HeadingLevel, BorderStyle, WidthType,
  ShadingType, PageNumber, Header, Footer, TableOfContents, PageBreak,
  VerticalAlign,
} = require("docx");

// Palette.
const NAVY = "0B2545";
const BLUE = "2E75B6";
const LIGHT = "D5E8F0";
const GREY = "5A6B7B";
const GREEN = "00C853";
const AMBER = "FFAB00";
const RED = "D50000";

const CW = 9360; // largeur de contenu (US Letter, marges 1")

// --- Helpers --------------------------------------------------------------
const P = (text, opts = {}) =>
  new Paragraph({
    spacing: { after: opts.after ?? 120, before: opts.before ?? 0, line: 276 },
    alignment: opts.align,
    children: [new TextRun({ text, font: "Arial", size: opts.size ?? 22,
      bold: opts.bold, italics: opts.italics, color: opts.color ?? "222222" })],
  });

const Runs = (runs, opts = {}) =>
  new Paragraph({
    spacing: { after: opts.after ?? 120, line: 276 },
    children: runs.map(r => new TextRun({ font: "Arial", size: 22, ...r })),
  });

const H1 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_1,
  children: [new TextRun({ text })] });
const H2 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_2,
  children: [new TextRun({ text })] });

const Bullet = (text, opts = {}) =>
  new Paragraph({
    numbering: { reference: "bullets", level: 0 },
    spacing: { after: 80, line: 276 },
    children: [
      ...(opts.lead ? [new TextRun({ text: opts.lead, bold: true, font: "Arial", size: 22, color: NAVY })] : []),
      new TextRun({ text, font: "Arial", size: 22, color: "222222" }),
    ],
  });

const Num = (text, opts = {}) =>
  new Paragraph({
    numbering: { reference: opts.ref ?? "numbers", level: 0 },
    spacing: { after: 80, line: 276 },
    children: [
      ...(opts.lead ? [new TextRun({ text: opts.lead, bold: true, font: "Arial", size: 22, color: NAVY })] : []),
      new TextRun({ text, font: "Arial", size: 22, color: "222222" }),
    ],
  });

const cell = (text, { w, fill, bold, color, align } = {}) =>
  new TableCell({
    width: { size: w, type: WidthType.DXA },
    shading: fill ? { fill, type: ShadingType.CLEAR } : undefined,
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    verticalAlign: VerticalAlign.CENTER,
    children: [new Paragraph({
      alignment: align,
      children: [new TextRun({ text, font: "Arial", size: 20, bold,
        color: color ?? "222222" })],
    })],
  });

const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border,
  insideHorizontal: border, insideVertical: border };

// =========================================================================
const children = [];

// --- Page de garde --------------------------------------------------------
children.push(
  new Paragraph({ spacing: { before: 2600, after: 0 }, alignment: AlignmentType.CENTER,
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: BLUE, space: 8 } },
    children: [new TextRun({ text: "BLOKQR", font: "Arial", size: 72, bold: true, color: NAVY })] }),
  new Paragraph({ spacing: { before: 240, after: 0 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Scanner de QR codes et codes-barres sécurisé par conception",
      font: "Arial", size: 30, color: BLUE })] }),
  new Paragraph({ spacing: { before: 120, after: 0 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Analyse multicouche, détection des menaces émergentes, vie privée par conception",
      font: "Arial", size: 22, italics: true, color: GREY })] }),
  new Paragraph({ spacing: { before: 2400, after: 0 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Proposition de projet — version 2.0 (réajustée)",
      font: "Arial", size: 24, bold: true, color: NAVY })] }),
  new Paragraph({ spacing: { before: 120, after: 0 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Application Android native (puis iOS) + service d'analyse cloud",
      font: "Arial", size: 20, color: GREY })] }),
  new Paragraph({ spacing: { before: 600, after: 0 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Document confidentiel — usage interne", font: "Arial", size: 18, color: GREY })] }),
  new Paragraph({ children: [new PageBreak()] }),
);

// --- Sommaire -------------------------------------------------------------
children.push(
  H1("Sommaire"),
  new TableOfContents("Sommaire", { hyperlink: true, headingStyleRange: "1-2" }),
  new Paragraph({ children: [new PageBreak()] }),
);

// --- 1. Résumé exécutif ---------------------------------------------------
children.push(
  H1("1. Résumé exécutif"),
  P("BlokQR est un scanner de QR codes et de codes-barres dont la sécurité est garantie par l'architecture elle-même, et non ajoutée après coup. Le principe fondateur est un « sas d'isolation » : le téléphone décode le code localement mais n'ouvre JAMAIS la cible. Il transmet uniquement la chaîne décodée à un service d'analyse qui réalise toutes les opérations risquées (résolution des redirections, rendu de la page en bac à sable, vérification de réputation), puis renvoie un verdict cryptographiquement signé."),
  P("Cette version réajuste la proposition initiale autour de trois axes décidés après analyse approfondie : (1) une application Android NATIVE (Kotlin, Jetpack Compose, CameraX, ML Kit) en lieu et place d'un socle multiplateforme, pour une fluidité et un accès matériel optimaux ; (2) une taxonomie de verdict claire à trois paliers — Safe / Dangerous / Malicious — avec un comportement d'ouverture strictement gradué ; (3) une architecture de vie privée à plusieurs paliers, fondée sur des standards (Oblivious HTTP — RFC 9458 — et la recherche par préfixe de hash de Google Safe Browsing v5)."),
  Runs([
    { text: "Réajustement clé : ", bold: true, color: NAVY },
    { text: "la promesse « n'envoyer qu'un hash de l'URL » et la promesse « suivre les redirections et rendre la page » sont contradictoires (on ne peut pas récupérer une page à partir d'un hash). Cette proposition résout cette tension honnêtement par un modèle à paliers, détaillé en section 5." },
  ]),
);

// --- 2. Contexte et menace ------------------------------------------------
children.push(
  H1("2. Contexte et menace"),
  P("L'hameçonnage par QR code (« quishing ») s'est imposé comme un vecteur d'attaque majeur. Les codes contournent les protections de messagerie (le lien n'est pas en texte), exploitent la confiance accordée à un objet physique (affiche, borne, courrier) et aboutissent sur un petit écran où l'URL est peu visible. Les estimations récentes situent le quishing autour de 10 à 12 % des tentatives d'hameçonnage et en forte croissance, avec une part importante d'URL non détectées par les moteurs de réputation lors du premier passage."),
  H2("2.1 Menaces visées"),
  Bullet("messages d'hameçonnage menant à de fausses pages de connexion (banques, messageries, services publics).", { lead: "Phishing classique : " }),
  Bullet("contenu servi différemment selon le profil (mobile vs robot d'analyse), la géolocalisation ou l'horaire — pour échapper aux scanners.", { lead: "Phishing conditionnel / cloaking : " }),
  Bullet("détournement d'une session par capture du QR code d'authentification d'un service web.", { lead: "QRLjacking : " }),
  Bullet("schémas applicatifs (intent:, etc.) déclenchant des actions sensibles sans navigation web.", { lead: "Deep links malveillants : " }),
  Bullet("un même QR, légitime lors d'une campagne, est ensuite reconfiguré pour pointer vers une page frauduleuse.", { lead: "QR dynamiques détournés : " }),
  P("Conséquence directe : une simple liste noire ne suffit plus. Il faut observer le COMPORTEMENT réel de la destination, dans un environnement isolé, et surveiller sa variation dans le temps et l'espace.", { italics: true }),
);

// --- 3. État de l'art -----------------------------------------------------
children.push(
  H1("3. État de l'art : forces et limites de l'existant"),
  P("Les solutions actuelles couvrent chacune une partie du besoin, mais aucune ne combine lecture universelle, suivi exhaustif des redirections, isolation du téléphone, respect de la vie privée et détection des menaces émergentes."),
);

const cmpHeader = new TableRow({ tableHeader: true, children: [
  cell("Solution", { w: 2600, fill: NAVY, bold: true, color: "FFFFFF" }),
  cell("Force principale", { w: 3380, fill: NAVY, bold: true, color: "FFFFFF" }),
  cell("Limite principale", { w: 3380, fill: NAVY, bold: true, color: "FFFFFF" }),
]});
const cmpRows = [
  ["Kaspersky / Trend Micro", "Excellente détection de phishing classique (base cloud).", "Ne suivent pas les redirections complexes ; ne protègent pas le téléphone à l'ouverture."],
  ["Norton Snap", "Bon équilibre analyse locale (vie privée) / cloud.", "Pas de redirections conditionnelles ni de défense QRLjacking."],
  ["Binary Defense QR Scanner", "Bon suivi des redirections.", "Pas d'isolation locale, pas d'anonymisation."],
  ["VirusTotal", "Réputation très puissante.", "N'est pas un scanner QR ; ne suit pas les redirections ; expose l'URL publiquement."],
  ["urlscan.io", "Référence du suivi de redirections et de la capture de page.", "Pas de scan QR ni de réputation ; scans publics (confidentialité)."],
  ["Browserling / RBI", "Isolation ultime (navigation distante).", "Trop lourd au quotidien ; aucune intégration native au scan QR."],
  ["Work Profile (Android)", "Bonne isolation applicative.", "Pas d'analyse de contenu ; configuration manuelle."],
];
children.push(new Table({ width: { size: CW, type: WidthType.DXA },
  columnWidths: [2600, 3380, 3380], borders,
  rows: [cmpHeader, ...cmpRows.map(r => new TableRow({ children: [
    cell(r[0], { w: 2600, fill: LIGHT, bold: true }),
    cell(r[1], { w: 3380 }), cell(r[2], { w: 3380 }),
  ]}))],
}));

children.push(
  H2("3.1 Neuf critères d'évaluation retenus"),
  P("Nous évaluons toute solution — y compris la nôtre — selon neuf critères :"),
);
[
  ["Lecture universelle", "Décoder QR, Data Matrix, codes-barres 1D/2D, etc."],
  ["Analyse en temps réel", "Vérification instantanée de la réputation (locale ou cloud)."],
  ["Suivi exhaustif des redirections", "Chaînes HTTP, JavaScript et redirections conditionnelles."],
  ["Isolation du processus", "Protection du téléphone par bac à sable / conteneurisation."],
  ["Respect de la vie privée", "Anonymisation ; non-exposition du lien complet à un tiers."],
  ["Menaces émergentes", "QRLjacking, deep links malveillants, phishing conditionnel."],
  ["Prévisualisation sécurisée", "Aperçu de la destination avant ouverture, avec analyse de risque."],
  ["IA embarquée", "Analyse sémantique de la page finale sans cloud (usurpation, formulaires)."],
  ["Contexte temporel / géographique", "Détection des QR dynamiques changeant de destination."],
].forEach(([t, d]) => children.push(Num(d, { lead: t + " — " })));

// --- 4. Principe directeur ------------------------------------------------
children.push(
  H1("4. Principe directeur : la sécurité par conception"),
  P("Le cœur de BlokQR est un sas d'isolation entre l'utilisateur et la cible :"),
  Num("le scanner décode le contenu EN LOCAL (ML Kit, hors ligne) ;", { ref: "flow" }),
  Num("aucune ouverture, aucun appel réseau vers la cible n'a lieu sur le téléphone ;", { ref: "flow" }),
  Num("seule la chaîne décodée (ou, au palier réputation, un simple préfixe de hash) quitte l'appareil ;", { ref: "flow" }),
  Num("toute opération risquée (résolution, rendu, détonation) se déroule dans un service isolé et jetable ;", { ref: "flow" }),
  Num("le verdict revient signé ; l'ouverture éventuelle se fait dans un navigateur isolé intégré.", { ref: "flow" }),
  P("Ce principe neutralise par construction les deep links malveillants et les pages à exécution automatique : rien ne s'exécute tant que l'utilisateur n'a pas reçu un verdict de confiance."),
);

// --- 5. Architecture multicouche -----------------------------------------
children.push(
  H1("5. Architecture d'analyse multicouche"),
  P("L'évaluation se fait en trois paliers, du plus respectueux de la vie privée au plus approfondi. Chaque palier n'est sollicité que si nécessaire."),
  H2("Palier 0 — Sur l'appareil (instantané, hors ligne)"),
  Bullet("décodage multi-format (ML Kit), classification du contenu (URL, Wi-Fi, vCard, deep link…) ;"),
  Bullet("normalisation canonique de l'URL et analyse lexicale (combosquatting, TLD à risque, identifiants intégrés, points d'authentification QR) ;"),
  Bullet("calcul des empreintes (SHA-256 des expressions hôte/chemin ; BLAKE3 pour l'empreinte stable de destination)."),
  H2("Palier 1 — Réputation k-anonyme (vie privée maximale)"),
  P("Le client n'envoie que des PRÉFIXES de 4 octets des hashs d'URL. Le serveur renvoie tous les hashs malveillants connus partageant ces préfixes ; la correspondance finale est faite EN LOCAL. Un préfixe de 4 octets étant partagé par d'innombrables URL (k-anonymat), le serveur n'apprend jamais quelle URL est vérifiée. Acheminé via un relais Oblivious HTTP (RFC 9458), il ne voit pas non plus l'adresse IP du client. Ce mécanisme est exactement celui de Google Safe Browsing v5, qui peut d'ailleurs servir de source en mode passerelle OHTTP."),
  H2("Palier 2 — Bac à sable dynamique (analyse approfondie, à la demande)"),
  P("Suivre les redirections et rendre la page finale EXIGE l'URL : aucun procédé cryptographique ne permet de le faire à partir d'un hash. C'est ici qu'est résolue honnêtement la tension de conception."),
  Runs([
    { text: "Résolution du paradoxe « hash vs redirections » : ", bold: true, color: NAVY },
    { text: "l'URL complète n'est transmise QUE lorsque l'utilisateur demande l'analyse approfondie, et toujours via le relais OHTTP (l'IP reste masquée), vers un service SANS ÉTAT qui ne journalise rien. La vie privée est donc préservée au niveau de l'identité (pas d'IP, pas de corrélation) et par minimisation des données (URL seule — jamais l'image du QR, ni l'historique, ni les données de l'appareil)." },
  ]),
  P("Dans ce bac à sable, le service charge la page avec deux profils (mobile et bureau) pour détecter le cloaking, capture les redirections JavaScript invisibles à l'analyse HTTP statique, repère les formulaires de mot de passe et tente d'identifier une usurpation de marque. Il renvoie en outre une CAPTURE D'ÉCRAN de la page finale, exploitée par l'IA embarquée (section 6.2)."),
);

// --- 6. Innovations -------------------------------------------------------
children.push(
  H1("6. Trois innovations différenciantes"),
  H2("6.1 Détection contextuelle temporelle et géographique"),
  P("Problème : un QR peut être légitime lors d'une campagne puis détourné, ou servir des destinations différentes selon le pays. Aucune solution ne surveille cette variation. BlokQR conserve sur l'appareil une empreinte hachée de la destination (et un géohash grossier) associée à la source scannée ; si le même code mène ultérieurement vers une destination différente, une alerte « QR dynamique : la destination a changé » est levée. Aucune position précise ni URL en clair ne quitte l'appareil — uniquement des hashs et un géohash tronqué (précision ville/région)."),
  H2("6.2 Double prévisualisation avec IA embarquée"),
  P("Problème : les aperçus classiques se limitent au lien ou à une icône. BlokQR affiche côte à côte la VRAIE page finale (capturée en bac à sable) et une évaluation du risque en langage clair (« Cette page se fait passer pour votre banque »). L'IA de détection d'usurpation (TensorFlow Lite) s'exécute SUR L'APPAREIL, à partir de la capture renvoyée : le contenu de la page n'est donc jamais transmis à un service tiers d'analyse — seul l'appareil juge de l'apparence."),
  H2("6.3 Architecture zéro confiance et vie privée par conception"),
  P("La plupart des applications envoient l'URL complète à un service externe. BlokQR applique la minimisation à chaque palier : préfixes de hash pour la réputation, relais OHTTP pour masquer l'IP, service d'analyse sans état pour le palier profond, et jugement d'apparence local. Le module d'isolation empêche toute exécution accidentelle avant validation."),
  Runs([
    { text: "Limite assumée : ", bold: true, color: AMBER },
    { text: "le relais OHTTP ne tient sa promesse de vie privée que s'il est opéré par un tiers indépendant du service (Cloudflare, Fastly…), faute de quoi l'opérateur pourrait corréler IP et requête. Cette séparation des rôles est une exigence de déploiement, pas une option." },
  ]),
);

// --- 7. Taxonomie de verdict ---------------------------------------------
children.push(
  H1("7. Interface de résultat : trois paliers de verdict"),
  P("Le résultat occupe tout l'écran, avec une couleur dominante, un message clair et une icône. Le comportement d'ouverture est strictement gradué."),
);
const vHeader = new TableRow({ tableHeader: true, children: [
  cell("Statut", { w: 1700, fill: NAVY, bold: true, color: "FFFFFF" }),
  cell("Couleur", { w: 1700, fill: NAVY, bold: true, color: "FFFFFF" }),
  cell("Message (exemples)", { w: 3160, fill: NAVY, bold: true, color: "FFFFFF" }),
  cell("Comportement d'ouverture", { w: 2800, fill: NAVY, bold: true, color: "FFFFFF" }),
]});
const vRows = [
  ["Safe", GREEN, "#00C853", "« Ce lien semble sûr. » / « Aucune menace détectée. »", "Ouverture dans le bac à sable ; navigateur possible avec avertissement léger."],
  ["Dangerous", AMBER, "#FFAB00", "« Lien suspect — plusieurs redirections. » / « Ce site pourrait être une copie. »", "Ouverture directe BLOQUÉE ; forçage uniquement dans le navigateur isolé intégré, avec affichage des raisons."],
  ["Malicious", RED, "#D50000", "« Menace confirmée — site de phishing connu. » / « QR code malveillant bloqué. »", "Aucune ouverture. Signalement communautaire optionnel (avec consentement)."],
];
children.push(new Table({ width: { size: CW, type: WidthType.DXA },
  columnWidths: [1700, 1700, 3160, 2800], borders,
  rows: [vHeader, ...vRows.map(r => new TableRow({ children: [
    cell(r[0], { w: 1700, fill: r[1], bold: true, color: "FFFFFF", align: AlignmentType.CENTER }),
    cell(r[2], { w: 1700, align: AlignmentType.CENTER, bold: true, color: r[1] }),
    cell(r[3], { w: 3160 }),
    cell(r[4], { w: 2800 }),
  ]}))],
}));
children.push(
  P("Contenu non navigable (texte brut, vCard, Wi-Fi…) : affiché directement, sur fond neutre, sans évaluation de menace — le risque y est différent et l'utilisateur garde la main.", { italics: true, before: 120 }),
);

// --- 8. Pile technologique ------------------------------------------------
children.push(
  H1("8. Pile technologique"),
  H2("8.1 Application Android (première version)"),
  Bullet("Google ML Kit Barcode Scanning — décodage on-device, multi-format.", { lead: "Scan : " }),
  Bullet("CameraX — flux caméra fluide et compatible, viseur animé en Jetpack Compose.", { lead: "Caméra / UI : " }),
  Bullet("OkHttp avec épinglage de certificat ; vérification obligatoire de la signature du verdict.", { lead: "Réseau : " }),
  Bullet("TensorFlow Lite — classification d'usurpation sur la capture, exécutée sur l'appareil.", { lead: "IA embarquée : " }),
  Bullet("WorkManager — l'analyse se poursuit en arrière-plan ; notification « Analyse en cours… » mise à jour vers le résultat.", { lead: "Tâches : " }),
  Bullet("Android Keystore pour les clés ; intégrité de l'app via Play Integrity (successeur de SafetyNet).", { lead: "Sécurité mobile : " }),
  H2("8.2 Service d'analyse (backend)"),
  P("Décision d'ingénierie : le cœur d'analyse est conservé en Python (FastAPI + Playwright), déjà éprouvé et testé. Playwright pilote un navigateur headless pour la détonation comportementale (équivalent de Puppeteer, avec une robustesse au moins comparable). Le service est sans état et conteneurisable ; il peut être déployé en mode serverless (AWS Lambda via Lambda Web Adapter / Mangum). L'alternative AWS Lambda + Puppeteer dans des microVM Firecracker reste pertinente — Lambda s'exécute d'ailleurs nativement sur Firecracker — et l'interface du bac à sable est indépendante du fournisseur. La rotation des IP de sortie protège contre le pistage du service par les pages détonées."),
  Bullet("réception du contenu, normalisation, analyse lexicale, résolution des redirections, threat intelligence (Google Safe Browsing, VirusTotal, urlscan.io, PhishTank, URLhaus — selon clés), notation et signature.", { lead: "FastAPI : " }),
  Bullet("réputation k-anonyme par préfixes de hash, conçue pour être placée derrière un relais OHTTP tiers.", { lead: "/v1/reputation : " }),
);

// --- 9. Sécurité & vie privée --------------------------------------------
children.push(
  H1("9. Sécurité et confidentialité"),
  Bullet("chaque verdict est signé (Ed25519, avec option post-quantique ML-DSA-65 / FIPS 204). Le client épingle la clé publique : un attaquant en position d'intercepteur ne peut pas transformer un verdict « malicious » en « safe ». Le nonce client lie le verdict à la requête et empêche le rejeu.", { lead: "Verdicts infalsifiables : " }),
  Bullet("le service refuse toute résolution vers des plages internes/privées et les métadonnées cloud (169.254.169.254), et n'autorise que http/https.", { lead: "Protection anti-SSRF : " }),
  Bullet("relais OHTTP (IP masquée) + préfixes de hash (URL masquée) + service sans état + jugement d'apparence local.", { lead: "Vie privée en profondeur : " }),
  Bullet("le téléphone ne contacte jamais la cible ; l'ouverture éventuelle est confinée à une WebView durcie (pas de stockage tiers, schémas non-web bloqués).", { lead: "Isolation : " }),
);

// --- 10. Déploiement ------------------------------------------------------
children.push(
  H1("10. Plan de déploiement"),
  H2("10.1 Backend"),
  Num("générer les clés de signature (scripts/generate_keys.py) ; renseigner le fichier .env (clés de threat intelligence optionnelles, seed Ed25519).", { ref: "deploy" }),
  Num("construire et lancer le conteneur (Docker / docker-compose) ; le service expose /health, /pubkey, /v1/analyze et /v1/reputation.", { ref: "deploy" }),
  Num("option serverless : empaqueter via Lambda Web Adapter / Mangum ; prévoir la rotation des IP de sortie.", { ref: "deploy" }),
  Num("placer la réputation derrière un relais OHTTP opéré par un tiers indépendant.", { ref: "deploy" }),
  H2("10.2 Application Android"),
  Num("ouvrir le dossier android/ dans Android Studio (JDK 17) ; laisser Gradle synchroniser.", { ref: "deploy2" }),
  Num("renseigner Config.kt : URL du service, clé publique de verdict (GET /pubkey), empreinte de certificat TLS.", { ref: "deploy2" }),
  Num("placer le modèle phishing_classifier.tflite dans assets/ (sinon l'app s'appuie sur les signaux serveur).", { ref: "deploy2" }),
  Num("générer l'icône de lancement, compiler et installer sur un appareil physique.", { ref: "deploy2" }),
);

// --- 11. Limites & honnêteté ---------------------------------------------
children.push(
  H1("11. Limites d'ingénierie et points d'intégration"),
  Bullet("le relais OHTTP doit être opéré par un tiers indépendant pour garantir la vie privée (séparation des rôles).", { lead: "OHTTP : " }),
  Bullet("le modèle TFLite d'usurpation est un point d'intégration : il doit être entraîné/sourcé (TensorFlow Lite Model Maker) ; l'application fonctionne sans, en s'appuyant sur les signaux serveur.", { lead: "IA embarquée : " }),
  Bullet("l'analyse approfondie nécessite par nature la transmission de l'URL (impossible depuis un hash) ; la vie privée repose alors sur l'anonymat réseau et la minimisation, non sur la rétention de l'URL.", { lead: "Palier profond : " }),
  Bullet("l'application Android est livrée prête à ouvrir dans Android Studio ; elle n'a pas été compilée dans l'environnement de génération du projet.", { lead: "Compilation : " }),
);

// --- 12. Feuille de route -------------------------------------------------
children.push(
  H1("12. Feuille de route"),
  Bullet("Android natif (scan, paliers 0-2, double prévisualisation, bac à sable, verdicts signés).", { lead: "v1 : " }),
  Bullet("modèle TFLite d'usurpation entraîné ; intégration du relais OHTTP de production ; flux de réputation (URLhaus/PhishTank).", { lead: "v1.1 : " }),
  Bullet("portage iOS (URLSession, Secure Enclave, App Attest) en réutilisant le service d'analyse.", { lead: "v2 : " }),
  Bullet("signalement communautaire consenti et corrélation géographique des QR dynamiques.", { lead: "v2.1 : " }),
  P("Le nom définitif de la solution reste à arrêter ; « BlokQR » est conservé comme nom de travail.", { italics: true, before: 120, color: GREY }),
);

// =========================================================================
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
    { reference: "numbers", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] },
    { reference: "flow", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] },
    { reference: "deploy", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] },
    { reference: "deploy2", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] },
  ]},
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840 },
      margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
    headers: { default: new Header({ children: [ new Paragraph({
      alignment: AlignmentType.RIGHT,
      border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "CCCCCC", space: 4 } },
      children: [new TextRun({ text: "BlokQR — Proposition de projet v2.0", font: "Arial", size: 16, color: GREY })] }) ] }) },
    footers: { default: new Footer({ children: [ new Paragraph({
      alignment: AlignmentType.CENTER,
      children: [ new TextRun({ text: "Page ", font: "Arial", size: 16, color: GREY }),
        new TextRun({ children: [PageNumber.CURRENT], font: "Arial", size: 16, color: GREY }) ] }) ] }) },
    children,
  }],
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("/home/claude/blokqr/docs/BlokQR_Proposition_v2.docx", buffer);
  console.log("OK proposition v2 générée");
});
