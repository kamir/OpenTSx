"""v2 model: Python must agree with Java byte for byte (shared vectors in opentsx-model)."""

import json
import math
import pathlib

import numpy as np
import pandas as pd
import pytest

from opentsx import model as m
from opentsx.model.pandas_io import episode_to_series, episodes_to_frame, series_to_episode
from opentsx.model.series_key import sorted_by_utf8

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
