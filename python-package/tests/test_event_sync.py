"""Tests for EventSynchronization (Quiroga, Kreuz & Grassberger 2002)."""

import numpy as np
import pytest

from opentsx import EventSynchronization


def poisson_events(rng, n, rate):
    """Event indices of a Bernoulli/Poisson process on 0..n-1."""
    return np.flatnonzero(rng.random(n) < rate).astype(np.int32)


def test_identical_event_series_give_full_sync():
    events = np.array([3, 10, 18, 25, 40, 41, 60], dtype=np.int32)
    Q, q_xy, q_yx = EventSynchronization().calculate_synchronization(events, events)
    assert Q == pytest.approx(1.0)
    assert q_xy == pytest.approx(q_yx)


def test_identical_time_series_give_full_sync():
    rng = np.random.default_rng(0)
    a = rng.standard_normal(2000)
    r = EventSynchronization().analyze(a, a.copy())
    assert r["overall_sync"] == pytest.approx(1.0)
    assert r["delay"] == pytest.approx(0.0)
    assert r["leader"] == "None (symmetric)"


def test_independent_poisson_events_give_small_sync():
    rng = np.random.default_rng(1)
    es = EventSynchronization()
    values = [
        es.calculate_synchronization(
            poisson_events(rng, 5000, 0.02), poisson_events(rng, 5000, 0.02)
        )[0]
        for _ in range(20)
    ]
    assert np.mean(values) < 0.3


@pytest.mark.parametrize("shift", [1, 2, 3, 5])
def test_shifted_copy_identifies_leader(shift):
    rng = np.random.default_rng(3)
    a = rng.standard_normal(3000)
    b = np.roll(a, shift)  # b lags a
    r = EventSynchronization().analyze(a, b)
    assert r["leader"] == "Series 1"
    assert r["delay"] > 0.5
    assert 0.0 <= r["overall_sync"] <= 1.0

    r_rev = EventSynchronization().analyze(b, a)
    assert r_rev["leader"] == "Series 2"
    assert r_rev["delay"] == pytest.approx(-r["delay"])


def test_issue_reproduction_is_bounded():
    rng = np.random.default_rng(2)
    a = rng.standard_normal(2000)
    b = np.roll(a, 3) + 0.3 * rng.standard_normal(2000)
    r = EventSynchronization().analyze(a, b)
    assert 0.0 <= r["overall_sync"] <= 1.0
    assert r["leader"] == "Series 1"


@pytest.mark.parametrize("tau_max", [None, 1.0, 5.0, 1e9])
def test_bounds_hold_on_random_inputs(tau_max):
    rng = np.random.default_rng(42)
    es = EventSynchronization(tau_max=tau_max)
    for _ in range(300):
        n = int(rng.integers(5, 400))
        x = poisson_events(rng, n, rng.uniform(0.01, 0.9))
        y = poisson_events(rng, n, rng.uniform(0.01, 0.9))
        Q, q_xy, q_yx = es.calculate_synchronization(x, y)
        assert 0.0 <= Q <= 1.0 + 1e-12
        assert -1.0 - 1e-12 <= q_xy - q_yx <= 1.0 + 1e-12
        assert q_xy >= 0.0 and q_yx >= 0.0


def test_bounds_hold_on_random_time_series():
    rng = np.random.default_rng(7)
    es = EventSynchronization()
    for _ in range(50):
        a = rng.standard_normal(1000)
        b = np.roll(a, int(rng.integers(-10, 10))) + rng.uniform(0, 2) * rng.standard_normal(1000)
        r = es.analyze(a, b)
        assert 0.0 <= r["overall_sync"] <= 1.0
        assert -1.0 <= r["delay"] <= 1.0


def test_tau_max_cap_restricts_matching():
    x = np.array([0, 100, 200, 300], dtype=np.int32)
    y = x + 10  # local window (half of 100) would accept the 10-step lag
    es = EventSynchronization()
    assert es.calculate_synchronization(x, y)[0] == pytest.approx(1.0)
    assert es.calculate_synchronization(x, y, tau_max=5)[0] == pytest.approx(0.0)
    assert EventSynchronization(tau_max=5).calculate_synchronization(x, y)[0] == 0.0


def test_empty_event_series():
    es = EventSynchronization()
    empty = np.array([], dtype=np.int32)
    assert es.calculate_synchronization(empty, np.array([1, 2])) == (0.0, 0.0, 0.0)


def test_result_keys_are_stable():
    rng = np.random.default_rng(5)
    r = EventSynchronization().analyze(rng.standard_normal(500), rng.standard_normal(500))
    for key in (
        "overall_sync", "sync_1_to_2", "sync_2_to_1", "num_events_1", "num_events_2",
        "events_1", "events_2", "leader", "lead_lag_strength", "tau_max",
    ):
        assert key in r
