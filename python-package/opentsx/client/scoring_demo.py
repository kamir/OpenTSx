"""Publish deterministic synthetic targets and known-ahead context for local scoring acceptance."""

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
SARIMAX_GENERATOR = "opentsx-scoring-demo.sarimax.v1"
CONTEXT_NAMES = ["schedule_sin", "schedule_cos"]
CONTEXT_UNITS = ["1", "1"]


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


def _edges(bootstrap, topic, *, validate=_validate, series=None):
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
            if message.key() != (_series() if series is None else series)["seriesId"].encode():
                raise ValueError("synthetic source Kafka key does not match the owned series")
            records.append(validate(raw, offset))
        return records[0], records[-1], high
    finally:
        consumer.close()


def _execute_ar1(command, bootstrap, prefix):
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


def _sarimax_series():
    return model.series_key(
        "research.synthetic.sarimax",
        {"source": "opentsx-scoring-demo", "origin": "SYNTHETIC"},
        "MW",
    )


def _schedule(index):
    return [math.sin(index * 0.07), math.cos(index * 0.113)]


def _mean(index):
    first, second = _schedule(index)
    return 2 * first - 1.2 * second


def _sarimax_identity(index):
    return model.new_ulid(
        epoch_millis=(EPOCH + index * INTERVAL) // 1000,
        randomness=hashlib.sha256((SARIMAX_GENERATOR + ":block:" + str(index)).encode()).digest()[
            :10
        ],
    )


def _sarimax_episode(index, previous):
    count = 512 if index == 0 else 64
    if index != 0 and (index < 512 or (index - 512) % 64):
        raise ValueError("synthetic SARIMAX block index is invalid")
    if EPOCH + (index + count) * INTERVAL > 2**53 - 1:
        raise ValueError("synthetic SARIMAX clock exceeds exact browser microseconds")
    residual = 0.0 if index == 0 else previous - _mean(index - 1)
    values = []
    for position in range(index, index + count):
        residual = 0.55 * residual + 0.3 * noise(position)
        values.append(_mean(position) + residual)
    labels = {
        "origin": "SYNTHETIC",
        "demo.generator": SARIMAX_GENERATOR,
        "demo.start_index": str(index),
        "demo.previous_value": previous.hex(),
        "demo.values_sha256": hashlib.sha256(np.asarray(values, dtype="<f8").tobytes()).hexdigest(),
    }
    return model.regular(
        _sarimax_series(),
        EPOCH + index * INTERVAL,
        INTERVAL,
        values,
        episode_id=_sarimax_identity(index),
        quality=bytes(count),
        labels=labels,
        source=SOURCE,
        created_at_us=0,
        pipeline=SARIMAX_GENERATOR,
        parent_episode_id=(
            None if index == 0 else _sarimax_identity(0 if index == 512 else index - 64)
        ),
    )


def _sarimax_validate(raw, offset):
    name, episode = model.decode(raw)
    if name != "Episode" or episode["series"] != _sarimax_series():
        raise ValueError("existing source is not the owned synthetic SARIMAX series")
    index = 0 if offset == 0 else 512 + (offset - 1) * 64
    try:
        previous = float.fromhex(episode["labels"]["demo.previous_value"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("synthetic SARIMAX previous value is invalid") from error
    if not math.isfinite(previous) or (index == 0 and previous != 0):
        raise ValueError("synthetic SARIMAX previous value is incompatible")
    if model.encode("Episode", _sarimax_episode(index, previous)) != raw:
        raise ValueError("existing SARIMAX source data, clock, quality or provenance is corrupt")
    return episode, index


def context_record(index, issued_at_micros=None):
    from opentsx.client.context_join import make_record

    if (
        isinstance(index, bool)
        or not isinstance(index, int)
        or (index != 0 and (index < 516 or (index - 516) % 64))
    ):
        raise ValueError("synthetic context block index is invalid")
    count = 516 if index == 0 else 64
    issued = (
        EPOCH + (-1 if index == 0 else index - 5) * INTERVAL
        if issued_at_micros is None
        else issued_at_micros
    )
    return make_record(
        _sarimax_series()["seriesId"],
        CONTEXT_NAMES,
        CONTEXT_UNITS,
        EPOCH + index * INTERVAL,
        INTERVAL,
        issued,
        [_schedule(position) for position in range(index, index + count)],
        [[issued, issued] for _ in range(count)],
    )


def _context_validate(raw, offset):
    from opentsx.client.context_join import encode_record, validate_record

    try:
        record = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("synthetic context is not valid UTF-8 JSON") from error
    validate_record(record)
    index = 0 if offset == 0 else 516 + (offset - 1) * 64
    if encode_record(context_record(index)) != raw:
        raise ValueError("existing synthetic context values, timing or identity are corrupt")
    return record, index


def _provision(bootstrap, topics, create):
    from confluent_kafka.admin import AdminClient, NewTopic

    admin = AdminClient({"bootstrap.servers": bootstrap})
    metadata = admin.list_topics(timeout=10)
    missing = []
    for topic in topics:
        if topic not in metadata.topics:
            missing.append(topic)
        elif metadata.topics[topic].error is not None or set(metadata.topics[topic].partitions) != {
            0
        }:
            raise ValueError(
                "synthetic SARIMAX topics require exactly one healthy partition numbered zero"
            )
    if missing and not create:
        raise ValueError("synthetic SARIMAX source or context is absent; run seed first")
    if missing:
        futures = admin.create_topics(
            [
                NewTopic(
                    topic,
                    num_partitions=1,
                    replication_factor=1,
                    config={"min.insync.replicas": "1"},
                )
                for topic in missing
            ],
            request_timeout=10,
        )
        for topic in missing:
            futures[topic].result(timeout=15)
    return set(missing)


def _publish(bootstrap, topic, raw, offset, timestamp):
    from confluent_kafka import Producer

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
        key=_sarimax_series()["seriesId"].encode(),
        value=raw,
        timestamp=timestamp // 1000,
        on_delivery=acknowledge,
    )
    if producer.flush(10):
        raise TimeoutError("synthetic SARIMAX publication was not acknowledged within its budget")
    if errors or len(delivered) != 1:
        raise RuntimeError("synthetic SARIMAX publication was not acknowledged")
    if delivered[0] != (0, offset):
        raise RuntimeError("synthetic SARIMAX source changed during publication")


def _context_to(bootstrap, topic, tail, high, desired):
    from opentsx.client.context_join import encode_record

    next_index = 0 if tail is None else tail[1] + len(tail[0]["values"])
    published = 0
    while next_index < desired:
        record = context_record(next_index)
        _publish(bootstrap, topic, encode_record(record), high, record["t_start_micros"])
        next_index += len(record["values"])
        high += 1
        published += 1
    return published


def _execute_sarimax(command, bootstrap, prefix):
    topic, context_topic = prefix + "sarimax.episodes.v2", prefix + "sarimax.context.v1"
    created = _provision(bootstrap, [topic, context_topic], command == "seed")
    first, last, high = (
        (None, None, 0)
        if topic in created
        else _edges(bootstrap, topic, validate=_sarimax_validate, series=_sarimax_series())
    )
    _, context_tail, context_high = (
        (None, None, 0)
        if context_topic in created
        else _edges(bootstrap, context_topic, validate=_context_validate, series=_sarimax_series())
    )
    if last is not None and context_tail is None:
        raise ValueError("synthetic SARIMAX context history is missing")
    base = {
        "schema": "opentsx.scoring-demo.v1",
        "scenario": "sarimax",
        "topic": topic,
        "context_topic": context_topic,
        "series_id": _sarimax_series()["seriesId"],
        "origin": "SYNTHETIC",
    }
    if command == "seed" and first is not None:
        return {**base, "status": "already_seeded", "last_index": last[1]}
    if command != "seed" and last is None:
        raise ValueError("synthetic SARIMAX source is empty; run seed first")
    if command == "context":
        desired = last[1] + last[0]["count"] + 4
        published = _context_to(bootstrap, context_topic, context_tail, context_high, desired)
        return {**base, "status": "context_ready", "context_records_published": published}
    index, previous = (
        (0, 0.0) if last is None else (last[1] + last[0]["count"], last[0]["values"][-1])
    )
    episode = _sarimax_episode(index, previous)
    if command != "target":
        _context_to(
            bootstrap, context_topic, context_tail, context_high, index + episode["count"] + 4
        )
    _publish(bootstrap, topic, model.encode("Episode", episode), high, EPOCH + index * INTERVAL)
    return {
        **base,
        "status": "seeded" if command == "seed" else "appended",
        "episode_id": episode["episodeId"],
        "start_index": index,
        "count": episode["count"],
        "partition": 0,
        "offset": str(high),
        "t_start_micros": EPOCH + index * INTERVAL,
        "t_end_micros": to_us(episode["tEnd"]),
    }


def execute(command, bootstrap, prefix="tsx.workbench-demo.", scenario="ar1"):
    if scenario not in ("ar1", "sarimax"):
        raise ValueError("scenario must be ar1 or sarimax")
    if scenario == "ar1":
        return _execute_ar1(command, bootstrap, prefix)
    if command not in ("seed", "append", "target", "context"):
        raise ValueError("SARIMAX command must be seed, append, target or context")
    if not isinstance(prefix, str) or not re.fullmatch(
        r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\.", prefix
    ):
        raise ValueError("prefix must be a valid Kafka topic prefix ending in a dot")
    if not isinstance(bootstrap, str) or not bootstrap.strip():
        raise ValueError("bootstrap must be a nonempty broker address")
    return _execute_sarimax(command, bootstrap, prefix)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("seed", "append", "target", "context"))
    parser.add_argument("--bootstrap", required=True)
    parser.add_argument("--prefix", default="tsx.workbench-demo.")
    parser.add_argument("--scenario", choices=("ar1", "sarimax"), default="ar1")
    args = parser.parse_args(argv)
    print(
        json.dumps(
            execute(args.command, args.bootstrap, args.prefix, args.scenario),
            sort_keys=True,
            allow_nan=False,
        )
    )


if __name__ == "__main__":
    main()
