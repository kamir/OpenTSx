import pathlib
import re

import pytest

tomllib = pytest.importorskip("tomllib")

PYPROJECT = tomllib.loads((pathlib.Path(__file__).resolve().parents[1] / "pyproject.toml").read_text(encoding="utf-8"))


def _requirements(extra):
    """The distribution names an extra installs, with the extras of opentsx it names resolved."""
    names = set()
    for requirement in PYPROJECT["project"]["optional-dependencies"][extra]:
        name, _, nested = re.match(r"([A-Za-z0-9_.-]+)(\[([^\]]+)\])?", requirement).groups()
        if name == "opentsx":
            for e in nested.split(","):
                names |= _requirements(e.strip())
        else:
            names.add(name.lower())
    return names


def test_all_installs_every_optional_feature():
    assert _requirements("all") == _requirements("kafka") | _requirements("iceberg") | _requirements(
        "viz") | _requirements("yaml")
    assert "pyiceberg" in _requirements("all")


def test_no_extra_names_a_package_the_code_does_not_use():
    extras = PYPROJECT["project"]["optional-dependencies"]
    assert set(extras) == {"kafka", "iceberg", "yaml", "viz", "all", "dev"}
    assert not {"pytest-asyncio", "sphinx"} & _requirements("dev")
