import copy
import importlib
import json
import re
import sys
from types import SimpleNamespace

import pytest

from opentsx import model

PREFIX = "tsx.workbench-demo."
TOPIC = PREFIX + "forecast.episodes.v2"
SARIMAX_TOPIC = PREFIX + "sarimax.episodes.v2"
CONTEXT_TOPIC = PREFIX + "sarimax.context.v1"


@pytest.fixture
def broker(monkeypatch):
    broker = SimpleNamespace(
        records=[],
        partitions=None,
        created=[],
        delivered=[],
        configs=[],
        error=None,
        remaining=0,
        other_records={SARIMAX_TOPIC: [], CONTEXT_TOPIC: []},
        other_partitions={},
        publications=[],
    )

    def records(topic):
        return broker.records if topic == TOPIC else broker.other_records[topic]

    class Admin:
        def __init__(self, config):
            broker.configs.append(config)

        def list_topics(self, timeout):
            topics = {
                name: SimpleNamespace(error=None, partitions=partitions)
                for name, partitions in broker.other_partitions.items()
            }
            if broker.partitions is not None:
                topics[TOPIC] = SimpleNamespace(error=None, partitions=broker.partitions)
            return SimpleNamespace(topics=topics)

        def create_topics(self, topics, request_timeout):
            broker.created.extend(topics)
            for topic in topics:
                if topic.topic == TOPIC:
                    broker.partitions = {0: None}
                else:
                    broker.other_partitions[topic.topic] = {0: None}
            return {topic.topic: SimpleNamespace(result=lambda timeout: None) for topic in topics}

    class Consumer:
        def __init__(self, config):
            broker.configs.append(config)
            self.position = None
            self.closed = False

        def get_watermark_offsets(self, partition, timeout):
            return 0, len(records(partition.topic))

        def assign(self, partitions):
            self.position = partitions[0]

        def poll(self, timeout):
            if self.position.offset >= len(records(self.position.topic)):
                return None
            offset, raw = self.position.offset, records(self.position.topic)[self.position.offset]
            return SimpleNamespace(
                error=lambda: None,
                topic=lambda: self.position.topic,
                partition=lambda: 0,
                offset=lambda: offset,
                value=lambda: raw,
                key=lambda: (
                    json.loads(raw)["target_series_id"]
                    if self.position.topic == CONTEXT_TOPIC
                    else model.decode(raw)[1]["series"]["seriesId"]
                ).encode(),
            )

        def close(self):
            self.closed = True

    class Producer:
        def __init__(self, config):
            broker.configs.append(config)
            self.pending = []

        def produce(self, topic, *, partition, key, value, timestamp, on_delivery):
            assert topic in (TOPIC, SARIMAX_TOPIC, CONTEXT_TOPIC) and partition == 0
            records(topic).append(value)
            broker.delivered.append((key, timestamp, value))
            broker.publications.append((topic, key, timestamp, value))
            self.pending.append((on_delivery, len(records(topic)) - 1))

        def flush(self, timeout):
            if not broker.remaining:
                for callback, offset in self.pending:
                    callback(
                        broker.error,
                        SimpleNamespace(partition=lambda: 0, offset=lambda: offset),
                    )
            return broker.remaining

    monkeypatch.setitem(
        sys.modules,
        "confluent_kafka",
        SimpleNamespace(
            Consumer=Consumer,
            Producer=Producer,
            TopicPartition=lambda topic, partition, offset=None: SimpleNamespace(
                topic=topic, partition=partition, offset=offset
            ),
        ),
    )
    monkeypatch.setitem(
        sys.modules,
        "confluent_kafka.admin",
        SimpleNamespace(
            AdminClient=Admin,
            NewTopic=lambda topic, num_partitions, replication_factor, config=None: SimpleNamespace(
                topic=topic,
                num_partitions=num_partitions,
                replication_factor=replication_factor,
                config=config,
            ),
        ),
    )
    broker.api = importlib.import_module("opentsx.client.scoring_demo")
    broker.run = lambda command, scenario="ar1": (
        broker.api.execute(command, "fixture:9092", PREFIX)
        if scenario == "ar1"
        else broker.api.execute(command, "fixture:9092", PREFIX, scenario)
    )
    return broker


def test_seed_creates_only_the_owned_topic_and_publishes_good_synthetic_ar1(broker):
    result = broker.run("seed")
    assert result["status"] == "seeded"
    assert len(broker.created) == 1
    topic = broker.created[0]
    assert (topic.topic, topic.num_partitions, topic.replication_factor) == (TOPIC, 1, 1)
    assert topic.config == {"min.insync.replicas": "1"}
    name, record = model.decode(broker.records[0])
    assert name == "Episode" and record["count"] == 512
    assert record["series"] == model.series_key(
        "research.synthetic.ar1",
        {"source": "opentsx-scoring-demo", "origin": "SYNTHETIC"},
        "MW",
    )
    assert record["quality"] == bytes(512)
    assert record["intervalMicros"] == 1_000_000
    assert record["labels"]["origin"] == "SYNTHETIC"
    assert "SYNTHETIC" in record["provenance"]["source"]
    assert re.fullmatch(r"[0-7][0-9A-HJKMNP-TV-Z]{25}", record["episodeId"])
    assert any(
        config.get("acks") == "all" and config.get("enable.idempotence") is False
        for config in broker.configs
    )


def test_seed_is_idempotent_after_owned_data_was_verified(broker):
    broker.run("seed")
    before = broker.records.copy()
    assert broker.run("seed")["status"] == "already_seeded"
    assert broker.records == before


def test_append_extends_the_signal_without_rewriting_old_records(broker):
    broker.run("seed")
    first = broker.records[0]
    result = broker.run("append")
    assert result["status"] == "appended"
    old = model.decode(first)[1]
    new = model.decode(broker.records[1])[1]
    assert new["count"] == 64
    assert new["tStart"] == old["tEnd"]
    assert new["values"][0] == 0.65 * old["values"][-1] + broker.api.noise(512)
    assert new["provenance"]["parentEpisodeId"] == old["episodeId"]
    assert broker.records[0] == first
    assert new["episodeId"] != old["episodeId"]
    broker.run("append")
    last = model.decode(broker.records[-1])[1]
    assert last["labels"]["demo.start_index"] == "576"
    assert last["tStart"] == new["tEnd"]


def test_the_same_commands_generate_identical_wire_bytes_and_timestamps(broker):
    broker.run("seed")
    broker.run("append")
    before = broker.delivered.copy()
    broker.records.clear()
    broker.delivered.clear()
    broker.partitions = None
    broker.run("seed")
    broker.run("append")
    assert broker.delivered == before


@pytest.mark.parametrize(
    "change", ["series", "unit", "clock", "count", "labels", "quality", "values", "revision"]
)
def test_seed_and_append_refuse_corrupt_or_foreign_records(broker, change):
    broker.run("seed")
    record = copy.deepcopy(model.decode(broker.records[0])[1])
    if change == "series":
        record["series"] = model.series_key("foreign", {}, "MW")
    elif change == "unit":
        record["series"]["unit"] = "kW"
    elif change == "clock":
        record["intervalMicros"] = 2_000_000
    elif change == "count":
        record["count"] = 64
    elif change == "labels":
        record["labels"]["origin"] = "unknown"
    elif change == "quality":
        record["quality"] = b"\x01" * 512
    elif change == "revision":
        record["revision"] = 1
    else:
        record["values"][5] += 1
    broker.records[0] = model.encode("Episode", record)
    for command in ["seed", "append"]:
        with pytest.raises(ValueError):
            broker.run(command)
    assert len(broker.records) == 1


def test_append_requires_seed_data_and_never_creates_a_missing_topic(broker):
    with pytest.raises(ValueError, match="seed"):
        broker.run("append")
    assert broker.created == []


def test_unexpected_partition_count_is_not_changed_or_ignored(broker):
    broker.partitions = {0: None, 1: None}
    with pytest.raises(ValueError, match="partition"):
        broker.run("seed")
    assert broker.created == [] and broker.records == []


@pytest.mark.parametrize("remaining,error", [(1, None), (0, "broker refused delivery")])
def test_missing_delivery_acknowledgement_is_an_explicit_failure(broker, remaining, error):
    broker.remaining, broker.error = remaining, error
    with pytest.raises((TimeoutError, RuntimeError), match="acknowledged"):
        broker.run("seed")


def test_invalid_prefix_is_rejected_before_any_broker_access(broker):
    with pytest.raises(ValueError):
        broker.api.execute("seed", "fixture:9092", "../private.")
    assert broker.configs == []


def test_cli_lists_supported_commands_and_requires_bootstrap(broker, capsys):
    with pytest.raises(SystemExit) as result:
        broker.api.main(["--help"])
    assert result.value.code == 0
    output = capsys.readouterr().out
    assert (
        "--bootstrap" in output and "--prefix" in output and "seed" in output and "append" in output
    )
    with pytest.raises(SystemExit):
        broker.api.main(["seed"])
    broker.api.main(["seed", "--bootstrap", "fixture:9092"])
    assert json.loads(capsys.readouterr().out)["status"] == "seeded"


def test_sarimax_seed_publishes_known_ahead_context_before_the_target(broker):
    result = broker.run("seed", "sarimax")
    assert result["topic"] == SARIMAX_TOPIC and result["context_topic"] == CONTEXT_TOPIC
    assert [entry[0] for entry in broker.publications] == [CONTEXT_TOPIC, SARIMAX_TOPIC]
    target = model.decode(broker.other_records[SARIMAX_TOPIC][0])[1]
    context = json.loads(broker.other_records[CONTEXT_TOPIC][0])
    assert target["count"] == 512 and len(context["values"]) == 516
    assert context["context_names"] == ["schedule_sin", "schedule_cos"]
    assert context["context_units"] == ["1", "1"]
    assert context["target_series_id"] == target["series"]["seriesId"]
    assert context["issued_at_micros"] == broker.api.EPOCH - broker.api.INTERVAL
    assert all(
        stamp <= broker.api.EPOCH - broker.api.INTERVAL
        for row in context["available_at_micros"]
        for stamp in row
    )
    assert broker.records == []


def test_sarimax_append_preserves_the_old_lookahead_and_adds_64_new_context_rows(broker):
    broker.run("seed", "sarimax")
    first = broker.other_records[CONTEXT_TOPIC][0]
    broker.run("append", "sarimax")
    assert len(broker.other_records[SARIMAX_TOPIC]) == 2
    context = json.loads(broker.other_records[CONTEXT_TOPIC][1])
    assert len(context["values"]) == 64
    assert context["t_start_micros"] == broker.api.EPOCH + 516 * broker.api.INTERVAL
    assert context["issued_at_micros"] == broker.api.EPOCH + 511 * broker.api.INTERVAL
    assert broker.other_records[CONTEXT_TOPIC][0] == first
    assert [entry[0] for entry in broker.publications[-2:]] == [CONTEXT_TOPIC, SARIMAX_TOPIC]


def test_split_sarimax_commands_can_expose_missing_context_then_release_it(broker):
    broker.run("seed", "sarimax")
    result = broker.run("target", "sarimax")
    assert result["status"] == "appended"
    assert len(broker.other_records[SARIMAX_TOPIC]) == 2
    assert len(broker.other_records[CONTEXT_TOPIC]) == 1
    result = broker.run("context", "sarimax")
    assert result["status"] == "context_ready"
    assert len(broker.other_records[SARIMAX_TOPIC]) == 2
    assert len(broker.other_records[CONTEXT_TOPIC]) == 2
    before = broker.publications.copy()
    broker.run("context", "sarimax")
    assert broker.publications == before


def test_sarimax_context_can_catch_up_multiple_pending_target_blocks(broker):
    broker.run("seed", "sarimax")
    broker.run("target", "sarimax")
    broker.run("target", "sarimax")
    broker.run("context", "sarimax")
    assert len(broker.other_records[CONTEXT_TOPIC]) == 3
    assert [len(json.loads(raw)["values"]) for raw in broker.other_records[CONTEXT_TOPIC]] == [
        516,
        64,
        64,
    ]


def test_sarimax_seed_is_idempotent_and_rejects_modified_context(broker):
    broker.run("seed", "sarimax")
    before = broker.publications.copy()
    assert broker.run("seed", "sarimax")["status"] == "already_seeded"
    assert broker.publications == before
    record = json.loads(broker.other_records[CONTEXT_TOPIC][0])
    record["values"][0][0] += 1
    broker.other_records[CONTEXT_TOPIC][0] = json.dumps(record).encode()
    with pytest.raises(ValueError):
        broker.run("append", "sarimax")


def test_context_record_helper_can_create_an_explicit_late_availability_exercise(broker):
    record = broker.api.context_record(
        516, issued_at_micros=broker.api.EPOCH + 600 * broker.api.INTERVAL
    )
    assert record["issued_at_micros"] > record["t_start_micros"]
    assert all(
        stamp == record["issued_at_micros"]
        for row in record["available_at_micros"]
        for stamp in row
    )


@pytest.mark.parametrize("command", ["target", "context"])
def test_split_commands_are_not_silent_ar1_aliases(broker, command):
    with pytest.raises(ValueError):
        broker.run(command)
