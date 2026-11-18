"""The secrets the backend needs: no defaults, no published example values, no short values. No .env file is read."""

import pytest
from pydantic import ValidationError

from app.core.config import Settings

REQUIRED = ("SECRET_KEY", "POSTGRES_PASSWORD", "FIRST_SUPERUSER_PASSWORD")


def refusal(monkeypatch, name, value=None):
    if value is None:
        monkeypatch.delenv(name)
    else:
        monkeypatch.setenv(name, value)
    with pytest.raises(ValidationError) as caught:
        Settings(_env_file=None)
    errors = caught.value.errors()
    assert [e["loc"] for e in errors] == [(name,)]
    return str(caught.value)


def test_the_test_values_load():
    loaded = Settings(_env_file=None)
    assert len(loaded.SECRET_KEY) >= 32


@pytest.mark.parametrize("name", REQUIRED)
def test_each_secret_is_required(monkeypatch, name):
    text = refusal(monkeypatch, name)
    assert name in text
    assert "Field required" in text


@pytest.mark.parametrize("value", [
    "your-secret-key-change-in-production-min-32-chars-long",
    "your-secret-key-change-this-in-production-use-random-32-chars",
    "change-this-to-a-secure-random-key-in-production",
    "your-secret-key-here-change-in-production",
    "your-secret-key-change-this-in-production",
    "your-secret-key-change-in-production",
])
def test_a_published_example_key_is_refused(monkeypatch, value):
    text = refusal(monkeypatch, "SECRET_KEY", value)
    assert "SECRET_KEY: published example value; generate a random key of at least 32 characters" in text
    assert value not in text


def test_a_short_key_is_refused(monkeypatch):
    text = refusal(monkeypatch, "SECRET_KEY", "k" * 31)
    assert "SECRET_KEY: shorter than 32 characters; set at least 32 random characters" in text
    assert "k" * 31 not in text


@pytest.mark.parametrize("name", ["POSTGRES_PASSWORD", "FIRST_SUPERUSER_PASSWORD"])
@pytest.mark.parametrize("value", [
    "change_this_password",
    "dev_password_123",
    "change_this_redis_password",
    "your_password",
    "SecurePassword123!",
    "SecurePass123!",
    "OldPassword123!",
    "NewSecurePassword456!",
])
def test_a_published_example_password_is_refused(monkeypatch, name, value):
    text = refusal(monkeypatch, name, value)
    assert f"{name}: published example value; set a random password of at least 12 characters" in text
    assert value not in text


@pytest.mark.parametrize("name", ["POSTGRES_PASSWORD", "FIRST_SUPERUSER_PASSWORD"])
def test_a_short_password_is_refused(monkeypatch, name):
    text = refusal(monkeypatch, name, "elevenchars")
    assert f"{name}: shorter than 12 characters; set at least 12 characters" in text
    assert "elevenchars" not in text


def test_an_administrator_password_bcrypt_would_cut_is_refused(monkeypatch):
    text = refusal(monkeypatch, "FIRST_SUPERUSER_PASSWORD", "ä" * 36 + "a")
    assert "FIRST_SUPERUSER_PASSWORD: longer than 72 bytes; set at most 72 bytes in UTF-8" in text


def test_the_database_password_has_no_upper_limit(monkeypatch):
    monkeypatch.setenv("POSTGRES_PASSWORD", "p" * 200)
    assert Settings(_env_file=None).POSTGRES_PASSWORD == "p" * 200
