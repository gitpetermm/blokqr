"""Configuration de la journalisation — confidentialité d'abord.

But : empêcher toute fuite, dans les logs, des URLs scannées (qui peuvent
contenir des capability-tokens, ex. `?CD_ACCESS=...`) et des clés API.

Le client HTTP `httpx`/`httpcore` journalise chaque requête sortante au niveau
INFO, ce qui imprime l'URL COMPLÈTE : la cible scannée, la destination après
redirection, et l'appel Web Risk avec `?uri=...&key=AIza...`. On rétrograde ces
journaux en WARNING (ils disparaissent en exploitation normale), puis on installe
un filtre de rédaction qui masque le query-string et les paramètres sensibles au
cas où un message de log contiendrait quand même une URL.

À appeler UNE FOIS au démarrage de l'application (avant de servir des requêtes).
"""
from __future__ import annotations
import logging
import re
# Clients HTTP dont la verbosité expose les URLs scannées et les clés API.
_NOISY_HTTP_LOGGERS = (
    "httpx",
    "httpcore",
    "httpcore.http11",
    "httpcore.connection",
)
# Paramètres de requête sensibles à masquer s'ils apparaissent dans un log.
_SENSITIVE_PARAMS = ("key", "token", "access_token", "access-token",
                     "cd_access", "auth", "apikey", "api_key", "uri", "url")
class RedactingFilter(logging.Filter):
    """Masque les query-strings d'URL et les paramètres sensibles dans les logs.

    Défense en profondeur : même si un message contient une URL avec des
    secrets, ils sont remplacés par [REDACTED] avant écriture.
    """
    _KEY = re.compile(
        r"([?&](?:" + "|".join(re.escape(p) for p in _SENSITIVE_PARAMS) + r")=)[^&\s\"']+",
        re.IGNORECASE,
    )
    _QUERY = re.compile(r"(https?://[^\s?\"']+)\?[^\s\"']*")
    def filter(self, record: logging.LogRecord) -> bool:
        try:
            msg = record.getMessage()
        except Exception:
            return True
        if "://" in msg or "?" in msg:
            redacted = self._KEY.sub(r"\1[REDACTED]", msg)
            redacted = self._QUERY.sub(r"\1?[REDACTED]", redacted)
            if redacted != msg:
                # On remplace le message déjà formaté (args consommés).
                record.msg = redacted
                record.args = ()
        return True
def configure_logging(app_level: int = logging.INFO) -> None:
    """Configure la journalisation privacy-first. Idempotent.

    - Rétrograde httpx/httpcore en WARNING (supprime l'impression des URLs).
    - Installe un filtre de rédaction sur les handlers racine + uvicorn.
    """
    # 1) Couper la verbosité des clients HTTP (source de la fuite d'URLs/clés).
    for name in _NOISY_HTTP_LOGGERS:
        logging.getLogger(name).setLevel(logging.WARNING)
    # 2) Filtre de rédaction sur tous les handlers existants (racine + uvicorn).
    redactor = RedactingFilter()
    targets = [logging.getLogger()]  # root
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        targets.append(logging.getLogger(name))
    for lg in targets:
        # Évite d'empiler plusieurs fois le même filtre (idempotence).
        for handler in lg.handlers:
            if not any(isinstance(f, RedactingFilter) for f in handler.filters):
                handler.addFilter(redactor)
    # 3) Niveau applicatif global (n'affecte pas le WARNING forcé ci-dessus).
    logging.getLogger().setLevel(app_level)
