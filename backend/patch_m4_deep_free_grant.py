#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_m4_deep_free_grant.py - Apercu approfondi OFFERT (1/jour) - Voie A serveur.

But
---
Accorder aux utilisateurs GRATUITS un (1) acces a /v1/analyze/deep par jour
(vrai rendu Chromium), decide et compte COTE SERVEUR (Redis), donc non
contournable en changeant l'heure du telephone.

Regle d'autorisation de /v1/analyze/deep apres ce patch :
  1. Pro (entitlement signe valide OU marque Redis pro:{id})  -> autorise (illimite)
  2. sinon, si grant deep-gratuit du jour disponible           -> autorise UNE fois
  3. sinon                                                     -> 402 pro_required

Decision produit (Option 1) : le deep gratuit accorde consomme AUSSI 1 scan
standard (try_consume). Resultat net : 7 analyses/jour dont la 1re approfondie.
Cela protege la ressource la plus couteuse (rendu Chromium) et colle a l'idee
initiale "6 gratuits + 1 approfondi".

Signalisation client : header de reponse X-Deep-Free = granted | exhausted | pro
  - granted  : ce resultat profond est l'apercu offert du jour (app -> badge)
  - pro      : l'utilisateur est Pro (pas un apercu offert)
  - exhausted: n'apparait pas sur un 200 ; l'app deduit "epuise" du 402.

Idempotent : re-executable sans dupliquer. Annulation : --defaire.

Fichiers touches :
  - app/quota.py           (fonctions deep-free : try/refund/peek + cle Redis)
  - app/config.py          (reglage free_deep_daily_quota)
  - app/api/routes.py      (regle d'autorisation + header X-Deep-Free)

Sauvegardes : *.bak-m4 a cote de chaque fichier (restaurees par --defaire).
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent
QUOTA = BASE / "app" / "quota.py"
CONFIG = BASE / "app" / "config.py"
ROUTES = BASE / "app" / "api" / "routes.py"
SUFFIX = ".bak-m4"
MARK = "M4_DEEP_FREE"   # marqueur d'idempotence


def _read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def _write(p: Path, s: str) -> None:
    p.write_text(s, encoding="utf-8")


def _backup(p: Path) -> None:
    bak = p.with_suffix(p.suffix + SUFFIX)
    if not bak.exists():
        bak.write_text(_read(p), encoding="utf-8")


def _restore(p: Path) -> bool:
    bak = p.with_suffix(p.suffix + SUFFIX)
    if bak.exists():
        p.write_text(bak.read_text(encoding="utf-8"), encoding="utf-8")
        bak.unlink()
        return True
    return False


# --------------------------------------------------------------------------- #
#  QUOTA : ajoute le compteur deep-gratuit (meme mecanique que quota:)
# --------------------------------------------------------------------------- #
QUOTA_BLOCK = '''

# --------------------------------------------------------------------------- #
#  M4_DEEP_FREE : apercu approfondi OFFERT (1/jour) aux utilisateurs gratuits.
#  Compteur Redis SEPARE du quota standard (cle deepfree:), meme mecanique :
#  INCR atomique, TTL minuit UTC + 2h, refund borne a 0. La limite (1/jour) est
#  lue depuis settings.free_deep_daily_quota.
# --------------------------------------------------------------------------- #
DEEPFREE_PREFIX = "deepfree:"


def _deepfree_key(install_id: str) -> str:
    d = datetime.now(timezone.utc).strftime("%Y%m%d")
    return f"{DEEPFREE_PREFIX}{install_id}:{d}"


async def try_consume_deep_free(install_id: str, settings: Settings) -> bool:
    """Tente de consommer 1 apercu approfondi offert du jour.

    Retourne True si accorde (et decremente le grant), False si epuise ou
    desactive (free_deep_daily_quota <= 0). Meme strategie atomique que
    try_consume : INCR puis DECR si depassement. N'accorde JAMAIS a un Pro
    (l'appelant traite le cas Pro avant d'arriver ici).
    """
    limit = int(getattr(settings, "free_deep_daily_quota", 1))
    if limit <= 0:
        return False
    key = _deepfree_key(install_id)
    new_used = await _redis.incr(key)
    if new_used == 1:
        await _redis.expire(key, _seconds_until_utc_midnight() + 7200)
    if new_used > limit:
        await _redis.decr(key)
        return False
    return True


async def refund_deep_free(install_id: str) -> None:
    """Rembourse l'apercu offert (DECR borne a 0) si l'analyse profonde n'a
    pas ete rendue (timeout / erreur) : l'utilisateur ne perd pas son offre."""
    if not install_id:
        return
    key = _deepfree_key(install_id)
    async with _redis.pipeline(transaction=True) as pipe:
        await pipe.get(key)
        results = await pipe.execute()
    cur = results[0]
    if cur and int(cur) > 0:
        await _redis.decr(key)


async def peek_deep_free(install_id: str, settings: Settings) -> bool:
    """Lit sans consommer : True si un apercu offert reste disponible aujourd'hui."""
    limit = int(getattr(settings, "free_deep_daily_quota", 1))
    if limit <= 0:
        return False
    used_raw = await _redis.get(_deepfree_key(install_id))
    used = int(used_raw) if used_raw else 0
    return used < limit
'''


def patch_quota(undo: bool) -> str:
    if undo:
        return "restaure" if _restore(QUOTA) else "rien"
    s = _read(QUOTA)
    if MARK in s:
        return "deja"
    _backup(QUOTA)
    _write(QUOTA, s.rstrip() + "\n" + QUOTA_BLOCK)
    return "patche"


# --------------------------------------------------------------------------- #
#  CONFIG : ajoute free_deep_daily_quota juste apres free_daily_quota
# --------------------------------------------------------------------------- #
def patch_config(undo: bool) -> str:
    if undo:
        return "restaure" if _restore(CONFIG) else "rien"
    s = _read(CONFIG)
    if "free_deep_daily_quota" in s:
        return "deja"
    anchor = "    free_daily_quota: int = 7\n"
    if anchor not in s:
        raise SystemExit("CONFIG: ancre free_daily_quota introuvable")
    _backup(CONFIG)
    add = (
        anchor
        + "    # M4_DEEP_FREE : nombre d'apercus approfondis OFFERTS par jour aux\n"
        + "    # utilisateurs gratuits (vrai /v1/analyze/deep). 0 = desactive.\n"
        + "    free_deep_daily_quota: int = 1\n"
    )
    _write(CONFIG, s.replace(anchor, add, 1))
    return "patche"


# --------------------------------------------------------------------------- #
#  ROUTES : import + regle d'autorisation + header X-Deep-Free
# --------------------------------------------------------------------------- #
def patch_routes(undo: bool) -> str:
    if undo:
        return "restaure" if _restore(ROUTES) else "rien"
    s = _read(ROUTES)
    if MARK in s:
        return "deja"
    _backup(ROUTES)

    # 1) Import des fonctions deep-free (a cote de l'import quota existant).
    old_import = ("from app.quota import peek as quota_peek, "
                  "refund as quota_refund, try_consume\n")
    new_import = ("from app.quota import (\n"
                  "    peek as quota_peek,\n"
                  "    refund as quota_refund,\n"
                  "    try_consume,\n"
                  "    try_consume_deep_free,  # M4_DEEP_FREE\n"
                  "    refund_deep_free,       # M4_DEEP_FREE\n"
                  ")\n")
    if old_import not in s:
        raise SystemExit("ROUTES: ancre import quota introuvable")
    s = s.replace(old_import, new_import, 1)

    # 2) Remplace le bloc d'autorisation deep + le gating quota qui suit.
    old_auth = '''    ent = verify_entitlement(payload.entitlement or "", signer)
    ent_pro = bool(ent and ent.get("pro"))
    if not ent_pro and not await is_pro(install_id):
        raise HTTPException(status_code=402, detail="pro_required")
    # Si un entitlement valide est présenté, on (re)pose la marque Pro
    # (idempotent, best-effort) pour garder quota et accès deep synchrones.
    if ent_pro:
        try:
            if not await is_pro(install_id):
                await mark_pro(install_id, ttl_seconds=settings.entitlement_ttl_seconds)
        except Exception:
            logger.warning("auto-mark_pro échoué (deep)", exc_info=True)
    # Gating quota (Pro -> quota Pro).
    st = await try_consume(install_id, settings)
    if not st.consumed:
        return _quota_exceeded_response(install_id, st, signer)
    try:
        verdict = await asyncio.wait_for(
            analyze(payload, settings, signer, deep=True),
            timeout=settings.overall_budget_seconds,
        )
    except asyncio.TimeoutError:
        verdict = build_timeout_verdict(payload, settings, signer)
        await quota_refund(install_id)
        st = await quota_peek(install_id, settings)
    return JSONResponse(
        status_code=200,
        content=verdict.model_dump(mode="json"),
        headers=st.to_headers(),
    )'''

    new_auth = '''    ent = verify_entitlement(payload.entitlement or "", signer)
    ent_pro = bool(ent and ent.get("pro"))
    pro = ent_pro or await is_pro(install_id)
    # M4_DEEP_FREE : si NON Pro, on tente d'accorder l'apercu approfondi offert
    # du jour (compteur Redis deepfree:, 1/jour par defaut). Accorde -> on
    # poursuit l'analyse profonde comme un Pro pour CE scan ; epuise -> 402.
    deep_free_granted = False
    if not pro:
        deep_free_granted = await try_consume_deep_free(install_id, settings)
        if not deep_free_granted:
            raise HTTPException(status_code=402, detail="pro_required")
    # Si un entitlement valide est présenté, on (re)pose la marque Pro
    # (idempotent, best-effort) pour garder quota et accès deep synchrones.
    if ent_pro:
        try:
            if not await is_pro(install_id):
                await mark_pro(install_id, ttl_seconds=settings.entitlement_ttl_seconds)
        except Exception:
            logger.warning("auto-mark_pro échoué (deep)", exc_info=True)
    # Gating quota (Pro -> quota Pro ; apercu offert -> compte comme 1 scan
    # standard, decision Option 1 : 7 analyses/jour dont la 1re approfondie).
    st = await try_consume(install_id, settings)
    if not st.consumed:
        # Le quota standard est plein : on rembourse l'apercu offert eventuel
        # (il n'a pas ete rendu) pour ne pas gacher l'offre du jour.
        if deep_free_granted:
            await refund_deep_free(install_id)
        return _quota_exceeded_response(install_id, st, signer)
    try:
        verdict = await asyncio.wait_for(
            analyze(payload, settings, signer, deep=True),
            timeout=settings.overall_budget_seconds,
        )
    except asyncio.TimeoutError:
        verdict = build_timeout_verdict(payload, settings, signer)
        await quota_refund(install_id)
        # L'analyse profonde n'a pas ete rendue : on rend aussi l'apercu offert.
        if deep_free_granted:
            await refund_deep_free(install_id)
        st = await quota_peek(install_id, settings)
    # M4_DEEP_FREE : signale au client la nature de ce resultat profond.
    headers = st.to_headers()
    headers["X-Deep-Free"] = "granted" if deep_free_granted else "pro"
    return JSONResponse(
        status_code=200,
        content=verdict.model_dump(mode="json"),
        headers=headers,
    )'''

    if old_auth not in s:
        raise SystemExit("ROUTES: ancre bloc autorisation deep introuvable "
                         "(le fichier a-t-il deja ete modifie a la main ?)")
    s = s.replace(old_auth, new_auth, 1)
    _write(ROUTES, s)
    return "patche"


def main() -> None:
    undo = "--defaire" in sys.argv
    action = "ANNULATION" if undo else "APPLICATION"
    print(f"== {action} patch M4 (apercu approfondi offert) ==")
    for name, fn in (("config.py", patch_config),
                     ("quota.py", patch_quota),
                     ("routes.py", patch_routes)):
        try:
            print(f"  {name:12} -> {fn(undo)}")
        except SystemExit as e:
            print(f"  {name:12} -> ERREUR: {e}")
            sys.exit(1)
    print("OK.")


if __name__ == "__main__":
    main()
