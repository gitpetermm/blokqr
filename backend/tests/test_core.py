"""
Tests unitaires de BlokQR.

Couvre la garde anti-SSRF, l'analyse lexicale, la classification de payload,
la signature des verdicts et le moteur de notation. Aucun accès réseau requis.
"""
import pytest

from app.analyzers import lexical
from app.analyzers.payload_classifier import classify
from app.config import get_settings
from app.schemas import PayloadType, Severity, Verdict
from app.scoring.engine import compute_score
from app.security.signing import VerdictSigner, verify_ed25519
from app.security.ssrf_guard import SSRFError, resolve_and_validate


# --- SSRF ----------------------------------------------------------------- #
def test_ssrf_blocks_localhost():
    with pytest.raises(SSRFError):
        resolve_and_validate("http://127.0.0.1/admin", ["http", "https"])


def test_ssrf_blocks_cloud_metadata():
    with pytest.raises(SSRFError):
        resolve_and_validate("http://169.254.169.254/latest/meta-data",
                             ["http", "https"])


def test_ssrf_blocks_private_range():
    with pytest.raises(SSRFError):
        resolve_and_validate("http://10.10.40.10:9200/", ["http", "https"])


def test_ssrf_rejects_non_http_scheme():
    with pytest.raises(SSRFError):
        resolve_and_validate("file:///etc/passwd", ["http", "https"])


# --- Classification ------------------------------------------------------- #
def test_classify_wifi():
    c = classify("WIFI:T:WPA;S:MonReseau;P:secret;;")
    assert c.payload_type == PayloadType.WIFI
    assert c.extras["ssid"] == "MonReseau"
    assert c.extras["has_password"] is True


def test_classify_https_url():
    c = classify("https://exemple.com/page")
    assert c.payload_type == PayloadType.URL
    assert c.url == "https://exemple.com/page"


def test_classify_deep_link():
    c = classify("whatsapp://send?text=bonjour")
    assert c.payload_type == PayloadType.DEEP_LINK


# --- Lexical -------------------------------------------------------------- #
def test_lexical_detects_combosquatting():
    signals = lexical.analyze_url("https://paypal.secure-login.tk/verify")
    codes = {s.code for s in signals}
    assert "brand_combosquatting" in codes
    assert "high_risk_tld" in codes


def test_lexical_detects_embedded_credentials():
    signals = lexical.analyze_url("https://user@evil.example/path")
    assert any(s.code == "embedded_credentials" for s in signals)


def test_lexical_detects_qr_login():
    signals = lexical.analyze_url("https://web.whatsapp.com/")
    assert any(s.code == "qr_login_endpoint" for s in signals)


# --- Signature ------------------------------------------------------------ #
def test_verdict_signature_roundtrip():
    signer = VerdictSigner(ed25519_seed_b64="", enable_pq=False)
    payload = '{"v":"safe","score":0}'
    bundle = signer.sign(payload)
    assert verify_ed25519(payload, bundle.ed25519_b64,
                          bundle.public_key_ed25519_b64)
    # Une altération invalide la signature.
    assert not verify_ed25519('{"v":"dangerous"}', bundle.ed25519_b64,
                              bundle.public_key_ed25519_b64)


# --- Notation ------------------------------------------------------------- #
def test_scoring_critical_forces_malicious():
    from app.schemas import Signal
    settings = get_settings()
    signals = [Signal(code="x", title="t", severity=Severity.CRITICAL, weight=10)]
    score, verdict, _ = compute_score(signals, [], settings)
    assert verdict == Verdict.MALICIOUS


def test_scoring_mid_score_is_dangerous():
    from app.schemas import Signal
    settings = get_settings()
    # Score dans [dangerous, malicious) -> DANGEROUS (suspect).
    w = settings.score_threshold_dangerous + 2
    signals = [Signal(code="x", title="t", severity=Severity.MEDIUM, weight=w)]
    score, verdict, _ = compute_score(signals, [], settings)
    assert verdict == Verdict.DANGEROUS


def test_scoring_clean_is_safe():
    settings = get_settings()
    score, verdict, _ = compute_score([], [], settings)
    assert verdict == Verdict.SAFE
    assert score == 0


# --- Normalisation d'URL & hachage --------------------------------------- #
def test_url_normalization_strips_tracking_and_lowercases():
    from app.security.url_normalize import canonicalize
    a = canonicalize("HTTPS://Example.COM/Path?utm_source=x&b=2&a=1")
    assert a.startswith("https://example.com/Path")
    assert "utm_source" not in a
    assert "a=1" in a and "b=2" in a


def test_url_fingerprint_prefixes_are_4_bytes():
    from app.security.url_normalize import fingerprint
    fp = fingerprint("https://paypal.secure-login.tk/verify")
    assert fp.host == "paypal.secure-login.tk"
    assert len(fp.blake3_hex) == 64
    assert fp.expression_prefixes
    assert all(len(p) == 8 for p in fp.expression_prefixes)  # 4 octets = 8 hex


# --- Réputation k-anonyme ------------------------------------------------- #
def test_kanon_reputation_match_without_url():
    from app.analyzers.reputation_kanon import get_store
    from app.security.url_normalize import fingerprint, full_expression_hash
    store = get_store()
    fp = fingerprint("https://paypal.secure-login.tk/verify")
    # Le serveur ne reçoit que des préfixes.
    matches = store.search_prefixes(fp.expression_prefixes)
    # La correspondance finale (locale) confirme la présence d'un hash connu.
    seed_hash = full_expression_hash("paypal.secure-login.tk/verify")
    assert any(m.full_hash_hex == seed_hash for m in matches)


def test_kanon_clean_url_no_match():
    from app.analyzers.reputation_kanon import get_store
    from app.security.url_normalize import fingerprint
    store = get_store()
    fp = fingerprint("https://www.wikipedia.org/")
    seed_known = "paypal.secure-login.tk/verify"
    from app.security.url_normalize import full_expression_hash
    bad = full_expression_hash(seed_known)
    matches = store.search_prefixes(fp.expression_prefixes)
    assert all(m.full_hash_hex != bad for m in matches)


# --- Contexte temporel ---------------------------------------------------- #
def test_context_detects_destination_change():
    from app.analyzers.context_analyzer import analyze_context, current_destination_hash
    prior = current_destination_hash("https://legit-campaign.example/promo")
    signals = analyze_context("https://evil-phish.example/login", prior, None)
    assert any(s.code == "destination_changed" for s in signals)


def test_context_stable_destination():
    from app.analyzers.context_analyzer import analyze_context, current_destination_hash
    prior = current_destination_hash("https://shop.example/a")
    # Même hôte de destination -> stable.
    signals = analyze_context("https://shop.example/b", prior, None)
    assert any(s.code == "destination_stable" for s in signals)
    assert not any(s.code == "destination_changed" for s in signals)


# --- PQC : signature hybride Ed25519 + ML-DSA-65 ------------------------- #
def test_hybrid_signature_requires_both():
    from app.security.signing import VerdictSigner, verify_hybrid, verify_mldsa
    s = VerdictSigner(enable_pq=True)
    payload = '{"v":"malicious","score":94}'
    b = s.sign(payload)
    assert b.mldsa65_b64  # signature PQ présente
    assert verify_hybrid(payload, b.ed25519_b64, b.public_key_ed25519_b64,
                         b.mldsa65_b64, b.public_key_mldsa65_b64)
    # Altération du payload -> les deux échouent.
    assert not verify_hybrid('{"v":"safe"}', b.ed25519_b64, b.public_key_ed25519_b64,
                             b.mldsa65_b64, b.public_key_mldsa65_b64)
    # ML-DSA seul rejette une mauvaise signature PQ.
    assert not verify_mldsa(payload, "AAAA", b.public_key_mldsa65_b64)


# --- PQC : manifeste de clés SLH-DSA ------------------------------------- #
def test_slhdsa_manifest_roundtrip_and_tamper(tmp_path):
    from app.security.key_manifest import ManifestSigner, verify_manifest
    ms = ManifestSigner(str(tmp_path / "root.json"))
    m = ms.build(version=1, key_id="abc", ed25519_pub_b64="ed", mldsa65_pub_b64="ml",
                 mlkem768_pub_b64="kem")
    assert verify_manifest(m.canonical, m.sig_slhdsa_b64, ms.root_pub_b64)
    assert not verify_manifest(m.canonical.replace('"abc"', '"evil"'),
                               m.sig_slhdsa_b64, ms.root_pub_b64)


# --- PQC : enveloppe hybride ML-KEM-768 + X25519 ------------------------- #
def test_mlkem_envelope_roundtrip_hides_plaintext():
    from app.security import pq_envelope as env
    gk = env.GatewayKeys.generate()
    sealed = env.seal_json({"prefixes": ["deadbeef", "cafe1234"]}, gk.public_bundle)
    assert "deadbeef" not in str(sealed)  # confidentialité
    assert env.open_json(sealed, gk) == {"prefixes": ["deadbeef", "cafe1234"]}


# --- Capability-URL ------------------------------------------------------- #
def test_capability_url_detection():
    from app.analyzers.capability_url import assess_capability
    assert assess_capability("https://app.example/reset?token=abc").is_capability
    assert assess_capability(
        "https://x.example/u/AbCdEf0123456789AbCdEf0123456789").is_capability
    assert not assess_capability("https://example.com/produits/chaussures").is_capability


# --- Intelligence de domaine --------------------------------------------- #
def test_domain_lookalike_and_legit():
    from app.analyzers.domain_intel import analyze_domain, registrable_domain
    assert registrable_domain("login.smile.amazon.fr") == "amazon.fr"
    bad = analyze_domain("https://www.paypa1.com/login")
    assert any(s.code == "lookalike_domain" for s in bad.signals)
    good = analyze_domain("https://www.amazon.fr/")
    assert not good.signals


# --- Contexte : consensus communautaire ---------------------------------- #
def test_consensus_catches_first_scan_flip():
    from app.analyzers.context_analyzer import (
        analyze_context, current_destination_hash, get_consensus_store)
    store = get_consensus_store()
    src = "src-test-xyz"
    legit = current_destination_hash("https://campaign.example/promo")
    for c in ["a", "b", "c", "d", "e"]:
        store.contribute(src, legit, c)
    sigs = analyze_context("https://phish.tk/login", None, None, source_hash=src)
    assert any(s.code == "destination_diverges_consensus" for s in sigs)
