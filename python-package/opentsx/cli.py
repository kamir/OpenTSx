"""The opentsx command of the Python runtime: config list, config show, config get and version."""

import json
import os
import sys
from importlib import metadata
from typing import Mapping

from opentsx.conf.conf import (
    MASK,
    OUTPUT_COMMANDS,
    OpenTsxConf,
    OpenTsxConfError,
    Registry,
    _arguments,
    _not_set,
    _type_text,
)

COMMON_OPTIONS = [
    ("-c, --conf <key>=<value>", "set a key on the command line; repeatable"),
    ("--properties-file <path>", "read keys from a properties file or a YAML file (.yaml, .yml)"),
    ("-h, --help", "print this help and exit"),
    ("--version", "print the version and exit"),
]
OUTPUT_OPTION = ("--output <text|json>", "print a table or one line of JSON")
WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full"


def main(argv: list[str] | None = None, env: Mapping[str, str] | None = None, registry: Registry | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    env = os.environ if env is None else env
    registry = Registry.shipped() if registry is None else registry
    try:
        text = _run(argv, env, registry)
    except OpenTsxConfError as e:
        sys.stderr.write(f"opentsx: {e}\n")
        return 2
    except Exception as e:
        sys.stderr.write(f"opentsx: {e}\n")
        return 1
    try:
        if sys.stdout is None:
            raise OSError("standard output is closed")
        sys.stdout.write(text)
        sys.stdout.flush()
    except OSError:
        _drop_stdout()
        sys.stderr.write(f"opentsx: {WRITE_FAILED}\n")
        return 1
    return 0


def _drop_stdout() -> None:
    """Points stdout at the null device, so that the flush at interpreter exit cannot fail a second time."""
    if sys.stdout is None or not hasattr(sys.stdout, "fileno"):
        return
    null = os.open(os.devnull, os.O_WRONLY)
    os.dup2(null, sys.stdout.fileno())
    os.close(null)


def _run(argv: list[str], env: Mapping[str, str], registry: Registry) -> str:
    loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"), registry=registry)
    if loaded.help:
        return _help(registry, loaded.command)
    if loaded.version or loaded.command == "version":
        return f"opentsx {metadata.version('opentsx')} (python)\n"
    conf = loaded.conf
    output = _arguments(registry, list(argv)).output
    if loaded.command == "config list":
        if output == "json":
            return registry.text
        rows = [("KEY", "TYPE", "DEFAULT", "FLAG", "ENV")]
        for key in sorted(registry.entries):
            entry = registry.entries[key]
            flags = [] if entry["secret"] else ["--" + flag for flag in _flags(entry)]
            names = [Registry.envName(key)] + entry.get("env", [])
            rows.append((key, _type_text(entry), entry.get("default", "-"), ", ".join(flags) or "-", ", ".join(names)))
        return _table(rows)
    if loaded.command == "config show":
        shown = [(key, _masked(registry, key, value), conf.source(key)) for key, value in conf.getAll()]
        if output == "json":
            values = [{"key": key, "value": value, "source": source} for key, value, source in shown]
            document = {"schema": "opentsx.config.show.v1", "values": values}
            return json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n"
        return _table([("KEY", "VALUE", "SOURCE")] + shown)
    if loaded.command == "config get":
        key = loaded.args[0]
        value = conf.getOption(key)
        if value is None:
            raise _not_set(registry.entries[key], with_flag=False)
        return _masked(registry, key, value) + "\n"
    command = loaded.command
    raise OpenTsxConfError(f"{command}: runs only in the Java runtime; use bin/opentsx or the opentsx-tools image")


def _masked(registry: Registry, key: str, value: str) -> str:
    return MASK if registry.entries[key]["secret"] else value


def _flags(entry: dict) -> list[str]:
    return ([entry["flag"]] if "flag" in entry else []) + entry.get("legacyFlags", [])


def _table(rows: list[tuple[str, ...]]) -> str:
    widths = [max(len(row[i]) for row in rows) for i in range(len(rows[0]) - 1)]
    lines = ["  ".join([cell.ljust(width) for cell, width in zip(row, widths)] + [row[-1]]) for row in rows]
    return "\n".join(lines) + "\n"


def _help(registry: Registry, command: str | None) -> str:
    options = list(COMMON_OPTIONS)
    if command is None:
        names = sorted(registry.commands)
        width = max(len(name) for name in names)
        lines = ["usage: opentsx <command> [options] [args]", "", "commands:"]
        lines += [f"  {name.ljust(width)}  {registry.commands[name]['doc']}" for name in names]
        ending = ["", "run opentsx <command> --help for the keys of a command"]
    else:
        entry = registry.commands[command]
        arguments = "".join(f" <{name}>" for name in entry.get("args", []))
        lines = [f"usage: opentsx {command}{arguments} [options]", entry["doc"]]
        keys = [registry.entries[key] for key in sorted(registry.entries)]
        read = [key for key in keys if command in key["commands"]]
        if read:
            lines += ["", "keys:"]
        for key in read:
            lines += ["  " + "  ".join(_key_parts(key)), "      " + key["doc"]]
        ending = []
        if command in OUTPUT_COMMANDS:
            options.append(OUTPUT_OPTION)
    lines += ["", "common options:"] + [f"  {name.ljust(26)}  {doc}" for name, doc in options] + ending
    return "\n".join(lines) + "\n"


def _key_parts(entry: dict) -> list[str]:
    parts = [entry["key"], _type_text(entry) + (" = " + entry["default"] if "default" in entry else "")]
    if "values" in entry:
        parts.append("one of " + "|".join(entry["values"]))
    if "unit" in entry:
        parts.append("unit " + entry["unit"])
    if _flags(entry) and not entry["secret"]:
        parts.append(", ".join("--" + flag for flag in _flags(entry)))
    parts.append(", ".join([Registry.envName(entry["key"])] + entry.get("env", [])))
    if entry["secret"]:
        parts.append("secret: environment or file only")
    return parts


if __name__ == "__main__":
    sys.exit(main())
