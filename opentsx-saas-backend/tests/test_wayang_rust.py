import importlib
import asyncio
import json
import hashlib

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from app.api import wayang
from app.core.config import settings
from app.core.deps import get_current_user
from app.db.base import Base
from app.main import app
from app.models.flow import Flow
from app.models.user import User
from app.models.wayang import WayangDeployment, WayangTopicOwner
from app.services import wayang_service as service


ADMIN = User(id=1, email="admin@example.com", is_superuser=True, is_active=True)
OTHER = User(id=2, email="other@example.com", is_superuser=True, is_active=True)
PLAIN = User(id=3, email="plain@example.com", is_superuser=False, is_active=True)
DAG = "schema: opentsx.dag.v2\ndag: test\nsource: {topics: [tsx.test.one.v2]}\n"


class AsyncDatabase:
    def __init__(self, session):
        self.session = session

    async def execute(self, statement):
        return self.session.execute(statement)

    def add(self, row):
        self.session.add(row)

    async def flush(self):
        self.session.flush()

    async def commit(self):
        self.session.commit()

    async def rollback(self):
        self.session.rollback()


@pytest.fixture
def database():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    with Session(engine, expire_on_commit=False) as session:
        session.add_all([User(id=user.id, email=user.email, hashed_password="fixture", is_superuser=user.is_superuser)
                         for user in (ADMIN, OTHER, PLAIN)])
        session.add_all([Flow(id=1, name="mine", definition={}, owner_id=1),
                         Flow(id=2, name="other", definition={}, owner_id=2)])
        session.commit()
        yield engine
    engine.dispose()


def client(database, user=ADMIN):
    session = Session(database, expire_on_commit=False)
    app.dependency_overrides[wayang.get_journal] = lambda: service.Journal(AsyncDatabase(session))
    if user:
        app.dependency_overrides[get_current_user] = lambda: user
    return TestClient(app)


@pytest.fixture(autouse=True)
def cleanup():
    yield
    app.dependency_overrides.clear()


def plan_for(data):
    assignments = []
    for number in range(1, 4):
        topic = f"tsx.test.input{number}.v2"
        assignment = {"node": f"worker-{number}", "runtime": "rust", "mode": "bounded",
                      "specHash": service.digest(data["dagText"]), "sourceDagText": data["dagText"],
                      "inputs": [{"topic": topic, "partitionCount": str(number), "positions": [
                          {"partition": str(index), "from": "0", "to": "2"} for index in range(number)]}]}
        request = {"assignmentText": json.dumps(assignment), "createdAtMicros": data["createdAtMicros"],
                   "sink": {"topic": "tsx.test.output.v2"}}
        assignments.append({"node": assignment["node"], "inputTopic": topic, "endpoint": "http://private-worker",
                            "requestText": json.dumps(request)})
    return {"schema": "opentsx.local-rollout.v1", "deployment": data["deployment"],
            "sourceDagText": data["dagText"], "specHash": service.digest(data["dagText"]),
            "createdAtMicros": data["createdAtMicros"], "assignments": assignments, "planHash": "a" * 64}


@pytest.fixture
def driver(monkeypatch):
    state = {"calls": [], "execute": "completed", "planStatus": 200}
    monkeypatch.setattr(settings, "WAYANG_URL", "http://wayang")
    monkeypatch.setattr(settings, "RETRIEVAL_URL", None)
    monkeypatch.setattr(settings, "DAG_RUN_ROLE", "superuser")

    def handler(request):
        state["calls"].append((request.url.path, request.content))
        if request.url.path == "/v1/capabilities":
            return httpx.Response(200, json={"schema": "opentsx.local-rollout-capabilities.v1", "profile": "bounded",
                "operators": ["convert.unit"], "workers": [{"node": "worker-1", "endpoint": "http://private-worker"}]})
        data = json.loads(request.content)
        if request.url.path == "/v1/plan":
            if state["planStatus"] != 200:
                return httpx.Response(state["planStatus"], json={"error": "unsupported operator"})
            plan = plan_for(data)
            if state.get("badPlan"):
                plan["sourceDagText"] = "changed"
            return httpx.Response(200, content=json.dumps(plan, indent=2))
        if state["execute"] == "unreachable":
            raise httpx.ConnectError("lost", request=request)
        result = {"deployment": data["deployment"], "planHash": data["planHash"], "state": state["execute"],
                  "assignments": [{"node": item["node"], "state": "completed", "receipt": {"inputRecords": 2}}
                                  for item in data["assignments"]]}
        if state.get("badResult"):
            result["planHash"] = "b" * 64
        if state.get("resultMutation"):
            state["resultMutation"](result)
        return httpx.Response(200, json=result)

    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    return state


def reconcile(database):
    with Session(database, expire_on_commit=False) as session:
        return asyncio.run(service.reconcile_once(service.Journal(AsyncDatabase(session))))


def submit(api, dag=DAG, flow=1):
    return api.post("/api/v1/dags/wayang-rust/deploy", json={"flowId": flow, "dagText": dag})


def test_bounded_wayang_target_exists():
    module = importlib.import_module("app.api.wayang")
    assert module.router.prefix == "/dags/wayang-rust"


def test_capabilities_require_auth_and_role_without_retrieval_dependency(database, driver):
    assert client(database, None).get("/api/v1/dags/wayang-rust/capabilities").status_code in (401, 403)
    app.dependency_overrides.clear()
    assert client(database, PLAIN).get("/api/v1/dags/wayang-rust/capabilities").json()["canRun"] is False
    result = client(database).get("/api/v1/dags/wayang-rust/capabilities").json()
    assert result["canRun"] is True
    assert "endpoint" not in result["workers"][0]


def test_admission_persists_exact_plan_before_any_execution_and_reuses_frozen_identity(database, driver):
    api = client(database)
    first = submit(api)
    assert first.status_code == 202, first.text
    row = first.json()
    assert row["state"] == "queued"
    assert [item["partitionCount"] for item in row["assignments"]] == ["1", "2", "3"]
    assert [path for path, _ in driver["calls"]] == ["/v1/plan"]
    assert submit(api).json() == row
    assert len(driver["calls"]) == 1
    with Session(database) as session:
        saved = session.get(WayangDeployment, row["id"])
        persisted = saved.plan_text.encode()
        exact_request = json.loads(saved.plan_text)["assignments"][0]["requestText"]
        assert row["assignments"][0]["requestHash"] == hashlib.sha256(exact_request.encode()).hexdigest()
        assert row["assignments"][0]["specHash"] == hashlib.sha256(DAG.encode()).hexdigest()
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4
    assert reconcile(database)
    assert driver["calls"][-1] == ("/v1/execute", persisted)
    complete = api.get(f"/api/v1/dags/wayang-rust/deployments/{row['id']}").json()
    assert complete["state"] == "completed"
    assert submit(api).json() == complete
    assert not reconcile(database)
    with Session(database) as session:
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


def test_uncertain_execution_retains_ownership_and_reconciles_same_plan_after_restart(database, driver):
    row = submit(client(database)).json()
    driver["execute"] = "unreachable"
    assert reconcile(database)
    first_body = driver["calls"][-1][1]
    with Session(database) as session:
        assert session.get(WayangDeployment, row["id"]).state == "running"
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4
    driver["execute"] = "completed"
    assert reconcile(database)
    assert driver["calls"][-1][1] == first_body


def test_conflicting_whole_topic_ownership_rejects_other_deployments(database, driver):
    api = client(database)
    assert submit(api).status_code == 202
    conflict = submit(api, DAG + "# changed\n")
    assert conflict.status_code == 409
    with Session(database) as session:
        assert len(session.execute(select(WayangDeployment)).scalars().all()) == 1


def test_foreign_flows_and_deployments_are_not_exposed(database, driver):
    api = client(database)
    assert submit(api, flow=2).status_code == 404
    own = submit(api).json()
    other = client(database, OTHER)
    assert other.get(f"/api/v1/dags/wayang-rust/deployments/{own['id']}").status_code == 404
    assert other.get("/api/v1/dags/wayang-rust/deployments").json() == {"deployments": []}


@pytest.mark.parametrize("body", ['{"flowId":1,"flowId":1,"dagText":"x"}',
                                     '{"flowId":true,"dagText":"x"}',
                                     '{"flowId":1,"dagText":"x","runtime":"java"}',
                                     '{"flowId":1,"dagText":"\\ud800"}'])
def test_malformed_typed_requests_never_reach_driver(database, driver, body):
    assert client(database).post("/api/v1/dags/wayang-rust/deploy", content=body).status_code == 422
    assert driver["calls"] == []


@pytest.mark.parametrize("status", [400, 409])
def test_compiler_refusal_never_falls_back_or_creates_work(database, driver, status):
    driver["planStatus"] = status
    assert submit(client(database)).status_code == status
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []


def test_mismatched_plan_and_receipt_fail_closed(database, driver):
    api = client(database)
    driver["badPlan"] = True
    assert submit(api).status_code == 502
    driver["badPlan"] = False
    row = submit(api).json()
    driver["badResult"] = True
    assert reconcile(database)
    with Session(database) as session:
        saved = session.get(WayangDeployment, row["id"])
        assert saved.state == "running"
        assert saved.result is None
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4


def test_conclusive_failure_releases_ownership_without_becoming_success(database, driver):
    api = client(database)
    row = submit(api).json()
    driver["execute"] = "failed"
    assert reconcile(database)
    assert api.get(f"/api/v1/dags/wayang-rust/deployments/{row['id']}").json()["state"] == "failed"
    with Session(database) as session:
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


def test_retryable_publication_keeps_the_frozen_plan_running_and_topics_owned(database, driver):
    row = submit(client(database)).json()
    driver["execute"] = "failed"
    driver["resultMutation"] = lambda result: result["assignments"][0].update(
        state="failed", activationRetryable=True, error="delivery acknowledgement was lost")
    assert reconcile(database)
    first_body = driver["calls"][-1][1]
    with Session(database) as session:
        saved = session.get(WayangDeployment, row["id"])
        assert saved.state == "running"
        assert saved.result is None
        assert saved.error == "The Wayang worker outcome still requires reconciliation."
        assert saved.created_at_micros == row["createdAtMicros"]
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4
    driver["execute"] = "completed"
    del driver["resultMutation"]
    assert reconcile(database)
    assert driver["calls"][-1][1] == first_body
    with Session(database) as session:
        assert session.get(WayangDeployment, row["id"]).state == "completed"
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


@pytest.mark.parametrize("mutation", [
    lambda result: result.update(assignments=result["assignments"][:1]),
    lambda result: result.update(assignments=[result["assignments"][0]] * 3),
    lambda result: result.update(assignments=[{"node": "unknown", "state": "completed", "receipt": {}}]),
    lambda result: result.update(assignments=[None]),
    lambda result: result.update(state="failed", assignments=[{"node": "worker-1", "state": "running"}]),
])
def test_unverified_worker_inventories_keep_the_plan_running_and_owned(database, driver, mutation):
    row = submit(client(database)).json()
    driver["resultMutation"] = mutation
    assert reconcile(database)
    with Session(database) as session:
        assert session.get(WayangDeployment, row["id"]).state == "running"
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4


def test_noncanonical_json_scalars_and_depth_are_refused(database, driver):
    api = client(database)
    for body in ['{"flowId":1,"dagText":"x","other":1e309}', '[' * 65 + '0' + ']' * 65]:
        assert api.post("/api/v1/dags/wayang-rust/deploy", content=body).status_code == 422
    assert driver["calls"] == []


def test_other_owner_cannot_recreate_the_same_scientific_identity_after_completion(database, driver, monkeypatch):
    clocks = iter((1_700_000_000_000_000_000, 1_700_000_001_000_000_000))
    monkeypatch.setattr(wayang.time, "time_ns", lambda: next(clocks))
    first = submit(client(database))
    assert first.status_code == 202
    assert reconcile(database)
    second = submit(client(database, OTHER), flow=2)
    assert second.status_code == 409
    assert second.json() == {"detail": "The deployment conflicts with an existing source identity or whole-topic owner."}
    with Session(database) as session:
        rows = session.execute(select(WayangDeployment)).scalars().all()
        assert len(rows) == 1
        assert rows[0].spec_hash == first.json()["specHash"]
        assert rows[0].created_at_micros == first.json()["createdAtMicros"]
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


@pytest.mark.parametrize("owner,flow,reuse", [(1, 1, True), (2, 2, False)])
def test_source_identity_race_reuses_only_the_same_owner_flow_and_frozen_request(database, driver, owner, flow, reuse):
    first = submit(client(database)).json()
    assert reconcile(database)
    with Session(database, expire_on_commit=False) as session:
        plan = plan_for({"deployment": "local-racing-request", "dagText": DAG, "createdAtMicros": "1700000001000000"})
        row = WayangDeployment(id=plan["deployment"], owner_id=owner, flow_id=flow,
            spec_hash=service.digest(DAG), dag_text=DAG, created_at_micros=plan["createdAtMicros"],
            plan_hash=plan["planHash"], plan_text=json.dumps(plan), state="queued")
        journal = service.Journal(AsyncDatabase(session))
        if reuse:
            accepted = asyncio.run(journal.accept(row, service.plan_assignments(plan)))
            assert accepted.id == first["id"]
            assert accepted.created_at_micros == first["createdAtMicros"]
        else:
            with pytest.raises(service.WayangError) as error:
                asyncio.run(journal.accept(row, service.plan_assignments(plan)))
            assert error.value.status == 409
            assert error.value.message == "The deployment conflicts with an existing source identity or whole-topic owner."
        assert len(session.execute(select(WayangDeployment)).scalars().all()) == 1
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []
