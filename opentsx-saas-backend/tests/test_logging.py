"""Logging of the backend: one text line per event through the loggers of the modules, the level from LOG_LEVEL, the
settings in one line at start with the secrets as set or unset, and the sign-ins that need a trace. No database."""

import ast
import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.engine import make_url

from app import main
from app.core.config import Settings, settings
from app.core.security import get_password_hash
from app.db.session import get_db
from app.db.utils import wait_for_db
from app.main import app
from app.models.user import User

APP = Path(__file__).resolve().parents[1] / "app"
PASSWORD = "the-password-of-a-1"


@pytest.fixture
def root_level():
    root = logging.getLogger()
    level = root.level
    yield root
    root.setLevel(level)


@pytest.fixture(autouse=True)
def no_overrides_left():
    yield
    app.dependency_overrides.clear()


def test_a_missing_logging_file_is_named(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "LOG_CONFIG", tmp_path / "logging.conf")
    with pytest.raises(FileNotFoundError, match="logging.conf"):
        main.configure_logging("INFO")


def test_log_level_sets_the_level_of_the_root_logger(root_level):
    main.configure_logging(Settings(_env_file=None, LOG_LEVEL="debug").LOG_LEVEL)
    assert root_level.level == logging.DEBUG
    main.configure_logging(Settings(_env_file=None).LOG_LEVEL)
    assert root_level.level == logging.INFO


def test_uvicorn_and_sqlalchemy_have_their_levels_in_the_logging_file(root_level):
    main.configure_logging("DEBUG")
    assert logging.getLogger("sqlalchemy").level == logging.WARNING
    assert logging.getLogger("uvicorn").level == logging.INFO
    assert logging.getLogger("uvicorn.access").handlers == []
    assert logging.getLogger("passlib.handlers.bcrypt").level == logging.ERROR


class ReadyEngine:
    url = make_url("postgresql+asyncpg://opentsx:database-password-in-the-url@postgres:5432/opentsx")

    @asynccontextmanager
    async def begin(self):
        yield self

    async def execute(self, statement):
        return None


def test_the_database_wait_names_host_port_and_database_at_info(caplog):
    asyncio.run(wait_for_db(ReadyEngine()))
    lines = [(r.name, r.levelname, r.getMessage()) for r in caplog.records]
    assert lines == [
        ("app.db.utils", "INFO", "waiting for the database db=postgres:5432/opentsx"),
        ("app.db.utils", "INFO", "Database connection established db=postgres:5432/opentsx"),
    ]
    assert "database-password-in-the-url" not in caplog.text


def test_no_print_in_the_backend():
    calls = [
        f"{source.relative_to(APP)}:{node.lineno}"
        for source in sorted(APP.rglob("*.py"))
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8")))
        if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "print"
    ]
    assert calls == []


def test_every_module_logs_through_the_logger_of_its_name():
    named = [
        f"{source.relative_to(APP)}:{node.lineno}"
        for source in sorted(APP.rglob("*.py"))
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8")))
        if isinstance(node, ast.Call) and getattr(node.func, "attr", None) in ("getLogger", "getChild") and node.args
        and getattr(node.args[0], "id", None) != "__name__"
    ]
    assert named == []


def test_the_start_line_lists_the_settings_and_no_secret(caplog, monkeypatch):
    monkeypatch.setattr(settings, "DAG_RUN_TOKEN", "the-run-token-of-the-test")
    monkeypatch.setattr(settings, "RETRIEVAL_URL", "http://opentsx-retrieval:8080")
    main.log_start()
    starts = [r for r in caplog.records if r.name == "app.main"]
    assert [(r.levelname, r.getMessage()) for r in starts] == [(
        "INFO",
        "starting backend db=localhost:5432/opentsx retrieval_url=http://opentsx-retrieval:8080 "
        "dag_run_role=superuser dag_run_token=set signup=true test_login=false "
        "cors_origins=http://localhost:3000,http://localhost:8000 log_level=INFO",
    )]
    for secret in ("the-run-token-of-the-test", settings.SECRET_KEY, settings.POSTGRES_PASSWORD,
                   settings.FIRST_SUPERUSER_PASSWORD):
        assert secret not in caplog.text


def test_the_start_line_says_when_the_run_token_is_unset(caplog, monkeypatch):
    monkeypatch.setattr(settings, "DAG_RUN_TOKEN", None)
    main.log_start()
    assert "dag_run_token=unset" in caplog.text


def test_test_operation_is_logged_at_start(caplog, monkeypatch):
    monkeypatch.setattr(settings, "TEST_LOGIN", True)
    main.log_start()
    assert ("WARNING", "TEST_LOGIN=true: admin/admin signs in as administrator; do not run this outside a lab network") in [
        (r.levelname, r.getMessage()) for r in caplog.records if r.name == "app.main"]


class OneUser:
    def __init__(self, user):
        self.user = user

    async def execute(self, statement):
        return self

    def scalar_one_or_none(self):
        return self.user

    async def commit(self):
        pass


def sign_in(user, email, password):
    app.dependency_overrides[get_db] = lambda: OneUser(user)
    return TestClient(app).post("/api/v1/auth/login", json={"email": email, "password": password})


def test_a_refused_sign_in_is_logged_without_the_password(caplog):
    user = User(id=1, email="a@example.com", hashed_password=get_password_hash(PASSWORD), is_active=True)
    with caplog.at_level(logging.INFO):
        assert sign_in(user, "a@example.com", "not-the-password-1").status_code == 401
    refused = [(r.name, r.levelname, r.getMessage()) for r in caplog.records if r.name.startswith("app.")]
    assert refused == [("app.services.auth_service", "INFO", "sign-in refused email='a@example.com'")]
    assert "not-the-password-1" not in caplog.text


def test_each_use_of_the_test_login_is_logged(caplog, monkeypatch):
    monkeypatch.setattr(settings, "TEST_LOGIN", True)
    monkeypatch.setattr(settings, "FIRST_SUPERUSER_EMAIL", "admin@example.com")
    admin = User(id=1, email="admin@example.com", full_name="Admin", is_active=True, is_superuser=True,
                 is_verified=True, created_at=datetime(2026, 1, 1))
    assert sign_in(admin, "admin", "admin").status_code == 200
    used = [(r.levelname, r.getMessage()) for r in caplog.records if r.name == "app.services.auth_service"]
    assert used == [("WARNING", "test login used name='admin' account=admin@example.com")]
