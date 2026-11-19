"""Authentication service for user operations."""

from datetime import datetime, timedelta
import hmac
import logging
from typing import Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import insert, select
from fastapi import HTTPException, status

from app.models.user import User, Organization, PlanTier, UserRole, user_organization
from app.core.security import (
    verify_password,
    get_password_hash,
    create_access_token,
)
from app.schemas.user import RegisterRequest, LoginRequest
from app.core.config import settings

log = logging.getLogger(__name__)

# The limits an organization stores per plan; no route enforces them. The first administrator's plan has none.
NO_LIMIT = 999_999
PLAN_LIMITS = {
    PlanTier.FREE: {"max_flows": 5, "max_executions_per_month": 1000, "max_team_members": 3},
    PlanTier.ENTERPRISE: {"max_flows": NO_LIMIT, "max_executions_per_month": NO_LIMIT, "max_team_members": NO_LIMIT},
}


async def add_organization(db: AsyncSession, owner: User, name: str, slug: str, plan_tier: PlanTier) -> Organization:
    """An organization of the owner with the limits of its plan, and the owner as its admin member."""
    organization = Organization(name=name, slug=slug, owner_id=owner.id, plan_tier=plan_tier, **PLAN_LIMITS[plan_tier])
    db.add(organization)
    await db.flush()
    # user.organizations.append() would lazy-load the collection, which fails in the async session (MissingGreenlet)
    await db.execute(
        insert(user_organization).values(user_id=owner.id, organization_id=organization.id, role=UserRole.ADMIN)
    )
    return organization


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


async def create_user(db: AsyncSession, user_create: RegisterRequest) -> User:
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
            detail="email: already registered; sign in or use another address"
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

    # The slug is unique per organization; the user id keeps equal local parts of two addresses apart.
    org_slug = f'{user_create.email.split("@")[0].replace(".", "-").replace("_", "-")}-{user.id}'
    name = f"{user_create.full_name}'s Organization" if user_create.full_name else f"{org_slug}'s Organization"
    await add_organization(db, user, name, org_slug, PlanTier.FREE)

    await db.commit()
    await db.refresh(user)

    return user


TEST_LOGIN_NAME = "admin"
TEST_LOGIN_PASSWORD = "admin"


def admin_test_login(identifier: str, password: str) -> bool:
    """
    True when test operation is on and the caller signs in with admin/admin (the name or FIRST_SUPERUSER_EMAIL).
    The stored password is not involved and not changed.
    """
    if not settings.TEST_LOGIN:
        return False
    name = identifier.strip().lower()
    if name not in (TEST_LOGIN_NAME, settings.FIRST_SUPERUSER_EMAIL.lower()):
        return False
    return hmac.compare_digest(password.encode(), TEST_LOGIN_PASSWORD.encode())


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
    if admin_test_login(login_data.email, login_data.password):
        # the administrator init_db keeps, also when FIRST_SUPERUSER_EMAIL names another address
        found = await db.execute(select(User).where(User.is_superuser.is_(True)).order_by(User.id).limit(1))
        user = found.scalar_one_or_none()
        log.warning("test login used name=%r account=%s", login_data.email, user.email if user else "none")
    else:
        user = await authenticate_user(db, login_data.email.strip(), login_data.password)

    if not user:
        log.info("sign-in refused email=%r", login_data.email)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="email or password: not correct; check both and sign in again",
            headers={"WWW-Authenticate": "Bearer"},
        )

    minutes = settings.ACCESS_TOKEN_EXPIRE_MINUTES
    # python-jose rejects a non-string subject when it decodes the token
    access_token = create_access_token(data={"sub": str(user.id)}, expires_delta=timedelta(minutes=minutes))

    return {
        "access_token": access_token,
        "token_type": "bearer",
        "expires_in": minutes * 60,
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
            detail="current_password: not correct; enter the password you sign in with"
        )

    user.hashed_password = get_password_hash(new_password)
    await db.commit()
