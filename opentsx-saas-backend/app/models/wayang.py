from datetime import datetime, timezone

from sqlalchemy import Column, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint

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


class WayangTopicOwner(Base):
    __tablename__ = "wayang_topic_owners"

    topic = Column(String(249), primary_key=True)
    deployment_id = Column(String(63), ForeignKey("wayang_deployments.id"), nullable=False, index=True)
