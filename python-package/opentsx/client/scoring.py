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
    if artifact["context_names"]:
        raise ValueError("continuous SARIMAX context joins are unavailable")
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


def run_worker(database_path, identifier, bootstrap, prefix, stop_event):
    from confluent_kafka import Consumer, Producer, TopicPartition
    from opentsx.algorithms.fitted import advance_artifact, validate_checkpoint
    from opentsx.client.lifecycle import LifecycleStore

    store = LifecycleStore(database_path)
    consumer = None
    failure = None
    try:
        deployment = store.get_deployment(identifier)
        registered = store.get_model(deployment["model_id"])
        artifact = registered["artifact"]
        _configuration(deployment, identifier, prefix, artifact)
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
        if saved is None:
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
            values, start, trimmed = _episode_tail(episode, artifact, checkpoint)
            if not values:
                following["counters"]["skipped"] += 1
                store.save_checkpoint(identifier, following)
                checkpoint = following
                continue
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
        if consumer is not None:
            try:
                consumer.close()
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
