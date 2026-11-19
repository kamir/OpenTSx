"""The settings of the backend: the secrets have no defaults, no published example values and no short values; a value
the backend cannot use and a key it does not know stop the start. No .env file is read unless a test writes one."""

import re
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.core.config import Settings

APP = Path(__file__).resolve().parents[1] / "app"

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


@pytest.mark.parametrize("name, value", [
    ("DAG_RUN_ROLE", "users"),
    ("RETRIEVAL_URL", "opentsx-retrieval:8080"),
    ("RETRIEVAL_URL", "ftp://opentsx-retrieval:8080"),
    ("ALGORITHM", "none"),
    ("BACKEND_CORS_ORIGINS", '["*"]'),
    ("BACKEND_CORS_ORIGINS", '["http://localhost:3000","*"]'),
    ("LOG_LEVEL", "verbose"),
])
def test_a_value_the_backend_cannot_use_stops_the_start(monkeypatch, name, value):
    assert name in refusal(monkeypatch, name, value)


@pytest.mark.parametrize("name, value, text", [
    ("RETRIEVAL_URL", "opentsx-retrieval:8080",
     "RETRIEVAL_URL: not an http or https address; set the address of the retrieval server such as "
     "http://opentsx-retrieval:8080"),
    ("BACKEND_CORS_ORIGINS", '["*"]',
     'BACKEND_CORS_ORIGINS: "*" is not allowed, the backend accepts credentials from these origins; '
     "list each origin such as http://localhost:3000"),
    ("DAG_RUN_ROLE", "users", "Input should be 'superuser' or 'user'"),
])
def test_the_refusals_say_what_is_allowed(monkeypatch, name, value, text):
    assert text in refusal(monkeypatch, name, value)


def test_the_typed_values_load(monkeypatch):
    monkeypatch.setenv("DAG_RUN_ROLE", "user")
    monkeypatch.setenv("RETRIEVAL_URL", "http://opentsx-retrieval:8080")
    monkeypatch.setenv("ALGORITHM", "HS512")
    monkeypatch.setenv("LOG_LEVEL", "debug")
    loaded = Settings(_env_file=None)
    assert (loaded.DAG_RUN_ROLE, loaded.RETRIEVAL_URL, loaded.ALGORITHM, loaded.LOG_LEVEL) == (
        "user", "http://opentsx-retrieval:8080", "HS512", "DEBUG")


def test_an_empty_retrieval_url_is_unset(monkeypatch):
    monkeypatch.setenv("RETRIEVAL_URL", "")
    assert Settings(_env_file=None).RETRIEVAL_URL is None


@pytest.mark.parametrize("key, value", [
    ("DATABASE_URL", "postgresql://opentsx:stale-database-password@localhost/opentsx"),
    ("REDIS_PASSWORD", "stale-redis-password"),
    ("secret_key", "lower-case-signing-key-0123456789abcdef"),
])
def test_a_key_in_the_env_file_that_is_no_setting_stops_the_start(tmp_path, key, value):
    env_file = tmp_path / ".env"
    env_file.write_text(f"POSTGRES_SERVER=db\n{key}={value}\n")
    with pytest.raises(ValidationError) as caught:
        Settings(_env_file=env_file)
    assert (f"{key}: not a setting of the backend; remove it from .env, the settings are API_V1_STR, PROJECT_NAME, "
            "VERSION, DESCRIPTION, BACKEND_CORS_ORIGINS, SECRET_KEY, ALGORITHM, ACCESS_TOKEN_EXPIRE_MINUTES, ") in str(caught.value)
    assert ", TEST_LOGIN, ENABLE_SIGNUP, LOG_LEVEL" in str(caught.value)
    assert value not in str(caught.value)


def test_a_variable_of_the_environment_that_is_no_setting_is_not_read(monkeypatch):
    monkeypatch.setenv("KAFKA_BOOTSTRAP_SERVERS", "broker:9092")
    assert not hasattr(Settings(_env_file=None), "KAFKA_BOOTSTRAP_SERVERS")


def test_every_setting_has_a_reader():
    code = "\n".join(p.read_text(encoding="utf-8") for p in sorted(APP.rglob("*.py")))
    unread = [name for name in Settings.model_fields if not re.search(rf"\b(settings|self)\.{name}\b", code)]
    assert unread == []


def test_the_example_env_file_loads(tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_text((APP.parent / ".env.example").read_text(encoding="utf-8"))
    loaded = Settings(_env_file=env_file)
    assert loaded.FIRST_SUPERUSER_EMAIL == "admin@opentsx.com"
