"""opentsx command of the Python runtime: exit codes, stdout and stderr, and the show vectors of conf.v1.json."""

import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
from importlib import metadata

import pytest

from opentsx import cli
from opentsx.conf import Registry

REPO = pathlib.Path(__file__).resolve().parents[2]
VECTORS = json.loads((REPO / "docs/datamodel-2026/testvectors/conf.v1.json").read_text(encoding="utf-8"))
FIXTURE = Registry(json.dumps(VECTORS["registry"]))
SHOW = [case for case in VECTORS["cases"] if case["kind"] == "show"]
SPEC = (REPO / "EVOLUTION/TASK-020-configuration-and-cli.md").read_text(encoding="utf-8")


def run(capsys, argv, env=None, registry=None):
    code = cli.main(argv, {} if env is None else env, registry)
    out, err = capsys.readouterr()
    return code, out, err


@pytest.mark.parametrize("case", SHOW, ids=[case["id"] for case in SHOW])
def test_show_vectors(case, capsys):
    code, out, err = run(capsys, case["args"], case.get("env", {}), FIXTURE)
    assert code == case["expect"]["exit"]
    assert out == case["expect"].get("stdout", "")
    assert err == case["expect"].get("stderr", "")


def test_config_list_prints_every_key(capsys):
    code, out, err = run(capsys, ["config", "list"])
    lines = out.splitlines()
    assert (code, err) == (0, "")
    assert lines[0].split() == ["KEY", "TYPE", "DEFAULT", "FLAG", "ENV"]
    assert [line.split("  ")[0] for line in lines[1:]] == sorted(Registry.shipped().entries)
    rows = {cells[0]: cells[1:] for cells in (re.split(" {2,}", line) for line in lines[1:])}
    assert rows["opentsx.stats.windows"] == ["list of duration", "PT10M,PT1H", "--windows", "OPENTSX_STATS_WINDOWS"]
    assert rows["opentsx.kafscale.bootstrap"] == [
        "string",
        "-",
        "--kafscale, --bootstrap",
        "OPENTSX_KAFSCALE_BOOTSTRAP, KAFSCALE_BOOTSTRAP",
    ]
    assert rows["opentsx.openmetadata.token"] == ["string", "-", "-", "OPENTSX_OPENMETADATA_TOKEN, OPENMETADATA_JWT"]


def test_config_list_json_is_the_registry_file(capsys):
    code, out, err = run(capsys, ["config", "list", "--output", "json"])
    assert (code, err) == (0, "")
    assert out.encode("utf-8") == (REPO / "python-package/opentsx/conf/opentsx.config.v1.json").read_bytes()


def test_config_show_names_the_source(capsys, tmp_path):
    (tmp_path / "opentsx-defaults.conf").write_text("opentsx.archive.dir /srv/history\n", encoding="utf-8")
    env = {"OPENTSX_CONF_DIR": str(tmp_path), "OPENTSX_STATS_SKETCH_ALPHA": "0.03"}
    code, out, err = run(capsys, ["config", "show", "--conf", "opentsx.domain=solar"], env)
    assert (code, err) == (0, "")
    rows = {line.split()[0]: line.split()[1:] for line in out.splitlines()[1:]}
    assert rows["opentsx.domain"] == ["solar", "commandLine"]
    assert rows["opentsx.stats.sketchAlpha"] == ["0.03", "environment"]
    assert rows["opentsx.archive.dir"] == ["/srv/history", "defaultsFile"]
    assert rows["opentsx.retrieval.port"] == ["8080", "default"]
    assert "opentsx.kafscale.bootstrap" not in rows


def test_secrets_never_reach_the_output(capsys):
    env = {"ENTSOE_TOKEN": "t0ken-value", "OPENTSX_RETRIEVAL_DAG_RUN_TOKEN": "t0ken-value"}
    for argv in (
        ["config", "show"],
        ["config", "show", "--output", "json"],
        ["config", "get", "opentsx.market.entsoeToken"],
        ["retrieval-server", "--dag-run-token", "t0ken-value"],
        ["config", "list"],
        ["market", "prices", "--help"],
    ):
        code, out, err = run(capsys, argv, env)
        assert "t0ken-value" not in out + err, argv
    code, out, err = run(capsys, ["config", "get", "opentsx.retrieval.dagRunToken"], env)
    assert (code, out, err) == (0, "******\n", "")


def test_unknown_key_exits_2_with_one_line_on_stderr(capsys):
    code, out, err = run(capsys, ["config", "show", "--conf", "opentsx.domian=x"])
    assert (code, out, err) == (2, "", "opentsx: opentsx.domian: unknown key; did you mean opentsx.domain?\n")


@pytest.mark.parametrize(
    "argv, message",
    [
        ([], "<command>: missing; run opentsx --help for the commands"),
        (["config"], "config: missing subcommand; expected one of config get, config list, config show"),
        (["config", "shwo"], "config shwo: unknown command; did you mean config show?"),
        (["config", "get"], "config get: missing argument <key>; expected opentsx config get <key>"),
        (["config", "list", "--output"], "--output: missing value; expected --output <text|json>"),
        (
            ["config", "get", "opentsx.domain", "--output", "json"],
            "--output: unknown option of config get; run opentsx config get --help for the options",
        ),
        (
            ["stats", "--alpha", "abc"],
            '--alpha: "abc" is not a double; expected a finite decimal number such as 0.01 or 1e-3',
        ),
        (["stats", "--kafscale"], "--kafscale: missing value; expected --kafscale <string>"),
        (["stats", "--delay-ms", "5"], "--delay-ms: unknown option of stats; run opentsx stats --help for the options"),
        (
            ["stats", "-c", "opentsx.market.fetchDelayMs=5"],
            "opentsx.market.fetchDelayMs: not read by stats; run opentsx stats --help for its keys",
        ),
        (["stats"], "stats: runs only in the Java runtime; use bin/opentsx or the opentsx-tools image"),
        (["history-twin", "twin"], "history-twin twin: runs only in the Java runtime; use bin/opentsx or the opentsx-tools image"),
    ],
)
def test_usage_errors_exit_2(capsys, argv, message):
    code, out, err = run(capsys, argv)
    assert (code, out, err) == (2, "", f"opentsx: {message}\n")


def test_malformed_environment_value_exits_2(capsys):
    code, out, err = run(capsys, ["config", "show"], {"OPENTSX_RETRIEVAL_PORT": "80a"})
    expected = (
        'opentsx: OPENTSX_RETRIEVAL_PORT: "80a" is not an int; '
        "expected a whole number from -2147483648 to 2147483647\n"
    )
    assert (code, out, err) == (2, "", expected)


def test_missing_configuration_directory_exits_2(capsys, tmp_path):
    missing = str(tmp_path / "missing")
    code, out, err = run(capsys, ["config", "show"], {"OPENTSX_CONF_DIR": missing})
    assert code == 2 and out == ""
    assert err == (
        f"opentsx: {missing}: not a directory; set OPENTSX_CONF_DIR to the directory that holds "
        "opentsx-defaults.conf or opentsx-defaults.yaml\n"
    )


def test_version(capsys):
    expected = f"opentsx {metadata.version('opentsx')} (python)\n"
    assert run(capsys, ["version"]) == (0, expected, "")
    assert run(capsys, ["--version"]) == (0, expected, "")
    assert run(capsys, ["config", "show", "--version"]) == (0, expected, "")


def test_runtime_failure_exits_1(capsys, monkeypatch):
    def missing(name):
        raise metadata.PackageNotFoundError(name)

    monkeypatch.setattr(cli.metadata, "version", missing)
    code, out, err = run(capsys, ["version"])
    assert (code, out) == (1, "")
    assert err == "opentsx: No package metadata was found for opentsx\n"


WRITE_FAILED = "opentsx: stdout: cannot write the output; check that standard output is open and not full\n"
MAIN = "import sys; from opentsx.cli import main; sys.exit(main())"


def test_stdout_that_cannot_be_written_exits_1():
    read_end, write_end = os.pipe()
    os.close(read_end)
    try:
        done = subprocess.run([sys.executable, "-c", MAIN, "config", "list"], stdout=write_end, stderr=subprocess.PIPE)
    finally:
        os.close(write_end)
    assert (done.returncode, done.stderr.decode()) == (1, WRITE_FAILED)


def test_closed_stdout_exits_1():
    command = f'exec "{sys.executable}" -c "{MAIN}" config get opentsx.domain >&-'
    done = subprocess.run(["sh", "-c", command], stderr=subprocess.PIPE)
    assert (done.returncode, done.stderr.decode()) == (1, WRITE_FAILED)


def test_help_wins_over_an_unknown_command(capsys):
    code, out, err = run(capsys, ["config", "--help"])
    assert (code, err) == (0, "")
    assert out.startswith("usage: opentsx <command> [options] [args]\n")


def test_dangling_defaults_file_is_an_error(capsys, tmp_path):
    (tmp_path / "opentsx-defaults.conf").symlink_to(tmp_path / "missing.conf")
    code, out, err = run(capsys, ["config", "show"], {"OPENTSX_CONF_DIR": str(tmp_path)})
    assert (code, out, err) == (2, "", f"opentsx: {tmp_path}/opentsx-defaults.conf: no such file; give an existing file\n")


def test_deep_yaml_is_a_configuration_error(capsys, tmp_path):
    path = tmp_path / "deep.yaml"
    path.write_text("a: " + "{a: " * 2000 + "x" + "}" * 2000 + "\n", encoding="utf-8")
    code, out, err = run(capsys, ["config", "show", "--properties-file", str(path)])
    if importlib.util.find_spec("yaml") is None:
        problem = 'YAML files need the extra opentsx[yaml]; run pip install "opentsx[yaml]"'
    else:
        problem = "not valid YAML; nesting deeper than 100 levels"
    assert (code, out, err) == (2, "", f"opentsx: {path}: {problem}\n")


def test_command_help_matches_the_specification(capsys):
    start = SPEC.index("usage: opentsx stats [options]")
    expected = SPEC[start : SPEC.index("```", start)]
    assert run(capsys, ["stats", "--help"]) == (0, expected, "")
    assert run(capsys, ["stats", "--bogus", "-h"]) == (0, expected, "")


def test_help_lists_the_commands_and_marks_secrets(capsys):
    code, out, err = run(capsys, ["--help"])
    assert (code, err) == (0, "")
    assert out.startswith("usage: opentsx <command> [options] [args]\n\ncommands:\n")
    assert out.endswith("\nrun opentsx <command> --help for the keys of a command\n")
    for name, command in Registry.shipped().commands.items():
        assert f"  {name} " in out and command["doc"] in out
    code, out, err = run(capsys, ["metadata", "--help"])
    token = next(line for line in out.splitlines() if line.startswith("  opentsx.openmetadata.token"))
    variables = "OPENTSX_OPENMETADATA_TOKEN, OPENMETADATA_JWT"
    assert token == f"  opentsx.openmetadata.token  string  {variables}  secret: environment or file only"
    code, out, err = run(capsys, ["config", "show", "--help"])
    assert out.endswith("  --version                   print the version and exit\n"
                        "  --output <text|json>        print a table or one line of JSON\n")


def test_no_command_notes_its_own_flags(capsys):
    code, out, err = run(capsys, ["kafscale-check", "--help"])
    assert (code, err) == (0, "")
    assert "In the Java runtime" not in out
    for command in (["market", "prices"], ["history-twin", "twin"], ["stats"]):
        code, out, err = run(capsys, command + ["--help"])
        assert (code, err) == (0, "")
        assert "In the Java runtime" not in out, command
