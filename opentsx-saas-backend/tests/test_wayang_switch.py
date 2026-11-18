import asyncio
import json
import secrets

import httpx
import pytest

from app.api import wayang
from app.core.config import settings
from app.main import app
from app.services import wayang_service as service
from app.models.wayang import WayangCycle, WayangDeployment, WayangPublication, WayangSwitch, WayangTopicOwner
from sqlalchemy import event, select
from sqlalchemy.exc import MissingGreenlet
from sqlalchemy.orm import Session
from test_wayang_continuous import continuous_driver, owned, progress, submit_continuous, tick
from test_wayang_rust import AsyncDatabase, OTHER, PLAIN, cleanup, client, database, reconcile, submit


class AwaitedDatabase(AsyncDatabase):
    def __init__(self, session):
        super().__init__(session)
        self.executing = False
        event.listen(session, "do_orm_execute", self.require_await)

    def require_await(self, state):
        if not self.executing and (state.is_column_load or state.is_relationship_load):
            raise MissingGreenlet("Expired ORM attributes require an awaited query.")

    async def execute(self, statement):
        self.executing = True
        try:
            return await super().execute(statement)
        finally:
            self.executing = False


@pytest.fixture
def constrained_database(database):
    with database.connect() as connection:
        connection.exec_driver_sql("PRAGMA foreign_keys=ON")
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 1
        connection.commit()
    return database


@pytest.fixture
def constrained_client(constrained_database):
    api = client(constrained_database)
    with Session(constrained_database, expire_on_commit=False) as session:
        journal = service.Journal(AwaitedDatabase(session))
        app.dependency_overrides[wayang.get_journal] = lambda: journal
        yield api


@pytest.fixture
def fenced_driver(monkeypatch, continuous_driver):
    state = continuous_driver
    original = service.http_client
    token = secrets.token_hex(32)
    state.update(epoch=1, operations={}, authorized=[], replacement_output="tsx.test.replacement.v2")
    monkeypatch.setattr(settings, "DAG_RUN_TOKEN", token)

    async def handler(request):
        state["authorized"].append(request.headers.get("X-OpenTSx-Run-Token") == token)
        path = request.url.path
        if path == "/v1/capabilities":
            state["calls"].append((path, request.content))
            return httpx.Response(200, json={"schema": "opentsx.local-rollout-capabilities.v1", "profile": "bounded",
                "profiles": ["bounded", "continuous"], "operators": ["convert.unit"], "workers": [{"node": "worker-1"}],
                "publicationFencing": True, "switchModes": ["resume", "restart"]})
        if path in ("/v1/switch", "/v1/retire"):
            state["calls"].append((path, request.content))
            body = json.loads(request.content)
            if path == "/v1/retire" and state.get("beforeRetire"):
                state["beforeRetire"](body)
            if state.get("unresolved"):
                return httpx.Response(503, json={"error": "Publication outcome remains uncertain."})
            operation = body["operation"]
            if operation not in state["operations"]:
                state["epoch"] += 1
                target = body.get("replacement")
                value = {"operation": operation, "publicationEpoch": str(state["epoch"]),
                         "state": "active" if path == "/v1/switch" else "retired",
                         "deployment": target["deployment"] if target else body.get("deployment", body.get("plan", {}).get("deployment"))}
                state["operations"][operation] = value
            if state.pop("loseReply", False):
                raise httpx.ConnectError("Switch response was lost.", request=request)
            value = dict(state["operations"][operation])
            if state.get("switchMutation"):
                state["switchMutation"](value)
            return httpx.Response(200, json=value)
        async with original() as base:
            response = await base.send(request)
        value = response.json()
        if path == "/v1/execute":
            value["publicationEpoch"] = str(state["epoch"])
            if state.get("epochMutation"):
                state["epochMutation"](value)
        if path == "/v1/plan" and json.loads(request.content)["dagText"] != service_test_dag():
            for unit in value["assignments"]:
                run = json.loads(unit["requestText"])
                run["sink"]["topic"] = state["replacement_output"]
                unit["requestText"] = json.dumps(run)
            if state.get("planMutation"):
                state["planMutation"](value)
        return httpx.Response(response.status_code, json=value)

    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    return state


def service_test_dag():
    from test_wayang_rust import DAG
    return DAG


def reconcile_without_autoflush(database):
    with Session(database, expire_on_commit=False, autoflush=False) as session:
        assert session.autoflush is False
        return asyncio.run(service.reconcile_once(service.Journal(AwaitedDatabase(session))))


def test_bounded_completion_and_actual_epoch_are_durable_before_retirement(constrained_database, fenced_driver):
    database = constrained_database
    api = client(database)
    admitted = submit(api).json()

    def before_retire(body):
        assert body["expectedEpoch"] == "1"
        with Session(database, autoflush=False) as session:
            saved = session.get(WayangDeployment, admitted["id"])
            assert saved.state == "running"
            assert saved.result["state"] == "completed"
            assert saved.result["planHash"] == admitted["planHash"]
            assert saved.result["publicationEpoch"] == "1"
            assert saved.publication.epoch == 1
            assert saved.publication.retired is False
            assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4

    fenced_driver["beforeRetire"] = before_retire
    assert reconcile_without_autoflush(database)
    completed = progress(api, admitted["id"])
    assert completed["state"] == "completed"
    assert completed["error"] is None
    assert completed["publicationEpoch"] == "2"
    assert completed["publicationRetired"] is True
    assert owned(database) == 0
    with Session(database) as session:
        saved = session.get(WayangDeployment, admitted["id"])
        assert saved.state == "completed"
        assert saved.result["publicationEpoch"] == "1"
        assert saved.publication.epoch == 2
        assert saved.publication.retired is True
    assert not reconcile_without_autoflush(database)


def test_bounded_lost_retirement_reply_reuses_durable_result_and_operation(constrained_database, fenced_driver):
    database = constrained_database
    api = client(database)
    admitted = submit(api).json()
    fenced_driver["loseReply"] = True
    assert reconcile_without_autoflush(database)
    pending = progress(api, admitted["id"])
    assert pending["state"] == "running"
    assert pending["publicationEpoch"] == "1"
    assert pending["publicationRetired"] is False
    assert owned(database) == 4
    first = [body for path, body in fenced_driver["calls"] if path == "/v1/retire"][-1]
    with Session(database) as session:
        saved = session.get(WayangDeployment, admitted["id"])
        result = saved.result
        operation = saved.publication.retire_operation
        assert result["state"] == "completed"
        assert result["publicationEpoch"] == "1"
        assert operation == json.loads(first)["operation"]
    assert reconcile_without_autoflush(database)
    completed = progress(api, admitted["id"])
    assert completed["state"] == "completed"
    assert completed["error"] is None
    assert completed["publicationEpoch"] == "2"
    assert completed["publicationRetired"] is True
    assert owned(database) == 0
    assert [path for path, _ in fenced_driver["calls"]].count("/v1/execute") == 1
    assert [body for path, body in fenced_driver["calls"] if path == "/v1/retire"] == [first, first]
    with Session(database) as session:
        saved = session.get(WayangDeployment, admitted["id"])
        assert saved.result == result
        assert saved.publication.retire_operation == operation
        assert saved.state == "completed"


@pytest.mark.parametrize("lose_reply", [False, True])
def test_stop_during_first_fenced_cycle_persists_completion_before_retirement(constrained_database, fenced_driver, lose_reply):
    database = constrained_database
    api = client(database)
    admitted = submit_continuous(api).json()
    stops = []
    fenced_driver["onExecute"] = lambda: stops.append(api.post(
        f"/api/v1/dags/wayang-rust/deployments/{admitted['id']}/stop").status_code)
    fenced_driver["loseReply"] = lose_reply

    def before_retire(body):
        assert body["expectedEpoch"] == "1"
        with Session(database, autoflush=False) as session:
            saved = session.get(WayangDeployment, admitted["id"])
            cycle = session.get(WayangCycle, (admitted["id"], 1))
            assert saved.state == "stopping"
            assert saved.continuous.stop_requested is True
            assert saved.continuous.completed_cycles == 1
            assert saved.continuous.totals["inputRecords"] == "12"
            assert saved.publication.epoch == 1
            assert saved.publication.retired is False
            assert saved.result["publicationEpoch"] == "1"
            assert cycle.state == "completed"
            assert cycle.result == saved.result
            assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4

    fenced_driver["beforeRetire"] = before_retire
    assert reconcile_without_autoflush(database)
    assert stops == [202]
    first = [body for path, body in fenced_driver["calls"] if path == "/v1/retire"][-1]
    if lose_reply:
        pending = progress(api, admitted["id"])
        assert pending["state"] == "stopping"
        assert pending["error"]
        assert pending["publicationEpoch"] == "1"
        assert pending["publicationRetired"] is False
        assert pending["completedCycles"] == "1"
        assert pending["inputRecords"] == "12"
        assert owned(database) == 4
        assert reconcile_without_autoflush(database)
    stopped = progress(api, admitted["id"])
    assert stopped["state"] == "stopped"
    assert stopped["error"] is None
    assert stopped["publicationEpoch"] == "2"
    assert stopped["publicationRetired"] is True
    assert stopped["completedCycles"] == "1"
    assert stopped["inputRecords"] == "12"
    assert owned(database) == 0
    assert [path for path, _ in fenced_driver["calls"]].count("/v1/execute") == 1
    assert [body for path, body in fenced_driver["calls"] if path == "/v1/retire"] == [first] * (2 if lose_reply else 1)
    with Session(database) as session:
        saved = session.get(WayangDeployment, admitted["id"])
        assert saved.state == "stopped"
        assert saved.publication.retire_operation == json.loads(first)["operation"]
        assert session.get(WayangCycle, (admitted["id"], 1)).state == "completed"
    assert not reconcile_without_autoflush(database)


@pytest.mark.parametrize("invalid", ["plan", "epoch", "shape"])
def test_bounded_retirement_retry_validates_the_durable_result_before_remote_calls(constrained_database, fenced_driver, invalid):
    database = constrained_database
    api = client(database)
    admitted = submit(api).json()
    fenced_driver["loseReply"] = True
    assert reconcile(database)
    with Session(database) as session:
        saved = session.get(WayangDeployment, admitted["id"])
        value = dict(saved.result)
        if invalid == "plan":
            value["planHash"] = "f" * 64
        elif invalid == "epoch":
            value["publicationEpoch"] = "2"
        else:
            value = []
        saved.result = value
        session.commit()
    calls = len(fenced_driver["calls"])
    assert reconcile_without_autoflush(database)
    pending = progress(api, admitted["id"])
    assert pending["state"] == "running"
    assert pending["error"]
    assert pending["publicationRetired"] is False
    assert owned(database) == 4
    assert len(fenced_driver["calls"]) == calls


def test_resume_switch_is_owner_authenticated_and_keeps_the_verified_checkpoint(database, fenced_driver):
    api = client(database)
    admitted = submit_continuous(api).json()
    assert reconcile(database)
    previous = progress(api, admitted["id"])
    response = api.post(f"/api/v1/dags/wayang-rust/deployments/{admitted['id']}/replace",
                        json={"expectedEpoch": "1", "mode": "resume"})
    assert response.status_code == 202, response.text
    pending = response.json()
    assert pending["state"] == "switching"
    assert pending["publicationEpoch"] == "1"
    operation = pending["switch"]["operation"]
    with Session(database) as session:
        saved = session.get(WayangSwitch, operation)
        assert saved.state == "pending"
        assert saved.expected_epoch == 1
    assert reconcile(database)
    active = progress(api, admitted["id"])
    assert active["state"] == "idle"
    assert active["publicationEpoch"] == "2"
    assert active["switch"]["operation"] == operation
    assert active["switch"]["state"] == "active"
    for key in ("id", "specHash", "planHash", "createdAtMicros", "assignments", "currentCycle", "completedCycles", "inputRecords", "outputRecords"):
        assert active[key] == previous[key]
    assert owned(database) == 4
    calls = len(fenced_driver["calls"])
    repeated = api.post(f"/api/v1/dags/wayang-rust/deployments/{admitted['id']}/replace",
                        json={"expectedEpoch": "1", "mode": "resume"})
    assert repeated.status_code == 202
    assert repeated.json()["switch"] == active["switch"]
    assert len(fenced_driver["calls"]) == calls
    assert all(fenced_driver["authorized"])
    fenced_driver["ready"] = True
    tick(fenced_driver)
    assert reconcile(database)
    assert reconcile(database)
    continued = progress(api, admitted["id"])
    assert continued["publicationEpoch"] == "2"
    assert continued["inputRecords"] == "24"
    assert all(position["next"] == "4" for assignment in continued["assignments"] for position in assignment["positions"])


def ready(database):
    api = client(database)
    row = submit_continuous(api).json()
    assert reconcile(database)
    return api, row


def request_switch(api, row, mode="resume", **values):
    return api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/replace",
                    json={"expectedEpoch": "1", "mode": mode, **values})


def test_fenced_admission_adopts_the_actual_gateway_epoch_and_exposes_capabilities(database, fenced_driver):
    fenced_driver["epoch"] = 7
    api = client(database)
    row = submit_continuous(api).json()
    assert row["publicationEpoch"] == "0"
    assert reconcile(database)
    assert progress(api, row["id"])["publicationEpoch"] == "7"
    assert request_switch(api, row).status_code == 409
    assert request_switch(api, row, expectedEpoch="7").status_code == 202
    assert reconcile(database)
    assert progress(api, row["id"])["publicationEpoch"] == "8"
    capabilities = api.get("/api/v1/dags/wayang-rust/capabilities").json()
    assert capabilities["publicationFencing"] is True
    assert capabilities["switchModes"] == ["resume", "restart"]


@pytest.mark.parametrize("phase,sequence", [("onNext", "1"), ("onExecute", "2")])
def test_switch_during_remote_work_uses_only_the_last_verified_cycle(database, fenced_driver, phase, sequence):
    api, row = ready(database)
    fenced_driver["ready"] = True
    tick(fenced_driver)
    if phase == "onExecute":
        assert reconcile(database)
        assert progress(api, row["id"])["state"] == "queued"
    responses = []
    fenced_driver[phase] = lambda: responses.append(request_switch(api, row).status_code)
    assert reconcile(database)
    assert responses == [202]
    pending = progress(api, row["id"])
    assert pending["state"] == "switching"
    assert pending["currentCycle"] == sequence
    assert pending["completedCycles"] == sequence
    assert not any(path == "/v1/switch" for path, _ in fenced_driver["calls"])
    assert reconcile(database)
    active = progress(api, row["id"])
    assert active["publicationEpoch"] == "2"
    assert active["currentCycle"] == sequence
    body = [json.loads(body) for path, body in fenced_driver["calls"] if path == "/v1/switch"][-1]
    assert body["plan"]["sequence"] == sequence
    assert body["result"]["planHash"] == body["plan"]["planHash"]
    assert [path for path, _ in fenced_driver["calls"]].count("/v1/execute") == int(sequence)


def test_switch_is_owner_scoped_and_cannot_start_before_the_first_verified_cycle(database, fenced_driver):
    api = client(database)
    row = submit_continuous(api).json()
    assert request_switch(api, row).status_code == 409
    assert reconcile(database)
    assert request_switch(client(database, OTHER), row).status_code == 404
    assert request_switch(client(database, PLAIN), row).status_code == 403
    assert owned(database) == 4
    assert not any(path == "/v1/switch" for path, _ in fenced_driver["calls"])


@pytest.mark.parametrize("values", [
    {"expectedEpoch": "0"}, {"expectedEpoch": "01"}, {"expectedEpoch": True},
    {"expectedEpoch": "9223372036854775808"}, {"mode": "unknown"},
    {"flowId": 1}, {"dagText": "changed"}, {"mode": "restart"}, {"extra": 1},
])
def test_switch_rejects_invalid_boundary_values_without_touching_authority(database, fenced_driver, values):
    api, row = ready(database)
    assert request_switch(api, row, **values).status_code == 422
    assert progress(api, row["id"])["publicationEpoch"] == "1"
    assert owned(database) == 4


def test_lost_switch_response_retries_the_identical_persisted_operation_after_restart(database, fenced_driver):
    api, row = ready(database)
    accepted = request_switch(api, row).json()
    fenced_driver["loseReply"] = True
    assert reconcile(database)
    pending = progress(api, row["id"])
    assert pending["state"] == "switching"
    assert pending["publicationEpoch"] == "1"
    assert owned(database) == 4
    first = [body for path, body in fenced_driver["calls"] if path == "/v1/switch"][-1]
    assert reconcile(database)
    second = [body for path, body in fenced_driver["calls"] if path == "/v1/switch"][-1]
    assert first == second
    assert progress(api, row["id"])["publicationEpoch"] == "2"
    assert len(fenced_driver["operations"]) == 1
    assert accepted["switch"]["operation"] == json.loads(second)["operation"]


def test_unresolved_publication_keeps_the_switch_and_all_topic_owners(database, fenced_driver):
    api, row = ready(database)
    assert request_switch(api, row).status_code == 202
    fenced_driver["unresolved"] = True
    assert reconcile(database)
    assert reconcile(database)
    pending = progress(api, row["id"])
    assert pending["state"] == "switching"
    assert pending["switch"]["state"] == "pending"
    assert pending["switch"]["error"]
    assert pending["publicationEpoch"] == "1"
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").status_code == 409
    assert owned(database) == 4
    fenced_driver["unresolved"] = False
    assert reconcile(database)
    assert progress(api, row["id"])["publicationEpoch"] == "2"


def test_changed_dag_restart_reserves_new_output_and_transfers_whole_topics_after_installation(constrained_database, constrained_client, fenced_driver):
    database, api = constrained_database, constrained_client
    row = submit_continuous(api).json()
    assert reconcile(database)
    text = service_test_dag().replace("dag: test", "dag: replacement")
    response = request_switch(api, row, mode="restart", flowId=1, dagText=text)
    assert response.status_code == 202, response.text
    target = response.json()["switch"]["deployment"]
    assert target != row["id"]
    waiting = progress(api, target)
    assert waiting["state"] == "waiting"
    assert waiting["inputRecords"] == "0"
    with Session(database) as session:
        cycle = session.get(WayangCycle, (target, 1))
        assert cycle is not None
        assert cycle.state == "queued"
        assert session.get(WayangPublication, target).operation_id == response.json()["switch"]["operation"]
        owners = {owner.topic: owner.deployment_id for owner in session.execute(select(WayangTopicOwner)).scalars()}
        assert owners["tsx.test.output.v2"] == row["id"]
        assert owners["tsx.test.replacement.v2"] == target
        assert all(owners[f"tsx.test.input{number}.v2"] == row["id"] for number in range(1, 4))
    assert reconcile(database)
    old = progress(api, row["id"])
    assert old["state"] == "replaced"
    assert old["publicationRetired"] is True
    replacement = progress(api, target)
    assert replacement["state"] == "queued"
    assert replacement["publicationEpoch"] == "2"
    assert replacement["specHash"] == service.digest(text)
    assert replacement["currentCycle"] == "1"
    with Session(database) as session:
        owners = {owner.topic: owner.deployment_id for owner in session.execute(select(WayangTopicOwner)).scalars()}
        assert "tsx.test.output.v2" not in owners
        assert set(owners.values()) == {target}
    assert reconcile(database)
    active = progress(api, target)
    assert active["state"] == "idle"
    assert active["inputRecords"] == "12"
    assert active["publicationEpoch"] == "2"
    assert all(position["next"] == "2" for assignment in active["assignments"] for position in assignment["positions"])
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").status_code == 409


def test_restart_requires_changed_source_distinct_output_and_owned_flow(database, fenced_driver):
    api, row = ready(database)
    assert request_switch(api, row, mode="restart", flowId=1, dagText=service_test_dag()).status_code == 409
    text = service_test_dag().replace("dag: test", "dag: replacement")
    assert request_switch(api, row, mode="restart", flowId=2, dagText=text).status_code == 404
    fenced_driver["replacement_output"] = "tsx.test.output.v2"
    assert request_switch(api, row, mode="restart", flowId=1, dagText=text).status_code == 409
    assert owned(database) == 4
    with Session(database) as session:
        assert session.execute(select(WayangSwitch)).scalars().all() == []


def test_restart_ownership_failure_keeps_every_remaining_topic_and_switch_pending(database, fenced_driver):
    api, row = ready(database)
    text = service_test_dag().replace("dag: test", "dag: replacement")
    response = request_switch(api, row, mode="restart", flowId=1, dagText=text)
    assert response.status_code == 202
    target = response.json()["switch"]["deployment"]
    with Session(database) as session:
        reservation = session.get(WayangTopicOwner, "tsx.test.replacement.v2")
        session.delete(reservation)
        session.commit()
    assert reconcile(database)
    pending = progress(api, row["id"])
    assert pending["state"] == "switching"
    assert pending["switch"]["state"] == "pending"
    assert pending["publicationEpoch"] == "1"
    assert progress(api, target)["state"] == "waiting"
    with Session(database) as session:
        owners = session.execute(select(WayangTopicOwner)).scalars().all()
        assert len(owners) == 4
        assert all(owner.deployment_id == row["id"] for owner in owners)


def test_stop_during_restart_preflight_prevents_switch_intent(database, fenced_driver):
    api, row = ready(database)
    fenced_driver["planMutation"] = lambda _: api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop")
    text = service_test_dag().replace("dag: test", "dag: replacement")
    assert request_switch(api, row, mode="restart", flowId=1, dagText=text).status_code == 409
    assert progress(api, row["id"])["state"] == "stopping"
    with Session(database) as session:
        assert session.execute(select(WayangSwitch)).scalars().all() == []
    assert owned(database) == 4


def test_restart_source_identity_conflict_returns_409_without_partial_intent(constrained_database, constrained_client, fenced_driver):
    database, api = constrained_database, constrained_client
    row = submit_continuous(api).json()
    assert reconcile(database)
    text = service_test_dag().replace("dag: test", "dag: replacement")
    with Session(database) as session:
        previous = session.get(WayangDeployment, row["id"])
        session.add(WayangDeployment(id="existing-source", owner_id=previous.owner_id, flow_id=previous.flow_id,
                    spec_hash=service.digest(text), dag_text=text, created_at_micros=previous.created_at_micros,
                    plan_hash=previous.plan_hash, plan_text=previous.plan_text, state="completed"))
        session.commit()
    response = request_switch(api, row, mode="restart", flowId=1, dagText=text)
    assert response.status_code == 409
    assert progress(api, row["id"])["state"] == "idle"
    assert owned(database) == 4
    with Session(database) as session:
        assert session.execute(select(WayangSwitch)).scalars().all() == []
        session.delete(session.get(WayangDeployment, "existing-source"))
        session.commit()
    recovered = request_switch(api, row, mode="restart", flowId=1, dagText=text)
    assert recovered.status_code == 202, recovered.text
    operation = recovered.json()["switch"]["operation"]
    repeated = request_switch(api, row, mode="restart", flowId=1, dagText=text)
    assert repeated.status_code == 202
    assert repeated.json()["switch"]["operation"] == operation
    with Session(database) as session:
        assert len(session.execute(select(WayangSwitch)).scalars().all()) == 1


def test_failed_draining_cycle_retains_ownership_and_exposes_both_handles_for_retirement(database, fenced_driver):
    api, row = ready(database)
    fenced_driver["ready"] = True
    tick(fenced_driver)
    assert reconcile(database)
    assert progress(api, row["id"])["state"] == "queued"
    text = service_test_dag().replace("dag: test", "dag: replacement")
    response = request_switch(api, row, mode="restart", flowId=1, dagText=text)
    assert response.status_code == 202
    target = response.json()["switch"]["deployment"]
    fenced_driver["execute"] = "failed"
    assert reconcile(database)
    failed = progress(api, row["id"])
    assert failed["state"] == "failed"
    assert failed["publicationEpoch"] == "1"
    assert failed["switch"]["state"] == "failed"
    assert progress(api, target)["state"] == "failed"
    assert owned(database) == 5
    assert not any(path == "/v1/switch" for path, _ in fenced_driver["calls"])
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").status_code == 202
    assert reconcile(database)
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{target}/stop").status_code == 202
    assert reconcile(database)
    assert owned(database) == 0


def test_stop_revokes_before_releasing_topics_and_retries_unknown_retirement(database, fenced_driver):
    api, row = ready(database)
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").status_code == 202
    fenced_driver["unresolved"] = True
    assert reconcile(database)
    assert progress(api, row["id"])["state"] == "stopping"
    assert owned(database) == 4
    first = [body for path, body in fenced_driver["calls"] if path == "/v1/retire"][-1]
    fenced_driver["unresolved"] = False
    assert reconcile(database)
    assert [body for path, body in fenced_driver["calls"] if path == "/v1/retire"][-1] == first
    stopped = progress(api, row["id"])
    assert stopped["state"] == "stopped"
    assert stopped["publicationRetired"] is True
    assert stopped["publicationEpoch"] == "2"
    assert owned(database) == 0
    assert request_switch(api, row, expectedEpoch="2").status_code == 409


def test_an_undispatched_fenced_deployment_retires_with_an_unallocated_epoch(database, fenced_driver):
    api = client(database)
    row = submit_continuous(api).json()
    assert row["publicationEpoch"] == "0"
    assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").status_code == 202
    assert reconcile(database)
    body = [json.loads(body) for path, body in fenced_driver["calls"] if path == "/v1/retire"][-1]
    assert body["expectedEpoch"] == "0"
    assert not any(path == "/v1/execute" for path, _ in fenced_driver["calls"])
    assert progress(api, row["id"])["publicationRetired"] is True
    assert owned(database) == 0


@pytest.mark.parametrize("stop", [False, True])
def test_legacy_rows_persist_publication_authority_before_fenced_dispatch_or_retirement(database, fenced_driver, stop):
    api = client(database)
    row = submit_continuous(api).json()
    with Session(database) as session:
        session.delete(session.get(WayangPublication, row["id"]))
        session.commit()
    if stop:
        assert api.post(f"/api/v1/dags/wayang-rust/deployments/{row['id']}/stop").status_code == 202
    else:
        fenced_driver["epoch"] = 7

        def publication_before_execute():
            with Session(database) as session:
                publication = session.get(WayangPublication, row["id"])
                assert publication is not None
                assert publication.epoch == 0

        fenced_driver["onExecute"] = publication_before_execute
    assert reconcile(database)
    value = progress(api, row["id"])
    assert value["publicationEpoch"] == ("2" if stop else "7")
    assert value["state"] == ("stopped" if stop else "idle")
    assert owned(database) == (0 if stop else 4)
    if stop:
        body = [json.loads(body) for path, body in fenced_driver["calls"] if path == "/v1/retire"][-1]
        assert body["expectedEpoch"] == "0"
        assert not any(path == "/v1/execute" for path, _ in fenced_driver["calls"])


@pytest.mark.parametrize("mutation", [
    lambda result: result.update(publicationEpoch="1"),
    lambda result: result.update(publicationEpoch=True),
    lambda result: result.update(deployment="another-deployment"),
    lambda result: result.update(state="retired"),
    lambda result: result.update(unexpected="field"),
])
def test_invalid_gateway_switch_reply_cannot_advance_public_authority(database, fenced_driver, mutation):
    api, row = ready(database)
    assert request_switch(api, row).status_code == 202
    fenced_driver["switchMutation"] = mutation
    assert reconcile(database)
    assert progress(api, row["id"])["publicationEpoch"] == "1"
    assert progress(api, row["id"])["state"] == "switching"
    assert owned(database) == 4
    fenced_driver["switchMutation"] = None
    assert reconcile(database)
    assert progress(api, row["id"])["publicationEpoch"] == "2"
