"""The opentsx-lg Python scripts compile and start with #!/usr/bin/env python3, and the MQTT example bootstrap installs
a pinned paho-mqtt without sudo and reaches the connector installs.

Run from the repository root: python -m pytest -q tools
"""
import os
import pathlib
import py_compile
import signal
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
LG = ROOT / "opentsx-lg"
BOOTSTRAP = LG / "src" / "main" / "mqtt-example" / "01_bootstrap.sh"
CONNECTOR_SCRIPT = "opentsx-lg/src/main/connect-cfg/deploy_connector.py"
SHEBANG = "#!/usr/bin/env python3"


def tracked(pattern: str) -> list[str]:
    listing = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", pattern], capture_output=True, check=True)
    return [p for p in listing.stdout.decode("utf-8").split("\0") if p]


def python_problems(root: pathlib.Path, name: str, cache: pathlib.Path) -> list[str]:
    path = root / name
    first = path.read_bytes().partition(b"\n")[0].decode("utf-8", "replace")
    problems = []
    if not first.startswith("#!"):
        problems.append(f"{name}: no shebang; start the script with {SHEBANG}")
    elif first != SHEBANG:
        problems.append(f"{name}: shebang {first} is not allowed; start the script with {SHEBANG}")
    try:
        py_compile.compile(str(path), cfile=str(cache / "check.pyc"), doraise=True)
    except py_compile.PyCompileError as error:
        cause = error.exc_value
        where = f"{name}:{cause.lineno}" if isinstance(cause, SyntaxError) else name
        reason = cause.msg if isinstance(cause, SyntaxError) else f"{error.exc_type_name}: {cause}"
        problems.append(f"{where}: {reason}; the script must compile with Python 3")
    return problems


def test_a_script_with_a_plain_python_shebang_fails_the_check(tmp_path):
    text = (ROOT / CONNECTOR_SCRIPT).read_text(encoding="utf-8").partition("\n")[2]
    (tmp_path / "copy.py").write_text("#!/usr/bin/python\n" + text, encoding="utf-8")
    (tmp_path / "bare.py").write_text(text, encoding="utf-8")
    assert python_problems(tmp_path, "copy.py", tmp_path) == [
        "copy.py: shebang #!/usr/bin/python is not allowed; start the script with #!/usr/bin/env python3"]
    assert python_problems(tmp_path, "bare.py", tmp_path) == [
        "bare.py: no shebang; start the script with #!/usr/bin/env python3"]


def test_a_script_with_a_syntax_error_fails_the_check(tmp_path):
    text = SHEBANG + "\n" + (ROOT / CONNECTOR_SCRIPT).read_text(encoding="utf-8").partition("\n")[2]
    (tmp_path / "copy.py").write_text(text + "\nx = (\n", encoding="utf-8")
    line = len((text + "\nx = (\n").splitlines())
    assert python_problems(tmp_path, "copy.py", tmp_path) == [
        f"copy.py:{line}: '(' was never closed; the script must compile with Python 3"]


def test_a_script_with_the_env_shebang_that_compiles_passes_the_check(tmp_path):
    (tmp_path / "run.py").write_text(SHEBANG + "\nimport json\nprint(json.dumps({}))\n", encoding="utf-8")
    assert python_problems(tmp_path, "run.py", tmp_path) == []


def test_every_lg_python_script_compiles_and_starts_with_the_env_shebang(tmp_path):
    scripts = tracked("opentsx-lg/*.py")
    assert CONNECTOR_SCRIPT in scripts
    assert [p for s in scripts for p in python_problems(ROOT, s, tmp_path)] == []


def test_the_bootstrap_installs_a_pinned_paho_mqtt_without_sudo_and_reaches_the_connector_installs(tmp_path):
    fake = tmp_path / "fake"
    fake.mkdir()
    work = tmp_path / "work"
    work.mkdir()
    calls = tmp_path / "calls"
    calls.touch()
    hold = tmp_path / "hold"
    os.mkfifo(hold)
    for tool in ("sudo", "pip3", "mosquitto_passwd", "mqtt", "confluent-hub"):
        block = f'[[ "$1" == sub* ]] && read -r _ < "{hold}"\n' if tool == "mqtt" else ""
        (fake / tool).write_text(f'#!/bin/bash\necho "{tool} $*" >> "{calls}"\n{block}exit 0\n', encoding="utf-8")
        (fake / tool).chmod(0o755)
    environment = dict(os.environ, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}")
    bootstrap = subprocess.Popen(["bash", str(BOOTSTRAP)], cwd=work, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=environment,
                                 start_new_session=True)
    try:
        output, _ = bootstrap.communicate(timeout=30)
    except subprocess.TimeoutExpired:
        os.killpg(bootstrap.pid, signal.SIGKILL)
        output, _ = bootstrap.communicate()
    assert calls.read_text(encoding="utf-8").splitlines() == [
        "pip3 install --user paho-mqtt==1.6.1",
        "mosquitto_passwd -c ./volume1/docker/mqtt/config/passwd demo1",
        "mqtt pub -t test -m Hello2 --mqttVersion 3",
        "confluent-hub install confluentinc/kafka-connect-mqtt:1.7.11",
        "confluent-hub install confluentinc/kafka-connect-influxdb:1.2.13",
    ], output
    assert bootstrap.returncode == 0, output
