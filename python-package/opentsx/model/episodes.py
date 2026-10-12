"""Build v2 episodes as plain dicts (fastavro records) with all derived fields, mirroring Java ``Episodes``."""

from __future__ import annotations

import time
from typing import Mapping, Optional, Sequence

import numpy as np

from opentsx.model.series_key import sorted_by_utf8, verify_series_key
from opentsx.model.summary import summarize
from opentsx.model.time_axis import encode_deltas, timestamps_us
from opentsx.model.ulid import new_ulid

PRODUCER = "opentsx-python/1.0.0"


def _provenance(source: str, producer: str, created_at_us: Optional[int], pipeline, parent) -> dict:
    return {
        "source": source,
        "producer": producer,
        "createdAt": int(time.time() * 1_000_000) if created_at_us is None else int(created_at_us),
        "pipeline": pipeline,
        "parentEpisodeId": parent,
    }


def _episode(series: Mapping, t_start: int, t_end: int, values: np.ndarray, encoding: str,
             interval: Optional[int], deltas: Optional[bytes], *, episode_id, source, producer, created_at_us,
             pipeline, parent_episode_id, labels, bucket_id, segmentation, quality, revision) -> dict:
    verify_series_key(series)
    if quality is not None and len(quality) != len(values):
        raise ValueError("quality must have one byte per value")
    return {
        "episodeId": episode_id or new_ulid(),
        "series": dict(series),
        "tStart": int(t_start),
        "tEnd": int(t_end),
        "count": int(len(values)),
        "timeEncoding": encoding,
        "intervalMicros": interval,
        "timeDeltas": deltas,
        "values": [float(v) for v in values],
        "quality": None if quality is None else bytes(quality),
        "summary": summarize(values),
        "segmentation": segmentation,
        "labels": sorted_by_utf8(labels),
        "bucketId": bucket_id,
        "revision": int(revision),
        "provenance": _provenance(source, producer, created_at_us, pipeline, parent_episode_id),
    }


def regular(series: Mapping, t_start_us: int, interval_us: int, values: Sequence[float], *,
            t_end_us: Optional[int] = None, episode_id: Optional[str] = None, source: str = "unknown",
            producer: str = PRODUCER, created_at_us: Optional[int] = None, pipeline: Optional[str] = None,
            parent_episode_id: Optional[str] = None, labels: Optional[Mapping[str, str]] = None,
            bucket_id: Optional[str] = None, segmentation: Optional[Mapping] = None,
            quality: Optional[bytes] = None, revision: int = 0) -> dict:
    if interval_us <= 0:
        raise ValueError("interval_us must be > 0")
    v = np.asarray(values, dtype=np.float64)
    n = len(v)
    nxt = t_start_us + interval_us * n
    t_end = t_end_us if t_end_us is not None else (t_start_us + interval_us if n == 0 else nxt)
    if n and nxt - interval_us >= t_end:
        raise ValueError("t_end cuts off values")
    return _episode(series, t_start_us, t_end, v, "REGULAR", int(interval_us), None,
                    episode_id=episode_id, source=source, producer=producer, created_at_us=created_at_us,
                    pipeline=pipeline, parent_episode_id=parent_episode_id, labels=labels, bucket_id=bucket_id,
                    segmentation=segmentation, quality=quality, revision=revision)


def irregular(series: Mapping, timestamps: Sequence[int], values: Sequence[float], *,
              t_end_us: Optional[int] = None, episode_id: Optional[str] = None, source: str = "unknown",
              producer: str = PRODUCER, created_at_us: Optional[int] = None, pipeline: Optional[str] = None,
              parent_episode_id: Optional[str] = None, labels: Optional[Mapping[str, str]] = None,
              bucket_id: Optional[str] = None, segmentation: Optional[Mapping] = None,
              quality: Optional[bytes] = None, revision: int = 0) -> dict:
    ts = [int(t) for t in timestamps]
    v = np.asarray(values, dtype=np.float64)
    if len(ts) != len(v):
        raise ValueError("timestamps and values differ in length")
    if not ts:
        raise ValueError("an irregular episode needs at least one value")
    t_end = t_end_us if t_end_us is not None else ts[-1] + 1
    if ts[-1] >= t_end:
        raise ValueError("t_end must be after the last timestamp")
    return _episode(series, ts[0], t_end, v, "IRREGULAR_DELTA", None, encode_deltas(ts),
                    episode_id=episode_id, source=source, producer=producer, created_at_us=created_at_us,
                    pipeline=pipeline, parent_episode_id=parent_episode_id, labels=labels, bucket_id=bucket_id,
                    segmentation=segmentation, quality=quality, revision=revision)


def values(episode: Mapping) -> np.ndarray:
    return np.asarray(episode["values"], dtype=np.float64)


__all__ = ["regular", "irregular", "values", "timestamps_us"]
