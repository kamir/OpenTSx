import asyncio
import json
from datetime import datetime, timedelta

import httpx
import pytest
from sqlalchemy import inspect, select
from sqlalchemy.orm import Session

from app.models.wayang import WayangDeployment, WayangTopicOwner
from app.services import wayang_service as service
from test_wayang_rust import OTHER, PLAIN, DAG, AsyncDatabase, cleanup, client, database, plan_for, reconcile


def continuous_plan(data, previous=None):
    value = plan_for(data)
    value.update(schema="opentsx.local-continuous-rollout.v1", sequence="1", previousPlanHash=None)
    if previous:
        value["sequence"] = str(int(previous["sequence"]) + 1)
        value["previousPlanHash"] = previous["planHash"]
    for index, item in enumerate(value["assignments"]):
        request = json.loads(item["requestText"])
        request.update(schema="opentsx.local-increment.v1", sequence=value["sequence"], previousRequestHash=None)
        assignment = json.loads(request["assignmentText"])
        assignment.update(deployment=value["deployment"], fragmentHash="e" * 64)
        if previous:
            old = previous["assignments"][index]["requestText"]
            request["previousRequestHash"] = service.digest(old)
            old_positions = json.loads(json.loads(old)["assignmentText"])["inputs"][0]["positions"]
            for position, old_position in zip(assignment["inputs"][0]["positions"], old_positions):
                position.update({"from": old_position["to"], "to": str(int(old_position["to"]) + 2)})
        request["assignmentText"] = json.dumps(assignment)
        item["requestText"] = json.dumps(request)
    value["planHash"] = service.legacy_plan_hash(value)
    return value


@pytest.fixture
def continuous_driver(monkeypatch):
    from app.core.config import settings
    clock = [datetime(2026, 1, 1)]
    state = {"calls": [], "ready": False, "execute": "completed", "clock": clock}
    monkeypatch.setattr(settings, "WAYANG_URL", "http://wayang")
    monkeypatch.setattr(settings, "RETRIEVAL_URL", None)
    monkeypatch.setattr(settings, "DAG_RUN_ROLE", "superuser")
    monkeypatch.setattr(service, "utc_now", lambda: clock[0])

    def handler(request):
        state["calls"].append((request.url.path, request.content))
        if request.url.path == "/v1/capabilities":
            return httpx.Response(200, json={"schema": "opentsx.local-rollout-capabilities.v1", "profile": "bounded",
                "profiles": ["bounded", "continuous"], "operators": ["convert.unit"], "workers": [{"node": "worker-1"}]})
        data = json.loads(request.content)
        if request.url.path == "/v1/plan":
            value = continuous_plan(data) if data.get("profile") == "continuous" else plan_for(data)
            return httpx.Response(200, content=json.dumps(value, indent=2))
        if request.url.path == "/v1/next":
            if state.get("onNext"):
                state["onNext"]()
            if state.get("nextError"):
                return httpx.Response(state["nextError"], json={"error": "source topology or retention changed"})
            previous = data["plan"]
            next_plan = continuous_plan({"deployment": previous["deployment"], "dagText": previous["sourceDagText"],
                                        "createdAtMicros": previous["createdAtMicros"]}, previous)
            if state.get("nextMutation"):
                state["nextMutation"](next_plan)
            return httpx.Response(200, json={"state": "ready", "plan": next_plan} if state["ready"]
                                  else {"state": "idle", "plan": None})
        assert request.url.path == "/v1/execute"
        if state.get("onExecute"):
            state["onExecute"]()
        if state["execute"] == "unreachable":
            raise httpx.ConnectError("lost acknowledgement", request=request)
        statuses = []
        for item in data["assignments"]:
            assignment = json.loads(json.loads(item["requestText"])["assignmentText"])
            positions = assignment["inputs"][0]["positions"]
            count = sum(int(p["to"]) - int(p["from"]) for p in positions)
            receipt = {"schema": "opentsx.local-receipt.v1", "requestHash": service.digest(item["requestText"]),
                       "deployment": data["deployment"], "node": item["node"], "inputTopic": item["inputTopic"],
                       "outputTopic": json.loads(item["requestText"])["sink"]["topic"],
                       "positions": [{"partition": p["partition"], "next": p["to"]} for p in positions],
                       "inputRecords": count, "outputRecords": count, "stateDigest": "f" * 64}
            statuses.append({"node": item["node"], "state": "completed", "receipt": receipt})
        result = {"deployment": data["deployment"], "planHash": data["planHash"], "state": state["execute"],
                  "assignments": statuses}
        if state.get("resultMutation"):
            state["resultMutation"](result)
        return httpx.Response(200, json=result)

    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    return state


def submit_continuous(api, text=DAG):
    return api.post("/api/v1/dags/wayang-rust/deploy", json={"flowId": 1, "dagText": text, "profile": "continuous"})


def progress(api, deployment):
    response = api.get(f"/api/v1/dags/wayang-rust/deployments/{deployment}")
    assert response.status_code == 200, response.text
    return response.json()


def tick(state):
    state["clock"][0] += timedelta(seconds=3)


def owned(database):
    with Session(database) as session:
        return len(session.execute(select(WayangTopicOwner)).scalars().all())


def test_continuous_admission_is_durable_idempotent_and_profile_explicit(database, continuous_driver):
    api = client(database)
    first = submit_continuous(api)
    assert first.status_code == 202, first.text
    row = first.json()
    assert row["profile"] == "continuous"
    assert row["currentCycle"] == "1"
    assert row["completedCycles"] == "0"
    assert row["inputRecords"] == row["outputRecords"] == "0"
    assert submit_continuous(api).json() == row
    assert [path for path, _ in continuous_driver["calls"]] == ["/v1/plan"]
    from app.models.wayang import WayangCycle
    with Session(database) as session:
        cycle = session.execute(select(WayangCycle)).scalar_one()
        assert cycle.plan_text == session.get(WayangDeployment, row["id"]).plan_text
        assert cycle.state == "queued"
    assert owned(database) == 4
    assert api.post("/api/v1/dags/wayang-rust/deploy", json={"flowId": 1, "dagText": DAG}).status_code == 409


def test_continuous_completion_idle_polling_and_new_data_preserve_ownership_and_watermarks(database, continuous_driver):
    api = client(database)
    first = submit_continuous(api).json()
    assert reconcile(database)
    idle = progress(api, first["id"])
    assert idle["state"] == "idle"
    assert idle["completedCycles"] == "1"
    assert idle["inputRecords"] == idle["outputRecords"] == "12"
    assert [item["cumulativeInputRecords"] for item in idle["assignments"]] == ["2", "4", "6"]
    assert [len(item["positions"]) for item in idle["assignments"]] == [1, 2, 3]
    assert owned(database) == 4
    tick(continuous_driver)
    assert reconcile(database)
    assert progress(api, first["id"])["completedCycles"] == "1"
    assert not reconcile(database)
    continuous_driver["ready"] = True
    tick(continuous_driver)
    assert reconcile(database)
    queued = progress(api, first["id"])
    assert queued["currentCycle"] == "2"
    assert queued["state"] == "queued"
    assert queued["inputRecords"] == "12"
    assert [path for path, _ in continuous_driver["calls"]].count("/v1/execute") == 1
    assert reconcile(database)
    current = progress(api, first["id"])
    assert current["completedCycles"] == "2"
    assert current["inputRecords"] == current["outputRecords"] == "24"
    assert all(p["next"] == "4" for item in current["assignments"] for p in item["positions"])
    assert current["createdAtMicros"] == first["createdAtMicros"]
    assert current["specHash"] == first["specHash"]
    assert submit_continuous(api).json() == current


def test_unknown_increment_replays_exact_durable_bytes_and_does_not_advance(database, continuous_driver):
    api = client(database)
    row = submit_continuous(api).json()
    continuous_driver["execute"] = "unreachable"
    assert reconcile(database)
    body = continuous_driver["calls"][-1][1]
    running = progress(api, row["id"])
    assert running["state"] == "running"
    assert running["completedCycles"] == "0"
    assert owned(database) == 4
    continuous_driver["execute"] = "completed"
    assert reconcile(database)
    assert continuous_driver["calls"][-1][1] == body
    assert progress(api, row["id"])["completedCycles"] == "1"


@pytest.mark.parametrize("dispatched", [False, True])
def test_stop_releases_only_after_dispatched_cycle_is_reconciled(database, continuous_driver, dispatched):
    api = client(database)
    row = submit_continuous(api).json()
    if dispatched:
        continuous_driver["execute"] = "unreachable"
        assert reconcile(database)
    stopped = api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop")
    assert stopped.status_code == 202, stopped.text
    assert stopped.json()["stopRequested"] is True
    assert owned(database) == 4
    if dispatched:
        assert reconcile(database)
        assert progress(api, row["id"])["state"] == "stopping"
        assert owned(database) == 4
        continuous_driver["execute"] = "completed"
    assert reconcile(database)
    result = progress(api, row["id"])
    assert result["state"] == "stopped"
    assert owned(database) == 0
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").json() == result
    assert not reconcile(database)
    assert [path for path, _ in continuous_driver["calls"]].count("/v1/execute") == (3 if dispatched else 0)


def test_stop_during_next_plan_prevents_the_new_cycle_from_being_persisted_or_executed(database, continuous_driver):
    api = client(database)
    row = submit_continuous(api).json()
    assert reconcile(database)
    continuous_driver["ready"] = True
    continuous_driver["onNext"] = lambda: api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop")
    tick(continuous_driver)
    assert reconcile(database)
    result = progress(api, row["id"])
    assert result["state"] == "stopped"
    assert result["currentCycle"] == "1"
    assert [path for path, _ in continuous_driver["calls"]].count("/v1/execute") == 1
    assert owned(database) == 0


def test_stop_during_execute_is_not_overwritten_by_stale_coordinator_state(database, continuous_driver):
    api = client(database)
    row = submit_continuous(api).json()
    continuous_driver["onExecute"] = lambda: api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop")
    assert reconcile(database)
    result = progress(api, row["id"])
    assert result["state"] == "stopped"
    assert result["completedCycles"] == "1"
    assert result["inputRecords"] == "12"
    assert owned(database) == 0


def test_completed_cycle_pruning_retains_totals_and_latest_two_only(database, continuous_driver):
    from app.models.wayang import WayangCycle
    api = client(database)
    row = submit_continuous(api).json()
    for number in range(1, 6):
        assert reconcile(database)
        current = progress(api, row["id"])
        assert current["completedCycles"] == str(number)
        assert current["inputRecords"] == str(number * 12)
        with Session(database) as session:
            cycles = session.execute(select(WayangCycle).order_by(WayangCycle.number)).scalars().all()
            assert [cycle.number for cycle in cycles] == list(range(max(1, number - 1), number + 1))
        if number < 5:
            tick(continuous_driver)
            continuous_driver["ready"] = True
            assert reconcile(database)
    assert owned(database) == 4


@pytest.mark.parametrize("mutation", [
    lambda plan: plan.update(sequence="3"),
    lambda plan: plan.update(previousPlanHash="0" * 64),
    lambda plan: plan.update(deployment="another-root"),
    lambda plan: plan["assignments"][0].update(endpoint="http://another-worker"),
    lambda plan: plan["assignments"][0].update(node="another-worker"),
])
def test_invalid_continuation_never_dispatches_or_releases_owners(database, continuous_driver, mutation):
    api = client(database)
    row = submit_continuous(api).json()
    assert reconcile(database)
    continuous_driver.update(ready=True, nextMutation=mutation)
    tick(continuous_driver)
    assert reconcile(database)
    result = progress(api, row["id"])
    assert result["currentCycle"] == "1"
    assert result["completedCycles"] == "1"
    assert result["error"]
    assert owned(database) == 4
    assert [path for path, _ in continuous_driver["calls"]].count("/v1/execute") == 1


@pytest.mark.parametrize("mutation", [
    lambda result: result["assignments"][0]["receipt"].update(inputRecords=True),
    lambda result: result["assignments"][0]["receipt"].update(outputRecords=-1),
    lambda result: result["assignments"][0]["receipt"].update(requestHash="0" * 64),
    lambda result: result["assignments"][0]["receipt"].update(positions=[{"partition": "0", "next": "3"}]),
    lambda result: result["assignments"][0].update(state="failed", activationRetryable=True),
])
def test_unverified_receipt_never_changes_watermarks_or_totals(database, continuous_driver, mutation):
    api = client(database)
    row = submit_continuous(api).json()
    continuous_driver["resultMutation"] = mutation
    assert reconcile(database)
    result = progress(api, row["id"])
    assert result["state"] == "running"
    assert result["completedCycles"] == "0"
    assert result["inputRecords"] == result["outputRecords"] == "0"
    assert owned(database) == 4


def test_stop_is_owner_scoped_and_uses_existing_role_policy(database, continuous_driver):
    own = submit_continuous(client(database)).json()
    url = f"/api/v1/dags/wayang-rust/deployments/{own['id']}/stop"
    assert client(database, OTHER).post(url).status_code == 404
    assert client(database, PLAIN).post(url).status_code == 403
    assert owned(database) == 4


def test_continuous_capabilities_are_explicit(database, continuous_driver):
    response = client(database).get("/api/v1/dags/wayang-rust/capabilities")
    assert response.status_code == 200
    assert response.json()["profiles"] == ["bounded", "continuous"]


def test_planning_refusal_holds_owners_until_explicit_stop(database, continuous_driver):
    api = client(database)
    row = submit_continuous(api).json()
    assert reconcile(database)
    continuous_driver["nextError"] = 400
    tick(continuous_driver)
    assert reconcile(database)
    failed = progress(api, row["id"])
    assert failed["state"] == "failed"
    assert failed["completedCycles"] == "1"
    assert failed["inputRecords"] == "12"
    assert owned(database) == 4
    assert not reconcile(database)
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").status_code == 202
    assert reconcile(database)
    assert progress(api, row["id"])["state"] == "stopped"
    assert owned(database) == 0


def test_successor_is_durable_before_its_unknown_execution_and_restart(database, continuous_driver):
    from app.models.wayang import WayangCycle
    api = client(database)
    row = submit_continuous(api).json()
    assert reconcile(database)
    tick(continuous_driver)
    continuous_driver["ready"] = True
    assert reconcile(database)
    with Session(database) as session:
        pending = session.get(WayangCycle, (row["id"], 2))
        assert pending.state == "queued"
        body = pending.plan_text.encode()
    continuous_driver["execute"] = "unreachable"
    assert reconcile(database)
    assert continuous_driver["calls"][-1] == ("/v1/execute", body)
    assert progress(api, row["id"])["completedCycles"] == "1"
    continuous_driver["execute"] = "completed"
    assert reconcile(database)
    assert continuous_driver["calls"][-1] == ("/v1/execute", body)
    assert progress(api, row["id"])["completedCycles"] == "2"
    assert progress(api, row["id"])["inputRecords"] == "24"


def test_new_bounded_row_has_an_explicitly_loaded_absent_continuous_control(database, continuous_driver):
    plan = plan_for({"deployment": "bounded-row", "dagText": DAG, "createdAtMicros": "1700000000000000"})
    row = WayangDeployment(id=plan["deployment"], owner_id=1, flow_id=1, spec_hash=service.digest(DAG), dag_text=DAG,
                          created_at_micros=plan["createdAtMicros"], plan_hash=plan["planHash"],
                          plan_text=json.dumps(plan), state="queued")
    with Session(database, expire_on_commit=False) as session:
        asyncio.run(service.Journal(AsyncDatabase(session)).accept(row, service.plan_assignments(plan)))
        assert inspect(row).attrs.continuous.loaded_value is None
