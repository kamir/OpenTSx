"""Flow database models."""

from datetime import datetime
from sqlalchemy import Boolean, Column, DateTime, Integer, String, ForeignKey, JSON, Enum as SQLEnum, Text
import enum

from app.db.base_class import Base


class FlowStatus(str, enum.Enum):
    """Flow execution status."""
    DRAFT = "draft"
    ACTIVE = "active"
    PAUSED = "paused"
    ARCHIVED = "archived"


class ExecutionStatus(str, enum.Enum):
    """Execution status."""
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class Flow(Base):
    """Flow model (visual pipeline definition)."""

    __tablename__ = "flows"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, index=True, nullable=False)
    description = Column(Text, nullable=True)

    # Flow definition (JSON descriptor)
    definition = Column(JSON, nullable=False)

    # Ownership
    owner_id = Column(Integer, ForeignKey("users.id"))
    organization_id = Column(Integer, ForeignKey("organizations.id"))
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=True)

    # Status
    status = Column(SQLEnum(FlowStatus), default=FlowStatus.DRAFT)
    is_public = Column(Boolean, default=False)  # For demo flows
    is_template = Column(Boolean, default=False)

    # Metadata
    tags = Column(JSON, default=list)
    category = Column(String, nullable=True)

    # Statistics
    execution_count = Column(Integer, default=0)
    last_executed_at = Column(DateTime, nullable=True)

    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    # Relationships
    from sqlalchemy.orm import relationship
    owner = relationship("User", back_populates="flows")
    organization = relationship("Organization", back_populates="flows")
    team = relationship("Team", back_populates="flows")
    executions = relationship("FlowExecution", back_populates="flow")


class FlowExecution(Base):
    """Flow execution history."""

    __tablename__ = "flow_executions"

    id = Column(Integer, primary_key=True, index=True)
    flow_id = Column(Integer, ForeignKey("flows.id"))

    # Status
    status = Column(SQLEnum(ExecutionStatus), default=ExecutionStatus.PENDING)

    # Execution details
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    duration_ms = Column(Integer, nullable=True)

    # Results and errors
    result = Column(JSON, nullable=True)
    error_message = Column(Text, nullable=True)
    node_results = Column(JSON, default=dict)  # Results from each node

    # Resource usage
    events_processed = Column(Integer, default=0)
    cpu_time_ms = Column(Integer, default=0)
    memory_mb = Column(Integer, default=0)

    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)

    # Relationships
    from sqlalchemy.orm import relationship
    flow = relationship("Flow", back_populates="executions")
