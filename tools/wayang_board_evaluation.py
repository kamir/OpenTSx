#!/usr/bin/env python3
"""Plan the B4 board matrix and check supplied measurement evidence integrity."""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from rust_runner_artifacts import RUST


SPEC = importlib.util.spec_from_file_location("board_federation", Path(__file__).with_name("wayang_federation_measurement.py"))
FEDERATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FEDERATION)
PLAN_SCHEMA = "opentsx.board-evaluation-plan.v1"
EVIDENCE_SCHEMA = "opentsx.board-evaluation-evidence.v1"
RESOURCE_FIELDS = ("elapsed_seconds", "rss_bytes", "cpu_seconds", "lag_records")
# D-T14 (owner, 2026-11-20): B4.2 is measured on one physical ARM64 board; ARMv7 is not part of the matrix.
ARCHITECTURES = ("arm64",)
RUNTIMES = ("java17", "java21", "rust")
# D-T15 (owner, 2026-11-20): three chains on a paced live feed of one sample per series and second.
CHAINS = ("passthrough", "clean", "stats")
SERIES = (100, 1000, 10000)
EVIDENCE_HELP = (
    "Each run separates artifact.version (the stable OpenTSx application version) from runtimeVersion "
    f"(a JDK 17.x/21.x version or Rust compiler {RUST}). Artifact SHA256, application version and runtime version "
    "must remain pinned within each architecture/runtime. Resources cover every whole second of durationSeconds; "
    "firstFetchSeconds and firstOutputSeconds cannot exceed the last actual sample. outputProof names checkerSource "
    "and its exact checkerClass. Its successful JUnit suite and testcase class names must match that pinned source. "
    "JUnit properties opentsx.sourceCommit, opentsx.conditionId, opentsx.artifactSha256 and opentsx.inputSha256 "
    "must match the current run. This checks evidence association and integrity, not hardware acceptance."
)


def protocol():
    return {"warmupSeconds": 300, "measurementSeconds": 1800, "sampleEverySeconds": 1,
            "feedSamplesPerSeriesPerSecond": 1}


def conditions():
    return [{"id": f"{architecture}-{chain}-s{series}-{runtime}-r{repetition}", "architecture": architecture,
             "chain": chain, "series": series, "runtime": runtime, "repetition": repetition}
            for architecture in ARCHITECTURES for chain in CHAINS for series in SERIES for runtime in RUNTIMES
            for repetition in range(1, 4)]


def plan():
    return {"schema": PLAN_SCHEMA, "status": "planned", "hardwareQualification": False,
            "protocol": protocol(), "boards": [{"architecture": architecture, "deviceModel": None,
                "executionMode": "unassigned"} for architecture in ARCHITECTURES], "conditions": conditions()}


def finite(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
        raise ValueError(f"{name} must be a finite non-negative number")
    try:
        if not math.isfinite(value):
            raise ValueError(f"{name} must be a finite non-negative number")
    except OverflowError as error:
        raise ValueError(f"{name} exceeds finite measurement precision") from error
    return value


def text(value, name):
    if not isinstance(value, str) or not value.strip() or "\x00" in value:
        raise ValueError(f"{name} must be a non-empty string")
    return value


def relative_file(root, name):
    text(name, "evidence path")
    path = Path(name)
    if path.is_absolute() or "\\" in name or any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("evidence path must be relative without traversal")
    resolved = root
    for part in path.parts:
        resolved /= part
        if resolved.is_symlink():
            raise ValueError("evidence path must not contain symbolic links")
    if not resolved.is_file():
        raise ValueError("evidence path must name an existing regular file")
    return resolved


def file_evidence(root, reference, *, limit=536_870_912):
    if not isinstance(reference, dict) or not re.fullmatch("[0-9a-f]{64}", str(reference.get("sha256"))):
        raise ValueError("file evidence needs an actual SHA256 pin")
    path = relative_file(root, reference.get("file"))
    if path.stat().st_size < 1 or path.stat().st_size > limit:
        raise ValueError("evidence file is empty or exceeds its byte limit")
    data = path.read_bytes()
    if len(data) > limit or hashlib.sha256(data).hexdigest() != reference["sha256"]:
        raise ValueError("evidence SHA256 differs from the actual file")
    return path, data


def output_proof(root, reference, source, snapshot, binding):
    path, data = file_evidence(root, reference, limit=2_097_152)
    checker = reference.get("checkerSource")
    if not isinstance(checker, str) or "/src/test/java/" not in checker or not checker.endswith("Test.java"):
        raise ValueError("output proof needs its existing Java test checker source")
    checker_file = relative_file(source, checker)
    if snapshot["files"].get(checker) != hashlib.sha256(checker_file.read_bytes()).hexdigest():
        raise ValueError("output checker source is absent from the verified source snapshot")
    checker_class = checker.split("/src/test/java/", 1)[1].removesuffix(".java").replace("/", ".")
    package = re.search(r"(?m)^\s*package\s+([A-Za-z_$][A-Za-z0-9_$.]*)\s*;", checker_file.read_text(encoding="utf-8"))
    declared_class = (package[1] + "." if package else "") + checker_file.stem
    if reference.get("checkerClass") != checker_class or declared_class != checker_class:
        raise ValueError("output checker class does not match its full source package and path")
    try:
        document = ET.fromstring(data)
    except ET.ParseError as error:
        raise ValueError("output proof must contain actual JUnit test evidence") from error
    suites = list(document.iter("testsuite"))
    if not suites:
        raise ValueError("output proof contains no JUnit test suite")
    for suite in suites:
        try:
            count = int(suite.attrib["tests"])
            bad = [int(suite.attrib[name]) for name in ("failures", "errors", "skipped")]
        except (KeyError, ValueError) as error:
            raise ValueError("output proof test counts are missing or invalid") from error
        cases = suite.findall("testcase")
        if count < 1 or any(value != 0 for value in bad) or count != len(cases) or any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
            raise ValueError("output proof needs executed successful tests with zero skips")
        if suite.attrib.get("name") != checker_class or any(case.attrib.get("classname") != checker_class for case in cases):
            raise ValueError("output proof does not name the full pinned checker class")
        properties = {}
        for prop in suite.findall("./properties/property"):
            name = prop.attrib.get("name")
            if not name or name in properties:
                raise ValueError("output proof binding properties are missing or duplicated")
            properties[name] = prop.attrib.get("value")
        if any(properties.get(name) != value for name, value in binding.items()):
            raise ValueError("output proof binding differs from the source, condition, artifact or input")
    return path


def resource_samples(data, duration):
    try:
        reader = csv.DictReader(io.StringIO(data.decode("utf-8")))
        if tuple(reader.fieldnames or ()) != RESOURCE_FIELDS:
            raise ValueError("resource CSV columns differ from the documented schema")
        samples = 0
        previous_time = -1
        previous_cpu = -1
        for row in reader:
            if set(row) != set(RESOURCE_FIELDS):
                raise ValueError("resource CSV row has missing or extra columns")
            elapsed = finite(float(row["elapsed_seconds"]), "resource elapsed_seconds")
            cpu = finite(float(row["cpu_seconds"]), "resource cpu_seconds")
            for field in ("rss_bytes", "lag_records"):
                if not isinstance(row[field], str) or not re.fullmatch("[0-9]+", row[field]):
                    raise ValueError(f"resource {field} must be a non-negative integer")
            second = math.floor(elapsed)
            if elapsed <= previous_time or elapsed > duration or cpu < previous_cpu or second != samples:
                raise ValueError("resource clocks or one-second sample coverage are inconsistent")
            samples += 1
            previous_time, previous_cpu = elapsed, cpu
        if samples < math.floor(duration):
            raise ValueError("resource evidence omits seconds from the declared run duration")
        return previous_time
    except (KeyError, TypeError, UnicodeError, csv.Error) as error:
        raise ValueError("resource CSV contains an invalid row") from error


def validate(value, root, source):
    if not isinstance(value, dict) or value.get("schema") != EVIDENCE_SCHEMA or value.get("status") != "complete":
        raise ValueError("validation needs a complete evidence manifest, not a measurement plan")
    if value.get("protocol") != protocol() or any(type(item) is not int for item in value["protocol"].values()):
        raise ValueError("board protocol must retain 300-second warm-up, 1800-second measurement, one-second sampling "
                         "and a feed of one sample per series and second")
    if value.get("evidenceKind") not in ("physical-board-measurements", "synthetic-test-fixture"):
        raise ValueError("evidence kind must distinguish measurements from synthetic test fixtures")
    snapshot = FEDERATION.source_snapshot(source)
    if value.get("sourceSnapshot") != snapshot or not re.fullmatch("[0-9a-f]{40}", str(snapshot.get("commit"))):
        raise ValueError("source evidence differs from the actual clean checkout")
    boards = value.get("boards")
    if (not isinstance(boards, list) or len(boards) != len(ARCHITECTURES)
            or [b.get("architecture") for b in boards if isinstance(b, dict)] != list(ARCHITECTURES)):
        raise ValueError("board evidence requires exactly one physical arm64 device (D-T14)")
    for board in boards:
        for field in ("deviceModel", "os", "kernel", "cpuGovernor", "storage", "network"):
            text(board.get(field), "board " + field)
        machine = "aarch64"
        if board.get("executionMode") != "physical" or board.get("system") != "Linux" or board.get("machine") != machine or board["cpuGovernor"] != "performance":
            raise ValueError("board execution must record physical Linux architecture and performance governor")
        file_evidence(root, board.get("identityEvidence"), limit=2_097_152)
    expected = {row["id"]: row for row in conditions()}
    runs = value.get("runs")
    if not isinstance(runs, list) or len(runs) != len(expected):
        raise ValueError("board evidence omits required matrix conditions")
    seen = set()
    run_files = set()
    artifacts = {}
    inputs = {}
    for run in runs:
        if not isinstance(run, dict) or not isinstance(run.get("condition"), dict):
            raise ValueError("board run condition is missing")
        condition = run["condition"]
        identity = condition.get("id")
        if (not isinstance(identity, str) or identity in seen or condition != expected.get(identity)
                or any(type(condition.get(field)) is not int for field in ("series", "repetition"))):
            raise ValueError("board evidence has an unknown or duplicated condition")
        seen.add(identity)
        duration = finite(run.get("durationSeconds"), "durationSeconds")
        fetch = finite(run.get("firstFetchSeconds"), "firstFetchSeconds")
        output = finite(run.get("firstOutputSeconds"), "firstOutputSeconds")
        if duration < 2100 or not fetch <= output <= duration:
            raise ValueError("board duration or first-fetch/output timing is incomplete")
        command = run.get("command")
        if not isinstance(command, list) or not command or any(not isinstance(item, str) or not item for item in command):
            raise ValueError("board run needs its actual command")
        runtime = condition["runtime"]
        if runtime != "rust":
            text(run.get("javaHeap"), "explicit Java heap")
        elif run.get("javaHeap") is not None:
            raise ValueError("Rust measurement cannot report a Java heap")
        artifact = run.get("artifact")
        file_evidence(root, artifact)
        version = artifact.get("version")
        if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:\+[0-9A-Za-z.]+)?", version):
            raise ValueError("artifact version must be an explicit stable version")
        runtime_version = run.get("runtimeVersion")
        if not isinstance(runtime_version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:\+[0-9A-Za-z.]+)?", runtime_version):
            raise ValueError("runtime version must explicitly identify the stable JDK or Rust compiler")
        if (runtime == "rust" and runtime_version != RUST
                or runtime != "rust" and runtime_version.split(".")[0] != runtime.removeprefix("java")):
            raise ValueError("runtime version differs from the declared runtime condition")
        key = condition["architecture"], runtime
        pin = artifact["sha256"], version, runtime_version
        if artifacts.setdefault(key, pin) != pin:
            raise ValueError("artifact pins changed within one board/runtime condition")
        file_evidence(root, run.get("input"))
        input_pin = run["input"]["sha256"]
        if inputs.setdefault(condition["series"], input_pin) != input_pin:
            raise ValueError("recorded input differs between comparable conditions")
        resource_path, data = file_evidence(root, run.get("resources"), limit=16_777_216)
        proof_path = output_proof(root, run.get("outputProof"), source, snapshot,
            {"opentsx.sourceCommit": snapshot["commit"], "opentsx.conditionId": identity,
             "opentsx.artifactSha256": artifact["sha256"], "opentsx.inputSha256": input_pin})
        if resource_path in run_files or proof_path in run_files:
            raise ValueError("resource or output proof files are reused across runs")
        run_files.update((resource_path, proof_path))
        covered_end = resource_samples(data, duration)
        if output > covered_end:
            raise ValueError("first-fetch/output clocks exceed the actual covered resource window")
    if FEDERATION.source_snapshot(source) != snapshot:
        raise ValueError("source changed while validating board evidence")
    return {"schema": "opentsx.board-evidence-integrity.v1", "evidenceKind": value["evidenceKind"],
            "conditions": len(seen), "sourceCommit": snapshot["commit"],
            "hardwareQualification": False, "ownerReviewRequired": True}


def json_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("duplicate JSON object key")
        result[name] = value
    return result


def invalid_constant(value):
    raise ValueError("non-finite JSON numbers are not valid evidence")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    planned = commands.add_parser("plan", help="write the unassigned B4 matrix without measurement results")
    planned.add_argument("--output", type=Path, required=True)
    checked = commands.add_parser("validate", help="check supplied evidence completeness and hashes without hardware acceptance",
                                  description=EVIDENCE_HELP)
    checked.add_argument("--input", type=Path, required=True)
    checked.add_argument("--source-root", type=Path, required=True)
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "plan":
            with arguments.output.open("x", encoding="utf-8") as target:
                target.write(json.dumps(plan(), indent=2) + "\n")
            print(f"Planned {len(conditions())} unassigned conditions; no board measurements or hardware qualification.")
        else:
            if arguments.input.is_symlink() or not arguments.input.is_file() or arguments.input.stat().st_size > 2_097_152:
                raise ValueError("evidence manifest must be a bounded regular file")
            with arguments.input.open("rb") as source:
                data = source.read(2_097_153)
            if len(data) > 2_097_152:
                raise ValueError("evidence manifest grew past its byte limit")
            value = json.loads(data.decode("utf-8"), object_pairs_hook=json_object, parse_constant=invalid_constant)
            result = validate(value, arguments.input.resolve(strict=True).parent, arguments.source_root.resolve(strict=True))
            print(json.dumps(result, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, RuntimeError, subprocess.CalledProcessError) as error:
        print("Board evidence check failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
