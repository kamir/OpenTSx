"""Operator example dfa (docs/user-manual/17-operators.md): DFA tells white noise, correlated noise and a random walk apart."""
import numpy as np
import pytest

from chart import ExampleChart


def correlated_noise(n: int, beta: float, rng: np.random.Generator) -> np.ndarray:
    """Noise with the power spectrum 1/f^beta (Fourier filtering); its DFA exponent is (1 + beta) / 2."""
    f = np.fft.rfftfreq(n)
    f[0] = f[1]
    return np.fft.irfft(np.fft.rfft(rng.normal(size=n)) * f ** (-beta / 2), n)


def test_dfa_example():
    rng = np.random.default_rng(7)
    series = {
        "white noise": rng.normal(size=8192),
        "1/f^0.6 noise": correlated_noise(8192, 0.6, rng),
        "random walk": np.cumsum(rng.normal(size=8192)),
    }

    # usage:begin
    from opentsx import DFA

    dfa = DFA(polynom_order=1)
    results = {name: dfa.analyze(x) for name, x in series.items()}
    alpha = {name: r["alpha"] for name, r in results.items()}
    # usage:end

    assert alpha["white noise"] == pytest.approx(0.5, abs=0.05)
    assert alpha["1/f^0.6 noise"] == pytest.approx(0.8, abs=0.07)
    assert alpha["random walk"] == pytest.approx(1.5, abs=0.1)

    chart = ExampleChart("dfa", "DFA: the slope of F(s) gives the correlation exponent alpha")
    chart.panel("Input, first 300 samples, scaled to std 1", "sample", "value, shifted by 6 per series")
    for k, (name, x) in enumerate(series.items()):
        w = x[:300]
        chart.add(name, "line", range(300), (w - w.mean()) / w.std() - 6 * k)
    chart.panel("Fluctuation function", "window size s", "F(s)", log="xy")
    for name, r in results.items():
        chart.add(name, "point", r["scales"], r["fluctuations"])
    chart.note("expected alpha: 0.5, 0.8, 1.5")
    chart.note("measured: " + ", ".join(f"{a:.2f}" for a in alpha.values()))
    chart.write()
