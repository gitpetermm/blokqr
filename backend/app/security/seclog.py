# app/security/seclog.py
import hashlib, logging, os

logger = logging.getLogger("blokqr.security")
_SALT = os.environ["SECLOG_SALT"].encode()   # secret, jamais committé

def pseudonymize_ip(ip: str) -> str:
    return hashlib.sha256(_SALT + ip.encode()).hexdigest()[:16]

def log_security_event(event: str, ip: str, device_id: str = "", detail: str = ""):
    logger.warning(
        "secevent=%s ip_hash=%s device=%s detail=%s",
        event, pseudonymize_ip(ip), device_id, detail,
    )
