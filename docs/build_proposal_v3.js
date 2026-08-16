/* Génère la proposition de projet BlokQR v3 (durcie et repositionnée). */
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, LevelFormat, HeadingLevel, BorderStyle, WidthType,
  ShadingType, PageNumber, Header, Footer, TableOfContents, PageBreak,
  VerticalAlign,
} = require("docx");

const NAVY = "0B2545", BLUE = "2E75B6", LIGHT = "D5E8F0", GREY = "5A6B7B";
const GREEN = "00C853", AMBER = "FFAB00", RED = "D50000";
const CW = 9360;

const P = (text, opts = {}) =>
  new Paragraph({
    spacing: { after: opts.after ?? 120, before: opts.before ?? 0, line: 276 },
    alignment: opts.align,
    children: [new TextRun({ text, font: "Arial", size: opts.size ?? 22,
      bold: opts.bold, italics: opts.italics, color: opts.color ?? "222222" })],
  });
const Runs = (runs, opts = {}) =>
  new Paragraph({ spacing: { after: opts.after ?? 120, line: 276 },
    children: runs.map(r => new TextRun({ font: "Arial", size: 22, ...r })) });
const H1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun({ text: t })] });
const H2 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun({ text: t })] });
const Bullet = (text, opts = {}) =>
  new Paragraph({ numbering: { reference: "bullets", level: 0 }, spacing: { after: 80, line: 276 },
    children: [...(opts.lead ? [new TextRun({ text: opts.lead, bold: true, font: "Arial", size: 22, color: NAVY })] : []),
      new TextRun({ text, font: "Arial", size: 22, color: "222222" })] });
const Num = (text, opts = {}) =>
  new Paragraph({ numbering: { reference: opts.ref ?? "numbers", level: 0 }, spacing: { after: 80, line: 276 },
    children: [...(opts.lead ? [new TextRun({ text: opts.lead, bold: true, font: "Arial", size: 22, color: NAVY })] : []),
      new TextRun({ text, font: "Arial", size: 22, color: "222222" })] });
const cell = (text, { w, fill, bold, color, align } = {}) =>
  new TableCell({ width: { size: w, type: WidthType.DXA },
    shading: fill ? { fill, type: ShadingType.CLEAR } : undefined,
    margins: { top: 80, bottom: 80, left: 120, right: 120 }, verticalAlign: VerticalAlign.CENTER,
    children: [new Paragraph({ alignment: align,
      children: [new TextRun({ text, font: "Arial", size: 20, bold, color: color ?? "222222" })] })] });
const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border,
  insideHorizontal: border, insideVertical: border };

const children = [];

// --- Page de garde --------------------------------------------------------
children.push(
  new Paragraph({ spacing: { before: 2600, after: 0 }, alignment: AlignmentType.CENTER,
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: BLUE, space: 8 } },
    children: [new TextRun({ text: "BLOKQR", font: "Arial", size: 72, bold: true, color: NAVY })] }),
  new Paragraph({ spacing: { before: 240 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Scanner de QR codes et codes-barres sécurisé par conception",
      font: "Arial", size: 30, color: BLUE })] }),
  new Paragraph({ spacing: { before: 120 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Orchestration multicouche · vie privée par conception · chaîne de confiance post-quantique",
      font: "Arial", size: 22, italics: true, color: GREY })] }),
  new Paragraph({ spacing: { before: 2400 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Proposition de projet — version 3.0 (durcie)", font: "Arial", size: 24, bold: true, color: NAVY })] }),
  new Paragraph({ spacing: { before: 120 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Application Android native + service d'analyse sans état", font: "Arial", size: 20, color: GREY })] }),
  new Paragraph({ spacing: { before: 600 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Document confidentiel — usage interne", font: "Arial", size: 18, color: GREY })] }),
  new Paragraph({ children: [new PageBreak()] }),
);

// --- Sommaire -------------------------------------------------------------
children.push(H1("Sommaire"),
  new TableOfContents("Sommaire", { hyperlink: true, headingStyleRange: "1-2" }),
  new Paragraph({ children: [new PageBreak()] }));

// --- 1. Résumé exécutif ---------------------------------------------------
children.push(
  H1("1. Résumé exécutif"),
  P("BlokQR est un scanner de QR codes et de codes-barres dont la sécurité tient à l'architecture elle-même. Le principe fondateur est un « sas d'isolation » : le téléphone décode le code localement mais n'ouvre JAMAIS la cible. Il transmet la chaîne décodée à un service d'analyse sans état qui réalise toutes les opérations risquées (résolution des redirections, rendu en bac à sable, vérification de réputation) et renvoie un verdict signé."),
  Runs([{ text: "Positionnement assumé : ", bold: true, color: NAVY },
    { text: "BlokQR n'est pas un nouveau moteur d'analyse de menaces — ce terrain est déjà couvert par Google Safe Browsing, VirusTotal ou urlscan.io. Sa valeur défendable est l'ORCHESTRATION : une expérience de scan native, un sas d'isolation strict, une confidentialité par conception, une intelligence contextuelle (temporelle/géographique) et une chaîne de confiance post-quantique. Le produit consomme les meilleures sources externes au lieu de les réinventer." }]),
  P("Cette version 3.0 répond point par point à une analyse critique interne : durcissement du bac à sable, intégration de flux de réputation réels, correction d'une fuite de vie privée sur les liens personnels, détection d'usurpation par le domaine (efficace contre les attaques AiTM), couche de consensus communautaire, politique d'échec « fail-closed », liaison cryptographique du rapport, et mise en œuvre OPÉRATIONNELLE des trois primitives post-quantiques (ML-DSA-65, SLH-DSA, ML-KEM-768)."),
);

// --- 2. Positionnement stratégique ----------------------------------------
children.push(
  H1("2. Positionnement stratégique honnête"),
  P("Un scanner autonome doit se justifier face à ce que le système d'exploitation et les grands acteurs offrent déjà. Cette section nomme explicitement les forces des solutions existantes et délimite la niche de BlokQR."),
  H2("2.1 Ce que les incumbents font mieux"),
  Bullet("la réputation d'URL par préfixe de hash, en temps réel et désormais via passerelle OHTTP, est fournie gratuitement par Google Safe Browsing v5, intégré à Android et Chrome.", { lead: "Réputation : " }),
  Bullet("VirusTotal et urlscan.io disposent d'un corpus de menaces et d'un crowdsourcing qu'un nouvel entrant ne peut égaler.", { lead: "Corpus : " }),
  Bullet("l'isolation de navigation à distance (RBI) de Cloudflare ou Zscaler rend les pages de façon bien plus robuste, avec sortie résidentielle, que tout détonateur headless maison.", { lead: "Rendu isolé : " }),
  H2("2.2 La niche défendable de BlokQR"),
  P("BlokQR se concentre donc sur l'assemblage et l'expérience, non sur une capacité d'analyse isolée :"),
  Bullet("le téléphone n'ouvre jamais la cible : décodage local, analyse déportée, verdict signé.", { lead: "Sas d'isolation : " }),
  Bullet("k-anonymat par préfixe de hash (le serveur n'apprend jamais l'URL) + IP non journalisée + traitement de la capture sur l'appareil, de sorte qu'aucun tiers ne relie une URL à un utilisateur.", { lead: "Vie privée : " }),
  Bullet("corrélation temporelle et géographique des destinations, consensus communautaire, détection des liens personnels.", { lead: "Contexte : " }),
  Bullet("double prévisualisation pédagogique et verdict gradué, pour décider en connaissance de cause.", { lead: "UX : " }),
  Bullet("verdicts et racine de confiance résistants à un futur ordinateur quantique.", { lead: "Confiance PQ : " }),
);

// --- 3. Architecture de vie privée à paliers ------------------------------
children.push(
  H1("3. Architecture de vie privée à paliers"),
  P("La promesse « n'envoyer qu'un hash de l'URL » et la promesse « suivre les redirections et rendre la page » sont contradictoires : on ne récupère pas une page à partir d'un hash. BlokQR résout cette tension par un modèle explicite à trois paliers."),
  new Table({ width: { size: CW, type: WidthType.DXA }, borders, rows: [
    new TableRow({ tableHeader: true, children: [
      cell("Palier", { w: 1500, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Données transmises", { w: 3400, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Garantie de confidentialité", { w: 4460, fill: NAVY, bold: true, color: "FFFFFF" }),
    ]}),
    new TableRow({ children: [
      cell("0 — appareil", { w: 1500, fill: LIGHT, bold: true }),
      cell("aucune (décodage, normalisation, analyse lexicale, empreintes locales)", { w: 3400 }),
      cell("totale : rien ne quitte le téléphone", { w: 4460 }) ]}),
    new TableRow({ children: [
      cell("1 — réputation", { w: 1500, fill: LIGHT, bold: true }),
      cell("uniquement des préfixes de 4 octets de hachages d'expressions hôte/chemin", { w: 3400 }),
      cell("k-anonymat (façon Safe Browsing v5) : le serveur n'apprend jamais l'URL ; IP non journalisée ; correspondance finale en local", { w: 4460 }) ]}),
    new TableRow({ children: [
      cell("2 — profond", { w: 1500, fill: LIGHT, bold: true }),
      cell("URL complète (indispensable pour rendre la page) — sur consentement", { w: 3400 }),
      cell("sur consentement ; jugement de la capture sur l'appareil ; IP non journalisée ; LIENS PERSONNELS exclus par défaut (voir 5.3)", { w: 4460 }) ]}),
  ]}),
  P("OHTTP (RFC 9458) est une OPTION de phase 2 : il masque aussi l'IP, mais n'a de valeur que s'il est opéré par un tiers indépendant (Fastly/Cloudflare) ou via Safe Browsing v5. Au démarrage, le k-anonymat et la non-journalisation d'IP suffisent.", { before: 120, italics: true, color: GREY }),
);

// --- 4. Chaîne de confiance post-quantique --------------------------------
children.push(
  H1("4. Chaîne de confiance post-quantique"),
  P("Les trois primitives normalisées par le NIST sont employées, chacune pour un rôle distinct et justifié — aucune n'est décorative. Toutes sont implémentées et vérifiées de bout en bout, côté serveur (PQClean) comme côté appareil (BouncyCastle)."),
  new Table({ width: { size: CW, type: WidthType.DXA }, borders, rows: [
    new TableRow({ tableHeader: true, children: [
      cell("Primitive", { w: 2100, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Rôle", { w: 3600, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Justification", { w: 3660, fill: NAVY, bold: true, color: "FFFFFF" }),
    ]}),
    new TableRow({ children: [
      cell("ML-DSA-65 (FIPS 204)", { w: 2100, fill: LIGHT, bold: true }),
      cell("seconde signature de chaque verdict, en hybride avec Ed25519", { w: 3600 }),
      cell("chemin chaud : taille (~3,3 Ko) et vitesse adaptées ; forger un verdict exige de casser le classique ET le post-quantique", { w: 3660 }) ]}),
    new TableRow({ children: [
      cell("SLH-DSA-SHA2-128s (FIPS 205)", { w: 2100, fill: LIGHT, bold: true }),
      cell("signature de la racine de confiance et du manifeste de clés", { w: 3600 }),
      cell("sécurité conservatrice (hachage pur) ; signée rarement ; ancre l'épinglage et permet la rotation des clés de verdict", { w: 3660 }) ]}),
    new TableRow({ children: [
      cell("ML-KEM-768 (FIPS 203)", { w: 2100, fill: LIGHT, bold: true }),
      cell("enveloppe de confidentialité hybride (X25519 + ML-KEM) du corps des requêtes", { w: 3600 }),
      cell("résistance « harvest-now, decrypt-later » en complément d'OHTTP/TLS", { w: 3660 }) ]}),
  ]}),
  H2("4.1 Manifeste de clés et rotation"),
  P("L'application n'épingle QUE la clé publique racine SLH-DSA. Elle récupère un manifeste signé publiant les clés courantes (Ed25519, ML-DSA-65, ML-KEM-768), en vérifie la signature contre la racine épinglée et la fraîcheur, puis fait confiance à ces clés. La rotation des clés de verdict ne nécessite donc aucune mise à jour de l'application — réponse directe au problème des clés éphémères."),
  H2("4.2 Liaison du rapport et fraîcheur"),
  P("La chaîne canonique signée inclut le hachage SHA-256 du rapport complet (report_sha256) : la capture d'écran et les explications sont ainsi authentifiées, pas seulement le verdict. Elle inclut aussi une échéance (expires_at), de sorte qu'un ancien verdict « sûr » ne puisse être rejoué."),
);

// --- 5. Détection des menaces ---------------------------------------------
children.push(
  H1("5. Détection des menaces (corrections apportées)"),
  H2("5.1 Bac à sable durci"),
  Bullet("suppression du drapeau --no-sandbox par défaut ; isolation déléguée à l'infrastructure (conteneur rootless + gVisor/Firecracker).", { lead: "Confinement : " }),
  Bullet("masquage des marqueurs headless (navigator.webdriver, langues, plugins) pour réduire l'identification du scanner par les kits qui servent une page bénigne aux robots.", { lead: "Furtivité : " }),
  Bullet("sortie réseau via proxy configurable (idéalement résidentiel/mobile) pour ne pas être trivialement classé « datacenter ».", { lead: "Sortie : " }),
  Bullet("un mur Turnstile/CAPTCHA/Cloudflare est traité comme un signal d'évasion d'analyse, pas comme une page neutre.", { lead: "Gating : " }),
  Bullet("profil unique par défaut ; escalade vers un second profil (détection de cloaking) uniquement en cas de suspicion, pour réduire la latence.", { lead: "Latence : " }),
  H2("5.2 Détection d'usurpation par le domaine (anti-AiTM)"),
  P("Contre les kits AiTM (type Evilginx) qui relaient la vraie page, l'analyse visuelle est inutile : la page est un proxy de l'authentique. Seul le domaine trahit l'attaque. BlokQR extrait le domaine enregistrable (eTLD+1) et détecte les sosies de marques (homoglyphes, fautes de frappe, combosquatting, punycode/IDN) par distance d'édition."),
  H2("5.3 Liens personnels (capability-URL)"),
  P("Certaines URL SONT un secret (réinitialisation de mot de passe, désinscription contenant l'e-mail, lien magique, JWT). Les analyser en profondeur les divulguerait à la passerelle. L'application détecte ces motifs et SUSPEND l'analyse profonde par défaut, ne la relançant qu'avec le consentement explicite de l'utilisateur."),
  H2("5.4 Contexte temporel et consensus communautaire"),
  P("Le hachage porte désormais sur le domaine enregistrable, ce qui supprime les faux positifs dus aux CDN et aux redirections régionales. Surtout, une couche de consensus k-anonyme agrège les couples source→destination observés par des appareils consentants : si la destination d'un code diverge du consensus établi par plusieurs contributeurs, l'alerte se déclenche dès le PREMIER scan de la victime — là où une simple mémoire locale resterait aveugle."),
  H2("5.5 Politique fail-closed"),
  P("Pour un outil de sécurité, échouer en silence vers « sûr » est dangereux. Lorsque l'analyse est incomplète (analyse profonde suspendue, rendu indisponible, erreur), le verdict n'est jamais « sûr » : il devient « Non vérifié » et l'application interdit l'ouverture directe, n'autorisant que l'inspection en bac à sable ou une nouvelle analyse."),
);

// --- 6. Taxonomie de verdict ----------------------------------------------
children.push(
  H1("6. Taxonomie de verdict"),
  new Table({ width: { size: CW, type: WidthType.DXA }, borders, rows: [
    new TableRow({ tableHeader: true, children: [
      cell("Verdict", { w: 1900, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Signification", { w: 4000, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Ouverture", { w: 3460, fill: NAVY, bold: true, color: "FFFFFF" }),
    ]}),
    new TableRow({ children: [
      cell("Sécurisé", { w: 1900, fill: GREEN, bold: true, color: "FFFFFF" }),
      cell("aucun signal de menace", { w: 4000 }),
      cell("ouverture directe (avertissement léger)", { w: 3460 }) ]}),
    new TableRow({ children: [
      cell("Prudence", { w: 1900, fill: AMBER, bold: true, color: "FFFFFF" }),
      cell("signaux suspects sans confirmation", { w: 4000 }),
      cell("bloqué ; forçage uniquement en bac à sable isolé", { w: 3460 }) ]}),
    new TableRow({ children: [
      cell("Dangereux", { w: 1900, fill: RED, bold: true, color: "FFFFFF" }),
      cell("menace confirmée (réputation ou signal critique)", { w: 4000 }),
      cell("blocage strict ; signalement communautaire optionnel", { w: 3460 }) ]}),
    new TableRow({ children: [
      cell("Non vérifié", { w: 1900, fill: GREY, bold: true, color: "FFFFFF" }),
      cell("analyse incomplète (fail-closed)", { w: 4000 }),
      cell("pas d'ouverture directe ; inspection isolée ou nouvelle analyse", { w: 3460 }) ]}),
  ]}),
);

// --- 7. Opérationnel vs points d'intégration ------------------------------
children.push(
  H1("7. Ce qui est opérationnel vs points d'intégration"),
  P("Transparence sur l'état de maturité, pour éviter toute promesse non tenue."),
  new Table({ width: { size: CW, type: WidthType.DXA }, borders, rows: [
    new TableRow({ tableHeader: true, children: [
      cell("Composant", { w: 4600, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("État", { w: 4760, fill: NAVY, bold: true, color: "FFFFFF" }),
    ]}),
    new TableRow({ children: [ cell("Service d'analyse, pipeline, scoring, API", { w: 4600, fill: LIGHT }),
      cell("opérationnel et testé (26 tests automatisés)", { w: 4760, color: "1B7A33" }) ]}),
    new TableRow({ children: [ cell("Signature hybride Ed25519 + ML-DSA-65", { w: 4600, fill: LIGHT }),
      cell("opérationnel (serveur PQClean + client BouncyCastle)", { w: 4760, color: "1B7A33" }) ]}),
    new TableRow({ children: [ cell("Manifeste de clés signé SLH-DSA + rotation", { w: 4600, fill: LIGHT }),
      cell("opérationnel et testé", { w: 4760, color: "1B7A33" }) ]}),
    new TableRow({ children: [ cell("Enveloppe hybride ML-KEM-768 + X25519", { w: 4600, fill: LIGHT }),
      cell("opérationnel et testé", { w: 4760, color: "1B7A33" }) ]}),
    new TableRow({ children: [ cell("Détection capability / domaine / consensus / fail-closed", { w: 4600, fill: LIGHT }),
      cell("opérationnel et testé", { w: 4760, color: "1B7A33" }) ]}),
    new TableRow({ children: [ cell("OHTTP (option phase 2)", { w: 4600, fill: LIGHT }),
      cell("point d'intégration (Cloudflare/Fastly ou auto-hébergé)", { w: 4760, color: "9A6A00" }) ]}),
    new TableRow({ children: [ cell("Flux de réputation en direct (Safe Browsing v5, URLhaus)", { w: 4600, fill: LIGHT }),
      cell("adaptateurs prêts ; échantillon embarqué ; clés/réseau requis", { w: 4760, color: "9A6A00" }) ]}),
    new TableRow({ children: [ cell("Sortie résidentielle/mobile du bac à sable", { w: 4600, fill: LIGHT }),
      cell("point d'intégration (fournisseur de proxy)", { w: 4760, color: "9A6A00" }) ]}),
    new TableRow({ children: [ cell("Modèle visuel d'usurpation sur l'appareil (TFLite)", { w: 4600, fill: LIGHT }),
      cell("interface prête ; modèle à entraîner (sinon s'appuyer sur le domaine)", { w: 4760, color: "9A6A00" }) ]}),
    new TableRow({ children: [ cell("Compilation et tests de l'application Android", { w: 4600, fill: LIGHT }),
      cell("à réaliser dans Android Studio (non compilé en environnement serveur)", { w: 4760, color: "9A6A00" }) ]}),
  ]}),
);

// --- 8. Menaces actuelles -------------------------------------------------
children.push(
  H1("8. État des menaces (2025-2026)"),
  Bullet("le quishing s'industrialise (QR dans PDF/images pour contourner les filtres texte) et s'appuie sur des redirecteurs légitimes et du gating anti-robot — techniques visant directement l'analyse automatisée.", { lead: "Quishing 2.0 : " }),
  Bullet("le phishing conditionnel sert un contenu différent selon l'agent, la géo, l'heure ou un jeton à usage unique ; la divergence multi-profils n'en capte qu'une partie.", { lead: "Cloaking : " }),
  Bullet("les kits relayant la vraie page (Evilginx) défont l'analyse visuelle ; seule l'intelligence de domaine et comportementale les trahit.", { lead: "AiTM : " }),
  Bullet("un modèle embarqué est extractible et attaquable en boîte blanche (perturbations, texte-en-image) : il doit rester une couche parmi d'autres, jamais l'unique rempart.", { lead: "ML adverse : " }),
);

// --- 9. Concurrents -------------------------------------------------------
children.push(
  H1("9. Comparaison concurrentielle"),
  new Table({ width: { size: CW, type: WidthType.DXA }, borders, rows: [
    new TableRow({ tableHeader: true, children: [
      cell("Solution", { w: 2600, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Force", { w: 3380, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Limite vis-à-vis de BlokQR", { w: 3380, fill: NAVY, bold: true, color: "FFFFFF" }),
    ]}),
    new TableRow({ children: [ cell("Safe Browsing (OS)", { w: 2600, fill: LIGHT, bold: true }),
      cell("réputation gratuite, intégrée, temps réel", { w: 3380 }),
      cell("pas de rendu, ni contexte temporel/géo, ni sas dédié au scan", { w: 3380 }) ]}),
    new TableRow({ children: [ cell("VirusTotal / urlscan", { w: 2600, fill: LIGHT, bold: true }),
      cell("corpus et crowdsourcing massifs", { w: 3380 }),
      cell("pas d'UX scan ni de vie privée k-anonyme ; BlokQR les CONSOMME", { w: 3380 }) ]}),
    new TableRow({ children: [ cell("RBI (Cloudflare/Zscaler)", { w: 2600, fill: LIGHT, bold: true }),
      cell("isolation de navigation robuste", { w: 3380 }),
      cell("orienté entreprise ; ni mobile grand public, ni verdict signé PQ", { w: 3380 }) ]}),
    new TableRow({ children: [ cell("Scanners QR du marché", { w: 2600, fill: LIGHT, bold: true }),
      cell("simples, rapides", { w: 3380 }),
      cell("ouvrent souvent la cible directement ; pas de sas, pas de signature", { w: 3380 }) ]}),
  ]}),
);

// --- 10. Pile technique & déploiement -------------------------------------
children.push(
  H1("10. Pile technique et déploiement"),
  H2("10.1 Application Android"),
  P("Kotlin, Jetpack Compose, CameraX, ML Kit (décodage local), OkHttp (épinglage de certificat), Tink (Ed25519), BouncyCastle (ML-DSA-65, SLH-DSA, ML-KEM-768), DataStore, WorkManager."),
  H2("10.2 Service d'analyse"),
  P("Python/FastAPI sans état ; Playwright durci pour le rendu ; cryptographie PQClean (pqcrypto) ; en-têtes de sécurité, CORS restreint, limitation de débit, IP non journalisée ; déployé en conteneur durci (gVisor) sur VPS KVM."),
  H2("10.3 Feuille de route"),
  Num("brancher la passerelle Safe Browsing v5 (OHTTP) et agréger URLhaus/PhishTank — ROI le plus élevé.", { ref: "deploy" }),
  Num("désempreinter le bac à sable (sortie résidentielle, anti-headless, microVM).", { ref: "deploy" }),
  Num("valider l'interopérabilité ML-DSA/SLH-DSA serveur↔appareil, puis activer la vérification PQ stricte.", { ref: "deploy" }),
  Num("entraîner le modèle visuel d'usurpation (ou s'en tenir au domaine) ; option OHTTP via Safe Browsing v5 ou relais tiers.", { ref: "deploy" }),
  Num("durcir le consensus communautaire (résistance à l'empoisonnement, quorum, fenêtres).", { ref: "deploy" }),
);

// --- 11. Limites honnêtes -------------------------------------------------
children.push(
  H1("11. Limites assumées"),
  Bullet("une signature authentifie le transport, pas la justesse de l'analyse : un « sûr » signé reste faux si la menace a été manquée.", { lead: "Signature ≠ vérité : " }),
  Bullet("face à un kit déterminé qui détecte le headless et cloake sur des signaux non variés, la détection reste un jeu du chat et de la souris tant que la sortie n'est pas résidentielle.", { lead: "Cloaking avancé : " }),
  Bullet("le consensus communautaire introduit une surface d'empoisonnement à maîtriser (quorum, pondération, jamais décisionnel seul).", { lead: "Consensus : " }),
  Bullet("la valeur du palier rapide dépend entièrement de la qualité des flux branchés.", { lead: "Réputation : " }),
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
    { reference: "numbers", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] },
    { reference: "deploy", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] },
  ]},
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840 },
      margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
    headers: { default: new Header({ children: [ new Paragraph({ alignment: AlignmentType.RIGHT,
      border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "CCCCCC", space: 4 } },
      children: [new TextRun({ text: "BlokQR — Proposition de projet v3.0", font: "Arial", size: 16, color: GREY })] }) ] }) },
    footers: { default: new Footer({ children: [ new Paragraph({ alignment: AlignmentType.CENTER,
      children: [ new TextRun({ text: "Page ", font: "Arial", size: 16, color: GREY }),
        new TextRun({ children: [PageNumber.CURRENT], font: "Arial", size: 16, color: GREY }) ] }) ] }) },
    children,
  }],
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("/home/claude/blokqr/docs/BlokQR_Proposition_v3.docx", buffer);
  console.log("OK proposition v3 générée");
});
