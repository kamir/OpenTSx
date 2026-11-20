"""v2 model: Python must agree with Java byte for byte (shared vectors in opentsx-model)."""

import importlib
import json
import math
import pathlib
import threading
from datetime import datetime

import numpy as np
import pandas as pd
import pytest

from opentsx import model as m
from opentsx.model import serde
from opentsx.model.pandas_io import episode_to_series, episodes_to_frame, series_to_episode
from opentsx.model.series_key import sorted_by_utf8
from opentsx.model.time_axis import to_us

REPO = pathlib.Path(__file__).resolve().parents[2]
VECTORS = REPO / "opentsx-model" / "src" / "test" / "resources" / "testvectors"
JAVA_SCHEMAS = REPO / "opentsx-model" / "src" / "main" / "avro"
PY_SCHEMAS = pathlib.Path(m.__file__).parent / "schemas"


def load(name):
    return json.loads((VECTORS / name).read_text(encoding="utf-8"))


def test_schema_copies_are_identical_to_the_java_module():
    java = sorted(p.name for p in JAVA_SCHEMAS.glob("*.avsc"))
    assert java == sorted(p.name for p in PY_SCHEMAS.glob("*.avsc"))
    for name in java:
        assert (JAVA_SCHEMAS / name).read_bytes() == (PY_SCHEMAS / name).read_bytes(), name


def test_xxh64_reference_vectors():
    for v in load("xxh64.json"):
        assert m.xxh64_hex(v["input"].encode("utf-8")) == v["hex"]


def test_series_identity_vectors():
    for v in load("series-id.json"):
        assert m.canonical(v["metric"], v["tags"]) == v["canonical"]
        assert m.series_id(v["metric"], v["tags"]) == v["seriesId"]


def test_tag_order_and_unit_do_not_change_identity():
    a = m.series_key("p", {"b": "2", "a": "1"}, "kW")
    b = m.series_key("p", {"a": "1", "b": "2"}, "W")
    assert a["seriesId"] == b["seriesId"]
    assert list(a["tags"]) == ["a", "b"]


def test_time_delta_vectors_roundtrip():
    for v in load("time-deltas.json"):
        ts = v["timestampsUs"]
        enc = m.encode_deltas(ts)
        assert enc.hex() == v["deltasHex"]
        np.testing.assert_array_equal(m.decode_deltas(ts[0], len(ts), enc), ts)


def test_time_delta_rejects_bad_input():
    with pytest.raises(ValueError):
        m.encode_deltas([5, 5])
    with pytest.raises(ValueError):
        m.decode_deltas(0, 2, bytes([2, 2]))


def test_a_time_without_a_zone_names_the_field():
    with pytest.raises(ValueError) as e:
        m.timestamps_us({"tStart": datetime(2026, 10, 1), "count": 1, "timeEncoding": "REGULAR",
                         "intervalMicros": 1})
    assert str(e.value) == ("tStart: 2026-10-01T00:00:00 has no time zone; "
                            "expected a timezone-aware datetime or epoch microseconds")
    with pytest.raises(ValueError) as e:
        to_us(datetime(2026, 10, 1, 12, 30), "createdAt")
    assert str(e.value) == ("createdAt: 2026-10-01T12:30:00 has no time zone; "
                            "expected a timezone-aware datetime or epoch microseconds")


@pytest.mark.parametrize("t_start, count, deltas, i", [
    (2**63 - 1, 2, "02", 1),
    (0, 3, "feffffffffffffffff01" "02", 2),
    (-2**63, 3, "feffffffffffffffff01" "02", 2),
    (-2**63, 2, "01", 1),
])
def test_a_decoded_timestamp_outside_64_bits_is_refused(t_start, count, deltas, i):
    with pytest.raises(ValueError) as e:
        m.decode_deltas(t_start, count, bytes.fromhex(deltas))
    assert str(e.value) == (f"timeDeltas: timestamp {i} leaves the 64-bit range; expected timestamps and deltas "
                            "from -9223372036854775808 to 9223372036854775807")


def test_timestamps_at_the_ends_of_the_64_bit_range_are_decoded():
    np.testing.assert_array_equal(m.decode_deltas(2**63 - 2, 2, bytes.fromhex("02")), [2**63 - 2, 2**63 - 1])
    np.testing.assert_array_equal(m.decode_deltas(-2**63, 2, bytes.fromhex("feffffffffffffffff01")), [-2**63, -1])


@pytest.mark.parametrize("timestamps, i, step", [
    ([-2**63, 2**63 - 1], 1, 2**64 - 1),
    ([-2, -1, 2**63 - 1], 2, 2**63),
])
def test_a_step_outside_64_bits_is_refused_when_encoding(timestamps, i, step):
    with pytest.raises(ValueError) as e:
        m.encode_deltas(timestamps)
    assert str(e.value) == (f"timestamps: timestamp {i} is {step} after timestamp {i - 1}; "
                            "expected steps of at most 9223372036854775807 microseconds")


@pytest.mark.parametrize("timestamps, i, t", [
    ([2**63, 2**63 + 1], 0, 2**63),
    ([0, 2**63], 1, 2**63),
    ([-2**63 - 1, 0], 0, -2**63 - 1),
    ([2**63], 0, 2**63),
])
def test_a_timestamp_outside_64_bits_is_refused_when_encoding(timestamps, i, t):
    text = f"timestamps: timestamp {i} is {t}; expected timestamps from -9223372036854775808 to 9223372036854775807"
    with pytest.raises(ValueError) as e:
        m.encode_deltas(timestamps)
    assert str(e.value) == text
    with pytest.raises(ValueError) as e:
        m.irregular(m.series_key("x"), timestamps, [1.0] * len(timestamps), t_end_us=0)
    assert str(e.value) == text


def test_timestamps_at_the_ends_of_the_64_bit_range_are_encoded():
    timestamps = [-2**63, -2**63 + 1]
    enc = m.encode_deltas(timestamps)
    np.testing.assert_array_equal(m.decode_deltas(timestamps[0], 2, enc), timestamps)
    episode = m.irregular(m.series_key("x"), [2**63 - 3, 2**63 - 2], [1.0, 2.0])
    np.testing.assert_array_equal(m.timestamps_us(episode), [2**63 - 3, 2**63 - 2])


def test_the_largest_step_is_encoded_and_decoded():
    timestamps = [-2**63, -1, 2**63 - 2]
    enc = m.encode_deltas(timestamps)
    assert enc.hex() == "feffffffffffffffff0100"
    np.testing.assert_array_equal(m.decode_deltas(timestamps[0], 3, enc), timestamps)


REFUSALS = json.loads((REPO / "docs" / "datamodel-2026" / "testvectors" / "model-refusals.v1.json").read_text(
    encoding="utf-8"))


def _refused_call(case):
    key = m.series_key("x")
    quality = bytes.fromhex(case["qualityHex"]) if "qualityHex" in case else None
    if case["call"] == "encodeDeltas":
        return lambda: m.encode_deltas(case["timestampsUs"])
    if case["call"] == "decodeDeltas":
        return lambda: m.decode_deltas(case["tStartUs"], case["count"], bytes.fromhex(case["deltasHex"]))
    if case["call"] == "regular":
        return lambda: m.regular(key, case["tStartUs"], case["intervalUs"], case["values"],
                                 t_end_us=case.get("tEndUs"), quality=quality)
    return lambda: m.irregular(key, case["timestampsUs"], case["values"], t_end_us=case.get("tEndUs"))


@pytest.mark.parametrize("case", REFUSALS["cases"], ids=lambda c: c["name"])
def test_refusals_say_what_java_says(case):
    with pytest.raises(ValueError) as e:
        _refused_call(case)()
    assert str(e.value) == case["error"]


def test_summary_merge_equals_summary_of_concatenation(rng):
    a, b = rng.normal(size=500), rng.normal(loc=50, size=31)
    merged = m.merge_summaries(m.summarize(a), m.summarize(b))
    direct = m.summarize(np.concatenate([a, b]))
    assert merged["validCount"] == direct["validCount"]
    assert merged["mean"] == pytest.approx(direct["mean"])
    assert m.variance(merged) == pytest.approx(np.var(np.concatenate([a, b]), ddof=1))


def test_summary_counts_nan_separately():
    s = m.summarize([1.0, float("nan"), 3.0])
    assert (s["validCount"], s["nanCount"], s["mean"]) == (2, 1, 2.0)


def test_summary_counts_infinite_values_as_nan():
    s = m.summarize([1.0, math.inf, 3.0, -math.inf, math.nan])
    assert (s["validCount"], s["nanCount"], s["min"], s["max"], s["mean"]) == (2, 3, 1.0, 3.0, 2.0)
    assert m.variance(s) == 2.0


def test_fingerprints_match_java():
    for name, fp in load("wire-format.json")["fingerprints"].items():
        assert f"{m.fingerprint(name):016x}" == fp


def _build(spec):
    key = m.series_key(spec["metric"], spec["tags"], spec["unit"])
    seg = dict(spec["segmentation"], params=sorted_by_utf8(spec["segmentation"]["params"]))
    common = dict(episode_id=spec["episodeId"], created_at_us=spec["createdAtUs"], source=spec["source"],
                  producer=spec["producer"], labels=spec["labels"], bucket_id=spec["bucketId"], segmentation=seg)
    if spec["kind"] == "regular":
        return m.regular(key, spec["tStartUs"], spec["intervalUs"], spec["values"], **common)
    return m.irregular(key, spec["timestampsUs"], spec["values"], t_end_us=spec["tEndUs"],
                       quality=bytes.fromhex(spec["qualityHex"]), **common)


def test_episodes_encode_to_java_bytes_and_decode_back():
    for v in load("wire-format.json")["episodes"]:
        payload = bytes.fromhex(v["hex"])
        assert m.encode("Episode", _build(v["spec"])) == payload
        name, decoded = m.decode(payload)
        assert name == "Episode"
        assert m.encode("Episode", decoded) == payload  # datetimes from the decoder re-encode identically
        np.testing.assert_array_equal(m.timestamps_us(decoded), m.timestamps_us(_build(v["spec"])))


def test_observation_matches_java_bytes():
    v = load("wire-format.json")["observation"]
    assert m.encode("Observation", v["record"]).hex() == v["hex"]


def test_decode_rejects_unknown_payloads():
    with pytest.raises(ValueError):
        m.decode(b"\x00\x00\x00\x00\x01abc")
    with pytest.raises(ValueError):
        m.decode(b"\xc3\x01" + bytes(8) + b"x")


def test_decode_rejects_a_truncated_body():
    payload = m.encode("Episode", m.regular(m.series_key("x"), 0, 10, [1.0, 2.0]))
    for cut in (12, 30, len(payload) - 3):
        with pytest.raises(ValueError, match="^Episode: unreadable Avro body"):
            m.decode(payload[:cut])


def test_an_unknown_schema_name_lists_the_known_names():
    for call in (m.schema, m.fingerprint, lambda name: m.encode(name, {})):
        with pytest.raises(ValueError, match=r'^schema: "Episodes" is unknown; use one of .*\bEpisode\b'):
            call("Episodes")


def test_two_threads_decoding_on_first_use_both_succeed(monkeypatch):
    payload = m.encode("Episode", m.regular(m.series_key("x"), 0, 10, [1.0, 2.0]))
    importlib.reload(serde)
    real = serde.parse_schema
    first_paused, second_done = threading.Event(), threading.Event()
    calls = []

    def parse_schema(*args, **kwargs):
        if threading.current_thread().name == "first":
            calls.append(1)
            if len(calls) == 2:
                first_paused.set()
                assert second_done.wait(30)
        return real(*args, **kwargs)

    def first():
        results["first"] = serde.decode(payload)[0]

    def second():
        assert first_paused.wait(30)
        try:
            results["second"] = serde.decode(payload)[0]
        except Exception as e:
            results["second"] = repr(e)
        finally:
            second_done.set()

    results = {}
    monkeypatch.setattr(serde, "parse_schema", parse_schema)
    try:
        threads = [threading.Thread(target=first, name="first"), threading.Thread(target=second, name="second")]
        for t in threads:
            t.start()
        for t in threads:
            t.join(30)
        assert results == {"first": "Episode", "second": "Episode"}
    finally:
        monkeypatch.undo()
        importlib.reload(serde)


def test_pandas_regular_roundtrip_keeps_utc_and_spacing():
    key = m.series_key("wind.met.wind_speed", {"park": "ber-01"}, "m/s")
    idx = pd.date_range("2026-01-01", periods=6, freq="10min", tz="Europe/Berlin")
    s = pd.Series([5.0, 6.5, float("nan"), 7.25, 8.0, 7.5], index=idx)

    ep = series_to_episode(s, key, source="test")
    assert ep["timeEncoding"] == "REGULAR"
    assert ep["intervalMicros"] == 600_000_000
    back = episode_to_series(ep)
    assert str(back.index.tz) == "UTC"
    assert (back.index == idx.tz_convert("UTC")).all()
    np.testing.assert_array_equal(back.to_numpy(), s.to_numpy())


def test_pandas_irregular_roundtrip_and_long_frame():
    key = m.series_key("wind.turbine.power_active", {"turbine": "T07"}, "kW")
    idx = pd.DatetimeIndex(["2026-01-01T00:00:00.000001Z", "2026-01-01T00:00:00.250Z", "2026-01-01T00:00:03Z"])
    s = pd.Series([1.0, 2.0, 3.0], index=idx)
    ep = series_to_episode(s, key)
    assert ep["timeEncoding"] == "IRREGULAR_DELTA"
    assert (episode_to_series(ep).index == idx).all()

    _, decoded = m.decode(m.encode("Episode", ep))
    frame = episodes_to_frame([decoded, ep])
    assert list(frame.columns) == ["series_id", "metric", "episode_id", "ts", "value"]
    assert len(frame) == 6
    assert str(frame["ts"].dt.tz) == "UTC"


def test_naive_index_is_rejected_unless_utc_is_assumed():
    key = m.series_key("x")
    s = pd.Series([1.0, 2.0], index=pd.date_range("2026-01-01", periods=2, freq="s"))
    with pytest.raises(ValueError):
        series_to_episode(s, key)
    assert series_to_episode(s, key, assume_utc=True)["count"] == 2


def test_builders_validate():
    key = m.series_key("x")
    with pytest.raises(ValueError):
        m.regular(key, 0, 10, [1, 2, 3], t_end_us=20)
    with pytest.raises(ValueError):
        m.irregular(key, [1, 2], [1.0])
    with pytest.raises(ValueError):
        m.regular(dict(key, seriesId="0000000000000000"), 0, 10, [1.0])
    assert math.isnan(m.regular(key, 0, 10, [float("nan")])["summary"]["mean"])


def test_bucket_checksum_vectors_ignore_order_and_duplicates():
    for v in load("bucket-checksum.json"):
        assert m.bucket_checksum(v["episodeIds"]) == v["checksum"]
        assert m.bucket_checksum(reversed(v["episodeIds"])) == v["checksum"]
