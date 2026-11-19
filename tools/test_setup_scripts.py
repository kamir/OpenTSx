"""The bin setup scripts fail on a failed check, write their errors to stderr and report syntax checks apart from tests.

Run from the repository root: python -m pytest -q tools
"""
import json
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent


def fake_tools(directory: pathlib.Path, tools: dict[str, str]) -> pathlib.Path:
    directory.mkdir()
    for tool, body in tools.items():
        path = directory / tool
        path.write_text(f"#!/bin/bash\n{body}\n", encoding="utf-8")
        path.chmod(0o755)
    return directory


def copy_script(tmp_path: pathlib.Path, name: str) -> pathlib.Path:
    (tmp_path / "bin").mkdir(exist_ok=True)
    return pathlib.Path(shutil.copy(ROOT / "bin" / name, tmp_path / "bin" / name))


def run(script: pathlib.Path, fake: pathlib.Path, *args: str, **env: str) -> subprocess.CompletedProcess:
    environment = dict(os.environ, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}", **env)
    return subprocess.run(["bash", str(script), *args], capture_output=True, text=True, env=environment,
                          cwd=script.parent.parent, check=False)


def validate(tmp_path: pathlib.Path, maven: str, *args: str, java_home: str = "", **bodies: str) -> subprocess.CompletedProcess:
    script = copy_script(tmp_path, "000_validate_environment.sh")
    (tmp_path / "pom.xml").write_text("<project/>\n", encoding="utf-8")
    (tmp_path / "opentsx-core" / "target").mkdir(parents=True)
    (tmp_path / "home").mkdir()
    fake = fake_tools(tmp_path / "fake", {
        "java": "echo 'openjdk version \"17.0.2\" 2022-01-18' >&2",
        "mvn": f"echo 'Apache Maven {maven} (abc)'",
        "git": "[ \"$1\" = --version ] && echo 'git version 2.43.0'; exit 0",
        "docker": "[ \"$1\" = --version ] && echo 'Docker version 27.0.0, build abc' && exit 0; exit 1",
        "curl": "exit 0",
        **bodies,
    })
    return run(script, fake, *args, HOME=str(tmp_path / "home"), JAVA_HOME=java_home or str(tmp_path), DISPLAY=":0")


def test_validate_environment_fails_on_maven_older_than_3_9(tmp_path):
    result = validate(tmp_path, "3.5.4")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "Maven version too old: 3.5.4 (need 3.9+)" in result.stderr
    assert "Environment has critical issues" in result.stderr


def test_validate_environment_passes_with_maven_3_9(tmp_path):
    result = validate(tmp_path, "3.9.6")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Maven 3.9.6 installed" in result.stdout
    assert "Failed checks: 0" in result.stdout


def test_validate_environment_names_a_failed_mvn_run_and_prints_the_summary(tmp_path):
    result = validate(tmp_path, "3.9.6",
                      mvn="echo 'The JAVA_HOME environment variable is not defined correctly,'; exit 1")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "Failed checks: 1" in result.stdout
    assert "Maven: mvn -version failed: The JAVA_HOME environment variable is not defined correctly," in result.stderr


def test_validate_environment_names_a_failed_java_run_and_prints_the_summary(tmp_path):
    result = validate(tmp_path, "3.9.6", java="echo 'Error: could not open lib/jvm.cfg' >&2; exit 1")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "Failed checks: 1" in result.stdout
    assert "Java: java -version failed: Error: could not open lib/jvm.cfg" in result.stderr


def test_validate_environment_warns_without_free_on_linux(tmp_path):
    result = validate(tmp_path, "3.9.6", uname="echo Linux", free="exit 127")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Failed checks: 0" in result.stdout
    assert "Memory: free is missing or failed; install procps to check the memory" in result.stdout


def test_validate_environment_names_a_failed_git_run_and_prints_the_summary(tmp_path):
    result = validate(tmp_path, "3.9.6", git="echo 'git: error while loading shared libraries' >&2; exit 127")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "Failed checks: 1" in result.stdout
    assert "Git: git --version failed: git: error while loading shared libraries" in result.stderr


def test_validate_environment_warns_on_a_failed_docker_run(tmp_path):
    result = validate(tmp_path, "3.9.6", docker="echo 'docker: the Docker Desktop CLI was removed' >&2; exit 1")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Failed checks: 0" in result.stdout
    assert "Docker: docker --version failed: docker: the Docker Desktop CLI was removed" in result.stdout


def test_validate_environment_warns_on_a_failed_df_run(tmp_path):
    result = validate(tmp_path, "3.9.6", uname="echo Linux", df="echo 'df: .: Stale file handle' >&2; exit 1")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Failed checks: 0" in result.stdout
    assert "Disk space: df failed; check the free space with df -h" in result.stdout


def test_validate_environment_fix_without_which_prints_the_summary(tmp_path):
    result = validate(tmp_path, "3.9.6", "--fix", java_home=str(tmp_path / "no-jdk"), which="exit 127")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "JAVA_HOME not set (recommended for Maven)" in result.stdout
    assert "Failed checks: 0" in result.stdout


def test_validate_environment_refuses_an_unknown_option(tmp_path):
    result = validate(tmp_path, "3.9.6", "--strict")
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stderr == ("000_validate_environment.sh: --strict: unknown option; expected --fix or --help\n")


def build(tmp_path: pathlib.Path, *, pom: bool, java: bool) -> subprocess.CompletedProcess:
    script = copy_script(tmp_path, "010_build.sh")
    if pom:
        (tmp_path / "pom.xml").write_text("<project/>\n", encoding="utf-8")
    tools = {"mvn": "exit 0"}
    if java:
        tools["java"] = "echo 'openjdk 17'"
    fake = fake_tools(tmp_path / "fake", tools)
    if java:
        return run(script, fake, JAVA_HOME=str(tmp_path))
    bare = fake_tools(tmp_path / "bare", {})
    (bare / "dirname").symlink_to(shutil.which("dirname"))
    return subprocess.run(["/bin/bash", str(script)], capture_output=True, text=True, check=False,
                          env={"PATH": f"{fake}{os.pathsep}{bare}", "JAVA_HOME": str(tmp_path)})


def test_build_script_writes_a_missing_pom_to_stderr(tmp_path):
    result = build(tmp_path, pom=False, java=True)
    assert result.returncode == 1, result.stdout + result.stderr
    assert f"ERROR: pom.xml not found in {tmp_path}" in result.stderr
    assert "pom.xml not found" not in result.stdout


def test_build_script_writes_a_missing_java_to_stderr(tmp_path):
    result = build(tmp_path, pom=True, java=False)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "ERROR: Java not found in PATH" in result.stderr
    assert "Java not found" not in result.stdout


def run_all_tests(tmp_path: pathlib.Path, mode: str) -> tuple[subprocess.CompletedProcess, dict]:
    script = copy_script(tmp_path, "run_all_tests.sh")
    for name in ("episode_02_create_timeseries.sh", "episode_03_basic_operations.sh", "episode_09_analysis.sh",
                 "episode_10_production_config.sh"):
        (tmp_path / "bin" / name).write_text("#!/bin/bash\necho demo\n", encoding="utf-8")
    for doc in ("README.md", "docs/user-manual/README.md", "docs/devguide/infrastructure/local-development.md",
                "exercises/README.md"):
        (tmp_path / doc).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / doc).write_text("# doc\n", encoding="utf-8")
    fake = fake_tools(tmp_path / "fake", {"mvn": "exit 0"})
    result = run(script, fake, mode)
    return result, json.loads((tmp_path / "test-summary.json").read_text(encoding="utf-8"))


def test_run_all_tests_counts_syntax_and_file_checks_apart_from_tests(tmp_path):
    result, summary = run_all_tests(tmp_path, "--full")
    assert result.returncode == 1, result.stdout + result.stderr
    lines = result.stdout.splitlines()
    assert "PASS: Maven validation" in lines
    assert "SYNTAX OK: bin/episode_02_create_timeseries.sh" in lines
    assert "FILE MISSING: bin/README.md" in lines
    assert lines[-3:] == [
        "Tests: 1, passed: 1, failed: 0",
        "Syntax checks (bash -n, the demos do not run): 4, passed: 4, failed: 0",
        "Required files: 5, present: 4, missing: 1",
    ]
    assert summary == {
        "mode": "--full",
        "tests": {"total": 1, "passed": 1, "failed": 0},
        "syntax_checks": {"total": 4, "passed": 4, "failed": 0},
        "file_checks": {"total": 5, "passed": 4, "failed": 1},
        "overall_status": "FAIL",
    }


def test_run_all_tests_demo_only_runs_no_test(tmp_path):
    result, summary = run_all_tests(tmp_path, "--demo-only")
    assert result.returncode == 0, result.stdout + result.stderr
    assert summary["tests"] == {"total": 0, "passed": 0, "failed": 0}
    assert summary["syntax_checks"] == {"total": 4, "passed": 4, "failed": 0}
