"""tools/pair_xcorr_vectors.sh writes vectors only from classes it compiled in the same run.

Run from the repository root: python -m pytest -q tools
"""
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
VECTORS = "docs/datamodel-2026/testvectors/pair-xcorr.v1.json"


MVN = 'for a in "$@"; do case "$a" in -Dmdep.outputFile=*) : > "${a#-Dmdep.outputFile=}" ;; esac; done'


def run(tmp_path: pathlib.Path, javac: str, mvn: str = MVN) -> subprocess.CompletedProcess:
    (tmp_path / "tools").mkdir()
    shutil.copy(ROOT / "tools" / "pair_xcorr_vectors.sh", tmp_path / "tools" / "pair_xcorr_vectors.sh")
    (tmp_path / VECTORS).parent.mkdir(parents=True)
    work = tmp_path / "work"
    for name in ("crosscorrelation/KreuzKorrelation.java", "linkstrength/CCFunction.java"):
        source = work / "we" / "src" / "main" / "java" / "experiments" / name
        source.parent.mkdir(parents=True)
        source.write_text("package experiments;\nclass Broken {\n", encoding="utf-8")
    (work / "we" / ".git").mkdir()
    stale = work / "classes" / "experiments" / "crosscorrelation" / "Vectors.class"
    stale.parent.mkdir(parents=True)
    stale.write_bytes(b"stale")
    fake = tmp_path / "fake"
    fake.mkdir()
    tools = {
        "mvn": mvn,
        "javac": javac,
        "java": 'cp="$2"; [ -f "${cp%%:*}/experiments/crosscorrelation/Vectors.class" ] && echo vectors > "$5"',
    }
    for tool, body in tools.items():
        (fake / tool).write_text(f"#!/bin/bash\n{body}\n", encoding="utf-8")
        (fake / tool).chmod(0o755)
    environment = dict(os.environ, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}")
    return subprocess.run(["bash", str(tmp_path / "tools" / "pair_xcorr_vectors.sh"), str(work)],
                          capture_output=True, text=True, env=environment, check=False)


def test_a_failed_compile_writes_no_vectors_from_old_classes(tmp_path):
    result = run(tmp_path, "echo 'Note: Some input files use unchecked operations.' >&2\n"
                           "echo 'KreuzKorrelation.java:2: error: reached end of file while parsing' >&2\nexit 1")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "KreuzKorrelation.java:2: error: reached end of file while parsing" in result.stderr
    assert "Note:" not in result.stdout + result.stderr
    assert (f"pair_xcorr_vectors.sh: javac: the sources in {tmp_path / 'work' / 'src'} do not compile;"
            " fix the errors above, no vectors were written") in result.stderr
    assert not (tmp_path / VECTORS).exists()
    assert not (tmp_path / "work" / "classes" / "experiments").exists()


def test_a_compile_with_notes_only_writes_the_vectors(tmp_path):
    result = run(tmp_path, "echo 'Note: Some input files use unchecked operations.' >&2\n"
                           'mkdir -p "$5/experiments/crosscorrelation"\n'
                           'echo new > "$5/experiments/crosscorrelation/Vectors.class"')
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Note:" not in result.stdout + result.stderr
    assert (tmp_path / VECTORS).read_text(encoding="utf-8") == "vectors\n"


def test_a_failed_maven_step_shows_maven_errors_and_writes_no_vectors(tmp_path):
    result = run(tmp_path, "exit 0", mvn="echo '[ERROR] Failed to execute goal on project opentsx-core: fake'; exit 1")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "[ERROR] Failed to execute goal on project opentsx-core: fake" in result.stderr
    assert ("pair_xcorr_vectors.sh: mvn install opentsx-core: Maven failed; see the Maven output above"
            in result.stderr)
    assert not (tmp_path / VECTORS).exists()
