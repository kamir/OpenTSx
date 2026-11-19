"""
Database connection utilities with retry logic.
"""

import asyncio
import logging
from typing import Optional

from sqlalchemy import text
from sqlalchemy.exc import OperationalError
from sqlalchemy.ext.asyncio import AsyncEngine

logger = logging.getLogger(__name__)


async def wait_for_db(engine: AsyncEngine, max_retries: int = 30, delay: float = 1.0,
                      stop: Optional[asyncio.Event] = None) -> bool:
    """
    Wait for database to be ready with exponential backoff.

    Args:
        engine: SQLAlchemy async engine
        max_retries: Maximum number of connection attempts
        delay: Initial delay between retries in seconds
        stop: Set when the process is asked to stop; the pause after a failed attempt ends then

    Returns:
        True if connection successful, raises exception otherwise

    Raises:
        RuntimeError: ``stop`` was set while the database could not be reached
    """
    stop = stop or asyncio.Event()
    where = f"{engine.url.host}:{engine.url.port}/{engine.url.database}"
    logger.info("waiting for the database db=%s", where)
    for attempt in range(max_retries):
        try:
            async with engine.begin() as conn:
                await conn.execute(text("SELECT 1"))
            logger.info("Database connection established db=%s", where)
            return True

        except (OperationalError, ConnectionRefusedError, OSError) as e:
            if attempt < max_retries - 1:
                wait_time = min(delay * (2 ** attempt), 10)  # Cap at 10 seconds
                logger.warning("database not ready db=%s attempt=%s/%s retry_in=%.1fs cause=%s",
                               where, attempt + 1, max_retries, wait_time, str(e)[:100])
                try:
                    await asyncio.wait_for(stop.wait(), wait_time)
                except asyncio.TimeoutError:
                    continue
                logger.warning("stop requested while waiting for the database db=%s attempts=%s", where, attempt + 1)
                raise RuntimeError(f"database {where}: not reachable when the stop came; start the backend again "
                                   "once the database answers") from e
            else:
                logger.error("database not reachable db=%s attempts=%s", where, max_retries)
                raise

    return False
