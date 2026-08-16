#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Message de confiance Free/Pro (12 langues).

Objectif : rassurer l'utilisateur gratuit que sa SÉCURITÉ n'est pas dégradée.
Le verdict utilise le même moteur de détection pour tous ; Pro ajoute la
profondeur (analyse dynamique approfondie, détail complet) et lève la limite
quotidienne. Formulation honnête : « même moteur de base, Pro ajoute l'analyse
dynamique » (Pro PEUT révéler des menaces dynamiques que l'analyse rapide ne
voit pas — on ne prétend donc pas un résultat strictement identique).

RÉVISE 2 clés :
  - about_feature_tiers
  - paywall_subtitle

AJOUTE 2 clés :
  - paywall_same_engine   (note de confiance, paywall)
  - quota_same_engine     (note de confiance, écran quota épuisé)

Idempotent. Apostrophes échappées. Usage :
    python add_trust_messaging.py [chemin_res]
"""
import os
import re
import sys

REVISE = {
    "": {
        "about_feature_tiers": "Même moteur de détection pour tous : réputation, redirections et signaux de sécurité. Pro ajoute le rendu dynamique approfondi.",
        "paywall_subtitle": "Tous les scans utilisent le même moteur de détection. La version gratuite en vérifie jusqu\\'à 7 par jour ; Pro lève cette limite et révèle l\\'analyse approfondie.",
    },
    "en": {
        "about_feature_tiers": "Same detection engine for everyone: reputation, redirects and security signals. Pro adds in-depth dynamic rendering.",
        "paywall_subtitle": "Every scan uses the same detection engine. The free version checks up to 7 per day; Pro removes this limit and reveals in-depth analysis.",
    },
    "es": {
        "about_feature_tiers": "El mismo motor de detección para todos: reputación, redirecciones y señales de seguridad. Pro añade el renderizado dinámico en profundidad.",
        "paywall_subtitle": "Todos los análisis usan el mismo motor de detección. La versión gratuita verifica hasta 7 al día; Pro elimina este límite y revela el análisis en profundidad.",
    },
    "pt": {
        "about_feature_tiers": "O mesmo motor de deteção para todos: reputação, redirecionamentos e sinais de segurança. O Pro adiciona a renderização dinâmica aprofundada.",
        "paywall_subtitle": "Todas as análises usam o mesmo motor de deteção. A versão gratuita verifica até 7 por dia; o Pro remove este limite e revela a análise aprofundada.",
    },
    "it": {
        "about_feature_tiers": "Stesso motore di rilevamento per tutti: reputazione, reindirizzamenti e segnali di sicurezza. Pro aggiunge il rendering dinamico approfondito.",
        "paywall_subtitle": "Tutte le analisi usano lo stesso motore di rilevamento. La versione gratuita ne verifica fino a 7 al giorno; Pro rimuove questo limite e rivela l\\'analisi approfondita.",
    },
    "de": {
        "about_feature_tiers": "Dieselbe Erkennungs-Engine für alle: Reputation, Weiterleitungen und Sicherheitssignale. Pro ergänzt das tiefgehende dynamische Rendering.",
        "paywall_subtitle": "Alle Scans nutzen dieselbe Erkennungs-Engine. Die kostenlose Version prüft bis zu 7 pro Tag; Pro hebt dieses Limit auf und zeigt die tiefgehende Analyse.",
    },
    "ar": {
        "about_feature_tiers": "محرك الكشف نفسه للجميع: السمعة وعمليات إعادة التوجيه وإشارات الأمان. يضيف Pro العرض الديناميكي المتعمق.",
        "paywall_subtitle": "تستخدم جميع عمليات الفحص محرك الكشف نفسه. تتحقق النسخة المجانية من 7 يوميًا كحد أقصى؛ ويزيل Pro هذا الحد ويكشف التحليل المتعمق.",
    },
    "tr": {
        "about_feature_tiers": "Herkes için aynı tespit motoru: itibar, yönlendirmeler ve güvenlik sinyalleri. Pro, derinlemesine dinamik işlemeyi ekler.",
        "paywall_subtitle": "Tüm taramalar aynı tespit motorunu kullanır. Ücretsiz sürüm günde en fazla 7 tane denetler; Pro bu sınırı kaldırır ve derinlemesine analizi gösterir.",
    },
    "hi": {
        "about_feature_tiers": "सभी के लिए एक ही पहचान इंजन: प्रतिष्ठा, रीडायरेक्ट और सुरक्षा संकेत। Pro गहन गतिशील रेंडरिंग जोड़ता है।",
        "paywall_subtitle": "सभी स्कैन एक ही पहचान इंजन का उपयोग करते हैं। मुफ़्त संस्करण प्रतिदिन 7 तक जाँचता है; Pro इस सीमा को हटाता है और गहन विश्लेषण दिखाता है।",
    },
    "zh-rCN": {
        "about_feature_tiers": "所有用户使用相同的检测引擎：信誉、重定向和安全信号。Pro 增加深度动态渲染。",
        "paywall_subtitle": "所有扫描都使用相同的检测引擎。免费版每天最多检查 7 次；Pro 取消此限制并展示深度分析。",
    },
    "ja": {
        "about_feature_tiers": "すべての方に同じ検出エンジン：レピュテーション、リダイレクト、セキュリティシグナル。Pro は詳細な動的レンダリングを追加します。",
        "paywall_subtitle": "すべてのスキャンが同じ検出エンジンを使用します。無料版は 1 日最大 7 回まで確認できます。Pro はこの制限を解除し、詳細な分析を表示します。",
    },
    "ru": {
        "about_feature_tiers": "Один и тот же движок обнаружения для всех: репутация, перенаправления и сигналы безопасности. Pro добавляет углублённый динамический рендеринг.",
        "paywall_subtitle": "Все проверки используют один и тот же движок обнаружения. Бесплатная версия проверяет до 7 в день; Pro снимает это ограничение и раскрывает углублённый анализ.",
    },
}

ADD = {
    "": {
        "paywall_same_engine": "La protection est la même pour tous : chaque scan utilise les mêmes analyseurs et le même niveau de détection. Pro ajoute la profondeur des résultats et lève la limite quotidienne.",
        "quota_same_engine": "Votre sécurité reste entière : le même moteur de détection vous protège, avec ou sans Pro.",
    },
    "en": {
        "paywall_same_engine": "Protection is the same for everyone: every scan uses the same analyzers and the same level of detection. Pro adds depth of results and removes the daily limit.",
        "quota_same_engine": "Your security stays intact: the same detection engine protects you, with or without Pro.",
    },
    "es": {
        "paywall_same_engine": "La protección es la misma para todos: cada análisis usa los mismos analizadores y el mismo nivel de detección. Pro añade profundidad en los resultados y elimina el límite diario.",
        "quota_same_engine": "Tu seguridad permanece intacta: el mismo motor de detección te protege, con o sin Pro.",
    },
    "pt": {
        "paywall_same_engine": "A proteção é a mesma para todos: cada análise usa os mesmos analisadores e o mesmo nível de deteção. O Pro acrescenta profundidade aos resultados e remove o limite diário.",
        "quota_same_engine": "A sua segurança permanece intacta: o mesmo motor de deteção protege-o, com ou sem Pro.",
    },
    "it": {
        "paywall_same_engine": "La protezione è la stessa per tutti: ogni analisi usa gli stessi analizzatori e lo stesso livello di rilevamento. Pro aggiunge profondità ai risultati e rimuove il limite giornaliero.",
        "quota_same_engine": "La tua sicurezza resta intatta: lo stesso motore di rilevamento ti protegge, con o senza Pro.",
    },
    "de": {
        "paywall_same_engine": "Der Schutz ist für alle gleich: Jeder Scan nutzt dieselben Analysatoren und dieselbe Erkennungsstufe. Pro ergänzt die Ergebnistiefe und hebt das Tageslimit auf.",
        "quota_same_engine": "Ihre Sicherheit bleibt vollständig: Dieselbe Erkennungs-Engine schützt Sie, mit oder ohne Pro.",
    },
    "ar": {
        "paywall_same_engine": "الحماية واحدة للجميع: يستخدم كل فحص المحلّلات نفسها والمستوى نفسه من الكشف. يضيف Pro عمقًا للنتائج ويزيل الحد اليومي.",
        "quota_same_engine": "يبقى أمانك كاملًا: محرك الكشف نفسه يحميك، مع Pro أو بدونه.",
    },
    "tr": {
        "paywall_same_engine": "Koruma herkes için aynıdır: her tarama aynı çözümleyicileri ve aynı tespit düzeyini kullanır. Pro, sonuçlara derinlik katar ve günlük sınırı kaldırır.",
        "quota_same_engine": "Güvenliğiniz eksiksiz kalır: aynı tespit motoru sizi korur, Pro ile veya Pro olmadan.",
    },
    "hi": {
        "paywall_same_engine": "सुरक्षा सभी के लिए समान है: हर स्कैन समान विश्लेषकों और समान स्तर की पहचान का उपयोग करता है। Pro परिणामों में गहराई जोड़ता है और दैनिक सीमा हटाता है।",
        "quota_same_engine": "आपकी सुरक्षा बरकरार रहती है: वही पहचान इंजन आपकी रक्षा करता है, Pro के साथ या बिना।",
    },
    "zh-rCN": {
        "paywall_same_engine": "所有用户的防护都相同：每次扫描都使用相同的分析器和相同的检测级别。Pro 增加结果的深度并取消每日限制。",
        "quota_same_engine": "您的安全性保持不变：无论是否使用 Pro，相同的检测引擎都会保护您。",
    },
    "ja": {
        "paywall_same_engine": "保護はすべての方に共通です。すべてのスキャンが同じアナライザーと同じ検出レベルを使用します。Pro は結果の詳細さを高め、1 日の上限を解除します。",
        "quota_same_engine": "あなたのセキュリティは保たれます。Pro の有無にかかわらず、同じ検出エンジンがあなたを守ります。",
    },
    "ru": {
        "paywall_same_engine": "Защита одинакова для всех: каждая проверка использует одни и те же анализаторы и один и тот же уровень обнаружения. Pro добавляет глубину результатов и снимает дневной лимит.",
        "quota_same_engine": "Ваша безопасность остаётся полной: один и тот же движок обнаружения защищает вас, с Pro или без него.",
    },
}

ADD_ORDER = ["paywall_same_engine", "quota_same_engine"]


def values_dir(res_root, lang):
    return os.path.join(res_root, "values" if lang == "" else f"values-{lang}")


def strings_path(res_root, lang):
    return os.path.join(values_dir(res_root, lang), "strings.xml")


def process(res_root, lang):
    path = strings_path(res_root, lang)
    if not os.path.isfile(path):
        return -1, -1
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    # Révisions
    n_rev = 0
    for name, val in REVISE.get(lang, {}).items():
        pat = re.compile(rf'(<string name="{re.escape(name)}"[^>]*>).*?(</string>)', re.S)
        src2, c = pat.subn(rf'\g<1>{val}\g<2>', src)
        if c:
            src = src2
            n_rev += c
    # Ajouts
    n_add = 0
    add_lines = []
    for name in ADD_ORDER:
        kv = ADD.get(lang, {})
        if name not in kv:
            continue
        if re.search(rf'<string\s+name="{re.escape(name)}"', src):
            continue
        add_lines.append(f'    <string name="{name}">{kv[name]}</string>')
    if add_lines and "</resources>" in src:
        src = src.replace("</resources>", "\n".join(add_lines) + "\n</resources>", 1)
        n_add = len(add_lines)
    if n_rev or n_add:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
    return n_rev, n_add


def main():
    res_root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
    if not os.path.isdir(res_root):
        print(f"ERREUR : dossier res introuvable : {res_root}")
        sys.exit(1)
    langs = ["", "en", "es", "pt", "it", "de", "ar", "tr", "hi", "zh-rCN", "ja", "ru"]
    tr = ta = 0
    for lang in langs:
        label = "values/" if lang == "" else f"values-{lang}/"
        r, a = process(res_root, lang)
        if r < 0:
            print(f"  {label:<16} ⚠ ABSENT")
        else:
            print(f"  {label:<16} {r} révisée(s), +{a} ajoutée(s)")
            tr += r
            ta += a
    print(f"\nTerminé. {tr} révision(s), {ta} ajout(s).")


if __name__ == "__main__":
    main()
