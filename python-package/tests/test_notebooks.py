"""The notebooks shipped with the package are valid and their code compiles."""
import ast
import json
import pathlib

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
