import importlib
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest


@pytest.fixture
def startup(monkeypatch):
    api = importlib.import_module("opentsx.client.research")
    state = SimpleNamespace(api=api, events=[], servers=[], serve_error=None, close_error=None)

    def forbidden(*args, **kwargs):
        raise AssertionError("A disabled client was constructed")

    monkeypatch.setattr(api, "KafkaStore", forbidden)
    for module, member in [
        ("opentsx.client.lifecycle", "LifecycleStore"),
        ("opentsx.client.registry", "MLflowRegistry"),
        ("opentsx.client.smart_topics", "SmartTopics"),
        ("opentsx.client.topic_catalogue", "TopicCatalogue"),
    ]:
        monkeypatch.setitem(sys.modules, module, SimpleNamespace(**{member: forbidden}))

    class Server:
        def __init__(self, address, handler):
            self.address, self.handler = address, handler
            self.closed = False
            state.servers.append(self)

        def serve_forever(self):
            state.events.append(("serve",))
            if state.serve_error is not None:
                raise state.serve_error

        def server_close(self):
            self.closed = True
            state.events.append(("server_close",))

    monkeypatch.setattr(api, "ThreadingHTTPServer", Server)
    monkeypatch.setattr(api.signal, "signal", lambda signum, callback: None)
    return state


def connected(startup, monkeypatch, tmp_path):
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("<title>Workbench fixture</title>", encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    manifest.write_text('{"revision":"fixture"}', encoding="utf-8")

    def client(name):
        def create(*args):
            startup.events.append((name, *args))
            return SimpleNamespace()

        return create

    def recover():
        startup.events.append(("recover",))

    def close():
        startup.events.append(("lifecycle_close",))
        if startup.close_error is not None:
            raise startup.close_error

    def lifecycle(*args):
        startup.events.append(("lifecycle", *args))
        return SimpleNamespace(recover=recover, close=close)

    monkeypatch.setattr(startup.api, "KafkaStore", client("kafka"))
    for module, member, constructor in [
        ("opentsx.client.lifecycle", "LifecycleStore", client("state")),
        ("opentsx.client.registry", "MLflowRegistry", client("registry")),
        ("opentsx.client.smart_topics", "SmartTopics", lifecycle),
        ("opentsx.client.topic_catalogue", "TopicCatalogue", client("catalogue")),
    ]:
        monkeypatch.setattr(sys.modules[module], member, constructor)
    return [
        "serve",
        "--bootstrap",
        "broker:29092",
        "--prefix",
        "tsx.example.",
        "--web-root",
        str(web),
        "--manifest",
        str(manifest),
        "--state-dir",
        str(tmp_path / "state"),
        "--registry-url",
        "http://registry:5000",
        "--port",
        "18083",
    ]


def test_finite_service_starts_api_only_without_clients_or_frontend(startup):
    startup.api.main(["serve-finite", "--port", "18082"])
    assert len(startup.servers) == 1
    assert startup.servers[0].address == ("127.0.0.1", 18082)
    assert startup.servers[0].closed
    assert startup.events == [("serve",), ("server_close",)]


def test_finite_service_can_serve_an_explicit_built_web_root(startup, tmp_path):
    (tmp_path / "index.html").write_text("<title>Finite fixture</title>", encoding="utf-8")
    startup.api.main(["serve-finite", "--web-root", str(tmp_path)])
    assert startup.servers[0].address == ("127.0.0.1", 8080)
    assert startup.servers[0].closed


def test_empty_web_root_does_not_serve_the_current_directory(
    startup, tmp_path, monkeypatch, capsys
):
    (tmp_path / "index.html").write_text("Private local file", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    with pytest.raises(SystemExit) as error:
        startup.api.main(["serve-finite", "--web-root", ""])
    assert error.value.code == 2
    assert "web root has no built index.html" in capsys.readouterr().err
    assert startup.servers == []


def test_finite_service_accepts_an_explicit_manifest_without_web_root(startup, tmp_path, capsys):
    manifest = tmp_path / "manifest.json"
    manifest.write_text('{"revision":"fixture"}', encoding="utf-8")
    startup.api.main(["serve-finite", "--manifest", str(manifest)])
    assert '"revision": "fixture"' in capsys.readouterr().out
    assert startup.servers[0].closed


@pytest.mark.parametrize(
    "option,value",
    [
        ("--bootstrap", "broker:29092"),
        ("--bootstrap", ""),
        ("--registry-url", "http://registry:5000"),
        ("--registry-url", ""),
        ("--state-dir", "state"),
        ("--state-dir", ""),
    ],
)
def test_finite_service_rejects_supplied_connected_settings_before_clients(
    startup, option, value, capsys
):
    with pytest.raises(SystemExit) as error:
        startup.api.main(["serve-finite", option, value])
    assert error.value.code == 2
    assert "serve-finite does not accept" in capsys.readouterr().err
    assert startup.events == []
    assert startup.servers == []


def test_finite_service_rejects_missing_built_index_before_startup(startup, tmp_path, capsys):
    with pytest.raises(SystemExit) as error:
        startup.api.main(["serve-finite", "--web-root", str(tmp_path)])
    assert error.value.code == 2
    assert "web root has no built index.html" in capsys.readouterr().err
    assert startup.servers == []


@pytest.mark.parametrize(
    "content",
    [
        "{",
        "[]",
        "null",
        '{"value":NaN}',
        '{"value":Infinity}',
        '{"revision":"first","revision":"second"}',
    ],
)
def test_supplied_manifest_must_be_a_strict_json_object_before_clients(
    startup, tmp_path, content, capsys
):
    manifest = tmp_path / "manifest.json"
    manifest.write_text(content, encoding="utf-8")
    with pytest.raises(SystemExit) as error:
        startup.api.main(["serve-finite", "--manifest", str(manifest)])
    assert error.value.code == 2
    assert "invalid manifest:" in capsys.readouterr().err
    assert startup.events == []
    assert startup.servers == []


def test_missing_manifest_fails_instead_of_starting_without_metadata(startup, tmp_path, capsys):
    with pytest.raises(SystemExit) as error:
        startup.api.main(["serve-finite", "--manifest", str(tmp_path / "missing.json")])
    assert error.value.code == 2
    assert "invalid manifest:" in capsys.readouterr().err
    assert startup.servers == []


@pytest.mark.parametrize(
    "arguments",
    [
        ["serve"],
        ["seed"],
        ["demo-seed"],
        ["serve", "--bootstrap", "broker:29092"],
        [
            "serve",
            "--bootstrap",
            "broker:29092",
            "--web-root",
            "web",
            "--manifest",
            "manifest.json",
        ],
    ],
)
def test_connected_commands_keep_required_settings_and_fail_before_clients(startup, arguments):
    with pytest.raises(SystemExit) as error:
        startup.api.main(arguments)
    assert error.value.code == 2
    assert startup.events == []
    assert startup.servers == []


def test_connected_serve_keeps_configured_clients_recovery_and_cleanup(
    startup, monkeypatch, tmp_path
):
    arguments = connected(startup, monkeypatch, tmp_path)
    startup.api.main(arguments)
    assert startup.servers[0].address == ("0.0.0.0", 18083)
    assert startup.servers[0].closed
    assert [event[0] for event in startup.events] == [
        "kafka",
        "state",
        "registry",
        "catalogue",
        "lifecycle",
        "recover",
        "serve",
        "lifecycle_close",
        "server_close",
    ]
    assert startup.events[0] == ("kafka", "broker:29092", "tsx.example.")
    assert startup.events[1] == ("state", Path(tmp_path / "state/lifecycle.sqlite"))
    assert startup.events[2] == ("registry", "http://registry:5000")
    assert startup.events[3] == ("catalogue", "broker:29092", "tsx.example.")


def test_finite_server_failure_closes_its_server_without_lifecycle_cleanup(startup):
    startup.serve_error = RuntimeError("server fixture failure")
    with pytest.raises(RuntimeError, match="server fixture failure"):
        startup.api.main(["serve-finite"])
    assert startup.servers[0].closed
    assert startup.events == [("serve",), ("server_close",)]


def test_lifecycle_close_failure_still_closes_the_connected_server(startup, monkeypatch, tmp_path):
    arguments = connected(startup, monkeypatch, tmp_path)
    startup.close_error = RuntimeError("lifecycle fixture failure")
    with pytest.raises(RuntimeError, match="lifecycle fixture failure"):
        startup.api.main(arguments)
    assert startup.servers[0].closed
    assert startup.events[-2:] == [("lifecycle_close",), ("server_close",)]
