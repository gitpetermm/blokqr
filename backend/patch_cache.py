import ast, base64, os

# 1) Nouveau module (embarqué en base64 pour préserver l'encodage exact)
NEW_PATH = 'app/analysis_cache.py'
NEW_B64 = "IiIiCkNhY2hlIG3DqW1vaXJlIChUVEwgKyBMUlUgYm9ybsOpKSBkZXMgcsOpc3VsdGF0cyBkJ2FuYWx5c2UuCgpCdXQgOiDDqXZpdGVyIGRlIHJlbGFuY2VyIHRvdXQgbGUgcGlwZWxpbmUgKHLDqXNvbHV0aW9uICsgcmVuZHUgaGVhZGxlc3MgKyB0aHJlYXQKaW50ZWxsaWdlbmNlKSBsb3JzcXUndW4gbcOqbWUgY29udGVudSBlc3QgcmVzY2FubsOpIOKAlCBjZSBxdWkgcsOpZHVpdCBsYSBsYXRlbmNlIEVUCmRpdmlzZSBsZSBjb8O7dCBkJ3VuIMOpdmVudHVlbCBhYnVzIGRlIGwnZW5kcG9pbnQuCgpHYXJhbnRpZXMgZGUgc8OpY3VyaXTDqSA6CiAgLSBPbiBuZSBtZXQgZW4gY2FjaGUgUVVFIGRlcyBhbmFseXNlcyBDT01QTMOIVEVTICh2ZXJkaWN0ICE9IHVua25vd24pLiBVbiB2ZXJkaWN0CiAgICBwcnVkZW50L2luY29tcGxldCAodGltZW91dCwgcGFnZSBkZSB0cmFuc2l0IG5vbiByw6lzb2x1ZSwgaG9sZCBkZSB2aWUgcHJpdsOpZSkKICAgIG5lIGRvaXQgamFtYWlzIMOqdHJlIGZpZ8OpIDogbGUgc2NhbiBzdWl2YW50IGRvaXQgcG91dm9pciByZXRlbnRlci4KICAtIE9uIG5lIHN0b2NrZSBRVUUgbGUgdHJpcGxldCAodmVyZGljdCwgc2NvcmUsIHJhcHBvcnQpLiBMYSBTSUdOQVRVUkUgbidlc3QKICAgIGphbWFpcyBtaXNlIGVuIGNhY2hlIDogZWxsZSBlc3Qgc3lzdMOpbWF0aXF1ZW1lbnQgcmVjYWxjdWzDqWUgcGFyIHJlcXXDqnRlLCBsacOpZQogICAgYXUgbm9uY2UgZHUgY2xpZW50IGV0IMOgIGxhIGZyYcOuY2hldXIgKGlzc3VlZF9hdCAvIGV4cGlyZXNfYXQpLiBVbiBoaXQgZGUgY2FjaGUKICAgIHJlc3RlIGRvbmMgbGnDqSBhdSBjbGllbnQgcXVpIGxlIHJlw6dvaXQgKHBhcyBkZSByZWpldSBpbnRlci1jbGllbnRzKS4KICAtIExlIG5vbmNlIGNsaWVudCBuJ2VudHJlIEpBTUFJUyBkYW5zIGxhIGNsw6kgZGUgY2FjaGUuCiAgLSBCb3JuZSBtw6ltb2lyZSBzdHJpY3RlICjDqXZpY3Rpb24gTFJVKSA6IHBhcyBkZSBmdWl0ZSBzb3VzIGNoYXJnZS4KIiIiCmZyb20gX19mdXR1cmVfXyBpbXBvcnQgYW5ub3RhdGlvbnMKCmltcG9ydCB0aW1lCmZyb20gY29sbGVjdGlvbnMgaW1wb3J0IE9yZGVyZWREaWN0CmZyb20gZGF0YWNsYXNzZXMgaW1wb3J0IGRhdGFjbGFzcwpmcm9tIHR5cGluZyBpbXBvcnQgT3B0aW9uYWwsIFR1cGxlCgpmcm9tIGFwcC5jb25maWcgaW1wb3J0IFNldHRpbmdzCmZyb20gYXBwLnNjaGVtYXMgaW1wb3J0IEFuYWx5c2lzUmVwb3J0LCBBbmFseXplUmVxdWVzdCwgVmVyZGljdAoKIyBTw6lwYXJhdGV1ciBkZSBjaGFtcHMgaW1wcm9iYWJsZSBkYW5zIHVuZSBjaGFyZ2UgdXRpbGUgKHVuaXQgc2VwYXJhdG9yIFVTKS4KX1NFUCA9ICJceDFmIgoKCkBkYXRhY2xhc3MKY2xhc3MgQ2FjaGVkQW5hbHlzaXM6CiAgICAiIiJJbnN0YW50YW7DqSBpbW11YWJsZSBkJ3VuZSBhbmFseXNlIGNvbXBsw6h0ZSAoc2FucyBzaWduYXR1cmUpLiIiIgogICAgdmVyZGljdDogVmVyZGljdAogICAgc2NvcmU6IGludAogICAgcmVwb3J0OiBBbmFseXNpc1JlcG9ydAoKCmNsYXNzIEFuYWx5c2lzQ2FjaGU6CiAgICAiIiJDYWNoZSDDoCBleHBpcmF0aW9uIChUVEwpIGV0IGNhcGFjaXTDqSBib3Juw6llIChMUlUpLiIiIgoKICAgIGRlZiBfX2luaXRfXyhzZWxmLCB0dGxfc2Vjb25kczogZmxvYXQsIG1heF9lbnRyaWVzOiBpbnQpIC0+IE5vbmU6CiAgICAgICAgc2VsZi50dGwgPSBmbG9hdCh0dGxfc2Vjb25kcykKICAgICAgICBzZWxmLm1heCA9IGludChtYXhfZW50cmllcykKICAgICAgICBzZWxmLl9zdG9yZTogIk9yZGVyZWREaWN0W3N0ciwgVHVwbGVbZmxvYXQsIENhY2hlZEFuYWx5c2lzXV0iID0gT3JkZXJlZERpY3QoKQoKICAgIGRlZiBnZXQoc2VsZiwga2V5OiBzdHIpIC0+IE9wdGlvbmFsW0NhY2hlZEFuYWx5c2lzXToKICAgICAgICBpdGVtID0gc2VsZi5fc3RvcmUuZ2V0KGtleSkKICAgICAgICBpZiBpdGVtIGlzIE5vbmU6CiAgICAgICAgICAgIHJldHVybiBOb25lCiAgICAgICAgZXhwaXJ5LCBjYWNoZWQgPSBpdGVtCiAgICAgICAgaWYgdGltZS5tb25vdG9uaWMoKSA+PSBleHBpcnk6CiAgICAgICAgICAgIHNlbGYuX3N0b3JlLnBvcChrZXksIE5vbmUpICAjIHB1cmdlIHBhcmVzc2V1c2UgZGUgbCdlbnRyw6llIGV4cGlyw6llCiAgICAgICAgICAgIHJldHVybiBOb25lCiAgICAgICAgc2VsZi5fc3RvcmUubW92ZV90b19lbmQoa2V5KSAgICAjIG1hcnF1ZSBjb21tZSByw6ljZW1tZW50IHV0aWxpc8OpZQogICAgICAgIHJldHVybiBjYWNoZWQKCiAgICBkZWYgc2V0KHNlbGYsIGtleTogc3RyLCBjYWNoZWQ6IENhY2hlZEFuYWx5c2lzKSAtPiBOb25lOgogICAgICAgIGlmIHNlbGYudHRsIDw9IDAgb3Igc2VsZi5tYXggPD0gMDoKICAgICAgICAgICAgcmV0dXJuICAjIGNhY2hlIGVmZmVjdGl2ZW1lbnQgZMOpc2FjdGl2w6kKICAgICAgICBzZWxmLl9zdG9yZVtrZXldID0gKHRpbWUubW9ub3RvbmljKCkgKyBzZWxmLnR0bCwgY2FjaGVkKQogICAgICAgIHNlbGYuX3N0b3JlLm1vdmVfdG9fZW5kKGtleSkKICAgICAgICB3aGlsZSBsZW4oc2VsZi5fc3RvcmUpID4gc2VsZi5tYXg6CiAgICAgICAgICAgIHNlbGYuX3N0b3JlLnBvcGl0ZW0obGFzdD1GYWxzZSkgICMgw6l2aWN0aW9uIGRlIGxhIHBsdXMgYW5jaWVubmUKCiAgICBkZWYgY2xlYXIoc2VsZikgLT4gTm9uZToKICAgICAgICBzZWxmLl9zdG9yZS5jbGVhcigpCgogICAgQHByb3BlcnR5CiAgICBkZWYgc2l6ZShzZWxmKSAtPiBpbnQ6CiAgICAgICAgcmV0dXJuIGxlbihzZWxmLl9zdG9yZSkKCgpfY2FjaGU6IE9wdGlvbmFsW0FuYWx5c2lzQ2FjaGVdID0gTm9uZQoKCmRlZiBnZXRfY2FjaGUoc2V0dGluZ3M6IFNldHRpbmdzKSAtPiBPcHRpb25hbFtBbmFseXNpc0NhY2hlXToKICAgICIiIlNpbmdsZXRvbiBwYXJlc3NldXguIFJlbnZvaWUgTm9uZSBzaSBsZSBjYWNoZSBlc3QgZMOpc2FjdGl2w6kgZW4gY29uZmlnLiIiIgogICAgZ2xvYmFsIF9jYWNoZQogICAgaWYgbm90IHNldHRpbmdzLmVuYWJsZV9hbmFseXNpc19jYWNoZToKICAgICAgICByZXR1cm4gTm9uZQogICAgaWYgX2NhY2hlIGlzIE5vbmU6CiAgICAgICAgX2NhY2hlID0gQW5hbHlzaXNDYWNoZSgKICAgICAgICAgICAgdHRsX3NlY29uZHM9c2V0dGluZ3MuYW5hbHlzaXNfY2FjaGVfdHRsX3NlY29uZHMsCiAgICAgICAgICAgIG1heF9lbnRyaWVzPXNldHRpbmdzLmFuYWx5c2lzX2NhY2hlX21heF9lbnRyaWVzLAogICAgICAgICkKICAgIHJldHVybiBfY2FjaGUKCgpkZWYgY2FjaGVfa2V5KHJlcXVlc3Q6IEFuYWx5emVSZXF1ZXN0KSAtPiBzdHI6CiAgICAiIiJDbMOpIGRlIGNhY2hlIExPU1NMRVNTIDogc3ltYm9sb2dpZSArIGNoYXJnZSBicnV0ZSBleGFjdGUuCgogICAgT24gZ2FyZGUgbGEgY2hhcmdlIGJydXRlIChldCBub24gdW5lIGZvcm1lIG5vcm1hbGlzw6llKSBwb3VyIG5lIGphbWFpcwogICAgZnVzaW9ubmVyIGRldXggZW50csOpZXMgcXVpIHBvdXJyYWllbnQgZGlmZsOpcmVyIHN1ciB1biBkw6l0YWlsIGluZmx1ZW7Dp2FudCBsZQogICAgdmVyZGljdCAocGFyYW3DqHRyZSBkZSByZXF1w6p0ZSwgZnJhZ21lbnQsIGNhc3NlIGR1IGNoZW1pbi4uLikuIExlIG5vbmNlIGNsaWVudAogICAgbidlbnRyZSBwYXMgZGFucyBsYSBjbMOpIDogdW4gbcOqbWUgUVIgcmVzY2FubsOpIHBhciBuJ2ltcG9ydGUgcXVpIGZhaXQgbW91Y2hlLgogICAgIiIiCiAgICBzeW0gPSAocmVxdWVzdC5zeW1ib2xvZ3kgb3IgIiIpLmxvd2VyKCkKICAgIHJldHVybiBmIntzeW19e19TRVB9e3JlcXVlc3QucmF3X3BheWxvYWR9Igo="
if os.path.exists(NEW_PATH):
    print(f'[skip] {NEW_PATH} : existe deja')
else:
    content = base64.b64decode(NEW_B64).decode('utf-8')
    ast.parse(content)
    open(NEW_PATH, 'w', encoding='utf-8').write(content)
    print(f'[ok] {NEW_PATH} cree')

# 2) Editions in-place (marqueur-gardees, abandon si ancre ambigue)
FILES = {
    'app/config.py': (
        'enable_analysis_cache',
        [
            (
                '''    # --- Cache d'analyse -----------------------------------------------------
    analysis_cache_ttl_seconds: int = 300''',
                '''    # --- Cache d'analyse -----------------------------------------------------
    # Cache mémoire (TTL + LRU) des résultats d'analyse complets. La signature
    # reste recalculée à chaque requête : seul le travail coûteux (résolution,
    # rendu, threat intel) est évité sur un rescan. Verdicts unknown jamais mis
    # en cache (prudence non figée).
    enable_analysis_cache: bool = True
    analysis_cache_ttl_seconds: int = 300
    analysis_cache_max_entries: int = 5000''',
            ),
        ],
    ),
    'app/pipeline.py': (
        'from app.analysis_cache import',
        [
            (
                '''from app.analyzers.threat_intel import gather_threat_intel
from app.config import Settings''',
                '''from app.analyzers.threat_intel import gather_threat_intel
from app.analysis_cache import CachedAnalysis, cache_key, get_cache
from app.config import Settings''',
            ),
            (
                '''    """Exécute le pipeline complet et renvoie un verdict signé."""
    classified = classify(request.raw_payload)''',
                '''    """Exécute le pipeline complet et renvoie un verdict signé."""
    # Cache : sur un rescan du même contenu, on rejoue le verdict mis en cache
    # mais on RE-SIGNE pour CE client (nonce + fraîcheur propres à la requête).
    cache = get_cache(settings)
    ckey = cache_key(request)
    if cache is not None:
        hit = cache.get(ckey)
        if hit is not None:
            return _finalize(hit.verdict, hit.score, hit.report, request, settings, signer)

    classified = classify(request.raw_payload)''',
            ),
            (
                '''    report.signals = signals
    report.reasons = _build_reasons(verdict, report, signals)

    # 9. Signature du verdict (liée au hash du rapport complet).
    return _finalize(verdict, score, report, request, settings, signer)''',
                '''    report.signals = signals
    report.reasons = _build_reasons(verdict, report, signals)

    # 9. Signature du verdict (liée au hash du rapport complet).
    # Mise en cache des seules analyses COMPLÈTES (jamais un verdict unknown :
    # une prudence transitoire ne doit pas être figée). Instantané deep-copié.
    if cache is not None and verdict != Verdict.UNKNOWN:
        cache.set(ckey, CachedAnalysis(
            verdict=verdict, score=score, report=report.model_copy(deep=True),
        ))
    return _finalize(verdict, score, report, request, settings, signer)''',
            ),
        ],
    ),
}

for path, (marker, prs) in FILES.items():
    s = open(path, encoding='utf-8').read()
    if marker in s:
        print(f'[skip] {path} : deja patche'); continue
    for i, (old, new) in enumerate(prs, 1):
        n = s.count(old)
        assert n == 1, f'[ABANDON] {path} ancre #{i} trouvee {n} fois - rien ecrit'
        s = s.replace(old, new, 1)
    ast.parse(s)
    open(path, 'w', encoding='utf-8').write(s)
    print(f'[ok] {path} patche')
