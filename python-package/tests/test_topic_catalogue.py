import copy
import hashlib
import importlib
import sys
from types import SimpleNamespace

import numpy as np
import pytest

from opentsx import model

PREFIX = "tsx.workbench-demo."
TOPIC = PREFIX + "voltage.episodes.v2"


def episode(start=1000, values=None, identifier="episode-a", **kwargs):
    return model.regular(
        model.series_key("voltage", {"origin": "SYNTHETIC"}, "V"),
        start,
        10,
        list(range(16)) if values is None else values,
        episode_id=identifier,
        created_at_us=0,
        **kwargs,
    )


class Consumer:
    def __init__(self, broker, config):
        self.broker, self.config = broker, config
        self.closed = False
        self.assigned = []
        self.messages = []

    def list_topics(self, topic, timeout):
        return SimpleNamespace(
            topics={
                topic: SimpleNamespace(error=None, partitions={p: None for p in self.broker.bounds})
            }
        )

    def get_watermark_offsets(self, partition, timeout):
        return self.broker.bounds[partition.partition]

    def assign(self, partitions):
        self.assigned = partitions
        for partition in partitions:
            for offset, record in self.broker.records.get(partition.partition, []):
                if offset >= partition.offset:
                    self.messages.append((partition.partition, offset, record))
        self.messages.sort(key=lambda value: value[:2])

    def poll(self, timeout):
        if not self.messages:
            return None
        partition, offset, record = self.messages.pop(0)
        return SimpleNamespace(
            error=lambda: None,
            topic=lambda: TOPIC,
            partition=lambda: partition,
            offset=lambda: offset,
            value=lambda: record if isinstance(record, bytes) else model.encode("Episode", record),
        )

    def close(self):
        self.closed = True


@pytest.fixture
def broker(monkeypatch):
    broker = SimpleNamespace(bounds={0: (0, 1)}, records={0: [(0, episode())]}, consumers=[])

    def consumer(config):
        result = Consumer(broker, config)
        broker.consumers.append(result)
        return result

    monkeypatch.setitem(
        sys.modules,
        "confluent_kafka",
        SimpleNamespace(
            Consumer=consumer,
            TopicPartition=lambda topic, partition, offset=None: SimpleNamespace(
                topic=topic,
                partition=partition,
                offset=offset,
            ),
        ),
    )
    api = importlib.import_module("opentsx.client.topic_catalogue")
    broker.catalogue = api.TopicCatalogue("fixture:9092", PREFIX)
    return broker


def document(samples=12):
    return {"topic": TOPIC, "series_id": episode()["series"]["seriesId"], "samples": samples}


def registered(values=None, start=1000):
    training = list(range(12)) if values is None else values
    return {
        "artifact": {
            "artifact_id": "a" * 64,
            "training_values": training,
            "source": {
                "series_id": episode()["series"]["seriesId"],
                "unit": "V",
                "time_kind": "absolute",
                "interval_micros": 10,
                "t_start_micros": start,
                "t_end_micros": start + len(training) * 10,
                "nobs": len(training),
                "values_sha256": hashlib.sha256(
                    np.asarray(training, dtype="<f8").tobytes()
                ).hexdigest(),
            },
        }
    }


def test_catalogue_keeps_actual_series_clock_unit_and_partition(broker):
    result = broker.catalogue.series(TOPIC)
    assert result["schema"] == "opentsx.modelling-series.v1"
    assert result["capture_policy"] == "latest_200_records"
    item = result["series"][0]
    assert item["series_id"] == document()["series_id"]
    assert item["unit"] == "V" and item["interval_micros"] == 10
    assert item["partitions"] == [0]
    assert (item["count"], item["first_micros"], item["last_micros"]) == (16, 1000, 1150)
    assert broker.consumers[0].closed
    assert broker.consumers[0].config["enable.auto.commit"] is False
    assert broker.consumers[0].config["enable.auto.offset.store"] is False
    assert broker.consumers[0].config["allow.auto.create.topics"] is False


def test_capture_clips_the_actual_range_and_preserves_unknown_quality(broker):
    result = broker.catalogue.capture(document())
    assert result["schema"] == "opentsx.modelling-capture.v1"
    record = result["episodes"][0]
    assert record["episodeId"] == "episode-a"
    assert record["values"] == list(range(4, 16))
    assert record["timestampsMicros"] == list(range(1040, 1160, 10))
    assert record["labels"]["capture.clipped"] == "true"
    assert "quality" not in record
    assert record["tStart"].endswith(".001040Z")
    assert record["tEnd"].endswith(".001160Z")


def test_capture_joins_contiguous_episodes_without_changing_episode_identity(broker):
    broker.bounds = {0: (0, 2)}
    broker.records = {
        0: [
            (0, episode(values=list(range(8)))),
            (1, episode(1080, list(range(8, 16)), "episode-b")),
        ]
    }
    result = broker.catalogue.capture(document(12))
    assert [entry["episodeId"] for entry in result["episodes"]] == ["episode-a", "episode-b"]
    assert sum(len(entry["values"]) for entry in result["episodes"]) == 12


def test_capture_preserves_quality_bytes_for_the_workbench(broker):
    broker.records[0][0][1]["quality"] = bytes(range(16))
    assert broker.catalogue.capture(document())["episodes"][0]["quality"] == list(range(4, 16))


def test_binding_checks_every_registered_sample_and_retains_source_offsets(broker):
    result = broker.catalogue.verify_binding(registered(), TOPIC, 0)
    assert result["schema"] == "opentsx.modelling-binding.v1"
    assert result["series_id"] == document()["series_id"]
    assert result["source_positions"][0]["offset"] == "0"
    assert result["quality_status"] == "unknown"


@pytest.mark.parametrize("change", ["values", "quality", "unit", "missing", "partition"])
def test_binding_rejects_unproven_or_incompatible_training(broker, change):
    fitted = registered()
    if change == "values":
        broker.records[0][0][1]["values"][5] += 1
    elif change == "quality":
        broker.records[0][0][1]["quality"] = b"\x01" * 16
    elif change == "unit":
        fitted["artifact"]["source"]["unit"] = "kV"
    elif change == "missing":
        fitted = registered(list(range(12)), start=2000)
    else:
        broker.bounds = {0: (0, 0), 1: (0, 1)}
        broker.records = {1: broker.records[0]}
    with pytest.raises(ValueError):
        broker.catalogue.verify_binding(fitted, TOPIC, 0)


def test_binding_can_cross_episode_boundaries_and_accept_explicit_zero_quality(broker):
    broker.bounds = {0: (0, 2)}
    broker.records = {
        0: [
            (0, episode(values=list(range(8)), quality=b"\0" * 8)),
            (1, episode(1080, list(range(8, 16)), "episode-b", quality=b"\0" * 8)),
        ]
    }
    result = broker.catalogue.verify_binding(registered(list(range(4, 16)), start=1040), TOPIC, 0)
    assert result["quality_status"] == "unflagged"
    assert len(result["source_positions"]) == 2


@pytest.mark.parametrize("change", ["duplicate", "revision", "overlap", "irregular"])
def test_unsupported_record_history_is_rejected(broker, change):
    record = copy.deepcopy(broker.records[0][0][1])
    if change == "revision":
        record["revision"] = 1
    elif change == "irregular":
        record["timeEncoding"] = "IRREGULAR_DELTA"
    else:
        broker.bounds = {0: (0, 2)}
        if change == "overlap":
            record["episodeId"] = "episode-b"
        broker.records[0].append((1, record))
    if change in ("revision", "irregular"):
        broker.records = {0: [(0, record)]}
    with pytest.raises(ValueError):
        broker.catalogue.capture(document())


def test_ambiguous_partition_ownership_is_visible_but_cannot_be_captured_or_bound(broker):
    broker.bounds = {0: (0, 1), 1: (0, 1)}
    broker.records[1] = [(0, episode(1160, list(range(16, 32)), "episode-b"))]
    assert broker.catalogue.series(TOPIC)["series"][0]["partitions"] == [0, 1]
    with pytest.raises(ValueError, match="partition"):
        broker.catalogue.capture(document())
    with pytest.raises(ValueError, match="partition"):
        broker.catalogue.verify_binding(registered(), TOPIC, 0)


def test_snapshot_reads_only_the_admitted_tail_and_preserves_exact_offsets(broker):
    broker.bounds = {0: (5, 210)}
    broker.records = {
        0: [
            (offset, episode(1000 + offset * 10, [offset], f"episode-{offset}"))
            for offset in range(5, 210)
        ]
    }
    result = broker.catalogue.snapshot(TOPIC)
    assert len(result) == 200
    assert (result[0]["offset"], result[-1]["offset"]) == (10, 209)


def test_empty_topic_has_no_invented_series_or_capture(broker):
    broker.bounds = {0: (0, 0)}
    broker.records = {0: []}
    assert broker.catalogue.series(TOPIC)["series"] == []
    with pytest.raises(ValueError):
        broker.catalogue.capture(document())


@pytest.mark.parametrize("change", ["partitions", "payload", "cells", "offset_gap"])
def test_snapshot_limits_and_missing_offsets_fail_closed(broker, change):
    if change == "partitions":
        broker.bounds = {p: (0, 0) for p in range(17)}
    elif change == "payload":
        broker.records = {0: [(0, b"x" * (4 * 1024 * 1024 + 1))]}
    elif change == "cells":
        broker.records = {0: [(0, episode(values=[0] * 262145))]}
    else:
        broker.bounds = {0: (0, 2)}
        broker.records = {0: [(1, episode())]}
    with pytest.raises((ValueError, RuntimeError)):
        broker.catalogue.snapshot(TOPIC)
    assert broker.consumers[-1].closed


def test_namespace_rejection_precedes_broker_access(broker):
    with pytest.raises(ValueError):
        broker.catalogue.series("private.episodes.v2")
    assert broker.consumers == []


def test_capture_does_not_close_a_gap_by_concatenating_unrelated_clock_ranges(broker):
    broker.bounds = {0: (0, 2)}
    broker.records = {
        0: [(0, episode(values=list(range(8)))), (1, episode(2000, list(range(8)), "episode-b"))]
    }
    with pytest.raises(ValueError, match="gap"):
        broker.catalogue.capture(document(12))


def test_a_finished_partition_eof_does_not_abort_other_admitted_partitions(broker, monkeypatch):
    broker.bounds = {0: (0, 1), 1: (0, 1)}
    second = episode(identifier="episode-b")
    second["series"] = model.series_key("current", {"origin": "SYNTHETIC"}, "A")
    broker.records[1] = [(0, second)]
    original = Consumer.poll
    eof_sent = False

    def poll(consumer, timeout):
        nonlocal eof_sent
        if consumer.messages and consumer.messages[0][0] == 1 and not eof_sent:
            eof_sent = True
            return SimpleNamespace(
                error=lambda: SimpleNamespace(code=lambda: -191), partition=lambda: 0
            )
        return original(consumer, timeout)

    monkeypatch.setattr(Consumer, "poll", poll)
    assert len(broker.catalogue.series(TOPIC)["series"]) == 2


def test_snapshot_deadline_is_one_total_budget_and_closes_the_consumer(broker, monkeypatch):
    api = importlib.import_module("opentsx.client.topic_catalogue")
    times = iter([0, 0, 11])
    monkeypatch.setattr(api, "monotonic", lambda: next(times))
    with pytest.raises(TimeoutError, match="ten-second"):
        broker.catalogue.snapshot(TOPIC)
    assert broker.consumers[-1].closed


def test_capture_retains_segmentation_and_parent_lineage(broker):
    record = broker.records[0][0][1]
    record["segmentation"] = {
        "strategy": "FIXED_COUNT",
        "params": {"count": "16"},
        "anchorTs": None,
    }
    record["provenance"]["parentEpisodeId"] = "parent-a"
    captured = broker.catalogue.capture(document())["episodes"][0]
    assert captured["segmentation"] == {"strategy": "FIXED_COUNT", "params": {"count": "16"}}
    assert captured["parentEpisodeId"] == "parent-a"


def test_missing_samples_remain_null_in_capture_and_never_become_training_proof(broker):
    broker.records[0][0][1]["values"][5] = float("nan")
    result = broker.catalogue.capture(document())
    assert result["episodes"][0]["values"][1] is None
    with pytest.raises(ValueError, match="not proven"):
        broker.catalogue.verify_binding(registered(), TOPIC, 0)


def test_whole_topic_binding_rejects_an_additional_empty_partition_but_capture_remains_available(
    broker,
):
    broker.bounds[1] = (0, 0)
    assert broker.catalogue.capture(document())["episodes"]
    with pytest.raises(ValueError, match="whole topic"):
        broker.catalogue.verify_binding(registered(), TOPIC, 0)
