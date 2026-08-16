import ast

FILES = {
    'app/analyzers/threat_intel.py': (
        'def _sanitize_for_ti',
        [
            (
                '''async def gather_threat_intel(url: str, settings: Settings) -> List[ThreatIntelResult]:
    """Interroge toutes les sources en parallèle avec un budget de temps borné."""''',
                '''def _sanitize_for_ti(url: str) -> str:
    """Retire les éléments porteurs de secrets (identifiants intégrés, fragment)
    avant toute interrogation d'un service tiers : ils n'ont aucune valeur pour la
    réputation et pourraient exposer un jeton (ex. un access_token placé en fragment).
    La query est conservée car elle porte un signal utile à la détection."""
    try:
        from urllib.parse import urlsplit, urlunsplit
        p = urlsplit(url)
        host = p.hostname or ""
        if ":" in host:
            host = f"[{host}]"
        netloc = f"{host}:{p.port}" if p.port else host
        return urlunsplit((p.scheme, netloc, p.path, p.query, ""))
    except Exception:  # noqa: BLE001
        return url


async def gather_threat_intel(url: str, settings: Settings) -> List[ThreatIntelResult]:
    """Interroge toutes les sources en parallèle avec un budget de temps borné."""
    url = _sanitize_for_ti(url)''',
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
