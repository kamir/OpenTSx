"""
Database connection utilities with retry logic.
"""

import asyncio
import logging
from sqlalchemy import text
from sqlalchemy.exc import OperationalError
from sqlalchemy.ext.asyncio import AsyncEngine

logger = logging.getLogger(__name__)


async def wait_for_db(engine: AsyncEngine, max_retries: int = 30, delay: float = 1.0) -> bool:
    """
    Wait for database to be ready with exponential backoff.

    Args:
        engine: SQLAlchemy async engine
        max_retries: Maximum number of connection attempts
        delay: Initial delay between retries in seconds

    Returns:
        True if connection successful, raises exception otherwise
    """
    for attempt in range(max_retries):
        try:
            async with engine.begin() as conn:
                # Simple query to test connection
                await conn.execute(text("SELECT 1"))
            logger.info("✅ Database connection established")
            return True

        except (OperationalError, ConnectionRefusedError, OSError) as e:
            if attempt < max_retries - 1:
                wait_time = min(delay * (2 ** attempt), 10)  # Cap at 10 seconds
                logger.warning(
                    f"Database not ready (attempt {attempt + 1}/{max_retries}). "
                    f"Retrying in {wait_time:.1f}s... Error: {str(e)[:100]}"
                )
                await asyncio.sleep(wait_time)
            else:
                logger.error(f"Failed to connect to database after {max_retries} attempts")
                raise

    return False
