"""The required secrets for the test run, as values that exist only here. Set before any test imports the app."""

import os

import pytest

os.environ["SECRET_KEY"] = "pytest-only-signing-key-0123456789abcdef"
os.environ["POSTGRES_PASSWORD"] = "pytest-only-database-password"
os.environ["FIRST_SUPERUSER_PASSWORD"] = "pytest-only-administrator-password"


class SqliteSession:
    """A database for the tests: SQLite in memory behind the calls the backend makes on its AsyncSession. The tables,
    unique keys and foreign keys are the ones create_all makes at start."""

    def __init__(self):
        from sqlalchemy import create_engine
        from sqlalchemy.orm import Session
        from sqlalchemy.pool import StaticPool

        import app.models  # noqa: F401  every table
        from app.db.base_class import Base

        engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
        Base.metadata.create_all(engine)
        self.sync = Session(engine, expire_on_commit=False)

    def add(self, row):
        self.sync.add(row)

    async def execute(self, statement):
        return self.sync.execute(statement)

    async def flush(self):
        self.sync.flush()

    async def commit(self):
        self.sync.commit()

    async def rollback(self):
        self.sync.rollback()

    async def refresh(self, row):
        self.sync.refresh(row)

    async def delete(self, row):
        self.sync.delete(row)


@pytest.fixture
def database():
    session = SqliteSession()
    yield session
    session.sync.close()
