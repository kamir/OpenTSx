"""Tests for tools/check_links.py.

Run from the repository root: python -m pytest -q tools
"""
import pathlib
import subprocess
import sys

import check_links

SCRIPT = pathlib.Path(__file__).resolve().parent / "check_links.py"


def write(root: pathlib.Path, files: dict[str, str]) -> None:
    for path, text in files.items():
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_text(text, encoding="utf-8")


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, check=False)


def test_a_broken_image_link_fails(tmp_path, capsys):
    write(tmp_path, {"docs/page.md": "![Power curve of turbine T07](img/missing.png)\n"})
    assert check_links.main(tmp_path) == 1
    assert "broken link: docs/page.md: img/missing.png" in capsys.readouterr().out


def test_an_image_that_exists_passes(tmp_path, capsys):
    write(tmp_path, {"docs/page.md": "![Power curve of turbine T07](img/curve.png)\n", "docs/img/curve.png": "png"})
    assert check_links.main(tmp_path) == 0
    assert "checked 1 files, 0 broken links" in capsys.readouterr().out


def test_the_saas_modules_exercises_tests_and_data_are_checked(tmp_path, capsys):
    dirs = ["opentsx-saas-frontend", "opentsx-saas-backend", "exercises", "tests", "data"]
    write(tmp_path, {f"{name}/README.md": "[gone](../GONE.md)\n" for name in dirs})
    assert check_links.main(tmp_path) == 1
    out = capsys.readouterr().out
    for name in dirs:
        assert f"broken link: {name}/README.md: ../GONE.md" in out
    assert "checked 5 files, 5 broken links" in out


def test_the_external_submodule_is_not_checked(tmp_path, capsys):
    write(tmp_path, {"README.md": "[guide](docs/guide.md)\n", "docs/guide.md": "text\n",
                     "external/wayang/README.md": "[tutorial](guides/tutorial.md)\n"})
    assert check_links.main(tmp_path) == 0
    assert "checked 2 files, 0 broken links" in capsys.readouterr().out


def test_a_missing_root_is_a_usage_error(tmp_path):
    missing = tmp_path / "nonexistent"
    result = run(str(missing))
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stdout == ""
    assert result.stderr == (f"check_links.py: root: {missing} is not a directory; "
                             "pass the repository root or another directory that holds .md files\n")


def test_a_root_without_a_markdown_file_to_check_is_a_usage_error(tmp_path):
    write(tmp_path, {"notes.txt": "text\n", "archive/old.md": "[gone](GONE.md)\n"})
    result = run(str(tmp_path))
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stdout == ""
    assert result.stderr == (f"check_links.py: root: {tmp_path} holds no .md file to check; "
                             "pass the repository root or another directory that holds .md files\n")


def test_help_prints_the_usage():
    result = run("--help")
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.startswith("usage: check_links.py [-h] [root]\n")
    assert "checked" not in result.stdout
