"""Regenerates the shared Java/Python test vectors of the v2 model.

    python opentsx-model/src/test/resources/testvectors/generate.py

Only rerun when the specification changes intentionally - both test suites compare against these files.
xxHash64 reference values come from the ``xxhash`` package (independent of both implementations).
"""

import json
import pathlib

import xxhash

from opentsx import model as m
from opentsx.model.series_key import sorted_by_utf8

HERE = pathlib.Path(__file__).parent


def write(name, data):
    (HERE / name).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


# --- xxHash64 -----------------------------------------------------------------------------------------
inputs = ["", "a", "abc", "0123456789abcdef0123456789abcdef", "The quick brown fox jumps over the lazy dog",
          "x" * 100, "Windpark Ostsee — Turbine Ü7"]
write("xxh64.json", [{"input": s, "hex": xxhash.xxh64_hexdigest(s.encode("utf-8"), seed=0)} for s in inputs])

# --- series identity ----------------------------------------------------------------------------------
cases = [
    ("wind.turbine.power_active", {"park": "ber-01", "turbine": "T07"}),
    ("wind.turbine.power_active", {"turbine": "T07", "park": "ber-01"}),  # order must not matter
    ("wind.met.wind_speed", {}),
    ("a{b}", {"k=1": "v,2", "back\\slash": "x"}),  # escaping
    ("temp", {"Zone": "1", "zone": "2", "ä": "3", "a": "4"}),  # UTF-8 byte order: Z < a < z < ä
    ("rotor.rpm", {"park": "nordsee-süd", "turbine": "WEA-12", "channel": "WROT.RotSpd"}),
]
write("series-id.json", [
    {"metric": metric, "tags": tags, "canonical": m.canonical(metric, tags), "seriesId": m.series_id(metric, tags)}
    for metric, tags in cases
])

# --- irregular time axis ------------------------------------------------------------------------------
axes = [
    [1_700_000_000_000_000],
    [0, 1_000_000],
    [1_700_000_000_000_000, 1_700_000_001_000_000, 1_700_000_002_000_000, 1_700_000_003_500_000],
    [10, 11, 13, 16, 20, 1_000_000_000_020, 1_000_000_000_021],
    [-5_000_000, -1, 0, 3],
]
write("time-deltas.json", [{"timestampsUs": ts, "deltasHex": m.encode_deltas(ts).hex()} for ts in axes])

# --- wire format --------------------------------------------------------------------------------------
power = m.series_key("wind.turbine.power_active", {"turbine": "T07", "park": "ber-01"}, "kW")
speed = m.series_key("wind.met.wind_speed", {"park": "ber-01", "turbine": "T07"}, "m/s")
regular_spec = {
    "kind": "regular", "metric": power["metric"], "tags": power["tags"], "unit": power["unit"],
    "tStartUs": 1_700_000_000_000_000, "intervalUs": 600_000_000,
    "values": [1520.5, 1498.25, float("nan"), 1601.0, -0.0],
    "episodeId": "01HF7Z1Y4X0000000000000000", "createdAtUs": 1_700_000_100_000_000,
    "source": "simulator:scenario-1", "producer": "testvectors", "labels": {"regime": "partial-load", "experiment": "e1"},
    "bucketId": "bucket-42",
    "segmentation": {"strategy": "FIXED_DURATION", "params": {"duration": "PT50M"}, "anchorTs": None,
                     "sourceEpisodeId": None},
}
irregular_spec = {
    "kind": "irregular", "metric": speed["metric"], "tags": speed["tags"], "unit": speed["unit"],
    "timestampsUs": [1_700_000_000_000_000, 1_700_000_000_250_000, 1_700_000_000_500_000, 1_700_000_001_750_000],
    "values": [7.25, 7.5, 12.0, 6.75], "tEndUs": 1_700_000_002_000_000,
    "episodeId": "01HF7Z1Y4X0000000000000001", "createdAtUs": 1_700_000_100_000_000,
    "source": "scada:ber-01", "producer": "testvectors", "labels": {}, "bucketId": None,
    "segmentation": {"strategy": "FEATURE_THRESHOLD",
                     "params": {"enter": ">12", "exit": "<10", "post": "PT0.25S", "pre": "PT1S"},
                     "anchorTs": 1_700_000_000_500_000, "sourceEpisodeId": "01HF7Z1Y4X00000000000000ZZ"},
    "qualityHex": "00000102",
}


def build(spec):
    key = m.series_key(spec["metric"], spec["tags"], spec["unit"])
    seg = spec["segmentation"]
    if seg is not None:
        seg = {**seg, "params": sorted_by_utf8(seg["params"])}
    common = dict(episode_id=spec["episodeId"], created_at_us=spec["createdAtUs"], source=spec["source"],
                  producer=spec["producer"], labels=spec["labels"], bucket_id=spec["bucketId"], segmentation=seg)
    if spec["kind"] == "regular":
        return m.regular(key, spec["tStartUs"], spec["intervalUs"], spec["values"], **common)
    return m.irregular(key, spec["timestampsUs"], spec["values"], t_end_us=spec["tEndUs"],
                       quality=bytes.fromhex(spec["qualityHex"]), **common)


observation = {"seriesId": power["seriesId"], "ts": 1_700_000_000_000_000, "value": 1520.5, "quality": None}

write("wire-format.json", {
    "fingerprints": {name: f"{m.fingerprint(name):016x}" for name in
                     ["Episode", "Observation", "SeriesKey", "BucketManifest", "SeriesStats", "PatternMatch"]},
    "episodes": [{"spec": spec, "hex": m.encode("Episode", build(spec)).hex()} for spec in (regular_spec, irregular_spec)],
    "observation": {"record": observation, "hex": m.encode("Observation", observation).hex()},
})
print("test vectors written to", HERE)
