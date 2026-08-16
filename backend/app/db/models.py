"""
Modeles SQLAlchemy (ORM async) -- source de verite durable BlokQR.

Deux tables, miroir des donnees durables aujourd'hui dans Redis :
  - installs      : remplace devkey:{install_id} -> secret HMAC.
  - subscriptions : remplace ptlink:{sha256(purchaseToken)} -> install_id,
                    enrichi de l'etat d'abonnement (product, state, expiry).

Ce module ne remplace RIEN pour l'instant : il est ecrit et teste isolement.
Le code de production continue d'utiliser Redis jusqu'a la Phase 2
(double ecriture), puis la Phase 3 (bascule des lectures).
"""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    """Base declarative commune a tous les modeles."""


class Install(Base):
    """Une installation de l'app (identifiant stable non-PII + secret HMAC).

    Remplace la cle Redis devkey:{install_id} -> secret_b64.
    install_id : UUIDv4 genere cote serveur, stocke par l'app.
    hmac_secret : secret partage (base64), sert a signer les requetes.
    """

    __tablename__ = "installs"

    install_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    hmac_secret: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    last_seen_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    subscriptions: Mapped[list["Subscription"]] = relationship(
        back_populates="install", cascade="all, delete-orphan"
    )


class Subscription(Base):
    """Lien purchaseToken (hache) -> installation, + etat d'abonnement.

    Remplace la cle Redis ptlink:{sha256(purchaseToken)} -> install_id.
    On stocke le HASH du token (jamais le token en clair), comme le fait
    deja rtdn.py (_pth = sha256). Enrichi de l'etat Play (product/state/expiry)
    pour devenir la source de verite des abonnements.
    """

    __tablename__ = "subscriptions"

    # sha256 hex du purchaseToken (64 caracteres), cle primaire.
    purchase_token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    install_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("installs.install_id", ondelete="CASCADE"),
        nullable=False, index=True,
    )
    product_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    state: Mapped[str | None] = mapped_column(String(32), nullable=True)  # active/cancelled/expired...
    expiry_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(),
        onupdate=func.now(), nullable=False,
    )

    install: Mapped["Install"] = relationship(back_populates="subscriptions")
