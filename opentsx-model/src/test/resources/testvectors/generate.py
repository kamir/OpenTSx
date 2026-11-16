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

# --- bucket checksum ----------------------------------------------------------------------------------
bucket_cases = [
    [],
    ["01HF7Z1Y4X0000000000000001"],
    ["01HF7Z1Y4X0000000000000002", "01HF7Z1Y4X0000000000000001", "01HF7Z1Y4X0000000000000002"],
]
write("bucket-checksum.json", [{"episodeIds": ids, "checksum": m.bucket_checksum(ids)} for ids in bucket_cases])

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


# --- analysis results ---------------------------------------------------------------------------------
# Parameters are written with their Avro type, {"long": "2"} or {"double": 2.0}, because JSON numbers lose it.
# Longs are decimal strings (exact beyond 2^53 in every language).
def typed(params):
    out = {}
    for k, v in params.items():
        (kind, value), = v.items()
        out[k] = {"long": int, "double": float, "string": str, "array": lambda a: [float(x) for x in a]}[kind](value)
    return out


def jsonable(value):
    """Doubles as JSON numbers; NaN and infinities as the strings "NaN", "Infinity" and "-Infinity"."""
    if value != value:
        return "NaN"
    if value in (float("inf"), float("-inf")):
        return "Infinity" if value > 0 else "-Infinity"
    return value


es_inputs = ["01HF7Z1Y4X0000000000000010", "01HF7Z1Y4X0000000000000011", "01HF7Z1Y4X0000000000000012"]
result_cases = [
    ("dfa", "1.0.0", {"order": {"long": 1}, "scales": {"array": [16, 32, 64, 128, 256]},
                      "fitFrom": {"double": 16.0}, "fitTo": {"double": 256.0}}, ["01HF7Z1Y4X0000000000000000"]),
    ("dfa", "1.0.0", {"fitTo": {"double": 256.0}, "fitFrom": {"double": 16.0},
                      "scales": {"array": [16, 32, 64, 128, 256]}, "order": {"long": 1}}, ["01HF7Z1Y4X0000000000000000"]),
    ("es", "1.0.0", {"threshold": {"string": "std"}, "tauMax": {"double": 5.0}}, es_inputs),
    ("es", "1.0.0", {"threshold": {"string": "std"}, "tauMax": {"double": 5.0}}, list(reversed(es_inputs))),
    ("es", "1.0.0", {"tauMax": {"long": 5}, "threshold": {"string": "std"}}, es_inputs),
    ("a@b{c}", "1(2)", {"k=1": {"string": "x,y]"}, "back\\slash": {"string": "@"}}, ["e[1]", "e,2"]),
    ("dfa", "1.0.0", {"Zone": {"long": -1}, "zone": {"double": -0.0}, "\u00e4": {"array": []}, "a": {"double": 0.0}},
     ["01HF7Z1Y4X0000000000000000"]),
    ("ris", "1.0.0", {"cap": {"double": float("inf")}, "big": {"long": 9_007_199_254_740_993}}, ["x"]),
    ("dfa", "1.0.0", {}, ["01HF7Z1Y4X0000000000000000"]),
]
write("result-id.json", [
    {"method": method, "methodVersion": version,
     "params": {k: {kind: (jsonable(v) if kind == "double" else str(v) if kind == "long" else v)
                    for kind, v in typed_value.items()} for k, typed_value in params.items()},
     "inputEpisodeIds": inputs,
     "canonical": m.result_canonical(method, version, typed(params), inputs),
     "resultId": xxhash.xxh64_hexdigest(m.result_canonical(method, version, typed(params), inputs).encode("utf-8"),
                                        seed=0)}
    for method, version, params, inputs in result_cases
])

dfa_spec = {
    "method": "dfa", "methodVersion": "1.0.0",
    "params": {"order": {"long": "1"}, "scales": {"array": [16, 32, 64, 128, 256]}},
    "inputEpisodeIds": ["01HF7Z1Y4X0000000000000000"], "inputSeriesIds": [power["seriesId"]],
    "tStartUs": 1_700_000_000_000_000, "tEndUs": 1_700_000_003_000_000,
    "scalars": [{"name": "alpha", "value": 0.75, "stdError": 0.0125, "unit": None, "fitFrom": 16.0, "fitTo": 256.0},
                {"name": "r_squared", "value": 0.998, "stdError": None, "unit": None, "fitFrom": None, "fitTo": None}],
    "curves": [{"name": "fluctuation", "xName": "scale", "xUnit": "samples", "x": [16.0, 32.0, 64.0, 128.0, 256.0],
                "y": [1.5, 2.5, 4.25, 7.0, 12.0], "unit": "kW"}],
    "matrices": [],
    "labels": {"experiment": "e1", "regime": "partial-load"},
    "source": "notebook:dfa", "producer": "testvectors", "createdAtUs": 1_700_000_100_000_000, "pipeline": None,
}
es_keys = [power["seriesId"], speed["seriesId"], "0000000000000001"]
es_spec = {
    "method": "es", "methodVersion": "1.0.0",
    "params": {"threshold": {"string": "std"}, "tauMax": {"double": 5.0}},
    "inputEpisodeIds": es_inputs, "inputSeriesIds": es_keys,
    "tStartUs": 1_700_000_000_000_000, "tEndUs": 1_700_003_600_000_000,
    "scalars": [], "curves": [],
    "matrices": [{"name": "Q", "rowKeys": es_keys, "colKeys": es_keys,
                  "values": [1.0, 0.5, 0.25, 0.5, 1.0, 0.125, 0.25, 0.125, 1.0], "unit": None},
                 {"name": "q_directed", "rowKeys": es_keys, "colKeys": es_keys,
                  "values": ["NaN", 0.375, -0.25, -0.375, "NaN", 0.0, 0.25, -0.0, "NaN"], "unit": None}],
    "labels": {}, "source": "workbench:pair.es", "producer": "testvectors", "createdAtUs": 1_700_000_100_000_000,
    "pipeline": "es-matrix",
}


def build_result(spec):
    matrices = [m.matrix(x["name"], x["rowKeys"], x["colKeys"], [float(v) for v in x["values"]], unit=x["unit"])
                for x in spec["matrices"]]
    return m.analysis_result(
        spec["method"], spec["methodVersion"], typed(spec["params"]), spec["inputEpisodeIds"], spec["inputSeriesIds"],
        spec["tStartUs"], spec["tEndUs"],
        scalars=[m.scalar(x["name"], x["value"], std_error=x["stdError"], unit=x["unit"], fit_from=x["fitFrom"],
                          fit_to=x["fitTo"]) for x in spec["scalars"]],
        curves=[m.curve(x["name"], x["xName"], x["x"], x["y"], x_unit=x["xUnit"], unit=x["unit"]) for x in spec["curves"]],
        matrices=matrices, labels=spec["labels"], source=spec["source"], producer=spec["producer"],
        created_at_us=spec["createdAtUs"], pipeline=spec["pipeline"])


write("wire-format.json", {
    "fingerprints": {name: f"{m.fingerprint(name):016x}" for name in
                     ["Episode", "Observation", "SeriesKey", "BucketManifest", "SeriesStats", "PatternMatch",
                      "AnalysisResult"]},
    "episodes": [{"spec": spec, "hex": m.encode("Episode", build(spec)).hex()} for spec in (regular_spec, irregular_spec)],
    "observation": {"record": observation, "hex": m.encode("Observation", observation).hex()},
    "analysisResults": [{"spec": spec, "resultId": build_result(spec)["resultId"],
                         "hex": m.encode("AnalysisResult", build_result(spec)).hex()} for spec in (dfa_spec, es_spec)],
})
print("test vectors written to", HERE)
