"""OpenTSx configuration: key registry, six sources, one type grammar (EVOLUTION/TASK-020-configuration-and-cli.md)."""

import functools
import json
import logging
import math
import os
import re
from dataclasses import dataclass
from datetime import timedelta
from importlib import resources
from typing import Iterable, Mapping, NamedTuple

MASK = "******"
OUTPUT_COMMANDS = ("config list", "config show")
EVERY_KEY_COMMANDS = ("config show", "config get")
CONF_COMMANDS = (
    "boat",
    "config get",
    "config list",
    "config show",
    "dwd house",
    "dwd mosmix",
    "dwd obs",
    "dwd stations",
    "house",
    "metadata",
    "modbus check",
    "modbus poll",
    "modbus read",
    "modbus simulate",
    "retrieval-server",
    "stats",
    "stats-flink",
    "studio-export",
    "twin",
    "twin-coupling",
    "twin-live bridge",
    "twin-live demo",
    "twin-live publish",
    "twin-schemas",
    "vectors",
    "vectors deploy",
    "vectors run",
    "vectors stop",
    "version",
    "windpark-sim",
)
DEFAULTS_FILES = ("opentsx-defaults.conf", "opentsx-defaults.yaml")
UTF8_ALLOWED = "pass UTF-8 text in a UTF-8 locale such as LANG=C.UTF-8"
LINE_ALLOWED = "write key value or key = value"
MAX_YAML_DEPTH = 100

_KEY = re.compile(r"opentsx(\.[a-z][a-z0-9]*([A-Z][a-z0-9]+)*)+")
_WORD = re.compile(r"[a-z0-9]+|[A-Z][a-z0-9]+")
_INT = re.compile(r"0|-?[1-9][0-9]{0,18}")
_DOUBLE = re.compile(r"-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?")
_N = r"(0|[1-9][0-9]{0,19})"
_ISO = re.compile(rf"P(?:{_N}D)?(?:T(?:{_N}H)?(?:{_N}M)?(?:{_N}(?:\.([0-9]{{1,3}}))?S)?)?")
_UNIT = re.compile(rf"{_N}(ms|s|min|m|h|d)")
_UNIT_MS = {"ms": 1, "s": 1000, "m": 60000, "min": 60000, "h": 3600000, "d": 86400000}
_MAX_MS = 999999999 * 86400000
_RANGE = {"int": (-(2**31), 2**31 - 1), "long": (-(2**63), 2**63 - 1)}
_EXPECTED = {
    "int": "expected a whole number from -2147483648 to 2147483647",
    "long": "expected a whole number from -9223372036854775808 to 9223372036854775807",
    "double": "expected a finite decimal number such as 0.01 or 1e-3",
    "boolean": "expected true or false",
    "duration": "expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d"
    " such as 10m, at most 999999999 days",
    "list": "expected comma-separated items such as a,b",
}
_GETTERS = {
    "int": "getInt",
    "long": "getLong",
    "double": "getDouble",
    "boolean": "getBoolean",
    "duration": "getDuration",
}
_UNSET = object()
_LOG_LEVELS = {
    "error": logging.ERROR,
    "warn": logging.WARNING,
    "info": logging.INFO,
    "debug": logging.DEBUG,
    "trace": 5,
}


class OpenTsxConfError(ValueError):
    pass


def _error(subject: str, problem: str, allowed: str) -> OpenTsxConfError:
    return OpenTsxConfError(f"{subject}: {problem}; {allowed}")


def _levenshtein(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def _near(word: str, candidates: Iterable[str]) -> str | None:
    best, best_distance = None, 3
    for candidate in sorted(candidates):
        distance = _levenshtein(word, candidate)
        if distance < best_distance:
            best, best_distance = candidate, distance
    return best


def _utf8(text: str) -> bool:
    return not any(c == "\ufffd" or "\ud800" <= c <= "\udfff" for c in text)


def _type_text(entry: dict) -> str:
    return entry["type"] + (" of " + entry["items"] if "items" in entry else "")


def _getter(entry: dict) -> str:
    if entry["type"] == "list":
        return "getDurationList" if entry.get("items") == "duration" else "getList"
    return _GETTERS.get(entry["type"], "get")


def _scalar(entry: dict, kind: str, text: str, subject: str, item: bool):
    shown = ("item " if item else "") + '"' + (MASK if entry["secret"] else text) + '"'
    if kind in ("string", "path"):
        return text
    if kind in _RANGE:
        low, high = _RANGE[kind]
        if _INT.fullmatch(text) and low <= int(text) <= high:
            return int(text)
        raise _error(subject, f"{shown} is not {'an' if kind == 'int' else 'a'} {kind}", _EXPECTED[kind])
    if kind == "double":
        if _DOUBLE.fullmatch(text) and math.isfinite(float(text)):
            return float(text)
        raise _error(subject, f"{shown} is not a double", _EXPECTED["double"])
    if kind == "boolean":
        if text.isascii() and text.lower() in ("true", "false"):
            return text.lower() == "true"
        raise _error(subject, f"{shown} is not a boolean", _EXPECTED["boolean"])
    if kind == "duration":
        millis = None
        iso = _ISO.fullmatch(text)
        unit = _UNIT.fullmatch(text)
        if iso and any(iso.groups()[:4]) and not text.endswith("T"):
            days, hours, minutes, seconds = (int(group or 0) for group in iso.groups()[:4])
            fraction = (iso.group(5) or "0").ljust(3, "0")
            millis = ((days * 24 + hours) * 60 + minutes) * 60000 + seconds * 1000 + int(fraction)
        elif unit:
            millis = int(unit.group(1)) * _UNIT_MS[unit.group(2)]
        if millis is None or millis > _MAX_MS:
            raise _error(subject, f"{shown} is not a duration", _EXPECTED["duration"])
        return millis
    if kind == "enum":
        if text in entry["values"]:
            return text
        raise _error(subject, f"{shown} is not allowed", "expected one of " + ", ".join(entry["values"]))
    raise AssertionError(f"{entry['key']}: unknown type {kind}")


def _parse(entry: dict, raw: str, subject: str) -> str:
    text = raw.strip(" \t")
    if text == "":
        raise _error(subject, "empty value", "set a value or leave the key unset")
    if any(c < " " or c == "\x7f" for c in text):
        raise _error(subject, "value contains a control character", "remove it")
    if not _utf8(text):
        raise _error(subject, "value is not valid UTF-8", UTF8_ALLOWED)
    if entry["type"] != "list":
        _scalar(entry, entry["type"], text, subject, False)
        return text
    items = [item.strip(" \t") for item in text.split(",")]
    if "" in items:
        shown = MASK if entry["secret"] else text
        raise _error(subject, f'"{shown}" has an empty item', _EXPECTED["list"])
    for item in items:
        _scalar(entry, entry.get("items", "string"), item, subject, True)
    return ",".join(items)


class Registry:
    """The key registry opentsx.config.v1; `text` keeps the file as read."""

    def __init__(self, text: str):
        self.text = text
        document = json.loads(text)
        self.entries = {entry["key"]: entry for entry in document["keys"]}
        self.commands = {command["name"]: command for command in document["commands"]}

    @staticmethod
    def shipped() -> "Registry":
        return _shipped()

    @staticmethod
    def validKey(key: str) -> bool:
        return _KEY.fullmatch(key) is not None

    @staticmethod
    def envName(key: str) -> str:
        words = [word.upper() for segment in key.split(".")[1:] for word in _WORD.findall(segment)]
        return "OPENTSX_" + "_".join(words)

    def entry(self, key: str, subject: str | None = None) -> dict:
        found = self.entries.get(key)
        if found is not None:
            return found
        subject = key if subject is None else subject
        near = _near(key, self.entries)
        if key.startswith("opentsx."):
            hint = f"did you mean {near}?" if near else "run opentsx config list for the known keys"
            raise _error(subject, "unknown key", hint)
        hint = f"did you mean {near}?" if near else "expected a key that starts with opentsx."
        raise _error(subject, "not an OpenTSx key", hint)

    def flags(self, command: str) -> dict:
        found = {}
        for entry in self.entries.values():
            if command in entry["commands"]:
                for flag in ([entry["flag"]] if "flag" in entry else []) + entry.get("legacyFlags", []):
                    found[flag] = entry
        return found

    def known(self, key: str) -> bool:
        return key in self.entries

    def reads(self, command: str, key: str) -> bool:
        return command in EVERY_KEY_COMMANDS or command in self.entries[key]["commands"]


@functools.cache
def _shipped() -> Registry:
    data = resources.files(__package__).joinpath("opentsx.config.v1.json").read_bytes()
    return Registry(data.decode("utf-8"))


def _not_set(entry: dict, with_flag: bool = True) -> OpenTsxConfError:
    key, variable = entry["key"], Registry.envName(entry["key"])
    if entry["secret"]:
        ways = f"{variable} or a properties file"
    elif with_flag and "flag" in entry:
        ways = f"--{entry['flag']}, --conf {key}=<value> or {variable}"
    else:
        ways = f"--conf {key}=<value> or {variable}"
    return _error(key, "not set", f"set it with {ways}")


def _secret_on_command_line(subject: str, entry: dict) -> OpenTsxConfError:
    key = entry["key"]
    variables = " or ".join([Registry.envName(key), *entry.get("env", [])])
    return _error(
        subject,
        "secret keys are not accepted on the command line",
        f"set {variables} or put {key} in a properties file",
    )


class OpenTsxConf:
    """Configuration values of one run; method names follow the Java class org.opentsx.conf.OpenTsxConf."""

    SOURCES = ("code", "commandLine", "environment", "propertiesFile", "defaultsFile", "default")

    def __init__(self, registry: Registry | None = None):
        self._registry = Registry.shipped() if registry is None else registry
        self._values: dict[str, tuple[str, str]] = {}

    @staticmethod
    def load(
        args: list[str], env: Mapping[str, str], confDir: str | os.PathLike | None, registry: Registry | None = None
    ) -> "Loaded":
        registry = Registry.shipped() if registry is None else registry
        confDir = None if confDir is None else os.fspath(confDir)
        parsed = _arguments(registry, list(args))
        if parsed.help or parsed.version:
            return Loaded(parsed.command, None, [], parsed.help, parsed.version)
        properties_file = parsed.propertiesFile
        layers = [
            (parsed.values, "commandLine"),
            (_environment(registry, env), "environment"),
            (dict(readFile(properties_file, registry)) if properties_file is not None else {}, "propertiesFile"),
            (_defaults_file(registry, confDir), "defaultsFile"),
        ]
        conf = OpenTsxConf(registry)
        for values, source in reversed(layers):
            for key, text in values.items():
                conf._values[key] = (text, source)
        level = conf._values.get("opentsx.log.level")
        if level is not None:
            logging.getLogger("opentsx").setLevel(_LOG_LEVELS[level[0]])
        return Loaded(parsed.command, conf, parsed.positional, False, False)

    def registry(self) -> Registry:
        return self._registry

    def set(self, key: str, value: str) -> "OpenTsxConf":
        self._values[key] = (self._checked(key, value), "code")
        return self

    def setIfMissing(self, key: str, value: str) -> "OpenTsxConf":
        text = self._checked(key, value)
        if not self.contains(key):
            self._values[key] = (text, "code")
        return self

    def setAll(self, pairs: Mapping[str, str] | Iterable[tuple[str, str]]) -> "OpenTsxConf":
        for key, value in pairs.items() if isinstance(pairs, Mapping) else pairs:
            self.set(key, value)
        return self

    def _checked(self, key: str, value: str) -> str:
        entry = self._registry.entry(key)
        if not isinstance(value, str):
            raise TypeError(f"{key}: value is {type(value).__name__}; pass the value as str")
        return _parse(entry, value, key)

    def get(self, key: str, defaultValue=_UNSET):
        found = self._lookup(key)
        if found is not None:
            return found[0]
        if defaultValue is _UNSET:
            raise _not_set(self._registry.entries[key])
        return defaultValue

    def getOption(self, key: str) -> str | None:
        found = self._lookup(key)
        return None if found is None else found[0]

    def getInt(self, key: str) -> int:
        return self._typed(key, "getInt")

    def getLong(self, key: str) -> int:
        return self._typed(key, "getLong")

    def getDouble(self, key: str) -> float:
        return self._typed(key, "getDouble")

    def getBoolean(self, key: str) -> bool:
        return self._typed(key, "getBoolean")

    def getDuration(self, key: str) -> timedelta:
        return timedelta(milliseconds=self._typed(key, "getDuration"))

    def getList(self, key: str) -> list[str]:
        return self._typed(key, "getList")

    def getDurationList(self, key: str) -> list[timedelta]:
        return [timedelta(milliseconds=millis) for millis in self._typed(key, "getDurationList")]

    def contains(self, key: str) -> bool:
        self._registry.entry(key)
        return key in self._values

    def getAll(self) -> list[tuple[str, str]]:
        found = ((key, self._lookup(key)) for key in sorted(self._registry.entries))
        return [(key, value[0]) for key, value in found if value is not None]

    def source(self, key: str) -> str | None:
        found = self._lookup(key)
        return None if found is None else found[1]

    def toDebugString(self) -> str:
        return "".join(self._pair(key) + "\n" for key, _ in self.getAll())

    def _pair(self, key: str) -> str:
        text, source = self._lookup(key)
        return f"{key}={MASK if self._registry.entries[key]['secret'] else text} ({source})"

    def _lookup(self, key: str) -> tuple[str, str] | None:
        entry = self._registry.entry(key)
        if key in self._values:
            return self._values[key]
        if "default" in entry:
            return entry["default"], "default"
        return None

    def _typed(self, key: str, getter: str):
        entry = self._registry.entry(key)
        if getter != _getter(entry) and not (getter == "getList" and entry["type"] == "list"):
            raise _error(key, f"has type {_type_text(entry)}", f"read it with {_getter(entry)}")
        text = self.get(key)
        if getter == "getList":
            return text.split(",")
        if getter == "getDurationList":
            return [_scalar(entry, "duration", item, key, True) for item in text.split(",")]
        return _scalar(entry, entry["type"], text, key, False)


@dataclass(frozen=True)
class Loaded:
    command: str | None
    conf: OpenTsxConf | None
    args: list[str]
    help: bool
    version: bool

    def startLine(self) -> str:
        """`starting <command>: key=value (source), ...` for the keys the command reads, secrets masked."""
        registry = self.conf.registry()
        keys = [key for key, _ in self.conf.getAll() if registry.reads(self.command, key)]
        pairs = ", ".join(self.conf._pair(key) for key in keys)
        return f"starting {self.command}" + (f": {pairs}" if pairs else "")


class _Arguments(NamedTuple):
    command: str | None
    help: bool
    version: bool
    values: dict[str, str]
    propertiesFile: str | None
    output: str | None
    positional: list[str]


def _arguments(registry: Registry, args: list[str]) -> _Arguments:
    before_dashes = args[: args.index("--")] if "--" in args else args
    help_wanted = "--help" in before_dashes or "-h" in before_dashes
    if help_wanted or "--version" in before_dashes:
        version = not help_wanted or args[0] == "--version"
        return _Arguments(_longest_command(registry, _words(args)), not version, version, {}, None, None, [])
    command, used = _command(registry, args)
    return _command_line(registry, command, args[used:])


def _words(args: list[str]) -> list[str]:
    words = []
    for token in args:
        if token.startswith("-"):
            break
        words.append(token)
    return words


def _longest_command(registry: Registry, words: list[str]) -> str | None:
    for count in range(len(words), 0, -1):
        if " ".join(words[:count]) in registry.commands:
            return " ".join(words[:count])
    return None


def _command(registry: Registry, args: list[str]) -> tuple[str, int]:
    words = _words(args)
    found = _longest_command(registry, words)
    if found is not None:
        return found, len(found.split(" "))
    if not words:
        raise _error("<command>", "missing", "run opentsx --help for the commands")
    group = sorted(name for name in registry.commands if name.split(" ")[0] == words[0])
    if not group:
        near = _near(words[0], {name.split(" ")[0] for name in registry.commands})
        hint = f"did you mean {near}?" if near else "run opentsx --help for the commands"
        raise _error(words[0], "unknown command", hint)
    if len(words) == 1:
        raise _error(words[0], "missing subcommand", "expected one of " + ", ".join(group))
    two = " ".join(words[:2])
    near = _near(two, group)
    raise _error(two, "unknown command", f"did you mean {near}?" if near else "expected one of " + ", ".join(group))


def _takes_value(rest: list[str], i: int) -> bool:
    return i + 1 < len(rest) and not rest[i + 1].startswith("--")


def _command_line(registry: Registry, command: str, rest: list[str]) -> _Arguments:
    flags = registry.flags(command)
    common = {"conf", "properties-file", "help", "version"} | ({"output"} if command in OUTPUT_COMMANDS else set())
    values: dict[str, str] = {}
    given: dict[str, str] = {}
    positional: list[str] = []
    properties_file = output = None
    i = 0
    while i < len(rest):
        token = rest[i]
        if token == "--":
            positional.extend(rest[i + 1 :])
            break
        if not token.startswith("-"):
            positional.append(token)
            i += 1
            continue
        if token in ("--conf", "-c"):
            if not _takes_value(rest, i):
                raise _error(token, "missing value", f"expected {token} key=value")
            pair = rest[i + 1]
            if "=" not in pair:
                raise _error(token, f'"{pair}" has no "="', f"expected {token} key=value")
            key, raw = pair.split("=", 1)
            entry = registry.entry(key)
            if entry["secret"]:
                raise _secret_on_command_line(key, entry)
            if not registry.reads(command, key):
                raise _error(key, f"not read by {command}", f"run opentsx {command} --help for its keys")
            if key in given:
                raise _error(key, f"set twice on the command line (also by {given[key]})", "set each key once")
            values[key] = _parse(entry, raw, key)
            given[key] = key
            i += 2
            continue
        if token == "--properties-file" or (token == "--output" and "output" in common):
            if not _takes_value(rest, i):
                placeholder = "<path>" if token == "--properties-file" else "<text|json>"
                raise _error(token, "missing value", f"expected {token} {placeholder}")
            if (properties_file if token == "--properties-file" else output) is not None:
                raise _error(token, "given twice", "give it once")
            if token == "--properties-file":
                if not _utf8(rest[i + 1]):
                    raise _error(token, "path is not valid UTF-8", UTF8_ALLOWED)
                properties_file = rest[i + 1]
            elif rest[i + 1] in ("text", "json"):
                output = rest[i + 1]
            else:
                raise _error(token, f'"{rest[i + 1]}" is not allowed', "expected one of text, json")
            i += 2
            continue
        name = token[2:] if token.startswith("--") else None
        if name not in flags:
            near = _near(token.lstrip("-") if name is None else name, set(flags) | common)
            raise _error(
                token,
                f"unknown option of {command}",
                f"did you mean --{near}?" if near else f"run opentsx {command} --help for the options",
            )
        entry = flags[name]
        key = entry["key"]
        if entry["secret"]:
            raise _secret_on_command_line(token, entry)
        if entry["type"] == "boolean":
            follows = i + 1 < len(rest) and rest[i + 1].isascii() and rest[i + 1].lower() in ("true", "false")
            raw, step = (rest[i + 1], 2) if follows else ("true", 1)
        elif _takes_value(rest, i):
            raw, step = rest[i + 1], 2
        else:
            placeholder = "<" + "|".join(entry["values"]) + ">" if entry["type"] == "enum" else f"<{entry['type']}>"
            raise _error(token, "missing value", f"expected {token} {placeholder}")
        if key in given:
            raise _error(token, f"set twice on the command line (also by {given[key]})", "set each key once")
        values[key] = _parse(entry, raw, token)
        given[key] = token
        i += step
    names = registry.commands[command].get("args", [])
    usage = "opentsx " + command + "".join(f" <{name}>" for name in names)
    if len(positional) > len(names):
        raise _error(positional[len(names)], "unexpected argument", f"expected {usage}")
    if len(positional) < len(names):
        raise _error(command, f"missing argument <{names[len(positional)]}>", f"expected {usage}")
    return _Arguments(command, False, False, values, properties_file, output, positional)


def _environment(registry: Registry, env: Mapping[str, str]) -> dict[str, str]:
    values = {}
    for key in sorted(registry.entries):
        entry = registry.entries[key]
        for name in [Registry.envName(key)] + entry.get("env", []):
            raw = env.get(name)
            if raw is not None and raw.strip(" \t") != "":
                values.setdefault(key, _parse(entry, raw, name))
    return values


def _defaults_file(registry: Registry, confDir: str | None) -> dict[str, str]:
    if confDir is None or confDir.strip(" \t") == "":
        return {}
    if not _utf8(confDir):
        raise _error("OPENTSX_CONF_DIR", "path is not valid UTF-8", UTF8_ALLOWED)
    if not os.path.isdir(confDir):
        raise _error(
            confDir,
            "not a directory",
            "set OPENTSX_CONF_DIR to the directory that holds opentsx-defaults.conf or opentsx-defaults.yaml",
        )
    if os.path.lexists(os.path.join(confDir, "opentsx-defaults.yml")):
        raise _error(f"{confDir}/opentsx-defaults.yml", "not a defaults file name", "rename it to opentsx-defaults.yaml")
    names = [name for name in DEFAULTS_FILES if os.path.lexists(os.path.join(confDir, name))]
    if len(names) == 2:
        raise _error(confDir, "both opentsx-defaults.conf and opentsx-defaults.yaml exist", "keep one")
    return dict(readFile(f"{confDir}/{names[0]}", registry)) if names else {}


def readFile(path: str, registry: Registry | None = None) -> list[tuple[str, str]]:
    """Reads a properties file, or a YAML file by its extension; returns the checked pairs in file order."""
    registry = Registry.shipped() if registry is None else registry
    if not os.path.isfile(path):
        raise _error(path, "no such file", "give an existing file")
    with open(path, "rb") as f:
        data = f.read()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise _error(path, "not valid UTF-8", "save the file as UTF-8") from None
    if path.endswith((".yaml", ".yml")):
        return _read_yaml(registry, path, text)
    return _read_properties(registry, path, text)


def _read_properties(registry: Registry, path: str, text: str) -> list[tuple[str, str]]:
    pairs = []
    first_line: dict[str, int] = {}
    for number, line in enumerate(text.removeprefix("\ufeff").split("\n"), 1):
        line = line.removesuffix("\r").strip(" \t")
        if line == "" or line[0] in "#!":
            continue
        where = f"{path}:{number}"
        if "\\" in line:
            if line.endswith("\\"):
                raise _error(where, "line continuation is not supported", "write the value on one line")
            raise _error(where, "escape sequences are not supported", "write the character itself")
        key = re.match(r"[^ \t=]*", line).group(0)
        rest = line[len(key) :].strip(" \t")
        value = rest[1:].strip(" \t") if rest.startswith("=") else rest
        if key == "" or key.startswith(":"):
            raise _error(where, "line has no key", LINE_ALLOWED)
        # The text after a colon or of an unknown key without value can be a secret glued to the key: never echo it.
        if ":" in key:
            raise _error(f"{where}: {key.split(':')[0]}", '":" is not a separator', LINE_ALLOWED)
        if value == "":
            raise _error(where, f"{key} has no value" if registry.known(key) else "line has no value", LINE_ALLOWED)
        subject = f"{where}: {key}"
        entry = registry.entry(key, subject)
        if key in first_line:
            raise _error(subject, f"set twice in this file (first on line {first_line[key]})", "set each key once")
        first_line[key] = number
        pairs.append((key, _parse(entry, value, subject)))
    return pairs


def _read_yaml(registry: Registry, path: str, text: str) -> list[tuple[str, str]]:
    try:
        import yaml
    except ImportError:
        raise _error(path, "YAML files need the extra opentsx[yaml]", 'run pip install "opentsx[yaml]"') from None
    try:
        events = list(yaml.parse(text, Loader=yaml.SafeLoader))
    except yaml.YAMLError as e:
        raise _error(path, "not valid YAML", str(e).split("\n")[0]) from None
    if sum(isinstance(event, yaml.DocumentStartEvent) for event in events) > 1:
        raise _error(path, "more than one YAML document", "keep one document per file")
    depth = deepest = 0
    for event in events:
        if isinstance(event, (yaml.MappingStartEvent, yaml.SequenceStartEvent)):
            depth += 1
            deepest = max(deepest, depth)
        elif isinstance(event, (yaml.MappingEndEvent, yaml.SequenceEndEvent)):
            depth -= 1
    if deepest > MAX_YAML_DEPTH:
        raise _error(path, "not valid YAML", f"nesting deeper than {MAX_YAML_DEPTH} levels")
    framing = (yaml.StreamStartEvent, yaml.StreamEndEvent, yaml.DocumentStartEvent, yaml.DocumentEndEvent)
    body = [event for event in events if not isinstance(event, framing)]
    events_left = iter(body)
    pairs: list[tuple[str, str]] = []
    seen: set[str] = set()

    def take():
        event = next(events_left)
        if isinstance(event, yaml.AliasEvent):
            raise _error(path, "aliases (*name) are not supported", "write the value")
        if getattr(event, "tag", None) is not None:
            raise _error(path, "tags (!!type) are not supported", "write the value as plain text")
        return event

    def is_null(event) -> bool:
        return (
            isinstance(event, yaml.ScalarEvent)
            and event.style is None
            and event.value in ("", "~", "null", "Null", "NULL")
        )

    def leaf(key: str, raw: str) -> None:
        subject = f"{path}: {key}"
        entry = registry.entry(key, subject)
        if key in seen:
            raise _error(subject, "set twice in this file", "set each key once")
        seen.add(key)
        pairs.append((key, _parse(entry, raw, subject)))

    def mapping(prefix: str) -> None:
        while not isinstance(name := take(), yaml.MappingEndEvent):
            if not isinstance(name, yaml.ScalarEvent):
                raise _error(path, "not valid YAML", "mapping keys must be plain text")
            key = name.value if prefix == "" else f"{prefix}.{name.value}"
            subject = f"{path}: {key}"
            value = take()
            if isinstance(value, yaml.MappingStartEvent):
                mapping(key)
            elif isinstance(value, yaml.SequenceStartEvent):
                entry = registry.entry(key, subject)
                items = []
                while not isinstance(item := take(), yaml.SequenceEndEvent):
                    if not isinstance(item, yaml.ScalarEvent) or is_null(item):
                        raise _error(subject, "list items must be plain values", "write one value per item")
                    if "," in item.value:
                        shown = MASK if entry["secret"] else item.value
                        raise _error(subject, f'item "{shown}" contains a comma', "write one value per item")
                    items.append(item.value)
                if entry["type"] != "list":
                    raise _error(subject, "a YAML list is only allowed for keys of type list", "write one value")
                leaf(key, ",".join(items))
            elif is_null(value):
                registry.entry(key, subject)
                raise _error(subject, "empty value", "set a value or leave the key unset")
            else:
                leaf(key, value.value)

    if not body:
        return pairs
    first = take()
    if is_null(first):
        return pairs
    if not isinstance(first, yaml.MappingStartEvent):
        raise _error(path, "the top level must be a mapping of keys to values", "write key: value lines")
    mapping("")
    return pairs
