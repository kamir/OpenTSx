import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import rust_b1_acceptance as common
import rust_b1_conformance as conformance
import rust_runner_artifacts as artifacts


ROOT = Path(__file__).resolve().parents[1]
VECTOR_SUITES = ("clean_catalog", "stats_vectors", "retrieval_vectors", "pair_vector_vectors",
                 "catalog_admission", "catalog_flow")
VECTORS = (
    "opentsx-model/src/test/resources/testvectors/wire-format.json",
    "opentsx-model/src/test/resources/testvectors/series-id.json",
    "opentsx-model/src/test/resources/testvectors/xxh64.json",
    "opentsx-model/src/test/resources/testvectors/bucket-checksum.json",
    "opentsx-model/src/test/resources/testvectors/time-deltas.json",
    "docs/datamodel-2026/testvectors/clean-ops.v1.json",
    "docs/datamodel-2026/testvectors/stats-window.v1.json",
    "docs/datamodel-2026/testvectors/align-vector.v1.json",
    "docs/datamodel-2026/testvectors/segment-threshold.v1.json",
    "docs/datamodel-2026/testvectors/segment-extremum.v1.json",
    "docs/datamodel-2026/testvectors/sax-word.v1.json",
    "docs/datamodel-2026/testvectors/dq-result.v1.json",
    "docs/datamodel-2026/testvectors/pair-xcorr.v1.json",
    "docs/datamodel-2026/testvectors/execution-contracts.v1.json",
)


def utc_now():
    return datetime.now(timezone.utc)


def expiry(value, issued):
    if not isinstance(value, str) or re.fullmatch(r"[0-9]{4}(?:-[0-9]{2}){2}T[0-9]{2}(?::[0-9]{2}){2}(?:\.[0-9]{1,9})?Z", value) is None:
        raise RuntimeError("qualification expiry must be an explicit RFC3339 UTC instant ending in Z")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise RuntimeError("qualification expiry is not a valid UTC instant") from error
    if parsed <= issued:
        raise RuntimeError("qualification expiry must be after its issued UTC instant")
    return value


def read_input(value, snapshots, limit):
    path = Path(value)
    if ".." in path.parts or path.is_symlink():
        raise RuntimeError("qualification inputs must not contain parent traversal or symbolic links")
    path = path.resolve(strict=True)
    common.safe_path(path)
    if not path.is_file() or path in snapshots:
        raise RuntimeError("qualification inputs must be distinct regular files")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise RuntimeError("a qualification input exceeds its byte limit")
    snapshots[path] = hashlib.sha256(data).hexdigest()
    return path, data


def version(binary, expected):
    try:
        result = subprocess.run([str(binary), "--version"], capture_output=True, check=False, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("cannot read the actual qualification executable version") from error
    if result.returncode != 0 or result.stderr or result.stdout != f"tsx-runner {expected}\n".encode():
        raise RuntimeError("the actual qualification executable does not match the stable runner version")
    return expected


def vector_suites(logs):
    executed = set()
    for text in logs:
        headers = list(re.finditer(r"(?m)^\s*Running (.+?)(?:\r?$)", text))
        for index, header in enumerate(headers):
            match = re.match(r"tests/([a-z0-9_]+)\.rs(?:\s|$)", header.group(1))
            if match is None or match[1] not in VECTOR_SUITES:
                continue
            section = text[header.end():headers[index + 1].start() if index + 1 < len(headers) else len(text)]
            summary = re.search(r"(?m)^test result: ok\. ([0-9]+) passed;", section)
            if summary is None or int(summary[1]) == 0:
                raise RuntimeError(f"qualification vector suite {match[1]} did not execute")
            executed.add(match[1])
    if executed != set(VECTOR_SUITES):
        raise RuntimeError("qualification logs omit required executed B2 vector suites")
    return list(VECTOR_SUITES)


def generate(runner, bootstrap, test_logs, protocol_report, placement_report, expires, output,
             placement_manifest=None):
    output = Path(output)
    if ".." in output.parts or output.is_symlink():
        raise RuntimeError("qualification output must not traverse parents or symbolic links")
    output = output.resolve()
    common.safe_path(output)
    if output.exists():
        raise RuntimeError("qualification output already exists; retain the current owner evidence")
    bootstrap = conformance.endpoint(bootstrap)
    issued = utc_now()
    expires = expiry(expires, issued)
    if not isinstance(test_logs, (list, tuple)) or not 2 <= len(test_logs) <= 16:
        raise RuntimeError("qualification requires two to sixteen actual Rust test logs")
    snapshots = {}
    binary, binary_bytes = read_input(runner, snapshots, 268_435_456)
    if not os.access(binary, os.X_OK):
        raise RuntimeError("qualification requires an executable runner")
    texts, hashes = [], []
    for path in test_logs:
        resolved, data = read_input(path, snapshots, 33_554_432)
        texts.append(data.decode("utf-8"))
        hashes.append(snapshots[resolved])
    if len(set(hashes)) != len(hashes):
        raise RuntimeError("duplicate test logs cannot inflate qualification counts")
    if any(re.search(r"(?m)^error(?:\[E[0-9]+\])?:", text) for text in texts):
        raise RuntimeError("qualification test logs contain a compiler or test-command failure")
    counts = artifacts.test_counts(texts)
    if type(counts["passed"]) is not int or counts["passed"] < 259:
        raise RuntimeError("qualification tests did not execute the required runner and contract cases")
    for field in ("failed", "ignored", "measured", "filtered"):
        common.require_int(counts[field], 0, f"qualification tests {field}")
    counts["vectorSuites"] = vector_suites(texts)
    _, protocol_bytes = read_input(protocol_report, snapshots, 2_097_152)
    protocol = conformance.validate_report(common.parse_json(protocol_bytes), bootstrap)
    _, placement_bytes = read_input(placement_report, snapshots, 2_097_152)
    placement = common.parse_json(placement_bytes)
    common.require_fields(placement, {"java", "rust"}, "qualification producer placement")
    common.validate_placement(placement["java"])
    if not isinstance(placement["rust"], dict):
        raise RuntimeError("qualification Rust producer placement is missing")
    fixture_hash = placement["rust"].get("fixtureSha256")
    if not isinstance(fixture_hash, str) or re.fullmatch(r"[0-9a-f]{64}", fixture_hash) is None:
        raise RuntimeError("qualification producer placement requires the actual Java fixture SHA-256")
    if placement_manifest is not None:
        path, data = read_input(placement_manifest, snapshots, 2_097_152)
        common.validate_placement_manifest(common.parse_json(data))
        fixture_hash = snapshots[path]
    common.validate_placement(placement["rust"], fixture_hash)
    vectors = {}
    for name in VECTORS:
        path, _ = read_input(ROOT / name, snapshots, 2_097_152)
        vectors[name] = snapshots[path]
    actual_version = version(binary, conformance.runner_version())
    result = {"schema": "opentsx.rust-b3-qualification.v1",
              "artifact": {"version": actual_version, "digest": "sha256:" + hashlib.sha256(binary_bytes).hexdigest(),
                           "stateAbi": "opentsx.rust.site-state.v1", "catalog": "tsx-catalog/1"},
              "endpoint": bootstrap, "issued": issued.isoformat(timespec="microseconds").replace("+00:00", "Z"),
              "expires": expires, "vectors": vectors, "tests": counts, "protocol": protocol,
              "placement": placement, "logs": hashes}
    for path, expected in snapshots.items():
        if not path.is_file() or path.is_symlink() or common.digest(path) != expected:
            raise RuntimeError("qualification inputs changed while the owner evidence was generated")
    encoded = (json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")
    if len(encoded) > 2_097_152:
        raise RuntimeError("qualification evidence exceeds two MiB")
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(encoded)
        stream.flush()
        os.fsync(stream.fileno())
    return result


def main():
    parser = argparse.ArgumentParser(description="Bind prior executed Rust and Proxy evidence into owner-local B3 qualification.")
    parser.add_argument("--runner", type=Path, required=True)
    parser.add_argument("--bootstrap", required=True)
    parser.add_argument("--test-log", type=Path, action="append", required=True)
    parser.add_argument("--protocol-report", type=Path, required=True)
    parser.add_argument("--placement-report", type=Path, required=True)
    parser.add_argument("--placement-manifest", type=Path)
    parser.add_argument("--expires", required=True, help="explicit future RFC3339 UTC instant ending in Z")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = generate(args.runner, args.bootstrap, args.test_log, args.protocol_report, args.placement_report,
                      args.expires, args.output, args.placement_manifest)
    print(json.dumps({"schema": result["schema"], "output": str(args.output), "artifact": result["artifact"],
                      "passedTests": result["tests"]["passed"], "protocolCases": result["protocol"]["passedCases"],
                      "placementKeys": result["placement"]["rust"]["matched"]}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, UnicodeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
