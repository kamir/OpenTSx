"""Initialize database with default data."""

import logging

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from app.models.user import User, PlanTier
from app.core.security import get_password_hash
from app.core.config import settings
from app.services.auth_service import add_organization

log = logging.getLogger(__name__)


async def init_db(db: AsyncSession) -> None:
    """
    Creates the first administrator (FIRST_SUPERUSER_EMAIL) and its organization when no administrator exists. An
    existing administrator stays as it is, also when FIRST_SUPERUSER_EMAIL names another address.

    Args:
        db: Database session
    """
    admins = (await db.execute(select(User.email).where(User.is_superuser.is_(True)).order_by(User.id))).scalars().all()
    if admins:
        if settings.FIRST_SUPERUSER_EMAIL not in admins:
            log.info("first administrator exists account=%s; FIRST_SUPERUSER_EMAIL=%s is not used",
                     admins[0], settings.FIRST_SUPERUSER_EMAIL)
        return
    taken = await db.execute(select(User.id).where(User.email == settings.FIRST_SUPERUSER_EMAIL))
    if taken.scalar_one_or_none() is not None:
        log.warning("no administrator exists and FIRST_SUPERUSER_EMAIL=%s belongs to an account that is not one; "
                    "no administrator created", settings.FIRST_SUPERUSER_EMAIL)
        return

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
    await add_organization(db, user, "Admin Organization", "admin-org", PlanTier.ENTERPRISE)
    await db.commit()
    log.info("first administrator created account=%s", settings.FIRST_SUPERUSER_EMAIL)
