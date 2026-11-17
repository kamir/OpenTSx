"""Bounded broker snapshots for modelling input and verified training bindings."""

from datetime import datetime, timedelta, timezone
import hashlib
import re
from time import monotonic

import numpy as np

from opentsx import model
from opentsx.model.series_key import verify_series_key
from opentsx.model.time_axis import to_us

MAX_RECORDS = 200
MAX_PARTITIONS = 16
MAX_CELLS = 262_144
MAX_BYTES = 4 * 1024 * 1024
POLICY = "latest_200_records"


def _integer(value, name, minimum=0, maximum=2**63 - 1):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be an exact integer from {minimum} to {maximum}")
    return value


def _whole_topic(metadata, topic, partition):
    if topic not in metadata.topics or metadata.topics[topic].error is not None:
        raise ValueError("continuous scoring source topic is unavailable")
    if (
        type(partition) is not int
        or partition != 0
        or set(metadata.topics[topic].partitions) != {0}
    ):
        raise ValueError("continuous scoring requires a whole topic with exactly partition 0")


def _regular(episode):
    verify_series_key(episode["series"])
    count = _integer(episode["count"], "episode count", 1, MAX_CELLS)
    if episode["timeEncoding"] != "REGULAR":
        raise ValueError("modelling catalogue requires regular episodes")
    interval = _integer(episode["intervalMicros"], "episode interval", 1)
    if _integer(episode["revision"], "episode revision") != 0:
        raise ValueError("source revisions are not admitted by the modelling catalogue")
    start, end = to_us(episode["tStart"]), to_us(episode["tEnd"])
    _integer(start, "episode start", -(2**53 - 1), 2**53 - 1)
    _integer(end, "episode end", -(2**53 - 1), 2**53 - 1)
    if end != start + count * interval:
        raise ValueError("episode end does not match its regular sample clock")
    values = np.asarray(episode["values"])
    if (
        values.shape != (count,)
        or not np.issubdtype(values.dtype, np.number)
        or np.iscomplexobj(values)
    ):
        raise ValueError("episode values must match its declared real sample count")
    if np.any(np.isinf(values)):
        raise ValueError("infinite samples cannot be captured for modelling")
    quality = episode.get("quality")
    if quality is not None and len(quality) != count:
        raise ValueError("episode quality does not match its sample count")
    if not isinstance(episode["episodeId"], str) or not episode["episodeId"]:
        raise ValueError("episode identity is missing")
    return count


def _groups(records):
    groups = {}
    previous = {}
    for record in records:
        episode = record["episode"]
        series = episode["series"]
        identifier = series["seriesId"]
        items = groups.setdefault(identifier, [])
        if items:
            first = items[0]["episode"]
            if first["series"] != series or first["intervalMicros"] != episode["intervalMicros"]:
                raise ValueError("series metadata changes inside the admitted snapshot")
        key = (identifier, record["partition"])
        if key in previous and to_us(episode["tStart"]) < previous[key]:
            raise ValueError("series contains overlapping or reversed episode order")
        previous[key] = to_us(episode["tEnd"])
        items.append(record)
    for items in groups.values():
        items.sort(key=lambda record: to_us(record["episode"]["tStart"]))
        for left, right in zip(items, items[1:]):
            if to_us(right["episode"]["tStart"]) < to_us(left["episode"]["tEnd"]):
                raise ValueError("series contains overlapping episode ranges")
    return groups


def _owned(groups, series_id):
    if not isinstance(series_id, str) or not 0 < len(series_id) <= 512:
        raise ValueError("series_id must be a nonempty bounded string")
    if series_id not in groups:
        raise ValueError("selected series is absent from the admitted topic snapshot")
    records = groups[series_id]
    if len({record["partition"] for record in records}) != 1:
        raise ValueError("series has ambiguous multi-partition ownership")
    return records


def _iso(micros):
    value = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(microseconds=micros)
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def _position(record):
    return {
        "partition": record["partition"],
        "offset": str(record["offset"]),
        "episode_id": record["episode"]["episodeId"],
    }


class TopicCatalogue:
    def __init__(self, bootstrap, prefix):
        if not isinstance(prefix, str) or not re.fullmatch(
            r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\.", prefix
        ):
            raise ValueError("topic prefix must be valid and end in a dot")
        self.bootstrap, self.prefix = bootstrap, prefix

    def _topic(self, topic):
        if (
            not isinstance(topic, str)
            or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", topic)
            or not topic.startswith(self.prefix)
            or not topic.endswith(".episodes.v2")
        ):
            raise ValueError("topic is outside the configured episode namespace")

    def snapshot(self, topic, *, whole_topic=False):
        self._topic(topic)
        from confluent_kafka import Consumer, TopicPartition

        deadline = monotonic() + 10

        def remaining():
            value = deadline - monotonic()
            if value <= 0:
                raise TimeoutError("modelling topic snapshot exceeded its ten-second budget")
            return value

        consumer = Consumer(
            {
                "bootstrap.servers": self.bootstrap,
                "group.id": "opentsx-modelling-catalogue",
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
                raise ValueError("selected source topic is unavailable")
            if whole_topic:
                _whole_topic(metadata, topic, 0)
            partitions = sorted(metadata.topics[topic].partitions)
            if not 1 <= len(partitions) <= MAX_PARTITIONS:
                raise ValueError("modelling snapshots require one to sixteen partitions")
            bounds = {}
            for partition in partitions:
                _integer(partition, "source partition", 0, 2**31 - 1)
                low, high = consumer.get_watermark_offsets(
                    TopicPartition(topic, partition), timeout=remaining()
                )
                _integer(low, "retained low offset")
                _integer(high, "retained high offset")
                if high < low:
                    raise ValueError("source has an invalid retained offset range")
                bounds[partition] = (low, high)
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
            records, identities = [], set()
            cells = 0
            while pending:
                message = consumer.poll(remaining())
                if message is None:
                    raise TimeoutError(
                        "source snapshot ended before its admitted offsets were read"
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
                    raise RuntimeError(f"source snapshot broker error: {error}")
                partition, offset = message.partition(), message.offset()
                if message.topic() != topic or partition not in next_offsets:
                    raise ValueError(
                        "source returned a record outside the admitted topic partitions"
                    )
                if partition not in pending and offset >= bounds[partition][1]:
                    continue
                if offset != next_offsets[partition]:
                    raise ValueError("source snapshot contains a missing or reordered offset")
                raw = message.value()
                if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_BYTES:
                    raise ValueError("source record exceeds the snapshot payload limit")
                name, episode = model.decode(raw)
                if name != "Episode":
                    raise ValueError("source snapshot contains a non-episode record")
                cells += _regular(episode)
                if cells > MAX_CELLS:
                    raise ValueError("source snapshot exceeds the total sample-cell limit")
                if episode["episodeId"] in identities:
                    raise ValueError("source snapshot contains duplicate episode identities")
                identities.add(episode["episodeId"])
                records.append({"partition": partition, "offset": offset, "episode": episode})
                next_offsets[partition] = offset + 1
                if next_offsets[partition] == bounds[partition][1]:
                    pending.remove(partition)
            remaining()
            return sorted(records, key=lambda record: (record["partition"], record["offset"]))
        finally:
            consumer.close()

    def series(self, topic):
        groups = _groups(self.snapshot(topic))
        result = []
        for identifier, records in sorted(groups.items()):
            first, last = records[0]["episode"], records[-1]["episode"]
            result.append(
                {
                    "series_id": identifier,
                    "label": first["series"]["metric"],
                    "unit": first["series"]["unit"],
                    "interval_micros": first["intervalMicros"],
                    "partitions": sorted({record["partition"] for record in records}),
                    "count": sum(record["episode"]["count"] for record in records),
                    "first_micros": to_us(first["tStart"]),
                    "last_micros": to_us(last["tEnd"]) - last["intervalMicros"],
                }
            )
        return {
            "schema": "opentsx.modelling-series.v1",
            "topic": topic,
            "series": result,
            "capture_policy": POLICY,
        }

    def capture(self, document):
        if (
            not isinstance(document, dict)
            or not {"topic", "series_id"} <= set(document)
            or set(document) - {"topic", "series_id", "samples"}
        ):
            raise ValueError("capture requires topic, series_id and an optional samples count")
        samples = _integer(document.get("samples", 512), "capture samples", 9, 2048)
        topic = document["topic"]
        records = _owned(_groups(self.snapshot(topic)), document["series_id"])
        selected = []
        needed, next_start = samples, None
        for record in reversed(records):
            episode = record["episode"]
            if next_start is not None and to_us(episode["tEnd"]) != next_start:
                raise ValueError("selected capture window contains a gap")
            count = episode["count"]
            first = max(0, count - needed)
            selected.append((record, first, count))
            needed -= count - first
            if needed == 0:
                break
            next_start = to_us(episode["tStart"])
        if needed:
            raise ValueError("selected snapshot has fewer samples than requested")
        episodes = []
        for record, first, stop in reversed(selected):
            episode = record["episode"]
            interval = episode["intervalMicros"]
            start = to_us(episode["tStart"]) + first * interval
            end = to_us(episode["tStart"]) + stop * interval
            labels = {
                **episode["labels"],
                "capture.topic": topic,
                "capture.partition": str(record["partition"]),
                "capture.offset": str(record["offset"]),
            }
            if first or stop != episode["count"]:
                labels["capture.clipped"] = "true"
            entry = {
                "episodeId": episode["episodeId"],
                "seriesId": episode["series"]["seriesId"],
                "metric": episode["series"]["metric"],
                "tags": episode["series"]["tags"],
                "unit": episode["series"]["unit"],
                "intervalMicros": interval,
                "tStart": _iso(start),
                "tEnd": _iso(end),
                "count": stop - first,
                "timeEncoding": "REGULAR",
                "timestampsMicros": list(range(start, end, interval)),
                "values": [
                    None if np.isnan(value) else value for value in episode["values"][first:stop]
                ],
                "labels": labels,
            }
            if episode.get("quality") is not None:
                entry["quality"] = list(episode["quality"][first:stop])
            if episode.get("segmentation") is not None:
                segmentation = episode["segmentation"]
                entry["segmentation"] = {
                    "strategy": segmentation["strategy"],
                    "params": segmentation["params"],
                }
                if segmentation.get("anchorTs") is not None:
                    entry["segmentation"]["anchorTs"] = _iso(to_us(segmentation["anchorTs"]))
            parent = episode["provenance"].get("parentEpisodeId")
            if parent is not None:
                entry["parentEpisodeId"] = parent
            episodes.append(entry)
        return {
            "schema": "opentsx.modelling-capture.v1",
            "topic": topic,
            "episodes": episodes,
            "capture_policy": POLICY,
            "source_positions": [_position(record) for record, _, _ in reversed(selected)],
        }

    def verify_binding(self, registered, input_topic, input_partition):
        _integer(input_partition, "input partition", 0, 2**31 - 1)
        artifact = registered["artifact"]
        source = artifact["source"]
        if source["time_kind"] != "absolute":
            raise ValueError("a broker binding requires an absolute training clock")
        training = np.asarray(artifact["training_values"], dtype="<f8")
        if (
            training.ndim != 1
            or not 1 <= len(training) <= 2048
            or not np.all(np.isfinite(training))
            or source["nobs"] != len(training)
            or hashlib.sha256(training.tobytes()).hexdigest() != source["values_sha256"]
        ):
            raise ValueError("preserved training samples do not match their source digest")
        interval = _integer(source["interval_micros"], "training interval", 1)
        start, end = source["t_start_micros"], source["t_end_micros"]
        if end != start + len(training) * interval:
            raise ValueError("training clock does not match its sample count")
        records = _owned(_groups(self.snapshot(input_topic, whole_topic=True)), source["series_id"])
        if records[0]["partition"] != input_partition:
            raise ValueError("selected partition does not own the registered series")
        actual, filled = np.empty(len(training), dtype="<f8"), np.zeros(len(training), dtype=bool)
        positions = []
        quality_known = True
        for record in records:
            episode = record["episode"]
            if episode["series"]["unit"] != source["unit"] or episode["intervalMicros"] != interval:
                raise ValueError("source unit or interval differs from the registered fit")
            lo, hi = max(start, to_us(episode["tStart"])), min(end, to_us(episode["tEnd"]))
            if hi <= lo:
                continue
            if (lo - start) % interval or (lo - to_us(episode["tStart"])) % interval:
                raise ValueError("source sample clock does not align with the registered fit")
            first, source_first, count = (
                (lo - start) // interval,
                (lo - to_us(episode["tStart"])) // interval,
                (hi - lo) // interval,
            )
            if np.any(filled[first : first + count]):
                raise ValueError("registered training samples have duplicate source positions")
            quality = episode.get("quality")
            if quality is None:
                quality_known = False
            elif any(quality[source_first : source_first + count]):
                raise ValueError("training source contains flagged quality")
            actual[first : first + count] = episode["values"][source_first : source_first + count]
            filled[first : first + count] = True
            positions.append(_position(record))
        if not np.all(filled) or actual.tobytes() != training.tobytes():
            raise ValueError(
                "registered training samples are not proven by the admitted source snapshot"
            )
        return {
            "schema": "opentsx.modelling-binding.v1",
            "artifact_id": artifact["artifact_id"],
            "input_topic": input_topic,
            "input_partition": input_partition,
            "series_id": source["series_id"],
            "training_start_micros": start,
            "training_end_micros": end,
            "training_values_sha256": source["values_sha256"],
            "source_positions": positions,
            "quality_status": "unflagged" if quality_known else "unknown",
            "capture_policy": POLICY,
        }
