"""Operator example stats.summary (docs/user-manual/17-operators.md): merged summaries of two halves equal the whole."""
import numpy as np
import pytest

from chart import ExampleChart


def test_summary_example():
    rng = np.random.default_rng(4)
    first = rng.normal(0, 1, 200)
    first[[40, 41, 120]] = np.nan
    second = rng.normal(5, 2, 200)

    # usage:begin
    from opentsx.model import merge_summaries, summarize, variance

    merged = merge_summaries(summarize(first), summarize(second))
    std = variance(merged) ** 0.5
    # usage:end

    whole = summarize(np.concatenate([first, second]))
    assert (merged["validCount"], merged["nanCount"]) == (whole["validCount"], whole["nanCount"]) == (397, 3)
    assert (merged["min"], merged["max"]) == (whole["min"], whole["max"])
    assert merged["mean"] == pytest.approx(whole["mean"], rel=1e-12)
    assert merged["m2"] == pytest.approx(whole["m2"], rel=1e-12)
    assert std == pytest.approx(np.nanstd(np.concatenate([first, second]), ddof=1), rel=1e-12)

    a = [whole["min"], whole["mean"], variance(whole) ** 0.5, whole["max"]]
    b = [merged["min"], merged["mean"], std, merged["max"]]
    worst = max(abs(x - y) / abs(x) for x, y in zip(a, b))

    chart = ExampleChart("stats.summary", "Summaries: the merged halves equal the whole")
    chart.panel("Input: two halves of 200 values, 3 of them NaN", "sample", "value")
    chart.add("value", "line", range(400), np.concatenate([first, second]))
    chart.panel("Merged summary against the summary of the whole", "summary of the whole", "merged summary of the halves")
    chart.add("y = x", "line", [a[0] - 1, a[3] + 1], [a[0] - 1, a[3] + 1])
    chart.add("min / mean / std / max", "point", a, b)
    chart.note("expected: merged = whole")
    chart.note(f"measured: counts {merged['validCount']}, NaN {merged['nanCount']} equal")
    chart.note(f"relative difference at most {worst:.0e}")
    chart.write()
