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


@pytest.mark.parametrize("cut, at", [(0, 0), (1, 1), (5, 1)])
def test_a_sketch_cut_in_its_header_raises_the_java_text(cut, at):
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(bytes.fromhex(JAVA_VECTOR)[:cut])
    assert str(e.value) == f"sketch bytes: truncated at {at} of {cut}"


@pytest.mark.parametrize("cut", [9, 12, len(JAVA_VECTOR) // 2 - 1])
def test_a_sketch_cut_inside_a_varint_raises_the_java_text(cut):
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(bytes.fromhex(JAVA_VECTOR)[:cut])
    assert str(e.value) == "sketch bytes: input ends inside a varint; expected complete varints"


_HEADER = bytes.fromhex(JAVA_VECTOR)[:9]


@pytest.mark.parametrize("body, text", [
    (bytes([0x80]), "sketch bytes: input ends inside a varint; expected complete varints"),
    (bytes([0xFF] * 9 + [0x01, 0, 0]),
     "sketch bytes: varint exceeds 9223372036854775807;"
     " expected a whole number from 0 to 9223372036854775807"),
    (bytes([0x80] * 9 + [0x02, 0, 0]),
     "sketch bytes: varint exceeds 64 bits; expected at most 64 bits per varint"),
    (bytes([0x80] * 9 + [0x81, 0x00, 0, 0]),
     "sketch bytes: varint longer than 10 bytes; expected at most 10 bytes per varint"),
])
def test_the_decoder_refuses_the_varints_of_quantile_sketch_test(body, text):
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(_HEADER + body)
    assert str(e.value) == text


def _zigzag_varint(v: int) -> bytes:
    z = ((v << 1) ^ (v >> 63)) & 0xFFFFFFFFFFFFFFFF
    out = bytearray()
    while z & ~0x7F:
        out.append((z & 0x7F) | 0x80)
        z >>= 7
    out.append(z)
    return bytes(out)


def _bins_at(*deltas: int) -> bytes:
    bins = b"".join(_zigzag_varint(d) + b"\x01" for d in deltas)
    return _HEADER + bytes([0, len(deltas)]) + bins + b"\x00"


@pytest.mark.parametrize("deltas, index", [
    ((1 << 40,), 1 << 40),
    ((2**31 - 1, 1), 2**31),
    ((-2**31, -1), -2**31 - 1),
    ((1, 2**63 - 1), 2**63),
])
def test_a_bin_index_outside_the_int_range_raises_the_java_text(deltas, index):
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(_bins_at(*deltas))
    assert str(e.value) == f"sketch bytes: bin index {index} out of range; expected an int"


def test_bin_indexes_at_the_ends_of_the_int_range_are_read():
    s = QuantileSketch.from_bytes(_bins_at(-2**31, 2**32 - 1))
    assert s.positive == {-2**31: 1, 2**31 - 1: 1}


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
        "cut": "sketch bytes: input ends inside a varint; expected complete varints",
        "header": "sketch bytes: truncated at 1 of 5",
        "empty": "sketch bytes: truncated at 0 of 0",
    }
    assert QuantileSketch.from_bytes(data) == s


def test_an_unknown_version_byte_is_read_signed_as_in_java():
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(bytes([0xFF, 0]))
    assert str(e.value) == "sketch bytes: format version -1 is not supported; expected 1"


_COUNT_ABOVE_LONG = ("sketch bytes: zeroCount and the bin counts add up to more than"
                     " 9223372036854775807; expected a total count of at most 9223372036854775807")
_NOT_ASCENDING = "; expected bins in ascending index order"
_ALPHA_ALLOWED = " is out of range; expected a number above 0 and below 1"


# the same bytes and texts as QuantileSketchTest (Java)
@pytest.mark.parametrize("data, text", [
    ("017b14ae47e17a843fffffffffffffffff7f01020100", _COUNT_ABOVE_LONG),
    ("017b14ae47e17a843f000202ffffffffffffffff7f020100", _COUNT_ABOVE_LONG),
    ("017b14ae47e17a843f00020a01000100",
     "sketch bytes: bin index 5 is not above the previous index 5" + _NOT_ASCENDING),
    ("017b14ae47e17a843f00020a01030100",
     "sketch bytes: bin index 3 is not above the previous index 5" + _NOT_ASCENDING),
    ("017b14ae47e17a843f00000207010001",
     "sketch bytes: bin index -4 is not above the previous index -4" + _NOT_ASCENDING),
    ("01000000000000f87f000000", "alpha: NaN" + _ALPHA_ALLOWED),
    ("01000000000000f07f000000", "alpha: Infinity" + _ALPHA_ALLOWED),
    ("019c7500883ce4377e000000", "alpha: 1.0E300" + _ALPHA_ALLOWED),
    ("01f168e388b5f8e4be000000", "alpha: -1.0E-5" + _ALPHA_ALLOWED),
    ("010100000000000080000000", "alpha: -4.9E-324" + _ALPHA_ALLOWED),
])
def test_the_decoder_raises_the_java_text_for_the_same_bytes(data, text):
    with pytest.raises(ValueError) as e:
        QuantileSketch.from_bytes(bytes.fromhex(data))
    assert str(e.value) == text


def test_the_first_bin_may_sit_at_index_0_and_below():
    assert QuantileSketch.from_bytes(_bins_at(0)).positive == {0: 1}
    assert QuantileSketch.from_bytes(_bins_at(-3)).positive == {-3: 1}


# the same texts as QuantileSketchTest.theRefusalsNameTheValueAndWhatIsAllowed (Java)
def test_the_refusals_name_the_value_and_what_is_allowed():
    texts = []
    for action in (lambda: QuantileSketch(5.0),
                   lambda: QuantileSketch(0.01).merge(QuantileSketch(0.02)),
                   lambda: QuantileSketch().quantile(1.5),
                   lambda: QuantileSketch().quantile(math.nan)):
        with pytest.raises(ValueError) as e:
            action()
        texts.append(str(e.value))
    assert texts == [
        "alpha: 5.0 is out of range; expected a number above 0 and below 1",
        "alpha: cannot merge a sketch with alpha 0.02 into one with alpha 0.01;"
        " expected the same alpha",
        "q: 1.5 is out of range; expected a number from 0 to 1",
        "q: NaN is out of range; expected a number from 0 to 1",
    ]


# Double.toString in Java writes each value as given here
@pytest.mark.parametrize("value, text", [
    (5, "5.0"), (1.0, "1.0"), (100.0, "100.0"), (9999999.0, "9999999.0"), (1e7, "1.0E7"),
    (12345678.9, "1.23456789E7"), (-0.5, "-0.5"), (-0.001, "-0.001"), (-1e-4, "-1.0E-4"),
    (-1e-5, "-1.0E-5"), (0.0, "0.0"), (-0.0, "-0.0"),
    (1e300, "1.0E300"), (math.inf, "Infinity"), (-math.inf, "-Infinity"), (math.nan, "NaN"),
    (-5e-324, "-4.9E-324"), (-1e-323, "-9.9E-324"),
])
def test_an_alpha_out_of_range_is_written_as_java_writes_a_double(value, text):
    with pytest.raises(ValueError) as e:
        QuantileSketch(value)
    assert str(e.value) == "alpha: " + text + _ALPHA_ALLOWED


def test_an_alpha_beyond_the_double_range_raises_value_error_and_names_it():
    with pytest.raises(ValueError) as e:
        QuantileSketch(10 ** 400)
    assert str(e.value) == "alpha: 1" + "0" * 400 + _ALPHA_ALLOWED
