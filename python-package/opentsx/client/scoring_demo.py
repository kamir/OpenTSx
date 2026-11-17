"""Seed or append the owned synthetic AR(1) source for local scoring acceptance."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import re

import numpy as np

from opentsx import model
from opentsx.model.time_axis import to_us

EPOCH = to_us(datetime(2026, 11, 17, tzinfo=timezone.utc))
INTERVAL = 1_000_000
GENERATOR = "opentsx-scoring-demo.ar1.v1"
SOURCE = "opentsx-scoring-demo:SYNTHETIC"


def noise(index):
    digest = hashlib.sha256((GENERATOR + ":" + str(index)).encode()).digest()
    first = (int.from_bytes(digest[:8], "big") + 0.5) / 2**64
    second = (int.from_bytes(digest[8:16], "big") + 0.5) / 2**64
    return math.sqrt(-2 * math.log(first)) * math.cos(math.tau * second)


def _series():
    return model.series_key(
        "research.synthetic.ar1", {"source": "opentsx-scoring-demo", "origin": "SYNTHETIC"}, "MW"
    )


def _identity(index):
    return model.new_ulid(
        epoch_millis=(EPOCH + index * INTERVAL) // 1000,
        randomness=hashlib.sha256((GENERATOR + ":block:" + str(index)).encode()).digest()[:10],
    )


def _episode(index, previous):
    count = 512 if index == 0 else 64
    if index != 0 and (index < 512 or (index - 512) % 64):
        raise ValueError("synthetic source block index is invalid")
    if EPOCH + (index + count) * INTERVAL > 2**53 - 1:
        raise ValueError("synthetic source clock exceeds exact browser microseconds")
    values, current = [], previous
    for position in range(index, index + count):
        current = 0.65 * current + noise(position)
        values.append(current)
    labels = {
        "origin": "SYNTHETIC",
        "demo.generator": GENERATOR,
        "demo.start_index": str(index),
        "demo.previous_value": previous.hex(),
        "demo.values_sha256": hashlib.sha256(np.asarray(values, dtype="<f8").tobytes()).hexdigest(),
    }
    return model.regular(
        _series(),
        EPOCH + index * INTERVAL,
        INTERVAL,
        values,
        episode_id=_identity(index),
        quality=bytes(count),
        labels=labels,
        source=SOURCE,
        created_at_us=0,
        pipeline=GENERATOR,
        parent_episode_id=None if index == 0 else _identity(0 if index == 512 else index - 64),
    )


def _validate(raw, offset):
    name, episode = model.decode(raw)
    if name != "Episode" or episode["series"] != _series():
        raise ValueError("existing source is not the owned synthetic series")
    labels = episode["labels"]
    index_text = labels.get("demo.start_index", "")
    if not re.fullmatch(r"0|[1-9][0-9]{0,19}", index_text):
        raise ValueError("existing synthetic block index is missing or malformed")
    index = int(index_text)
    expected_index = 0 if offset == 0 else 512 + (offset - 1) * 64
    if index != expected_index:
        raise ValueError("synthetic source offset does not match its block index")
    try:
        previous = float.fromhex(labels["demo.previous_value"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("existing synthetic previous value is invalid") from error
    if not math.isfinite(previous) or (index == 0 and previous != 0):
        raise ValueError("existing synthetic previous value is incompatible")
    expected = _episode(index, previous)
    if model.encode("Episode", expected) != raw:
        raise ValueError("existing synthetic source data, clock, quality or provenance is corrupt")
    return episode, index


def _edges(bootstrap, topic):
    from confluent_kafka import Consumer, TopicPartition

    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap,
            "group.id": "opentsx-scoring-demo",
            "enable.auto.commit": False,
            "enable.auto.offset.store": False,
            "allow.auto.create.topics": False,
            "auto.offset.reset": "error",
        }
    )
    try:
        low, high = consumer.get_watermark_offsets(TopicPartition(topic, 0), timeout=10)
        if low < 0 or high < low:
            raise ValueError("synthetic source retained offsets are invalid")
        if high == low:
            return None, None, high
        records = []
        for offset in sorted({low, high - 1}):
            consumer.assign([TopicPartition(topic, 0, offset)])
            message = consumer.poll(10)
            if message is None:
                raise TimeoutError("synthetic source edge read timed out")
            if message.error() is not None:
                raise RuntimeError(f"synthetic source broker error: {message.error()}")
            if message.topic() != topic or message.partition() != 0 or message.offset() != offset:
                raise ValueError("synthetic source edge offset is unavailable")
            raw = message.value()
            if not isinstance(raw, bytes) or not 0 < len(raw) <= 4 * 1024 * 1024:
                raise ValueError("synthetic source payload is empty or oversized")
            if message.key() != _series()["seriesId"].encode():
                raise ValueError("synthetic source Kafka key does not match the owned series")
            records.append(_validate(raw, offset))
        return records[0], records[-1], high
    finally:
        consumer.close()


def execute(command, bootstrap, prefix="tsx.workbench-demo."):
    if command not in ("seed", "append"):
        raise ValueError("command must be seed or append")
    if not isinstance(prefix, str) or not re.fullmatch(
        r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\.", prefix
    ):
        raise ValueError("prefix must be a valid Kafka topic prefix ending in a dot")
    if not isinstance(bootstrap, str) or not bootstrap.strip():
        raise ValueError("bootstrap must be a nonempty broker address")
    topic = prefix + "forecast.episodes.v2"
    from confluent_kafka import Producer
    from confluent_kafka.admin import AdminClient, NewTopic

    admin = AdminClient({"bootstrap.servers": bootstrap})
    metadata = admin.list_topics(timeout=10)
    created = topic not in metadata.topics
    if created:
        if command != "seed":
            raise ValueError("synthetic source is absent; run seed first")
        futures = admin.create_topics(
            [
                NewTopic(
                    topic,
                    num_partitions=1,
                    replication_factor=1,
                    config={"min.insync.replicas": "1"},
                )
            ],
            request_timeout=10,
        )
        futures[topic].result(timeout=15)
        first, last, high = None, None, 0
    else:
        existing = metadata.topics[topic]
        if existing.error is not None or set(existing.partitions) != {0}:
            raise ValueError(
                "synthetic source requires exactly one healthy partition numbered zero"
            )
        first, last, high = _edges(bootstrap, topic)
    if command == "seed" and first is not None:
        return {
            "schema": "opentsx.scoring-demo.v1",
            "status": "already_seeded",
            "topic": topic,
            "series_id": _series()["seriesId"],
            "last_index": last[1],
        }
    if command == "append" and last is None:
        raise ValueError("synthetic source is empty; run seed first")
    if command == "seed":
        index, previous = 0, 0.0
    else:
        previous_episode, previous_index = last
        index = previous_index + previous_episode["count"]
        previous = previous_episode["values"][-1]
    episode = _episode(index, previous)
    raw = model.encode("Episode", episode)
    delivered, errors = [], []

    def acknowledge(error, record):
        if error is not None:
            errors.append(str(error))
        else:
            delivered.append((record.partition(), record.offset()))

    producer = Producer(
        {
            "bootstrap.servers": bootstrap,
            "acks": "all",
            "enable.idempotence": False,
            "max.in.flight.requests.per.connection": 1,
            "delivery.timeout.ms": 10000,
        }
    )
    producer.produce(
        topic,
        partition=0,
        key=_series()["seriesId"].encode(),
        value=raw,
        timestamp=(EPOCH + index * INTERVAL) // 1000,
        on_delivery=acknowledge,
    )
    if producer.flush(10):
        raise TimeoutError(
            "synthetic source publication was not acknowledged within its delivery budget"
        )
    if errors or len(delivered) != 1:
        raise RuntimeError("synthetic source publication was not acknowledged")
    if delivered[0] != (0, high):
        raise RuntimeError(
            "synthetic source changed during publication; concurrent append is unsupported"
        )
    return {
        "schema": "opentsx.scoring-demo.v1",
        "status": "seeded" if command == "seed" else "appended",
        "topic": topic,
        "series_id": _series()["seriesId"],
        "episode_id": episode["episodeId"],
        "start_index": index,
        "count": episode["count"],
        "partition": 0,
        "offset": str(high),
        "t_start_micros": EPOCH + index * INTERVAL,
        "t_end_micros": to_us(episode["tEnd"]),
        "origin": "SYNTHETIC",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("seed", "append"))
    parser.add_argument("--bootstrap", required=True)
    parser.add_argument("--prefix", default="tsx.workbench-demo.")
    args = parser.parse_args(argv)
    print(
        json.dumps(
            execute(args.command, args.bootstrap, args.prefix), sort_keys=True, allow_nan=False
        )
    )


if __name__ == "__main__":
    main()
