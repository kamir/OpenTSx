"""Launcher scripts name main classes that exist, a classpath the build makes, and keep Maven's exit status.

Run from the repository root: python -m pytest -q tools
"""
import os
import pathlib
import re
import shutil
import subprocess

import pytest

ROOT = pathlib.Path(__file__).resolve().parent.parent
EPISODES = sorted(str(p.relative_to(ROOT)) for p in (ROOT / "bin").glob("episode_*.sh"))
SCRIPTS = [
    "opentsx-lg/bin/run_KSAPP_01.sh",
    "opentsx-lg/bin/run_KTAPP_01.sh",
    "opentsx-lg/src/main/docker-compose/bin/executor-script.sh",
    *EPISODES,
]
CLASS = re.compile(r"\borg\.opentsx(?:\.[a-z_][a-z0-9_]*)+\.[A-Z][A-Za-z0-9_]*\b")


def has_source(name: str) -> bool:
    relative = pathlib.Path(*name.split(".")).with_suffix(".java")
    return any((sources / relative).is_file() for sources in ROOT.glob("*/src/main/java"))


@pytest.mark.parametrize("script", SCRIPTS)
def test_named_classes_exist(script):
    names = CLASS.findall((ROOT / script).read_text(encoding="utf-8"))
    assert names, f"{script} names no class"
    missing = [name for name in names if not has_source(name)]
    assert not missing, f"{script} names classes without a source file: {missing}"


def test_every_episode_script_is_checked():
    assert len(EPISODES) == 4, EPISODES


@pytest.mark.parametrize("script", EPISODES)
def test_episode_scripts_take_the_classpath_from_the_launcher(script):
    text = (ROOT / script).read_text(encoding="utf-8")
    assert "SNAPSHOT" not in text, f"{script} names a SNAPSHOT jar"
    assert "target/lib" not in text, f"{script} names a lib directory the build does not make"
    assert 'CLASSPATH="$(bin/opentsx classpath opentsx-core)"' in text


def run_build_script(tmp_path: pathlib.Path, maven_status: int) -> subprocess.CompletedProcess:
    (tmp_path / "bin").mkdir()
    shutil.copy(ROOT / "bin" / "010_build.sh", tmp_path / "bin" / "010_build.sh")
    (tmp_path / "pom.xml").write_text("<project/>\n", encoding="utf-8")
    fake = tmp_path / "fake"
    fake.mkdir()
    for tool, body in (("mvn", f"exit {maven_status}"), ("java", "echo 'openjdk 17'")):
        path = fake / tool
        path.write_text(f"#!/bin/bash\n{body}\n", encoding="utf-8")
        path.chmod(0o755)
    environment = dict(os.environ, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}", JAVA_HOME=str(tmp_path))
    return subprocess.run(["bash", str(tmp_path / "bin" / "010_build.sh")], capture_output=True, text=True,
                          env=environment, check=False)


def test_build_script_parses():
    subprocess.run(["bash", "-n", str(ROOT / "bin" / "010_build.sh")], check=True)


def test_build_script_stops_with_the_maven_status(tmp_path):
    result = run_build_script(tmp_path, 3)
    assert result.returncode == 3, result.stdout + result.stderr
    assert "Build completed" not in result.stdout


def test_build_script_reports_a_successful_build(tmp_path):
    result = run_build_script(tmp_path, 0)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Build completed successfully" in result.stdout
