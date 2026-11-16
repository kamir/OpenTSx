import json
from pathlib import Path, PurePosixPath

import numpy as np
import pytest

from opentsx import DFA


LIBRARY_MANIFEST = Path(__file__).resolve().parents[2] / "docs/learning/library-operators.v1.json"


def execute_starter(lesson_id):
    lessons = json.loads(LIBRARY_MANIFEST.read_text())["lessons"]
    matches = [lesson for lesson in lessons if lesson["id"] == lesson_id]
    assert len(matches) == 1
    starter = matches[0]["starter"]
    assert starter["language"] == "python"
    namespace = {}
    exec(compile(starter["code"], f"{LIBRARY_MANIFEST}#{lesson_id}", "exec"), namespace)
    return namespace


def test_learning_dfa_preserves_scales_and_rescales_fluctuations(monkeypatch):
    analyze = DFA.analyze
    checked_inputs = []

    def preserving_input(self, values, *args, **kwargs):
        original = values.copy()
        result = analyze(self, values, *args, **kwargs)
        np.testing.assert_array_equal(values, original)
        checked_inputs.append(original)
        return result

    monkeypatch.setattr(DFA, "analyze", preserving_input)
    starter = execute_starter("library.dfa")
    result = starter["result"]
    transformed = starter["changed"]

    assert len(checked_inputs) == 2
    np.testing.assert_array_equal(result["scales"], [8, 16, 32, 64])
    assert np.all(np.isfinite(result["fluctuations"]))
    assert np.all(result["fluctuations"] > 0)
    np.testing.assert_allclose(
        transformed["fluctuations"], 3 * result["fluctuations"], rtol=1e-12, atol=1e-12
    )
    assert transformed["alpha"] == pytest.approx(result["alpha"], abs=1e-12)


def test_learning_mfdfa_q2_fluctuations_match_dfa():
    starter = execute_starter("library.mfdfa")
    result = starter["result"]
    _, dfa_fluctuations = DFA(polynom_order=1).calculate(
        starter["values"], scales=starter["scales"]
    )

    np.testing.assert_array_equal(result["q_values"], [-2.0, 0.0, 2.0])
    np.testing.assert_array_equal(result["scales"], [8, 16, 32, 64])
    assert result["Fq"].shape == (3, 4)
    assert np.all(np.isfinite(result["Fq"]))
    assert starter["q2"] == 2
    np.testing.assert_allclose(starter["fluctuations_q2"], dfa_fluctuations, rtol=1e-12, atol=1e-12)
    assert result["tau_q"][1] == -1.0


def test_learning_event_sync_exposes_direction_and_strict_cap():
    starter = execute_starter("library.event-synchronization")
    result = starter["result"]
    capped = starter["capped"]

    np.testing.assert_array_equal(result["events_1"], [2, 8, 14, 20])
    np.testing.assert_array_equal(result["events_2"], [3, 9, 15, 21])
    assert result["overall_sync"] == 1.0
    assert result["sync_1_to_2"] == 1.0
    assert result["sync_2_to_1"] == 0.0
    assert result["leader"] == "Series 1"
    assert capped["overall_sync"] == 0.0


def test_learning_ris_reports_exact_intervals_and_empirical_survival():
    starter = execute_starter("library.ris")
    result = starter["result"]
    intervals = starter["intervals"]
    survival = starter["survival"]

    np.testing.assert_array_equal(result["event_indices"], [1, 3, 7, 13])
    np.testing.assert_array_equal(result["return_intervals"], [2, 4, 6])
    assert result["threshold"] == 5
    assert result["mean_ri"] == 4
    assert result["median_ri"] == 4
    assert result["risk_parameter"] == pytest.approx(0.408248290463863)
    assert result["gamma"] == result["tau_0"] == 0.0
    np.testing.assert_array_equal(intervals, [2, 4, 6])
    np.testing.assert_allclose(survival, [1, 2 / 3, 1 / 3], rtol=1e-12, atol=1e-12)


def test_learning_covers_every_exported_python_algorithm(tmp_path):
    import opentsx.algorithms as algorithms

    def check_coverage(path):
        lessons = json.loads(path.read_text())["lessons"]
        covered = {
            lesson["operator"].split(".", 1)[0]
            for lesson in lessons
            if any(
                PurePosixPath(source.split("#", 1)[0]).parent
                == PurePosixPath("python-package/opentsx/algorithms")
                and PurePosixPath(source.split("#", 1)[0]).suffix == ".py"
                for source in lesson["references"]["source"]
            )
        }
        assert covered == set(algorithms.__all__)

    manifest = LIBRARY_MANIFEST
    check_coverage(manifest)
    document = json.loads(manifest.read_text())
    exported = set(algorithms.__all__)
    assert exported
    missing_class = sorted(exported)[0]
    missing = dict(document)
    missing["lessons"] = [
        lesson
        for lesson in document["lessons"]
        if lesson["operator"].split(".", 1)[0] != missing_class
    ]
    fixture = tmp_path / "library-lessons.json"
    fixture.write_text(json.dumps(missing))
    with pytest.raises(AssertionError):
        check_coverage(fixture)
    unknown = json.loads(manifest.read_text())
    extra = next(
        lesson.copy()
        for lesson in unknown["lessons"]
        if lesson["operator"].split(".", 1)[0] in exported
    )
    extra["id"] = "library.unexported"
    extra["operator"] = "UnexportedLearningAlgorithm.analyze"
    unknown["lessons"].append(extra)
    fixture.write_text(json.dumps(unknown))
    with pytest.raises(AssertionError):
        check_coverage(fixture)
