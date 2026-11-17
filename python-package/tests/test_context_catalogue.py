import copy
import hashlib
import importlib
import math
import sys
from types import SimpleNamespace

import numpy as np
import pytest

PREFIX = "tsx.workbench-demo."
TOPIC = PREFIX + "sarimax.context.v1"


def document():
    return {
        "topic": TOPIC,
        "partition": 0,
        "target": {
            "series_id": "target-a",
            "label": "Target",
            "unit": "MW",
            "time_kind": "absolute",
            "values": [math.sin(i * 0.2) for i in range(16)],
            "timestamps_micros": list(range(1000, 1160, 10)),
            "episode_ids": ["episode-a"],
        },
        "configuration": {
            "family": "sarimax",
            "order": [0, 0, 0],
            "seasonal_order": [0, 0, 0, 0],
            "trend": "n",
            "steps": 4,
            "alpha": 0.1,
            "maxiter": 200,
        },
        "backtest": {
            "initial": 12,
            "horizon": 2,
            "step": 2,
            "window": None,
            "seasonal_period": 2,
            "methods": ["naive"],
        },
    }


@pytest.fixture
def broker(monkeypatch):
    api = importlib.import_module("opentsx.client.context_catalogue")
    core = importlib.import_module("opentsx.client.context_join")
    record = core.make_record(
        "target-a",
        ["schedule_sin", "schedule_cos"],
        ["1", "1"],
        1000,
        10,
        500,
        [[math.sin(i * 0.2), math.cos(i * 0.3)] for i in range(20)],
        [[500, 500] for _ in range(20)],
    )
    broker = SimpleNamespace(
        bounds={0: (0, 1)}, records={0: [(0, record)]}, consumers=[], core=core
    )

    class Consumer:
        def __init__(self, config):
            self.config, self.closed, self.queue = config, False, []
            broker.consumers.append(self)

        def list_topics(self, topic=None, timeout=10):
            return SimpleNamespace(
                topics={
                    TOPIC: SimpleNamespace(error=None, partitions={p: None for p in broker.bounds}),
                    "private.context.v1": SimpleNamespace(error=None, partitions={0: None}),
                }
            )

        def get_watermark_offsets(self, partition, timeout):
            return broker.bounds[partition.partition]

        def assign(self, partitions):
            for partition in partitions:
                self.queue.extend(
                    (partition.partition, offset, value)
                    for offset, value in broker.records.get(partition.partition, [])
                    if offset >= partition.offset
                )
            self.queue.sort(key=lambda value: value[:2])

        def poll(self, timeout):
            if not self.queue:
                return None
            partition, offset, value = self.queue.pop(0)
            return SimpleNamespace(
                error=lambda: None,
                topic=lambda: TOPIC,
                partition=lambda: partition,
                offset=lambda: offset,
                value=lambda: value if isinstance(value, bytes) else core.encode_record(value),
            )

        def close(self):
            self.closed = True

    monkeypatch.setitem(
        sys.modules,
        "confluent_kafka",
        SimpleNamespace(
            Consumer=Consumer,
            TopicPartition=lambda topic, partition, offset=None: SimpleNamespace(
                topic=topic, partition=partition, offset=offset
            ),
        ),
    )
    monkeypatch.setitem(
        sys.modules,
        "confluent_kafka.admin",
        SimpleNamespace(
            AdminClient=lambda config: SimpleNamespace(
                list_topics=lambda timeout: Consumer(config).list_topics(timeout=timeout)
            ),
        ),
    )
    broker.catalogue = api.ContextCatalogue("fixture:9092", PREFIX)
    return broker


def registered(request):
    target = request["target"]
    return {
        "artifact": {
            "context_names": request["context"]["names"],
            "configuration": request["configuration"],
            "source": {
                "series_id": target["series_id"],
                "unit": target["unit"],
                "time_kind": target["time_kind"],
                "nobs": len(target["values"]),
                "t_start_micros": 1000,
                "t_end_micros": 1160,
                "interval_micros": 10,
                "values_sha256": hashlib.sha256(
                    np.asarray(target["values"], dtype="<f8").tobytes()
                ).hexdigest(),
            },
        }
    }


def retained(broker):
    capture = broker.catalogue.capture(document())
    request = {
        key: copy.deepcopy(value)
        for key, value in document().items()
        if key not in ("topic", "partition")
    }
    request.update(schema="opentsx.modelling-request.v1", context=capture["context"])
    return request


def test_context_topic_discovery_uses_the_existing_namespace(broker):
    assert broker.catalogue.topics() == {"schema": "opentsx.context-topics.v1", "topics": [TOPIC]}


def test_context_series_exposes_schema_units_clock_and_partition(broker):
    result = broker.catalogue.series(TOPIC)
    assert result["schema"] == "opentsx.context-series.v1"
    assert result["capture_policy"] == "latest_200_context_records"
    item = result["contexts"][0]
    assert item["partition"] == 0
    assert item["context_schema"]["names"] == ["schedule_sin", "schedule_cos"]
    assert item["context_schema"]["units"] == ["1", "1"]
    assert (item["first_micros"], item["last_micros"], item["row_count"]) == (1000, 1190, 20)


def test_capture_builds_exact_historical_future_and_availability_matrices(broker):
    result = broker.catalogue.capture(document())
    context = result["context"]
    assert result["schema"] == "opentsx.context-capture.v1"
    assert context["names"] == ["schedule_sin", "schedule_cos"]
    assert context["units"] == ["1", "1"]
    assert context["historical"] == broker.records[0][0][1]["values"][:16]
    assert context["future"] == broker.records[0][0][1]["values"][16:20]
    assert context["historical_available_at_micros"] == [[500, 500]] * 16
    assert result["binding"]["source_positions"] == [
        {"offset": "0", "record_id": broker.records[0][0][1]["record_id"]}
    ]
    assert result["binding"]["schema_id"] == result["context_schema"]["schema_id"]
    assert len(result["receipt_id"]) == 64
    assert broker.consumers[-1].closed


def test_capture_identity_changes_when_the_target_window_changes(broker):
    original = broker.catalogue.capture(document())
    changed = document()
    changed["target"]["values"][0] += 1
    result = broker.catalogue.capture(changed)
    assert result["target_digest"] != original["target_digest"]
    assert result["receipt_id"] != original["receipt_id"]


def test_binding_verifies_the_server_retained_context_without_trusting_new_client_arrays(broker):
    request = retained(broker)
    receipt = broker.catalogue.verify_binding(registered(request), request, TOPIC, 0)
    assert receipt["schema"] == "opentsx.context-binding.v1"
    assert receipt["source_positions"][0]["offset"] == "0"
    assert receipt["schema_id"]


@pytest.mark.parametrize(
    "field",
    [
        "historical",
        "future",
        "historical_available_at_micros",
        "future_available_at_micros",
        "units",
        "names",
    ],
)
def test_binding_rejects_changed_values_availability_units_or_column_order(broker, field):
    request = retained(broker)
    if field in ("units", "names"):
        request["context"][field] = list(reversed(request["context"][field]))
        if field == "units":
            request["context"][field][0] = "MW"
    else:
        request["context"][field][0][0] += 1
    with pytest.raises(ValueError):
        broker.catalogue.verify_binding(registered(request), request, TOPIC, 0)


@pytest.mark.parametrize("change", ["late", "missing", "wrong_target", "relative", "wrong_family"])
def test_capture_rejects_unavailable_or_incompatible_context(broker, change):
    request = document()
    old = broker.records[0][0][1]
    if change == "late":
        broker.records[0][0] = (
            0,
            broker.core.make_record(
                "target-a",
                old["context_names"],
                old["context_units"],
                1000,
                10,
                500,
                old["values"],
                [[2000, 2000]] * 20,
            ),
        )
    elif change == "missing":
        broker.records[0][0] = (
            0,
            broker.core.make_record(
                "target-a",
                old["context_names"],
                old["context_units"],
                1000,
                10,
                500,
                old["values"][:18],
                [[500, 500]] * 18,
            ),
        )
    elif change == "wrong_target":
        request["target"]["series_id"] = "other"
    elif change == "relative":
        request["target"]["time_kind"] = "relative"
    else:
        request["configuration"]["family"] = "arima"
    with pytest.raises((ValueError, RuntimeError)):
        broker.catalogue.capture(request)


def test_capture_rejects_values_that_arrived_after_an_earlier_backtest_origin(broker):
    old = broker.records[0][0][1]
    availability = [[500, 500] for _ in range(20)]
    availability[12] = [1120, 1120]
    broker.records[0][0] = (
        0,
        broker.core.make_record(
            "target-a",
            old["context_names"],
            old["context_units"],
            1000,
            10,
            500,
            old["values"],
            availability,
        ),
    )
    with pytest.raises(ValueError, match="available"):
        broker.catalogue.capture(document())


@pytest.mark.parametrize(
    "change", ["duplicate", "overlap", "schema", "offset_gap", "payload", "partition_count"]
)
def test_context_snapshot_conflicts_and_bounds_fail_closed(broker, change):
    old = broker.records[0][0][1]
    if change == "partition_count":
        broker.bounds = {p: (0, 0) for p in range(17)}
    elif change == "payload":
        broker.records[0][0] = (0, b"x" * (4 * 1024 * 1024 + 1))
    elif change == "offset_gap":
        broker.bounds = {0: (0, 2)}
        broker.records = {0: [(1, old)]}
    else:
        broker.bounds = {0: (0, 2)}
        next_record = old
        if change == "overlap":
            next_record = broker.core.make_record(
                "target-a",
                old["context_names"],
                old["context_units"],
                1100,
                10,
                500,
                old["values"],
                [[500, 500]] * 20,
            )
        elif change == "schema":
            next_record = broker.core.make_record(
                "target-a", ["other"], ["MW"], 1200, 10, 500, [[1.0]] * 4, [[500]] * 4
            )
        broker.records[0].append((1, next_record))
    with pytest.raises((ValueError, RuntimeError)):
        broker.catalogue.capture(document())
    assert broker.consumers[-1].closed


def test_context_namespace_is_checked_before_any_consumer_is_created(broker):
    with pytest.raises(ValueError):
        broker.catalogue.series("private.context.v1")
    assert broker.consumers == []


def test_snapshot_rejects_nonfinite_json_at_the_boundary(broker):
    broker.records = {0: [(0, b'{"value":NaN}')]}
    with pytest.raises(ValueError):
        broker.catalogue.snapshot(TOPIC)


def test_binding_requires_context_units_in_the_original_training_request(broker):
    request = retained(broker)
    del request["context"]["units"]
    with pytest.raises(ValueError, match="unit"):
        broker.catalogue.verify_binding(registered(request), request, TOPIC, 0)


@pytest.mark.parametrize("operation", ["capture", "binding"])
@pytest.mark.parametrize("placement", ["before", "after"])
def test_selected_partition_rejects_unrelated_schemas_outside_the_requested_range(
    broker, operation, placement
):
    request = retained(broker)
    original = broker.records[0][0][1]
    unrelated = broker.core.make_record(
        "other-target",
        original["context_names"],
        original["context_units"],
        800 if placement == "before" else 1200,
        10,
        500,
        original["values"],
        original["available_at_micros"],
    )
    ordered = [unrelated, original] if placement == "before" else [original, unrelated]
    broker.records = {0: list(enumerate(ordered))}
    broker.bounds = {0: (0, 2)}
    before = copy.deepcopy((broker.records, request))
    with pytest.raises(ValueError, match="schema"):
        if operation == "capture":
            broker.catalogue.capture(document())
        else:
            broker.catalogue.verify_binding(registered(request), request, TOPIC, 0)
    assert (broker.records, request) == before


@pytest.mark.parametrize("operation", ["capture", "binding"])
@pytest.mark.parametrize("placement", ["before", "after"])
def test_selected_partition_rejects_clock_gaps_outside_the_requested_range(
    broker, operation, placement
):
    request = retained(broker)
    original = broker.records[0][0][1]
    separated = broker.core.make_record(
        "target-a",
        original["context_names"],
        original["context_units"],
        800 if placement == "before" else 1300,
        10,
        500,
        original["values"][:10],
        original["available_at_micros"][:10],
    )
    ordered = [separated, original] if placement == "before" else [original, separated]
    broker.records = {0: list(enumerate(ordered))}
    broker.bounds = {0: (0, 2)}
    with pytest.raises(ValueError, match="gap|contiguous"):
        if operation == "capture":
            broker.catalogue.capture(document())
        else:
            broker.catalogue.verify_binding(registered(request), request, TOPIC, 0)


@pytest.mark.parametrize("other", ["gap", "mixed_schema", "malformed"])
def test_selected_partition_is_independent_of_other_partition_histories(broker, other):
    original = broker.records[0][0][1]
    first = broker.core.make_record(
        "other-target",
        original["context_names"],
        original["context_units"],
        800,
        10,
        500,
        original["values"][:10],
        original["available_at_micros"][:10],
    )
    second = broker.core.make_record(
        "third-target" if other == "mixed_schema" else "other-target",
        original["context_names"],
        original["context_units"],
        1000,
        10,
        500,
        original["values"][:10],
        original["available_at_micros"][:10],
    )
    broker.records[1] = [(0, first), (1, b"not-json" if other == "malformed" else second)]
    broker.bounds[1] = (0, 2)
    captured = broker.catalogue.capture(document())
    assert captured["binding"]["partition"] == 0
    request = retained(broker)
    with pytest.raises(ValueError, match="whole topic"):
        broker.catalogue.verify_binding(registered(request), request, TOPIC, 0)


def test_whole_topic_context_binding_rejects_an_additional_empty_partition(broker):
    request = retained(broker)
    broker.bounds[1] = (0, 0)
    assert broker.catalogue.capture(document())["context"] == request["context"]
    with pytest.raises(ValueError, match="whole topic"):
        broker.catalogue.verify_binding(registered(request), request, TOPIC, 0)
