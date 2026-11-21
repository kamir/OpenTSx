import asyncio
import json
from pathlib import Path

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.wayang import WayangDeployment, WayangTopicOwner
from app.db.upgrade_wayang_ownership import TABLES
from app.services import wayang_contract as contract
from app.services import wayang_service as service
from test_wayang_rust import DAG, cleanup, client, database, driver, plan_for
from test_wayang_continuous import continuous_driver


DEFAULT_ADMISSION_FACTS = contract.admission_facts


def test_legacy_plan_hash_is_recomputed_before_journal_accept(database, driver, monkeypatch):
    def changed(data):
        plan = plan_for(data)
        plan["planHash"] = "f" * 64
        return plan

    monkeypatch.setattr("test_wayang_rust.plan_for", changed)
    response = client(database).post("/api/v1/dags/wayang-rust/deploy", json={"flowId": 1, "dagText": DAG})
    assert response.status_code == 502
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


def test_driver_located_refusal_is_preserved_without_untrusted_error(database, driver, monkeypatch):
    refusal = {"schema": "opentsx.plan-refusal.v1", "code": "deployment.incarnation", "node": "source",
               "field": "/ownership/0/incarnation", "message": "Physical deployment refused: deployment.incarnation."}

    def handler(request):
        return httpx.Response(400, json={"error": "http://private-worker/input", "refusal": refusal})

    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    response = client(database).post("/api/v1/dags/wayang-rust/deploy", json={"flowId": 1, "dagText": DAG})
    assert response.status_code == 400
    assert response.json()["detail"] == refusal
    assert "private-worker" not in response.text
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []


def test_physical_profile_reaches_driver_without_mutation_when_facts_are_unavailable(database, driver, monkeypatch):
    refusal = {"schema": "opentsx.plan-refusal.v1", "code": "deployment.incarnation", "node": None,
               "field": "/ownership", "message": "Physical deployment refused: deployment.incarnation."}

    def handler(request):
        assert json.loads(request.content)["profile"] == "physical"
        return httpx.Response(400, json={"error": refusal["message"], "refusal": refusal})

    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    response = client(database).post("/api/v1/dags/wayang-rust/deploy",
                                    json={"flowId": 1, "dagText": DAG, "profile": "physical"})
    assert response.status_code == 400
    assert response.json()["detail"] == refusal
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


def legacy_journal(database):
    from sqlalchemy import text
    with database.begin() as connection:
        connection.execute(text("DROP TABLE wayang_topic_owners"))
        connection.execute(text("CREATE TABLE wayang_topic_owners (topic VARCHAR(249) PRIMARY KEY, "
                                "deployment_id VARCHAR(63) NOT NULL REFERENCES wayang_deployments(id))"))


def test_offline_upgrade_preserves_terminal_plan_and_result_bytes(database):
    from sqlalchemy import text
    from app.db.upgrade_wayang_ownership import check_schema, upgrade
    legacy_journal(database)
    with Session(database) as session:
        plan = plan_for({"deployment": "history", "dagText": DAG, "createdAtMicros": "42"})
        session.add(WayangDeployment(id="history", owner_id=1, flow_id=1, spec_hash=service.digest(DAG),
                    dag_text=DAG, created_at_micros="42", plan_hash=plan["planHash"], plan_text=json.dumps(plan, indent=2),
                    state="completed", result={"receipt": "retained"}))
        session.commit()
    with database.connect() as connection:
        before = tuple(connection.execute(text("SELECT * FROM wayang_deployments")).first())
        with pytest.raises(RuntimeError, match="explicit offline upgrade"):
            check_schema(connection)
    assert upgrade(database) is True
    assert upgrade(database) is False
    with database.connect() as connection:
        check_schema(connection)
        assert tuple(connection.execute(text("SELECT * FROM wayang_deployments")).first()) == before
        assert connection.execute(text("SELECT * FROM wayang_topic_owners")).all() == []


@pytest.mark.parametrize("unsafe", ["owner", "active", "publication", "switch", "cycle", "orphan", "unknown"])
def test_offline_upgrade_refuses_ambiguous_legacy_state_without_reset(database, unsafe):
    from sqlalchemy import inspect, text
    from app.db.upgrade_wayang_ownership import upgrade
    legacy_journal(database)
    plan = plan_for({"deployment": "history", "dagText": DAG, "createdAtMicros": "42"})
    with Session(database) as session:
        session.add(WayangDeployment(id="history", owner_id=1, flow_id=1, spec_hash=service.digest(DAG),
                    dag_text=DAG, created_at_micros="42", plan_hash=plan["planHash"], plan_text=json.dumps(plan),
                    state="queued" if unsafe == "active" else "completed"))
        session.commit()
    statements = {
        "owner": "INSERT INTO wayang_topic_owners VALUES ('input','history')",
        "publication": "INSERT INTO wayang_publication (deployment_id,epoch,retired) VALUES ('history',1,false)",
        "switch": "INSERT INTO wayang_switches (id,deployment_id,expected_epoch,mode,body_hash,state,created_at,updated_at) "
                  "VALUES ('switch','history',1,'resume','hash','pending',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        "cycle": "INSERT INTO wayang_cycles (deployment_id,number,plan_hash,plan_text,state,created_at,updated_at) "
                 "VALUES ('history',1,'hash','text','running',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        "orphan": "INSERT INTO wayang_continuous (deployment_id,current_cycle,completed_cycles,stop_requested,next_poll_at,totals) "
                  "VALUES ('unknown',1,1,false,CURRENT_TIMESTAMP,'{}')",
        "unknown": "ALTER TABLE wayang_topic_owners ADD COLUMN unrecognized TEXT",
    }
    with database.begin() as connection:
        if unsafe in statements:
            connection.execute(text(statements[unsafe]))
        before = {table: connection.execute(text(f"SELECT * FROM {table}")).all()
                  for table in inspect(connection).get_table_names()}
    with pytest.raises(RuntimeError, match="refused|unknown"):
        upgrade(database)
    with database.connect() as connection:
        assert {table: connection.execute(text(f"SELECT * FROM {table}")).all()
                for table in inspect(connection).get_table_names()} == before


def test_scoped_registry_allows_replicated_fragments_and_different_incarnations(database):
    from sqlalchemy.exc import IntegrityError
    with Session(database) as session:
        plan = plan_for({"deployment": "owner", "dagText": DAG, "createdAtMicros": "42"})
        session.add(WayangDeployment(id="owner", owner_id=1, flow_id=1, spec_hash=service.digest(DAG),
                    dag_text=DAG, created_at_micros="42", plan_hash=plan["planHash"], plan_text=json.dumps(plan), state="queued"))
        session.flush()
        for fragment, cluster, incarnation in (("f1", "cluster-a", "topic-a"), ("f2", "cluster-a", "topic-a"),
                                               ("f1", "cluster-a", "topic-b"), ("f1", "cluster-b", "topic-a")):
            session.add(WayangTopicOwner(scope="physical", fragment=fragment, cluster=cluster,
                        incarnation=incarnation, topic="input", deployment_id="owner"))
        session.commit()
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 4
        session.add(WayangTopicOwner(scope="physical", fragment="f1", cluster="cluster-a", incarnation="topic-a",
                                    topic="input", deployment_id="owner"))
        with pytest.raises(IntegrityError):
            session.commit()
        session.rollback()


VECTORS = json.loads((Path(__file__).resolve().parents[2] /
                     "docs/datamodel-2026/testvectors/physical-deployment.v1.json").read_text())["cases"]


@pytest.mark.parametrize("case", VECTORS, ids=lambda case: case["name"])
def test_shared_physical_deployment_vectors(case, monkeypatch):
    if case["kind"] == "canonical":
        encoded = contract.canonical(case["input"])
        assert encoded == case["canonical"]
        assert service.digest(encoded) == case["sha256"]
    elif case["kind"] == "computation":
        encoded = contract.encode_computation(case["input"])
        assert encoded == case["canonical"]
        assert service.digest(encoded) == case["sha256"]
        assert contract.encode_computation(contract.decode_computation(encoded)) == encoded
    else:
        monkeypatch.setattr(contract, "admission_facts", lambda plan, now: case["facts"])
        def validate():
            plan = contract.parse_document(case["input"])
            identity = plan if isinstance(plan, dict) else {}
            return contract.validate(plan, identity.get("deployment"), identity.get("sourceDagText"),
                                     identity.get("createdAtMicros"), case["nowMicros"])

        if case["refusal"] is None:
            result = validate()
            plan = contract.parse_document(case["input"])
            assert len(result) == len(plan["assignmentTexts"])
        else:
            with pytest.raises(service.WayangError) as caught:
                validate()
            assert caught.value.refusal["code"] == case["refusal"]["code"]
            assert caught.value.refusal["field"] == case["refusal"]["field"]
            assert caught.value.refusal["node"] == case["refusal"]["node"]


@pytest.fixture
def physical_driver(driver, monkeypatch):
    case = next(case for case in VECTORS if case["name"] == "complete-bounded-deployment")
    template = json.loads(case["input"])
    state = {"calls": [], "unreachable": False}
    monkeypatch.setattr(contract.time, "time_ns", lambda: 150_000)
    monkeypatch.setattr(contract, "admission_facts", lambda plan, now: case["facts"])

    def handler(request):
        state["calls"].append((request.url.path, bytes(request.content)))
        data = json.loads(request.content)
        if request.url.path == "/v1/plan":
            assert data["profile"] == "physical"
            plan = json.loads(json.dumps(template))
            plan.update(deployment=data["deployment"], sourceDagText=data["dagText"],
                        specHash=service.digest(data["dagText"]), createdAtMicros=data["createdAtMicros"])
            for index, raw in enumerate(plan["assignmentTexts"]):
                assignment = json.loads(raw)
                assignment.update(deployment=plan["deployment"], sourceDagText=plan["sourceDagText"], specHash=plan["specHash"])
                plan["assignmentTexts"][index] = json.dumps(assignment, ensure_ascii=False)
            if state.get("mutation"):
                state["mutation"](plan)
            plan["planHash"] = contract.plan_hash(plan)
            return httpx.Response(200, content=json.dumps(plan, ensure_ascii=False, indent=2))
        assert request.url.path == "/v1/execute"
        if state["unreachable"]:
            raise httpx.ConnectError("fixture disconnect", request=request)
        statuses = []
        for assignment in contract.assignment_inventory(data).values():
            source = assignment["inputs"][0]
            receipt = {key: assignment[key] for key in ("artifactDigest", "deployment", "generation", "assignment", "node")}
            receipt.update(schema="opentsx.local-receipt.v1", requestHash="a" * 64, inputTopic=source["topic"],
                           outputTopic=next(item["outputTopic"] for item in contract.assignments(data)
                                            if item["node"] == assignment["node"]),
                           positions=[{"partition": item["partition"], "next": item["to"]} for item in source["positions"]],
                           inputRecords=sum(int(item["to"]) - int(item["from"]) for item in source["positions"]),
                           outputRecords=1, stateDigest="b" * 64, outputDigest="c" * 64)
            if state.get("receiptMutation"):
                state["receiptMutation"](receipt)
            statuses.append({"node": assignment["node"], "state": "completed", "requestHash": "a" * 64, "receipt": receipt})
        return httpx.Response(200, json={"deployment": data["deployment"], "planHash": data["planHash"],
                                         "state": "completed", "assignments": statuses})

    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    state["dag"] = template["sourceDagText"]
    return state


def physical_submit(api, state):
    return api.post("/api/v1/dags/wayang-rust/deploy", json={"flowId": 1, "dagText": state["dag"], "profile": "physical"})


def test_physical_admission_and_uncertain_retry_keep_exact_envelope(database, physical_driver):
    from test_wayang_rust import reconcile
    api = client(database)
    response = physical_submit(api, physical_driver)
    assert response.status_code == 202, response.text
    row = response.json()
    assert row["profile"] == "physical"
    assert "requestHash" not in row["assignments"][0]
    assert len(row["assignments"][0]["assignmentHash"]) == 64
    assert physical_submit(api, physical_driver).json() == row
    assert len(physical_driver["calls"]) == 1
    with Session(database) as session:
        saved = session.get(WayangDeployment, row["id"])
        exact = saved.plan_text.encode()
        owners = session.execute(select(WayangTopicOwner)).scalars().all()
        assert [(owner.scope, owner.fragment, owner.cluster, owner.incarnation, owner.topic) for owner in owners] == [
            ("physical", "fragment-1", "cluster-a", "topic-0", "tsx.wind.episodes.v2"),
            ("physical", "fragment-1", "cluster-a", "topic-1", "tsx.wind.clean.v2")]
    physical_driver["unreachable"] = True
    assert reconcile(database)
    assert physical_driver["calls"][-1] == ("/v1/execute", exact)
    with Session(database) as session:
        assert session.get(WayangDeployment, row["id"]).state == "running"
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 2
    physical_driver["unreachable"] = False
    assert reconcile(database)
    assert physical_driver["calls"][-1] == ("/v1/execute", exact)
    assert api.get(f"/api/v1/dags/wayang-rust/deployments/{row['id']}").json()["state"] == "completed"
    with Session(database) as session:
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


def test_physical_missing_trusted_provider_refuses_before_accept_and_retry(database, physical_driver, monkeypatch):
    from test_wayang_rust import reconcile
    original = contract.admission_facts
    monkeypatch.setattr(contract, "admission_facts", DEFAULT_ADMISSION_FACTS)
    api = client(database)
    response = physical_submit(api, physical_driver)
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "deployment.incarnation"
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []
    monkeypatch.setattr(contract, "admission_facts", original)
    row = physical_submit(api, physical_driver).json()
    monkeypatch.setattr(contract, "admission_facts", DEFAULT_ADMISSION_FACTS)
    count = len(physical_driver["calls"])
    assert physical_submit(api, physical_driver).status_code == 400
    assert reconcile(database)
    assert len(physical_driver["calls"]) == count
    with Session(database) as session:
        assert session.get(WayangDeployment, row["id"]).state == "queued"
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 2


@pytest.mark.parametrize("legacy_first", [True, False])
@pytest.mark.parametrize("topic", ["tsx.wind.episodes.v2", "tsx.wind.clean.v2"])
def test_cross_profile_topic_overlap_is_conservative(database, physical_driver, legacy_first, topic):
    from test_wayang_rust import AsyncDatabase
    api = client(database)
    legacy = plan_for({"deployment": "legacy", "dagText": DAG, "createdAtMicros": "42"})
    row = WayangDeployment(id="legacy", owner_id=1, flow_id=1, spec_hash=service.digest(DAG), dag_text=DAG,
                           created_at_micros="42", plan_hash=legacy["planHash"], plan_text=json.dumps(legacy), state="queued")
    items = service.plan_assignments(legacy)
    items[0]["inputTopic"] = topic
    if legacy_first:
        with Session(database, expire_on_commit=False) as session:
            asyncio.run(service.Journal(AsyncDatabase(session)).accept(row, items))
        assert physical_submit(api, physical_driver).status_code == 409
    else:
        assert physical_submit(api, physical_driver).status_code == 202
        with Session(database, expire_on_commit=False) as session:
            with pytest.raises(service.WayangError) as caught:
                asyncio.run(service.Journal(AsyncDatabase(session)).accept(row, items))
            assert caught.value.status == 409
    with Session(database) as session:
        assert len(session.execute(select(WayangDeployment)).scalars().all()) == 1


def test_offline_command_runs_against_disposable_fixture(database, monkeypatch, capsys):
    import runpy
    from app.db import session as db_session
    from app.db import upgrade_wayang_ownership
    legacy_journal(database)
    monkeypatch.setattr(db_session, "sync_engine", database)
    monkeypatch.setattr("sys.argv", ["app.db.upgrade_wayang_ownership", "--offline"])
    runpy.run_path(upgrade_wayang_ownership.__file__, run_name="__main__")
    assert capsys.readouterr().out == "Wayang ownership schema upgraded.\n"
    runpy.run_path(upgrade_wayang_ownership.__file__, run_name="__main__")
    assert capsys.readouterr().out == "Wayang ownership schema is current.\n"


@pytest.mark.parametrize("case", [case for case in VECTORS if case["name"] in {
    "scalar-logical-node", "nonstring-logical-node-id", "duplicate-logical-node-id", "null-assignment-inventory",
    "nonarray-placements", "nonarray-channels", "rehashed-fragment-signed-zero",
}], ids=lambda case: case["name"])
def test_physical_api_locates_malformed_contract_before_any_journal_mutation(database, physical_driver, case):
    source = json.loads(case["input"])

    def mutation(plan):
        for key in ("computationText", "computationHash", "assignmentTexts", "placements", "channels"):
            if key == "assignmentTexts" and source[key] is not None:
                if case["name"] != "rehashed-fragment-signed-zero":
                    continue
                old = json.loads(source[key][0])
                current = json.loads(plan[key][0])
                current.update(fragmentText=old["fragmentText"], fragmentHash=old["fragmentHash"])
                plan[key][0] = json.dumps(current)
            elif key in ("computationText", "computationHash") or key == "assignmentTexts" and source[key] is None:
                plan[key] = source[key]
            elif case["name"] == "nonarray-placements" and key == "placements" or case["name"] == "nonarray-channels" and key == "channels":
                plan[key] = source[key]

    physical_driver["mutation"] = mutation
    response = physical_submit(client(database), physical_driver)
    assert response.status_code == 400, response.text
    assert response.json()["detail"] == case["refusal"]
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


def test_physical_plan_collision_retains_complete_existing_input_and_sink_claims(database, physical_driver):
    api = client(database)
    assert physical_submit(api, physical_driver).status_code == 202
    physical_driver["dag"] += "\n"
    response = physical_submit(api, physical_driver)
    assert response.status_code == 409
    with Session(database) as session:
        assert len(session.execute(select(WayangDeployment)).scalars().all()) == 1
        owners = session.execute(select(WayangTopicOwner)).scalars().all()
        assert {owner.topic for owner in owners} == {"tsx.wind.episodes.v2", "tsx.wind.clean.v2"}


def test_distinct_physical_input_cannot_reuse_another_deployments_sink(database, physical_driver):
    from test_wayang_rust import AsyncDatabase
    api = client(database)
    first = physical_submit(api, physical_driver).json()
    with Session(database, expire_on_commit=False) as session:
        plan = json.loads(session.get(WayangDeployment, first["id"]).plan_text)
        plan["deployment"] = "another"
        plan["sourceDagText"] += "\n"
        plan["specHash"] = service.digest(plan["sourceDagText"])
        raw = json.loads(plan["assignmentTexts"][0])
        raw.update(deployment=plan["deployment"], sourceDagText=plan["sourceDagText"], specHash=plan["specHash"])
        raw["inputs"][0]["topic"] = "tsx.wind.other.v2"
        plan["assignmentTexts"][0] = json.dumps(raw)
        for channel in plan["channels"]:
            if channel["from"] == "$source":
                channel.update(topic="tsx.wind.other.v2", incarnation="other-incarnation")
        row = WayangDeployment(id=plan["deployment"], owner_id=1, flow_id=1, spec_hash=plan["specHash"],
                               dag_text=plan["sourceDagText"], created_at_micros=plan["createdAtMicros"],
                               plan_hash=contract.plan_hash(plan), plan_text=json.dumps(plan), state="queued")
        with pytest.raises(service.WayangError) as caught:
            asyncio.run(service.Journal(AsyncDatabase(session)).accept(row, contract.assignments(plan), "physical"))
        assert caught.value.status == 409
        assert len(session.execute(select(WayangDeployment)).scalars().all()) == 1


@pytest.mark.parametrize("field,value", [
    ("assignment", "other"), ("deployment", "other"), ("generation", "2"), ("artifactDigest", "sha256:" + "0" * 64),
    ("node", "other"), ("inputTopic", "other"), ("outputTopic", "other"),
    ("positions", [{"partition": "0", "next": "0"}]), ("inputRecords", True), ("outputRecords", -1),
    ("requestHash", "not-a-hash"), ("requestHash", "f" * 64), ("outputRecords", 10001), ("stateDigest", "not-a-hash"), ("outputDigest", "not-a-hash"),
])
def test_physical_receipt_mismatch_retains_complete_ownership(database, physical_driver, field, value):
    from test_wayang_rust import reconcile
    api = client(database)
    row = physical_submit(api, physical_driver).json()
    physical_driver["receiptMutation"] = lambda receipt: receipt.update({field: value})
    assert reconcile(database)
    result = api.get(f"/api/v1/dags/wayang-rust/deployments/{row['id']}").json()
    assert result["state"] == "running"
    assert result["error"] == "The physical worker receipt does not match the admitted assignment and positions."
    assert "requestHash" not in result["assignments"][0]
    with Session(database) as session:
        assert len(session.execute(select(WayangTopicOwner)).scalars().all()) == 2
        assert session.get(WayangDeployment, row["id"]).result is None


def test_legacy_canonical_hash_matches_executed_jackson_reference():
    assert service.legacy_plan_hash(VECTORS[0]["input"]) == "26ea2734c00307f5dfe8199fff7c16b389e20357979a923029fee068884f0674"


@pytest.mark.parametrize("raw", ["null", "[]", "7", '{"schema":"one","schema":"two"}'])
def test_physical_api_locates_invalid_outer_json_before_mutation(database, driver, monkeypatch, raw):
    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, content=raw))))
    response = client(database).post("/api/v1/dags/wayang-rust/deploy",
                                    json={"flowId": 1, "dagText": DAG, "profile": "physical"})
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == ("deployment.json" if raw.startswith("{") else "deployment.fields")
    assert response.json()["detail"]["field"] == "/"
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


def test_physical_cut_exceeding_admitted_records_refuses_before_journal(database, physical_driver):
    def mutation(plan):
        assignment = json.loads(plan["assignmentTexts"][0])
        assignment["inputs"][0]["positions"][0]["to"] = "10001"
        plan["assignmentTexts"][0] = json.dumps(assignment)

    physical_driver["mutation"] = mutation
    response = physical_submit(client(database), physical_driver)
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "deployment.limit"
    assert response.json()["detail"]["field"] == "/assignmentTexts/inputs"
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []
        assert session.execute(select(WayangTopicOwner)).scalars().all() == []


@pytest.mark.parametrize("change", [{"code": []}, {"message": "https://private-worker/input"}, {"node": "https://private-worker/input"}])
def test_invalid_driver_refusal_is_not_rendered_or_persisted(database, driver, monkeypatch, change):
    refusal = {"schema": "opentsx.plan-refusal.v1", "code": "deployment.incarnation", "node": "source",
               "field": "/facts/topics", "message": "Physical deployment refused: deployment.incarnation.", **change}
    monkeypatch.setattr(service, "http_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(400, json={"error": "https://private-worker/input", "refusal": refusal}))))
    response = client(database).post("/api/v1/dags/wayang-rust/deploy", json={"flowId": 1, "dagText": DAG})
    assert response.status_code == 502
    assert response.json()["detail"] == "The Wayang service returned an invalid located refusal."
    assert "private-worker" not in response.text
    with Session(database) as session:
        assert session.execute(select(WayangDeployment)).scalars().all() == []


def test_computation_encoding_has_an_explicit_version_frame():
    encoded = contract.encode_computation({"factor": -0.0})
    frame = json.loads(encoded)
    assert len(frame) == 2
    assert frame[0] == "opentsx.computation.v1"
    assert contract.encode_computation(contract.decode_computation(encoded)) == encoded


def test_unversioned_computation_encoding_is_refused():
    with pytest.raises(service.WayangError) as caught:
        contract.decode_computation('["f64","8000000000000000"]')
    assert caught.value.refusal["code"] == "deployment.computation"
    assert caught.value.refusal["field"] == "/computationText"


@pytest.mark.parametrize("missing_table", TABLES)
def test_startup_refuses_every_partial_active_journal(database, continuous_driver, missing_table):
    from sqlalchemy import inspect, text
    from app.db.base import Base
    from app.db.upgrade_wayang_ownership import check_schema
    response = client(database).post("/api/v1/dags/wayang-rust/deploy",
                                    json={"flowId": 1, "dagText": DAG, "profile": "continuous"})
    assert response.status_code == 202, response.text
    assert response.json()["state"] == "queued"
    with database.begin() as connection:
        assert connection.execute(text("SELECT count(*) FROM wayang_cycles")).scalar_one() == 1
        assert connection.execute(text("SELECT count(*) FROM wayang_topic_owners")).scalar_one() == 4
        before = {table: connection.execute(text(f"SELECT * FROM {table}")).all()
                  for table in TABLES if table != missing_table}
        connection.execute(text(f"DROP TABLE {missing_table}"))
    with pytest.raises(RuntimeError, match="explicit offline upgrade"):
        with database.begin() as connection:
            check_schema(connection)
            Base.metadata.create_all(connection)
    with database.connect() as connection:
        assert missing_table not in inspect(connection).get_table_names()
        assert {table: connection.execute(text(f"SELECT * FROM {table}")).all()
                for table in TABLES if table != missing_table} == before


@pytest.mark.parametrize("unrelated_table", [False, True])
def test_startup_creates_a_genuinely_new_wayang_journal(unrelated_table):
    from sqlalchemy import create_engine, inspect, text
    from app.db.base import Base
    from app.db.upgrade_wayang_ownership import TABLES, check_schema
    engine = create_engine("sqlite://")
    try:
        with engine.begin() as connection:
            if unrelated_table:
                connection.execute(text("CREATE TABLE existing_application (id INTEGER PRIMARY KEY)"))
            check_schema(connection)
            Base.metadata.create_all(connection)
            assert set(TABLES).issubset(inspect(connection).get_table_names())
            assert connection.execute(text("SELECT count(*) FROM wayang_topic_owners")).scalar_one() == 0
    finally:
        engine.dispose()
