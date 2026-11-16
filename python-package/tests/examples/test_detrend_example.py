"""Operator example detrend (docs/user-manual/17-operators.md): detrend of order 2 removes a quadratic trend."""
import numpy as np
import pytest

from chart import ExampleChart


def test_detrend_example():
    rng = np.random.default_rng(3)
    t = np.arange(500)
    u = t / 499
    trend = 5 - 10 * u + 30 * u ** 2
    noise = rng.normal(size=500)
    values = trend + noise

    # usage:begin
    from opentsx import TimeSeriesObject

    ts = TimeSeriesObject(data=values, timestamps=t * 60.0, label="temperature")
    linear = ts.detrend(order=1)
    quadratic = ts.detrend(order=2)
    # usage:end

    bow_std = np.sqrt(30 ** 2 / 180 + 1)
    assert quadratic.values.std() == pytest.approx(noise.std(), abs=0.01)
    assert np.abs(values - quadratic.values - trend).max() < 0.35
    assert linear.values.std() == pytest.approx(bow_std, abs=0.15)
    assert np.array_equal(quadratic.timestamps, ts.timestamps)

    chart = ExampleChart("detrend", "detrend: order 2 removes a quadratic trend, order 1 leaves a bow")
    chart.panel("Input: quadratic trend plus noise of std 1", "minute", "value")
    chart.add("input", "line", t, values)
    chart.add("planted trend", "line", t, trend)
    chart.note(f"residual std, order 1: {linear.values.std():.2f}, expected {bow_std:.2f}")
    chart.note(f"residual std, order 2: {quadratic.values.std():.2f}, expected {noise.std():.2f}")
    chart.panel("detrend(order=1)", "minute", "residual")
    chart.add("order 1 residual", "line", t, linear.values)
    chart.panel("detrend(order=2)", "minute", "residual")
    chart.add("order 2 residual", "line", t, quadratic.values)
    chart.write()
