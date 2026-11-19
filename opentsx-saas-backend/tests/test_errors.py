"""Error answers of the API: one body {"error": text, "type": t}, without the input of the request or internals.

No database, no network: the session is a function of the test.
"""

import ast
import logging
from datetime import datetime
from pathlib import Path

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from jose import jwt

from app import main
from app.core.config import settings
from app.core.deps import get_current_user
from app.core.security import create_access_token, get_password_hash
from app.db.session import get_db
from app.main import app
from app.models.flow import Flow
from app.models.user import User

ADMIN = User(id=1, email="admin@example.com", is_active=True, is_superuser=True)
SECRET = "a-secret-password-1"
APP = Path(__file__).resolve().parent.parent / "app"


class Found:
    def __init__(self, row):
        self.row = row

    def scalar_one_or_none(self):
        return self.row

    def scalars(self):
        return self

    def first(self):
        return self.row


class OneRowSession:
    """Answers every query with one row, or with none."""

    def __init__(self, row):
        self.row = row

    async def execute(self, statement):
        return Found(self.row)

    async def commit(self):
        pass


def client_with(user=None, row=None):
    app.dependency_overrides.clear()
    app.dependency_overrides[get_db] = lambda: OneRowSession(row)
    if user is not None:
        app.dependency_overrides[get_current_user] = lambda: user
    return TestClient(app)


@pytest.fixture(autouse=True)
def no_overrides_left():
    yield
    app.dependency_overrides.clear()


def test_a_refused_register_names_the_field_and_does_not_echo_the_body():
    answer = client_with().post("/api/v1/auth/register", json={"email": "a@example.com", "password": SECRET})
    assert answer.status_code == 422
    assert answer.json() == {"error": "full_name: Field required", "type": "bad_request"}
    assert SECRET not in answer.text
    assert "input" not in answer.text


def test_a_body_that_is_not_json_is_a_bad_request():
    answer = client_with().post("/api/v1/auth/login", content=b'{"email":', headers={"Content-Type": "application/json"})
    assert answer.status_code == 422
    assert answer.json()["type"] == "bad_request"
    assert answer.json()["error"].startswith("body: not valid JSON")


def test_an_unhandled_fault_answers_internal_error_without_its_text(caplog):
    def broken():
        raise RuntimeError("the database said hunter2")

    client = client_with()
    app.dependency_overrides[get_current_user] = broken
    with caplog.at_level(logging.DEBUG, logger="app.main"):
        answer = client.get("/api/v1/auth/me")
    assert answer.status_code == 500
    assert answer.json() == {"error": "internal error", "type": "internal"}
    errors = [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert [(r.name, r.getMessage()) for r in errors] == [
        ("app.main",
         "request failed method=GET path=/api/v1/auth/me status=500 cause=RuntimeError: the database said hunter2")
    ]
    assert errors[0].exc_info is None
    traces = [r for r in caplog.records if r.levelno == logging.DEBUG and r.name == "app.main"]
    assert [type(r.exc_info[1]) for r in traces] == [RuntimeError]


def test_an_unknown_route_and_a_wrong_method_answer_the_error_body():
    client = client_with()
    missing = client.get("/api/v1/nothing")
    assert missing.status_code == 404
    assert missing.json() == {"error": "GET /api/v1/nothing: no such route; see /docs for the routes", "type": "not_found"}
    wrong = client.delete("/api/v1/auth/login")
    assert wrong.status_code == 405
    assert wrong.headers["allow"] == "POST"
    assert wrong.json() == {"error": "DELETE /api/v1/auth/login: method not allowed; expected POST", "type": "method_not_allowed"}


def test_a_missing_token_is_unauthorized():
    answer = client_with().get("/api/v1/auth/me")
    assert answer.status_code == 401
    assert answer.headers["www-authenticate"] == "Bearer"
    assert answer.json() == {"error": "Authorization: no bearer token; sign in and send Authorization: Bearer <access token>",
                             "type": "unauthorized"}


@pytest.mark.parametrize("token", [
    "not-a-token",
    jwt.encode({"sub": "1", "type": "refresh", "exp": datetime(2100, 1, 1)}, settings.SECRET_KEY, algorithm=settings.ALGORITHM),
    create_access_token(data={"sub": "admin@example.com"}),
], ids=["not a JWT", "refresh token of an earlier version", "subject not a user id"])
def test_a_token_that_is_not_an_access_token_is_unauthorized(token):
    answer = client_with(row=ADMIN).get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert answer.status_code == 401
    assert answer.headers["www-authenticate"] == "Bearer"
    assert answer.json() == {"error": "token: not valid or expired; sign in again", "type": "unauthorized"}
    assert token not in answer.text


def test_a_deactivated_account_is_forbidden():
    inactive = User(id=1, email="a@example.com", is_active=False, is_superuser=False)
    token = create_access_token(data={"sub": "1"})
    answer = client_with(row=inactive).get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert answer.status_code == 403
    assert answer.json() == {"error": "this account: deactivated; ask an administrator to activate it", "type": "forbidden"}


def test_an_internal_error_keeps_the_cors_headers():
    def broken():
        raise RuntimeError("x")

    client = client_with()
    app.dependency_overrides[get_current_user] = broken
    origin = settings.BACKEND_CORS_ORIGINS[0]
    answer = client.get("/api/v1/auth/me", headers={"Origin": origin})
    assert answer.status_code == 500
    assert answer.headers["access-control-allow-origin"] == origin


def test_a_token_of_a_deleted_user_is_unauthorized():
    token = create_access_token(data={"sub": "42"})
    answer = client_with(row=None).get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert answer.status_code == 401
    assert answer.headers["www-authenticate"] == "Bearer"
    assert answer.json() == {"error": "token: the account no longer exists; sign in with an existing account",
                             "type": "unauthorized"}


@pytest.mark.parametrize("method, path", [
    ("GET", "/api/v1/organizations"),
    ("POST", "/api/v1/organizations"),
    ("GET", "/api/v1/organizations/1"),
    ("GET", "/api/v1/organizations/1/teams"),
    ("POST", "/api/v1/organizations/1/invitations"),
    ("POST", "/api/v1/invitations/inv_abc123xyz/accept"),
    ("GET", "/api/v1/users/me"),
    ("PATCH", "/api/v1/users/me"),
    ("POST", "/api/v1/billing/create-checkout-session"),
    ("POST", "/api/v1/billing/webhook"),
    ("GET", "/api/v1/node-types"),
])
def test_the_stub_routes_are_gone(method, path):
    answer = client_with().request(method, path)
    assert answer.status_code == 404
    assert answer.json()["type"] == "not_found"
    assert path not in app.openapi()["paths"]


def test_demo_flows_are_no_route_of_their_own():
    answer = client_with(user=ADMIN).get("/api/v1/flows/demo")
    assert answer.status_code == 422
    assert answer.json()["error"].startswith("flow_id: ")
    assert "Getting Started" not in answer.text


def test_the_real_login_answers_without_the_stub_in_front():
    user = User(id=1, email="a@example.com", full_name="A", hashed_password=get_password_hash(SECRET),
                is_active=True, is_superuser=False, is_verified=False, created_at=datetime(2026, 1, 1))
    client = client_with(row=user)
    signed_in = client.post("/api/v1/auth/login", json={"email": "a@example.com", "password": SECRET})
    assert signed_in.status_code == 200
    claims = jwt.decode(signed_in.json()["access_token"], settings.SECRET_KEY, algorithms=[settings.ALGORITHM])
    assert claims["sub"] == "1"
    refused = client.post("/api/v1/auth/login", json={"email": "a@example.com", "password": "not-the-password"})
    assert refused.status_code == 401
    assert refused.json() == {"error": "email or password: not correct; check both and sign in again", "type": "unauthorized"}


def test_the_login_answers_one_access_token_that_expires_as_set(monkeypatch):
    monkeypatch.setattr(settings, "ACCESS_TOKEN_EXPIRE_MINUTES", 5)
    user = User(id=1, email="a@example.com", full_name="A", hashed_password=get_password_hash(SECRET),
                is_active=True, is_superuser=False, is_verified=False, created_at=datetime(2026, 1, 1))
    answer = client_with(row=user).post("/api/v1/auth/login", json={"email": "a@example.com", "password": SECRET})
    assert answer.status_code == 200
    assert "refresh_token" not in answer.json()
    assert answer.json()["expires_in"] == 300
    assert answer.json()["token_type"] == "bearer"


def test_an_administrator_at_localhost_signs_in_and_reads_the_account():
    admin = User(id=1, email="admin@localhost", full_name="Admin", hashed_password=get_password_hash(SECRET),
                 is_active=True, is_superuser=True, is_verified=True, created_at=datetime(2026, 1, 1))
    client = client_with(row=admin)
    signed_in = client.post("/api/v1/auth/login", json={"email": "admin@localhost", "password": SECRET})
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["email"] == "admin@localhost"
    me = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {signed_in.json()['access_token']}"})
    assert me.status_code == 200, me.text
    assert me.json()["email"] == "admin@localhost"


def test_a_wrong_current_password_is_named():
    user = User(id=1, email="a@example.com", hashed_password=get_password_hash(SECRET), is_active=True)
    answer = client_with(user=user).post("/api/v1/auth/change-password",
                                         json={"current_password": "not-the-password", "new_password": "a-new-password-1"})
    assert answer.status_code == 400
    assert answer.json() == {"error": "current_password: not correct; enter the password you sign in with",
                             "type": "bad_request"}


@pytest.mark.parametrize("method, path, body, status, error", [
    ("GET", "/api/v1/flows/7", None, 404, {"error": "flow 7: not found; use the id of one of your flows", "type": "not_found"}),
    ("POST", "/api/v1/flows", {"name": "f", "definition": {}, "organization_id": 3}, 403,
     {"error": "organization_id: 3 is not an organization of this account; leave it out to use the first one",
      "type": "forbidden"}),
    ("POST", "/api/v1/flows", {"name": "f", "definition": {}}, 400,
     {"error": "this account: belongs to no organization; a flow needs one", "type": "bad_request"}),
])
def test_a_refused_flow_request_names_what_is_missing(method, path, body, status, error):
    answer = client_with(user=ADMIN, row=None).request(method, path, json=body)
    assert answer.status_code == status
    assert answer.json() == error


def test_a_flow_status_that_is_not_one_is_named_without_the_value():
    flow = Flow(id=1, name="f", owner_id=1, definition={"nodes": []})
    answer = client_with(user=ADMIN, row=flow).put("/api/v1/flows/1", json={"status": "running" * 1000})
    assert answer.status_code == 422
    assert answer.json() == {"error": "status: Input should be 'draft', 'active', 'paused' or 'archived'",
                             "type": "bad_request"}


def test_flow_execution_is_no_route():
    flow = Flow(id=1, name="f", owner_id=1, definition={"nodes": []})
    answer = client_with(user=ADMIN, row=flow).post("/api/v1/flows/1/execute")
    assert answer.status_code == 404
    assert answer.json()["type"] == "not_found"
    assert not (APP / "services" / "flink_service.py").exists()


def test_the_execution_history_is_no_route():
    flow = Flow(id=1, name="f", owner_id=1, definition={"nodes": []})
    answer = client_with(user=ADMIN, row=flow).get("/api/v1/flows/1/executions")
    assert answer.status_code == 404
    assert answer.json() == {"error": "GET /api/v1/flows/1/executions: no such route; see /docs for the routes",
                             "type": "not_found"}
    assert "/api/v1/flows/{flow_id}/executions" not in app.openapi()["paths"]


def _http_exception_texts():
    """The detail of every HTTPException the app raises, as its literal text; an f-string by its literal parts."""
    texts = []
    for source in sorted(APP.rglob("*.py")):
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
            if not (isinstance(node, ast.Call) and getattr(node.func, "id", None) == "HTTPException"):
                continue
            detail = node.args[1] if len(node.args) > 1 else next((k.value for k in node.keywords if k.arg == "detail"), None)
            if detail is None:
                continue
            for part in ast.walk(detail):
                if isinstance(part, ast.Constant) and isinstance(part.value, str):
                    texts.append((f"{source.relative_to(APP)}:{node.lineno}", part.value))
    return texts


def test_no_error_text_ends_with_a_period():
    texts = _http_exception_texts()
    assert len(texts) > 20
    assert [t for t in texts if t[1].rstrip().endswith(".")] == []


def test_the_page_calls_no_removed_route():
    page = TestClient(app).get("/").text
    for path in ("/api/v1/organizations", "/api/v1/flows/demo", "/api/v1/node-types"):
        assert path not in page


def _error_statuses():
    """Every error status the app names: status.HTTP_4xx/5xx_* anywhere, and a number as first argument of HTTPException."""
    statuses = set()
    for source in sorted(APP.rglob("*.py")):
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Attribute) and node.attr.startswith("HTTP_"):
                statuses.add(int(node.attr.split("_")[1]))
            elif (isinstance(node, ast.Call) and getattr(node.func, "id", None) == "HTTPException"
                  and node.args and isinstance(node.args[0], ast.Constant)):
                statuses.add(node.args[0].value)
    return {s for s in statuses if s >= 400}


def test_every_status_the_app_raises_has_a_type():
    statuses = _error_statuses()
    assert {401, 413, 422, 502, 503} <= statuses
    assert statuses <= set(main.ERROR_TYPES)


def test_a_status_without_a_type_is_an_internal_error(caplog):
    def teapot():
        raise HTTPException(418, "short and stout")

    client = client_with()
    app.dependency_overrides[get_current_user] = teapot
    with caplog.at_level(logging.ERROR, logger="app.main"):
        answer = client.get("/api/v1/auth/me")
    assert answer.status_code == 500
    assert answer.json() == {"error": "internal error", "type": "internal"}
    assert [r.getMessage() for r in caplog.records if r.name == "app.main"] == [
        "request failed method=GET path=/api/v1/auth/me status=500 cause=KeyError: 418; caused by HTTPException: 418: short and stout"
    ]


def test_the_log_line_names_the_explicit_causes(caplog):
    def broken():
        try:
            raise ConnectionRefusedError(111, "Connection refused")
        except OSError as e:
            raise RuntimeError("the database cannot be reached") from e

    client = client_with()
    app.dependency_overrides[get_current_user] = broken
    with caplog.at_level(logging.ERROR, logger="app.main"):
        assert client.get("/api/v1/auth/me").status_code == 500
    assert [r.getMessage() for r in caplog.records if r.name == "app.main"] == [
        "request failed method=GET path=/api/v1/auth/me status=500 cause=RuntimeError: the database cannot be reached; "
        "caused by ConnectionRefusedError: [Errno 111] Connection refused"
    ]
