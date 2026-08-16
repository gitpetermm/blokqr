#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ajoute les 6 clés i18n du Paquet 3B (quota + mode local) dans les 12 langues.

Idempotent : si une clé existe déjà dans un fichier, elle n'est pas redupliquée.
Insère chaque clé juste avant la balise fermante </resources>.

Clés ajoutées :
  - quota_exhausted_title       : titre de l'écran plein « quota atteint »
  - quota_exhausted_body        : corps (avec %1$d = limite quotidienne)
  - quota_exhausted_reset_in    : « Réinitialisation dans %1$s » (durée)
  - quota_exhausted_cta_pro     : bouton « Passer à Pro »
  - quota_exhausted_cta_local   : bouton « Analyse locale limitée »
  - quota_exhausted_cta_later   : lien « Réessayer plus tard »
  - quota_banner_remaining      : bandeau « %1$d analyses restantes aujourd'hui »
  - quota_local_mode_notice     : bandeau « Analyse locale — non vérifiée »

Usage :
    python add_quota_strings.py [chemin_racine_res]
    (par défaut : app/src/main/res)
"""
import os
import sys
import re

# 12 langues : fr (values/ par défaut), en, es, pt, it, de, ar, tr, hi, zh-rCN, ja, ru.
# La clé est le suffixe de dossier values ; "" = values/ (français par défaut).
TRANSLATIONS = {
    "": {  # values/ (français — langue par défaut de l'app)
        "quota_exhausted_title": "Limite quotidienne atteinte",
        "quota_exhausted_body": "Vous avez utilisé vos %1$d analyses vérifiées du jour. Passez à Pro pour des analyses approfondies, ou continuez avec une analyse locale limitée.",
        "quota_exhausted_reset_in": "Réinitialisation dans %1$s",
        "quota_exhausted_cta_pro": "Passer à Pro",
        "quota_exhausted_cta_local": "Analyse locale limitée",
        "quota_exhausted_cta_later": "Réessayer plus tard",
        "quota_banner_remaining": "Il vous reste %1$d analyse(s) aujourd\\'hui",
        "quota_local_mode_notice": "Analyse locale — non vérifiée par le serveur",
    },
    "en": {
        "quota_exhausted_title": "Daily limit reached",
        "quota_exhausted_body": "You\\'ve used your %1$d verified scans for today. Upgrade to Pro for in-depth analysis, or continue with a limited local check.",
        "quota_exhausted_reset_in": "Resets in %1$s",
        "quota_exhausted_cta_pro": "Upgrade to Pro",
        "quota_exhausted_cta_local": "Limited local check",
        "quota_exhausted_cta_later": "Try again later",
        "quota_banner_remaining": "%1$d scan(s) left today",
        "quota_local_mode_notice": "Local check — not verified by the server",
    },
    "es": {
        "quota_exhausted_title": "Límite diario alcanzado",
        "quota_exhausted_body": "Has usado tus %1$d análisis verificados de hoy. Cambia a Pro para análisis en profundidad, o continúa con una comprobación local limitada.",
        "quota_exhausted_reset_in": "Se restablece en %1$s",
        "quota_exhausted_cta_pro": "Cambiar a Pro",
        "quota_exhausted_cta_local": "Comprobación local limitada",
        "quota_exhausted_cta_later": "Intentar más tarde",
        "quota_banner_remaining": "Te quedan %1$d análisis hoy",
        "quota_local_mode_notice": "Comprobación local — no verificada por el servidor",
    },
    "pt": {
        "quota_exhausted_title": "Limite diário atingido",
        "quota_exhausted_body": "Você usou suas %1$d análises verificadas de hoje. Mude para Pro para análises aprofundadas, ou continue com uma verificação local limitada.",
        "quota_exhausted_reset_in": "Redefine em %1$s",
        "quota_exhausted_cta_pro": "Mudar para Pro",
        "quota_exhausted_cta_local": "Verificação local limitada",
        "quota_exhausted_cta_later": "Tentar mais tarde",
        "quota_banner_remaining": "Restam %1$d análises hoje",
        "quota_local_mode_notice": "Verificação local — não verificada pelo servidor",
    },
    "it": {
        "quota_exhausted_title": "Limite giornaliero raggiunto",
        "quota_exhausted_body": "Hai usato le tue %1$d analisi verificate di oggi. Passa a Pro per analisi approfondite, o continua con un controllo locale limitato.",
        "quota_exhausted_reset_in": "Si reimposta tra %1$s",
        "quota_exhausted_cta_pro": "Passa a Pro",
        "quota_exhausted_cta_local": "Controllo locale limitato",
        "quota_exhausted_cta_later": "Riprova più tardi",
        "quota_banner_remaining": "Ti restano %1$d analisi oggi",
        "quota_local_mode_notice": "Controllo locale — non verificato dal server",
    },
    "de": {
        "quota_exhausted_title": "Tageslimit erreicht",
        "quota_exhausted_body": "Sie haben Ihre %1$d geprüften Scans für heute aufgebraucht. Wechseln Sie zu Pro für tiefgehende Analysen oder fahren Sie mit einer eingeschränkten lokalen Prüfung fort.",
        "quota_exhausted_reset_in": "Zurücksetzen in %1$s",
        "quota_exhausted_cta_pro": "Auf Pro upgraden",
        "quota_exhausted_cta_local": "Eingeschränkte lokale Prüfung",
        "quota_exhausted_cta_later": "Später erneut versuchen",
        "quota_banner_remaining": "Noch %1$d Scan(s) heute",
        "quota_local_mode_notice": "Lokale Prüfung — nicht vom Server verifiziert",
    },
    "ar": {
        "quota_exhausted_title": "تم الوصول إلى الحد اليومي",
        "quota_exhausted_body": "لقد استخدمت %1$d عمليات فحص موثَّقة اليوم. قم بالترقية إلى Pro للتحليل المتعمق، أو تابع بفحص محلي محدود.",
        "quota_exhausted_reset_in": "تتم إعادة التعيين خلال %1$s",
        "quota_exhausted_cta_pro": "الترقية إلى Pro",
        "quota_exhausted_cta_local": "فحص محلي محدود",
        "quota_exhausted_cta_later": "أعد المحاولة لاحقًا",
        "quota_banner_remaining": "تبقّى لديك %1$d عملية فحص اليوم",
        "quota_local_mode_notice": "فحص محلي — غير موثَّق من الخادم",
    },
    "tr": {
        "quota_exhausted_title": "Günlük sınıra ulaşıldı",
        "quota_exhausted_body": "Bugünkü %1$d doğrulanmış taramanızı kullandınız. Derinlemesine analiz için Pro\\'ya geçin veya sınırlı bir yerel denetimle devam edin.",
        "quota_exhausted_reset_in": "%1$s içinde sıfırlanır",
        "quota_exhausted_cta_pro": "Pro\\'ya geç",
        "quota_exhausted_cta_local": "Sınırlı yerel denetim",
        "quota_exhausted_cta_later": "Daha sonra tekrar dene",
        "quota_banner_remaining": "Bugün %1$d taramanız kaldı",
        "quota_local_mode_notice": "Yerel denetim — sunucu tarafından doğrulanmadı",
    },
    "hi": {
        "quota_exhausted_title": "दैनिक सीमा पूरी हो गई",
        "quota_exhausted_body": "आपने आज की अपनी %1$d सत्यापित जाँचें उपयोग कर ली हैं। गहन विश्लेषण के लिए Pro में अपग्रेड करें, या सीमित स्थानीय जाँच के साथ जारी रखें।",
        "quota_exhausted_reset_in": "%1$s में रीसेट होगा",
        "quota_exhausted_cta_pro": "Pro में अपग्रेड करें",
        "quota_exhausted_cta_local": "सीमित स्थानीय जाँच",
        "quota_exhausted_cta_later": "बाद में पुनः प्रयास करें",
        "quota_banner_remaining": "आज %1$d जाँच शेष हैं",
        "quota_local_mode_notice": "स्थानीय जाँच — सर्वर द्वारा सत्यापित नहीं",
    },
    "zh-rCN": {
        "quota_exhausted_title": "已达每日上限",
        "quota_exhausted_body": "您已用完今天的 %1$d 次已验证扫描。升级到 Pro 可进行深度分析，或继续使用受限的本地检查。",
        "quota_exhausted_reset_in": "将在 %1$s 后重置",
        "quota_exhausted_cta_pro": "升级到 Pro",
        "quota_exhausted_cta_local": "受限的本地检查",
        "quota_exhausted_cta_later": "稍后重试",
        "quota_banner_remaining": "今天还剩 %1$d 次扫描",
        "quota_local_mode_notice": "本地检查 — 未经服务器验证",
    },
    "ja": {
        "quota_exhausted_title": "1日の上限に達しました",
        "quota_exhausted_body": "本日の検証済みスキャン %1$d 回を使い切りました。詳細な分析には Pro にアップグレードするか、制限付きのローカルチェックを続行してください。",
        "quota_exhausted_reset_in": "%1$s 後にリセット",
        "quota_exhausted_cta_pro": "Pro にアップグレード",
        "quota_exhausted_cta_local": "制限付きローカルチェック",
        "quota_exhausted_cta_later": "後で再試行",
        "quota_banner_remaining": "本日の残りスキャン回数: %1$d",
        "quota_local_mode_notice": "ローカルチェック — サーバー未検証",
    },
    "ru": {
        "quota_exhausted_title": "Дневной лимит достигнут",
        "quota_exhausted_body": "Вы использовали свои %1$d проверенных сканирований за сегодня. Перейдите на Pro для углублённого анализа или продолжите с ограниченной локальной проверкой.",
        "quota_exhausted_reset_in": "Сброс через %1$s",
        "quota_exhausted_cta_pro": "Перейти на Pro",
        "quota_exhausted_cta_local": "Ограниченная локальная проверка",
        "quota_exhausted_cta_later": "Повторить позже",
        "quota_banner_remaining": "Осталось %1$d сканирований сегодня",
        "quota_local_mode_notice": "Локальная проверка — не проверено сервером",
    },
}

# Ordre d'insertion des clés (stable, pour des diffs lisibles).
KEY_ORDER = [
    "quota_exhausted_title",
    "quota_exhausted_body",
    "quota_exhausted_reset_in",
    "quota_exhausted_cta_pro",
    "quota_exhausted_cta_local",
    "quota_exhausted_cta_later",
    "quota_banner_remaining",
    "quota_local_mode_notice",
]


def values_dir(res_root: str, lang_suffix: str) -> str:
    name = "values" if lang_suffix == "" else f"values-{lang_suffix}"
    return os.path.join(res_root, name)


def strings_path(res_root: str, lang_suffix: str) -> str:
    return os.path.join(values_dir(res_root, lang_suffix), "strings.xml")


def add_keys_to_file(path: str, kv: dict) -> int:
    """Ajoute les clés manquantes au fichier strings.xml. Retourne le nombre ajouté."""
    if not os.path.isfile(path):
        print(f"  ⚠ ABSENT : {path} (ignoré)")
        return 0
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    added = 0
    lines_to_insert = []
    for key in KEY_ORDER:
        if key not in kv:
            continue
        # Idempotence : ne pas ré-ajouter si la clé existe déjà.
        if re.search(rf'<string\s+name="{re.escape(key)}"', content):
            continue
        value = kv[key]
        lines_to_insert.append(f'    <string name="{key}">{value}</string>')
        added += 1

    if added == 0:
        return 0

    # Insertion juste avant </resources>.
    insertion = "\n".join(lines_to_insert) + "\n"
    if "</resources>" not in content:
        print(f"  ⚠ Pas de </resources> dans {path} (ignoré)")
        return 0
    content = content.replace("</resources>", insertion + "</resources>", 1)

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return added


def main():
    res_root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
    if not os.path.isdir(res_root):
        print(f"ERREUR : dossier res introuvable : {res_root}")
        sys.exit(1)

    total = 0
    for lang_suffix, kv in TRANSLATIONS.items():
        path = strings_path(res_root, lang_suffix)
        label = "values/" if lang_suffix == "" else f"values-{lang_suffix}/"
        n = add_keys_to_file(path, kv)
        total += n
        status = f"+{n} clé(s)" if n else "déjà à jour"
        print(f"  {label:<16} {status}")

    print(f"\nTerminé. {total} clé(s) ajoutée(s) au total.")
    if total == 0:
        print("(Toutes les clés étaient déjà présentes — script idempotent.)")


if __name__ == "__main__":
    main()