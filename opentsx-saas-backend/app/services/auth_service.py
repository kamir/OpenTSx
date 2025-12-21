"""Authentication service for user operations."""

from datetime import datetime, timedelta
from typing import Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from fastapi import HTTPException, status

from app.models.user import User, Organization, PlanTier
from app.core.security import (
    verify_password,
    get_password_hash,
    create_access_token,
    create_refresh_token,
)
from app.schemas.user import UserCreate, LoginRequest


async def authenticate_user(db: AsyncSession, email: str, password: str) -> Optional[User]:
    """
    Authenticate user with email and password.

    Args:
        db: Database session
        email: User email
        password: Plain text password

    Returns:
        User if authentication successful, None otherwise
    """
    result = await db.execute(select(User).where(User.email == email))
    user = result.scalar_one_or_none()

    if not user:
        return None

    if not verify_password(password, user.hashed_password):
        return None

    # Update last login
    user.last_login_at = datetime.utcnow()
    await db.commit()

    return user


async def create_user(db: AsyncSession, user_create: UserCreate) -> User:
    """
    Create new user and default organization.

    Args:
        db: Database session
        user_create: User creation data

    Returns:
        Created user

    Raises:
        HTTPException: If email already exists
    """
    # Check if user exists
    result = await db.execute(select(User).where(User.email == user_create.email))
    existing_user = result.scalar_one_or_none()

    if existing_user:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Email already registered"
        )

    # Create user
    user = User(
        email=user_create.email,
        hashed_password=get_password_hash(user_create.password),
        full_name=user_create.full_name or "",
        is_active=True,
        is_verified=False,
    )

    db.add(user)
    await db.flush()  # Flush to get user.id

    # Create default organization
    org_slug = user_create.email.split("@")[0].replace(".", "-").replace("_", "-")
    organization = Organization(
        name=f"{user_create.full_name}'s Organization" if user_create.full_name else f"{org_slug}'s Organization",
        slug=org_slug,
        owner_id=user.id,
        plan_tier=PlanTier.FREE,
        max_flows=5,
        max_executions_per_month=1000,
        max_team_members=3,
    )

    db.add(organization)

    # Add user to organization members
    user.organizations.append(organization)

    await db.commit()
    await db.refresh(user)

    return user


async def login(db: AsyncSession, login_data: LoginRequest) -> dict:
    """
    Login user and return tokens.

    Args:
        db: Database session
        login_data: Login credentials

    Returns:
        Dictionary with tokens and user info

    Raises:
        HTTPException: If credentials are invalid
    """
    user = await authenticate_user(db, login_data.email, login_data.password)

    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    # Create tokens
    access_token = create_access_token(data={"sub": user.id})
    refresh_token = create_refresh_token(data={"sub": user.id})

    return {
        "access_token": access_token,
        "refresh_token": refresh_token,
        "token_type": "bearer",
        "expires_in": 1800,  # 30 minutes
        "user": user,
    }


async def change_password(
    db: AsyncSession,
    user: User,
    current_password: str,
    new_password: str
) -> None:
    """
    Change user password.

    Args:
        db: Database session
        user: Current user
        current_password: Current password
        new_password: New password

    Raises:
        HTTPException: If current password is incorrect
    """
    if not verify_password(current_password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Incorrect password"
        )

    user.hashed_password = get_password_hash(new_password)
    await db.commit()
