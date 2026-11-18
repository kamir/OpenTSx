"""The compose files that start this backend: secrets come from the environment, Postgres and the backend listen on
127.0.0.1 only, no password on a command line, no Redis. Each finding names file and line."""

import re
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parents[1]
COMPOSE_FILES = [HERE / "docker-compose.yml", HERE.parent / "docker-compose.local.yml"]
SECRET_NAME = re.compile(r"PASSWORD|SECRET|_TOKEN$|_KEY$")
REQUIRED_VARIABLE = re.compile(r"\$\{(\w+):\?[^}]+\}")
HEALTHCHECK_USER = '-U "$${POSTGRES_USER}"'
LOOPBACK_SERVICES = ("postgres", "backend")


def mapping(node):
    return {key.value: value for key, value in node.value}


def scalars(node):
    if isinstance(node, yaml.ScalarNode):
        return [node]
    return [item for item in node.value if isinstance(item, yaml.ScalarNode)]


def line(node):
    return node.start_mark.line + 1


def environment(node):
    """(name, value, line) of each entry, in the mapping or the list form."""
    if isinstance(node, yaml.MappingNode):
        return [(key.value, value.value, line(key)) for key, value in node.value]
    entries = []
    for item in node.value:
        name, _, value = item.value.partition("=")
        entries.append((name, value, line(item)))
    return entries


def services(path):
    return mapping(mapping(yaml.compose(path.read_text()))["services"])


def findings(path):
    found = []
    for name, node in services(path).items():
        service = mapping(node)
        if name == "redis":
            found.append(f"{path.name}:{node.start_mark.line}: service redis, which no code uses")
        for key, value, at in environment(service.get("environment", yaml.MappingNode("", []))):
            if not SECRET_NAME.search(key):
                continue
            if ":-" in value:
                found.append(f"{path.name}:{at}: {key} falls back to a literal")
            elif not REQUIRED_VARIABLE.fullmatch(value):
                found.append(f"{path.name}:{at}: {key} is a literal")
        for item in scalars(service.get("command", yaml.SequenceNode("", []))):
            if re.search("pass", item.value, re.IGNORECASE):
                found.append(f"{path.name}:{line(item)}: password on the command line")
        if "healthcheck" in service:
            for item in scalars(mapping(service["healthcheck"])["test"]):
                if "PASSWORD=" in item.value:
                    found.append(f"{path.name}:{line(item)}: password in the healthcheck")
                if name == "postgres" and item.value.startswith("pg_isready") and HEALTHCHECK_USER not in item.value:
                    found.append(f"{path.name}:{line(item)}: healthcheck user is not POSTGRES_USER")
        if name in LOOPBACK_SERVICES:
            for item in scalars(service.get("ports", yaml.SequenceNode("", []))):
                if not item.value.startswith("127.0.0.1:"):
                    found.append(f"{path.name}:{line(item)}: {name} port {item.value} on all interfaces")
    return found


def test_the_compose_files_hold_no_secret_and_publish_on_loopback():
    found = [f for path in COMPOSE_FILES for f in findings(path)]
    assert found == []


def test_the_checks_see_postgres_and_the_backend_in_both_files():
    for path in COMPOSE_FILES:
        assert {"postgres", "backend"} <= services(path).keys()
