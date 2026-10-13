"""Working copy of a system document: the sources a user set for signals in the web application."""

from datetime import datetime

from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Integer, JSON, String, UniqueConstraint

from app.db.base_class import Base


class SignalBinding(Base):
    """
    The source of one grade of one signal, set by one user. It lies over the system document of the repository until
    it is written into the file (docs/datamodel-2026/14-ENGINEERING-FLOW.md, decision D42).
    """

    __tablename__ = "signal_bindings"
    __table_args__ = (UniqueConstraint("owner_id", "system", "signal", "grade", name="uq_signal_binding"),)

    id = Column(Integer, primary_key=True, index=True)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    system = Column(String, nullable=False, index=True)
    signal = Column(String, nullable=False)
    grade = Column(String, nullable=False)
    # the source as in signals[].<grade>.source of opentsx.system.v1
    source = Column(JSON, nullable=False)
    # the person confirmed that the bound data is the quantity the signal means (decision D53)
    meaning_confirmed = Column(Boolean, nullable=False, default=False)
    # hash of the system document the binding was made against
    base_hash = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
