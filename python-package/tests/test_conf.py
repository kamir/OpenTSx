"""opentsx.conf: the shared vectors conf.v1.json, the registry rules and the copy of the Java registry."""

import base64
import importlib.util
import json
import logging
import pathlib
import re
import sys
from datetime import timedelta

import pytest

from opentsx.conf import OpenTsxConf, OpenTsxConfError, Registry, readFile
from opentsx.conf.conf import CONF_COMMANDS, _parse

REPO = pathlib.Path(__file__).resolve().parents[2]
VECTORS = json.loads((REPO / "docs/datamodel-2026/testvectors/conf.v1.json").read_text(encoding="utf-8"))
FIXTURE = Registry(json.dumps(VECTORS["registry"]))
CASES = {case["id"]: case for case in VECTORS["cases"]}
JAVA_REGISTRY = REPO / "opentsx-conf/src/main/resources/org/opentsx/conf/opentsx.config.v1.json"
PYTHON_REGISTRY = REPO / "python-package/opentsx/conf/opentsx.config.v1.json"
HAVE_YAML = importlib.util.find_spec("yaml") is not None
YAML_EXTRA = 'YAML files need the extra opentsx[yaml]; run pip install "opentsx[yaml]"'


@pytest.fixture(autouse=True)
def keep_the_opentsx_log_level():
    logger = logging.getLogger("opentsx")
    level = logger.level
    yield
    logger.setLevel(level)


def ids(kind):
    return [case["id"] for case in VECTORS["cases"] if case["kind"] == kind]


def fill(value, directory):
    if isinstance(value, str):
        return value.replace("{dir}", str(directory))
    if isinstance(value, list):
        return [fill(item, directory) for item in value]
    if isinstance(value, dict):
        return {key: fill(item, directory) for key, item in value.items()}
    return value


def millis(value):
    if isinstance(value, timedelta):
        return {"millis": value // timedelta(milliseconds=1)}
    if isinstance(value, list):
        return [millis(item) for item in value]
    return value


def typed(conf, key):
    entry = FIXTURE.entries[key]
    if entry["type"] == "list":
        return conf.getDurationList(key) if entry.get("items") == "duration" else conf.getList(key)
    getter = {
        "int": conf.getInt,
        "long": conf.getLong,
        "double": conf.getDouble,
        "boolean": conf.getBoolean,
        "duration": conf.getDuration,
    }.get(entry["type"], conf.get)
    return getter(key)


def test_vector_counts():
    counts = {}
    for case in VECTORS["cases"]:
        counts[case["kind"]] = counts.get(case["kind"], 0) + 1
    assert counts == {
        "keyGrammar": 12,
        "envName": 8,
        "value": 92,
        "properties": 22,
        "yaml": 33,
        "load": 105,
        "notSet": 4,
        "show": 7,
        "debugString": 4,
        "api": 1,
    }
    assert len(VECTORS["cases"]) == 288


@pytest.mark.parametrize("case_id", ids("keyGrammar"))
def test_key_grammar(case_id):
    case = CASES[case_id]
    assert Registry.validKey(case["key"]) is case["valid"]


@pytest.mark.parametrize("case_id", ids("envName"))
def test_env_name(case_id):
    case = CASES[case_id]
    assert Registry.envName(case["key"]) == case["name"]


@pytest.mark.parametrize("case_id", ids("value"))
def test_value(case_id):
    case = CASES[case_id]
    conf = OpenTsxConf(FIXTURE)
    if "error" in case["expect"]:
        with pytest.raises(OpenTsxConfError) as raised:
            conf.set(case["key"], case["input"])
        assert str(raised.value) == case["expect"]["error"]
        assert conf.getOption(case["key"]) == FIXTURE.entries[case["key"]].get("default")
        return
    assert conf.set(case["key"], case["input"]) is conf
    assert conf.get(case["key"]) == case["expect"]["text"]
    assert conf.source(case["key"]) == "code"
    value = typed(conf, case["key"])
    assert millis(value) == case["expect"]["value"]
    assert type(millis(value)) is type(case["expect"]["value"])


@pytest.mark.parametrize("case_id", ids("properties") + ids("yaml"))
def test_file(case_id, tmp_path):
    case = fill(CASES[case_id], tmp_path)
    if "base64" in case:
        pathlib.Path(case["file"]).write_bytes(base64.b64decode(case["base64"]))
    else:
        pathlib.Path(case["file"]).write_bytes(case["text"].encode("utf-8"))
    expect = case["expect"]
    if case["kind"] == "yaml" and not HAVE_YAML and "base64" not in case:
        expect = {"error": f"{case['file']}: {YAML_EXTRA}"}
    if "pairs" in expect:
        assert readFile(case["file"], FIXTURE) == [tuple(pair) for pair in expect["pairs"]]
        return
    with pytest.raises(OpenTsxConfError) as raised:
        readFile(case["file"], FIXTURE)
    if "errorStartsWith" in expect:
        assert str(raised.value).startswith(expect["errorStartsWith"])
        assert "\n" not in str(raised.value)
    else:
        assert str(raised.value) == expect["error"]


def run_load(case, directory):
    case = fill(case, directory)
    for name, text in case.get("files", {}).items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8"))
    for name in case.get("dirs", []):
        (directory / name).mkdir(parents=True)
    registry = Registry.shipped() if case.get("registry") == "shipped" else FIXTURE
    loaded = OpenTsxConf.load(case["args"], case.get("env", {}), case.get("confDir"), registry=registry)
    if loaded.conf is not None:
        for key, value in case.get("set", []):
            loaded.conf.set(key, value)
        for key, value in case.get("setIfMissing", []):
            loaded.conf.setIfMissing(key, value)
    return loaded


@pytest.mark.parametrize("case_id", ids("load"))
def test_load(case_id, tmp_path):
    case = CASES[case_id]
    expect = fill(case["expect"], tmp_path)
    yaml_files = [name for name in case.get("files", {}) if name.endswith((".yaml", ".yml"))]
    if yaml_files and "error" not in expect and not HAVE_YAML:
        expect = {"error": f"{tmp_path}/{yaml_files[0]}: {YAML_EXTRA}", "exit": 2}
    if "error" in expect:
        assert expect["exit"] == 2
        with pytest.raises(OpenTsxConfError) as raised:
            run_load(case, tmp_path)
        assert str(raised.value) == expect["error"]
        return
    loaded = run_load(case, tmp_path)
    assert loaded.command == expect["command"]
    if expect.get("help") or expect.get("version"):
        assert loaded.help is bool(expect.get("help"))
        assert loaded.version is bool(expect.get("version"))
        assert loaded.conf is None
        return
    assert loaded.help is False and loaded.version is False
    assert loaded.args == expect["positional"]
    for key, want in expect["values"].items():
        assert (loaded.conf.get(key), loaded.conf.source(key)) == (want["value"], want["source"]), key
        assert loaded.conf.contains(key) is (want["source"] != "default")
    for key in expect.get("unset", []):
        assert loaded.conf.getOption(key) is None
        assert loaded.conf.source(key) is None
        assert loaded.conf.contains(key) is False


@pytest.mark.parametrize("case_id", ids("notSet"))
def test_not_set(case_id):
    case = CASES[case_id]
    conf = OpenTsxConf(FIXTURE)
    for read in (conf.get, lambda key: typed(conf, key)):
        with pytest.raises(OpenTsxConfError) as raised:
            read(case["key"])
        assert str(raised.value) == case["expect"]["error"]
    assert conf.getOption(case["key"]) is None
    assert conf.get(case["key"], "fallback") == "fallback"


@pytest.mark.parametrize("case_id", ids("api"))
def test_api_names(case_id):
    names = sorted(name for name in vars(OpenTsxConf) if not name.startswith("_"))
    assert names == CASES[case_id]["names"]


@pytest.mark.parametrize("case_id", ids("debugString"))
def test_debug_string(case_id, tmp_path):
    case = CASES[case_id]
    loaded = run_load(case, tmp_path)
    assert loaded.conf.toDebugString() == case["expect"]["text"]
    assert loaded.startLine() == case["expect"]["line"]


def test_registry_copy_is_identical_to_the_java_resource():
    assert PYTHON_REGISTRY.read_bytes() == JAVA_REGISTRY.read_bytes()
    assert Registry.shipped().text.encode("utf-8") == JAVA_REGISTRY.read_bytes()


def test_conf_commands_are_the_loads_conf_of_the_java_registry():
    source = (REPO / "opentsx-conf/src/main/java/org/opentsx/conf/Registry.java").read_text(encoding="utf-8")
    declared = re.search(r"LOADS_CONF = List\.of\(([^)]*)\);", source).group(1)
    assert CONF_COMMANDS == tuple(re.findall(r'"([^"]+)"', declared))


def registry_rules(registry):
    document = json.loads(registry.text)
    keys = [entry["key"] for entry in document["keys"]]
    commands = [command["name"] for command in document["commands"]]
    assert commands == sorted(commands)
    assert keys == sorted(keys)
    for key in keys:
        assert Registry.validKey(key), key
        assert not any(other.startswith(key + ".") for other in keys), key
    names = {}
    for entry in document["keys"]:
        for name in [Registry.envName(entry["key"])] + entry.get("env", []):
            assert name not in names, (name, entry["key"], names.get(name))
            assert name not in ("OPENTSX_HOME", "OPENTSX_CONF_DIR", "OPENTSX_EXTRA_ARGS"), name
            names[name] = entry["key"]
    for command in commands:
        owners = {}
        for entry in document["keys"]:
            if command in entry["commands"]:
                for flag in ([entry["flag"]] if "flag" in entry else []) + entry.get("legacyFlags", []):
                    assert owners.setdefault(flag, entry["key"]) == entry["key"], (command, flag)
                    assert flag not in ("conf", "c", "properties-file", "help", "h", "version", "output"), flag
    for entry in document["keys"]:
        if entry["secret"]:
            assert "default" not in entry, entry["key"]
        if "default" in entry:
            assert _parse(entry, entry["default"], entry["key"]) == entry["default"], entry["key"]
        assert set(entry["commands"]) <= set(commands), entry["key"]
    for item in document["keys"] + document["commands"]:
        assert item["doc"][:1].isupper() and item["doc"].endswith("."), item["doc"]


def test_shipped_registry_follows_the_rules():
    registry_rules(Registry.shipped())


def test_fixture_registry_follows_the_rules():
    registry_rules(FIXTURE)


def test_precedence_of_the_six_sources_on_the_shipped_registry(tmp_path):
    (tmp_path / "conf").mkdir()
    (tmp_path / "conf/opentsx-defaults.conf").write_text("opentsx.stats.sketchAlpha 0.05\n", encoding="utf-8")
    (tmp_path / "job.conf").write_text("opentsx.stats.sketchAlpha 0.04\n", encoding="utf-8")
    key = "opentsx.stats.sketchAlpha"
    conf_dir = str(tmp_path / "conf")
    job = ["--properties-file", str(tmp_path / "job.conf")]
    env = {"OPENTSX_STATS_SKETCH_ALPHA": "0.03"}
    rows = [
        (["stats", "--alpha", "0.02"] + job, env, conf_dir, True, ("0.06", "code")),
        (["stats", "--alpha", "0.02"] + job, env, conf_dir, False, ("0.02", "commandLine")),
        (["stats"] + job, env, conf_dir, False, ("0.03", "environment")),
        (["stats"] + job, {}, conf_dir, False, ("0.04", "propertiesFile")),
        (["stats"], {}, conf_dir, False, ("0.05", "defaultsFile")),
        (["stats"], {}, None, False, ("0.01", "default")),
    ]
    for args, environment, directory, in_code, want in rows:
        conf = OpenTsxConf.load(args, environment, directory).conf
        if in_code:
            conf.set(key, "0.06")
        assert (conf.get(key), conf.source(key)) == want
        assert conf.getDouble(key) == float(want[0])


def test_legacy_environment_name_loses_against_the_canonical_name():
    conf = OpenTsxConf.load(
        ["stats"], {"KAFSCALE_BOOTSTRAP": "legacy:9092", "OPENTSX_KAFSCALE_BOOTSTRAP": "canonical:9092"}, None
    ).conf
    assert conf.get("opentsx.kafscale.bootstrap") == "canonical:9092"


def test_log_level_sets_the_level_of_the_opentsx_logger():
    logger = logging.getLogger("opentsx")
    logger.setLevel(logging.NOTSET)
    OpenTsxConf.load(["stats"], {}, None)
    assert logger.level == logging.NOTSET
    OpenTsxConf.load(["stats"], {"OPENTSX_LOG_LEVEL": "debug"}, None)
    assert logger.level == logging.DEBUG
    levels = {"error": logging.ERROR, "warn": logging.WARNING, "info": logging.INFO, "trace": 5}
    for text, level in levels.items():
        OpenTsxConf.load(["stats", "--conf", f"opentsx.log.level={text}"], {}, None)
        assert logger.level == level, text


def test_secrets_are_masked_but_returned_to_code():
    conf = OpenTsxConf.load(["market", "prices"], {"ENTSOE_TOKEN": "t0ken-value"}, None).conf
    assert conf.get("opentsx.market.entsoeToken") == "t0ken-value"
    assert ("opentsx.market.entsoeToken", "t0ken-value") in conf.getAll()
    assert "opentsx.market.entsoeToken=****** (environment)\n" in conf.toDebugString()
    assert "t0ken-value" not in conf.toDebugString()


@pytest.mark.parametrize(
    "args",
    [
        ["metadata", "--token", "t0ken-value"],
        ["metadata", "--conf", "opentsx.openmetadata.token=t0ken-value"],
        ["retrieval-server", "--dag-run-token", "t0ken-value"],
    ],
)
def test_secret_on_the_command_line_is_refused_without_the_value(args):
    with pytest.raises(OpenTsxConfError) as raised:
        OpenTsxConf.load(args, {}, None)
    assert "secret keys are not accepted on the command line" in str(raised.value)
    assert "t0ken-value" not in str(raised.value)


def test_setters_chain_and_set_all_takes_pairs():
    conf = OpenTsxConf().setAll([("opentsx.domain", "solar"), ("opentsx.kafscale.partitions", "3")])
    assert conf.setIfMissing("opentsx.domain", "wind").get("opentsx.domain") == "solar"
    assert conf.getInt("opentsx.kafscale.partitions") == 3
    assert conf.contains("opentsx.domain") and not conf.contains("opentsx.vectors.state")


def test_set_all_takes_a_mapping_and_load_takes_a_path(tmp_path):
    assert OpenTsxConf().setAll({"opentsx.domain": "solar"}).get("opentsx.domain") == "solar"
    (tmp_path / "opentsx-defaults.conf").write_text("opentsx.domain solar\n", encoding="utf-8")
    conf = OpenTsxConf.load(["stats"], {}, tmp_path).conf
    assert (conf.get("opentsx.domain"), conf.source("opentsx.domain")) == ("solar", "defaultsFile")


def test_set_takes_text_only():
    with pytest.raises(TypeError):
        OpenTsxConf().set("opentsx.kafscale.partitions", 3)


def test_typed_getter_must_match_the_type():
    conf = OpenTsxConf()
    with pytest.raises(OpenTsxConfError) as raised:
        conf.getInt("opentsx.domain")
    assert str(raised.value) == "opentsx.domain: has type string; read it with get"
    with pytest.raises(OpenTsxConfError) as raised:
        conf.getDuration("opentsx.stats.windows")
    assert str(raised.value) == "opentsx.stats.windows: has type list of duration; read it with getDurationList"
    assert conf.getList("opentsx.stats.windows") == ["PT10M", "PT1H"]
    assert conf.getDurationList("opentsx.stats.windows") == [timedelta(minutes=10), timedelta(hours=1)]


def test_unknown_key_fails_every_accessor():
    conf = OpenTsxConf()
    for read in (conf.get, conf.getOption, conf.contains, conf.source, conf.getInt):
        with pytest.raises(OpenTsxConfError) as raised:
            read("opentsx.domian")
        assert str(raised.value) == "opentsx.domian: unknown key; did you mean opentsx.domain?"


def test_long_digit_strings_are_grammar_errors():
    conf = OpenTsxConf()
    for key, text in (("opentsx.kafscale.partitions", "1" * 5000), ("opentsx.kafscale.checkTimeout", "1" * 5000 + "s")):
        with pytest.raises(OpenTsxConfError) as raised:
            conf.set(key, text)
        assert " is not a" in str(raised.value)


def test_yaml_without_the_extra_names_the_extra(tmp_path, monkeypatch):
    monkeypatch.setitem(sys.modules, "yaml", None)
    path = tmp_path / "job.yaml"
    path.write_text("opentsx:\n  domain: wind\n", encoding="utf-8")
    with pytest.raises(OpenTsxConfError) as raised:
        readFile(str(path), FIXTURE)
    assert str(raised.value) == f'{path}: YAML files need the extra opentsx[yaml]; run pip install "opentsx[yaml]"'
    (tmp_path / "conf").mkdir()
    (tmp_path / "conf/opentsx-defaults.yaml").write_text("opentsx.domain: wind\n", encoding="utf-8")
    with pytest.raises(OpenTsxConfError) as raised:
        OpenTsxConf.load(["serve"], {}, str(tmp_path / "conf"), registry=FIXTURE)
    assert str(raised.value) == f"{tmp_path}/conf/opentsx-defaults.yaml: {YAML_EXTRA}"


def test_file_that_is_not_utf8_is_a_configuration_error(tmp_path):
    path = tmp_path / "job.conf"
    path.write_bytes(b"opentsx.domain w\xffnd\n")
    with pytest.raises(OpenTsxConfError) as raised:
        readFile(str(path), FIXTURE)
    assert str(raised.value) == f"{path}: not valid UTF-8; save the file as UTF-8"
