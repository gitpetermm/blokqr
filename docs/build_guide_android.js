/* Génère le guide Android (build, configuration, interop, CI/CD) en .docx */
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, LevelFormat, HeadingLevel, BorderStyle, WidthType,
  ShadingType, PageNumber, Header, Footer, TableOfContents, PageBreak, VerticalAlign,
} = require("docx");

const NAVY = "0B2545", BLUE = "2E75B6", LIGHT = "D5E8F0", GREY = "5A6B7B", CODEBG = "F2F4F7";
const CW = 9360;
const P = (t, o = {}) => new Paragraph({ spacing: { after: o.after ?? 120, before: o.before ?? 0, line: 276 }, alignment: o.align,
  children: [new TextRun({ text: t, font: "Arial", size: o.size ?? 22, bold: o.bold, italics: o.italics, color: o.color ?? "222222" })] });
const Runs = (runs, o = {}) => new Paragraph({ spacing: { after: o.after ?? 120, line: 276 },
  children: runs.map(r => new TextRun({ font: "Arial", size: 22, ...r })) });
const H1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun({ text: t })] });
const H2 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun({ text: t })] });
const Bullet = (t, o = {}) => new Paragraph({ numbering: { reference: "bullets", level: 0 }, spacing: { after: 80, line: 276 },
  children: [...(o.lead ? [new TextRun({ text: o.lead, bold: true, font: "Arial", size: 22, color: NAVY })] : []),
    new TextRun({ text: t, font: "Arial", size: 22, color: "222222" })] });
const Code = (lines) => new Paragraph({ shading: { type: ShadingType.CLEAR, fill: CODEBG },
  spacing: { before: 60, after: 120, line: 240 },
  border: { left: { style: BorderStyle.SINGLE, size: 18, color: BLUE, space: 8 } },
  children: lines.map((ln, i) => new TextRun({ text: ln, font: "Consolas", size: 18, color: "1A1A1A", break: i === 0 ? 0 : 1 })) });
const cell = (t, { w, fill, bold, color } = {}) => new TableCell({ width: { size: w, type: WidthType.DXA },
  shading: fill ? { fill, type: ShadingType.CLEAR } : undefined, margins: { top: 70, bottom: 70, left: 110, right: 110 },
  verticalAlign: VerticalAlign.CENTER, children: [new Paragraph({ children: [new TextRun({ text: t, font: "Arial", size: 19, bold, color: color ?? "222222" })] })] });
const bd = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: bd, bottom: bd, left: bd, right: bd, insideHorizontal: bd, insideVertical: bd };
const T = (rows) => new Table({ width: { size: CW, type: WidthType.DXA }, borders, rows });
const Trow = (cells) => new TableRow({ children: cells });

const c = [];
c.push(
  new Paragraph({ spacing: { before: 2600 }, alignment: AlignmentType.CENTER,
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: BLUE, space: 8 } },
    children: [new TextRun({ text: "BLOKQR", font: "Arial", size: 64, bold: true, color: NAVY })] }),
  new Paragraph({ spacing: { before: 240 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Guide Android — build, configuration, CI/CD", font: "Arial", size: 30, color: BLUE })] }),
  new Paragraph({ spacing: { before: 120 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "De zéro à l'APK sur le téléphone, contre le backend en production", font: "Arial", size: 21, italics: true, color: GREY })] }),
  new Paragraph({ spacing: { before: 2600 }, alignment: AlignmentType.CENTER,
    children: [new TextRun({ text: "Version 3.0 — backend déjà en ligne sur api.blokqr.com", font: "Arial", size: 22, bold: true, color: NAVY })] }),
  new Paragraph({ children: [new PageBreak()] }),
  H1("Sommaire"),
  new TableOfContents("Sommaire", { hyperlink: true, headingStyleRange: "1-2" }),
  new Paragraph({ children: [new PageBreak()] }),
);

// 1. Réalité du build
c.push(
  H1("1. La réalité du build Android"),
  Runs([{ text: "L'app Android ne se compile pas sur le VPS.", bold: true, color: NAVY },
    { text: " Le VPS héberge l'API ; l'application, elle, tourne sur des téléphones et se construit avec le SDK Android + Gradle. Votre pipeline CI/CD ne « déploie » donc pas l'app sur le VPS comme le backend : il produit un ARTEFACT installable (APK/AAB)." }]),
  P("Deux chemins, complémentaires :"),
  Bullet("Android Studio sur votre poste — indispensable au DÉBUT (émulateur, logcat, débogage de l'interopérabilité post-quantique). C'est aussi ce qui génère le Gradle wrapper.", { lead: "Local : " }),
  Bullet("GitHub Actions — pour automatiser ensuite les builds signés (workflow fourni). C'est le prolongement naturel de votre CI/CD existante.", { lead: "CI : " }),
  Runs([{ text: "Ordre recommandé : ", bold: true, color: NAVY },
    { text: "1) build local + validation de l'interop PQ ; 2) automatisation CI ; 3) distribution. Tenter la CI d'abord sur un projet jamais compilé fait perdre du temps (l'émulateur et logcat diagnostiquent bien plus vite)." }]),
);

// 2. Prérequis
c.push(
  H1("2. Prérequis (poste local)"),
  Bullet("Android Studio (Koala ou plus récent) — embarque le SDK et le wrapper Gradle.", {}),
  Bullet("JDK 17 (fourni par Android Studio), SDK Platform 35 + Build-Tools 35 (AGP 8.5.2, Gradle 8.9).", {}),
  Bullet("Un téléphone Android 8.0+ (API 26) en débogage USB, ou un émulateur.", {}),
  P("VS Code peut éditer le Kotlin, mais le build/émulateur passe par le SDK Android (CLI ou Android Studio)."),
);

// 3. Configuration
c.push(
  H1("3. Configuration (à faire maintenant, backend en ligne)"),
  P("L'app n'épingle que deux valeurs publiques : la racine de confiance SLH-DSA et l'empreinte TLS. Un script les extrait du backend en production :"),
  Code(["bash deploy/extract-android-pins.sh api.blokqr.com"]),
  P("Reportez les trois lignes affichées dans android/app/src/main/java/com/blokqr/app/Config.kt :"),
  Code(["const val API_BASE_URL = \"https://api.blokqr.com\"",
    "const val PINNED_SLHDSA_ROOT_PUBKEY_B64 = \"<racine SLH-DSA>\"",
    "const val CERT_PIN_SHA256 = \"sha256/<empreinte>\""]),
  Runs([{ text: "Ces valeurs sont publiques", bold: true, color: NAVY },
    { text: " (clés/empreintes d'épinglage) : vous pouvez les committer. Seul le keystore de signature reste secret." }]),
);

// 4. Premier build + interop
c.push(
  H1("4. Premier build local et jalon critique"),
  P("Ouvrez le dossier android/ dans Android Studio (Open). Il synchronise Gradle et génère le wrapper. Lancez ensuite :"),
  Code(["./gradlew assembleDebug        # ou bouton Run sur un appareil/émulateur"]),
  Runs([{ text: "Jalon n°1 — valider l'interopérabilité post-quantique. ", bold: true, color: NAVY },
    { text: "C'est le point de vigilance principal : la signature ML-DSA-65 est produite côté serveur par PQClean et vérifiée côté appareil par BouncyCastle. Les deux visent FIPS 204, mais l'encodage brut doit concorder." }]),
  P("Test : scannez un QR encodant « https://www.paypa1.com/login ». Attendu : verdict « Dangereux » ET vérification de signature réussie (Ed25519 + ML-DSA-65)."),
  Bullet("Si la vérification ÉCHOUE sur la partie PQ : passez temporairement REQUIRE_PQ_VERIFICATION=false dans Config.kt pour confirmer que la chaîne Ed25519 fonctionne de bout en bout (isole le problème au PQ).", {}),
  Bullet("Causes probables : encodage de clé publique attendu différemment par BouncyCastle (brut vs SubjectPublicKeyInfo), ou chaîne de contexte ML-DSA non vide. Alignez les deux côtés sur l'encodage brut FIPS et un contexte vide, puis réactivez la vérification stricte.", {}),
  P("Tant que ce jalon n'est pas franchi, ne publiez pas : la promesse hybride n'est tenue qu'à la double vérification."),
);

// 5. CI/CD
c.push(
  H1("5. CI/CD — GitHub Actions"),
  P("Le workflow .github/workflows/android.yml est fourni. À chaque push touchant android/, il construit lint + tests + APK de debug (artefact). Sur un tag v*, il produit un APK/AAB SIGNÉ et publie une Release GitHub."),
  H2("5.1 Générer le keystore de release (une fois)"),
  Code(["keytool -genkeypair -v -keystore release.keystore \\",
    "  -alias blokqr -keyalg RSA -keysize 4096 -validity 10000",
    "# Sauvegardez release.keystore HORS dépôt et hors ligne (perte = plus de MAJ Play Store)."]),
  H2("5.2 Déposer les secrets GitHub"),
  P("Dans Settings > Secrets and variables > Actions, créez :"),
  Code(["KEYSTORE_BASE64     # base64 -w0 release.keystore",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS           # blokqr",
    "KEY_PASSWORD"]),
  P("Le build Gradle lit ces variables (config de signature déjà ajoutée) ; en local, un fichier keystore.properties non commité joue le même rôle."),
  H2("5.3 Déclencher un build signé"),
  Code(["git tag v1.0.0 && git push origin v1.0.0"]),
  P("La Release GitHub contiendra l'APK (sideload) et l'AAB (Play Store)."),
);

// 6. Minification
c.push(
  H1("6. Minification (déjà sécurisée)"),
  P("La release active R8 (isMinifyEnabled). Sans précaution, R8 supprimerait les classes BouncyCastle chargées par réflexion et casserait ML-DSA/SLH-DSA/ML-KEM. Les règles de conservation (proguard-rules.pro) sont déjà en place pour BouncyCastle, Tink, OkHttp et kotlinx.serialization. Vérifiez après le premier build de release que la vérification PQ fonctionne toujours."),
);

// 7. Distribution
c.push(
  H1("7. Distribution"),
  T([
    Trow([cell("Option", { w: 2800, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Quand l'utiliser", { w: 6560, fill: NAVY, bold: true, color: "FFFFFF" })]),
    Trow([cell("Play Store (AAB)", { w: 2800, fill: LIGHT, bold: true }),
      cell("diffusion grand public ; piste « test interne » d'abord pour valider sur appareils réels", { w: 6560 })]),
    Trow([cell("APK auto-hébergé", { w: 2800, fill: LIGHT, bold: true }),
      cell("héberger l'APK signé sur le VPS (ex. https://blokqr.com/download) pour sideload ; prévenir l'utilisateur (sources inconnues)", { w: 6560 })]),
    Trow([cell("Release GitHub", { w: 2800, fill: LIGHT, bold: true }),
      cell("distribution aux testeurs/équipe via le tag v* (déjà automatisé)", { w: 6560 })]),
  ]),
);

// 8. Dépannage
c.push(
  H1("8. Dépannage"),
  T([
    Trow([cell("Symptôme", { w: 3800, fill: NAVY, bold: true, color: "FFFFFF" }),
      cell("Remède", { w: 5560, fill: NAVY, bold: true, color: "FFFFFF" })]),
    Trow([cell("« Verdict non authentifié : Signature ML-DSA-65 invalide »", { w: 3800, fill: LIGHT }),
      cell("problème d'interop d'encodage (voir §4) ; isoler avec REQUIRE_PQ_VERIFICATION=false", { w: 5560 })]),
    Trow([cell("crypto KO seulement en release", { w: 3800, fill: LIGHT }),
      cell("règles R8 ; vérifier proguard-rules.pro (BouncyCastle/Tink conservés)", { w: 5560 })]),
    Trow([cell("CI : ./gradlew introuvable", { w: 3800, fill: LIGHT }),
      cell("le wrapper n'est pas commité ; le workflow le génère, ou commitez-le après le 1er build local", { w: 5560 })]),
    Trow([cell("CertPin échoue", { w: 3800, fill: LIGHT }),
      cell("re-extraire l'empreinte (le certif a pu être renouvelé) via extract-android-pins.sh", { w: 5560 })]),
    Trow([cell("SSL/cleartext bloqué", { w: 3800, fill: LIGHT }),
      cell("l'app n'autorise que HTTPS ; vérifier que api.blokqr.com répond en TLS", { w: 5560 })]),
  ]),
);

const doc = new Document({
  creator: "BlokQR",
  styles: { default: { document: { run: { font: "Arial", size: 22, color: "222222" } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 30, bold: true, font: "Arial", color: NAVY },
        paragraph: { spacing: { before: 320, after: 160 }, outlineLevel: 0,
          border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: BLUE, space: 4 } } } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 25, bold: true, font: "Arial", color: BLUE },
        paragraph: { spacing: { before: 200, after: 120 }, outlineLevel: 1 } },
    ] },
  numbering: { config: [{ reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "•",
    alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 280 } } } }] }] },
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840 }, margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
    headers: { default: new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT,
      border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "CCCCCC", space: 4 } },
      children: [new TextRun({ text: "BlokQR — Guide Android", font: "Arial", size: 16, color: GREY })] })] }) },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER,
      children: [new TextRun({ text: "Page ", font: "Arial", size: 16, color: GREY }),
        new TextRun({ children: [PageNumber.CURRENT], font: "Arial", size: 16, color: GREY })] })] }) },
    children: c,
  }],
});

Packer.toBuffer(doc).then(b => { fs.writeFileSync("/home/claude/blokqr/docs/BlokQR_Guide_Android.docx", b); console.log("OK guide Android généré"); });
