"""
Creation des tables PostgreSQL -- BlokQR (Phase 1).

A lancer UNE FOIS pour creer le schema (installs, subscriptions) sur une base
vierge. Idempotent : create_all ne recree pas les tables existantes.

Usage (dans le conteneur backend) :
    python -m app.db.init_db

Ce script NE TOUCHE PAS aux donnees Redis ni au code de production. Il ne fait
que creer les tables dans PostgreSQL.
"""
from __future__ import annotations

import asyncio

from app.db.engine import get_engine
from app.db.models import Base


async def create_all() -> None:
    engine = get_engine()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    print("Tables creees (ou deja presentes) : installs, subscriptions")


if __name__ == "__main__":
    asyncio.run(create_all())
