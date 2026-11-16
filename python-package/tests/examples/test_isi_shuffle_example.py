"""Operator example surrogate.isi-shuffle (docs/user-manual/17-operators.md): the intervals stay, the coupling goes."""
import numpy as np
import pytest

from chart import ExampleChart


def event_times(rng: np.random.Generator) -> np.ndarray:
    return np.cumsum(10 + rng.geometric(0.025, size=120)).astype(float)


def test_isi_shuffle_example():
    rng = np.random.default_rng(1)
    events_a = event_times(rng)
    events_b = events_a + 3
    events_independent = event_times(rng)

    # usage:begin
    from opentsx import EventSynchronization
    from opentsx.algorithms.event_sync import Mulberry32, isi_shuffle

    rand = Mulberry32(1)
    copies = [isi_shuffle(events_b, rand) for _ in range(10)]

    es = EventSynchronization()
    q_original = es.calculate_synchronization(events_a, events_b)[0]
    q_copies = [es.calculate_synchronization(events_a, c)[0] for c in copies]
    # usage:end

    q_independent = es.calculate_synchronization(events_a, events_independent)[0]
    for c in copies:
        assert c[0] == events_b[0] and c[-1] == events_b[-1]
        assert np.array_equal(np.sort(np.diff(c)), np.sort(np.diff(events_b)))
        assert not np.array_equal(c, events_b)
    assert q_original == pytest.approx(1.0, abs=1e-12)
    assert max(q_copies) < 0.6
    assert np.mean(q_copies) == pytest.approx(q_independent, abs=0.15)
    assert np.mean(q_copies) == pytest.approx(es.surrogate_sync(events_a, events_b, shuffles=10, seed=1), abs=1e-12)

    chart = ExampleChart("surrogate.isi-shuffle", "ISI shuffle: the intervals stay, the coupling to A goes")
    chart.panel("Event times, first 600 ticks, copy 1", "tick", "row: A 2, B 1, B shuffled 0")
    for row, (name, events) in enumerate({"A": events_a, "B": events_b, "B shuffled": copies[0]}.items()):
        shown = events[events < 600]
        chart.add(name, "point", shown, np.full(len(shown), 2.0 - row))
    chart.panel("Intervals between events, sorted, copy 1", "rank", "interval, ticks", log="y")
    chart.add("B", "line", range(len(events_b) - 1), np.sort(np.diff(events_b)))
    chart.add("B shuffled", "point", range(len(events_b) - 1), np.sort(np.diff(copies[0])))
    chart.note(f"kept: first and last event, all {len(events_b) - 1} intervals")
    low, high, mean = min(q_copies), max(q_copies), np.mean(q_copies)
    chart.note(f"Q with A: B {q_original:.2f}, copies {low:.2f} to {high:.2f}, mean {mean:.2f}")
    chart.note(f"expected: an independent train, Q {q_independent:.2f}")
    chart.panel("Q with A: B at copy 0, shuffled copies 1 to 10", "copy", "Q")
    chart.add("B", "point", [0], [q_original])
    chart.add("B shuffled", "point", range(1, 11), q_copies)
    chart.write()
