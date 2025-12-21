"""User database models."""

from datetime import datetime
from typing import Optional
from sqlalchemy import Boolean, Column, DateTime, Integer, String, ForeignKey, Table, Enum as SQLEnum
from sqlalchemy.orm import relationship
import enum

from app.db.base_class import Base


class UserRole(str, enum.Enum):
    """User roles."""
    ADMIN = "admin"
    USER = "user"
    VIEWER = "viewer"


class PlanTier(str, enum.Enum):
    """Subscription plan tiers."""
    FREE = "free"
    STARTER = "starter"
    PROFESSIONAL = "professional"
    ENTERPRISE = "enterprise"


# Association table for user-organization membership
user_organization = Table(
    "user_organization",
    Base.metadata,
    Column("user_id", Integer, ForeignKey("users.id", ondelete="CASCADE")),
    Column("organization_id", Integer, ForeignKey("organizations.id", ondelete="CASCADE")),
    Column("role", SQLEnum(UserRole), default=UserRole.USER),
    Column("joined_at", DateTime, default=datetime.utcnow),
)


# Association table for user-team membership
user_team = Table(
    "user_team",
    Base.metadata,
    Column("user_id", Integer, ForeignKey("users.id", ondelete="CASCADE")),
    Column("team_id", Integer, ForeignKey("teams.id", ondelete="CASCADE")),
    Column("role", SQLEnum(UserRole), default=UserRole.USER),
    Column("joined_at", DateTime, default=datetime.utcnow),
)


class User(Base):
    """User model."""

    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    email = Column(String, unique=True, index=True, nullable=False)
    hashed_password = Column(String, nullable=False)
    full_name = Column(String)
    is_active = Column(Boolean, default=True)
    is_superuser = Column(Boolean, default=False)
    is_verified = Column(Boolean, default=False)

    # Profile
    avatar_url = Column(String, nullable=True)
    bio = Column(String, nullable=True)

    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    last_login_at = Column(DateTime, nullable=True)

    # Relationships
    organizations = relationship(
        "Organization",
        secondary=user_organization,
        back_populates="members"
    )
    teams = relationship(
        "Team",
        secondary=user_team,
        back_populates="members"
    )
    owned_organizations = relationship("Organization", back_populates="owner")
    flows = relationship("Flow", back_populates="owner")
    invitations_sent = relationship(
        "Invitation",
        foreign_keys="Invitation.inviter_id",
        back_populates="inviter"
    )
    # Note: invitations_received not included as invitations are sent to emails,
    # not user IDs. Query by email if needed: Invitation.invitee_email == user.email


class Organization(Base):
    """Organization model."""

    __tablename__ = "organizations"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, index=True, nullable=False)
    slug = Column(String, unique=True, index=True, nullable=False)
    description = Column(String, nullable=True)
    logo_url = Column(String, nullable=True)

    # Subscription
    plan_tier = Column(SQLEnum(PlanTier), default=PlanTier.FREE)
    stripe_customer_id = Column(String, nullable=True)
    stripe_subscription_id = Column(String, nullable=True)

    # Limits based on plan
    max_flows = Column(Integer, default=5)  # FREE: 5, STARTER: 25, PRO: 100, ENT: unlimited
    max_executions_per_month = Column(Integer, default=1000)
    max_team_members = Column(Integer, default=3)

    # Owner
    owner_id = Column(Integer, ForeignKey("users.id"))

    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    # Relationships
    owner = relationship("User", back_populates="owned_organizations")
    members = relationship(
        "User",
        secondary=user_organization,
        back_populates="organizations"
    )
    teams = relationship("Team", back_populates="organization")
    flows = relationship("Flow", back_populates="organization")


class Team(Base):
    """Team model (sub-groups within organizations)."""

    __tablename__ = "teams"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, index=True, nullable=False)
    description = Column(String, nullable=True)

    # Parent organization
    organization_id = Column(Integer, ForeignKey("organizations.id"))

    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    # Relationships
    organization = relationship("Organization", back_populates="teams")
    members = relationship(
        "User",
        secondary=user_team,
        back_populates="teams"
    )
    flows = relationship("Flow", back_populates="team")


class Invitation(Base):
    """Invitation model."""

    __tablename__ = "invitations"

    id = Column(Integer, primary_key=True, index=True)
    invitee_email = Column(String, index=True, nullable=False)
    organization_id = Column(Integer, ForeignKey("organizations.id"))
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=True)
    role = Column(SQLEnum(UserRole), default=UserRole.USER)

    # Invitation token
    token = Column(String, unique=True, index=True, nullable=False)

    # Status
    is_accepted = Column(Boolean, default=False)
    is_expired = Column(Boolean, default=False)

    # Inviter
    inviter_id = Column(Integer, ForeignKey("users.id"))

    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    expires_at = Column(DateTime, nullable=False)
    accepted_at = Column(DateTime, nullable=True)

    # Relationships
    inviter = relationship(
        "User",
        foreign_keys=[inviter_id],
        back_populates="invitations_sent"
    )
    # Note: invitee relationship removed because invitations are sent to emails,
    # not to existing users. Use invitee_email to look up users if needed.
