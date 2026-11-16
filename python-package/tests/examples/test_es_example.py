"""Operator example es (docs/user-manual/17-operators.md): Q rises with the share of events B repeats from A."""
import numpy as np
import pytest

from chart import ExampleChart

N = 6000
DELAY = 3


def event_times(rng: np.random.Generator) -> np.ndarray:
    t = np.cumsum(10 + rng.geometric(0.025, size=N // 10))
    return t[t < N - 10]


def spikes(rng: np.random.Generator, events: np.ndarray) -> np.ndarray:
    x = rng.normal(scale=0.1, size=N)
    x[events] += 10.0
    return x


def test_es_example():
    rng = np.random.default_rng(2)
    events_a = event_times(rng)
    events_c = event_times(rng)
    a = spikes(rng, events_a)
    pairs = {}
    for share in np.round(np.linspace(0, 1, 11), 1):
        copied = events_a[rng.random(len(events_a)) < share] + DELAY
        own = events_c[rng.random(len(events_c)) >= share]
        pairs[share] = (a, spikes(rng, np.unique(np.concatenate([copied, own]))))

    # usage:begin
    from opentsx import EventSynchronization

    es = EventSynchronization()
    results = {}
    for share, (x, y) in pairs.items():
        r = es.analyze(x, y)
        q_rand = es.surrogate_sync(r["events_1"], r["events_2"], shuffles=10, seed=1)
        results[share] = {"Q": r["overall_sync"], "q_rand": q_rand, "leader": r["leader"]}
    # usage:end

    copy, independent = results[1.0], results[0.0]
    assert copy["Q"] == pytest.approx(1.0, abs=1e-12)
    assert copy["leader"] == "Series 1"
    assert copy["q_rand"] < 0.5
    assert independent["Q"] == pytest.approx(independent["q_rand"], abs=0.06)

    chart = ExampleChart("es", "ES: Q grows with shared events, q_rand stays at chance")
    chart.panel("Share 1: B repeats A 3 ticks later", "tick", "value, A shifted up by 12")
    chart.add("A", "line", range(400), a[:400] + 12)
    chart.add("B", "line", range(400), pairs[1.0][1][:400])
    chart.panel("Q against the chance level q_rand", "share of B's events copied from A", "Q")
    chart.add("Q", "line", list(results), [r["Q"] for r in results.values()])
    chart.add("q_rand", "line", list(results), [r["q_rand"] for r in results.values()])
    chart.note("expected: Q 1 at share 1, Q = q_rand at share 0")
    chart.note(f"share 1: Q {copy['Q']:.2f}, q_rand {copy['q_rand']:.2f}")
    chart.note(f"share 0: Q {independent['Q']:.2f}, q_rand {independent['q_rand']:.2f}")
    chart.note(f"leader: {copy['leader']} at 1, {independent['leader']} at 0")
    chart.write()
