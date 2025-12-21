"""Initialize database with default data."""

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, insert

from app.models.user import User, Organization, PlanTier, UserRole, user_organization
from app.core.security import get_password_hash
from app.core.config import settings


async def init_db(db: AsyncSession) -> None:
    """
    Initialize database with first superuser.

    Args:
        db: Database session
    """
    # Check if superuser exists
    result = await db.execute(
        select(User).where(User.email == settings.FIRST_SUPERUSER_EMAIL)
    )
    user = result.scalar_one_or_none()

    if not user:
        # Create superuser
        user = User(
            email=settings.FIRST_SUPERUSER_EMAIL,
            hashed_password=get_password_hash(settings.FIRST_SUPERUSER_PASSWORD),
            full_name="Admin User",
            is_active=True,
            is_superuser=True,
            is_verified=True,
        )
        db.add(user)
        await db.flush()

        # Create admin organization
        organization = Organization(
            name="Admin Organization",
            slug="admin-org",
            owner_id=user.id,
            plan_tier=PlanTier.ENTERPRISE,
            max_flows=999999,
            max_executions_per_month=999999,
            max_team_members=999999,
        )
        db.add(organization)
        await db.flush()  # Flush to get organization.id

        # Add user as admin member of the organization
        # Can't use user.organizations.append() in async context (greenlet error)
        # Must manually insert into association table
        await db.execute(
            insert(user_organization).values(
                user_id=user.id,
                organization_id=organization.id,
                role=UserRole.ADMIN
            )
        )

        await db.commit()
        print(f"Created superuser: {settings.FIRST_SUPERUSER_EMAIL}")
