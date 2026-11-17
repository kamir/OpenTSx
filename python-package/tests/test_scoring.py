import copy
import hashlib
import importlib
import json
import sys
from threading import Event
from types import SimpleNamespace

import numpy as np
import pytest

from opentsx import model

PREFIX = "tsx.workbench-demo."
TOPIC = PREFIX + "voltage.episodes.v2"
ARTIFACT_ID = "a" * 64


def episode(start=1040, values=(5.0, 6.0), **kwargs):
    return model.regular(
        model.series_key("voltage", {"origin": "SYNTHETIC"}, "V"),
        start,
        10,
        values,
        episode_id="01ARZ3NDEKTSV4RRFFQ69G5FA0",
        created_at_us=0,
        **kwargs,
    )


def message(offset=0, record=None):
    return SimpleNamespace(
        error=lambda: None,
        topic=lambda: TOPIC,
        partition=lambda: 1,
        offset=lambda: offset,
        value=lambda: model.encode("Episode", episode() if record is None else record),
    )


class Store:
    def __init__(self):
        training = [1.0, 2.0, 3.0, 4.0]
        self.deployment = {
            "id": "lab",
            "model_id": "registered-a",
            "input_topic": TOPIC,
            "input_partition": 1,
            "output_topic": PREFIX + "smart.lab.predictions.v1",
            "series_id": episode()["series"]["seriesId"],
            "desired_state": "running",
        }
        self.artifact = {
            "artifact_id": ARTIFACT_ID,
            "training_values": training,
            "source": {
                "series_id": self.deployment["series_id"],
                "unit": "V",
                "time_kind": "absolute",
                "interval_micros": 10,
                "t_start_micros": 1000,
                "t_end_micros": 1040,
                "values_sha256": hashlib.sha256(
                    np.array(training, dtype="<f8").tobytes()
                ).hexdigest(),
            },
            "context_names": [],
            "initial_checkpoint": {
                "artifact_id": ARTIFACT_ID,
                "total_nobs": 4,
                "next_timestamp_micros": 1040,
            },
        }
        self.checkpoint = None
        self.outputs = []
        self.statuses = []
        self.fail_after_ack = False

    def get_deployment(self, identifier):
        assert identifier == "lab"
        return copy.deepcopy(self.deployment)

    def get_model(self, identifier):
        assert identifier == "registered-a"
        return {"artifact": copy.deepcopy(self.artifact)}

    def get_checkpoint(self, identifier):
        return copy.deepcopy(self.checkpoint)

    def save_checkpoint(self, identifier, checkpoint):
        self.checkpoint = copy.deepcopy(checkpoint)

    def set_worker_status(self, identifier, status, error=None):
        self.statuses.append((status, error))

    def record_output(self, identifier, key, payload, broker_partition, broker_offset, checkpoint):
        if self.fail_after_ack:
            raise OSError("checkpoint storage unavailable")
        self.outputs.append((key, copy.deepcopy(payload), broker_partition, broker_offset))
        self.checkpoint = copy.deepcopy(checkpoint)


class Consumer:
    def __init__(self, stop):
        self.stop = stop
        self.messages = []
        self.bounds = (0, 0)
        self.closed = False
        self.assignments = []
        self.polls = []

    def get_watermark_offsets(self, partition, timeout):
        assert (partition.topic, partition.partition) == (TOPIC, 1)
        return self.bounds

    def assign(self, partitions):
        self.assignments.append(partitions)

    def poll(self, timeout):
        self.polls.append(timeout)
        if self.messages:
            return self.messages.pop(0)
        self.stop.set()
        return None

    def close(self):
        self.closed = True


class Producer:
    def __init__(self):
        self.produced = []
        self.pending = []
        self.delivery_error = None
        self.remaining = 0

    def produce(self, topic, *, key, value, on_delivery):
        self.produced.append((topic, key, value))
        self.pending.append(on_delivery)

    def flush(self, timeout):
        if not self.remaining:
            for callback in self.pending:
                callback(
                    self.delivery_error, SimpleNamespace(partition=lambda: 2, offset=lambda: 12)
                )
            self.pending.clear()
        return self.remaining


@pytest.fixture
def runtime(monkeypatch):
    store = Store()
    stop = Event()
    consumer = Consumer(stop)
    producer = Producer()
    configs = {}
    advances = []

    def advance(artifact, checkpoint, values, t_start_micros):
        assert t_start_micros == checkpoint["next_timestamp_micros"]
        advances.append((copy.deepcopy(checkpoint), list(values), t_start_micros))
        end = t_start_micros + len(values) * 10
        numerical = {
            **checkpoint,
            "next_timestamp_micros": end,
            "total_nobs": checkpoint["total_nobs"] + len(values),
        }
        observed = {
            "interval_status": "conditional_gaussian",
            "actual": list(values),
            "predicted": [0.0] * len(values),
            "residual": list(values),
            "lower": [-1.0] * len(values),
            "upper": [1.0] * len(values),
            "timestamps_micros": list(range(t_start_micros, end, 10)),
        }
        forecast = {
            "mean": [0.0],
            "lower": [-1.0],
            "upper": [1.0],
            "timestamps_micros": [end],
            "interval_status": "conditional_gaussian",
        }
        return {"checkpoint": numerical, "observed": observed, "forecast": forecast}

    def configured(name, instance):
        def create(config):
            configs[name] = config
            return instance

        return create

    lifecycle = importlib.import_module("opentsx.client.lifecycle")
    monkeypatch.setattr(lifecycle, "LifecycleStore", lambda path: store)

    def validate(artifact, checkpoint):
        if checkpoint.get("artifact_id") != artifact["artifact_id"]:
            raise ValueError("checkpoint artifact identity is invalid")
        if not isinstance(checkpoint.get("next_timestamp_micros"), int):
            raise ValueError("checkpoint clock is invalid")
        return checkpoint

    fitted = importlib.import_module("opentsx.algorithms.fitted")
    real_validate_checkpoint = fitted.validate_checkpoint
    monkeypatch.setattr(fitted, "advance_artifact", advance)
    monkeypatch.setattr(fitted, "validate_checkpoint", validate)
    kafka = importlib.import_module("confluent_kafka")
    monkeypatch.setattr(kafka, "Consumer", configured("consumer", consumer))
    monkeypatch.setattr(kafka, "Producer", configured("producer", producer))
    monkeypatch.setattr(
        kafka,
        "TopicPartition",
        lambda topic, partition, offset=None: SimpleNamespace(
            topic=topic, partition=partition, offset=offset
        ),
    )
    api = importlib.import_module("opentsx.client.scoring")

    def run(records=(), bounds=None):
        stop.clear()
        consumer.messages = list(records)
        consumer.bounds = (0, len(records)) if bounds is None else bounds
        api.run_worker("fixture.sqlite", "lab", "fixture:9092", PREFIX, stop)

    return SimpleNamespace(
        store=store,
        stop=stop,
        consumer=consumer,
        producer=producer,
        advances=advances,
        configs=configs,
        run=run,
        real_validate_checkpoint=real_validate_checkpoint,
    )


def test_scores_only_after_training_and_persists_after_acknowledgement(runtime):
    old = episode(1000, (1.0, 2.0, 3.0, 4.0))
    runtime.run([message(0, old), message(1)])
    assert len(runtime.store.outputs) == 1
    key, payload, partition, offset = runtime.store.outputs[0]
    assert payload["schema"] == "opentsx.smart-prediction.v1"
    assert payload["source"]["offset"] == "1"
    assert payload["origin_micros"] == 1060
    assert payload["observed"]["actual"] == [5.0, 6.0]
    assert payload["forecast"]["timestamps_micros"] == [1060]
    assert payload["artifact_id"] == ARTIFACT_ID
    assert len(key) == 64 and (partition, offset) == (2, 12)
    assert runtime.store.checkpoint["next_offset"] == 2
    assert runtime.store.checkpoint["numerical"]["total_nobs"] == 6
    assert runtime.consumer.closed
    assert max(runtime.consumer.polls) <= 1
    assert runtime.configs["consumer"]["enable.auto.commit"] is False
    assert runtime.configs["consumer"]["enable.auto.offset.store"] is False
    assert runtime.configs["consumer"]["auto.offset.reset"] == "error"
    assert runtime.configs["producer"]["acks"] == "all"
    assert runtime.configs["producer"]["enable.idempotence"] is False


def test_replays_the_same_key_and_bytes_after_ack_before_checkpoint_failure(runtime):
    runtime.store.fail_after_ack = True
    with pytest.raises(OSError, match="checkpoint"):
        runtime.run([message()])
    first = runtime.producer.produced[0]
    assert runtime.store.checkpoint["next_offset"] == 0
    runtime.store.fail_after_ack = False
    runtime.run([message()])
    assert runtime.producer.produced[-1] == first
    assert runtime.store.checkpoint["next_offset"] == 1


def test_restart_uses_the_saved_offset_and_numerical_state(runtime):
    runtime.run([message()])
    runtime.run([message(1, episode(1060, (7.0, 8.0)))], bounds=(0, 2))
    assert runtime.consumer.assignments[-1][0].offset == 1
    assert runtime.advances[-1][0]["total_nobs"] == 6
    assert runtime.store.checkpoint["numerical"]["total_nobs"] == 8


@pytest.mark.parametrize("bounds", [(2, 3), (0, 0)])
def test_retention_or_log_truncation_does_not_reset_a_saved_offset(runtime, bounds):
    runtime.run([message()])
    before = copy.deepcopy(runtime.store.checkpoint)
    with pytest.raises(ValueError, match="offset"):
        runtime.run([], bounds=bounds)
    assert runtime.store.checkpoint == before
    assert runtime.store.statuses[-1][0] == "failed"


def test_empty_topic_waits_without_scoring_or_fabricating_output(runtime):
    runtime.run()
    assert runtime.store.outputs == []
    assert runtime.advances == []
    assert runtime.store.checkpoint["next_offset"] == 0
    assert runtime.store.statuses[-1][0] == "stopped"


def test_unrelated_series_advances_only_the_broker_checkpoint(runtime):
    other = episode()
    other["series"] = model.series_key("current", {"origin": "SYNTHETIC"}, "A")
    runtime.run([message(0, other)])
    assert runtime.store.outputs == []
    assert runtime.advances == []
    assert runtime.store.checkpoint["next_offset"] == 1
    assert runtime.store.checkpoint["numerical"]["total_nobs"] == 4


@pytest.mark.parametrize("remaining,error", [(1, None), (0, "delivery failed")])
def test_unacknowledged_output_never_advances_the_checkpoint(runtime, remaining, error):
    runtime.producer.remaining = remaining
    runtime.producer.delivery_error = error
    with pytest.raises((TimeoutError, RuntimeError), match="acknowledged"):
        runtime.run([message()])
    assert runtime.store.outputs == []
    assert runtime.store.checkpoint["next_offset"] == 0
    assert runtime.store.statuses[-1][0] == "failed"
    assert runtime.consumer.closed


@pytest.mark.parametrize(
    "change", ["gap", "overlap", "revision", "quality", "interval", "unit", "empty"]
)
def test_rejects_invalid_same_series_without_publication(runtime, change):
    record = episode()
    if change == "gap":
        record["tStart"], record["tEnd"] = 1050, 1070
    elif change == "overlap":
        record["tStart"], record["tEnd"] = 1030, 1050
    elif change == "revision":
        record["revision"] = 1
    elif change == "quality":
        record["quality"] = b"\x00\x01"
    elif change == "interval":
        record["intervalMicros"] = 20
        record["tEnd"] = 1080
    elif change == "unit":
        record["series"] = model.series_key("voltage", {"origin": "SYNTHETIC"}, "kV")
        runtime.store.deployment["series_id"] = record["series"]["seriesId"]
        runtime.store.artifact["source"]["series_id"] = record["series"]["seriesId"]
    else:
        record["values"] = []
        record["count"] = 0
    with pytest.raises(ValueError):
        runtime.run([message(0, record)])
    assert runtime.producer.produced == []
    assert runtime.store.checkpoint["next_offset"] == 0


def test_trims_only_a_verified_training_prefix(runtime):
    runtime.run([message(0, episode(1000, (1.0, 2.0, 3.0, 4.0, 5.0, 6.0)))])
    assert runtime.advances[0][1:] == ([5.0, 6.0], 1040)
    assert runtime.store.outputs[0][1]["source"]["trimmed_training_samples"] == 4


def test_changed_training_prefix_is_rejected_before_filtering(runtime):
    with pytest.raises(ValueError, match="training"):
        runtime.run([message(0, episode(1000, (9.0, 2.0, 3.0, 4.0, 5.0)))])
    assert runtime.advances == []
    assert runtime.producer.produced == []


def test_partial_training_overlap_uses_the_preserved_sample_positions(runtime):
    runtime.run([message(0, episode(1020, (3.0, 4.0, 5.0, 6.0)))])
    assert runtime.advances[0][1:] == ([5.0, 6.0], 1040)
    assert runtime.store.outputs[0][1]["source"]["trimmed_training_samples"] == 2


def test_training_history_tampering_is_not_silently_skipped(runtime):
    with pytest.raises(ValueError, match="training"):
        runtime.run([message(0, episode(1020, (30.0, 4.0)))])
    assert runtime.store.checkpoint["next_offset"] == 0
    assert runtime.advances == []


def test_a_second_copy_of_a_scored_episode_cannot_update_numerical_state_twice(runtime):
    runtime.run([message()])
    before = copy.deepcopy(runtime.store.checkpoint)
    with pytest.raises(ValueError, match="overlap"):
        runtime.run([message(1)], bounds=(0, 2))
    assert runtime.store.checkpoint == before
    assert len(runtime.store.outputs) == 1


@pytest.mark.parametrize("count", [4096, 6144])
def test_large_episode_is_filtered_in_bounded_chunks_and_committed_once(runtime, count):
    runtime.run([message(0, episode(1040, list(range(count))))])
    assert [len(call[1]) for call in runtime.advances] == [2048] * (count // 2048)
    assert len(runtime.store.outputs) == 1
    assert len(runtime.store.outputs[0][1]["observed"]["actual"]) == count
    assert runtime.store.checkpoint["numerical"]["total_nobs"] == count + 4


def test_unknown_quality_is_reported_without_becoming_confirmed_good(runtime):
    runtime.run([message()])
    payload = runtime.store.outputs[0][1]
    assert payload["source"]["quality_status"] == "unknown"
    assert any("quality" in warning.lower() for warning in payload["warnings"])
    json.dumps(payload, allow_nan=False)


def test_explicit_zero_flags_remain_unflagged_rather_than_unknown(runtime):
    runtime.run([message(0, episode(quality=b"\x00\x00"))])
    assert runtime.store.outputs[0][1]["source"]["quality_status"] == "unflagged"
    assert not any(
        "quality" in warning.lower() for warning in runtime.store.outputs[0][1]["warnings"]
    )


def test_large_kafka_offset_is_lossless_in_the_prediction_envelope(runtime):
    offset = 2**53 + 9
    runtime.run([message(offset)], bounds=(offset, offset + 1))
    assert runtime.store.outputs[0][1]["source"]["offset"] == str(offset)
    assert runtime.store.checkpoint["next_offset"] == offset + 1


def test_oversized_source_is_rejected_before_decoding_or_publication(runtime):
    record = message()
    record.value = lambda: b"x" * (4 * 1024 * 1024 + 1)
    with pytest.raises(ValueError, match="payload limit"):
        runtime.run([record])
    assert runtime.producer.produced == []
    assert runtime.store.checkpoint["next_offset"] == 0


def test_a_successful_flush_without_a_delivery_callback_is_not_an_acknowledgement(runtime):
    runtime.producer.flush = lambda timeout: 0
    with pytest.raises(RuntimeError, match="acknowledged"):
        runtime.run([message()])
    assert runtime.store.outputs == []
    assert runtime.store.checkpoint["next_offset"] == 0


@pytest.mark.parametrize(
    "field,value",
    [
        ("output_topic", PREFIX + "other.v1"),
        ("input_topic", "private.episodes.v2"),
        ("input_partition", True),
    ],
)
def test_namespace_and_partition_are_checked_before_consumer_creation(runtime, field, value):
    runtime.store.deployment[field] = value
    with pytest.raises(ValueError):
        runtime.run()
    assert runtime.configs == {}


def test_out_of_order_offset_is_rejected_without_advancing_state(runtime):
    with pytest.raises(ValueError, match="offset"):
        runtime.run([message(1)], bounds=(0, 2))
    assert runtime.store.checkpoint["next_offset"] == 0
    assert runtime.producer.produced == []


@pytest.mark.parametrize("change", ["artifact", "relative", "context", "unit"])
def test_incompatible_artifact_or_checkpoint_is_rejected_before_read(runtime, change):
    if change == "artifact":
        runtime.store.checkpoint = {
            "artifact_id": "b" * 64,
            "next_offset": 0,
            "numerical": runtime.store.artifact["initial_checkpoint"],
            "counters": {"read": 0, "scored": 0, "skipped": 0},
        }
    elif change == "relative":
        runtime.store.artifact["source"]["time_kind"] = "relative"
    elif change == "unit":
        runtime.store.artifact["source"]["unit"] = None
    else:
        runtime.store.artifact["context_names"] = ["unavailable-live-context"]
    with pytest.raises(ValueError):
        runtime.run()
    assert runtime.producer.produced == []


def test_corrupt_numerical_checkpoint_fails_before_assigning_the_source(runtime):
    runtime.run([message()])
    runtime.store.checkpoint["numerical"]["next_timestamp_micros"] = None
    assignments = len(runtime.consumer.assignments)
    with pytest.raises(ValueError, match="checkpoint clock"):
        runtime.run([], bounds=(0, 1))
    assert len(runtime.consumer.assignments) == assignments
    assert runtime.store.statuses[-1][0] == "failed"


def test_a_stopped_deployment_creates_no_broker_clients(runtime):
    runtime.store.deployment["desired_state"] = "stopped"
    runtime.run()
    assert runtime.configs == {}
    assert runtime.store.statuses[-1][0] == "stopped"


def test_worker_resolves_the_installed_fitted_module_without_a_fake_import(runtime, monkeypatch):
    assert "opentsx.client.fitted" not in sys.modules
    fitted = importlib.import_module("opentsx.algorithms.fitted")
    monkeypatch.setattr(fitted, "validate_checkpoint", runtime.real_validate_checkpoint)
    with pytest.raises(ValueError):
        runtime.run()
    assert runtime.configs == {}
    assert runtime.store.statuses[-1][0] == "failed"


@pytest.mark.parametrize("family", ["arima", "sarima"])
def test_real_registered_artifact_scores_through_the_worker_without_refitting(
    tmp_path, monkeypatch, family
):
    import confluent_kafka
    from statsmodels.tsa.statespace.sarimax import SARIMAX

    from opentsx.client.lifecycle import LifecycleStore
    from opentsx.client.modelling import execute_modelling

    fitted = importlib.import_module("opentsx.algorithms.fitted")
    key = model.series_key(
        "power.active", {"origin": "SYNTHETIC", "fixture": "worker-integration"}, "MW"
    )
    values = np.random.default_rng(744).normal(0, 0.3, 72)
    for index in range(1, len(values)):
        values[index] += 0.65 * values[index - 1]
    origin, interval = 1700000000000000, 1000000
    training = model.regular(
        key,
        origin,
        interval,
        values[:64],
        episode_id="01ARZ3NDEKTSV4RRFFQ69G5FA0",
        created_at_us=0,
        quality=bytes(64),
    )
    following = model.regular(
        key,
        origin + 64 * interval,
        interval,
        values[64:],
        episode_id="01ARZ3NDEKTSV4RRFFQ69G5FA1",
        created_at_us=0,
        quality=bytes(8),
    )
    request = {
        "schema": "opentsx.modelling-request.v1",
        "target": {
            "series_id": key["seriesId"],
            "label": "Synthetic power",
            "unit": "MW",
            "time_kind": "absolute",
            "episode_ids": [training["episodeId"]],
            "values": values[:64].tolist(),
            "timestamps_micros": [origin + i * interval for i in range(64)],
            "quality": [0] * 64,
            "quality_known": [1] * 64,
        },
        "configuration": {
            "family": family,
            "order": [1, 0, 0] if family == "arima" else [0, 0, 0],
            "seasonal_order": [0, 0, 0, 0] if family == "arima" else [0, 1, 0, 4],
            "trend": "n",
            "steps": 4,
            "alpha": 0.05,
            "maxiter": 200,
        },
        "backtest": {
            "initial": 48,
            "horizon": 4,
            "step": 8,
            "window": None,
            "seasonal_period": 4,
            "methods": ["naive"],
        },
        "context": None,
    }
    result = execute_modelling(request)
    store = LifecycleStore(tmp_path / "real-worker.sqlite")
    store.save_run(request, result)

    class Registry:
        def register(self, *args):
            return {"run_id": "fixture-registry-run", "version": "1"}

    registered = store.register(
        {
            "run_id": result["run_id"],
            "name": "power-forecast",
            "review": {
                "accepted": True,
                "note": "Reviewed for numerical worker integration",
                "minimum_improvement": -10,
            },
        },
        Registry(),
    )
    artifact = registered["artifact"]
    store.add_deployment(
        {
            "id": "lab",
            "model_id": registered["id"],
            "series_id": key["seriesId"],
            "input_topic": TOPIC,
            "input_partition": 1,
            "output_topic": PREFIX + "smart.lab.predictions.v1",
            "desired_state": "running",
            "status": "starting",
            "error": None,
        }
    )

    def forbidden_fit(*args, **kwargs):
        raise AssertionError("continuous scoring must not fit parameters again")

    monkeypatch.setattr(SARIMAX, "fit", forbidden_fit)
    expected = fitted.advance_artifact(
        artifact, artifact["initial_checkpoint"], values[64:].tolist(), following["tStart"]
    )
    stop = Event()
    consumer, producer = Consumer(stop), Producer()
    consumer.bounds = (0, 2)
    consumer.messages = [message(0, training), message(1, following)]
    monkeypatch.setattr(confluent_kafka, "Consumer", lambda config: consumer)
    monkeypatch.setattr(confluent_kafka, "Producer", lambda config: producer)
    assert "opentsx.client.fitted" not in sys.modules
    importlib.import_module("opentsx.client.scoring").run_worker(
        store.path, "lab", "fixture:9092", PREFIX, stop
    )
    outputs = store.results("lab")["results"]
    assert len(outputs) == len(producer.produced) == 1
    payload = outputs[0]["payload"]
    assert payload == json.loads(producer.produced[0][2])
    assert payload["artifact_id"] == artifact["artifact_id"]
    assert payload["model_id"] == registered["id"]
    assert payload["target"] == {
        "series_id": key["seriesId"],
        "unit": "MW",
        "time_kind": "absolute",
        "interval_micros": interval,
    }
    assert payload["observed"]["actual"] == values[64:].tolist()
    for field in ("mean", "lower", "upper", "timestamps_micros"):
        np.testing.assert_allclose(
            payload["forecast"][field], expected["forecast"][field], rtol=0, atol=1e-10
        )
        assert np.all(np.isfinite(payload["forecast"][field]))
    checkpoint = store.get_checkpoint("lab")
    assert checkpoint["counters"] == {"read": 2, "scored": 1, "skipped": 1}
    assert checkpoint["next_offset"] == 2
    assert checkpoint["numerical"]["total_nobs"] == 72
    assert checkpoint["numerical"]["next_timestamp_micros"] == origin + 72 * interval
    assert store.get_deployment("lab")["status"] == "stopped"
    assert consumer.closed
