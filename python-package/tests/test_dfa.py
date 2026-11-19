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


def _cascade(levels, a):
    ones = np.array([bin(k).count("1") for k in range(2 ** levels)])
    return a ** ones * (1 - a) ** (levels - ones)


@pytest.mark.parametrize("cls", [DFA, MFDFA])
def test_nan_in_the_series_raises(cls, rng):
    data = rng.normal(size=1000)
    data[500] = np.nan
    with pytest.raises(ValueError, match=r"^time_series: 1 of 1000 values are not finite, the first at index 500 is nan; "):
        cls().analyze(data)


@pytest.mark.parametrize("cls", [DFA, MFDFA])
def test_too_short_series_raises(cls, rng):
    with pytest.raises(
        ValueError,
        match=r"^time_series: 20 values give max_scale 5, not above min_scale 10; expected at least 44 values",
    ):
        cls().analyze(rng.normal(size=20))


@pytest.mark.parametrize("cls", [DFA, MFDFA])
def test_constant_series_raises(cls):
    with pytest.raises(ValueError, match=r"^time_series: all 1000 values are 1.0; expected a series that varies"):
        cls().analyze(np.ones(1000))


@pytest.mark.parametrize("cls", [DFA, MFDFA])
def test_zero_fluctuation_at_a_window_size_raises(cls):
    data = np.zeros(1000)
    data[-2:] = [1.0, -1.0]  # only the dropped rest of n / 11 varies
    with pytest.raises(ValueError, match=r"^time_series: every window of size 11 is fitted exactly by the trend"):
        cls().analyze(data)


@pytest.mark.parametrize("cls", [DFA, MFDFA])
def test_polynomial_series_raises(cls):
    with pytest.raises(ValueError, match=r"^time_series: every window of size 10 is fitted exactly"):
        cls(polynom_order=2).analyze(np.arange(1000.0))


def test_mfdfa_drops_windows_fitted_exactly_up_to_rounding():
    x = (np.random.default_rng(3).random(1000) < 0.02).astype(float)
    mfdfa = MFDFA()
    h = mfdfa.generalized_hurst_exponent(*mfdfa.calculate(x)[::2])
    h_scaled = mfdfa.generalized_hurst_exponent(*mfdfa.calculate(3 * x + 100)[::2])
    assert h == pytest.approx(h_scaled, abs=1e-9)
    assert h[0] < 1


def test_scale_arguments_are_checked(rng):
    x = rng.normal(size=1000)
    with pytest.raises(ValueError, match=r"^min_scale: 2 is below 3; "):
        DFA().analyze(x, min_scale=2)
    with pytest.raises(ValueError, match=r"^max_scale: 2000 is outside 11 to 1000; "):
        DFA().analyze(x, max_scale=2000)
    with pytest.raises(ValueError, match=r"^num_scales: 1 is below 2; "):
        MFDFA().analyze(x, num_scales=1)
    with pytest.raises(ValueError, match=r"^scales: window size 1001 is outside 3 to 1000; "):
        DFA().analyze(x, scales=[10, 1001])
    with pytest.raises(ValueError, match=r"^scales: 1 distinct window size; "):
        MFDFA().analyze(x, scales=[10, 10])


def test_mfdfa_h_2_is_h_of_q_2():
    result = MFDFA(polynom_order=1).analyze(_cascade(14, 0.75))
    q = list(result["q_values"])
    assert result["h_2"] == result["h_q"][q.index(2.0)]
    assert result["h_2"] == pytest.approx((1 - np.log2(0.75 ** 2 + 0.25 ** 2)) / 2, abs=0.12)


def test_mfdfa_q_range_must_hold_2():
    with pytest.raises(ValueError, match=r"^q_range: no value is 2; "):
        MFDFA(q_range=np.array([-3.0, -1.0, 1.0, 3.0]))


def test_mfdfa_q_range_holds_2_up_to_rounding(rng):
    q = np.arange(-5, 5.1, 0.1)
    assert q[70] != 2.0
    result = MFDFA(q_range=q).analyze(rng.normal(size=1000), surrogates=1)
    assert result["h_2"] == result["h_q"][70]


def _power_law_noise(n, beta, seed):
    f = np.fft.rfftfreq(n)
    f[0] = f[1]
    white = np.random.default_rng(seed).standard_normal(n)
    return np.fft.irfft(np.fft.rfft(white) * f ** (-beta / 2), n)


def test_mfdfa_white_noise_with_a_wide_h_q_is_not_called_multifractal():
    x = np.random.default_rng(1).standard_normal(8192)
    result = MFDFA(polynom_order=1).analyze(x)
    assert result["delta_h"] > 0.1
    assert result["delta_h"] < result["delta_h_surrogates"]
    assert result["is_multifractal"] is False


@pytest.mark.parametrize(
    "x",
    [np.cumsum(np.random.default_rng(1).standard_normal(8192)), _power_law_noise(8192, 0.9, 1)],
    ids=["random-walk", "1/f^0.9-noise"],
)
def test_mfdfa_correlated_gaussian_series_is_not_called_multifractal(x):
    result = MFDFA(polynom_order=1).analyze(x)
    assert result["is_multifractal"] is False
    assert result["delta_h"] < result["delta_h_surrogates"]


def test_mfdfa_heavy_tailed_values_are_called_multifractal():
    result = MFDFA(polynom_order=1).analyze(np.random.default_rng(1).standard_t(1.5, 8192))
    assert result["is_multifractal"] is True
    assert result["delta_h"] > result["delta_h_surrogates"]


def test_mfdfa_cascade_is_more_multifractal_than_its_surrogates():
    result = MFDFA(polynom_order=1).analyze(_cascade(14, 0.75))
    assert result["delta_h"] > result["delta_h_surrogates"]
    assert result["is_multifractal"] is True


def test_mfdfa_surrogates_must_be_positive(rng):
    with pytest.raises(ValueError, match=r"^surrogates: 0 is below 1; "):
        MFDFA().analyze(rng.normal(size=1000), surrogates=0)


def test_mfdfa_surrogates_follow_the_seed():
    x = _power_law_noise(4096, 0.9, 2)
    mfdfa = MFDFA()
    first = mfdfa.analyze(x, surrogates=3, seed=5)["delta_h_surrogates"]
    assert first == mfdfa.analyze(x, surrogates=3, seed=5)["delta_h_surrogates"]
    assert first != mfdfa.analyze(x, surrogates=3, seed=6)["delta_h_surrogates"]


def test_fit_needs_two_positive_fluctuations():
    dfa = DFA()
    with pytest.raises(ValueError, match=r"^fluctuations: F\(20\) is 0.0; expected positive values"):
        dfa.fit_scaling_exponent(np.array([10, 20, 40]), np.array([1.0, 0.0, 3.0]))
    with pytest.raises(ValueError, match=r"^fit_range: \(15, 30\) keeps 1 window size; expected at least 2"):
        dfa.fit_scaling_exponent(np.array([10, 20, 40]), np.array([1.0, 2.0, 3.0]), fit_range=(15, 30))


def test_mfdfa_hurst_exponent_needs_positive_fluctuations():
    with pytest.raises(ValueError, match=r"^Fq: F_q\(s\) is 0.0 at q -10.0 and window size 10; expected positive values"):
        MFDFA().generalized_hurst_exponent(np.array([10, 20]), np.zeros((42, 2)))


@pytest.mark.parametrize(
    "alpha, band",
    [
        (0.44, "Anti-correlated (mean-reverting)"),
        (0.45, "Uncorrelated (white noise)"),
        (0.47, "Uncorrelated (white noise)"),
        (0.52, "Uncorrelated (white noise)"),
        (0.55, "Uncorrelated (white noise)"),
        (0.56, "Correlated (persistent, trending)"),
        (0.95, "1/f noise (pink noise)"),
        (1.05, "1/f noise (pink noise)"),
        (1.2, "Non-stationary (fractional Brownian motion)"),
        (1.5, "Non-stationary (fractional Brownian motion)"),
    ],
)
def test_interpretation_bands_are_symmetric_around_0_5_and_1(alpha, band):
    assert DFA._interpret_alpha(alpha) == band


def test_interpretation_refuses_an_alpha_that_is_not_finite():
    with pytest.raises(ValueError) as e:
        DFA._interpret_alpha(float("nan"))
    assert str(e.value) == "alpha: nan is not finite; expected a finite scaling exponent"
