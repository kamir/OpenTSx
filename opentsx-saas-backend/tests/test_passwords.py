"""Passwords and bcrypt, which reads at most 72 bytes: a longer password is refused, never cut.

No database: the session is a dictionary of users with the calls register and login make.
"""

from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from app.core.deps import get_current_user
from app.core.security import get_password_hash, verify_password
from app.db.session import get_db
from app.main import app
from app.models.user import User

LONG = "ä" * 36 + "a"
assert len(LONG.encode("utf-8")) == 73 and len(LONG) == 37
FIRST_72 = "ä" * 36
CREATED = datetime(2026, 1, 1)


class Found:
    def __init__(self, user):
        self.user = user

    def scalar_one_or_none(self):
        return self.user


class MemorySession:
    """Users by address; ids, defaults and timestamps are filled in on flush, as the database would."""

    def __init__(self):
        self.users = {}
        self.added = []

    async def execute(self, statement):
        address = next((v for k, v in statement.compile().params.items() if k.startswith("email")), None)
        return Found(self.users.get(address))

    def add(self, row):
        self.added.append(row)

    async def flush(self):
        for row in self.added:
            row.id = row.id or len(self.users) + len(self.added)
            if isinstance(row, User):
                row.is_superuser = bool(row.is_superuser)
                row.created_at = row.updated_at = CREATED
                self.users[row.email] = row
        self.added = []

    async def commit(self):
        await self.flush()

    async def refresh(self, row):
        pass


@pytest.fixture
def client():
    session = MemorySession()
    app.dependency_overrides[get_db] = lambda: session
    yield TestClient(app)
    app.dependency_overrides.clear()


def register(client, password):
    return client.post("/api/v1/auth/register",
                       json={"email": "long@example.com", "password": password, "full_name": "Long"})


def test_register_refuses_more_than_72_bytes(client):
    answer = register(client, LONG)
    assert answer.status_code == 422, answer.text
    assert "password" in answer.text
    assert "at most 72 bytes" in answer.text
    login = client.post("/api/v1/auth/login", json={"email": "long@example.com", "password": FIRST_72})
    assert login.status_code == 401


def test_register_takes_72_bytes_and_that_password_signs_in(client):
    assert register(client, FIRST_72).status_code == 201
    login = client.post("/api/v1/auth/login", json={"email": "long@example.com", "password": FIRST_72})
    assert login.status_code == 200, login.text


def test_change_password_refuses_more_than_72_bytes(client):
    app.dependency_overrides[get_current_user] = lambda: User(id=1, email="a@example.com", is_active=True,
                                                               hashed_password=get_password_hash(FIRST_72))
    answer = client.post("/api/v1/auth/change-password", json={"current_password": FIRST_72, "new_password": LONG})
    assert answer.status_code == 422, answer.text
    assert "new_password" in answer.text
    assert "at most 72 bytes" in answer.text


def test_hashing_refuses_more_than_72_bytes():
    with pytest.raises(ValueError, match="password: longer than 72 bytes; use at most 72 bytes in UTF-8"):
        get_password_hash(LONG)


def test_a_longer_password_does_not_match_its_first_72_bytes():
    assert verify_password(FIRST_72, get_password_hash(FIRST_72))
    assert not verify_password(FIRST_72 + "a", get_password_hash(FIRST_72))
