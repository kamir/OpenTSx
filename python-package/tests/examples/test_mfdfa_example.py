"""Operator example mfdfa (docs/user-manual/17-operators.md): MF-DFA tells a monofractal from a multifractal series."""
import numpy as np

from chart import ExampleChart

A = 0.75


def binomial_cascade(levels: int, a: float) -> np.ndarray:
    ones = np.array([bin(k).count("1") for k in range(2 ** levels)])
    return a ** ones * (1 - a) ** (levels - ones)


def cascade_h(q: np.ndarray, a: float) -> np.ndarray:
    return (1 - np.log2(a ** q + (1 - a) ** q)) / q


def cascade_spectrum(q: np.ndarray, a: float) -> tuple:
    tau = -np.log2(a ** q + (1 - a) ** q)
    alpha = -(a ** q * np.log(a) + (1 - a) ** q * np.log(1 - a)) / ((a ** q + (1 - a) ** q) * np.log(2))
    return alpha, q * alpha - tau


def test_mfdfa_example():
    rng = np.random.default_rng(0)
    series = {
        "white noise": rng.normal(size=2 ** 14),
        "binomial cascade": binomial_cascade(14, A),
    }

    # usage:begin
    from opentsx import MFDFA

    mfdfa = MFDFA(polynom_order=1)
    results = {name: mfdfa.analyze(x) for name, x in series.items()}
    delta_h = {name: r["delta_h"] for name, r in results.items()}
    delta_alpha = {name: r["delta_alpha"] for name, r in results.items()}
    # usage:end

    q = np.linspace(-10, 10.5, 400)
    theory_h = cascade_h(q, A)
    theory_alpha, theory_f = cascade_spectrum(q, A)
    expected_delta_h = theory_h[0] - theory_h[-1]
    expected_delta_alpha = theory_alpha.max() - theory_alpha.min()
    measured_q = results["binomial cascade"]["q_values"]
    nonzero = measured_q != 0
    measured_gap = np.abs(results["binomial cascade"]["h_q"][nonzero] - cascade_h(measured_q[nonzero], A)).max()

    assert delta_h["white noise"] < 0.15
    assert np.abs(results["white noise"]["h_q"] - 0.5).max() < 0.1
    assert abs(delta_h["binomial cascade"] - expected_delta_h) < 0.05
    assert measured_gap < 0.12
    assert abs(delta_alpha["binomial cascade"] - expected_delta_alpha) < 0.05

    chart = ExampleChart("mfdfa", "MF-DFA: h(q) is flat for white noise and falls with q for a multifractal cascade")
    chart.panel("Input, means of 64 values, scaled to std 1", "sample / 64", "value, cascade shifted up by 10")
    for k, (name, x) in enumerate(series.items()):
        b = x.reshape(-1, 64).mean(axis=1)
        chart.add(name, "line", range(len(b)), (b - b.mean()) / b.std() + 10 * k)
    chart.note(f"delta h expected: 0 and {expected_delta_h:.2f}")
    chart.note("delta h measured: " + " and ".join(f"{v:.2f}" for v in delta_h.values()))
    chart.note(f"delta alpha expected: 0 and {expected_delta_alpha:.2f}")
    chart.note("delta alpha measured: " + " and ".join(f"{v:.2f}" for v in delta_alpha.values()))
    chart.panel("Generalized Hurst exponent", "q", "h(q)")
    for name, r in results.items():
        chart.add(name, "point", r["q_values"], r["h_q"])
    chart.add("cascade theory", "line", q, theory_h)
    chart.panel("Singularity spectrum", "alpha", "f(alpha)")
    for name, r in results.items():
        chart.add(name, "point", r["alpha"], r["f_alpha"])
    chart.add("cascade theory", "line", theory_alpha, theory_f)
    chart.write()
