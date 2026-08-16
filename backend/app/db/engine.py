"""
Engine SQLAlchemy async + fabrique de sessions -- BlokQR.

Cree un moteur asyncpg unique (pool de connexions) et une factory de sessions.
L'URL vient de settings.database_url (lue depuis .env), au format :
  postgresql+asyncpg://user:password@postgres:5432/blokqr

Usage (dans un module qui accede a la base) :
    from app.db.engine import get_session
    async with get_session() as session:
        ...  # requetes ORM
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

from sqlalchemy.ext.asyncio import (
    AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine,
)

from app.config import get_settings

_engine: AsyncEngine | None = None
_session_factory: async_sessionmaker[AsyncSession] | None = None


def get_engine() -> AsyncEngine:
    """Retourne l'engine async unique (cree a la premiere demande)."""
    global _engine
    if _engine is None:
        settings = get_settings()
        _engine = create_async_engine(
            settings.database_url,
            pool_pre_ping=True,   # verifie la connexion avant usage (evite les stale)
            pool_size=5,
            max_overflow=5,
            echo=False,
        )
    return _engine


def get_session_factory() -> async_sessionmaker[AsyncSession]:
    """Retourne la factory de sessions (creee a la premiere demande)."""
    global _session_factory
    if _session_factory is None:
        _session_factory = async_sessionmaker(
            bind=get_engine(), expire_on_commit=False, class_=AsyncSession,
        )
    return _session_factory


@asynccontextmanager
async def get_session() -> AsyncIterator[AsyncSession]:
    """Context manager de session : commit auto si succes, rollback si erreur."""
    factory = get_session_factory()
    async with factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
