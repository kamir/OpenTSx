"""Bounded immutable known-ahead context capture and broker binding evidence."""

import copy
import hashlib
import json
import re
from time import monotonic

import numpy as np

from opentsx.client.context_join import record_rows, schema_descriptor, select_rows, validate_record
from opentsx.client.modelling import validate_modelling
from opentsx.client.topic_catalogue import (
    MAX_BYTES,
    MAX_CELLS,
    MAX_PARTITIONS,
    MAX_RECORDS,
    _integer,
)

POLICY = "latest_200_context_records"


def _encoded(value):
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError("context capture requires finite JSON data") from error


def _identity(value):
    return hashlib.sha256(_encoded(value)).hexdigest()


def _nonfinite(value):
    raise ValueError("nonfinite context JSON is not admitted")


def _object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("duplicate context JSON fields are not admitted")
        result[name] = value
    return result


def _descriptor(record):
    return schema_descriptor(
        record["target_series_id"],
        record["context_names"],
        record["context_units"],
        record["interval_micros"],
    )


def _groups(records):
    groups = {}
    for item in records:
        record = item["record"]
        descriptor = _descriptor(record)
        key = item["partition"], descriptor["schema_id"]
        group = groups.setdefault(key, {"descriptor": descriptor, "records": []})
        if group["records"]:
            previous = group["records"][-1]["record"]
            if (
                record["t_start_micros"]
                < previous["t_start_micros"] + len(previous["values"]) * previous["interval_micros"]
            ):
                raise ValueError(
                    "immutable context contains overlapping or reversed valid-time ranges"
                )
        group["records"].append(item)
    return groups


class ContextCatalogue:
    def __init__(self, bootstrap, prefix):
        if not isinstance(prefix, str) or not re.fullmatch(
            r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\.", prefix
        ):
            raise ValueError("context prefix must be valid and end in a dot")
        self.bootstrap, self.prefix = bootstrap, prefix

    def _topic(self, topic):
        if (
            not isinstance(topic, str)
            or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", topic)
            or not topic.startswith(self.prefix)
            or not topic.endswith(".context.v1")
        ):
            raise ValueError("context topic is outside the configured namespace")

    def topics(self):
        from confluent_kafka.admin import AdminClient

        metadata = AdminClient({"bootstrap.servers": self.bootstrap}).list_topics(timeout=10)
        topics = sorted(
            name
            for name, entry in metadata.topics.items()
            if name.startswith(self.prefix)
            and name.endswith(".context.v1")
            and re.fullmatch(r"[A-Za-z0-9._-]{1,249}", name)
            and entry.error is None
        )
        return {"schema": "opentsx.context-topics.v1", "topics": topics}

    def snapshot(self, topic):
        self._topic(topic)
        from confluent_kafka import Consumer, TopicPartition

        deadline = monotonic() + 10

        def remaining():
            budget = deadline - monotonic()
            if budget <= 0:
                raise TimeoutError("context snapshot exceeded its ten-second budget")
            return budget

        consumer = Consumer(
            {
                "bootstrap.servers": self.bootstrap,
                "group.id": "opentsx-context-catalogue",
                "enable.auto.commit": False,
                "enable.auto.offset.store": False,
                "allow.auto.create.topics": False,
                "auto.offset.reset": "error",
                "enable.partition.eof": True,
            }
        )
        try:
            metadata = consumer.list_topics(topic, timeout=remaining())
            if topic not in metadata.topics or metadata.topics[topic].error is not None:
                raise ValueError("context topic is unavailable")
            partitions = sorted(metadata.topics[topic].partitions)
            if not 1 <= len(partitions) <= MAX_PARTITIONS:
                raise ValueError("context snapshots require one to sixteen partitions")
            bounds = {}
            for partition in partitions:
                _integer(partition, "context partition", 0, 2**31 - 1)
                low, high = consumer.get_watermark_offsets(
                    TopicPartition(topic, partition), timeout=remaining()
                )
                _integer(low, "context low offset")
                _integer(high, "context high offset")
                if high < low:
                    raise ValueError("context retained offsets are invalid")
                bounds[partition] = low, high
            take = {partition: 0 for partition in partitions}
            for _ in range(MAX_RECORDS):
                candidates = [p for p in partitions if take[p] < bounds[p][1] - bounds[p][0]]
                if not candidates:
                    break
                partition = min(candidates, key=lambda p: (take[p], p))
                take[partition] += 1
            next_offsets = {p: bounds[p][1] - take[p] for p in partitions if take[p]}
            pending = set(next_offsets)
            if not pending:
                return []
            consumer.assign([TopicPartition(topic, p, next_offsets[p]) for p in sorted(pending)])
            records, seen, cells = [], set(), 0
            while pending:
                message = consumer.poll(remaining())
                if message is None:
                    raise TimeoutError(
                        "context snapshot ended before all admitted offsets were read"
                    )
                error = message.error()
                if error is not None:
                    partition = message.partition()
                    if (
                        error.code() == -191
                        and partition in next_offsets
                        and next_offsets[partition] == bounds[partition][1]
                    ):
                        continue
                    raise RuntimeError(f"context broker error: {error}")
                partition, offset = message.partition(), message.offset()
                if message.topic() != topic or partition not in next_offsets:
                    raise ValueError("context record is outside the admitted partitions")
                if partition not in pending and offset >= bounds[partition][1]:
                    continue
                if offset != next_offsets[partition]:
                    raise ValueError("context snapshot contains a missing or reordered offset")
                raw = message.value()
                if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_BYTES:
                    raise ValueError("context record exceeds the payload limit")
                try:
                    record = json.loads(raw, parse_constant=_nonfinite, object_pairs_hook=_object)
                except (UnicodeDecodeError, json.JSONDecodeError) as error:
                    raise ValueError("context record is not valid UTF-8 JSON") from error
                validate_record(record)
                cells += len(record["values"]) * len(record["context_names"])
                if cells > MAX_CELLS:
                    raise ValueError("context snapshot exceeds the total sample-cell limit")
                if record["record_id"] in seen:
                    raise ValueError("context snapshot contains duplicate immutable records")
                seen.add(record["record_id"])
                records.append({"partition": partition, "offset": offset, "record": record})
                next_offsets[partition] = offset + 1
                if next_offsets[partition] == bounds[partition][1]:
                    pending.remove(partition)
            remaining()
            return sorted(records, key=lambda item: (item["partition"], item["offset"]))
        finally:
            consumer.close()

    def series(self, topic):
        contexts = []
        for (partition, _), group in sorted(_groups(self.snapshot(topic)).items()):
            records = group["records"]
            first, last = records[0]["record"], records[-1]["record"]
            contexts.append(
                {
                    "partition": partition,
                    "context_schema": group["descriptor"],
                    "first_micros": first["t_start_micros"],
                    "last_micros": last["t_start_micros"]
                    + (len(last["values"]) - 1) * last["interval_micros"],
                    "row_count": sum(len(item["record"]["values"]) for item in records),
                }
            )
        return {
            "schema": "opentsx.context-series.v1",
            "topic": topic,
            "contexts": contexts,
            "capture_policy": POLICY,
        }

    def capture(self, document):
        if not isinstance(document, dict) or set(document) != {
            "topic",
            "partition",
            "target",
            "configuration",
            "backtest",
        }:
            raise ValueError(
                "context capture requires topic, partition, target, configuration and backtest"
            )
        partition = _integer(document["partition"], "context partition", 0, 2**31 - 1)
        target, configuration = document["target"], document["configuration"]
        if not isinstance(target, dict) or target.get("time_kind") != "absolute":
            raise ValueError("broker context capture requires an absolute target clock")
        if not isinstance(configuration, dict) or configuration.get("family") != "sarimax":
            raise ValueError("context capture requires the SARIMAX family")
        times = target.get("timestamps_micros")
        if not isinstance(times, list) or not 9 <= len(times) <= 2048:
            raise ValueError("context capture requires 9 to 2048 target coordinates")
        for value in times:
            _integer(value, "target coordinate", -(2**53 - 1), 2**53 - 1)
        interval = times[1] - times[0]
        if interval <= 0 or any(right - left != interval for left, right in zip(times, times[1:])):
            raise ValueError("context capture requires a regular increasing target clock")
        steps = _integer(configuration.get("steps"), "forecast steps", 1, 96)
        groups = _groups(self.snapshot(document["topic"]))
        matching = [
            group
            for (part, _), group in groups.items()
            if part == partition
            and group["descriptor"]["target_series_id"] == target.get("series_id")
        ]
        if len(matching) != 1:
            raise ValueError(
                "selected context schema is absent or ambiguous for the target and partition"
            )
        group = matching[0]
        descriptor = group["descriptor"]
        if descriptor["interval_micros"] != interval:
            raise ValueError("context interval differs from the target clock")
        end = times[-1] + (steps + 1) * interval
        rows = [
            row
            for item in group["records"]
            for row in record_rows(item["record"], item["offset"])
            if times[0] <= row["timestamp_micros"] < end
        ]
        historical = select_rows(rows, times[0], len(times), interval)
        future = select_rows(rows, times[-1] + interval, steps, interval)
        context = {
            "names": descriptor["names"],
            "units": descriptor["units"],
            "historical": historical["values"],
            "historical_available_at_micros": historical["available_at_micros"],
            "future": future["values"],
            "future_available_at_micros": future["available_at_micros"],
        }
        request = {
            "schema": "opentsx.modelling-request.v1",
            "target": target,
            "configuration": configuration,
            "backtest": document["backtest"],
            "context": context,
        }
        validate_modelling(request)
        positions = {
            (position["offset"], position["record_id"])
            for selection in (historical, future)
            for position in selection["source_positions"]
        }
        binding = {
            "topic": document["topic"],
            "partition": partition,
            "schema_id": descriptor["schema_id"],
            "source_positions": [
                {"offset": offset, "record_id": identity}
                for offset, identity in sorted(positions, key=lambda item: int(item[0]))
            ],
        }
        result = {
            "schema": "opentsx.context-capture.v1",
            "context": context,
            "context_schema": descriptor,
            "binding": binding,
            "target_digest": _identity(target),
            "request_digest": _identity(document),
            "capture_policy": POLICY,
        }
        result["receipt_id"] = _identity(result)
        return copy.deepcopy(result)

    def verify_binding(self, registered, request, topic, partition):
        validate_modelling(request)
        context = request["context"]
        if context is None or "units" not in context:
            raise ValueError("live context binding requires explicit units in the retained request")
        artifact, target = registered["artifact"], request["target"]
        source = artifact["source"]
        expected = {
            "series_id": target["series_id"],
            "unit": target["unit"],
            "time_kind": target["time_kind"],
            "nobs": len(target["values"]),
            "t_start_micros": target["timestamps_micros"][0],
            "t_end_micros": target["timestamps_micros"][-1] + source["interval_micros"],
            "values_sha256": hashlib.sha256(
                np.asarray(target["values"], dtype="<f8").tobytes()
            ).hexdigest(),
        }
        if (
            any(source.get(name) != value for name, value in expected.items())
            or artifact["context_names"] != context["names"]
            or artifact["configuration"] != request["configuration"]
        ):
            raise ValueError(
                "registered artifact does not match the retained target and context request"
            )
        captured = self.capture(
            {
                "topic": topic,
                "partition": partition,
                "target": target,
                "configuration": request["configuration"],
                "backtest": request["backtest"],
            }
        )
        actual = captured["context"]
        if actual["names"] != context["names"] or actual["units"] != context["units"]:
            raise ValueError(
                "context column order or units differ from the retained training request"
            )
        for name in (
            "historical",
            "future",
            "historical_available_at_micros",
            "future_available_at_micros",
        ):
            dtype = "<i8" if "available" in name else "<f8"
            if (
                np.asarray(actual[name], dtype=dtype).tobytes()
                != np.asarray(context[name], dtype=dtype).tobytes()
            ):
                raise ValueError(
                    "broker context differs from the exact retained values or availability"
                )
        result = {
            "schema": "opentsx.context-binding.v1",
            **captured["binding"],
            "context_schema": captured["context_schema"],
            "context_sha256": _identity(context),
            "request_digest": captured["request_digest"],
            "target_digest": captured["target_digest"],
            "capture_policy": POLICY,
        }
        result["receipt_id"] = _identity(result)
        return result
