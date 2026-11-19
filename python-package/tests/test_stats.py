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


@pytest.mark.parametrize(
    "cut, at",
    [(0, 0), (1, 1), (5, 1), (9, 9), (12, 12), (len(JAVA_VECTOR) // 2 - 1, len(JAVA_VECTOR) // 2 - 1)],
)
def test_a_truncated_sketch_raises_the_java_text(cut, at):
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(bytes.fromhex(JAVA_VECTOR)[:cut])
    assert str(e.value) == f"sketch bytes: truncated at {at} of {cut}"


def test_trailing_bytes_after_a_sketch_raise_the_java_text():
    data = bytes.fromhex(JAVA_VECTOR)
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(data + b"\x00\x00")
    assert str(e.value) == f"sketch bytes: 2 trailing bytes; expected exactly {len(data)}"


def test_the_decoder_raises_the_texts_of_quantile_sketch_test():
    s = QuantileSketch(0.01).update([1.0, 2.5, -3.75, 0.0])
    data = s.to_bytes()
    texts = {}
    for name, cut in (("trailing", data + bytes(2)), ("cut", data[:-1]), ("header", data[:5]), ("empty", b"")):
        with pytest.raises(ValueError) as e:
            QuantileSketch.from_bytes(cut)
        texts[name] = str(e.value)
    assert texts == {
        "trailing": f"sketch bytes: 2 trailing bytes; expected exactly {len(data)}",
        "cut": f"sketch bytes: truncated at {len(data) - 1} of {len(data) - 1}",
        "header": "sketch bytes: truncated at 1 of 5",
        "empty": "sketch bytes: truncated at 0 of 0",
    }
    assert QuantileSketch.from_bytes(data) == s


def test_an_unknown_version_byte_is_read_signed_as_in_java():
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(bytes([0xFF, 0]))
    assert str(e.value) == "unsupported sketch format version -1"
