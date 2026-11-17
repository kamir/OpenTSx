import asyncio

import pytest

from app import wayang_worker


class Guard:
    def __init__(self, acquired=True, lost=False):
        self.acquired = acquired
        self.lost = lost
        self.statements = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return None

    async def scalar(self, statement, parameters=None):
        self.statements.append(str(statement))
        if str(statement) == "SELECT 1" and self.lost:
            raise ConnectionError("ownership connection lost")
        return self.acquired

    async def execute(self, statement, parameters=None):
        self.statements.append(str(statement))

    async def commit(self):
        return None


class Engine:
    def __init__(self, guard):
        self.guard = guard

    def connect(self):
        return self.guard


def test_another_coordinator_cannot_start(monkeypatch):
    guard = Guard(acquired=False)
    monkeypatch.setattr(wayang_worker, "async_engine", Engine(guard))
    with pytest.raises(RuntimeError, match="Another bounded Wayang coordinator"):
        asyncio.run(wayang_worker.run(asyncio.Event()))
    assert guard.statements == ["SELECT pg_try_advisory_lock(:key)"]


def test_stopping_releases_the_same_session_lock_without_dispatch(monkeypatch):
    guard = Guard()
    monkeypatch.setattr(wayang_worker, "async_engine", Engine(guard))
    stopped = asyncio.Event()
    stopped.set()
    asyncio.run(wayang_worker.run(stopped))
    assert guard.statements == ["SELECT pg_try_advisory_lock(:key)", "SELECT pg_advisory_unlock(:key)"]


def test_lost_database_ownership_is_checked_before_dispatch(monkeypatch):
    guard = Guard(lost=True)
    monkeypatch.setattr(wayang_worker, "async_engine", Engine(guard))
    with pytest.raises(ConnectionError, match="ownership connection lost"):
        asyncio.run(wayang_worker.run(asyncio.Event()))
    assert guard.statements == ["SELECT pg_try_advisory_lock(:key)", "SELECT 1", "SELECT pg_advisory_unlock(:key)"]
