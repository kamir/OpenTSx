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
        spec["tStartUs"], spec["tEndUs"], input_digests=spec["inputDigests"],
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
    assert len(cases) == 12
    for v in cases:
        params = typed(v["params"])
        args = (v["method"], v["methodVersion"], params, v["inputEpisodeIds"], v["inputDigests"])
        assert m.result_canonical(*args) == v["canonical"]
        assert m.result_id(*args) == v["resultId"]


def test_identity_ignores_param_order_but_not_input_order_or_type():
    ids = [v["resultId"] for v in load("result-id.json")]
    assert ids[0] == ids[1]
    assert ids[2] != ids[3]
    assert ids[2] != ids[4]


def test_identity_binds_the_content_of_the_input_episodes():
    cases = load("result-id.json")
    ids_only, bound, changed = cases[0], cases[9], cases[10]
    assert ids_only["inputEpisodeIds"] == bound["inputEpisodeIds"] == changed["inputEpisodeIds"]
    assert bound["canonical"] == ids_only["canonical"] + "{" + bound["inputDigests"][0] + "}"
    assert len({ids_only["resultId"], bound["resultId"], changed["resultId"]}) == 3
    episodes = load("episode-digest.json")
    assert bound["inputDigests"] == [episodes[0]["contentDigest"]]
    assert changed["inputDigests"] == [episodes[2]["contentDigest"]]


def test_payloads_written_with_v2_1_decode_without_digests_and_keep_their_ids():
    vectors = load("wire-format.json")["analysisResults"]
    assert len(vectors) == 2
    for v in vectors:
        name, back = m.decode(bytes.fromhex(v["hex"]))
        assert name == "AnalysisResult"
        assert back["inputDigests"] == []
        assert back["resultId"] == v["resultId"]
        m.verify_analysis_result(back)


def test_analysis_results_encode_to_java_bytes_and_decode_back():
    vectors = load("analysis-result-wire.json")
    assert vectors["fingerprint"] == f"{m.fingerprint('AnalysisResult'):016x}"
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


@pytest.mark.parametrize("digests, error", [
    (["a" * 64], "1 inputDigests for 2 inputEpisodeIds"),
    (["a" * 64, "A" * 64], "input digest 1 is not 64 lower-case hex characters"),
    (["a" * 63, "a" * 64], "input digest 0 is not 64 lower-case hex characters"),
])
def test_bad_digests_are_rejected(digests, error):
    with pytest.raises(ValueError, match=error):
        m.result_id("dfa", "1.0.0", {}, ["e1", "e2"], digests)


def test_builders_and_verify_reject_inconsistent_records():
    with pytest.raises(ValueError, match="at least one input episode"):
        m.result_id("dfa", "1.0.0", {}, [])
    with pytest.raises(ValueError, match="x has 2 values, y has 1"):
        m.curve("F", "scale", [1, 2], [1])
    with pytest.raises(ValueError, match="3 values for 2 x 2"):
        m.matrix("Q", ["a", "b"], ["a", "b"], [1, 2, 3])
    record = build(load("analysis-result-wire.json")["analysisResults"][0]["spec"])
    with pytest.raises(ValueError, match="does not match"):
        m.verify_analysis_result({**record, "params": {**record["params"], "order": 2}})
    with pytest.raises(ValueError, match="2 inputSeriesIds for 1 inputEpisodeIds"):
        m.verify_analysis_result({**record, "inputSeriesIds": ["a", "b"]})
    with pytest.raises(ValueError, match="must be before tEnd"):
        m.verify_analysis_result({**record, "tEnd": record["tStart"]})
    with pytest.raises(ValueError, match="does not match"):
        m.verify_analysis_result({**record, "inputDigests": [load("episode-digest.json")[2]["contentDigest"]]})
    with pytest.raises(ValueError, match="does not match"):
        m.verify_analysis_result({**record, "inputDigests": []})
    spec = load("analysis-result-wire.json")["analysisResults"][0]["spec"]
    with pytest.raises(ValueError, match="0 inputDigests for 1 inputEpisodeIds"):
        build({**spec, "inputDigests": []})


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
    assert record["inputDigests"] == [m.content_digest(episode)]
    assert dfa_result(dfa, episode, source="other", created_at_us=2)["resultId"] == record["resultId"]
    assert m.decode(m.encode("AnalysisResult", record))[1]["resultId"] == record["resultId"]


def test_dfa_result_id_changes_with_the_values_of_the_same_episode_id():
    walk = np.cumsum(np.random.default_rng(7).standard_normal(4096))
    first = _episode(walk, "wind.met.wind_speed", "01HF7Z1Y4X0000000000000100")
    revised = dict(_episode(walk + 1e-9, "wind.met.wind_speed", "01HF7Z1Y4X0000000000000100"), revision=1)
    dfa = DFA(polynom_order=1)
    assert dfa_result(dfa, first, source="test")["resultId"] != dfa_result(dfa, revised, source="test")["resultId"]


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
    assert record["inputDigests"] == [m.content_digest(e) for e in episodes]
    with pytest.raises(ValueError, match="at least two episodes"):
        event_sync_result(EventSynchronization(), episodes[:1], source="test")
