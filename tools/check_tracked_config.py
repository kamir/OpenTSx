#!/usr/bin/env python3
"""Fails when tracked files hold private configuration, IDE state, measurement output, real hosts or home paths, and
on a gitlink without a submodule entry.

Usage: python3 tools/check_tracked_config.py [root]
Exits 1 and prints one line per finding.

Checks the files `git ls-files` lists under root:
  * nothing under config/private/, nothing under .idea/ and nothing under the measurement output directories
    (opentsx-lg/src/main/docker-compose/output/, opentsx-lg/opentsx/plots/);
  * no file named ccloud.props, a Kafka client profile with credentials, and not the MQTT password file
    opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd that 01_bootstrap.sh creates;
  * in *.props, *.properties and .env files (.env, .env.<name>, .env-<name>): the hosts in the values of keys that
    end in servers, url, host, hostname, address or addresses (bootstrap.servers, schema.registry.url) and every
    value that is an IPv4 address are localhost, a loopback address or a placeholder (<name>, ${NAME}, $NAME, or a
    host under example.com, example.org, example.net or .example);
  * in every tracked text file: no Confluent Cloud cluster or registry host, no *.gce.cloudera.com host, no
    Confluent Cloud resource id (lkc-, pkc-, psrc-, lsrc-, env- or sa- and 5 or more lower-case letters or digits
    with at least one digit, not inside a word) and no personal home path, a directory below /Users or /home other
    than the service account homes Shared, jovyan, ubuntu, ec2-user and user;
  * when root holds the private deny list config/private/denylist.txt (git-ignored; one word per line, blank lines
    and lines that start with # skipped): no tracked path and no line of a tracked text file holds one of its words,
    in any letter case, as a whole word, that is, not next to a letter or digit. A deny list that cannot be read is
    one finding, and so is a link on its path that points to nothing. Without the file these checks do not run.

When it reads the git listing itself (no paths given), it also fails on a gitlink, an index entry of mode 160000,
whose path has no submodule.<name>.path entry in the .gitmodules file at the top level of the repository (no file
means no entries). A root below the top level compares the paths below it. A .gitmodules file that git cannot read,
for example one with conflict markers, is one finding with git's reason; the other checks still run.
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
RESOURCE_ID = re.compile(r"(?<![A-Za-z0-9_-])(?:lkc|pkc|psrc|lsrc|env|sa)-(?=[a-z0-9]*[0-9])[a-z0-9]{5,}"
                         r"(?![A-Za-z0-9_])")
OUTPUT_DIRS = ("opentsx-lg/src/main/docker-compose/output/", "opentsx-lg/opentsx/plots/")
CLIENT_PROFILE = "ccloud.props"
MQTT_PASSWORD_FILE = "opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd"
HOME_PATH = re.compile(r"/(?:Users|home)/([A-Za-z0-9._-]+)(?![A-Za-z0-9._-])")
SERVICE_HOMES = {"Shared", "jovyan", "ubuntu", "ec2-user", "user"}
DENY_LIST = "config/private/denylist.txt"


def tracked_files(root: pathlib.Path) -> list[str]:
    listing = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True)
    return [p for p in listing.stdout.decode("utf-8").split("\0") if p]


def gitlinks(root: pathlib.Path) -> list[str]:
    listing = subprocess.run(["git", "-C", str(root), "ls-files", "-s", "-z"], capture_output=True, check=True)
    entries = (entry.partition("\t") for entry in listing.stdout.decode("utf-8").split("\0") if entry)
    return [path for info, _, path in entries if info.startswith("160000 ")]


class UnreadableModules(Exception):
    pass


def submodule_paths(root: pathlib.Path) -> tuple[str, set[str]]:
    location = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel", "--show-prefix"],
                              capture_output=True, check=True)
    top, prefix = location.stdout.decode("utf-8").split("\n")[:2]
    if not (pathlib.Path(top) / ".gitmodules").is_file():
        return prefix, set()
    command = ["git", "-C", top, "config", "-z", "-f", ".gitmodules", "--get-regexp", r"^submodule\..*\.path$"]
    result = subprocess.run(command, capture_output=True, check=False)
    if result.returncode not in (0, 1):
        reason = "; ".join(line.strip() for line in result.stderr.decode("utf-8", "replace").splitlines()
                           if line.strip())
        raise UnreadableModules(f".gitmodules: git cannot read it ({reason}); fix the file, for example resolve "
                                "its conflict markers")
    return prefix, {entry.partition("\n")[2] for entry in result.stdout.decode("utf-8").split("\0") if entry}


def gitlink_findings(root: pathlib.Path) -> list[str]:
    try:
        prefix, known = submodule_paths(root)
    except UnreadableModules as error:
        return [str(error)]
    findings = []
    for path in gitlinks(root):
        top = prefix + path
        if top not in known:
            findings.append(f"{path}: gitlink without a .gitmodules entry; at the top level of the repository, add "
                            f"one with git config -f .gitmodules submodule.{top}.path {top}, git config -f "
                            f".gitmodules submodule.{top}.url <url> and git add .gitmodules, or remove it with git rm "
                            f"--cached {top}")
    return findings


def deny_list(root: pathlib.Path) -> tuple[list[tuple[str, re.Pattern]], list[str]]:
    try:
        text = (root / DENY_LIST).read_text(encoding="utf-8-sig")
    except FileNotFoundError as error:
        parts = pathlib.PurePosixPath(DENY_LIST).parts
        links = (root.joinpath(*parts[:n]) for n in range(1, len(parts) + 1))
        if not any(link.is_symlink() and not link.exists() for link in links):
            return [], []
        return [], [f"{DENY_LIST}: cannot read it ({error.strerror}); fix the file or remove it"]
    except (OSError, UnicodeDecodeError) as error:
        cause = getattr(error, "strerror", None) or str(error)
        return [], [f"{DENY_LIST}: cannot read it ({cause}); fix the file or remove it"]
    words = dict.fromkeys(line.strip() for line in text.splitlines())
    return [(word, re.compile(rf"(?<![^\W_]){re.escape(word)}(?![^\W_])", re.I)) for word in words
            if word and not word.startswith("#")], []


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
    denied, findings = deny_list(root)
    for path in paths:
        for word, pattern in denied:
            if pattern.search(path):
                findings.append(f"{path}: {word}: a word of the private deny list in the path; rename the file")
        parts = pathlib.PurePosixPath(path).parts
        if ".idea" in parts:
            findings.append(f"{path}: IDE state is tracked; untrack it, .idea/ is git-ignored")
            continue
        if any(parts[i:i + 2] == ("config", "private") for i in range(len(parts) - 1)):
            findings.append(f"{path}: private configuration is tracked; untrack it, config/private/ is git-ignored")
            continue
        if parts[-1] == CLIENT_PROFILE:
            findings.append(f"{path}: a Kafka client profile with credentials is tracked; untrack it, "
                            f"{CLIENT_PROFILE} is git-ignored")
            continue
        if path == MQTT_PASSWORD_FILE:
            findings.append(f"{path}: the MQTT password file is tracked; untrack it, 01_bootstrap.sh creates it")
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
            hosts = list(REAL_HOST.finditer(line))
            for host in hosts:
                findings.append(f"{path}:{number}: \"{host.group(0)}\" is a real host; use localhost or a placeholder")
            for resource in RESOURCE_ID.finditer(line):
                if not any(h.start() <= resource.start() < h.end() for h in hosts):
                    findings.append(f"{path}:{number}: {resource.group(0)}: looks like a Confluent Cloud resource id; "
                                    "write a placeholder such as lkc-xxxxx")
            for home in HOME_PATH.finditer(line):
                if home.group(1) not in SERVICE_HOMES:
                    findings.append(f"{path}:{number}: \"{home.group(0)}\" is a personal home path; "
                                    "use $HOME or a placeholder such as <home>")
            for word, pattern in denied:
                if pattern.search(line):
                    findings.append(f"{path}:{number}: {word}: a word of the private deny list; "
                                    "remove it or write a placeholder")
    return findings


def main(root: pathlib.Path, paths: list[str] | None = None) -> int:
    findings = []
    if paths is None:
        paths = tracked_files(root)
        findings = gitlink_findings(root)
    findings += check(root, paths)
    for finding in findings:
        print(finding)
    print(f"checked {len(paths)} tracked files, {len(findings)} findings")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()))
