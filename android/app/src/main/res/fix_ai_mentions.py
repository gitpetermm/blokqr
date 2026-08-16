#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Audit pré-publication — retrait des mentions « IA embarquée » (fonctionnalité
non encore disponible : le modèle .tflite n'est pas livré) + alignement du
modèle Free (7 vérifications/jour).

RÉVISE 3 clés existantes, dans les 12 langues :
  - about_description   : ajoute « (jusqu'à 7 vérifications par jour) »,
                          retire « par IA embarquée » (garde « détection d'usurpation »
                          qui, elle, existe côté serveur).
  - about_feature_ai    : remplace le bullet « IA embarquée » par une vraie
                          fonctionnalité serveur (détection d'usurpation de marque
                          et de pages de connexion frauduleuses).
  - privacy_s4_body     : la capture est PRÉSENTÉE dans l'app (pas analysée par
                          une IA locale).

NB : la détection d'usurpation (login_impersonation) est produite CÔTÉ SERVEUR
(analyse Playwright du DOM) — elle reste donc mentionnée, on retire seulement
l'attribution erronée « par IA embarquée / sur l'appareil ».

Idempotent : remplace le CONTENU des clés ciblées par regex sur le name.
Relancer ne casse rien (réécrit la même valeur).

Usage :
    python fix_ai_mentions.py [chemin_res]   (défaut : app/src/main/res)
"""
import os
import re
import sys

REVISE = {
    "": {
        "about_description": "BlokQR décode les QR codes et codes-barres directement sur l\\'appareil, puis analyse leur destination SANS jamais l\\'ouvrir. La cible est inspectée par un service distant qui suit les redirections et renvoie un verdict signé (sûr, prudence, dangereux), pour vous protéger de l\\'hameçonnage et des liens malveillants avant tout clic. L\\'analyse rapide est gratuite (jusqu\\'à 7 vérifications par jour) ; l\\'analyse approfondie (rendu en bac à sable et détection d\\'usurpation) est réservée aux abonnés Pro.",
        "about_feature_ai": "Détection d\\'usurpation de marque et de pages de connexion frauduleuses.",
        "privacy_s4_body": "Pour l\\'analyse approfondie, notre service produit une capture de la page cible, rendue côté serveur, qui vous est présentée dans l\\'application. Elle n\\'est pas partagée avec des tiers.",
    },
    "en": {
        "about_description": "BlokQR decodes QR codes and barcodes directly on your device, then analyzes their destination WITHOUT ever opening it. The target is inspected by a remote service that follows redirects and returns a signed verdict (safe, caution, dangerous), protecting you from phishing and malicious links before any click. Quick analysis is free (up to 7 checks per day); in-depth analysis (sandbox rendering and impersonation detection) is reserved for Pro subscribers.",
        "about_feature_ai": "Detection of brand impersonation and fraudulent login pages.",
        "privacy_s4_body": "For in-depth analysis, our service produces a screenshot of the target page, rendered server-side, which is shown to you in the app. It is not shared with third parties.",
    },
    "es": {
        "about_description": "BlokQR decodifica los códigos QR y de barras directamente en el dispositivo y luego analiza su destino SIN abrirlo nunca. El destino es inspeccionado por un servicio remoto que sigue las redirecciones y devuelve un veredicto firmado (seguro, precaución, peligroso), para protegerte del phishing y los enlaces maliciosos antes de cualquier clic. El análisis rápido es gratuito (hasta 7 comprobaciones al día); el análisis en profundidad (renderizado en espacio aislado y detección de suplantación) está reservado a los suscriptores Pro.",
        "about_feature_ai": "Detección de suplantación de marcas y de páginas de inicio de sesión fraudulentas.",
        "privacy_s4_body": "Para el análisis en profundidad, nuestro servicio genera una captura de la página de destino, renderizada en el servidor, que se te muestra en la aplicación. No se comparte con terceros.",
    },
    "pt": {
        "about_description": "O BlokQR decodifica códigos QR e de barras diretamente no dispositivo e depois analisa o destino deles SEM nunca abri-lo. O destino é inspecionado por um serviço remoto que segue os redirecionamentos e devolve um veredicto assinado (seguro, atenção, perigoso), para protegê-lo de phishing e links maliciosos antes de qualquer clique. A análise rápida é gratuita (até 7 verificações por dia); a análise aprofundada (renderização em ambiente isolado e deteção de falsificação) é reservada aos assinantes Pro.",
        "about_feature_ai": "Deteção de falsificação de marcas e de páginas de início de sessão fraudulentas.",
        "privacy_s4_body": "Para a análise aprofundada, o nosso serviço produz uma captura da página de destino, renderizada no servidor, que lhe é apresentada na aplicação. Não é partilhada com terceiros.",
    },
    "it": {
        "about_description": "BlokQR decodifica i codici QR e a barre direttamente sul dispositivo, poi analizza la loro destinazione SENZA mai aprirla. La destinazione viene ispezionata da un servizio remoto che segue i reindirizzamenti e restituisce un verdetto firmato (sicuro, attenzione, pericoloso), per proteggerti dal phishing e dai link dannosi prima di ogni clic. L\\'analisi rapida è gratuita (fino a 7 verifiche al giorno); l\\'analisi approfondita (rendering in ambiente isolato e rilevamento di contraffazione) è riservata agli abbonati Pro.",
        "about_feature_ai": "Rilevamento di contraffazione di marchi e di pagine di accesso fraudolente.",
        "privacy_s4_body": "Per l\\'analisi approfondita, il nostro servizio produce uno screenshot della pagina di destinazione, renderizzato sul server, che ti viene mostrato nell\\'app. Non è condiviso con terze parti.",
    },
    "de": {
        "about_description": "BlokQR dekodiert QR-Codes und Barcodes direkt auf dem Gerät und analysiert dann ihr Ziel, OHNE es jemals zu öffnen. Das Ziel wird von einem entfernten Dienst geprüft, der Weiterleitungen folgt und ein signiertes Urteil zurückgibt (sicher, Vorsicht, gefährlich), um Sie vor Phishing und schädlichen Links vor jedem Klick zu schützen. Die Schnellanalyse ist kostenlos (bis zu 7 Prüfungen pro Tag); die tiefgehende Analyse (Sandbox-Rendering und Erkennung von Markenfälschung) ist Pro-Abonnenten vorbehalten.",
        "about_feature_ai": "Erkennung von Markenfälschung und betrügerischen Anmeldeseiten.",
        "privacy_s4_body": "Für die tiefgehende Analyse erstellt unser Dienst eine serverseitig gerenderte Aufnahme der Zielseite, die Ihnen in der App angezeigt wird. Sie wird nicht an Dritte weitergegeben.",
    },
    "ar": {
        "about_description": "يفك BlokQR ترميز رموز QR والباركود مباشرةً على جهازك، ثم يحلل وجهتها دون فتحها أبدًا. تُفحص الوجهة بواسطة خدمة بعيدة تتتبّع عمليات إعادة التوجيه وتُعيد حكمًا موقَّعًا (آمن، حذر، خطير)، لحمايتك من التصيّد والروابط الضارة قبل أي نقرة. التحليل السريع مجاني (حتى 7 عمليات تحقق يوميًا)؛ أما التحليل المتعمق (العرض في بيئة معزولة وكشف الانتحال) فمخصّص لمشتركي Pro.",
        "about_feature_ai": "كشف انتحال العلامات التجارية وصفحات تسجيل الدخول الاحتيالية.",
        "privacy_s4_body": "للتحليل المتعمق، تُنتج خدمتنا لقطة لصفحة الوجهة، تُعرَض من جانب الخادم، وتُعرض لك داخل التطبيق. ولا تتم مشاركتها مع أي طرف ثالث.",
    },
    "tr": {
        "about_description": "BlokQR, QR kodlarını ve barkodları doğrudan cihazınızda çözer, ardından hedeflerini asla açmadan analiz eder. Hedef, yönlendirmeleri izleyen ve imzalı bir karar (güvenli, dikkat, tehlikeli) döndüren uzak bir hizmet tarafından incelenir; böylece herhangi bir tıklamadan önce sizi kimlik avından ve kötü amaçlı bağlantılardan korur. Hızlı analiz ücretsizdir (günde en fazla 7 denetim); derinlemesine analiz (yalıtılmış ortamda işleme ve taklit tespiti) Pro abonelerine ayrılmıştır.",
        "about_feature_ai": "Marka taklidi ve sahte oturum açma sayfalarının tespiti.",
        "privacy_s4_body": "Derinlemesine analiz için hizmetimiz, hedef sayfanın sunucu tarafında işlenen bir ekran görüntüsünü üretir ve bu görüntü uygulamada size gösterilir. Üçüncü taraflarla paylaşılmaz.",
    },
    "hi": {
        "about_description": "BlokQR QR कोड और बारकोड को सीधे आपके डिवाइस पर डिकोड करता है, फिर उन्हें कभी खोले बिना उनके गंतव्य का विश्लेषण करता है। गंतव्य की जाँच एक दूरस्थ सेवा द्वारा की जाती है जो रीडायरेक्ट का अनुसरण करती है और एक हस्ताक्षरित निर्णय (सुरक्षित, सावधानी, खतरनाक) लौटाती है, ताकि किसी भी क्लिक से पहले आपको फ़िशिंग और दुर्भावनापूर्ण लिंक से बचाया जा सके। त्वरित विश्लेषण निःशुल्क है (प्रतिदिन 7 जाँच तक); गहन विश्लेषण (पृथक वातावरण में रेंडरिंग और प्रतिरूपण पहचान) Pro ग्राहकों के लिए आरक्षित है।",
        "about_feature_ai": "ब्रांड प्रतिरूपण और धोखाधड़ी वाले लॉगिन पृष्ठों की पहचान।",
        "privacy_s4_body": "गहन विश्लेषण के लिए, हमारी सेवा गंतव्य पृष्ठ का एक स्क्रीनशॉट तैयार करती है, जो सर्वर पर रेंडर होता है और आपको ऐप में दिखाया जाता है। इसे किसी तीसरे पक्ष के साथ साझा नहीं किया जाता।",
    },
    "zh-rCN": {
        "about_description": "BlokQR 直接在您的设备上解码二维码和条形码，然后在从不打开的情况下分析其目标地址。目标地址由远程服务检查，该服务会跟踪重定向并返回经过签名的判定结果（安全、谨慎、危险），在您点击之前保护您免受网络钓鱼和恶意链接的侵害。快速分析免费（每天最多 7 次检查）；深度分析（隔离环境渲染和仿冒检测）仅限 Pro 订阅者使用。",
        "about_feature_ai": "检测品牌仿冒和欺诈性登录页面。",
        "privacy_s4_body": "进行深度分析时，我们的服务会生成目标页面的截图（在服务器端渲染），并在应用中向您展示。该截图不会与第三方共享。",
    },
    "ja": {
        "about_description": "BlokQR は QR コードやバーコードをデバイス上で直接デコードし、その遷移先を一度も開くことなく分析します。遷移先はリダイレクトを追跡するリモートサービスによって検査され、署名付きの判定（安全・注意・危険）を返します。これにより、クリックする前にフィッシングや悪意のあるリンクからあなたを保護します。クイック分析は無料です（1 日最大 7 回のチェック）。詳細分析（隔離環境でのレンダリングとなりすまし検出）は Pro 加入者専用です。",
        "about_feature_ai": "ブランドのなりすましや不正なログインページの検出。",
        "privacy_s4_body": "詳細分析では、当サービスが遷移先ページのスクリーンショットをサーバー側で生成し、アプリ内でお客様に表示します。第三者と共有することはありません。",
    },
    "ru": {
        "about_description": "BlokQR декодирует QR-коды и штрихкоды прямо на вашем устройстве, а затем анализирует их назначение, НИКОГДА его не открывая. Назначение проверяется удалённым сервисом, который отслеживает перенаправления и возвращает подписанный вердикт (безопасно, осторожно, опасно), защищая вас от фишинга и вредоносных ссылок до любого нажатия. Быстрый анализ бесплатен (до 7 проверок в день); углублённый анализ (рендеринг в изолированной среде и обнаружение подделки) доступен только подписчикам Pro.",
        "about_feature_ai": "Обнаружение подделки брендов и мошеннических страниц входа.",
        "privacy_s4_body": "Для углублённого анализа наш сервис создаёт снимок целевой страницы, отрисованный на стороне сервера, который показывается вам в приложении. Он не передаётся третьим сторонам.",
    },
}


def values_dir(res_root, lang):
    return os.path.join(res_root, "values" if lang == "" else f"values-{lang}")


def strings_path(res_root, lang):
    return os.path.join(values_dir(res_root, lang), "strings.xml")


def revise(path, kv):
    if not os.path.isfile(path):
        return -1
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    n = 0
    for name, new_val in kv.items():
        pattern = re.compile(
            rf'(<string name="{re.escape(name)}"[^>]*>).*?(</string>)',
            re.S,
        )
        new_src, count = pattern.subn(rf'\g<1>{new_val}\g<2>', src)
        if count:
            src = new_src
            n += count
    if n:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
    return n


def main():
    res_root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
    if not os.path.isdir(res_root):
        print(f"ERREUR : dossier res introuvable : {res_root}")
        sys.exit(1)
    langs = ["", "en", "es", "pt", "it", "de", "ar", "tr", "hi", "zh-rCN", "ja", "ru"]
    total = 0
    for lang in langs:
        path = strings_path(res_root, lang)
        label = "values/" if lang == "" else f"values-{lang}/"
        n = revise(path, REVISE.get(lang, {}))
        if n < 0:
            print(f"  {label:<16} ⚠ ABSENT")
        else:
            print(f"  {label:<16} {n} clé(s) révisée(s)")
            total += n
    print(f"\nTerminé. {total} révision(s) au total.")


if __name__ == "__main__":
    main()
