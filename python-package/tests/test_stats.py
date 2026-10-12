import math
import random
from datetime import datetime, timedelta, timezone

import pytest

from opentsx import model as m
from opentsx.stats import QuantileSketch, stats_to_frame

# same values and bytes as QuantileSketchTest.testVector (Java)
JAVA_VECTOR = "017b14ae47e17a843f020500015c0278012601ba0301024301ca0101"


def test_bytes_identical_to_java():
    s = QuantileSketch(0.01).update([0.0, 1.0, 2.5, 2.5, -3.75, 8.2, 12.0, 1e-12, 1000.0, -0.5])
    assert s.to_bytes().hex() == JAVA_VECTOR
    back = QuantileSketch.from_bytes(bytes.fromhex(JAVA_VECTOR))
    assert back == s
    assert back.count == 10


def test_relative_accuracy_and_merge():
    rnd = random.Random(1)
    values = [math.exp(rnd.gauss(0, 2)) if i % 2 else rnd.uniform(-50, 50) for i in range(50_000)]
    a, b, full = QuantileSketch(), QuantileSketch(), QuantileSketch()
    for i, v in enumerate(values):
        (a if i % 2 else b).add(v)
        full.add(v)
    assert a.merge(b) == full
    ordered = sorted(values)
    for q in (0.0, 0.01, 0.25, 0.5, 0.75, 0.99, 1.0):
        want = ordered[math.floor(q * (len(ordered) - 1))]
        assert abs(full.quantile(q) - want) <= 0.01 * abs(want) + 1e-9


def test_stats_frame_from_avro_records():
    t0 = datetime(2026, 10, 1, tzinfo=timezone.utc)
    sk = QuantileSketch().update([7.5, 8.0, 8.5, 9.0])

    def rec(valid, start):
        return {"seriesId": "58280afbd12100b7", "windowType": "PT10M", "windowStart": start,
                "windowEnd": start + timedelta(minutes=10), "validCount": valid, "nanCount": 4 - valid,
                "min": 7.5, "max": 9.0, "mean": 8.25, "m2": 1.25, "quantileSketch": sk.to_bytes(),
                "gapCount": 0, "lastSeen": start + timedelta(minutes=9)}

    # an earlier, partial emission of the same window and the final one (no compaction on KafScale)
    payloads = [m.encode("SeriesStats", rec(2, t0)), m.encode("SeriesStats", rec(4, t0)),
                m.encode("SeriesStats", rec(4, t0 + timedelta(minutes=10)))]
    records = [m.decode(p)[1] for p in payloads]
    df = stats_to_frame(records)
    assert len(df) == 2
    first = df.iloc[0]
    assert first["valid_count"] == 4
    assert first["std"] == pytest.approx(math.sqrt(1.25 / 3))
    assert first["q50"] == pytest.approx(8.0, rel=0.01)
    assert str(first["window_start"].tz) == "UTC"
