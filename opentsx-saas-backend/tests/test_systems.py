"""The working copy of signal bindings and the demo generators: who may do what, what is refused, what is forwarded.

No database, no network: the store is a dictionary and the retrieval server a function.
"""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import dags, systems
from app.core.config import settings
from app.core.deps import get_current_user
from app.main import app
from app.models.user import User

ADMIN = User(id=1, email="admin@example.com", is_active=True, is_superuser=True)
PLAIN = User(id=2, email="user@example.com", is_active=True, is_superuser=False)

TOPIC = {
    "kind": "topic",
    "topic": "tsx.wind.episodes.v2",
    "origin": "REPORTED",
    "select": {"metric": "h2.demand", "tags": {"customer": "c1"}},
}
GENERATOR = {"kind": "generator", "processor": "gen.ou", "seed": 7, "params": {"mean": 60, "sigma": 5}}


class MemoryStore:
    """Working copies in a dictionary, with the interface of the SQL store."""

    def __init__(self):
        self.rows = {}

    async def list(self, owner, system):
        return [v for (o, s, _, _), v in sorted(self.rows.items()) if o == owner and s == system]

    async def put(self, owner, system, binding):
        row = {"signal": binding.signal, "grade": binding.grade, "source": binding.source,
               "meaningConfirmed": binding.meaningConfirmed, "baseHash": binding.baseHash}
        self.rows[(owner, system, binding.signal, binding.grade)] = row
        return row

    async def remove(self, owner, system, signal, grade):
        return self.rows.pop((owner, system, signal, grade), None) is not None


@pytest.fixture
def store():
    return MemoryStore()


def client_as(user, store):
    app.dependency_overrides.clear()
    app.dependency_overrides[systems.get_store] = lambda: store
    if user is not None:
        app.dependency_overrides[get_current_user] = lambda: user
    return TestClient(app)


@pytest.fixture(autouse=True)
def no_overrides_left():
    yield
    app.dependency_overrides.clear()


@pytest.fixture
def retrieval(monkeypatch):
    seen = {}
    state = {"status": 200, "json": {"signal": "market.price_eur_mwh", "episodes": 1, "samples": 24, "fidelity": "demo"}, "seen": seen}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["token"] = request.headers.get(dags.RUN_TOKEN_HEADER)
        seen["content_type"] = request.headers.get("content-type")
        seen["body"] = json.loads(request.content)
        return httpx.Response(state["status"], json=state["json"])

    monkeypatch.setattr(dags, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    monkeypatch.setattr(settings, "RETRIEVAL_URL", "http://retrieval:8080/")
    monkeypatch.setattr(settings, "DAG_RUN_TOKEN", "run-token")
    monkeypatch.setattr(settings, "DAG_RUN_ROLE", "superuser")
    return state


def test_a_working_copy_belongs_to_one_user(store):
    plain = client_as(PLAIN, store)
    saved = plain.put("/api/v1/systems/h2-chain/bindings",
                      json={"signal": "offtake.demand_kgph", "source": TOPIC, "meaningConfirmed": True, "baseHash": "8747aeb3"})
    assert saved.status_code == 200
    assert saved.json()["binding"]["source"]["origin"] == "REPORTED"
    mine = plain.get("/api/v1/systems/h2-chain/bindings").json()
    assert [b["signal"] for b in mine["bindings"]] == ["offtake.demand_kgph"]
    assert mine["bindings"][0]["baseHash"] == "8747aeb3"
    # another user has a working copy of their own, and so has another system
    assert client_as(ADMIN, store).get("/api/v1/systems/h2-chain/bindings").json()["bindings"] == []
    assert client_as(PLAIN, store).get("/api/v1/systems/boat/bindings").json()["bindings"] == []


def test_needs_a_signed_in_user(store):
    assert client_as(None, store).get("/api/v1/systems/h2-chain/bindings").status_code in (401, 403)
    assert client_as(None, store).put("/api/v1/systems/h2-chain/bindings", json={"signal": "a.b", "source": GENERATOR}).status_code in (401, 403)


def test_a_series_is_bound_only_after_its_meaning_was_confirmed(store):
    client = client_as(PLAIN, store)
    refused = client.put("/api/v1/systems/h2-chain/bindings", json={"signal": "offtake.demand_kgph", "source": TOPIC})
    assert refused.status_code == 422
    assert "Confirm that the series is the quantity" in refused.json()["detail"]
    assert store.rows == {}


@pytest.mark.parametrize("binding, reason", [
    ({"signal": "Not A Signal", "source": GENERATOR}, "signal id must be"),
    ({"signal": "a.b", "grade": "features", "source": GENERATOR}, "grade raw or clean"),
    ({"signal": "a.b", "source": {"kind": "flow", "flow": "x"}}, "becomes a source by publishing"),
    ({"signal": "a.b", "source": {"kind": "topic", "topic": "t", "origin": "REPORTED"}, "meaningConfirmed": True}, "what to select"),
    ({"signal": "a.b", "source": {**TOPIC, "origin": "REAL"}, "meaningConfirmed": True}, "how its data came to be"),
    ({"signal": "a.b", "source": {"kind": "generator", "processor": "gen.magic"}}, "generator must be one of"),
    ({"signal": "a.b", "source": {"kind": "generator", "processor": "gen.ou", "seed": "seven"}}, "whole number"),
    ({"signal": "a.b", "source": {"kind": "manual"}}, "needs a value"),
])
def test_a_binding_that_cannot_be_a_source_is_refused_with_the_reason(store, binding, reason):
    answer = client_as(PLAIN, store).put("/api/v1/systems/h2-chain/bindings", json=binding)
    assert answer.status_code == 422
    assert reason in answer.json()["detail"]


def test_a_binding_is_replaced_and_removed(store):
    client = client_as(PLAIN, store)
    client.put("/api/v1/systems/h2-chain/bindings", json={"signal": "market.price_eur_mwh", "source": GENERATOR})
    client.put("/api/v1/systems/h2-chain/bindings", json={"signal": "market.price_eur_mwh", "source": {"kind": "manual", "value": 62.8}})
    rows = client.get("/api/v1/systems/h2-chain/bindings").json()["bindings"]
    assert len(rows) == 1 and rows[0]["source"]["kind"] == "manual"
    assert client.delete("/api/v1/systems/h2-chain/bindings", params={"signal": "market.price_eur_mwh"}).status_code == 200
    assert client.delete("/api/v1/systems/h2-chain/bindings", params={"signal": "market.price_eur_mwh"}).status_code == 404
    assert client.get("/api/v1/systems/h2-chain/bindings").json()["bindings"] == []


def test_an_administrator_generates_and_the_working_copy_goes_along(store, retrieval):
    client = client_as(ADMIN, store)
    client.put("/api/v1/systems/h2-chain/bindings", json={"signal": "offtake.demand_kgph", "source": GENERATOR})
    answer = client.post("/api/v1/systems/h2-chain/generate",
                         json={"signal": "offtake.demand_kgph", "from": "2026-10-01T00:00:00Z", "to": "2026-10-01T06:00:00Z"})
    assert answer.status_code == 200
    assert answer.json()["fidelity"] == "demo"
    seen = retrieval["seen"]
    assert seen["url"] == "http://retrieval:8080/v1/systems/h2-chain/generate"
    assert seen["token"] == "run-token"
    assert seen["content_type"] == "application/json"
    assert seen["body"]["signal"] == "offtake.demand_kgph"
    assert seen["body"]["from"] == "2026-10-01T00:00:00Z"
    assert seen["body"]["bindings"] == [{"signal": "offtake.demand_kgph", "grade": "raw", "source": GENERATOR}]


def test_generating_writes_to_the_broker_and_is_for_administrators(store, retrieval):
    answer = client_as(PLAIN, store).post("/api/v1/systems/h2-chain/generate",
                                          json={"signal": "market.price_eur_mwh", "from": "2026-10-01T00:00:00Z", "to": "2026-10-01T06:00:00Z"})
    assert answer.status_code == 403
    assert "url" not in retrieval["seen"]


def test_a_refusal_of_the_retrieval_server_reaches_the_person(store, retrieval):
    retrieval["status"] = 400
    retrieval["json"] = {"error": "ely.setpoint_kw is not bound to a generator"}
    answer = client_as(ADMIN, store).post("/api/v1/systems/h2-chain/generate",
                                          json={"signal": "ely.setpoint_kw", "from": "2026-10-01T00:00:00Z", "to": "2026-10-01T06:00:00Z"})
    assert answer.status_code == 400
    assert answer.json()["detail"] == "ely.setpoint_kw is not bound to a generator"
