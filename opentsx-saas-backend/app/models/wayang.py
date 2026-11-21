from datetime import datetime, timezone

from sqlalchemy import BigInteger, Boolean, CheckConstraint, Column, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import relationship

from app.db.base_class import Base


def utc_now():
    return datetime.now(timezone.utc).replace(tzinfo=None)


class WayangDeployment(Base):
    __tablename__ = "wayang_deployments"
    __table_args__ = (UniqueConstraint("spec_hash", name="uq_wayang_spec"),)

    id = Column(String(63), primary_key=True)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    flow_id = Column(Integer, nullable=False)
    spec_hash = Column(String(64), nullable=False)
    dag_text = Column(Text, nullable=False)
    created_at_micros = Column(String(20), nullable=False)
    plan_hash = Column(String(64), nullable=False)
    plan_text = Column(Text, nullable=False)
    state = Column(String(16), nullable=False, default="queued", index=True)
    result = Column(JSON, nullable=True)
    error = Column(Text, nullable=True)
    created_at = Column(DateTime, nullable=False, default=utc_now)
    updated_at = Column(DateTime, nullable=False, default=utc_now)
    continuous = relationship("WayangContinuous", uselist=False, lazy="joined")
    publication = relationship("WayangPublication", uselist=False, lazy="joined")


class WayangTopicOwner(Base):
    __tablename__ = "wayang_topic_owners"
    __table_args__ = (CheckConstraint(
        "(scope = 'legacy' AND fragment = '' AND cluster = '' AND incarnation = '') OR "
        "(scope = 'physical' AND fragment <> '' AND cluster <> '' AND incarnation <> '')",
        name="ck_wayang_owner_scope"),)

    scope = Column(String(8), primary_key=True, default="legacy")
    fragment = Column(String(256), primary_key=True, default="")
    cluster = Column(String(256), primary_key=True, default="")
    incarnation = Column(String(256), primary_key=True, default="")
    topic = Column(String(249), primary_key=True)
    deployment_id = Column(String(63), ForeignKey("wayang_deployments.id"), nullable=False, index=True)


class WayangContinuous(Base):
    __tablename__ = "wayang_continuous"

    deployment_id = Column(String(63), ForeignKey("wayang_deployments.id"), primary_key=True)
    current_cycle = Column(BigInteger, nullable=False, default=1)
    completed_cycles = Column(BigInteger, nullable=False, default=0)
    stop_requested = Column(Boolean, nullable=False, default=False)
    next_poll_at = Column(DateTime, nullable=False, default=utc_now)
    totals = Column(JSON, nullable=False, default=lambda: {"inputRecords": "0", "outputRecords": "0", "assignments": {}})


class WayangCycle(Base):
    __tablename__ = "wayang_cycles"

    deployment_id = Column(String(63), ForeignKey("wayang_deployments.id"), primary_key=True)
    number = Column(BigInteger, primary_key=True)
    plan_hash = Column(String(64), nullable=False)
    plan_text = Column(Text, nullable=False)
    state = Column(String(16), nullable=False, default="queued")
    result = Column(JSON, nullable=True)
    error = Column(Text, nullable=True)
    created_at = Column(DateTime, nullable=False, default=utc_now)
    updated_at = Column(DateTime, nullable=False, default=utc_now)


class WayangPublication(Base):
    __tablename__ = "wayang_publication"

    deployment_id = Column(String(63), ForeignKey("wayang_deployments.id"), primary_key=True)
    epoch = Column(BigInteger, nullable=False, default=0)
    retired = Column(Boolean, nullable=False, default=False)
    retire_operation = Column(String(63), nullable=True)
    operation_id = Column(String(63), ForeignKey("wayang_switches.id"), nullable=True)
    operation = relationship("WayangSwitch", uselist=False, lazy="joined")


class WayangSwitch(Base):
    __tablename__ = "wayang_switches"
    __table_args__ = (UniqueConstraint("deployment_id", "expected_epoch", name="uq_wayang_switch_epoch"),)

    id = Column(String(63), primary_key=True)
    deployment_id = Column(String(63), ForeignKey("wayang_deployments.id"), nullable=False, index=True)
    expected_epoch = Column(BigInteger, nullable=False)
    mode = Column(String(7), nullable=False)
    body_hash = Column(String(64), nullable=False)
    replacement_id = Column(String(63), ForeignKey("wayang_deployments.id"), nullable=True)
    publication_epoch = Column(BigInteger, nullable=True)
    state = Column(String(16), nullable=False, default="pending")
    error = Column(Text, nullable=True)
    created_at = Column(DateTime, nullable=False, default=utc_now)
    updated_at = Column(DateTime, nullable=False, default=utc_now)
