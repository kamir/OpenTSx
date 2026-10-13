"""Test operation of the login: admin/admin as the first administrator, only where it is switched on."""

import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app
from app.services.auth_service import admin_test_login


@pytest.fixture
def admin(monkeypatch):
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "admin@opentsx.com")
    return "admin@opentsx.com"


def test_off_by_default(admin):
    assert settings.TEST_LOGIN is False
    assert admin_test_login("admin", "admin") is None
    assert TestClient(app).get("/api/v1/auth/mode").json() == {"testLogin": False}


def test_admin_admin_is_the_first_administrator_when_switched_on(admin, monkeypatch):
    monkeypatch.setattr(settings, "TEST_LOGIN", True)
    assert admin_test_login("admin", "admin") == admin
    assert admin_test_login(" Admin ", "admin") == admin
    assert admin_test_login("admin@opentsx.com", "admin") == admin
    assert TestClient(app).get("/api/v1/auth/mode").json() == {"testLogin": True}


def test_nothing_else_gets_in_that_way(admin, monkeypatch):
    monkeypatch.setattr(settings, "TEST_LOGIN", True)
    assert admin_test_login("admin", "Admin") is None
    assert admin_test_login("admin", "") is None
    assert admin_test_login("root", "admin") is None
    assert admin_test_login("someone@example.com", "admin") is None
