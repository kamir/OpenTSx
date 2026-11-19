"""tools/build_studio.py takes one JSON file and needs the data marker exactly once in the template.

Run from the repository root: python -m pytest -q tools
"""
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEMPLATE = "docs/prototypes/smart-topics-studio.template.html"
PAGE = "docs/prototypes/smart-topics-studio.html"


def run(tmp_path: pathlib.Path, template: str, *args: str) -> subprocess.CompletedProcess:
    (tmp_path / "tools").mkdir()
    shutil.copy(ROOT / "tools" / "build_studio.py", tmp_path / "tools" / "build_studio.py")
    (tmp_path / TEMPLATE).parent.mkdir(parents=True)
    (tmp_path / TEMPLATE).write_text(template, encoding="utf-8")
    return subprocess.run([sys.executable, str(tmp_path / "tools" / "build_studio.py"), *args],
                          capture_output=True, text=True, cwd=tmp_path, check=False)


def data(tmp_path: pathlib.Path, text: str) -> str:
    path = tmp_path / "studio.json"
    path.write_text(text, encoding="utf-8")
    return str(path)


TEMPLATE_TEXT = "<title>t</title><style>a{}</style><script>const DATA = /*DATA*/null;</script>"


def test_no_argument_is_a_usage_error(tmp_path):
    result = run(tmp_path, TEMPLATE_TEXT)
    assert result.returncode == 2, result.stdout + result.stderr
    assert "usage: build_studio.py [-h] data" in result.stderr
    assert not (tmp_path / PAGE).exists()


def test_a_template_without_the_marker_fails(tmp_path):
    result = run(tmp_path, "<style>a{}</style><script>const DATA = null;</script>", data(tmp_path, "{}"))
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == (f"build_studio.py: {TEMPLATE}: the marker /*DATA*/null occurs 0 times;"
                             " expected exactly once\n")
    assert not (tmp_path / PAGE).exists()


def test_a_template_with_the_marker_twice_fails(tmp_path):
    result = run(tmp_path, TEMPLATE_TEXT + "/*DATA*/null", data(tmp_path, "{}"))
    assert result.returncode == 1, result.stdout + result.stderr
    assert "the marker /*DATA*/null occurs 2 times; expected exactly once" in result.stderr


def test_data_that_is_not_json_fails(tmp_path):
    path = data(tmp_path, "scenarios: []\n")
    result = run(tmp_path, TEMPLATE_TEXT, path)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == (f"build_studio.py: {path}: not JSON (Expecting value: line 1 column 1 (char 0));"
                             " expected the output of org.opentsx.twin.studio.StudioExport\n")
    assert not (tmp_path / PAGE).exists()


def test_the_page_holds_the_data(tmp_path):
    result = run(tmp_path, TEMPLATE_TEXT, data(tmp_path, '{"scenarios": []}'))
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout == f"wrote {PAGE}\n"
    page = (tmp_path / PAGE).read_text(encoding="utf-8")
    assert 'const DATA = {"scenarios": []};' in page
    assert "/*DATA*/" not in page
