"""Pydantic schemas for user-related requests and responses."""

from typing import Optional, List
from datetime import datetime
from pydantic import BaseModel, EmailStr, Field


# ==================== Base Schemas ====================

class UserBase(BaseModel):
    """Base user schema."""
    email: EmailStr
    full_name: Optional[str] = None
    is_active: bool = True
    is_superuser: bool = False


class UserCreate(BaseModel):
    """Schema for creating a user."""
    email: EmailStr
    password: str = Field(..., min_length=8)
    full_name: Optional[str] = None


class UserUpdate(BaseModel):
    """Schema for updating a user."""
    email: Optional[EmailStr] = None
    full_name: Optional[str] = None
    bio: Optional[str] = None
    avatar_url: Optional[str] = None


class UserInDB(UserBase):
    """User schema as stored in database."""
    id: int
    hashed_password: str
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class User(UserBase):
    """User schema for responses (without password)."""
    id: int
    bio: Optional[str] = None
    avatar_url: Optional[str] = None
    is_verified: bool = False
    created_at: datetime
    last_login_at: Optional[datetime] = None

    class Config:
        from_attributes = True


# ==================== Auth Schemas ====================

class Token(BaseModel):
    """Token response schema."""
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int = 1800  # 30 minutes


class TokenData(BaseModel):
    """Token payload data."""
    sub: Optional[int] = None  # user_id


class LoginRequest(BaseModel):
    """Login request schema."""
    email: EmailStr
    password: str


class LoginResponse(BaseModel):
    """Login response schema."""
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int = 1800
    user: User


class RegisterRequest(BaseModel):
    """Registration request schema."""
    email: EmailStr
    password: str = Field(..., min_length=8)
    full_name: str


class ChangePasswordRequest(BaseModel):
    """Change password request schema."""
    current_password: str
    new_password: str = Field(..., min_length=8)


# ==================== Organization Schemas ====================

class OrganizationBase(BaseModel):
    """Base organization schema."""
    name: str
    slug: str
    description: Optional[str] = None


class OrganizationCreate(OrganizationBase):
    """Schema for creating organization."""
    pass


class Organization(OrganizationBase):
    """Organization response schema."""
    id: int
    owner_id: int
    plan_tier: str
    max_flows: int
    max_executions_per_month: int
    max_team_members: int
    created_at: datetime

    class Config:
        from_attributes = True


# ==================== Team Schemas ====================

class TeamBase(BaseModel):
    """Base team schema."""
    name: str
    description: Optional[str] = None


class TeamCreate(TeamBase):
    """Schema for creating team."""
    pass


class Team(TeamBase):
    """Team response schema."""
    id: int
    organization_id: int
    created_at: datetime

    class Config:
        from_attributes = True


# ==================== Invitation Schemas ====================

class InvitationCreate(BaseModel):
    """Schema for creating invitation."""
    email: EmailStr
    role: str = "user"
    team_id: Optional[int] = None


class Invitation(BaseModel):
    """Invitation response schema."""
    id: int
    invitee_email: EmailStr
    organization_id: int
    team_id: Optional[int] = None
    role: str
    token: str
    is_accepted: bool
    is_expired: bool
    created_at: datetime
    expires_at: datetime

    class Config:
        from_attributes = True
