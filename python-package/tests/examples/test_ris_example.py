"""Operator example ris (docs/user-manual/17-operators.md): long-term correlations cluster the extreme events."""
import numpy as np
import pytest

from chart import ExampleChart


def correlated_noise(n: int, beta: float, rng: np.random.Generator) -> np.ndarray:
    f = np.fft.rfftfreq(n)
    f[0] = f[1]
    return np.fft.irfft(np.fft.rfft(rng.normal(size=n)) * f ** (-beta / 2), n)


def test_ris_example():
    rng = np.random.default_rng(1)
    series = {
        "uncorrelated noise": rng.normal(size=2 ** 16),
        "1/f^0.6 noise": correlated_noise(2 ** 16, 0.6, rng),
    }

    # usage:begin
    from opentsx import RIS

    ris = RIS(threshold_percentile=95)
    results = {name: ris.analyze(x) for name, x in series.items()}
    mean_interval = {name: r["mean_ri"] for name, r in results.items()}
    risk = {name: r["risk_parameter"] for name, r in results.items()}
    # usage:end

    assert mean_interval["uncorrelated noise"] == pytest.approx(20.0, rel=0.01)
    assert mean_interval["1/f^0.6 noise"] == pytest.approx(20.0, rel=0.01)
    assert risk["uncorrelated noise"] == pytest.approx(np.sqrt(0.95), abs=0.04)
    assert risk["1/f^0.6 noise"] > 1.5

    chart = ExampleChart("ris", "RIS: correlated noise clusters its extremes, yet the mean return interval stays 20")
    for name, r in results.items():
        chart.panel(f"{name}: intervals in event order", "event number", "return interval r", log="y")
        chart.add(name, "line", range(200), r["return_intervals"][:200])
    chart.panel("Share of intervals shorter than r", "r", "P(interval < r)", log="x")
    for name, r in results.items():
        tau, survival = ris.survival_function(r["return_intervals"])
        chart.add(name, "line", tau, 1 - survival)
    tau = np.array([1, 2, 3, 5, 7, 10, 15, 20, 30, 50, 70, 100, 150, 200])
    chart.add("expected without correlation", "point", tau, 1 - 0.95 ** (tau - 1))
    chart.note("mean r: " + " and ".join(f"{v:.1f}" for v in mean_interval.values()) + ", expected 20")
    chart.note("R measured: " + " and ".join(f"{v:.2f}" for v in risk.values()))
    chart.note(f"R expected: {np.sqrt(0.95):.2f} and above 1")
    chart.write()
