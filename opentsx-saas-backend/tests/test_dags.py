"""DAG deployments through the backend: who may deploy and stop, what is forwarded, how refusals come back.

No database, no network.
"""

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import dags
from app.core.config import settings
from app.core.deps import get_current_user
from app.main import app
from app.models.user import User

DAG = b"dag: wind-exp2-smart\nnodes: []\n"


@pytest.fixture
def retrieval(monkeypatch):
    """A retrieval server made of a function: records what the backend sends and answers what the test says."""
    seen = {}
    state = {"status": 200, "json": {"dag": "wind-exp2-smart", "state": "deployed", "sinks": [{"topic": "tsx.wind.smart.x.vectors.v1"}]}}

    def handler(request: httpx.Request) -> httpx.Response:
        if state.get("down"):
            raise httpx.ConnectError("refused", request=request)
        seen["url"] = str(request.url)
        seen["token"] = request.headers.get(dags.RUN_TOKEN_HEADER)
        seen["by"] = request.headers.get(dags.BY_HEADER)
        seen["content_type"] = request.headers.get("content-type")
        seen["body"] = request.content
        return httpx.Response(state["status"], json=state["json"])

    monkeypatch.setattr(dags, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    monkeypatch.setattr(settings, "RETRIEVAL_URL", "http://retrieval:8080/")
    monkeypatch.setattr(settings, "DAG_RUN_TOKEN", "run-token")
    monkeypatch.setattr(settings, "DAG_RUN_ROLE", "superuser")
    state["seen"] = seen
    return state


def client_as(user):
    app.dependency_overrides.clear()
    if user is not None:
        app.dependency_overrides[get_current_user] = lambda: user
    return TestClient(app)


@pytest.fixture(autouse=True)
def no_overrides_left():
    yield
    app.dependency_overrides.clear()


ADMIN = User(id=1, email="admin@example.com", is_active=True, is_superuser=True)
PLAIN = User(id=2, email="user@example.com", is_active=True, is_superuser=False)


def test_needs_a_signed_in_user(retrieval):
    answer = client_as(None).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code in (401, 403)
    assert "url" not in retrieval["seen"]


def test_an_administrator_runs_and_the_backend_adds_the_run_token(retrieval):
    answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG, headers={"Content-Type": "application/yaml"})
    assert answer.status_code == 200
    assert answer.json()["state"] == "deployed"
    seen = retrieval["seen"]
    assert seen["url"] == "http://retrieval:8080/v1/dags/deploy"
    assert seen["by"] == "admin@example.com"
    assert seen["token"] == "run-token"
    assert seen["content_type"] == "application/yaml"
    assert seen["body"] == DAG


def test_a_plain_user_may_not_run_unless_the_role_says_so(retrieval, monkeypatch):
    client = client_as(PLAIN)
    answer = client.post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == 403
    assert "administrators" in answer.json()["detail"]
    assert "url" not in retrieval["seen"]
    assert client.get("/api/v1/dags/capabilities").json() == {
        "canRun": False,
        "reason": "Only administrators may deploy or stop DAGs on this installation.",
    }

    monkeypatch.setattr(settings, "DAG_RUN_ROLE", "user")
    assert client.post("/api/v1/dags/deploy", content=DAG).status_code == 200
    assert client.get("/api/v1/dags/capabilities").json() == {"canRun": True, "reason": None}


def test_the_reason_of_a_refused_dag_reaches_the_user(retrieval):
    retrieval["status"] = 400
    retrieval["json"] = {"error": "node n1 (norm.zscore) is not available in this runtime"}
    answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == 400
    assert answer.json()["detail"] == "node n1 (norm.zscore) is not available in this runtime"


@pytest.mark.parametrize("upstream, expected, text", [
    (401, 503, "run token"),
    (404, 503, "does not deploy DAGs"),
    (500, 502, "boom"),
])
def test_trouble_of_the_installation_is_not_reported_as_the_users_fault(retrieval, upstream, expected, text):
    retrieval["status"] = upstream
    retrieval["json"] = {"error": "boom"}
    answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == expected
    assert text in answer.json()["detail"]


def test_an_unreachable_retrieval_server_is_a_bad_gateway(retrieval):
    retrieval["down"] = True
    answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == 502
    assert "could not be reached" in answer.json()["detail"]


def test_empty_and_oversized_documents_are_refused_before_forwarding(retrieval):
    client = client_as(ADMIN)
    assert client.post("/api/v1/dags/deploy", content=b"").status_code == 400
    assert client.post("/api/v1/dags/deploy", content=b"x" * (dags.MAX_DAG_BYTES + 1)).status_code == 413
    assert "url" not in retrieval["seen"]


def test_without_a_retrieval_server_nothing_runs(retrieval, monkeypatch):
    monkeypatch.setattr(settings, "RETRIEVAL_URL", None)
    client = client_as(ADMIN)
    assert client.post("/api/v1/dags/deploy", content=DAG).status_code == 503
    assert client.get("/api/v1/dags/capabilities").json()["canRun"] is False


def test_stop_forwards_the_name_and_needs_the_same_rights(retrieval):
    retrieval["json"] = {"dag": "wind-exp2-smart", "state": "stopped"}
    answer = client_as(ADMIN).post("/api/v1/dags/stop", params={"name": "wind-exp2-smart"})
    assert answer.status_code == 200
    assert answer.json()["state"] == "stopped"
    assert retrieval["seen"]["url"] == "http://retrieval:8080/v1/dags/stop?name=wind-exp2-smart"
    assert retrieval["seen"]["token"] == "run-token"

    retrieval["seen"].clear()
    assert client_as(PLAIN).post("/api/v1/dags/stop", params={"name": "wind-exp2-smart"}).status_code == 403
    assert client_as(ADMIN).post("/api/v1/dags/stop").status_code == 422
    assert "url" not in retrieval["seen"]
