import numpy as np
import pytest

from opentsx import DFA, MFDFA, TimeSeriesObject


@pytest.mark.parametrize(
    "make_series, expected_alpha",
    [
        (lambda rng: rng.normal(size=10_000), 0.5),              # white noise
        (lambda rng: np.cumsum(rng.normal(size=10_000)), 1.5),   # random walk (Brownian motion)
    ],
    ids=["white-noise", "random-walk"],
)
def test_dfa_alpha_matches_theory(rng, make_series, expected_alpha):
    result = DFA(polynom_order=1).analyze(TimeSeriesObject(data=make_series(rng)))
    assert result["alpha"] == pytest.approx(expected_alpha, abs=0.1)
    assert result["r_squared"] > 0.95


def test_dfa_is_shift_and_scale_invariant(rng):
    data = rng.normal(size=5000)
    a1 = DFA().analyze(TimeSeriesObject(data=data))["alpha"]
    a2 = DFA().analyze(TimeSeriesObject(data=3.0 * data + 100.0))["alpha"]
    assert a1 == pytest.approx(a2, abs=1e-9)


def test_mfdfa_h2_agrees_with_dfa_for_white_noise(rng):
    ts = TimeSeriesObject(data=rng.normal(size=10_000))
    result = MFDFA(polynom_order=1).analyze(ts)
    assert result["h_2"] == pytest.approx(0.5, abs=0.1)


def test_mfdfa_white_noise_is_monofractal(rng):
    result = MFDFA(polynom_order=1).analyze(TimeSeriesObject(data=rng.normal(size=10_000)))
    assert len(result["h_q"]) == len(result["q_values"])
    assert len(result["tau_q"]) == len(result["q_values"])
    assert result["delta_h"] < 0.2
