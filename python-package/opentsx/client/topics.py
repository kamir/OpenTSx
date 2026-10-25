"""Reads the episodes of a topic directly from the broker (KafScale or Kafka), without the retrieval server.

The reader assigns every partition, reads from the start to the end it found when it began, keeps the episodes that
match, and stops. It joins no consumer group and commits nothing, so it leaves no trace in the broker. A broker that
is shared may be slow: the reader has a limit of records it reads, and it says so when the topic holds more, instead
of reading on silently.

>>> r = TopicReader(os.environ["KAFKA_BOOTSTRAP"])
>>> eps = r.episodes("tsx.wind.episodes.v2", metric="weather.wind_speed_10min", tags={"station": "01759"})
>>> df = episodes_to_frame(eps)

Needs ``confluent-kafka`` (``pip install opentsx[kafka]``).
"""

from __future__ import annotations

from math import isfinite
from time import monotonic
from typing import Any, Callable, List, Mapping, Optional

import pandas as pd

from opentsx import model as m
from opentsx.model.time_axis import to_us

try:  # the reader needs confluent-kafka; the rest of the package does not
    from confluent_kafka import TopicPartition
except ImportError:  # pragma: no cover - depends on the environment
    from collections import namedtuple

    _TP = namedtuple("TopicPartition", "topic partition offset")

    def TopicPartition(topic, partition, offset=-1001):  # noqa: N802 - same name as the class it stands in for
        return _TP(topic, partition, offset)


class TopicLimitReached(RuntimeError):
    """The topic holds more records than the reader was allowed to read."""


def _micros(t: Any) -> Optional[int]:
    if t is None:
        return None
    ts = pd.Timestamp(t)
    if ts.tzinfo is None:
        raise ValueError("naive time; pass a timezone-aware value (UTC)")
    return int(ts.value // 1000)


def _matches(
    ep: Mapping,
    metric: Optional[str],
    tags: Optional[Mapping[str, str]],
    start: Optional[int],
    end: Optional[int],
) -> bool:
    s = ep["series"]
    if metric is not None and s["metric"] != metric:
        return False
    if tags and any((s.get("tags") or {}).get(k) != v for k, v in tags.items()):
        return False
    t0 = to_us(ep["tStart"])
    t1 = to_us(ep["tEnd"])
    if start is not None and t1 <= start:  # tEnd is the end of the last interval, not inside it
        return False
    if end is not None and t0 >= end:
        return False
    return True


class TopicReader:
    """Reads the episodes of a topic once, from the start to the end it found when it began."""

    def __init__(
        self,
        bootstrap: str,
        consumer_factory: Optional[Callable[[dict], Any]] = None,
        timeout_s: float = 30.0,
    ):
        if not isfinite(timeout_s) or timeout_s <= 0:
            raise ValueError("timeout_s must be finite and positive")
        self.bootstrap = bootstrap
        self.timeout_s = timeout_s
        self._factory = consumer_factory

    def _consumer(self):
        conf = {
            "bootstrap.servers": self.bootstrap,
            "group.id": "opentsx-notebook-reader",
            "enable.auto.commit": False,
            "enable.auto.offset.store": False,
            "enable.partition.eof": True,
            "auto.offset.reset": "earliest",
        }
        if self._factory is not None:
            return self._factory(conf)
        try:
            from confluent_kafka import Consumer
        except ImportError as e:  # pragma: no cover - depends on the environment
            raise ImportError(
                "TopicReader needs confluent-kafka: pip install 'opentsx[kafka]'"
            ) from e
        return Consumer(conf)

    def episodes(
        self,
        topic: str,
        metric: Optional[str] = None,
        tags: Optional[Mapping[str, str]] = None,
        start: Any = None,
        end: Any = None,
        max_records: int = 20_000,
    ) -> List[dict]:
        """Episodes of ``topic`` that match; raises :class:`TopicLimitReached` past ``max_records`` records read."""
        lo, hi = _micros(start), _micros(end)
        deadline = monotonic() + self.timeout_s

        def remaining():
            budget = deadline - monotonic()
            if budget <= 0:
                raise TimeoutError(f"deadline exceeded while reading {topic}")
            return budget

        c = self._consumer()
        out: List[dict] = []
        try:
            meta = c.list_topics(topic, timeout=remaining())
            remaining()
            if topic not in meta.topics or meta.topics[topic].error is not None:
                raise ValueError(f"the broker has no topic {topic}")
            parts = sorted(meta.topics[topic].partitions)
            ends = {}
            assign = []
            for p in parts:
                low, high = c.get_watermark_offsets(TopicPartition(topic, p), timeout=remaining())
                remaining()
                if high > low:
                    ends[p] = high
                    assign.append(TopicPartition(topic, p, low))
            total = sum(ends[p] - a.offset for a in assign for p in [a.partition])
            if total > max_records:
                raise TopicLimitReached(
                    f"{topic} holds {total} records, more than max_records={max_records}; "
                    "narrow the question or raise the limit on purpose"
                )
            remaining()
            c.assign(assign)
            remaining()
            open_parts = set(ends)
            coordinator_events = 0
            while open_parts:
                msg = c.poll(remaining())
                remaining()
                if msg is None:
                    raise TimeoutError(
                        f"no answer from the broker for {self.timeout_s} s while reading {topic}"
                    )
                error = msg.error()
                if error:
                    if error.code() == -191:  # _PARTITION_EOF
                        open_parts.discard(msg.partition())
                        continue
                    if (
                        error.code() == -165
                        and not error.fatal()
                        and error.str().startswith("FindCoordinator response error:")
                    ):
                        coordinator_events += 1
                        if coordinator_events > 32:
                            raise RuntimeError(
                                f"coordinator event limit exceeded while reading {topic}"
                            )
                        continue
                    raise RuntimeError(f"reading {topic}: {error}")
                if msg.offset() + 1 >= ends.get(msg.partition(), 0):
                    open_parts.discard(msg.partition())
                name, rec = m.decode(msg.value())
                remaining()
                if name == "Episode" and _matches(rec, metric, tags, lo, hi):
                    out.append(rec)
        finally:
            c.close()
        # one episode per identity: a topic may hold the same episode twice when it was written again
        seen = {}
        for e in out:
            seen[e["episodeId"]] = e
        return sorted(seen.values(), key=lambda e: (e["series"]["seriesId"], to_us(e["tStart"])))
