"""Accounts against the test database (SQLite in memory with the tables of the backend): sign-up and its switch, the
first administrator at start, and the texts of a refused registration."""

import asyncio
import logging

from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.core.config import settings
from app.db.init_db import init_db
from app.db.session import get_db
from app.main import app
from app.models.user import Organization, User

NEW = {"email": "new@example.com", "password": "a-password-of-a-1", "full_name": "New"}


def client_on(database):
    app.dependency_overrides[get_db] = lambda: database
    return TestClient(app)


def count(database, model):
    return asyncio.run(database.execute(select(func.count()).select_from(model))).scalar_one()


def test_sign_up_creates_the_account_and_its_organization(database):
    try:
        answer = client_on(database).post("/api/v1/auth/register", json=NEW)
    finally:
        app.dependency_overrides.clear()
    assert answer.status_code == 201, answer.text
    assert answer.json()["email"] == "new@example.com"
    assert (count(database, User), count(database, Organization)) == (1, 1)


def test_closed_sign_up_creates_no_account(database, monkeypatch):
    monkeypatch.setattr(settings, "ENABLE_SIGNUP", False)
    try:
        answer = client_on(database).post("/api/v1/auth/register", json=NEW)
    finally:
        app.dependency_overrides.clear()
    assert answer.status_code == 403
    assert answer.json() == {"error": "sign-up is closed; ask an administrator of this installation for an account",
                             "type": "forbidden"}
    assert count(database, User) == 0


def test_an_address_that_is_registered_is_named(database):
    client = client_on(database)
    try:
        assert client.post("/api/v1/auth/register", json=NEW).status_code == 201
        again = client.post("/api/v1/auth/register", json={**NEW, "full_name": "Other"})
    finally:
        app.dependency_overrides.clear()
    assert again.status_code == 409
    assert again.json() == {"error": "email: already registered; sign in or use another address", "type": "bad_request"}
    assert count(database, User) == 1


def test_a_changed_first_administrator_address_starts_and_keeps_one_administrator(database, monkeypatch, caplog):
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "admin@example.com")
    asyncio.run(init_db(database))
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "ops@example.com")
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="app.db.init_db"):
        asyncio.run(init_db(database))
    admins = asyncio.run(database.execute(select(User.email).where(User.is_superuser.is_(True)))).scalars().all()
    assert admins == ["admin@example.com"]
    assert count(database, Organization) == 1
    assert [(r.levelname, r.getMessage()) for r in caplog.records if r.name == "app.db.init_db"] == [(
        "INFO",
        "first administrator exists account=admin@example.com; FIRST_SUPERUSER_EMAIL=ops@example.com is not used",
    )]


def test_the_first_start_creates_the_administrator_once(database, monkeypatch, caplog):
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "admin@localhost")
    with caplog.at_level(logging.INFO, logger="app.db.init_db"):
        asyncio.run(init_db(database))
        asyncio.run(init_db(database))
    admin = asyncio.run(database.execute(select(User).where(User.is_superuser.is_(True)))).scalars().one()
    assert admin.email == "admin@localhost"
    organization = asyncio.run(database.execute(select(Organization))).scalars().one()
    assert (organization.slug, organization.owner_id) == ("admin-org", admin.id)
    assert [r.getMessage() for r in caplog.records if r.name == "app.db.init_db"] == [
        "first administrator created account=admin@localhost"]


def test_an_address_that_belongs_to_an_account_is_not_made_administrator(database, monkeypatch, caplog):
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "new@example.com")
    try:
        assert client_on(database).post("/api/v1/auth/register", json=NEW).status_code == 201
    finally:
        app.dependency_overrides.clear()
    with caplog.at_level(logging.INFO, logger="app.db.init_db"):
        asyncio.run(init_db(database))
    assert count(database, User) == 1
    assert asyncio.run(database.execute(select(User.is_superuser))).scalar_one() is False
    assert [(r.levelname, r.getMessage()) for r in caplog.records if r.name == "app.db.init_db"] == [(
        "WARNING",
        "no administrator exists and FIRST_SUPERUSER_EMAIL=new@example.com belongs to an account that is not one; "
        "no administrator created",
    )]


def test_the_test_login_signs_in_as_the_administrator_that_exists(database, monkeypatch):
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "admin@example.com")
    asyncio.run(init_db(database))
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "ops@example.com")
    asyncio.run(init_db(database))
    monkeypatch.setattr(settings, "TEST_LOGIN", True)
    try:
        answer = client_on(database).post("/api/v1/auth/login", json={"email": "admin", "password": "admin"})
    finally:
        app.dependency_overrides.clear()
    assert answer.status_code == 200, answer.text
    assert answer.json()["user"]["email"] == "admin@example.com"


def test_the_test_login_never_signs_in_as_an_account_that_is_no_administrator(database, monkeypatch):
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "new@example.com")
    monkeypatch.setattr(settings, "TEST_LOGIN", True)
    client = client_on(database)
    try:
        assert client.post("/api/v1/auth/register", json=NEW).status_code == 201
        asyncio.run(init_db(database))
        answer = client.post("/api/v1/auth/login", json={"email": "admin", "password": "admin"})
    finally:
        app.dependency_overrides.clear()
    assert answer.status_code == 401
