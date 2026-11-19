import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "opentsx.ci-selection.v1"
JOBS = (
    "operator-learning", "execution-contracts", "rust-runner", "rust-runner-native",
    "rust-runner-static", "rust-b1-acceptance", "rust-runner-compose", "wayang-rust-compose",
    "java", "python", "saas-frontend", "saas-backend", "deploy", "kafscale-conformance",
    "cross-language", "documentation",
)
DEMO_JOBS = ("operator-learning", "saas-frontend", "documentation")
DEMO_PATHS = frozenset({
    "README.md", "CHANGELOG.md", "docs/devguide/standalone-workbench.md",
    "opentsx-saas-frontend/src/workbench/deviceSample.ts",
    "opentsx-saas-frontend/src/workbench/deviceSample.test.ts",
    "opentsx-saas-frontend/src/workbench/WorkbenchDemo.test.tsx",
    "opentsx-saas-frontend/scripts/demo-smoke.mjs",
})
OID = re.compile(r"[0-9a-fA-F]{40}")


def manifest(scope, reason):
    return {"schema": SCHEMA, "scope": scope,
            "jobs": list(DEMO_JOBS if scope == "demo" else JOBS), "reason": reason}


def git(root, *arguments):
    try:
        result = subprocess.run(["git", *arguments], cwd=root, capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError(f"Cannot execute Git {arguments[0]}: {type(error).__name__}.") from error
    if result.returncode:
        raise ValueError(f"Git {arguments[0]} failed with exit code {result.returncode}.")
    return result.stdout


def valid_path(value):
    return bool(value) and not any(part in ("", ".", "..") for part in value.split("/")) \
        and "\\" not in value and ":" not in value and all(ord(char) >= 32 and ord(char) != 127 for char in value)


def changes(data):
    if not data:
        return []
    if not data.endswith(b"\0"):
        raise ValueError("The Git diff is not NUL terminated.")
    fields = data[:-1].split(b"\0")
    if len(fields) % 2:
        raise ValueError("The Git diff has an incomplete status/path pair.")
    result = []
    seen = set()
    for index in range(0, len(fields), 2):
        try:
            status = fields[index].decode("ascii")
            name = fields[index + 1].decode("utf-8")
        except UnicodeError as error:
            raise ValueError("The Git diff has invalid text encoding.") from error
        if status not in ("A", "M", "D"):
            raise ValueError("The Git diff has an unsupported change status.")
        if not valid_path(name) or name in seen:
            raise ValueError("The Git diff has an invalid or duplicate path.")
        seen.add(name)
        result.append((status, name))
    return result


def tree_paths(data, requested):
    if not data:
        return set()
    if not data.endswith(b"\0"):
        raise ValueError("The Git tree is not NUL terminated.")
    found = set()
    for entry in data[:-1].split(b"\0"):
        try:
            metadata, name = entry.decode("utf-8").split("\t", 1)
            mode, kind, oid = metadata.split(" ")
        except (UnicodeError, ValueError) as error:
            raise ValueError("The Git tree has a malformed entry.") from error
        if mode != "100644" or kind != "blob" or not OID.fullmatch(oid):
            raise ValueError("An allowlisted change is not a regular non-executable file.")
        if name not in requested or name in found:
            raise ValueError("The Git tree has an unexpected or duplicate path.")
        found.add(name)
    return found


def select_scope(event, base, head, root=ROOT):
    if event != "pull_request":
        return manifest("full", "Only pull_request events can reduce CI scope.")
    if not isinstance(base, str) or not isinstance(head, str) or not OID.fullmatch(base) or not OID.fullmatch(head):
        return manifest("full", "The pull request needs immutable 40-character base and head commit hashes.")
    try:
        if git(root, "rev-parse", "--is-shallow-repository") != b"false\n":
            raise ValueError("The repository history is shallow or its completeness is unknown.")
        for oid in (base, head):
            if git(root, "cat-file", "-t", oid) != b"commit\n":
                raise ValueError("A supplied object is not a commit.")
        ancestors = git(root, "merge-base", "--all", base, head).splitlines()
        if len(ancestors) != 1 or not OID.fullmatch(ancestors[0].decode("ascii")):
            raise ValueError("The pull request does not have exactly one merge base.")
        ancestor = ancestors[0].decode("ascii")
        changed = changes(git(root, "diff", "--no-ext-diff", "--no-textconv", "--name-status",
                              "--no-renames", "-z", ancestor, head, "--"))
        if not changed:
            return manifest("full", "No changed paths were found.")
        outside = sorted(name for _, name in changed if name not in DEMO_PATHS)
        if outside:
            return manifest("full", "Changes extend beyond the standalone demo allowlist: " + ", ".join(outside))
        requested = {name for _, name in changed}
        for oid, present in ((ancestor, {name for status, name in changed if status != "A"}),
                             (head, {name for status, name in changed if status != "D"})):
            actual = tree_paths(git(root, "ls-tree", "-z", oid, "--", *sorted(requested)), requested)
            if actual != present:
                raise ValueError("The Git tree does not match the declared change statuses.")
    except (ValueError, UnicodeError) as error:
        return manifest("full", f"Full CI is required because change detection could not be trusted: {error}")
    return manifest("demo", "Every changed path is an allowlisted standalone demo file with a regular file mode.")


def json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON contains a duplicate field.")
        result[key] = value
    return result


def invalid_constant(value):
    raise ValueError("JSON contains a non-finite number.")


def read_json(value):
    return json.loads(value, object_pairs_hook=json_object, parse_constant=invalid_constant)


def gate(needs):
    if not isinstance(needs, dict) or set(needs) != {*JOBS, "ci-selection"}:
        raise ValueError("CI results must contain the selector and every known workload exactly once.")
    selector = needs["ci-selection"]
    if not isinstance(selector, dict) or set(selector) != {"result", "outputs"} or selector.get("result") != "success":
        raise ValueError("The CI selector did not succeed.")
    outputs = selector.get("outputs")
    if not isinstance(outputs, dict) or set(outputs) != {"scope", "selection"}:
        raise ValueError("The CI selector outputs are missing or unknown.")
    if outputs["scope"] not in ("demo", "full") or not isinstance(outputs["selection"], str):
        raise ValueError("The CI selector outputs have invalid types or scope.")
    selected = read_json(outputs["selection"])
    if not isinstance(selected, dict) or set(selected) != {"schema", "scope", "jobs", "reason"}:
        raise ValueError("The CI selection manifest has missing or unknown fields.")
    if selected["schema"] != SCHEMA or selected["scope"] != outputs["scope"]:
        raise ValueError("The CI selection schema or scope is inconsistent.")
    expected = DEMO_JOBS if selected["scope"] == "demo" else JOBS
    if not isinstance(selected["jobs"], list) or any(not isinstance(job, str) for job in selected["jobs"]) \
            or len(selected["jobs"]) != len(expected) or set(selected["jobs"]) != set(expected):
        raise ValueError("The CI selection does not contain the exact jobs required by its scope.")
    if not isinstance(selected["reason"], str) or not selected["reason"].strip():
        raise ValueError("The CI selection reason is missing.")
    for job in JOBS:
        result = needs[job]
        if not isinstance(result, dict) or set(result) != {"result", "outputs"} or not isinstance(result["outputs"], dict):
            raise ValueError(f"The CI result for {job} is malformed.")
        allowed = ("success",) if job in expected else ("success", "skipped")
        if result["result"] not in allowed:
            raise ValueError(f"The CI result for {job} is not acceptable: {result['result']!r}.")
    return selected


def main(argv=None):
    parser = argparse.ArgumentParser(description="Select conservative CI scope and validate completed jobs.")
    commands = parser.add_subparsers(dest="command", required=True)
    select = commands.add_parser("select", help="Reduce pull request checks only for the exact demo allowlist.")
    select.add_argument("--event", required=True)
    select.add_argument("--base", required=True)
    select.add_argument("--head", required=True)
    select.add_argument("--github-output", type=Path)
    commands.add_parser("gate", help="Validate CI_NEEDS_JSON and require every selected job to succeed.")
    args = parser.parse_args(argv)
    try:
        if args.command == "select":
            selected = select_scope(args.event, args.base, args.head)
            if args.github_output is not None:
                with args.github_output.open("a", encoding="utf-8") as target:
                    target.write(f"scope={selected['scope']}\nselection={json.dumps(selected, separators=(',', ':'))}\n")
        else:
            value = os.environ.get("CI_NEEDS_JSON")
            if not value:
                raise ValueError("CI_NEEDS_JSON is required.")
            selected = gate(read_json(value))
        print(json.dumps(selected, sort_keys=True))
    except (OSError, ValueError, TypeError) as error:
        print(f"CI scope error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
