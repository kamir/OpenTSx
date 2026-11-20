"""The opentsx-lg Python scripts compile and start with #!/usr/bin/env python3, the MQTT generator prints no password,
exits 1 on a configuration file it cannot read or parse or whose entries have the wrong shape or a value it cannot use,
names the broker only when the connect fails, reports a closed stdout and a failed publish in one line, and exits 2 on
a usage error, the connector script posts the config object of cfg.json, refuses a cfg.json it cannot read or without
one and reports what Kafka Connect answered, also when the body ends early, is reset or stops for 10 s, or that no
HTTP answer came, waiting at most 10 s for each socket operation, and the MQTT example bootstrap installs a pinned
paho-mqtt without sudo, reaches the connector installs without a broker and stops at the first command that fails,
and the broker script publishes its test message after it starts the broker.

Run from the repository root: python -m pytest -q tools
"""
import http.server
import json
import os
import pathlib
import py_compile
import signal
import socket
import struct
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

    stub_paho(monkeypatch, Client)


def stub_paho(monkeypatch, client_class):
    client = types.ModuleType("paho.mqtt.client")
    client.Client = client_class
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


def test_the_generator_exits_1_with_one_line_when_the_host_is_no_valid_name(tmp_path, monkeypatch):
    class Client:
        def connect(self, host, port):
            raise UnicodeError("label empty or too long")

    stub_paho(monkeypatch, Client)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"mqtt": {"host": "broker..test", "port": 1884, "topic": "t"},
                                  "sensors": {"s1": {"range": [0, 1]}}}), encoding="utf-8")
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(config))
    assert stop.value.code == ("broker..test:1884: cannot connect to the MQTT broker (label empty or too long); "
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


@pytest.mark.parametrize("text, cause", [
    (None, "No such file or directory"),
    ("not json", "Expecting value: line 1 column 1 (char 0)"),
])
def test_the_generator_exits_1_when_it_cannot_read_the_configuration(tmp_path, monkeypatch, text, cause):
    refusing_paho(monkeypatch)
    config = tmp_path / "config.json"
    if text is not None:
        config.write_text(text, encoding="utf-8")
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(config))
    assert stop.value.code == (f"{config}: cannot read the configuration file ({cause}); "
                               "pass the path of a JSON configuration file such as config.json")


STUB_CLIENT = """MQTT_ERR_SUCCESS = 0


def error_string(rc):
    return {{4: "The client is not currently connected."}}[rc]


class Info:
    def __init__(self, rc):
        self.rc = rc


class Client:
    def connect(self, host, port):
        pass

    def loop_start(self):
        pass

    def publish(self, topic, payload):
        {publish}
"""
CLOSE_STDOUT = "import os; read, write = os.pipe(); os.close(read); os.dup2(write, 1); return Info(0)"


def generator(tmp_path: pathlib.Path, publish: str, verbose: bool, stdout, unbuffered: bool = False,
              sensors: dict | None = None, closed_at_start: bool = False) -> subprocess.CompletedProcess:
    stub = tmp_path / "stub" / "paho" / "mqtt"
    stub.mkdir(parents=True)
    (stub.parent / "__init__.py").touch()
    (stub / "__init__.py").touch()
    (stub / "client.py").write_text(STUB_CLIENT.format(publish=publish), encoding="utf-8")
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"mqtt": {"host": "localhost", "port": 1884, "topic": "t"},
                                  "misc": {"interval_ms": 0, "verbose": verbose},
                                  "sensors": {"s1": {"range": [0, 1]}} if sensors is None else sensors}),
                      encoding="utf-8")
    environment = {k: v for k, v in os.environ.items() if k != "PYTHONUNBUFFERED"}
    environment["PYTHONPATH"] = str(tmp_path / "stub")
    if unbuffered:
        environment["PYTHONUNBUFFERED"] = "1"
    return subprocess.run([sys.executable, str(MQTTGEN), str(config)], cwd=tmp_path, stdin=subprocess.DEVNULL,
                          stdout=stdout, stderr=subprocess.PIPE, text=True, env=environment, timeout=30, check=False,
                          preexec_fn=(lambda: os.close(1)) if closed_at_start else None)


@pytest.mark.parametrize("sensors", [None, {}], ids=["sensors", "no-sensors"])
@pytest.mark.parametrize("unbuffered", [False, True])
@pytest.mark.parametrize("verbose", [True, False])
def test_the_generator_exits_1_with_one_line_when_the_reader_closes_its_stdout(tmp_path, verbose, unbuffered,
                                                                              sensors):
    read, write = os.pipe()
    os.close(read)
    try:
        result = generator(tmp_path, "return Info(0)", verbose, write, unbuffered, sensors)
    finally:
        os.close(write)
    assert result.returncode == 1, result.stderr
    assert result.stderr.splitlines() == ["stdout: closed by its reader; read the whole output"]


@pytest.mark.parametrize("unbuffered", [False, True])
def test_the_generator_advises_verbose_false_when_the_reader_closes_its_stdout_at_a_payload_line(tmp_path,
                                                                                                 unbuffered):
    result = generator(tmp_path, CLOSE_STDOUT, True, subprocess.PIPE, unbuffered)
    assert result.returncode == 1, result.stderr
    assert result.stdout.splitlines()[-1].startswith('t: {"id": "s1", "value": ')
    assert result.stderr.splitlines() == [
        "stdout: closed by its reader; read the whole output or set misc.verbose to false"]


def test_the_generator_runs_and_publishes_when_stdout_is_closed_at_start(tmp_path):
    result = generator(tmp_path, "raise SystemExit(0)", True, None, closed_at_start=True)
    assert result.returncode == 0, result.stderr
    assert result.stderr == ""


def test_the_generator_exits_1_with_one_line_when_a_publish_fails(tmp_path):
    result = generator(tmp_path, 'raise BrokenPipeError(32, "Broken pipe")', False, subprocess.PIPE)
    assert result.returncode == 1, result.stderr
    assert result.stderr.splitlines() == [
        "localhost:1884: cannot publish to topic t ([Errno 32] Broken pipe); check the MQTT broker"]


def test_the_generator_exits_1_with_one_line_when_a_publish_returns_an_error_code(tmp_path):
    result = generator(tmp_path, "return Info(4)", False, subprocess.PIPE)
    assert result.returncode == 1, result.stderr
    assert result.stderr.splitlines() == [
        "localhost:1884: cannot publish to topic t (The client is not currently connected.); check the MQTT broker"]


def test_the_generator_publishes_with_the_shipped_configuration(monkeypatch, capsys):
    class Published(Exception):
        pass

    calls = []

    class Client:
        def username_pw_set(self, username, password):
            calls.append(("user", username))

        def connect(self, host, port):
            calls.append(("connect", host, port))

        def loop_start(self):
            pass

        def publish(self, topic, payload):
            calls.append(("publish", topic))
            raise Published()

    stub_paho(monkeypatch, Client)
    with pytest.raises(Published):
        load(MQTTGEN, "mqttgen").main(str(MQTTGEN.parent / "config.json"))
    assert calls == [("user", "demo1"), ("connect", "localhost", 1883), ("publish", "sensors")]


@pytest.mark.parametrize("config, address", [
    ({"mqtt": {"host": "h", "port": 1, "topic": "a/b"}, "misc": {"interval_ms": 0, "verbose": False}}, ("h", 1)),
    ({"mqtt": {"port": 65535}, "misc": {"interval_ms": 86400000, "verbose": True}}, ("localhost", 65535)),
    ({"misc": {"interval_ms": 0.5}}, ("localhost", 1883)),
    ({"mqtt": {"topic": "t" * 65535}}, ("localhost", 1883)),
    ({"mqtt": {"topic": "\u20ac" * 21845}}, ("localhost", 1883)),
])
def test_the_generator_connects_with_values_at_their_bounds(tmp_path, monkeypatch, capsys, config, address):
    class Connected(Exception):
        pass

    class Client:
        def connect(self, host, port):
            raise Connected(host, port)

    stub_paho(monkeypatch, Client)
    path = tmp_path / "config.json"
    path.write_text(json.dumps(dict(config, sensors={"s1": {"range": [0, 1]}})), encoding="utf-8")
    with pytest.raises(Connected) as connected:
        load(MQTTGEN, "mqttgen").main(str(path))
    assert connected.value.args == address


INTERVAL = "a number of milliseconds from 0 to 86400000"
PORT = "a whole number from 1 to 65535"
TOPIC = "a non-empty JSON string of at most 65535 UTF-8 bytes without +, # or a lone surrogate"
HOST = "a non-empty JSON string without a lone surrogate"
LOGIN = "a JSON string without a lone surrogate"


@pytest.mark.parametrize("section, key, value, expected", [
    ("misc", "interval_ms", "x", INTERVAL),
    ("misc", "interval_ms", -1, INTERVAL),
    ("misc", "interval_ms", 86400001, INTERVAL),
    ("misc", "interval_ms", True, INTERVAL),
    ("misc", "interval_ms", None, INTERVAL),
    ("misc", "interval_ms", float("inf"), INTERVAL),
    ("misc", "interval_ms", float("nan"), INTERVAL),
    ("misc", "verbose", "yes", "true or false"),
    ("misc", "verbose", 1, "true or false"),
    ("mqtt", "host", 5, HOST),
    ("mqtt", "host", "", HOST),
    ("mqtt", "host", "h\ud800", HOST),
    ("mqtt", "port", "1883", PORT),
    ("mqtt", "port", 0, PORT),
    ("mqtt", "port", 65536, PORT),
    ("mqtt", "port", 1883.0, PORT),
    ("mqtt", "port", False, PORT),
    ("mqtt", "topic", ["t"], TOPIC),
    ("mqtt", "topic", "", TOPIC),
    ("mqtt", "topic", "a/#", TOPIC),
    ("mqtt", "topic", "a/+/b", TOPIC),
    ("mqtt", "topic", "a\ud800", TOPIC),
])
def test_the_generator_exits_1_before_the_connect_on_a_value_it_cannot_use(tmp_path, monkeypatch, capsys, section,
                                                                          key, value, expected):
    class Client:
        def connect(self, host, port):
            raise AssertionError(f"connected to {host}:{port}")

    stub_paho(monkeypatch, Client)
    path = tmp_path / "config.json"
    path.write_text(json.dumps({section: {key: value}, "sensors": {"s1": {"range": [0, 1]}}}), encoding="utf-8")
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(path))
    assert stop.value.code == f"{path}: {section}.{key}: {json.dumps(value)} is not allowed; expected {expected}"
    assert capsys.readouterr().out == ""


@pytest.mark.parametrize("topic, size", [("t" * 65536, 65536), ("\u20ac" * 21846, 65538)])
def test_the_generator_exits_1_before_the_connect_on_a_topic_of_more_than_65535_bytes(tmp_path, monkeypatch, capsys,
                                                                                     topic, size):
    class Client:
        def connect(self, host, port):
            raise AssertionError(f"connected to {host}:{port}")

    stub_paho(monkeypatch, Client)
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"mqtt": {"topic": topic}, "sensors": {"s1": {"range": [0, 1]}}}), encoding="utf-8")
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(path))
    assert stop.value.code == (f"{path}: mqtt.topic: a JSON string of {size} UTF-8 bytes is not allowed; "
                               f"expected {TOPIC}")
    assert capsys.readouterr().out == ""


@pytest.mark.parametrize("login, message", [
    ({"username": 5}, f"mqtt.username: 5 is not allowed; expected {LOGIN}"),
    ({"username": None}, f"mqtt.username: null is not allowed; expected {LOGIN}"),
    ({"username": "u\ud800"}, f'mqtt.username: "u\\ud800" is not allowed; expected {LOGIN}'),
    ({"username": "demo1", "password": 12345}, f"mqtt.password: a JSON number is not allowed; expected {LOGIN}"),
    ({"password": 12345}, f"mqtt.password: a JSON number is not allowed; expected {LOGIN}"),
    ({"username": "demo1", "password": []}, f"mqtt.password: a JSON array is not allowed; expected {LOGIN}"),
    ({"username": "demo1", "password": None}, f"mqtt.password: a JSON null is not allowed; expected {LOGIN}"),
    ({"username": "demo1", "password": "12345\ud800"},
     f"mqtt.password: a JSON string with a lone surrogate is not allowed; expected {LOGIN}"),
    ({"password": "12345\ud800"},
     f"mqtt.password: a JSON string with a lone surrogate is not allowed; expected {LOGIN}"),
])
def test_the_generator_exits_1_before_the_connect_on_a_login_that_is_not_a_json_string(tmp_path, monkeypatch, capsys,
                                                                                      login, message):
    class Client:
        def username_pw_set(self, username, password):
            raise AssertionError(f"login as {username}")

        def connect(self, host, port):
            raise AssertionError(f"connected to {host}:{port}")

    stub_paho(monkeypatch, Client)
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"mqtt": login, "sensors": {"s1": {"range": [0, 1]}}}), encoding="utf-8")
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(path))
    assert stop.value.code == f"{path}: {message}"
    assert "12345" not in stop.value.code.removeprefix(f"{path}: ")
    assert capsys.readouterr().out == ""


@pytest.mark.parametrize("login, calls", [
    ({}, [("connect", "localhost", 1883)]),
    ({"username": "demo1"}, [("login", "demo1", None), ("connect", "localhost", 1883)]),
    ({"username": "demo1", "password": MQTT_PASSWORD},
     [("login", "demo1", MQTT_PASSWORD), ("connect", "localhost", 1883)]),
])
def test_the_generator_connects_without_a_login_or_with_a_login_of_json_strings(tmp_path, monkeypatch, capsys, login,
                                                                                calls):
    class Connected(Exception):
        pass

    done = []

    class Client:
        def username_pw_set(self, username, password):
            done.append(("login", username, password))

        def connect(self, host, port):
            done.append(("connect", host, port))
            raise Connected()

    stub_paho(monkeypatch, Client)
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"mqtt": login, "sensors": {"s1": {"range": [0, 1]}}}), encoding="utf-8")
    with pytest.raises(Connected):
        load(MQTTGEN, "mqttgen").main(str(path))
    assert done == calls
    assert MQTT_PASSWORD not in capsys.readouterr().out


RANGE = "is not allowed; expected two whole numbers [low, high] with low <= high"


@pytest.mark.parametrize("config, message", [
    ([], "top level: a JSON array is not allowed; expected a JSON object with mqtt, misc and sensors"),
    ("config", "top level: a JSON string is not allowed; expected a JSON object with mqtt, misc and sensors"),
    ({"mqtt": "localhost:1883", "sensors": {"s1": {}}}, "mqtt: a JSON string is not allowed; expected a JSON object"),
    ({"misc": [500], "sensors": {"s1": {}}}, "misc: a JSON array is not allowed; expected a JSON object"),
    ({"sensors": ["s1"]}, "sensors: a JSON array is not allowed; expected a JSON object with one object per sensor"),
    ({"sensors": None}, "sensors: a JSON null is not allowed; expected a JSON object with one object per sensor"),
    ({"sensors": {"s1": 5}}, "sensors.s1: a JSON number is not allowed; expected a JSON object"),
    ({"sensors": {"s1": {}, "s2": {"range": [0, 45, 90]}}}, f"sensors.s2.range: [0, 45, 90] {RANGE}"),
    ({"sensors": {"s1": {}, "s2": {"range": [0.5, 45]}}}, f"sensors.s2.range: [0.5, 45] {RANGE}"),
    ({"sensors": {"s1": {}, "s2": {"range": [45, 0]}}}, f"sensors.s2.range: [45, 0] {RANGE}"),
    ({"sensors": {"s1": {}, "s2": {"range": [True, 5]}}}, f"sensors.s2.range: [true, 5] {RANGE}"),
    ({"sensors": {"s1": {}, "s2": {"range": "0-45"}}}, f'sensors.s2.range: "0-45" {RANGE}'),
])
def test_the_generator_exits_1_on_a_configuration_of_the_wrong_shape(tmp_path, monkeypatch, capsys, config, message):
    class Client:
        def connect(self, host, port):
            pass

        def loop_start(self):
            pass

        def publish(self, topic, payload):
            raise AssertionError(f"published {payload}")

    stub_paho(monkeypatch, Client)
    path = tmp_path / "config.json"
    path.write_text(json.dumps(config), encoding="utf-8")
    with pytest.raises(SystemExit) as stop:
        load(MQTTGEN, "mqttgen").main(str(path))
    assert stop.value.code == f"{path}: {message}"
    assert capsys.readouterr().out == ""


@pytest.mark.parametrize("config", [{}, {"sensors": {}}])
def test_the_generator_without_sensors_does_not_connect(tmp_path, monkeypatch, capsys, config):
    refusing_paho(monkeypatch)
    path = tmp_path / "config.json"
    path.write_text(json.dumps(config), encoding="utf-8")
    load(MQTTGEN, "mqttgen").main(str(path))
    assert capsys.readouterr().out == "no sensors specified in config, nothing to do\n"


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
    status, answer, length, reset, requests = 0, b"", None, False, []

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        Connect.requests.append((self.command, self.path, self.headers["Content-Type"], json.loads(body)))
        if Connect.status is None:
            self.wfile.write(Connect.answer)
            return
        self.send_response(Connect.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(Connect.answer) if Connect.length is None else Connect.length))
        self.end_headers()
        self.wfile.write(Connect.answer)
        if Connect.reset:
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            os.close(self.connection.detach())

    def log_message(self, format, *args):
        pass


def deploy_connector(status: int | None, answer: bytes, length: int | None = None, reset: bool = False):
    Connect.status, Connect.answer, Connect.length, Connect.reset, Connect.requests = status, answer, length, reset, []
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


def test_the_connector_script_keeps_the_status_201_of_an_answer_whose_body_ends_early():
    result = deploy_connector(201, b'{"name":"debug_c1"}', length=40)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1] == "New connector debug_c1 created."
    assert result.stderr == ""


def test_the_connector_script_keeps_the_status_500_of_an_answer_whose_body_ends_early():
    result = deploy_connector(500, b'{"error_code":500,', length=40)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "created" not in result.stdout
    assert result.stderr.splitlines() == [
        'debug_c1: POST http://127.0.0.1:8083/connectors answered HTTP 500 {"error_code":500,; the connector was not '
        "created, check the log of the Kafka Connect worker"]


def test_the_connector_script_keeps_the_status_201_when_the_connection_is_reset_in_the_body():
    result = deploy_connector(201, b'{"name":', length=40, reset=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1] == "New connector debug_c1 created."
    assert result.stderr == ""


def test_the_connector_script_keeps_the_status_500_and_the_cause_when_the_connection_is_reset_in_the_body():
    result = deploy_connector(500, b'{"error_code":500,', length=40, reset=True)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "created" not in result.stdout
    assert result.stderr.splitlines() == [
        "debug_c1: POST http://127.0.0.1:8083/connectors answered HTTP 500 ([Errno 104] Connection reset by peer); "
        "the connector was not created, check the log of the Kafka Connect worker"]


class Stalled:
    def read(self, *arguments):
        raise TimeoutError("timed out")

    def close(self):
        pass


def test_the_connector_script_keeps_the_status_201_when_the_body_stops_for_10_s(monkeypatch, capsys):
    class Created(Stalled):
        def getcode(self):
            return 201

        def __enter__(self):
            return self

        def __exit__(self, *failure):
            return False

    monkeypatch.chdir(CONNECT_CFG)
    monkeypatch.setattr("urllib.request.urlopen", lambda request, timeout=None: Created())
    load(ROOT / CONNECTOR_SCRIPT, "deploy_connector").main()
    assert capsys.readouterr().out.splitlines()[-1] == "New connector debug_c1 created."


def test_the_connector_script_keeps_the_status_500_and_the_cause_when_the_body_stops_for_10_s(monkeypatch, capsys):
    def refuse(request, timeout=None):
        raise urllib.error.HTTPError(request.full_url, 500, "Internal Server Error", {}, Stalled())

    monkeypatch.chdir(CONNECT_CFG)
    monkeypatch.setattr("urllib.request.urlopen", refuse)
    with pytest.raises(SystemExit) as stop:
        load(ROOT / CONNECTOR_SCRIPT, "deploy_connector").main()
    assert stop.value.code == ("debug_c1: POST http://127.0.0.1:8083/connectors answered HTTP 500 (timed out); "
                               "the connector was not created, check the log of the Kafka Connect worker")
    assert "created" not in capsys.readouterr().out


@pytest.mark.parametrize("answer, cause", [
    (b"", "Remote end closed connection without response"),
    (b"SSH-2.0-stub\r\n", "SSH-2.0-stub"),
])
def test_the_connector_script_exits_1_when_the_connection_closes_without_an_http_answer(answer, cause):
    result = deploy_connector(None, answer)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "created" not in result.stdout
    assert result.stderr.splitlines() == [
        f"http://127.0.0.1:8083/connectors: no HTTP answer ({cause}); check that Kafka Connect listens on "
        "http://127.0.0.1:8083/connectors and read the log of its worker"]


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


@pytest.mark.parametrize("text, cause", [
    (None, "No such file or directory"),
    ("not json", "Expecting value: line 1 column 1 (char 0)"),
])
def test_the_connector_script_exits_1_when_it_cannot_read_cfg_json(tmp_path, monkeypatch, capsys, text, cause):
    def unreachable(request, timeout=None):
        raise AssertionError("no request without a readable cfg.json")

    if text is not None:
        (tmp_path / "cfg.json").write_text(text, encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("urllib.request.urlopen", unreachable)
    with pytest.raises(SystemExit) as stop:
        load(ROOT / CONNECTOR_SCRIPT, "deploy_connector").main()
    assert stop.value.code == (f"cfg.json: cannot read the configuration file ({cause}); "
                               "run the script in a folder with a JSON file cfg.json, such as src/main/connect-cfg/")
    assert capsys.readouterr().out == ""


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
