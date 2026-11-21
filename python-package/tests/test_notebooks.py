"""The notebooks shipped with the package are valid, their code compiles, and notebook 4 reads a comparison as the server answers it."""
import ast
import json
import pathlib

import pandas as pd

NOTEBOOKS = sorted((pathlib.Path(__file__).parent.parent / "notebooks").glob("*.ipynb"))


def test_there_are_notebooks():
    assert [p.name for p in NOTEBOOKS][:1] == ["01-zeitreihen-inventar.ipynb"]
    # the example notebook 3 tests against ships with it, and says what it is
    example = NOTEBOOKS[0].parent / "beispiel-windmessung.csv"
    assert example.exists() and "Keine Messung eines Betreibers" in example.read_text(encoding="utf-8")


def test_every_code_cell_compiles_and_no_output_is_shipped():
    for p in NOTEBOOKS:
        nb = json.loads(p.read_text(encoding="utf-8"))
        assert nb["nbformat"] == 4
        for i, cell in enumerate(nb["cells"]):
            if cell["cell_type"] == "code":
                ast.parse("".join(cell["source"]), filename=f"{p.name}[{i}]")
                assert cell["outputs"] == [], f"{p.name}[{i}] ships output"


def test_the_notebooks_write_nothing():
    for p in NOTEBOOKS:
        text = p.read_text(encoding="utf-8")
        # a run computes on the server and is kept there; nothing goes to the broker
        for forbidden in ("Producer(", ".produce(", "KafkaProducer"):
            assert forbidden not in text, f"{p.name} contains {forbidden}"


ROWS = [{"key": "ely.trips", "label": "trips", "unit": "", "decimals": 0, "better": -1, "base": 1.0, "run": 1.0, "delta": 0.0},
        {"key": "handover.delivered_kg", "label": "delivered", "unit": "kg", "decimals": 1, "better": 1, "base": 900.0,
         "run": 970.0, "delta": 70.0}]


def _show_comparison(compare):
    """Runs the comparison cell of notebook 4 on a comparison the server answered; returns its table."""
    nb = json.loads((NOTEBOOKS[0].parent / "04-den-zwilling-fragen.ipynb").read_text(encoding="utf-8"))
    cell = next("".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code" and "client.compare(" in "".join(c["source"]))

    class Client:
        def compare(self, system, run, base):
            assert (system, run, base) == ("h2-chain", "r2", "r1")
            return compare

    ns = {"pd": pd, "client": Client(), "SYSTEM": "h2-chain", "run": {"run": "r2", "baseline": "r1"}}
    exec(cell, ns)
    return ns["kpis"]


def test_notebook_4_shows_a_comparison_on_generated_inputs_without_verdict_and_says_why(capsys):
    reason = "generated, not measured: offtake.demand_kgph; no KPI is ranked better or worse"
    kpis = _show_comparison({"reasons": [reason], "kpis": ROWS})
    assert list(kpis["difference"]) == [0.0, 70.0]
    assert kpis["verdict"].isna().all()
    assert reason in capsys.readouterr().out


def test_notebook_4_shows_the_verdict_of_a_ranked_comparison(capsys):
    kpis = _show_comparison({"reasons": [], "kpis": [dict(ROWS[0], verdict="same"), dict(ROWS[1], verdict="better")]})
    assert list(kpis["verdict"]) == ["same", "better"]
    assert capsys.readouterr().out == ""
