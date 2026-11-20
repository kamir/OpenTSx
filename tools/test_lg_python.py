"""The opentsx-lg Python scripts compile and start with #!/usr/bin/env python3, the MQTT generator prints no password,
exits 1 on a configuration file it cannot read and names the broker it cannot reach, and exits 2 on a usage error, the
connector script posts the config object of cfg.json, refuses a cfg.json without one and reports what Kafka Connect
answered, waiting at most 10 s for each socket operation, and the MQTT example bootstrap installs a pinned paho-mqtt
without sudo, reaches the connector installs without a broker and stops at the first command that fails, and the broker
script publishes its test message after it starts the broker.

Run from the repository root: python -m pytest -q tools
"""
import http.server
import json
import os
import pathlib
import py_compile
import signal
import subprocess
import sys
import threading
import types
import urllib.error

import pytest

ROOT = pathlib.Path(__file__).resolve().parent.parent
LG = ROOT / "opentsx-lg"
BOOTSTRAP = LG / "src" / "main" / "mqtt-example" / "01_bootstrap.sh"
BROKER = LG / "src" / "main" / "mqtt-example" / "02_run_mqtt_broker.sh"
CONNECTOR_SCRIPT = "opentsx-lg/src/main/connect-cfg/deploy_connector.py"
CONNECT_CFG = ROOT / "opentsx-lg" / "src" / "main" / "connect-cfg"
MQTTGEN = LG / "src" / "main" / "mqtt-example" / "mqtt-generator-gist" / "mqttgen.py"
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


def load(path: pathlib.Path, name: str):
    module = types.ModuleType(name)
    module.__file__ = str(path)
    exec(compile(path.read_text(encoding="utf-8"), str(path), "exec"), module.__dict__)
    return module


MQTT_PASSWORD = "test-password-0x2"


def refusing_paho(monkeypatch):
    class Client:
        def username_pw_set(self, username, password):
            pass

        def connect(self, host, port):
            raise ConnectionRefusedError(111, "Connection refused")

    client = types.ModuleType("paho.mqtt.client")
    client.Client = Client
    monkeypatch.setitem(sys.modules, "paho", types.ModuleType("paho"))
    monkeypatch.setitem(sys.modules, "paho.mqtt", types.ModuleType("paho.mqtt"))
    monkeypatch.setitem(sys.modules, "paho.mqtt.client", client)
    sys.modules["paho"].mqtt = sys.modules["paho.mqtt"]
    sys.modules["paho.mqtt"].client = client


def test_the_generator_exits_1_and_names_the_broker_when_it_cannot_connect(tmp_path, monkeypatch):
    refusing_paho(monkeypatch)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"mqtt": {"host": "localhost", "port": 1884, "topic": "t"},
                                  "sensors": {"s1": {"range": [0, 1]}}}), encoding="utf-8")
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(config))
    assert stop.value.code == ("localhost:1884: cannot connect to the MQTT broker ([Errno 111] Connection refused); "
                               "start the broker first, for example with 02_run_mqtt_broker.sh")


def test_the_generator_prints_the_broker_and_the_user_but_not_the_password(tmp_path, monkeypatch, capsys):
    refusing_paho(monkeypatch)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"mqtt": {"host": "localhost", "port": 1884, "username": "demo1",
                                           "password": MQTT_PASSWORD, "topic": "t"},
                                  "sensors": {"s1": {"range": [0, 1]}}}), encoding="utf-8")
    with pytest.raises(SystemExit):
        load(MQTTGEN, "mqttgen").main(str(config))
    out = capsys.readouterr().out
    assert MQTT_PASSWORD not in out
    assert out.splitlines() == ["MQTT broker localhost:1884, user demo1"]


def test_the_generator_exits_1_when_it_cannot_read_the_configuration(tmp_path, monkeypatch):
    refusing_paho(monkeypatch)
    missing = tmp_path / "missing.json"
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(missing))
    assert stop.value.code == (f"{missing}: cannot read the configuration file (No such file or directory); "
                               "pass the path of a JSON configuration file such as config.json")


@pytest.mark.parametrize("arguments", [[], ["a.json", "b.json"]])
def test_the_generator_exits_2_on_a_usage_error(monkeypatch, capsys, arguments):
    refusing_paho(monkeypatch)
    monkeypatch.setattr(sys, "argv", ["mqttgen.py", *arguments])
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "__main__")
    assert stop.value.code == 2
    assert capsys.readouterr().err.splitlines() == [
        f"mqttgen.py: {len(arguments)} arguments given; expected 1, the path of a JSON configuration file such as "
        "config.json"]


class Connect(http.server.BaseHTTPRequestHandler):
    status, answer, requests = 0, b"", []

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        Connect.requests.append((self.command, self.path, self.headers["Content-Type"], json.loads(body)))
        self.send_response(Connect.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(Connect.answer)))
        self.end_headers()
        self.wfile.write(Connect.answer)

    def log_message(self, format, *args):
        pass


def deploy_connector(status: int, answer: bytes):
    Connect.status, Connect.answer, Connect.requests = status, answer, []
    server = http.server.HTTPServer(("127.0.0.1", 0), Connect)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    try:
        environment = {k: v for k, v in os.environ.items() if not k.lower().endswith("_proxy")}
        environment["http_proxy"] = f"http://127.0.0.1:{server.server_address[1]}"
        result = subprocess.run([sys.executable, str(ROOT / CONNECTOR_SCRIPT)], cwd=CONNECT_CFG,
                                stdin=subprocess.DEVNULL, capture_output=True, text=True, env=environment,
                                timeout=30, check=False)
    finally:
        server.shutdown()
        thread.join()
        server.server_close()
    config = json.loads((CONNECT_CFG / "cfg.json").read_text(encoding="utf-8"))["config"]
    assert "connector.class" in config
    assert Connect.requests == [("POST", "http://127.0.0.1:8083/connectors", "application/json",
                                 {"name": "debug_c1", "config": config})], result.stdout + result.stderr
    return result


def test_the_connector_script_exits_1_and_reports_the_answer_when_kafka_connect_refuses_the_connector():
    result = deploy_connector(500, b'{"error_code":500,"message":"stub failure"}')
    assert result.returncode == 1, result.stdout + result.stderr
    assert "created" not in result.stdout
    assert result.stderr.splitlines() == [
        'debug_c1: POST http://127.0.0.1:8083/connectors answered HTTP 500 {"error_code":500,"message":"stub failure"};'
        " the connector was not created, check the log of the Kafka Connect worker"]


def test_the_connector_script_prints_created_when_kafka_connect_creates_the_connector():
    result = deploy_connector(201, b'{"name":"debug_c1"}')
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1] == "New connector debug_c1 created."
    assert result.stderr == ""


def test_the_connector_script_exits_1_when_it_cannot_reach_kafka_connect(monkeypatch, capsys):
    def refuse(request, timeout):
        raise urllib.error.URLError(ConnectionRefusedError(111, "Connection refused"))

    monkeypatch.chdir(CONNECT_CFG)
    monkeypatch.setattr("urllib.request.urlopen", refuse)
    with pytest.raises(SystemExit) as stop:
        load(ROOT / CONNECTOR_SCRIPT, "deploy_connector").main()
    assert stop.value.code == ("http://127.0.0.1:8083/connectors: cannot connect ([Errno 111] Connection refused); "
                               "start Kafka Connect on 127.0.0.1:8083 first")
    assert "created" not in capsys.readouterr().out


@pytest.mark.parametrize("text", ['{"name": "c1"}', '{"name": "c1", "config": "f1"}', '["c1"]'])
def test_the_connector_script_exits_1_when_cfg_json_has_no_config_object(tmp_path, monkeypatch, capsys, text):
    def unreachable(request, timeout=None):
        raise AssertionError("no request without a config object")

    (tmp_path / "cfg.json").write_text(text, encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("urllib.request.urlopen", unreachable)
    with pytest.raises(SystemExit) as stop:
        load(ROOT / CONNECTOR_SCRIPT, "deploy_connector").main()
    assert stop.value.code == ("cfg.json: config: missing or not a JSON object; "
                               "write the connector settings as the object \"config\": {...}")
    assert "created" not in capsys.readouterr().out


@pytest.mark.parametrize("failure, message", [
    (urllib.error.URLError(TimeoutError("timed out")),
     "http://127.0.0.1:8083/connectors: cannot connect (timed out); start Kafka Connect on 127.0.0.1:8083 first"),
    (TimeoutError("timed out"),
     "http://127.0.0.1:8083/connectors: no answer within 10 s; check the log of the Kafka Connect worker"),
])
def test_the_connector_script_waits_at_most_10_s_for_each_socket_operation(monkeypatch, capsys, failure, message):
    timeouts = []

    def wait(request, timeout=None):
        timeouts.append(timeout)
        raise failure

    monkeypatch.chdir(CONNECT_CFG)
    monkeypatch.setattr("urllib.request.urlopen", wait)
    with pytest.raises(SystemExit) as stop:
        load(ROOT / CONNECTOR_SCRIPT, "deploy_connector").main()
    assert (timeouts, stop.value.code) == ([10], message)
    assert "created" not in capsys.readouterr().out


def bootstrap(tmp_path: pathlib.Path, failing: tuple[str, ...] = (), script: pathlib.Path = BOOTSTRAP):
    fake = tmp_path / "fake"
    fake.mkdir()
    work = tmp_path / "work"
    work.mkdir()
    calls = tmp_path / "calls"
    calls.touch()
    hold = tmp_path / "hold"
    os.mkfifo(hold)
    for tool in ("sudo", "pip3", "mosquitto_passwd", "mqtt", "confluent-hub", "docker"):
        block = f'[[ "$1" == sub* ]] && read -r _ < "{hold}"\n' if tool == "mqtt" else ""
        status = 1 if tool in failing else 0
        (fake / tool).write_text(f'#!/bin/bash\necho "{tool} $*" >> "{calls}"\n{block}exit {status}\n',
                                 encoding="utf-8")
        (fake / tool).chmod(0o755)
    environment = dict(os.environ, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}")
    process = subprocess.Popen(["bash", str(script)], cwd=work, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=environment,
                               start_new_session=True)
    try:
        output, _ = process.communicate(timeout=30)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
    return process.returncode, calls.read_text(encoding="utf-8").splitlines(), output


BOOTSTRAP_CALLS = [
    "pip3 install --user paho-mqtt==1.6.1",
    "mosquitto_passwd -c ./volume1/docker/mqtt/config/passwd demo1",
    "confluent-hub install confluentinc/kafka-connect-mqtt:1.7.11",
    "confluent-hub install confluentinc/kafka-connect-influxdb:1.2.13",
]


def test_the_bootstrap_installs_a_pinned_paho_mqtt_without_sudo_and_reaches_the_connector_installs(tmp_path):
    returncode, calls, output = bootstrap(tmp_path)
    assert calls == BOOTSTRAP_CALLS, output
    assert returncode == 0, output


@pytest.mark.parametrize("tool, reached", [("pip3", 1), ("mosquitto_passwd", 2), ("confluent-hub", 3)])
def test_the_bootstrap_stops_at_the_first_command_that_fails(tmp_path, tool, reached):
    returncode, calls, output = bootstrap(tmp_path, failing=(tool,))
    assert calls == BOOTSTRAP_CALLS[:reached], output
    assert returncode == 1, output


def test_the_bootstrap_reaches_the_connector_installs_when_no_broker_runs(tmp_path):
    returncode, calls, output = bootstrap(tmp_path, failing=("mqtt",))
    assert calls[-2:] == BOOTSTRAP_CALLS[-2:], output
    assert returncode == 0, output


def test_the_broker_script_publishes_the_test_message_after_it_starts_the_broker(tmp_path):
    returncode, calls, output = bootstrap(tmp_path, script=BROKER)
    assert calls[0].startswith("docker run --name mqtt "), output
    assert calls[1:] == ["mqtt pub -t test -m Hello2 --mqttVersion 3", "mqtt test -h 127.0.0.1"], output
    assert returncode == 0, output
