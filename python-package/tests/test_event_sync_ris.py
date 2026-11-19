import subprocess
import sys

import numpy as np
import pytest

from opentsx.algorithms import RIS, EventSynchronization


def _spiky(rng, n, spikes, amplitude=10.0):
    data = rng.normal(scale=0.1, size=n)
    data[np.asarray(spikes)] += amplitude
    return data


def test_lagged_spikes_are_synchronized_and_leader_detected(rng):
    spikes = 50 + np.cumsum(rng.integers(30, 90, size=14))
    ts1 = _spiky(rng, 1000, spikes)
    ts2 = _spiky(rng, 1000, spikes + 3)

    result = EventSynchronization(tau_max=5).analyze(ts1, ts2)

    assert result["num_events_1"] == len(spikes)
    assert result["num_events_2"] == len(spikes)
    assert result["overall_sync"] > 0.9
    assert result["leader"] == "Series 1"


def test_periodic_spikes_name_no_leader_because_their_shuffled_copies_lag_alike(rng):
    spikes = np.arange(50, 950, 60)
    result = EventSynchronization(tau_max=5).analyze(_spiky(rng, 1000, spikes), _spiky(rng, 1000, spikes + 3))

    assert result["delay"] == 1.0
    assert result["delay_surrogates"] == 1.0
    assert result["leader"] == "None (symmetric)"


def test_independent_spikes_are_not_synchronized(rng):
    ts1 = _spiky(rng, 1000, np.arange(50, 950, 60))
    ts2 = _spiky(rng, 1000, np.arange(80, 950, 60))  # offset by half a period

    result = EventSynchronization(tau_max=5).analyze(ts1, ts2)

    assert result["overall_sync"] < 0.1


def test_ris_on_iid_noise_matches_poisson_expectation(rng):
    n = 50_000
    result = RIS(threshold_percentile=95).analyze(rng.normal(size=n))

    assert result["num_events"] == pytest.approx(0.05 * n, rel=0.02)
    assert result["mean_ri"] == pytest.approx(20.0, rel=0.1)  # 1 / p
    assert result["risk_parameter"] == pytest.approx(np.sqrt(0.95), abs=0.05)  # geometric intervals, p = 0.05


def test_ris_detects_clustering(rng):
    data = rng.normal(size=20_000)
    for start in range(1000, 20_000, 2000):
        data[start:start + 50:5] += 10.0  # bursts of extremes

    result = RIS(threshold_percentile=99.5).analyze(data)

    assert result["risk_parameter"] > 1.2


def _correlated_noise(n, beta, rng):
    f = np.fft.rfftfreq(n)
    f[0] = f[1]
    return np.fft.irfft(np.fft.rfft(rng.normal(size=n)) * f ** (-beta / 2), n)


def test_ris_rejects_nan_and_too_few_events(rng):
    x = rng.normal(size=1000)
    x[3] = np.nan
    with pytest.raises(ValueError, match=r"^time_series: 1 of 1000 values are not finite, the first at index 3 is nan; "):
        RIS().analyze(x)
    with pytest.raises(ValueError, match=r"^time_series: 2 values lie above the threshold "):
        RIS(threshold_percentile=95).analyze(rng.normal(size=30))
    with pytest.raises(ValueError, match=r"^return_intervals: 1 given; expected at least 2 intervals"):
        RIS().risk_parameter(np.array([5]))


def test_risk_parameter_refuses_intervals_with_mean_0():
    with pytest.raises(ValueError, match=r"^return_intervals: mean is 0; expected positive intervals"):
        RIS().risk_parameter(np.array([0, 0]))


def test_stretched_exponential_fit_recovers_gamma_and_tau_0():
    from scipy import stats

    sample = stats.gengamma(a=1 / 0.5, c=0.5, scale=10.0).rvs(size=20_000, random_state=np.random.default_rng(5))
    fit = RIS().stretched_exponential_fit(sample)
    assert fit["gamma"] == pytest.approx(0.5, abs=0.02)
    assert fit["tau_0"] == pytest.approx(10.0, rel=0.15)


def test_stretched_exponential_gamma_follows_the_correlation():
    rng = np.random.default_rng(1)
    ris = RIS(threshold_percentile=95)
    white = ris.stretched_exponential_fit(ris.analyze(rng.normal(size=2 ** 16))["return_intervals"])
    correlated = ris.stretched_exponential_fit(ris.analyze(_correlated_noise(2 ** 16, 0.6, rng))["return_intervals"])
    # whole-number intervals from 1 on: for geometric intervals with p = 0.05 the fit tends to 1.08, not 1
    assert white["gamma"] == pytest.approx(1.08, abs=0.06)
    assert correlated["gamma"] == pytest.approx(0.4, abs=0.1)  # gamma = 1 - beta (Bunde et al. 2005)


def test_stretched_exponential_fit_refuses_what_it_cannot_fit():
    ris = RIS()
    with pytest.raises(ValueError, match=r"^return_intervals: 9 given; expected at least 10 intervals"):
        ris.stretched_exponential_fit(np.arange(1, 10))
    with pytest.raises(ValueError, match=r"^return_intervals: all 12 intervals are 60; "):
        ris.stretched_exponential_fit(np.full(12, 60))
    with pytest.raises(ValueError, match=r"^return_intervals: interval 0 at index 2 is not a positive finite number; "):
        ris.stretched_exponential_fit(np.array([3, 4, 0, 5, 6, 7, 8, 9, 10, 11]))
    with pytest.raises(ValueError, match=r"^return_intervals: the likelihood is largest at gamma 20, a limit "):
        ris.stretched_exponential_fit(np.array([59, 60, 61] * 4))


@pytest.mark.parametrize("bad", [np.inf, np.nan])
def test_stretched_exponential_fit_refuses_an_interval_that_is_not_finite(bad):
    with pytest.raises(
        ValueError, match=rf"^return_intervals: interval {bad:g} at index 0 is not a positive finite number; "
    ):
        RIS().stretched_exponential_fit(np.r_[bad, np.arange(1, 12)])


def test_ris_analyze_reports_no_fit(rng):
    result = RIS().analyze(rng.normal(size=5000))
    assert "gamma" not in result and "tau_0" not in result


def test_es_absolute_method_needs_a_threshold():
    es = EventSynchronization()
    with pytest.raises(ValueError, match=r"^threshold: not set for method 'absolute'; "):
        es.detect_events(np.array([0.0, 2.0, 0.0, 5.0, 0.0]), method="absolute")
    assert es.detect_events(np.array([0.0, 2.0, 0.0, 5.0, 0.0]), threshold=3.0, method="absolute").tolist() == [3]


def test_es_unknown_method_is_named():
    with pytest.raises(ValueError, match=r"^method: 'mean' is unknown; expected 'std', 'percentile' or 'absolute'$"):
        EventSynchronization().detect_events(np.arange(5.0), method="mean")


def test_es_rejects_nan_and_series_without_events(rng):
    a = rng.normal(size=1000)
    b = a.copy()
    b[10] = np.nan
    with pytest.raises(ValueError, match=r"^ts2: 1 of 1000 values are not finite, the first at index 10 is nan; "):
        EventSynchronization().analyze(a, b)
    with pytest.raises(ValueError, match=r"^ts1: no event, no local maximum lies above the threshold; "):
        EventSynchronization().analyze(np.ones(1000), a)


def test_import_does_not_load_scipy():
    code = "import sys, opentsx; print(sorted(m for m in sys.modules if m.split('.')[0] == 'scipy')[:3])"
    done = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True)
    assert done.stdout == "[]\n"


def test_ris_analyze_reports_r_without_a_risk_label(rng):
    result = RIS().analyze(rng.normal(size=5000))
    assert set(result) == {
        "num_events", "threshold", "return_intervals", "mean_ri", "median_ri", "std_ri", "min_ri", "max_ri",
        "risk_parameter", "event_indices",
    }
