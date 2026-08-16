import ast

FILES = {
    'app/security/pqc.py': (
        'ALG_ROOT_PQ = "SPHINCS+-SHA2-128s"',
        [
            (
                '''  - SLH-DSA / SPHINCS+ (FIPS 205) : signature du MANIFESTE DE CLÉS (racine de''',
                '''  - SPHINCS+-SHA2-128s (round 3) : signature du MANIFESTE DE CLÉS (racine de''',
            ),
            (
                '''ALG_ROOT_PQ = "SLH-DSA-SHA2-128s"''',
                '''ALG_ROOT_PQ = "SPHINCS+-SHA2-128s"''',
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
