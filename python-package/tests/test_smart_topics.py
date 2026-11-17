import importlib
from types import SimpleNamespace

import pytest

from opentsx.client.lifecycle import LifecycleStore, encoded

PREFIX = "tsx.workbench-demo."
INPUT = PREFIX + "forecast.episodes.v2"
MODEL_ID = "a" * 64


def api():
    return importlib.import_module("opentsx.client.smart_topics")


def test_manager_exposes_the_local_worker_lifecycle():
    manager = api().SmartTopics
    for name in ("recover", "deploy", "start", "stop", "deployments", "close"):
        assert callable(getattr(manager, name))


def test_public_checkpoint_exposes_context_cursor_without_values():
    value = api().public_deployment(
        {
            "checkpoint": {
                "context": {
                    "topic": "a.context.v1",
                    "partition": 0,
                    "next_offset": 2,
                    "schema_id": "a" * 64,
                    "watermark_micros": 100,
                    "rows": [{"timestamp_micros": 100, "values": [3]}],
                }
            }
        }
    )
    context = value["checkpoint"]["context"]
    assert context["next_offset"] == 2
    assert context["buffered_rows"] == 1
    assert "rows" not in context


def model():
    source = {
        "series_id": "series-a",
        "unit": "MW",
        "time_kind": "absolute",
        "interval_micros": 1000000,
    }
    return {
        "id": MODEL_ID,
        "name": "forecast",
        "artifact_id": "b" * 64,
        "version": "1",
        "source": source,
        "artifact": {"artifact_id": "b" * 64, "source": source, "context_names": []},
        "status": "registered",
        "review": {
            "accepted": True,
            "note": "Reviewed held-out predictions",
            "minimum_improvement": 0,
        },
    }


def document(name="forecast"):
    return {
        "model_id": MODEL_ID,
        "input_topic": INPUT,
        "input_partition": 0,
        "output_topic": PREFIX + "smart." + name + ".predictions.v1",
    }


def record(name="forecast", desired="stopped"):
    return {
        "id": name,
        "model_id": MODEL_ID,
        "series_id": "series-a",
        "input_topic": INPUT,
        "input_partition": 0,
        "output_topic": PREFIX + "smart." + name + ".predictions.v1",
        "desired_state": desired,
        "status": "stopped",
        "error": None,
    }


class Event:
    def __init__(self):
        self.set_count = 0

    def set(self):
        self.set_count += 1


class Process:
    def __init__(self, context, **options):
        self.context, self.options = context, options
        self.event = options["args"][-1]
        self.alive = self.closed = self.terminated = False
        self.pid = None
        self.exitcode = None
        self.joins = []
        self.stop_on_join = True
        self.stop_on_terminate = True

    def start(self):
        self.context.events.append("process.start")
        if self.context.fail_start:
            raise OSError("spawn unavailable")
        self.pid, self.alive = 123, True

    def is_alive(self):
        if self.closed:
            raise ValueError("process is closed")
        return self.alive

    def join(self, timeout):
        assert self.pid is not None
        self.joins.append(timeout)
        if self.event.set_count and self.stop_on_join:
            self.alive, self.exitcode = False, 0

    def terminate(self):
        self.terminated = True
        if self.stop_on_terminate:
            self.alive, self.exitcode = False, -15

    def close(self):
        assert not self.alive
        self.closed = True


class Context:
    def __init__(self, events):
        self.events = events
        self.processes = []
        self.fail_start = False

    def Event(self):
        return Event()

    def Process(self, **options):
        process = Process(self, **options)
        self.processes.append(process)
        return process


class Admin:
    def __init__(self, events):
        self.events, self.topics = events, {
            INPUT: SimpleNamespace(error=None, partitions={0: None})
        }
        self.created = []
        self.create_failure = None
        self.before_create = None

    def list_topics(self, timeout):
        self.events.append("metadata")
        return SimpleNamespace(topics=self.topics.copy())

    def create_topics(self, topics, request_timeout):
        self.events.append("create_topics")
        self.created.extend(topics)
        if self.before_create:
            self.before_create()
        for topic in topics:
            assert (topic.num_partitions, topic.replication_factor) == (1, 1)
            self.topics[topic.topic] = SimpleNamespace(error=None)

        def result(timeout):
            if self.create_failure:
                raise self.create_failure

        return {topic.topic: SimpleNamespace(result=result) for topic in topics}


@pytest.fixture
def runtime(tmp_path, monkeypatch):
    import confluent_kafka.admin
    import opentsx.algorithms.fitted

    events = []
    store = LifecycleStore(tmp_path / "lifecycle.sqlite")

    def save_model(value):
        with store.connection() as connection:
            connection.execute(
                "INSERT OR REPLACE INTO models VALUES(?,?)",
                (value["id"], encoded(value)),
            )

    save_model(model())
    admin = Admin(events)
    monkeypatch.setattr(confluent_kafka.admin, "AdminClient", lambda config: admin)
    monkeypatch.setattr(
        confluent_kafka.admin,
        "NewTopic",
        lambda topic, **kwargs: SimpleNamespace(topic=topic, **kwargs),
    )

    def validate(artifact):
        events.append("artifact.validate")
        if artifact.get("invalid"):
            raise ValueError("artifact integrity mismatch")

    monkeypatch.setattr(opentsx.algorithms.fitted, "validate_artifact", validate)

    class Registry:
        failure = None

        def verify(self, value):
            events.append("registry.verify")
            if self.failure:
                raise self.failure

        def promote(self, value):
            events.append("registry.promote")

    class Catalogue:
        failure = None

        def verify_binding(self, value, topic, partition):
            events.append("catalogue.verify")
            if self.failure:
                raise self.failure
            assert (topic, partition) == (INPUT, 0)
            return {"series_id": "series-a", "verified": True}

    registry, catalogue = Registry(), Catalogue()
    context = Context(events)
    manager = api().SmartTopics(store, registry, catalogue, "broker:9092", PREFIX)
    manager.context = context
    yield SimpleNamespace(
        manager=manager,
        store=store,
        registry=registry,
        catalogue=catalogue,
        admin=admin,
        context=context,
        events=events,
        save_model=save_model,
    )
    for process in context.processes:
        process.alive = False
        process.stop_on_join = process.stop_on_terminate = True
    if not manager.owner.closed:
        manager.close()


def test_database_owner_is_exclusive_and_released_on_close(runtime):
    m = runtime.manager
    with pytest.raises(RuntimeError, match="another workbench"):
        api().SmartTopics(runtime.store, runtime.registry, runtime.catalogue, "broker:9092", PREFIX)
    m.close()
    reopened = api().SmartTopics(
        runtime.store, runtime.registry, runtime.catalogue, "broker:9092", PREFIX
    )
    reopened.close()


def test_reserves_the_binding_durably_before_creating_its_output_topic(runtime):
    def check_reservation():
        reopened = LifecycleStore(runtime.store.path)
        assert reopened.get_deployment("forecast")["output_topic"] == document()["output_topic"]

    runtime.admin.before_create = check_reservation
    answer = runtime.manager.deploy(document())
    assert answer["status"] == "starting"
    assert answer["desired_state"] == "running"
    assert (
        runtime.events.index("catalogue.verify")
        < runtime.events.index("metadata")
        < runtime.events.index("create_topics")
    )
    assert runtime.events.index("registry.verify") < runtime.events.index("process.start")


def test_retry_after_uncertain_topic_creation_uses_the_durable_reservation(runtime):
    runtime.admin.create_failure = TimeoutError("topic acknowledgement was lost")
    with pytest.raises(TimeoutError, match="acknowledgement"):
        runtime.manager.deploy(document())
    assert runtime.store.get_deployment("forecast")["desired_state"] == "stopped"
    assert runtime.context.processes == []
    runtime.admin.create_failure = None
    answer = runtime.manager.deploy(document())
    assert answer["status"] == "starting"
    assert runtime.events.count("create_topics") == 1


def test_new_local_prediction_topic_requires_one_in_sync_replica(runtime):
    runtime.manager.deploy(document())
    assert len(runtime.admin.created) == 1
    topic = runtime.admin.created[0]
    assert (topic.num_partitions, topic.replication_factor) == (1, 1)
    assert topic.config == {"min.insync.replicas": "1"}


@pytest.mark.parametrize("unavailable", [False, True])
def test_foreign_or_unavailable_output_is_not_reserved_or_adopted(runtime, unavailable):
    runtime.admin.topics[document()["output_topic"]] = SimpleNamespace(
        error="unavailable" if unavailable else None
    )
    with pytest.raises(ValueError, match="outside this deployment|unavailable"):
        runtime.manager.deploy(document())
    assert runtime.store.list_deployments() == []
    assert "create_topics" not in runtime.events
    assert runtime.context.processes == []


def test_source_preflight_failure_happens_before_output_mutation(runtime):
    runtime.catalogue.failure = ValueError("training values are absent")
    with pytest.raises(ValueError, match="training values"):
        runtime.manager.deploy(document())
    assert "metadata" not in runtime.events
    assert "create_topics" not in runtime.events
    assert runtime.store.list_deployments() == []


def test_database_reservation_failure_prevents_topic_creation(runtime, monkeypatch):
    def fail_reservation(record):
        raise OSError("state storage unavailable")

    monkeypatch.setattr(runtime.store, "add_deployment", fail_reservation)
    with pytest.raises(OSError, match="state storage"):
        runtime.manager.deploy(document())
    assert "metadata" in runtime.events
    assert "create_topics" not in runtime.events
    assert runtime.context.processes == []


def test_owned_output_metadata_error_is_not_treated_as_a_usable_topic(runtime):
    runtime.store.add_deployment(record())
    runtime.admin.topics[document()["output_topic"]] = SimpleNamespace(error="authorization failed")
    with pytest.raises(ValueError, match="unavailable"):
        runtime.manager.deploy(document())
    assert runtime.context.processes == []
    assert runtime.store.get_deployment("forecast")["status"] == "stopped"


@pytest.mark.parametrize("change", ["review", "draft", "clock", "unit", "context", "artifact"])
def test_unreviewed_or_incompatible_models_never_create_topics_or_workers(runtime, change):
    value = model()
    if change == "review":
        value["review"]["accepted"] = False
    elif change == "draft":
        value["status"] = "draft"
    elif change == "clock":
        value["artifact"]["source"]["time_kind"] = "relative"
    elif change == "unit":
        value["artifact"]["source"]["unit"] = None
    elif change == "context":
        value["artifact"]["context_names"] = ["future-context"]
    else:
        value["artifact"]["invalid"] = True
    runtime.save_model(value)
    with pytest.raises(ValueError):
        runtime.manager.deploy(document())
    assert "metadata" not in runtime.events
    assert runtime.context.processes == []


def test_registered_version_is_verified_before_any_topic_mutation_or_spawn(runtime):
    runtime.registry.failure = RuntimeError("registered version changed")
    with pytest.raises(RuntimeError, match="registered version"):
        runtime.manager.deploy(document())
    assert "metadata" not in runtime.events
    runtime.store.add_deployment(record())
    with pytest.raises(RuntimeError, match="registered version"):
        runtime.manager.start("forecast")
    assert runtime.context.processes == []
    assert runtime.store.get_deployment("forecast")["desired_state"] == "stopped"


def test_existing_deployment_binding_cannot_be_changed(runtime):
    runtime.store.add_deployment({**record(), "input_partition": 1})
    with pytest.raises(ValueError, match="immutable configuration"):
        runtime.manager.deploy(document())
    assert "metadata" not in runtime.events
    assert runtime.context.processes == []


def test_successful_start_reports_starting_until_the_worker_reports_running(runtime):
    runtime.store.add_deployment(record())
    answer = runtime.manager.start("forecast")
    assert (answer["desired_state"], answer["status"]) == ("running", "starting")
    assert runtime.manager.start("forecast")["status"] == "starting"
    assert len(runtime.context.processes) == 1
    runtime.store.set_worker_status("forecast", "running")
    assert runtime.manager.deployments()["deployments"][0]["status"] == "running"
    assert runtime.context.processes[0].options["args"][:4] == (
        runtime.store.path,
        "forecast",
        "broker:9092",
        PREFIX,
    )
    assert runtime.context.processes[0].options["daemon"] is True


def test_local_worker_limit_does_not_start_or_mark_a_fifth_worker_running(runtime):
    for index in range(5):
        runtime.store.add_deployment(record(f"forecast-{index}"))
    for index in range(4):
        runtime.manager.start(f"forecast-{index}")
    with pytest.raises(ValueError, match="four active"):
        runtime.manager.start("forecast-4")
    assert len(runtime.context.processes) == 4
    assert runtime.store.get_deployment("forecast-4")["desired_state"] == "stopped"


def test_stop_reaps_and_closes_the_worker_before_reporting_stopped(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.start("forecast")
    process = runtime.context.processes[0]
    result = runtime.manager.stop("forecast")
    assert process.event.set_count == 1
    assert process.closed and process.joins
    assert "forecast" not in runtime.manager.children
    assert (result["desired_state"], result["status"]) == ("stopped", "stopped")


def test_stop_terminates_a_worker_that_does_not_exit_gracefully(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.start("forecast")
    process = runtime.context.processes[0]
    process.stop_on_join = False
    runtime.manager.stop("forecast")
    assert process.terminated and process.closed


def test_unstoppable_worker_remains_owned_and_cannot_be_reported_stopped(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.start("forecast")
    process = runtime.context.processes[0]
    process.stop_on_join = process.stop_on_terminate = False
    with pytest.raises(RuntimeError, match="did not stop"):
        runtime.manager.stop("forecast")
    assert runtime.store.get_deployment("forecast")["status"] != "stopped"
    assert runtime.manager.children["forecast"][0] is process
    assert not process.closed


def test_close_retains_the_exclusive_owner_if_a_worker_cannot_be_stopped(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.start("forecast")
    process = runtime.context.processes[0]
    process.stop_on_join = process.stop_on_terminate = False
    with pytest.raises(RuntimeError, match="did not stop"):
        runtime.manager.close()
    assert not runtime.manager.owner.closed
    with pytest.raises(RuntimeError, match="another workbench"):
        api().SmartTopics(runtime.store, runtime.registry, runtime.catalogue, "broker:9092", PREFIX)
    assert runtime.manager.children["forecast"][0] is process


def test_failed_process_start_is_reported_and_its_handle_is_closed(runtime):
    runtime.store.add_deployment(record())
    runtime.context.fail_start = True
    with pytest.raises(OSError, match="spawn unavailable"):
        runtime.manager.start("forecast")
    result = runtime.store.get_deployment("forecast")
    assert (result["desired_state"], result["status"]) == ("stopped", "failed")
    assert "worker start failed" in result["error"]
    assert runtime.context.processes[0].closed
    assert runtime.manager.children == {}


def test_restarting_a_dead_worker_closes_its_old_process_handle(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.start("forecast")
    old = runtime.context.processes[0]
    old.alive, old.exitcode = False, 7
    runtime.manager.start("forecast")
    assert old.closed and old.joins
    assert len(runtime.context.processes) == 2
    assert runtime.context.processes[1].alive


def test_listing_a_dead_child_reports_failure_and_reaps_its_handle(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.start("forecast")
    process = runtime.context.processes[0]
    process.alive, process.exitcode = False, 9
    result = runtime.manager.deployments()["deployments"][0]
    assert (result["desired_state"], result["status"]) == ("stopped", "failed")
    assert "code 9" in result["error"]
    assert process.closed and process.joins
    assert runtime.manager.children == {}


def test_start_reaps_other_dead_worker_handles_before_launching_another_binding(
    runtime,
):
    runtime.store.add_deployment(record())
    runtime.manager.start("forecast")
    old = runtime.context.processes[0]
    old.alive, old.exitcode = False, 6
    runtime.store.add_deployment(record("forecast-next"))
    runtime.manager.start("forecast-next")
    assert old.closed
    assert "forecast" not in runtime.manager.children
    assert runtime.store.get_deployment("forecast")["status"] == "failed"


def test_recovery_preserves_the_durable_numerical_checkpoint(runtime):
    runtime.store.add_deployment(record(desired="running"))
    checkpoint = {
        "artifact_id": "b" * 64,
        "numerical": {"predicted_state": [2]},
        "next_offset": 12,
        "counters": {"read": 12, "scored": 4, "skipped": 8},
    }
    runtime.store.save_checkpoint("forecast", checkpoint)
    runtime.manager.recover()
    assert runtime.store.get_checkpoint("forecast") == checkpoint
    assert len(runtime.context.processes) == 1
    assert runtime.store.get_deployment("forecast")["status"] == "starting"
    runtime.manager.close()
    assert runtime.store.get_deployment("forecast")["desired_state"] == "running"
    assert runtime.store.get_checkpoint("forecast") == checkpoint


def test_recovery_leaves_explicitly_stopped_deployments_stopped(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.recover()
    assert runtime.context.processes == []
    assert runtime.store.get_deployment("forecast")["desired_state"] == "stopped"


def test_recovery_failure_is_visible_without_erasing_the_checkpoint(runtime):
    runtime.store.add_deployment(record(desired="running"))
    checkpoint = {"next_offset": 17}
    runtime.store.save_checkpoint("forecast", checkpoint)
    runtime.registry.failure = RuntimeError("registry unavailable")
    runtime.manager.recover()
    result = runtime.store.get_deployment("forecast")
    assert (result["desired_state"], result["status"]) == ("stopped", "failed")
    assert "registry unavailable" in result["error"]
    assert runtime.store.get_checkpoint("forecast") == checkpoint


def test_public_checkpoint_exposes_only_progress_fields(runtime):
    runtime.store.add_deployment(record())
    checkpoint = {
        "artifact_id": "b" * 64,
        "next_offset": 19,
        "counters": {"read": 19, "scored": 3, "skipped": 16},
        "last_source": {"offset": "18"},
        "numerical": {"predicted_state": [2]},
        "private_extra": "internal",
    }
    runtime.store.save_checkpoint("forecast", checkpoint)
    public = runtime.manager.deployments()["deployments"][0]["checkpoint"]
    assert set(public) == {"artifact_id", "next_offset", "counters", "last_source"}
    assert runtime.store.get_checkpoint("forecast") == checkpoint


def test_close_is_idempotent_and_a_closed_manager_cannot_spawn_workers(runtime):
    runtime.store.add_deployment(record())
    runtime.manager.close()
    runtime.manager.close()
    with pytest.raises(RuntimeError, match="closed"):
        runtime.manager.start("forecast")
    assert runtime.context.processes == []


@pytest.mark.parametrize(
    "change",
    [
        "extra",
        "missing",
        "foreign_output",
        "output_type",
        "bad_partition",
        "negative_partition",
        "bad_model",
        "foreign_input",
    ],
)
def test_malformed_inputs_fail_before_output_mutation(runtime, change):
    body = document()
    if change == "extra":
        body["artifact"] = {}
    elif change == "missing":
        del body["model_id"]
    elif change == "foreign_output":
        body["output_topic"] = "foreign.smart.forecast.predictions.v1"
    elif change == "output_type":
        body["output_topic"] = []
    elif change == "bad_partition":
        body["input_partition"] = True
    elif change == "negative_partition":
        body["input_partition"] = -1
    elif change == "bad_model":
        body["model_id"] = []
    else:
        body["input_topic"] = "foreign.episodes.v2"
    with pytest.raises(ValueError):
        runtime.manager.deploy(body)
    assert "metadata" not in runtime.events
    assert runtime.context.processes == []


def qualified_context(runtime):
    from opentsx.client.context_join import schema_descriptor

    value = model()
    value["run_id"] = "c" * 64
    value["artifact"]["context_names"] = ["schedule"]
    value["context_schema"] = schema_descriptor("series-a", ["schedule"], ["MW"], 1000000)
    runtime.save_model(value)
    with runtime.store.connection() as connection:
        connection.execute("INSERT INTO runs VALUES(?,?,?)", (value["run_id"], "{}", "{}"))
    calls = []

    def verify(model, request, topic, partition):
        calls.append((model["id"], topic, partition))
        return {
            "schema": "opentsx.context-binding.v1",
            "schema_id": value["context_schema"]["schema_id"],
        }

    runtime.admin.topics[PREFIX + "forecast.context.v1"] = SimpleNamespace(
        error=None, partitions={0: None}
    )
    runtime.manager.context_catalogue = SimpleNamespace(verify_binding=verify)
    return value, calls


def test_qualified_sarimax_binds_context_before_starting(runtime):
    value, calls = qualified_context(runtime)
    context = {"topic": PREFIX + "forecast.context.v1", "partition": 0}
    result = runtime.manager.deploy({**document(), "context": context})
    assert calls == [(MODEL_ID, context["topic"], 0)]
    assert result["context"] == context
    assert result["context_schema"] == value["context_schema"]
    assert result["status"] == "starting"
    with pytest.raises(ValueError, match="immutable"):
        runtime.manager.deploy({**document(), "context": {**context, "partition": 1}})


@pytest.mark.parametrize(
    "context",
    [
        None,
        {"topic": "outside.context.v1", "partition": 0},
        {"topic": PREFIX + "a.context.v1", "partition": -1},
    ],
)
def test_sarimax_rejects_invalid_context_before_topic_mutation(runtime, context):
    qualified_context(runtime)
    with pytest.raises(ValueError):
        runtime.manager.deploy({**document(), "context": context})
    assert runtime.admin.created == []
    assert runtime.context.processes == []


def test_context_free_model_rejects_context_binding(runtime):
    with pytest.raises(ValueError, match="context-free"):
        runtime.manager.deploy(
            {
                **document(),
                "context": {"topic": PREFIX + "a.context.v1", "partition": 0},
            }
        )
    assert runtime.admin.created == []


@pytest.mark.parametrize("topic", ["target", "context"])
def test_whole_topic_admission_rejects_multiple_partitions_before_mutation(runtime, topic):
    if topic == "context":
        qualified_context(runtime)
        binding = {"topic": PREFIX + "forecast.context.v1", "partition": 0}
        body = {**document(), "context": binding}
        selected = binding["topic"]
    else:
        body, selected = document(), INPUT
    runtime.admin.topics[selected].partitions[1] = None
    with pytest.raises(ValueError, match="whole topic"):
        runtime.manager.deploy(body)
    assert runtime.store.list_deployments() == []
    assert runtime.admin.created == []
    assert "registry.promote" not in runtime.events
    assert runtime.context.processes == []


def test_whole_topic_restart_rejects_expansion_without_mutating_saved_state(runtime):
    runtime.store.add_deployment(record())
    checkpoint = {"artifact_id": "b" * 64, "next_offset": 9, "numerical": {"total_nobs": 80}}
    runtime.store.save_checkpoint("forecast", checkpoint)
    before = runtime.store.get_deployment("forecast")
    runtime.admin.topics[INPUT].partitions[1] = None
    with pytest.raises(ValueError, match="whole topic"):
        runtime.manager.start("forecast")
    assert runtime.store.get_deployment("forecast") == before
    assert runtime.context.processes == []


def test_whole_topic_context_expansion_fails_recovery_without_losing_checkpoint(runtime):
    value, calls = qualified_context(runtime)
    context = {"topic": PREFIX + "forecast.context.v1", "partition": 0}
    runtime.store.add_deployment(
        {
            **record(desired="running"),
            "context": context,
            "context_schema": value["context_schema"],
            "context_binding": {"schema_id": value["context_schema"]["schema_id"]},
        }
    )
    checkpoint = {"artifact_id": "b" * 64, "next_offset": 9, "numerical": {"total_nobs": 80}}
    runtime.store.save_checkpoint("forecast", checkpoint)
    runtime.admin.topics[context["topic"]].partitions[1] = None
    runtime.manager.recover()
    saved = runtime.store.get_deployment("forecast")
    assert saved["status"] == "failed"
    assert saved["desired_state"] == "stopped"
    assert "whole topic" in saved["error"]
    assert saved["checkpoint"] == checkpoint
    assert runtime.context.processes == []
