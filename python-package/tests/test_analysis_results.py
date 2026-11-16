"""AnalysisResult: Python must agree with Java and TypeScript (shared vectors in opentsx-model)."""

import json
import math
import pathlib

import numpy as np
import pytest

from opentsx import model as m
from opentsx.algorithms.dfa import DFA
from opentsx.algorithms.event_sync import EventSynchronization
from opentsx.algorithms.results import dfa_result, event_sync_result

VECTORS = pathlib.Path(__file__).resolve().parents[2] / "opentsx-model" / "src" / "test" / "resources" / "testvectors"


def load(name):
    return json.loads((VECTORS / name).read_text(encoding="utf-8"))


def typed(params):
    out = {}
    for k, v in params.items():
        (kind, value), = v.items()
        out[k] = {"long": int, "double": float, "string": str, "array": lambda a: [float(x) for x in a]}[kind](value)
    return out


def build(spec):
    return m.analysis_result(
        spec["method"], spec["methodVersion"], typed(spec["params"]), spec["inputEpisodeIds"], spec["inputSeriesIds"],
        spec["tStartUs"], spec["tEndUs"],
        scalars=[m.scalar(s["name"], s["value"], std_error=s["stdError"], unit=s["unit"], fit_from=s["fitFrom"],
                          fit_to=s["fitTo"]) for s in spec["scalars"]],
        curves=[m.curve(c["name"], c["xName"], c["x"], c["y"], x_unit=c["xUnit"], unit=c["unit"])
                for c in spec["curves"]],
        matrices=[m.matrix(x["name"], x["rowKeys"], x["colKeys"], [float(v) for v in x["values"]], unit=x["unit"])
                  for x in spec["matrices"]],
        labels=spec["labels"], source=spec["source"], producer=spec["producer"], created_at_us=spec["createdAtUs"],
        pipeline=spec["pipeline"])


def test_result_id_vectors():
    cases = load("result-id.json")
    assert len(cases) == 9
    for v in cases:
        params = typed(v["params"])
        assert m.result_canonical(v["method"], v["methodVersion"], params, v["inputEpisodeIds"]) == v["canonical"]
        assert m.result_id(v["method"], v["methodVersion"], params, v["inputEpisodeIds"]) == v["resultId"]


def test_identity_ignores_param_order_but_not_input_order_or_type():
    ids = [v["resultId"] for v in load("result-id.json")]
    assert ids[0] == ids[1]
    assert ids[2] != ids[3]
    assert ids[2] != ids[4]


def test_analysis_results_encode_to_java_bytes_and_decode_back():
    vectors = load("wire-format.json")
    assert vectors["fingerprints"]["AnalysisResult"] == f"{m.fingerprint('AnalysisResult'):016x}"
    assert len(vectors["analysisResults"]) == 2
    for v in vectors["analysisResults"]:
        record = build(v["spec"])
        assert record["resultId"] == v["resultId"]
        assert m.encode("AnalysisResult", record).hex() == v["hex"]
        name, back = m.decode(bytes.fromhex(v["hex"]))
        assert name == "AnalysisResult"
        assert back["resultId"] == v["resultId"]
        assert back["params"] == record["params"]
        for got, want in zip(back["matrices"], record["matrices"]):
            assert got["rowKeys"] == want["rowKeys"]
            assert all((math.isnan(a) and math.isnan(b)) or a == b for a, b in zip(got["values"], want["values"]))


@pytest.mark.parametrize("params, error", [
    ({"x": float("nan")}, "NaN is not allowed"),
    ({"x": [1.0, float("nan")]}, "NaN is not allowed"),
    ({"x": True}, "boolean parameters are not supported"),
    ({"x": 1 << 63}, "long parameter out of range"),
    ({"": 1}, "parameter names must not be empty"),
])
def test_bad_parameters_are_rejected(params, error):
    with pytest.raises((ValueError, TypeError), match=error):
        m.result_id("dfa", "1.0.0", params, ["e1"])


def test_builders_and_verify_reject_inconsistent_records():
    with pytest.raises(ValueError, match="at least one input episode"):
        m.result_id("dfa", "1.0.0", {}, [])
    with pytest.raises(ValueError, match="x has 2 values, y has 1"):
        m.curve("F", "scale", [1, 2], [1])
    with pytest.raises(ValueError, match="3 values for 2 x 2"):
        m.matrix("Q", ["a", "b"], ["a", "b"], [1, 2, 3])
    record = build(load("wire-format.json")["analysisResults"][0]["spec"])
    with pytest.raises(ValueError, match="does not match"):
        m.verify_analysis_result({**record, "params": {**record["params"], "order": 2}})
    with pytest.raises(ValueError, match="2 inputSeriesIds for 1 inputEpisodeIds"):
        m.verify_analysis_result({**record, "inputSeriesIds": ["a", "b"]})
    with pytest.raises(ValueError, match="must be before tEnd"):
        m.verify_analysis_result({**record, "tEnd": record["tStart"]})


def _episode(values, metric, episode_id):
    key = m.series_key(metric, {"park": "ber-01"}, "m/s")
    return m.regular(key, 1_700_000_000_000_000, 1_000_000, values, episode_id=episode_id,
                     created_at_us=1_700_000_100_000_000, source="test")


def test_dfa_result_holds_alpha_and_fluctuation_function():
    walk = np.cumsum(np.random.default_rng(7).standard_normal(4096))
    episode = _episode(walk, "wind.met.wind_speed", "01HF7Z1Y4X0000000000000100")
    dfa = DFA(polynom_order=1)
    record = dfa_result(dfa, episode, source="test", created_at_us=1)
    expected = dfa.analyze(m.values(episode))
    alpha = next(s for s in record["scalars"] if s["name"] == "alpha")
    assert alpha["value"] == pytest.approx(expected["alpha"], abs=0)
    assert alpha["fitFrom"] == float(min(expected["scales"])) and alpha["fitTo"] == float(max(expected["scales"]))
    assert record["curves"][0]["y"] == [float(f) for f in expected["fluctuations"]]
    assert record["params"]["order"] == 1
    assert record["inputSeriesIds"] == [episode["series"]["seriesId"]]
    assert dfa_result(dfa, episode, source="other", created_at_us=2)["resultId"] == record["resultId"]
    assert m.decode(m.encode("AnalysisResult", record))[1]["resultId"] == record["resultId"]


def test_event_sync_result_is_a_matrix_over_series_ids():
    rng = np.random.default_rng(11)
    base = rng.standard_normal(2000)
    episodes = [_episode(base + 0.1 * rng.standard_normal(2000), f"s{i}", f"01HF7Z1Y4X000000000000020{i}")
                for i in range(3)]
    record = event_sync_result(EventSynchronization(tau_max=5.0), episodes, source="test", created_at_us=1)
    q = np.array(record["matrices"][0]["values"]).reshape(3, 3)
    d = np.array(record["matrices"][1]["values"]).reshape(3, 3)
    keys = [e["series"]["seriesId"] for e in episodes]
    assert record["matrices"][0]["rowKeys"] == keys == record["matrices"][0]["colKeys"]
    assert np.all(np.isnan(np.diag(q))) and np.all(np.isnan(np.diag(d)))
    off = ~np.eye(3, dtype=bool)
    assert np.array_equal(q[off], q.T[off])
    assert np.array_equal(d[off], -d.T[off])
    assert record["params"] == {"tauMax": 5.0, "threshold": "std"}
    with pytest.raises(ValueError, match="at least two episodes"):
        event_sync_result(EventSynchronization(), episodes[:1], source="test")
