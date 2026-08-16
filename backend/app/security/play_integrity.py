"""
Vérification d'un token Google Play Integrity.

Rôle
----
Lors d'un POST /v1/install, le client envoie un JWS signé par Google
(« integrity token ») dans le header X-Integrity-Token, ainsi que le
nonce qu'il a utilisé pour le générer dans le body de la requête.

Ce module :
  1. décode le JWS via l'API officielle Google
     (`playintegrity.v1.decodeIntegrityToken`) en utilisant le service
     account `play-service-account.json` (le même que pour Billing) ;
  2. valide toutes les propriétés critiques du verdict :
       - packageName == "com.blokqr.app"
       - nonce reçu == nonce attendu (anti-rejeu lié à la requête)
       - timestampMillis frais (anti-rejeu temporel)
       - appRecognitionVerdict ∈ {PLAY_RECOGNIZED}
       - deviceRecognitionVerdict ≥ seuil configuré
       - appLicensingVerdict ∈ {LICENSED, UNLICENSED} (tolérant en dev)
  3. enregistre le nonce dans Redis avec un TTL court (anti-rejeu durable :
     deux requêtes ne peuvent pas réutiliser le même nonce, même si
     toutes les autres validations passent).

Mode permissif
--------------
Au démarrage initial, `require_integrity` est False : un token invalide
ou absent N'EMPÊCHE PAS le provisionnement. La fonction renvoie alors
un verdict explicite (ok=False, reason=...) que la route /v1/install
journalise et utilise pour décider. Cela permet une migration en douceur
sans casser les installations existantes.

Sécurité
--------
Le décodage du JWS est délégué à l'API Google : seul Google possède la
clé privée qui a signé le token. Une « décompression locale » du JWS
(sans appel API) serait insuffisante en l'absence des clés publiques
versionnées de Google.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Any

import redis.asyncio as redis
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from app.config import Settings

logger = logging.getLogger(__name__)

# --- Constantes Play Integrity --------------------------------------------

# Niveaux acceptables d'intégrité de l'appareil, par ordre CROISSANT.
# Source : https://developer.android.com/google/play/integrity/verdicts
_DEVICE_VERDICT_RANK = {
    "MEETS_BASIC_INTEGRITY": 1,
    "MEETS_DEVICE_INTEGRITY": 2,
    "MEETS_STRONG_INTEGRITY": 3,
    "MEETS_VIRTUAL_INTEGRITY": 0,   # émulateur / VM Play -> rejeté par défaut
}

# Valeurs acceptables pour appRecognitionVerdict.
# PLAY_RECOGNIZED  = installé via Play Store, intacte.
# UNRECOGNIZED_VERSION = signature OK mais version inconnue de Play (sideload APK).
# UNEVALUATED      = pas encore évalué (premier lancement éclair).
_ACCEPTED_APP_VERDICTS = {"PLAY_RECOGNIZED"}

# Valeurs acceptables pour appLicensingVerdict.
# LICENSED   = le compte Google possède l'app sur Play.
# UNLICENSED = le compte ne l'a pas (sideload, autre compte).
# UNEVALUATED= non évalué (rare).
# En mode permissif (`integrity_allow_unlicensed=True`), on tolère UNLICENSED
# pour permettre les tests via sideload et l'Internal Testing Play Console.
_ACCEPTED_LICENSE_VERDICTS_STRICT = {"LICENSED"}
_ACCEPTED_LICENSE_VERDICTS_LENIENT = {"LICENSED", "UNLICENSED", "UNEVALUATED"}

# Préfixe Redis pour les nonces déjà vus (anti-replay durable).
_NONCE_PREFIX = "integrity_nonce:"

# Scope OAuth2 nécessaire pour appeler decodeIntegrityToken.
_INTEGRITY_SCOPE = "https://www.googleapis.com/auth/playintegrity"


# --- DTO de résultat ------------------------------------------------------

@dataclass
class IntegrityVerdict:
    """Résultat de la vérification d'un token Play Integrity.

    Conçu pour être journalisé : pas de PII, juste des codes catégoriels.
    """
    ok: bool
    """True si le token est valide ET satisfait tous les seuils."""

    reason: str = ""
    """Code court (snake_case) expliquant la décision, ex. 'nonce_mismatch'."""

    detail: str = ""
    """Détail libre (pour debug ; ne contient pas le token brut)."""

    # Champs informatifs extraits du verdict (None si non disponibles).
    package_name: str | None = None
    app_recognition_verdict: str | None = None
    device_recognition_verdicts: list[str] = field(default_factory=list)
    app_licensing_verdict: str | None = None
    timestamp_millis: int | None = None


# --- Client Google (initialisation paresseuse, cache process-wide) --------

@lru_cache(maxsize=1)
def _integrity_client(service_account_path: str):
    """Construit (et met en cache) le client Google Play Integrity.

    Le `lru_cache` garantit qu'on ne lit le fichier de credentials qu'une
    seule fois par process FastAPI (uvicorn worker). En cas de rotation
    du service account, redémarrer le conteneur suffit à recharger.
    """
    credentials = service_account.Credentials.from_service_account_file(
        service_account_path,
        scopes=[_INTEGRITY_SCOPE],
    )
    return build(
        "playintegrity", "v1",
        credentials=credentials,
        cache_discovery=False,  # pas de cache disque (conteneur éphémère)
    )


# --- Redis pour anti-replay nonce ----------------------------------------

# Un seul client Redis partagé (asyncio). On garde le même schéma de
# connexion que `install_token.py` pour cohérence opérationnelle.
_redis = redis.from_url("redis://redis:6379/0", decode_responses=True)


# --- Décodage bas niveau (synchrone, envelopé en asyncio.to_thread) ------

def _decode_token_sync(
    service_account_path: str,
    package_name: str,
    integrity_token: str,
) -> dict[str, Any]:
    """Appelle Google pour décoder le JWS. Synchrone (HTTP bloquant)."""
    client = _integrity_client(service_account_path)
    return (
        client.v1()
        .decodeIntegrityToken(
            packageName=package_name,
            body={"integrity_token": integrity_token},
        )
        .execute()
    )


# --- Vérification de haut niveau (utilisée par la route /v1/install) -----

def _canonical_nonce(value: str) -> str:
    """Normalise un nonce Base64 vers une forme canonique pour comparaison.

    Google Play Integrity et l'app Android peuvent encoder le même nonce
    avec des variantes :
      - URL-safe (`-_`) vs standard (`+/`)
      - avec padding (`=`) vs sans
    On ramène tout vers : URL-safe, SANS padding. Cette normalisation est
    purement syntaxique : elle ne modifie pas le contenu binaire encodé.
    """
    if not value:
        return ""
    return value.replace("+", "-").replace("/", "_").rstrip("=")


async def verify(
    integrity_token: str,
    expected_nonce: str,
    settings: Settings,
) -> IntegrityVerdict:
    """
    Décode + valide un token Play Integrity.

    Renvoie un IntegrityVerdict. La route appelante décide ensuite quoi
    faire selon le mode (`require_integrity` strict ou permissif).

    Tous les échecs sont catégorisés en `reason` :
        - missing_token / missing_nonce
        - decode_error             (Google n'a pas pu décoder : token forgé)
        - missing_request_details  (structure verdict inattendue)
        - package_mismatch
        - nonce_mismatch
        - nonce_replayed           (déjà vu en Redis)
        - token_expired            (> max_age_seconds)
        - app_not_recognized       (UNRECOGNIZED_VERSION, etc.)
        - license_rejected         (UNLICENSED en mode strict)
        - device_integrity_low     (sous le seuil configuré)
        - emulator_detected        (MEETS_VIRTUAL_INTEGRITY)
        - internal_error           (exception inattendue)
    """
    if not integrity_token:
        return IntegrityVerdict(ok=False, reason="missing_token")
    if not expected_nonce:
        return IntegrityVerdict(ok=False, reason="missing_nonce")

    # 1) Décodage Google -- appel HTTP bloquant, on l'éxécute en thread pool
    #    pour ne pas bloquer la boucle asyncio. Petit retry pour absorber les
    #    503 transitoires (propagation Cloud Console, micro-pannes Google).
    decoded = None
    try:
        last_http_status = None
        for attempt in range(2):  # 1 tentative + 1 retry
            try:
                decoded = await asyncio.to_thread(
                    _decode_token_sync,
                    settings.play_service_account_path,
                    settings.android_package_name,
                    integrity_token,
                )
                break
            except HttpError as exc:
                last_http_status = exc.resp.status
                # On retry uniquement sur erreurs serveur transitoires (5xx).
                if last_http_status in (500, 502, 503, 504) and attempt == 0:
                    await asyncio.sleep(0.5)
                    continue
                # Erreur définitive (401/403 = perms, 400 = token forgé, etc.).
                return IntegrityVerdict(
                    ok=False,
                    reason="decode_error",
                    detail=f"HttpError status={last_http_status}",
                )
    except Exception as exc:  # noqa: BLE001
        logger.exception("Erreur inattendue lors du décodage Integrity")
        return IntegrityVerdict(
            ok=False,
            reason="internal_error",
            detail=str(exc)[:200],
        )
    if decoded is None:
        # Filet de securite : ne devrait jamais arriver (la boucle break ou return).
        return IntegrityVerdict(ok=False, reason="internal_error", detail="no_decode")

    # 2) Extraction des champs (structure documentée Play Integrity v1).
    payload = decoded.get("tokenPayloadExternal") or {}
    request_details = payload.get("requestDetails") or {}
    app_integrity = payload.get("appIntegrity") or {}
    device_integrity = payload.get("deviceIntegrity") or {}
    account_details = payload.get("accountDetails") or {}

    if not request_details:
        return IntegrityVerdict(ok=False, reason="missing_request_details")

    package_name = request_details.get("requestPackageName")
    nonce_in_token = request_details.get("nonce") or ""
    timestamp_millis = request_details.get("timestampMillis")
    try:
        timestamp_millis = int(timestamp_millis) if timestamp_millis else None
    except (TypeError, ValueError):
        timestamp_millis = None

    app_verdict = app_integrity.get("appRecognitionVerdict")
    device_verdicts = list(device_integrity.get("deviceRecognitionVerdict") or [])
    license_verdict = account_details.get("appLicensingVerdict")

    # Pré-construit pour annoter tous les retours suivants.
    verdict = IntegrityVerdict(
        ok=False,
        package_name=package_name,
        app_recognition_verdict=app_verdict,
        device_recognition_verdicts=device_verdicts,
        app_licensing_verdict=license_verdict,
        timestamp_millis=timestamp_millis,
    )

    # 3) Vérifie le packageName : le token DOIT être pour notre app.
    if package_name != settings.android_package_name:
        verdict.reason = "package_mismatch"
        verdict.detail = f"got={package_name}"
        return verdict

    # 4) Vérifie le nonce : doit matcher EXACTEMENT celui du body.
    #    Comparaison constante en temps n'est pas critique ici (le nonce
    #    n'est pas un secret), mais on reste prudent.
    # Normalisation Base64 avant comparaison : Google peut renvoyer le nonce
    # avec padding "=" (que l'app Android omet), ou avec encodage standard
    # `+/` (que l'app utilise URL-safe `-_`). On compare la forme canonique.
    if _canonical_nonce(nonce_in_token) != _canonical_nonce(expected_nonce):
        verdict.reason = "nonce_mismatch"
        return verdict

    # 5) Vérifie la fraîcheur du token (anti-replay temporel).
    if timestamp_millis is None:
        verdict.reason = "missing_timestamp"
        return verdict
    now_ms = int(time.time() * 1000)
    age_seconds = max(0, (now_ms - timestamp_millis) / 1000.0)
    if age_seconds > settings.integrity_max_age_seconds:
        verdict.reason = "token_expired"
        verdict.detail = f"age={int(age_seconds)}s"
        return verdict

    # 6) Anti-replay durable : le nonce n'a pas déjà été utilisé.
    nonce_key = _NONCE_PREFIX + expected_nonce
    # SET NX : crée si absent ; renvoie False si la clé existait déjà.
    was_new = await _redis.set(
        nonce_key, "1",
        ex=settings.integrity_max_age_seconds + 60,  # TTL > fenêtre acceptée
        nx=True,
    )
    if not was_new:
        verdict.reason = "nonce_replayed"
        return verdict

    # 7) Vérifie le verdict d'application (PLAY_RECOGNIZED requis).
    # En mode strict (`integrity_allow_unrecognized=False`), on exige
    # PLAY_RECOGNIZED. En mode tolérant (sideload Android Studio, APK pas
    # encore catalogué côté Play), on accepte aussi UNRECOGNIZED_VERSION.
    # Google bascule en PLAY_RECOGNIZED dès l'inscription en Internal Testing.
    accepted_app_verdicts = set(_ACCEPTED_APP_VERDICTS)
    if getattr(settings, "integrity_allow_unrecognized", False):
        accepted_app_verdicts.add("UNRECOGNIZED_VERSION")
    if app_verdict not in accepted_app_verdicts:
        verdict.reason = "app_not_recognized"
        verdict.detail = f"got={app_verdict}"
        return verdict

    # 8) Vérifie l'intégrité de l'appareil.
    #    Refuse explicitement les émulateurs Play (MEETS_VIRTUAL_INTEGRITY).
    if "MEETS_VIRTUAL_INTEGRITY" in device_verdicts:
        verdict.reason = "emulator_detected"
        return verdict
    min_rank = _DEVICE_VERDICT_RANK.get(settings.integrity_min_device_verdict, 2)
    best_rank = max(
        (_DEVICE_VERDICT_RANK.get(v, 0) for v in device_verdicts),
        default=0,
    )
    if best_rank < min_rank:
        verdict.reason = "device_integrity_low"
        verdict.detail = f"got={device_verdicts}"
        return verdict

    # 9) Vérifie la licence selon le mode configuré.
    accepted_licenses = (
        _ACCEPTED_LICENSE_VERDICTS_LENIENT
        if settings.integrity_allow_unlicensed
        else _ACCEPTED_LICENSE_VERDICTS_STRICT
    )
    if license_verdict not in accepted_licenses:
        verdict.reason = "license_rejected"
        verdict.detail = f"got={license_verdict}"
        return verdict

    # 10) Tous les contrôles passent.
    verdict.ok = True
    verdict.reason = "ok"
    return verdict
