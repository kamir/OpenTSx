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


@pytest.fixture
def broker(monkeypatch):
    broker = SimpleNamespace(
        records=[], partitions=None, created=[], delivered=[], configs=[], error=None, remaining=0
    )

    class Admin:
        def __init__(self, config):
            broker.configs.append(config)

        def list_topics(self, timeout):
            topics = (
                {}
                if broker.partitions is None
                else {TOPIC: SimpleNamespace(error=None, partitions=broker.partitions)}
            )
            return SimpleNamespace(topics=topics)

        def create_topics(self, topics, request_timeout):
            broker.created.extend(topics)
            broker.partitions = {0: None}
            return {TOPIC: SimpleNamespace(result=lambda timeout: None)}

    class Consumer:
        def __init__(self, config):
            broker.configs.append(config)
            self.position = None
            self.closed = False

        def get_watermark_offsets(self, partition, timeout):
            return 0, len(broker.records)

        def assign(self, partitions):
            self.position = partitions[0]

        def poll(self, timeout):
            if self.position.offset >= len(broker.records):
                return None
            offset, raw = self.position.offset, broker.records[self.position.offset]
            return SimpleNamespace(
                error=lambda: None,
                topic=lambda: TOPIC,
                partition=lambda: 0,
                offset=lambda: offset,
                value=lambda: raw,
                key=lambda: model.decode(raw)[1]["series"]["seriesId"].encode(),
            )

        def close(self):
            self.closed = True

    class Producer:
        def __init__(self, config):
            broker.configs.append(config)
            self.pending = []

        def produce(self, topic, *, partition, key, value, timestamp, on_delivery):
            assert topic == TOPIC and partition == 0
            broker.records.append(value)
            broker.delivered.append((key, timestamp, value))
            self.pending.append(on_delivery)

        def flush(self, timeout):
            if not broker.remaining:
                for callback in self.pending:
                    callback(
                        broker.error,
                        SimpleNamespace(
                            partition=lambda: 0, offset=lambda: len(broker.records) - 1
                        ),
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
    broker.run = lambda command: broker.api.execute(command, "fixture:9092", PREFIX)
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


def test_cli_has_only_seed_append_and_the_required_bootstrap(broker, capsys):
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
