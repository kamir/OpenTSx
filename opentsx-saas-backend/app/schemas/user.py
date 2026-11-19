"""Pydantic schemas for user-related requests and responses."""

from typing import Annotated, Optional
from datetime import datetime
from pydantic import AfterValidator, BaseModel, EmailStr, Field
from pydantic_core import PydanticCustomError

from app.core.config import PASSWORD_MAX_BYTES


def at_most_72_bytes(password: str) -> str:
    if len(password.encode("utf-8")) > PASSWORD_MAX_BYTES:
        raise PydanticCustomError("password_too_long", "at most 72 bytes")
    return password


NewPassword = Annotated[str, Field(min_length=8), AfterValidator(at_most_72_bytes)]


class UserBase(BaseModel):
    """Base user schema. The address as stored: the first administrator may have one EmailStr refuses, such as
    admin@localhost."""
    email: str
    full_name: Optional[str] = None
    is_active: bool = True
    is_superuser: bool = False


class UserUpdate(BaseModel):
    """Schema for updating a user."""
    email: Optional[EmailStr] = None
    full_name: Optional[str] = None
    bio: Optional[str] = None
    avatar_url: Optional[str] = None


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


class LoginRequest(BaseModel):
    """Login request schema. `email` is an address, or the name "admin" where test operation is switched on."""
    email: str = Field(min_length=1, max_length=254)
    password: str


class LoginResponse(BaseModel):
    """Login response schema. `expires_in` is ACCESS_TOKEN_EXPIRE_MINUTES in seconds."""
    access_token: str
    token_type: str = "bearer"
    expires_in: int
    user: User


class RegisterRequest(BaseModel):
    """Registration request schema."""
    email: EmailStr
    password: NewPassword
    full_name: str


class ChangePasswordRequest(BaseModel):
    """Change password request schema."""
    current_password: str
    new_password: NewPassword
