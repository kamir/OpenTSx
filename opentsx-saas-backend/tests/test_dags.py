"""DAG deployments through the backend: who may deploy and stop, what is forwarded, how refusals come back.

No database, no network.
"""

import logging

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
        if "text" in state:
            return httpx.Response(state["status"], text=state["text"], headers={"Content-Type": state["content_type"]})
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
    assert answer.json() == {
        "error": "this account: not an administrator; only administrators may deploy or stop DAGs on this installation",
        "type": "forbidden",
    }
    assert "url" not in retrieval["seen"]
    assert client.get("/api/v1/dags/capabilities").json() == {
        "canRun": False,
        "reason": "this account: not an administrator; only administrators may deploy or stop DAGs on this installation",
    }

    monkeypatch.setattr(settings, "DAG_RUN_ROLE", "user")
    assert client.post("/api/v1/dags/deploy", content=DAG).status_code == 200
    assert client.get("/api/v1/dags/capabilities").json() == {"canRun": True, "reason": None}


def test_the_reason_of_a_refused_dag_reaches_the_user(retrieval):
    retrieval["status"] = 400
    retrieval["json"] = {"error": "node n1 (norm.zscore) is not available in this runtime"}
    answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == 400
    assert answer.json() == {"error": "node n1 (norm.zscore) is not available in this runtime", "type": "bad_request"}


@pytest.mark.parametrize("upstream, expected, kind, text", [
    (401, 503, "unavailable", "retrieval server: refused the run token of this installation; "
                              "DAG_RUN_TOKEN must equal OPENTSX_DAG_RUN_TOKEN of the retrieval server"),
    (404, 503, "unavailable", "retrieval server: does not deploy DAGs; it needs --kafscale and --dag-runs true"),
    (500, 502, "unavailable", "retrieval server: answered HTTP 500; see the log of the backend"),
])
def test_trouble_of_the_installation_is_not_reported_as_the_users_fault(retrieval, caplog, upstream, expected, kind, text):
    retrieval["status"] = upstream
    retrieval["json"] = {"error": "boom"}
    with caplog.at_level(logging.WARNING, logger="app.api.dags"):
        answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == expected
    assert answer.json() == {"error": text, "type": kind}
    assert "boom" not in answer.text
    assert [r.getMessage() for r in caplog.records if r.name == "app.api.dags"] == [
        f"dag deploy by user 1 refused by the retrieval server: HTTP {upstream} error='boom'"
    ]


@pytest.mark.parametrize("answer_text, content_type", [
    ("<html><body>Sign in to continue</body></html>", "text/html"),
    ("[1, 2]", "application/json"),
])
def test_an_answer_that_is_not_a_json_object_is_a_bad_gateway(retrieval, caplog, answer_text, content_type):
    retrieval["text"] = answer_text
    retrieval["content_type"] = content_type
    with caplog.at_level(logging.WARNING, logger="app.api.dags"):
        answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == 502
    assert answer.json() == {
        "error": "retrieval server: gave an answer the backend cannot read; check that RETRIEVAL_URL names the retrieval server",
        "type": "unavailable",
    }
    assert [r.getMessage() for r in caplog.records if r.name == "app.api.dags"] == [
        f"dag deploy by user 1: the retrieval server answered HTTP 200 with content type {content_type}, not a JSON object"
    ]


def test_an_unreachable_retrieval_server_is_a_bad_gateway(retrieval):
    retrieval["down"] = True
    answer = client_as(ADMIN).post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == 502
    assert answer.json() == {"error": "retrieval server: not reachable; try again later", "type": "unavailable"}


def test_empty_and_oversized_documents_are_refused_before_forwarding(retrieval):
    client = client_as(ADMIN)
    empty = client.post("/api/v1/dags/deploy", content=b"")
    assert empty.status_code == 400
    assert empty.json() == {"error": "body: empty; send an opentsx.dag.v1 document as YAML", "type": "bad_request"}
    large = client.post("/api/v1/dags/deploy", content=b"x" * (dags.MAX_DAG_BYTES + 1))
    assert large.status_code == 413
    assert large.json() == {"error": "body: 262145 bytes; a DAG has at most 262144 bytes", "type": "too_large"}
    assert "url" not in retrieval["seen"]


def test_without_a_retrieval_server_nothing_runs(retrieval, monkeypatch):
    monkeypatch.setattr(settings, "RETRIEVAL_URL", None)
    client = client_as(ADMIN)
    answer = client.post("/api/v1/dags/deploy", content=DAG)
    assert answer.status_code == 503
    assert answer.json() == {"error": "RETRIEVAL_URL: not set; this installation deploys no DAGs", "type": "unavailable"}
    assert client.get("/api/v1/dags/capabilities").json() == {
        "canRun": False, "reason": "RETRIEVAL_URL: not set; this installation deploys no DAGs"}


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
