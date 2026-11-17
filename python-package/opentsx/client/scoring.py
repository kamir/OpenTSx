"""Continuous fixed-parameter scoring with acknowledged Kafka output and durable state."""

import copy
import hashlib
import json
import re

import numpy as np

from opentsx import model
from opentsx.algorithms._analysis import regular_values

MAX_BYTES = 4 * 1024 * 1024
MAX_SAMPLES = 262_144
CHUNK_SAMPLES = 2048


def _integer(value, name, minimum=0):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value < 2**63:
        raise ValueError(f"{name} must be an exact integer from {minimum} to 9223372036854775807")
    return value


def _configuration(deployment, identifier, prefix, artifact):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", identifier):
        raise ValueError("deployment identity is invalid")
    if not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\.", prefix):
        raise ValueError("topic prefix is invalid")
    if deployment["id"] != identifier:
        raise ValueError("deployment identity does not match the requested worker")
    topic = deployment["input_topic"]
    if (
        not isinstance(topic, str)
        or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", topic)
        or not topic.startswith(prefix)
        or not topic.endswith(".episodes.v2")
    ):
        raise ValueError("input topic is outside the configured episode namespace")
    if deployment[
        "output_topic"
    ] != prefix + "smart." + identifier + ".predictions.v1" or not re.fullmatch(
        r"[A-Za-z0-9._-]{1,249}", deployment["output_topic"]
    ):
        raise ValueError("output topic does not match the deployment namespace")
    _integer(deployment["input_partition"], "input partition")
    source = artifact["source"]
    if source["time_kind"] != "absolute":
        raise ValueError("continuous scoring requires an absolute source clock")
    if not isinstance(source.get("unit"), str) or not source["unit"].strip():
        raise ValueError("continuous scoring requires a declared target unit")
    if source["series_id"] != deployment["series_id"]:
        raise ValueError("registered source identity does not match the deployment")
    _integer(source["interval_micros"], "source interval", 1)
    if source["t_end_micros"] != artifact["initial_checkpoint"]["next_timestamp_micros"]:
        raise ValueError("training end does not match the initial numerical state")


def _checkpoint(saved, artifact, low, high):
    if saved is None:
        saved = {
            "artifact_id": artifact["artifact_id"],
            "numerical": copy.deepcopy(artifact["initial_checkpoint"]),
            "next_offset": low,
            "counters": {"read": 0, "scored": 0, "skipped": 0},
        }
    if (
        not isinstance(saved, dict)
        or saved.get("artifact_id") != artifact["artifact_id"]
        or not isinstance(saved.get("numerical"), dict)
        or saved["numerical"].get("artifact_id") != artifact["artifact_id"]
    ):
        raise ValueError("checkpoint does not belong to the registered artifact")
    offset = _integer(saved.get("next_offset"), "checkpoint offset")
    if not low <= offset <= high:
        raise ValueError(
            "checkpoint offset is unavailable; retention or truncation prevents resume"
        )
    counters = saved.get("counters")
    if not isinstance(counters, dict) or set(counters) != {"read", "scored", "skipped"}:
        raise ValueError("checkpoint counters are invalid")
    for name, value in counters.items():
        _integer(value, name + " counter")
    return copy.deepcopy(saved)


def _episode_tail(episode, artifact, checkpoint):
    if _integer(episode.get("revision"), "episode revision") != 0:
        raise ValueError("source revisions require a new evaluated deployment")
    count = _integer(episode.get("count"), "episode count", 1)
    if count > MAX_SAMPLES:
        raise ValueError("episode exceeds the continuous sample limit")
    values, metadata = regular_values(episode, minimum=1, allow_constant=True)
    source = artifact["source"]
    interval = source["interval_micros"]
    start, end = metadata["t_start_micros"], metadata["t_end_micros"]
    if metadata["unit"] != source["unit"] or metadata["interval_micros"] != interval:
        raise ValueError("source unit or interval changed from the registered fit")
    if end != start + count * interval:
        raise ValueError("regular episode end does not match its sample clock")
    _integer(start, "episode start", -(2**63))
    _integer(end, "episode end", -(2**63))
    origin = checkpoint["numerical"]["next_timestamp_micros"]
    training_start = source["t_start_micros"]
    training_end = artifact["initial_checkpoint"]["next_timestamp_micros"]
    if origin == training_end:
        overlap_start, overlap_end = max(start, training_start), min(end, training_end)
        if overlap_end > overlap_start:
            if (overlap_start - start) % interval or (overlap_start - training_start) % interval:
                raise ValueError("training overlap is not aligned to the registered clock")
            first = (overlap_start - start) // interval
            training_first = (overlap_start - training_start) // interval
            length = (overlap_end - overlap_start) // interval
            expected = np.asarray(
                artifact["training_values"][training_first : training_first + length], dtype="<f8"
            )
            actual = np.asarray(values[first : first + length], dtype="<f8")
            if actual.tobytes() != expected.tobytes():
                raise ValueError("training overlap differs from the preserved training samples")
        if end <= training_end:
            return [], start, count
        if start < training_end:
            if (training_end - start) % interval:
                raise ValueError("training overlap is not aligned to the registered clock")
            trim = (training_end - start) // interval
            return values[trim:].tolist(), training_end, trim
    if start != origin:
        raise ValueError("source contains a gap or overlap after the prediction origin")
    return values.tolist(), start, 0


def _advance(advance_artifact, artifact, numerical, values, start):
    observed = None
    forecast = None
    for offset in range(0, len(values), CHUNK_SAMPLES):
        result = advance_artifact(
            artifact,
            numerical,
            values[offset : offset + CHUNK_SAMPLES],
            start + offset * artifact["source"]["interval_micros"],
        )
        numerical, forecast = result["checkpoint"], result["forecast"]
        if observed is None:
            observed = copy.deepcopy(result["observed"])
        else:
            if set(observed) != set(result["observed"]):
                raise ValueError("numerical observation fields changed between chunks")
            for name in observed:
                field_values = result["observed"][name]
                if isinstance(observed[name], list) and isinstance(field_values, list):
                    observed[name].extend(field_values)
                elif observed[name] != field_values:
                    raise ValueError("numerical observation metadata changed between chunks")
    return numerical, forecast, observed


def _publish(producer, topic, key, raw):
    delivered, errors = [], []

    def acknowledge(error, record):
        if error is not None:
            errors.append(str(error))
        else:
            delivered.append((record.partition(), record.offset()))

    producer.produce(topic, key=key.encode("ascii"), value=raw, on_delivery=acknowledge)
    if producer.flush(10):
        raise TimeoutError("prediction output was not acknowledged within the delivery budget")
    if errors or len(delivered) != 1:
        raise RuntimeError("prediction output was not acknowledged by the broker")
    return delivered[0]


def _context_configuration(deployment, registered, artifact, prefix):
    from opentsx.client.context_join import canonical, schema_descriptor

    selected = deployment.get("context")
    if not artifact["context_names"]:
        if selected is not None:
            raise ValueError("context-free scoring does not accept a context source")
        return None
    schema = registered.get("context_schema")
    if not isinstance(schema, dict):
        raise ValueError("registered context schema is missing")
    expected = schema_descriptor(
        artifact["source"]["series_id"],
        artifact["context_names"],
        schema.get("units"),
        artifact["source"]["interval_micros"],
    )
    if schema != expected or deployment.get("context_schema") != expected:
        raise ValueError("context schema differs from the registered fit")
    if not isinstance(selected, dict) or not isinstance(selected.get("topic"), str):
        raise ValueError("SARIMAX scoring requires an immutable context source")
    topic, partition = selected["topic"], selected.get("partition")
    if (
        not topic.startswith(prefix)
        or not topic.endswith(".context.v1")
        or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", topic)
        or topic == deployment["input_topic"]
    ):
        raise ValueError("context topic is outside the configured context namespace")
    _integer(partition, "context partition")
    binding = deployment.get("context_binding")
    if not isinstance(binding, dict) or any(
        binding.get(key) != value
        for key, value in (
            ("topic", topic),
            ("partition", partition),
            ("schema_id", schema["schema_id"]),
        )
    ):
        raise ValueError("context binding does not match the selected source")
    if "context_schema" in binding and binding["context_schema"] != schema:
        raise ValueError("context binding schema differs from the registered fit")
    positions = binding.get("source_positions")
    if not isinstance(positions, list) or not 1 <= len(positions) <= 4096:
        raise ValueError("context binding requires bounded source positions")
    pinned = {}
    for position in positions:
        if not isinstance(position, dict) or set(position) != {"offset", "record_id"}:
            raise ValueError("context binding source position is invalid")
        offset = position["offset"]
        identity = position["record_id"]
        if (
            not isinstance(offset, str)
            or not offset.isascii()
            or not offset.isdecimal()
            or str(int(offset)) != offset
        ):
            raise ValueError("context binding offset must be an exact decimal string")
        _integer(int(offset), "bound context offset")
        if (
            not isinstance(identity, str)
            or not re.fullmatch(r"[a-f0-9]{64}", identity)
            or int(offset) in pinned
        ):
            raise ValueError("context binding record identity is invalid")
        pinned[int(offset)] = identity
    digest = hashlib.sha256(
        canonical({key: value for key, value in binding.items() if key != "receipt_id"})
    ).hexdigest()
    if binding.get("receipt_id", digest) != digest:
        raise ValueError("context binding receipt integrity does not match")
    return {
        "topic": topic,
        "partition": partition,
        "schema": schema,
        "binding_id": digest,
        "positions": pinned,
    }


def _context_checkpoint(checkpoint, config, artifact, low, high):
    from opentsx.client.context_join import MAX_BUFFER_ROWS, canonical, integer

    saved = checkpoint.get("context")
    if saved is None:
        if any(not low <= offset < high for offset in config["positions"]):
            raise ValueError("bound context offset is unavailable due to retention or truncation")
        return {
            "topic": config["topic"],
            "partition": config["partition"],
            "schema_id": config["schema"]["schema_id"],
            "binding_id": config["binding_id"],
            "next_offset": low,
            "watermark_micros": None,
            "rows": [],
        }
    expected = {
        "topic",
        "partition",
        "schema_id",
        "binding_id",
        "next_offset",
        "watermark_micros",
        "rows",
    }
    if not isinstance(saved, dict) or set(saved) != expected:
        raise ValueError("context checkpoint has missing or unsupported fields")
    for name, value in (
        ("topic", config["topic"]),
        ("partition", config["partition"]),
        ("schema_id", config["schema"]["schema_id"]),
        ("binding_id", config["binding_id"]),
    ):
        if saved[name] != value:
            raise ValueError("context checkpoint source or binding identity changed")
    offset = _integer(saved["next_offset"], "context checkpoint offset")
    if not low <= offset <= high:
        raise ValueError("context checkpoint offset is unavailable due to retention or truncation")
    watermark, rows = saved["watermark_micros"], saved["rows"]
    if not isinstance(rows, list) or len(rows) > MAX_BUFFER_ROWS:
        raise ValueError("context checkpoint exceeds its bounded lookahead")
    if watermark is None:
        if rows:
            raise ValueError("context checkpoint rows require a watermark")
    else:
        integer(watermark, "context checkpoint watermark")
    interval = artifact["source"]["interval_micros"]
    origin = checkpoint["numerical"]["next_timestamp_micros"]
    previous = None
    columns = len(artifact["context_names"])
    for row in rows:
        if not isinstance(row, dict) or set(row) != {
            "timestamp_micros",
            "values",
            "available_at_micros",
            "issued_at_micros",
            "record_id",
            "offset",
        }:
            raise ValueError("context checkpoint row is invalid")
        stamp = integer(row["timestamp_micros"], "cached context time")
        if (
            stamp < origin
            or (stamp - artifact["source"]["t_start_micros"]) % interval
            or (previous is not None and stamp != previous + interval)
        ):
            raise ValueError("context checkpoint row clock is invalid")
        previous = stamp
        values, available = row["values"], row["available_at_micros"]
        if (
            not isinstance(values, list)
            or len(values) != columns
            or not isinstance(available, list)
            or len(available) != columns
        ):
            raise ValueError("context checkpoint column dimensions are invalid")
        for value in values:
            if type(value) not in (int, float):
                raise ValueError("cached context must contain finite real values")
        try:
            finite = np.all(np.isfinite(np.asarray(values, dtype=float)))
        except (ValueError, TypeError, OverflowError) as error:
            raise ValueError("cached context must contain finite real values") from error
        if not finite:
            raise ValueError("cached context must contain finite real values")
        issued = integer(row["issued_at_micros"], "cached context issue time")
        for available_at in available:
            if integer(available_at, "cached context availability") < issued:
                raise ValueError("cached context issue time exceeds its availability")
        if not isinstance(row["record_id"], str) or not re.fullmatch(
            r"[a-f0-9]{64}", row["record_id"]
        ):
            raise ValueError("cached context identity is invalid")
        source_offset = row["offset"]
        if (
            not isinstance(source_offset, str)
            or not source_offset.isascii()
            or not source_offset.isdecimal()
            or str(int(source_offset)) != source_offset
            or not 0 <= int(source_offset) < offset
        ):
            raise ValueError("cached context source offset is invalid")
        if (
            int(source_offset) in config["positions"]
            and config["positions"][int(source_offset)] != row["record_id"]
        ):
            raise ValueError("cached context differs from the pinned source version")
    if rows and rows[-1]["timestamp_micros"] + interval != watermark:
        raise ValueError("context checkpoint watermark does not match its lookahead")
    if len(canonical(saved)) > MAX_BYTES:
        raise ValueError("context checkpoint exceeds its payload limit")
    return copy.deepcopy(saved)


def _join_context(
    store, identifier, consumer, config, artifact, checkpoint, values, start, stop_event
):
    from opentsx.client.context_join import (
        MissingContext,
        append_record,
        canonical,
        select_rows,
        decode_record,
    )

    interval = artifact["source"]["interval_micros"]
    end = start + len(values) * interval
    steps = artifact["configuration"]["steps"]
    while not stop_event.is_set():
        if store.get_deployment(identifier)["desired_state"] != "running":
            return None
        context = checkpoint["context"]
        try:
            observed = select_rows(
                context["rows"],
                start,
                len(values),
                interval,
                [start + (index - 1) * interval for index in range(len(values))],
            )
            future = select_rows(context["rows"], end, steps, interval, end - interval)
            all_rows = observed["rows"] + future["rows"]
            positions = {(row["offset"], row["record_id"]) for row in all_rows}
            provenance = {
                "policy": config["schema"]["policy"],
                "schema_id": config["schema"]["schema_id"],
                "topic": config["topic"],
                "partition": config["partition"],
                "source_positions": [
                    {"offset": offset, "record_id": identity}
                    for offset, identity in sorted(positions, key=lambda item: int(item[0]))
                ],
                "observed_start_micros": start,
                "observed_end_micros": end,
                "forecast_start_micros": end,
                "forecast_end_micros": end + steps * interval,
                "available_at_max_micros": max(
                    stamp for row in all_rows for stamp in row["available_at_micros"]
                ),
                "values_sha256": hashlib.sha256(
                    canonical(observed["values"] + future["values"])
                ).hexdigest(),
                "availability_sha256": hashlib.sha256(
                    canonical(observed["available_at_micros"] + future["available_at_micros"])
                ).hexdigest(),
            }
            store.set_context_status(identifier, None)
            store.set_worker_status(identifier, "running")
            return checkpoint, observed["values"], future["values"], provenance
        except MissingContext as missing:
            watermark = context["watermark_micros"]
            if watermark is not None and watermark > missing.missing_start_micros:
                raise ValueError(
                    "context stream has a gap before the required valid time"
                ) from missing
            detail = {
                "reason": str(missing),
                "missing_start_micros": missing.missing_start_micros,
                "missing_end_micros": missing.missing_end_micros,
                "cutoff_micros": missing.cutoff_micros,
                "topic": config["topic"],
                "partition": config["partition"],
                "next_offset": context["next_offset"],
                "watermark_micros": watermark,
                "buffered_rows": len(context["rows"]),
            }
            store.set_context_status(identifier, detail)
            store.set_worker_status(identifier, "waiting_context")
        message = consumer.poll(1.0)
        if message is None:
            continue
        if message.error() is not None:
            raise RuntimeError(f"context broker error: {message.error()}")
        offset = message.offset()
        if (
            message.topic() != config["topic"]
            or message.partition() != config["partition"]
            or offset != checkpoint["context"]["next_offset"]
        ):
            raise ValueError(
                "context topic, partition or offset differs from the next admitted record"
            )
        _integer(offset, "context source offset")
        raw = message.value()
        if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_BYTES:
            raise ValueError("context record exceeds the payload limit or is empty")
        record = decode_record(raw)
        if offset in config["positions"] and record["record_id"] != config["positions"][offset]:
            raise ValueError("context source version differs from the pinned binding")
        if (record["t_start_micros"] - artifact["source"]["t_start_micros"]) % interval:
            raise ValueError("context valid times do not align with the target clock")
        following = copy.deepcopy(checkpoint)
        context = following["context"]
        rows, watermark = append_record(
            context["rows"], record, offset, config["schema"], context["watermark_micros"]
        )
        context["rows"] = [
            row
            for row in rows
            if row["timestamp_micros"] >= checkpoint["numerical"]["next_timestamp_micros"]
        ]
        context["watermark_micros"] = watermark
        context["next_offset"] = _integer(offset + 1, "next context offset")
        if len(canonical(context)) > MAX_BYTES:
            raise ValueError("context checkpoint exceeds its payload limit")
        store.save_checkpoint(identifier, following)
        checkpoint = following
    return None


def run_worker(database_path, identifier, bootstrap, prefix, stop_event):
    from confluent_kafka import Consumer, Producer, TopicPartition
    from opentsx.algorithms.fitted import advance_artifact, validate_checkpoint
    from opentsx.client.lifecycle import LifecycleStore

    store = LifecycleStore(database_path)
    consumer = None
    context_consumer = None
    failure = None
    try:
        deployment = store.get_deployment(identifier)
        registered = store.get_model(deployment["model_id"])
        artifact = registered["artifact"]
        _configuration(deployment, identifier, prefix, artifact)
        context_config = _context_configuration(deployment, registered, artifact, prefix)
        validate_checkpoint(artifact, artifact["initial_checkpoint"])
        store.set_worker_status(identifier, "starting")
        if deployment["desired_state"] != "running" or stop_event.is_set():
            store.set_worker_status(identifier, "stopped")
            return
        consumer = Consumer(
            {
                "bootstrap.servers": bootstrap,
                "group.id": "opentsx-scoring-" + identifier,
                "enable.auto.commit": False,
                "enable.auto.offset.store": False,
                "allow.auto.create.topics": False,
                "auto.offset.reset": "error",
                "enable.partition.eof": False,
            }
        )
        topic, partition = deployment["input_topic"], deployment["input_partition"]
        low, high = consumer.get_watermark_offsets(TopicPartition(topic, partition), timeout=10)
        _integer(low, "retained low offset")
        _integer(high, "retained high offset")
        if high < low:
            raise ValueError("source offset range is invalid")
        saved = store.get_checkpoint(identifier)
        checkpoint = _checkpoint(saved, artifact, low, high)
        validate_checkpoint(artifact, checkpoint["numerical"])
        if context_config is not None:
            context_consumer = Consumer(
                {
                    "bootstrap.servers": bootstrap,
                    "group.id": "opentsx-scoring-context-" + identifier,
                    "enable.auto.commit": False,
                    "enable.auto.offset.store": False,
                    "allow.auto.create.topics": False,
                    "auto.offset.reset": "error",
                    "enable.partition.eof": False,
                }
            )
            context_partition = TopicPartition(context_config["topic"], context_config["partition"])
            context_low, context_high = context_consumer.get_watermark_offsets(
                context_partition, timeout=10
            )
            _integer(context_low, "retained context low offset")
            _integer(context_high, "retained context high offset")
            if context_high < context_low:
                raise ValueError("context offset range is invalid")
            checkpoint["context"] = _context_checkpoint(
                checkpoint, context_config, artifact, context_low, context_high
            )
            context_consumer.assign(
                [
                    TopicPartition(
                        context_config["topic"],
                        context_config["partition"],
                        checkpoint["context"]["next_offset"],
                    )
                ]
            )
        if saved is None or (context_config is not None and "context" not in saved):
            store.save_checkpoint(identifier, checkpoint)
        consumer.assign([TopicPartition(topic, partition, checkpoint["next_offset"])])
        producer = Producer(
            {
                "bootstrap.servers": bootstrap,
                "acks": "all",
                "enable.idempotence": False,
                "max.in.flight.requests.per.connection": 1,
                "delivery.timeout.ms": 10000,
            }
        )
        store.set_worker_status(identifier, "running")
        while not stop_event.is_set():
            if store.get_deployment(identifier)["desired_state"] != "running":
                break
            message = consumer.poll(1.0)
            if message is None:
                continue
            if message.error() is not None:
                raise RuntimeError(f"source broker error: {message.error()}")
            offset = message.offset()
            if (
                message.topic() != topic
                or message.partition() != partition
                or offset != checkpoint["next_offset"]
            ):
                raise ValueError(
                    "source topic, partition or offset differs from the next admitted record"
                )
            _integer(offset, "source offset")
            raw = message.value()
            if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_BYTES:
                raise ValueError("source record exceeds the payload limit or is empty")
            name, episode = model.decode(raw)
            if name != "Episode":
                raise ValueError("source record is not a v2 episode")
            following = copy.deepcopy(checkpoint)
            following["next_offset"] = _integer(offset + 1, "next source offset")
            following["counters"]["read"] = _integer(
                following["counters"]["read"] + 1, "read counter"
            )
            if episode["series"]["seriesId"] != deployment["series_id"]:
                following["counters"]["skipped"] += 1
                store.save_checkpoint(identifier, following)
                checkpoint = following
                continue
            if (
                context_config is not None
                and _integer(episode.get("count"), "episode count", 1) > CHUNK_SAMPLES
            ):
                raise ValueError("SARIMAX scoring accepts target records of at most 2048 samples")
            values, start, trimmed = _episode_tail(episode, artifact, checkpoint)
            if not values:
                following["counters"]["skipped"] += 1
                store.save_checkpoint(identifier, following)
                checkpoint = following
                continue
            context_provenance = None
            if context_config is not None:
                if len(values) > CHUNK_SAMPLES:
                    raise ValueError("SARIMAX scoring accepts at most 2048 new target samples")
                joined = _join_context(
                    store,
                    identifier,
                    context_consumer,
                    context_config,
                    artifact,
                    checkpoint,
                    values,
                    start,
                    stop_event,
                )
                if joined is None:
                    break
                checkpoint, exog, future_exog, context_provenance = joined
                following["context"] = copy.deepcopy(checkpoint["context"])
                result = advance_artifact(
                    artifact,
                    checkpoint["numerical"],
                    values,
                    start,
                    exog=exog,
                    future_exog=future_exog,
                )
                numerical, forecast, observed = (
                    result["checkpoint"],
                    result["forecast"],
                    result["observed"],
                )
                following["context"]["rows"] = [
                    row
                    for row in following["context"]["rows"]
                    if row["timestamp_micros"] >= numerical["next_timestamp_micros"]
                ]
            else:
                numerical, forecast, observed = _advance(
                    advance_artifact, artifact, checkpoint["numerical"], values, start
                )
            following["numerical"] = numerical
            following["counters"]["scored"] += 1
            identity = [identifier, artifact["artifact_id"], topic, partition, offset]
            key = hashlib.sha256(json.dumps(identity, separators=(",", ":")).encode()).hexdigest()
            unknown_quality = episode.get("quality") is None
            payload = {
                "schema": "opentsx.smart-prediction.v1",
                "id": key,
                "deployment_id": identifier,
                "model_id": deployment["model_id"],
                "artifact_id": artifact["artifact_id"],
                "target": {
                    name: artifact["source"][name]
                    for name in ("series_id", "unit", "time_kind", "interval_micros")
                },
                "source": {
                    "topic": topic,
                    "partition": partition,
                    "offset": str(offset),
                    "episode_id": episode["episodeId"],
                    "values_sha256": hashlib.sha256(
                        np.asarray(values, dtype="<f8").tobytes()
                    ).hexdigest(),
                    "trimmed_training_samples": trimmed,
                    "quality_status": "unknown" if unknown_quality else "unflagged",
                },
                "origin_micros": numerical["next_timestamp_micros"],
                "forecast": forecast,
                "observed": observed,
                "warnings": [
                    "Conditional Gaussian intervals omit full parameter-estimation uncertainty."
                ]
                + (
                    ["Input quality was not supplied; unknown quality is not confirmed good."]
                    if unknown_quality
                    else []
                ),
            }
            if context_provenance is not None:
                payload["context"] = context_provenance
            for field, name in (("version", "model_version"), ("mlflow_version", "mlflow_version")):
                if registered.get(field) is not None:
                    payload[name] = registered[field]
            encoded = json.dumps(
                payload, sort_keys=True, separators=(",", ":"), allow_nan=False
            ).encode()
            if len(encoded) > MAX_BYTES:
                raise ValueError("prediction output exceeds the payload limit")
            output_partition, output_offset = _publish(
                producer, deployment["output_topic"], key, encoded
            )
            store.record_output(
                identifier, key, payload, output_partition, output_offset, following
            )
            checkpoint = following
        store.set_worker_status(identifier, "stopping")
    except Exception as error:
        failure = error
    finally:
        for client in (consumer, context_consumer):
            if client is not None:
                try:
                    client.close()
                except Exception as error:
                    failure = (
                        error
                        if failure is None
                        else RuntimeError(f"{failure}; consumer close failed: {error}")
                    )
    if failure is not None:
        store.set_worker_status(identifier, "failed", error=f"{type(failure).__name__}: {failure}")
        raise failure
    store.set_worker_status(identifier, "stopped")
