import asyncio
import signal

from sqlalchemy import text

from app.db.session import AsyncSessionLocal, async_engine
from app.services.wayang_service import Journal, reconcile_once


LOCK_ID = 803205779688358437


async def run(stopped=None):
    if stopped is None:
        stopped = asyncio.Event()
        loop = asyncio.get_running_loop()
        for value in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(value, stopped.set)
    async with async_engine.connect() as guard:
        acquired = await guard.scalar(text("SELECT pg_try_advisory_lock(:key)"), {"key": LOCK_ID})
        await guard.commit()
        if not acquired:
            raise RuntimeError("Another bounded Wayang coordinator owns this database.")
        try:
            while not stopped.is_set():
                await guard.scalar(text("SELECT 1"))
                await guard.commit()
                async with AsyncSessionLocal() as session:
                    await reconcile_once(Journal(session))
                try:
                    await asyncio.wait_for(stopped.wait(), timeout=2.0)
                except TimeoutError:
                    pass
        finally:
            await guard.execute(text("SELECT pg_advisory_unlock(:key)"), {"key": LOCK_ID})
            await guard.commit()


if __name__ == "__main__":
    asyncio.run(run())
