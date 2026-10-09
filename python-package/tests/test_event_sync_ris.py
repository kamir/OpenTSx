import numpy as np
import pytest

from opentsx.algorithms import RIS, EventSynchronization


def _spiky(rng, n, spikes, amplitude=10.0):
    data = rng.normal(scale=0.1, size=n)
    data[np.asarray(spikes)] += amplitude
    return data


def test_lagged_spikes_are_synchronized_and_leader_detected(rng):
    spikes = np.arange(50, 950, 60)
    ts1 = _spiky(rng, 1000, spikes)
    ts2 = _spiky(rng, 1000, spikes + 3)

    result = EventSynchronization(tau_max=5).analyze(ts1, ts2)

    assert result["num_events_1"] == len(spikes)
    assert result["num_events_2"] == len(spikes)
    assert result["overall_sync"] > 0.9
    assert result["leader"] == "Series 1"


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
    assert result["risk_interpretation"].startswith("Moderate")  # R ~ 1 for Poisson


def test_ris_detects_clustering(rng):
    data = rng.normal(size=20_000)
    for start in range(1000, 20_000, 2000):
        data[start:start + 50:5] += 10.0  # bursts of extremes

    result = RIS(threshold_percentile=99.5).analyze(data)

    assert result["risk_parameter"] > 1.2
