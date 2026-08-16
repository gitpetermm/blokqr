#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Paquet 4 — Révision onboarding écran 6 + avantages Pro (12 langues).

Ce script fait DEUX choses, de façon idempotente :

  1. RÉVISE des clés existantes dont le texte contredit le nouveau modèle
     économique (Free 7/jour, Pro « illimité* ») :
       - onb6_body          (ne parlait que d'analyse approfondie)
       - paywall_subtitle   (affirmait à tort « gratuite et ILLIMITÉE »)

  2. AJOUTE de nouvelles clés :
       - onb6_unlimited_note         (note astérisque onboarding)
       - paywall_benefit_unlimited   (« Analyses illimitées* »)
       - paywall_benefit_signals     (« Tous les signaux détaillés »)
       - paywall_unlimited_note      (note astérisque paywall)

Les traductions sont NATIVES par langue (aucun mélange). Apostrophes échappées
pour AAPT2. Placeholders Android préservés.

Usage :
    python add_onboarding_pro_strings.py [chemin_res]   (défaut : app/src/main/res)
"""
import os
import re
import sys

# --- Clés à RÉVISER (remplacement du contenu, par regex sur le name) ---------
# Pour chaque langue : { name: nouvelle_valeur }
REVISE = {
    "": {  # français (values/)
        "onb6_body": "En version gratuite, vous avez 7 analyses vérifiées par jour. Passez à Pro pour des analyses illimitées* et l\\'analyse approfondie des liens les plus sensibles.",
        "paywall_subtitle": "La version gratuite vérifie jusqu\\'à 7 liens par jour. Pro lève cette limite et ajoute l\\'analyse approfondie.",
    },
    "en": {
        "onb6_body": "The free version gives you 7 verified scans per day. Go Pro for unlimited scans* and in-depth analysis of the most sensitive links.",
        "paywall_subtitle": "The free version checks up to 7 links per day. Pro removes this limit and adds in-depth analysis.",
    },
    "es": {
        "onb6_body": "La versión gratuita te ofrece 7 análisis verificados al día. Pásate a Pro para análisis ilimitados* y el análisis en profundidad de los enlaces más sensibles.",
        "paywall_subtitle": "La versión gratuita verifica hasta 7 enlaces al día. Pro elimina este límite y añade el análisis en profundidad.",
    },
    "pt": {
        "onb6_body": "A versão gratuita oferece 7 análises verificadas por dia. Mude para o Pro para análises ilimitadas* e a análise aprofundada dos links mais sensíveis.",
        "paywall_subtitle": "A versão gratuita verifica até 7 links por dia. O Pro remove este limite e adiciona a análise aprofundada.",
    },
    "it": {
        "onb6_body": "La versione gratuita ti offre 7 analisi verificate al giorno. Passa a Pro per analisi illimitate* e l\\'analisi approfondita dei link più sensibili.",
        "paywall_subtitle": "La versione gratuita verifica fino a 7 link al giorno. Pro rimuove questo limite e aggiunge l\\'analisi approfondita.",
    },
    "de": {
        "onb6_body": "Die kostenlose Version bietet Ihnen 7 geprüfte Scans pro Tag. Mit Pro erhalten Sie unbegrenzte Scans* und die tiefgehende Analyse der sensibelsten Links.",
        "paywall_subtitle": "Die kostenlose Version prüft bis zu 7 Links pro Tag. Pro hebt dieses Limit auf und ergänzt die tiefgehende Analyse.",
    },
    "ar": {
        "onb6_body": "تمنحك النسخة المجانية 7 عمليات فحص موثَّقة يوميًا. قم بالترقية إلى Pro للحصول على عمليات فحص غير محدودة* وتحليل متعمق لأكثر الروابط حساسية.",
        "paywall_subtitle": "تتحقق النسخة المجانية من 7 روابط يوميًا كحد أقصى. يزيل Pro هذا الحد ويضيف التحليل المتعمق.",
    },
    "tr": {
        "onb6_body": "Ücretsiz sürüm size günde 7 doğrulanmış tarama sunar. Sınırsız tarama* ve en hassas bağlantıların derinlemesine analizi için Pro\\'ya geçin.",
        "paywall_subtitle": "Ücretsiz sürüm günde en fazla 7 bağlantıyı denetler. Pro bu sınırı kaldırır ve derinlemesine analizi ekler.",
    },
    "hi": {
        "onb6_body": "मुफ़्त संस्करण आपको प्रतिदिन 7 सत्यापित जाँचें देता है। असीमित जाँच* और सबसे संवेदनशील लिंक के गहन विश्लेषण के लिए Pro में अपग्रेड करें।",
        "paywall_subtitle": "मुफ़्त संस्करण प्रतिदिन 7 लिंक तक जाँचता है। Pro इस सीमा को हटाता है और गहन विश्लेषण जोड़ता है।",
    },
    "zh-rCN": {
        "onb6_body": "免费版每天提供 7 次已验证扫描。升级到 Pro 即可获得无限扫描*以及对最敏感链接的深度分析。",
        "paywall_subtitle": "免费版每天最多检查 7 个链接。Pro 取消此限制并增加深度分析。",
    },
    "ja": {
        "onb6_body": "無料版では 1 日 7 回の検証済みスキャンをご利用いただけます。Pro にアップグレードすると、無制限のスキャン*と、最も機密性の高いリンクの詳細な分析が可能になります。",
        "paywall_subtitle": "無料版では 1 日最大 7 件のリンクを確認できます。Pro はこの制限を解除し、詳細な分析を追加します。",
    },
    "ru": {
        "onb6_body": "Бесплатная версия предоставляет 7 проверенных сканирований в день. Перейдите на Pro для неограниченных сканирований* и углублённого анализа самых уязвимых ссылок.",
        "paywall_subtitle": "Бесплатная версия проверяет до 7 ссылок в день. Pro снимает это ограничение и добавляет углублённый анализ.",
    },
}

# --- Clés à AJOUTER (insérées avant </resources> si absentes) ----------------
ADD = {
    "": {
        "onb6_unlimited_note": "*Jusqu\\'à 500 analyses par jour (protection anti-abus).",
        "paywall_benefit_unlimited": "Analyses illimitées* tous les jours",
        "paywall_benefit_signals": "Tous les signaux de sécurité détaillés",
        "paywall_unlimited_note": "*Jusqu\\'à 500 analyses par jour (protection anti-abus).",
    },
    "en": {
        "onb6_unlimited_note": "*Up to 500 scans per day (anti-abuse protection).",
        "paywall_benefit_unlimited": "Unlimited scans* every day",
        "paywall_benefit_signals": "All detailed security signals",
        "paywall_unlimited_note": "*Up to 500 scans per day (anti-abuse protection).",
    },
    "es": {
        "onb6_unlimited_note": "*Hasta 500 análisis al día (protección antiabuso).",
        "paywall_benefit_unlimited": "Análisis ilimitados* cada día",
        "paywall_benefit_signals": "Todas las señales de seguridad detalladas",
        "paywall_unlimited_note": "*Hasta 500 análisis al día (protección antiabuso).",
    },
    "pt": {
        "onb6_unlimited_note": "*Até 500 análises por dia (proteção antiabuso).",
        "paywall_benefit_unlimited": "Análises ilimitadas* todos os dias",
        "paywall_benefit_signals": "Todos os sinais de segurança detalhados",
        "paywall_unlimited_note": "*Até 500 análises por dia (proteção antiabuso).",
    },
    "it": {
        "onb6_unlimited_note": "*Fino a 500 analisi al giorno (protezione anti-abuso).",
        "paywall_benefit_unlimited": "Analisi illimitate* ogni giorno",
        "paywall_benefit_signals": "Tutti i segnali di sicurezza dettagliati",
        "paywall_unlimited_note": "*Fino a 500 analisi al giorno (protezione anti-abuso).",
    },
    "de": {
        "onb6_unlimited_note": "*Bis zu 500 Scans pro Tag (Schutz vor Missbrauch).",
        "paywall_benefit_unlimited": "Unbegrenzte Scans* jeden Tag",
        "paywall_benefit_signals": "Alle detaillierten Sicherheitssignale",
        "paywall_unlimited_note": "*Bis zu 500 Scans pro Tag (Schutz vor Missbrauch).",
    },
    "ar": {
        "onb6_unlimited_note": "*حتى 500 عملية فحص يوميًا (حماية من إساءة الاستخدام).",
        "paywall_benefit_unlimited": "عمليات فحص غير محدودة* كل يوم",
        "paywall_benefit_signals": "جميع إشارات الأمان التفصيلية",
        "paywall_unlimited_note": "*حتى 500 عملية فحص يوميًا (حماية من إساءة الاستخدام).",
    },
    "tr": {
        "onb6_unlimited_note": "*Günde en fazla 500 tarama (kötüye kullanım koruması).",
        "paywall_benefit_unlimited": "Her gün sınırsız tarama*",
        "paywall_benefit_signals": "Tüm ayrıntılı güvenlik sinyalleri",
        "paywall_unlimited_note": "*Günde en fazla 500 tarama (kötüye kullanım koruması).",
    },
    "hi": {
        "onb6_unlimited_note": "*प्रतिदिन 500 जाँच तक (दुरुपयोग-रोधी सुरक्षा)।",
        "paywall_benefit_unlimited": "हर दिन असीमित जाँच*",
        "paywall_benefit_signals": "सभी विस्तृत सुरक्षा संकेत",
        "paywall_unlimited_note": "*प्रतिदिन 500 जाँच तक (दुरुपयोग-रोधी सुरक्षा)।",
    },
    "zh-rCN": {
        "onb6_unlimited_note": "*每天最多 500 次扫描（防滥用保护）。",
        "paywall_benefit_unlimited": "每天无限次扫描*",
        "paywall_benefit_signals": "所有详细的安全信号",
        "paywall_unlimited_note": "*每天最多 500 次扫描（防滥用保护）。",
    },
    "ja": {
        "onb6_unlimited_note": "*1 日最大 500 回のスキャン（不正使用防止のため）。",
        "paywall_benefit_unlimited": "毎日無制限のスキャン*",
        "paywall_benefit_signals": "詳細なセキュリティシグナルをすべて表示",
        "paywall_unlimited_note": "*1 日最大 500 回のスキャン（不正使用防止のため）。",
    },
    "ru": {
        "onb6_unlimited_note": "*До 500 сканирований в день (защита от злоупотреблений).",
        "paywall_benefit_unlimited": "Неограниченные сканирования* каждый день",
        "paywall_benefit_signals": "Все подробные сигналы безопасности",
        "paywall_unlimited_note": "*До 500 сканирований в день (защита от злоупотреблений).",
    },
}

ADD_ORDER = [
    "onb6_unlimited_note",
    "paywall_benefit_unlimited",
    "paywall_benefit_signals",
    "paywall_unlimited_note",
]


def values_dir(res_root, lang):
    return os.path.join(res_root, "values" if lang == "" else f"values-{lang}")


def strings_path(res_root, lang):
    return os.path.join(values_dir(res_root, lang), "strings.xml")


def revise_keys(content, kv):
    """Remplace le contenu des clés existantes. Retourne (content, n_revised)."""
    n = 0
    for name, new_val in kv.items():
        # Remplace <string name="X" ...>...</string> (contenu uniquement).
        pattern = re.compile(
            rf'(<string name="{re.escape(name)}"[^>]*>).*?(</string>)',
            re.S,
        )
        new_content, count = pattern.subn(rf'\g<1>{new_val}\g<2>', content)
        if count > 0:
            content = new_content
            n += count
    return content, n


def add_keys(content, kv):
    """Ajoute les clés absentes avant </resources>. Retourne (content, n_added)."""
    lines = []
    for name in ADD_ORDER:
        if name not in kv:
            continue
        if re.search(rf'<string\s+name="{re.escape(name)}"', content):
            continue  # idempotent
        lines.append(f'    <string name="{name}">{kv[name]}</string>')
    if not lines:
        return content, 0
    if "</resources>" not in content:
        return content, 0
    block = "\n".join(lines) + "\n"
    return content.replace("</resources>", block + "</resources>", 1), len(lines)


def process(res_root, lang):
    path = strings_path(res_root, lang)
    label = "values/" if lang == "" else f"values-{lang}/"
    if not os.path.isfile(path):
        print(f"  {label:<16} ⚠ ABSENT (ignoré)")
        return 0, 0
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    content, n_rev = revise_keys(content, REVISE.get(lang, {}))
    content, n_add = add_keys(content, ADD.get(lang, {}))
    if n_rev or n_add:
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
    print(f"  {label:<16} {n_rev} révisée(s), +{n_add} ajoutée(s)")
    return n_rev, n_add


def main():
    res_root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
    if not os.path.isdir(res_root):
        print(f"ERREUR : dossier res introuvable : {res_root}")
        sys.exit(1)
    langs = ["", "en", "es", "pt", "it", "de", "ar", "tr", "hi", "zh-rCN", "ja", "ru"]
    tot_rev = tot_add = 0
    for lang in langs:
        r, a = process(res_root, lang)
        tot_rev += r
        tot_add += a
    print(f"\nTerminé. {tot_rev} révision(s), {tot_add} ajout(s) au total.")


if __name__ == "__main__":
    main()