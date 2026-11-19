#!/usr/bin/env python3
"""Fails when tracked files hold private configuration, IDE state, measurement output, real hosts or home paths.

Usage: python3 tools/check_tracked_config.py [root]
Exits 1 and prints one line per finding.

Checks the files `git ls-files` lists under root:
  * nothing under config/private/, nothing under .idea/ and nothing under the measurement output directories
    (opentsx-lg/src/main/docker-compose/output/, opentsx-lg/opentsx/plots/);
  * in *.props, *.properties and .env files (.env, .env.<name>, .env-<name>): the hosts in the values of keys that
    end in servers, url, host, hostname, address or addresses (bootstrap.servers, schema.registry.url) and every
    value that is an IPv4 address are localhost, a loopback address or a placeholder (<name>, ${NAME}, $NAME, or a
    host under example.com, example.org, example.net or .example);
  * in every tracked text file: no Confluent Cloud cluster or registry host, no *.gce.cloudera.com host and no
    personal home path, a directory below /Users or /home other than the service account homes Shared, jovyan,
    ubuntu, ec2-user and user.
"""
import ipaddress
import pathlib
import re
import subprocess
import sys

CONFIG_SUFFIXES = (".props", ".properties")
ENV_NAME = re.compile(r"^\.env(?:[.-].+)?$")
HOST_KEY = re.compile(r"(?:^|[._])(?:servers|url|host|hostname|address|addresses)$", re.I)
ADDRESS = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?$")
PLACEHOLDER = re.compile(r"^(?:<[^<>]+>|\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*)$")
EXAMPLE_DOMAIN = re.compile(r"(?:^|\.)(?:example\.(?:com|org|net)|example)$", re.I)
REAL_HOST = re.compile(r"\b(?:(?:pkc|psrc|lkc|lsrc)-(?!x+\b)[a-z0-9]+(?:\.[a-z0-9-]+)*\.confluent\.cloud"
                       r"|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.gce\.cloudera\.com)\b", re.I)
OUTPUT_DIRS = ("opentsx-lg/src/main/docker-compose/output/", "opentsx-lg/opentsx/plots/")
HOME_PATH = re.compile(r"/(?:Users|home)/([A-Za-z0-9._-]+)(?![A-Za-z0-9._-])")
SERVICE_HOMES = {"Shared", "jovyan", "ubuntu", "ec2-user", "user"}


def tracked_files(root: pathlib.Path) -> list[str]:
    listing = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True)
    return [p for p in listing.stdout.decode("utf-8").split("\0") if p]


def is_config(path: str) -> bool:
    name = pathlib.PurePosixPath(path).name
    return name.endswith(CONFIG_SUFFIXES) or bool(ENV_NAME.match(name))


def allowed_host(host: str) -> bool:
    host = host.strip().strip("[]")
    if not host or PLACEHOLDER.match(host) or EXAMPLE_DOMAIN.search(host) or host.lower() == "localhost":
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.is_loopback or address.is_unspecified


def host_of(item: str) -> str:
    item = re.sub(r"^[A-Za-z][A-Za-z0-9+.-]*://", "", item)
    item = re.split(r"[/?#]", item, maxsplit=1)[0]
    item = item.rsplit("@", 1)[-1]
    if item.startswith("["):
        return item.split("]", 1)[0] + "]"
    return re.sub(r":\d+$", "", item) if item.count(":") <= 1 else item


def hosts_of(key: str, value: str) -> list[str]:
    items = [item.strip().strip("\"'") for item in value.split(",")]
    items = [item for item in items if item]
    if HOST_KEY.search(key):
        return [host_of(item) for item in items]
    return [host_of(item) for item in items if ADDRESS.match(item)]


def config_findings(path: str, text: str) -> list[str]:
    findings = []
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        line = re.sub(r"^export\s+", "", line)
        match = re.match(r"^([^=:\s]+)\s*[=:]\s*(.*)$", line)
        if not match:
            continue
        key, value = match.group(1), match.group(2).strip()
        for host in dict.fromkeys(hosts_of(key, value)):
            if not allowed_host(host):
                findings.append(f"{path}:{number}: {key}: \"{host}\" is not localhost or a placeholder")
    return findings


def check(root: pathlib.Path, paths: list[str]) -> list[str]:
    findings = []
    for path in paths:
        parts = pathlib.PurePosixPath(path).parts
        if ".idea" in parts:
            findings.append(f"{path}: IDE state is tracked; untrack it, .idea/ is git-ignored")
            continue
        if any(parts[i:i + 2] == ("config", "private") for i in range(len(parts) - 1)):
            findings.append(f"{path}: private configuration is tracked; untrack it, config/private/ is git-ignored")
            continue
        output_dir = next((d for d in OUTPUT_DIRS if path.startswith(d)), None)
        if output_dir:
            findings.append(f"{path}: measurement output is tracked; untrack it, {output_dir} is git-ignored")
            continue
        file = root / path
        if not file.is_file():
            continue
        data = file.read_bytes()
        if b"\0" in data:
            continue
        text = data.decode("utf-8", errors="replace")
        if is_config(path):
            findings.extend(config_findings(path, text))
        for number, line in enumerate(text.splitlines(), 1):
            for host in REAL_HOST.findall(line):
                findings.append(f"{path}:{number}: \"{host}\" is a real host; use localhost or a placeholder")
            for home in HOME_PATH.finditer(line):
                if home.group(1) not in SERVICE_HOMES:
                    findings.append(f"{path}:{number}: \"{home.group(0)}\" is a personal home path; "
                                    "use $HOME or a placeholder such as <home>")
    return findings


def main(root: pathlib.Path, paths: list[str] | None = None) -> int:
    paths = tracked_files(root) if paths is None else paths
    findings = check(root, paths)
    for finding in findings:
        print(finding)
    print(f"checked {len(paths)} tracked files, {len(findings)} findings")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()))
