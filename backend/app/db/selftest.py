"""
Auto-test isole du module db -- BlokQR (Phase 1).

Verifie, SANS toucher a la prod ni a Redis :
  1. la connexion a PostgreSQL,
  2. la creation des tables,
  3. un insert + read + count sur installs et subscriptions,
  4. le nettoyage des donnees de test.

Usage (dans le conteneur backend) :
    python -m app.db.selftest
"""
from __future__ import annotations

import asyncio

from app.db.engine import get_engine, get_session
from app.db.models import Base, Install, Subscription
from app.db import repo


async def main() -> None:
    # 1. Creation des tables.
    engine = get_engine()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    print("[1] Tables OK")

    # 2. Insert install de test.
    test_id = "00000000-0000-0000-0000-000000000000"
    await repo.db_create_install(test_id, "c2VjcmV0X3Rlc3Q=")
    print("[2] Install inseree")

    # 3. Read + verif.
    secret = await repo.db_get_secret(test_id)
    exists = await repo.db_install_exists(test_id)
    assert secret == "c2VjcmV0X3Rlc3Q=", "secret incorrect"
    assert exists is True, "install introuvable"
    print(f"[3] Read OK (secret={secret}, exists={exists})")

    # 4. Lien d'achat de test.
    await repo.db_link_purchase("token_test_xyz", test_id,
                                product_id="blokqr_pro", state="active")
    linked = await repo.db_install_for_token("token_test_xyz")
    assert linked == test_id, "lien purchaseToken->install incorrect"
    print(f"[4] Lien d'achat OK (install={linked})")

    # 5. Comptages.
    ni = await repo.db_count_installs()
    ns = await repo.db_count_subscriptions()
    print(f"[5] Comptes : installs={ni}, subscriptions={ns}")

    # 6. Nettoyage des donnees de test.
    async with get_session() as session:
        sub = await session.get(Subscription, repo.purchase_token_hash("token_test_xyz"))
        if sub:
            await session.delete(sub)
        inst = await session.get(Install, test_id)
        if inst:
            await session.delete(inst)
    print("[6] Nettoyage OK")
    print("\nSUCCES : le module db fonctionne (connexion + CRUD + nettoyage).")


if __name__ == "__main__":
    asyncio.run(main())
