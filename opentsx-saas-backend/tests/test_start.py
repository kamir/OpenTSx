"""The start of the backend while the database is down: a stop signal ends the wait for the database at once, and the
handler that was there before (uvicorn's) still gets the signal. No database, no sleep."""

import asyncio
import signal

import pytest
from sqlalchemy.engine import make_url

from app import main
from app.db.utils import wait_for_db

STOPPED = ("database postgres:5432/opentsx: not reachable when the stop came; start the backend again once the database "
           "answers")


class DownEngine:
    url = make_url("postgresql+asyncpg://opentsx@postgres:5432/opentsx")

    def __init__(self, during_first_attempt):
        self.attempts = 0
        self.during_first_attempt = during_first_attempt

    def begin(self):
        self.attempts += 1
        if self.attempts > 1:
            raise AssertionError("a second attempt after the stop")
        self.during_first_attempt()
        raise ConnectionRefusedError("connection refused")


def test_a_stop_ends_the_wait_for_the_database(caplog):
    async def wait():
        stop = asyncio.Event()
        engine = DownEngine(stop.set)
        with pytest.raises(RuntimeError) as e:
            await wait_for_db(engine, delay=3600, stop=stop)
        return engine, e.value

    engine, error = asyncio.run(wait())
    assert engine.attempts == 1
    assert str(error) == STOPPED
    assert isinstance(error.__cause__, ConnectionRefusedError)
    assert ("app.db.utils", "WARNING", "stop requested while waiting for the database db=postgres:5432/opentsx "
            "attempts=1") in [(r.name, r.levelname, r.getMessage()) for r in caplog.records]


def test_a_stop_signal_ends_the_start_and_reaches_the_handler_before(monkeypatch):
    seen = []

    def server_handler(sig, frame):
        seen.append(sig)

    before = signal.signal(signal.SIGTERM, server_handler)
    try:
        engine = DownEngine(lambda: signal.raise_signal(signal.SIGTERM))
        monkeypatch.setattr(main, "async_engine", engine)

        async def start():
            async with main.lifespan(main.app):
                pass

        with pytest.raises(RuntimeError) as e:
            asyncio.run(start())
        assert signal.getsignal(signal.SIGTERM) is server_handler
    finally:
        signal.signal(signal.SIGTERM, before)
    assert str(e.value) == STOPPED
    assert engine.attempts == 1
    assert seen == [signal.SIGTERM]
