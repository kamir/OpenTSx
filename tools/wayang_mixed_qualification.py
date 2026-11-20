#!/usr/bin/env python3
"""Collect executed B4 mixed-runtime qualification evidence from a clean checkout."""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("mixed_federation", Path(__file__).with_name("wayang_federation_measurement.py"))
FEDERATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FEDERATION)
FILES = ("mixed/result.json", "mixed/execution.json", "mixed/windows.csv", "mixed/partials.csv",
         "sketch/result.json", "sketch/input.json", "sketch/rust-output.json")
SITES = {"s1": ("java", "A", 1080), "s2": ("java", "A", 1080), "s3": ("rust", "B", 1440)}
TOLERANCE = 3e-12
ALPHA = 0.01
LIMIT = 33_554_432
TESTS = {
    "FederatedStatsIntegrationTest": {"everyActualMixedW3WindowMatchesCentralizedAndAllSixMergeOrders"},
    "MixedSketchQualificationTest": {"mixedSketchesQualifySharedBoundaryZeroBucketAndCollapsedTailCases",
                                     "helperReportsItsVersionAndRejectsMalformedOrOversizedInput"},
}
PROPERTIES = ("opentsx.sourceCommit", "opentsx.rust.b3.runner", "opentsx.rust.b4.sketch", "java.version", "os.name", "os.arch")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, name, expected=None):
    require(type(value) is int and value >= 0 and (expected is None or value == expected), name + " is invalid")
    return value


def finite(value, name):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0, name + " must be finite and non-negative")
    return value


def digest(value, name, prefix=""):
    require(isinstance(value, str) and re.fullmatch(re.escape(prefix) + "[0-9a-f]{64}", value) is not None, name + " must be a SHA256 digest")


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "JSON contains a duplicate object key")
        result[key] = value
    return result


def invalid_constant(value):
    raise ValueError("JSON contains a non-finite number: " + value)


def document(data):
    result = json.loads(data.decode("utf-8"), object_pairs_hook=object_pairs, parse_constant=invalid_constant)
    require(isinstance(result, dict), "evidence JSON must contain an object")
    return result


def regular_file(root, name):
    path = root
    for part in Path(name).parts:
        require(part not in ("", ".", "..") and not Path(part).is_absolute(), "evidence path contains traversal")
        path /= part
        require(not path.is_symlink(), "evidence path contains a symbolic link")
    require(path.is_file() and 0 < path.stat().st_size <= LIMIT, "evidence file is absent, empty or oversized: " + name)
    with path.open("rb") as source:
        data = source.read(LIMIT + 1)
    require(len(data) <= LIMIT, "evidence file grew past its byte limit")
    return data


def source_snapshot(root, output):
    snapshot = FEDERATION.source_snapshot(root, output)
    unknown = FEDERATION.LATENCY.run(["git", "ls-files", "-z", "--others", "--exclude-standard", "--", "rust"], cwd=root)
    require(not unknown, "reported qualification rejects untracked Rust source")
    tracked = FEDERATION.LATENCY.run(["git", "ls-files", "-z", "--", "rust"], cwd=root)
    names = [name for name in tracked.split("\0") if name]
    require("rust/tsx-runner/Cargo.lock" in names, "qualification requires tracked Rust runtime sources")
    snapshot["files"].update({name: hashlib.sha256(regular_file(root, name)).hexdigest() for name in names})
    FEDERATION.LATENCY.require_clean_checkout(root)
    return snapshot


def csv_rows(data, count, required):
    reader = csv.DictReader(io.StringIO(data.decode("utf-8")))
    fields = reader.fieldnames
    require(fields and len(fields) == len(set(fields)) and set(required).issubset(fields), "CSV columns are missing or duplicated")
    rows = list(reader)
    require(len(rows) == count and all(set(row) == set(fields) and all(value is not None for value in row.values()) for row in rows),
            "CSV case count or row width differs")
    return rows


def validate_mixed(result, execution, windows_data, partials_data):
    require(result.get("schema") == "opentsx.rust-b4-mixed-acceptance.v1" and result.get("route") == "signal-cluster", "mixed acceptance schema or route differs")
    counts = {"episodes": 3600, "samples": 2_160_000, "series": 100, "partialCount": 1260,
              "finalWindowCount": 420, "uniqueFinalWindowCount": 420, "driverWindowCount": 420,
              "mergeOrders": 6, "mergeByteComparisons": 2520, "conformanceChecks": 30}
    for name, expected in counts.items():
        integer(result.get(name), name, expected)
    require(result.get("meanM2RelativeTolerance") == TOLERANCE and result.get("alpha") == ALPHA, "mixed numeric contract differs")
    require(execution.get("schema") == "opentsx.rust-b4-mixed-execution.v1" and execution.get("route") == "signal-cluster"
            and result.get("execution") == execution, "mixed execution evidence differs")
    reports, commands = result.get("conformance"), execution.get("sites")
    require(isinstance(reports, dict) and set(reports) == set(SITES) and isinstance(commands, dict) and set(commands) == set(SITES), "mixed site inventory differs")
    for site, (runtime, track, records) in SITES.items():
        report, command = reports[site], commands[site]
        require(report.get("schema") == "opentsx.site-conformance.v1" and report.get("site") == site and report.get("track") == track,
                "site conformance identity differs")
        checks = report.get("checks", [])
        require(len(checks) == 10 and len({check.get("id") for check in checks}) == 10
                and all(check.get("result") == "pass" for check in checks), "site conformance checks did not all pass")
        require(command.get("runtime") == runtime and command.get("track") == track, "site runtime or track differs")
        integer(command.get("inputRecords"), "site inputRecords", records)
        for field in ("commandHash", "specHash", "fragmentHash"):
            digest(command.get(field), field)
        digest(command.get("digest"), "runtime digest", "sha256:")
        require(isinstance(command.get("version"), str) and command["version"] and isinstance(command.get("stateAbi"), str) and command["stateAbi"], "runtime version or state ABI is missing")
        artifact = report.get("artifact", {})
        require(artifact == {"version": command["version"], "digest": command["digest"], "stateAbi": command["stateAbi"]}, "conformance artifact differs from the qualified pin")
        statuses = command.get("statuses", [])
        require(statuses and any(value.get("state") == "running" for value in statuses), "site running status is missing")
        for value in statuses:
            require(value.get("site") == site and value.get("track") == track and value.get("specHash") == command["specHash"]
                    and value.get("artifact") == {"version": command["version"], "digest": command["digest"]}, "authenticated site status identity differs")
        require(statuses[-1].get("state") == "stopped" and statuses[-1].get("records") == records, "site terminal status is incomplete")
    outcomes = execution.get("rustOutcomes", {})
    run = outcomes.get("run", {})
    require(run == outcomes.get("replay") and run.get("state") == "completed" and run.get("completed") is True
            and type(run.get("forwardedRecords")) is int and run["forwardedRecords"] == 420, "Rust run or replay did not complete forwarding")
    traffic = result.get("traffic", [])
    require(len(traffic) == 3 and {row.get("site") for row in traffic} == set(SITES)
            and all(row.get("produceRecords") == 420 and row.get("produceErrors") == 0 and row.get("produceFrameBytes", 0) > 0 for row in traffic), "actual forwarding traffic is incomplete")
    common = ("key", "group_id", "window_type", "window_start_micros", "window_end_micros", "mean_relative_error", "m2_relative_error",
              "actual_positive_bins", "actual_negative_bins", "expected_positive_bins", "expected_negative_bins")
    windows = csv_rows(windows_data, 420, (*common, "exact_fields_equal", "sketch_equal", "final", "revision", "missing_sites"))
    partials = csv_rows(partials_data, 1260, (*common, "site", "track", "spec_hash", "fragment_hash", "payload_sha256"))
    identities = set()
    for rows in (windows, partials):
        require(len({row["key"] for row in rows}) == len(rows), "CSV record identities are duplicated")
        for row in rows:
            for name in ("mean_relative_error", "m2_relative_error"):
                require(finite(float(row[name]), name) <= TOLERANCE, "record moments exceed the qualified tolerance")
            for prefix in ("actual_", "expected_"):
                for sign in ("positive", "negative"):
                    bins = row[prefix + sign + "_bins"]
                    require(re.fullmatch("[0-9]+", bins) is not None and int(bins) < 4096, "W3 evidence contains a collapsed store")
    for row in windows:
        require(row["exact_fields_equal"] == "true" and row["final"] == "true" and row["revision"] == "0" and row["missing_sites"] == "", "final exact fields or emission identity differ")
        identity = tuple(row[name] for name in ("group_id", "window_type", "window_start_micros", "window_end_micros"))
        identities.add(identity)
        for quantile in ("q01", "q50", "q99"):
            for runtime in ("actual", "expected"):
                name = quantile + "_" + runtime
                require(row.get(name + "_within_alpha") == "true", "W3 quantile alpha check failed")
                domain = row.get(name + "_domain")
                require(domain in ("zero-bucket", "outside-zero-without-collapse"), "W3 quantile accuracy domain differs")
                finite(float(row[name + "_absolute_error"]), "quantile absolute error")
                if domain == "zero-bucket":
                    require(row[name + "_relative_error"] == "" and float(row[name]) == 0, "zero-bucket quantile differs")
                else:
                    require(finite(float(row[name + "_relative_error"]), "quantile relative error") <= ALPHA, "W3 quantile exceeds alpha")
    require(len(identities) == 420, "final window identities are duplicated")
    membership = {identity: set() for identity in identities}
    for row in partials:
        site = row["site"]
        require(site in SITES and row["track"] == SITES[site][1], "partial site or track differs")
        command = commands[site]
        require(row["spec_hash"] == command["specHash"] and row["fragment_hash"] == command["fragmentHash"], "partial source plan hashes differ")
        digest(row["payload_sha256"], "partial payload digest")
        identity = tuple(row[name] for name in ("group_id", "window_type", "window_start_micros", "window_end_micros"))
        require(identity in membership and site not in membership[identity], "partial scope is unknown or duplicated")
        membership[identity].add(site)
    require(all(sites == set(SITES) for sites in membership.values()), "each final window needs all three site partials")
    maxima = {}
    for prefix, rows in (("", windows), ("Partial", partials)):
        for metric, column in (("Mean", "mean_relative_error"), ("M2", "m2_relative_error")):
            name = "max" + prefix + metric + "RelativeError"
            maxima[name] = max(float(row[column]) for row in rows)
            require(result.get(name) == maxima[name], "recorded maxima differ from raw CSV")
    differences = sum(row["sketch_equal"] == "false" for row in windows)
    integer(result.get("centralSketchByteDifferences"), "centralSketchByteDifferences", differences)
    maxima["maxQuantileRelativeError"] = max(float(row[q + "_" + runtime + "_relative_error"])
            for row in windows for q in ("q01", "q50", "q99") for runtime in ("actual", "expected")
            if row[q + "_" + runtime + "_domain"] == "outside-zero-without-collapse")
    maxima.update({name: result[name] for name in counts})
    maxima["centralSketchByteDifferences"] = differences
    return maxima


def validate_sketch(result, request, output):
    require(result.get("schema") == "opentsx.rust-b4-sketch-qualification.v1" and request.get("schema") == "opentsx.rust-b4-sketch-input.v1"
            and output.get("schema") == "opentsx.rust-b4-sketch-output.v1", "sketch evidence schemas differ")
    integer(result.get("caseCount"), "sketch caseCount", 58)
    integer(result.get("mixedMergeOrders"), "sketch mixedMergeOrders", 348)
    require(result.get("alpha") == ALPHA and output.get("alpha") == ALPHA and result.get("maxBins") == 4096
            and output.get("maxBins") == 4096 and result.get("rustVersion") == output.get("version"), "sketch numeric or runtime contract differs")
    digest(result.get("rustArtifactDigest"), "sketch helper digest", "sha256:")
    cases, inputs, outputs = result.get("cases", []), request.get("cases", []), output.get("cases", [])
    require(len(cases) == 58 and len(inputs) == 116 and len(outputs) == 116, "sketch case evidence is incomplete")
    expected_domains = {"ordinary": 3, "zero": 3, "threshold": 6, "boundary": 42,
                        "collapsed-negative-merge": 1, "collapsed-negative-site": 1,
                        "collapsed-positive-merge": 1, "collapsed-positive-site": 1}
    require({domain: sum(case.get("domain") == domain for case in cases) for domain in expected_domains} == expected_domains,
            "sketch qualification domain coverage differs")
    by_input = {row.get("name"): row for row in inputs}
    by_output = {row.get("name"): row for row in outputs}
    require(len(by_input) == 116 and len(by_output) == 116 and len({row.get("name") for row in cases}) == 58, "sketch case identities are duplicated")
    for case in cases:
        name, domain = case.get("name"), case.get("domain")
        require(isinstance(name, str) and name and isinstance(domain, str) and domain, "sketch case identity is missing")
        integer(case.get("mergeOrders"), "case mergeOrders", 6)
        samples = case.get("samples", [])
        integer(case.get("sampleCount"), "case sampleCount", len(samples))
        for suffix in ("all", "s3"):
            key = name + "-" + suffix
            require(key in by_input and key in by_output, "sketch request or output case is missing")
            selected = samples if suffix == "all" else [sample for sample in samples if sample.get("site") == "s3"]
            expected = [{field: sample[field] for field in ("timestampMicros", "valueBits")} for sample in selected]
            require(by_input[key].get("samples") == expected, "sketch request samples differ from the qualification evidence")
            require(case.get("rust" if suffix == "all" else "rustSitePartial") == by_output[key], "raw Rust sketch output differs")
        for field in ("meanRelativeError", "m2RelativeError"):
            require(field in case, "sketch moment check is missing")
            value = case.get(field)
            require(value is None and case.get("java", {}).get("validCount") == 0 or value is not None and finite(value, field) <= TOLERANCE,
                    "sketch moments exceed the qualified tolerance")
        for runtime in ("java", "rust", "mixed"):
            value = case.get(runtime, {})
            for counter in ("validCount", "nanCount", "gapCount", "zeroCount"):
                integer(value.get(counter), "sketch " + counter)
            for field in ("minBits", "maxBits"):
                require(isinstance(value.get(field), str) and re.fullmatch("[0-9a-f]{16}", value[field]) is not None, "sketch exact value bits are missing")
            for exact in ("validCount", "nanCount", "gapCount", "minBits", "maxBits"):
                require(value.get(exact) == case.get("java", {}).get(exact), "sketch exact statistics differ")
            counted = value["zeroCount"]
            for sign in ("positive", "negative"):
                bins = value.get(sign)
                require(isinstance(bins, dict) and len(bins) <= 4096 and (domain.startswith("collapsed") or len(bins) < 4096), "sketch bin limit or no-collapse proof differs")
                require(all(type(count) is int and count > 0 for count in bins.values()), "sketch bin counts are invalid")
                counted += sum(bins.values())
            require(counted == value["validCount"], "sketch stores do not account for the valid sample count")
        checks = case.get("quantileChecks", [])
        require(len(checks) == 15 and {(check.get("runtime"), check.get("q")) for check in checks}
                == {(runtime, q) for runtime in ("java", "rust", "mixed") for q in (0, 0.01, 0.5, 0.99, 1)}, "sketch quantile checks are incomplete")
        for check in checks:
            accuracy = check.get("accuracyDomain")
            require(accuracy in ("empty", "zero-bucket", "outside-zero-without-collapse", "excluded-collapsed-tail"), "sketch accuracy domain differs")
            if accuracy == "outside-zero-without-collapse":
                require(check.get("withinAlphaInterval") is True, "sketch alpha check failed")
            elif accuracy == "zero-bucket":
                require(check.get("valueBits") == "0000000000000000", "sketch zero-bucket result differs")
            elif accuracy == "empty":
                require(case["java"]["validCount"] == 0, "nonempty sketch has an empty accuracy domain")
            else:
                require(domain.startswith("collapsed") and case.get("lowMagnitudeTailExceedsAlpha") is True
                        and case.get("highMagnitudeTailWithinAlpha") is True and case.get("uncollapsedBinCount") == 5001,
                        "collapsed-tail qualification is incomplete")
    boundary_differences = sum(case.get("javaRustSketchEqual") is False for case in cases if case.get("domain") in ("boundary", "threshold"))
    integer(result.get("boundaryDifferencesObserved"), "boundaryDifferencesObserved", boundary_differences)
    return {"cases": len(cases), "mergeComparisons": result["mixedMergeOrders"], "boundaryDifferences": boundary_differences,
            "collapsedCases": sum(case["domain"].startswith("collapsed") for case in cases)}


def test_evidence(root, commit, mixed, sketch):
    summaries = {}
    for name, required in TESTS.items():
        relative = "opentsx-wayang/target/surefire-reports/TEST-org.opentsx.wayang." + name + ".xml"
        data = regular_file(root, relative)
        suite = ET.fromstring(data)
        require(suite.tag == "testsuite" and suite.get("name") == "org.opentsx.wayang." + name, "JUnit suite identity differs")
        cases = suite.findall("testcase")
        count = int(suite.attrib["tests"])
        require(count > 0 and count == len(cases) and all(int(suite.attrib[field]) == 0 for field in ("failures", "errors", "skipped"))
                and all(case.find(tag) is None for case in cases for tag in ("failure", "error", "skipped")), "JUnit suite did not pass with zero skips")
        require(required.issubset({case.get("name") for case in cases}), "required qualification method did not execute")
        properties = object_pairs([(item.attrib["name"], item.attrib["value"]) for item in suite.findall("properties/property") if item.attrib.get("name") in PROPERTIES])
        require(properties.get("opentsx.sourceCommit") == commit, "JUnit source commit differs from the clean checkout")
        summaries[name] = {"sourceFile": relative, "sha256": hashlib.sha256(data).hexdigest(), "tests": count,
                           "failures": 0, "errors": 0, "skipped": 0, "methods": sorted(case.attrib["name"] for case in cases), "properties": properties}
    require(mixed.get("sourceCommit") == commit and sketch.get("sourceCommit") == commit, "raw result source commit differs from the clean checkout")
    pins = {}
    for name, suite, property_name, default, expected in (
            ("runner", "FederatedStatsIntegrationTest", "opentsx.rust.b3.runner", None, mixed["execution"]["sites"]["s3"]["digest"]),
            ("sketch", "MixedSketchQualificationTest", "opentsx.rust.b4.sketch", "../rust/tsx-runner/target/debug/examples/b4_sketch", sketch["rustArtifactDigest"])):
        value = summaries[suite]["properties"].get(property_name, default)
        require(isinstance(value, str) and value, "JUnit runtime executable path is missing")
        path = Path(value)
        if not path.is_absolute():
            path = root / "opentsx-wayang" / path
        path = path.resolve(strict=True)
        require(path.is_file() and 0 < path.stat().st_size <= 268_435_456, "qualified executable is absent or oversized")
        actual = FEDERATION.LATENCY.sha256(path)
        require("sha256:" + actual == expected, "actual executable differs from its executed artifact digest")
        pins[name] = {"file": str(path), "sha256": actual}
    return {"schema": "opentsx.rust-b4-qualified-tests.v1", "sourceCommit": commit, "suites": summaries, "executables": pins}


def provenance(root, sketch):
    commands = [["java", "-version"], ["rustc", "-Vv"], ["cargo", "--version"], ["go", "version"]]
    versions = {command[0]: FEDERATION.LATENCY.run(command, cwd=root).strip() for command in commands}
    require(sketch.get("javaVersion") in versions["java"], "collector JDK differs from the executed qualification JDK")
    require(re.fullmatch(r"go version go1\.25\.2 \S+", versions["go"]) is not None, "collector needs the pinned Go 1.25.2 toolchain")
    hardware = FEDERATION.LATENCY.run(["sysctl", "-n", "hw.model", "machdep.cpu.brand_string", "hw.memsize"], cwd=root).strip() \
        if platform.system() == "Darwin" else Path("/proc/cpuinfo").read_text()
    binaries = {}
    for name in ("KAFSCALE_BROKER_BINARY", "KAFSCALE_PROXY_BINARY", "KAFSCALE_CLUSTER_BINARY"):
        value = os.environ.get(name)
        if value:
            path = Path(value).resolve(strict=True)
            require(path.is_file(), name + " must name an actual binary")
            binaries[name] = {"file": str(path), "sha256": FEDERATION.LATENCY.sha256(path)}
    return {"collectedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "platform": platform.platform(),
            "system": platform.system(), "machine": platform.machine(), "hardware": " / ".join(hardware.splitlines()),
            "cpuCount": os.cpu_count(), "toolchains": versions, "commands": commands,
            "executionHost": {field: sketch.get(field) for field in ("osName", "osArch", "javaVersion")},
            "kafScaleFixturePin": {"version": "1.6.0", "commit": FEDERATION.LATENCY.KAFSCALE_COMMIT},
            "kafScaleBinaries": binaries, "physicalLinuxBoardAcceptance": False, "armv7HardwareAcceptance": "open"}


def report(snapshot, metadata, mixed, sketch):
    lines = ["# Qualify bounded mixed W3 statistics", "",
             f"Executed qualification evidence for OpenTSx `{snapshot['commit']}`.", "",
             f"Execution host: {metadata['executionHost']['osName']} ({metadata['executionHost']['osArch']}). "
             f"Collection host: {metadata['platform']}. Hardware: {metadata['hardware']}.", "",
             "This is local lab qualification. It does not establish physical Linux board acceptance. "
             "ARMv7 hardware acceptance remains open.", "",
             "The bounded signal-cluster plan uses Java at s1 and s2 and the actual signed Rust runner at s3. "
             "All three sites supply accepted conformance reports before inventory admission.", "",
             "| Evidence | Executed count |", "|---|---:|", f"| Input episodes | {mixed['episodes']} |", f"| Input samples | {mixed['samples']} |",
             f"| Source series | {mixed['series']} |", f"| Site partials | {mixed['partialCount']} |", f"| Unique final windows | {mixed['uniqueFinalWindowCount']} |",
             f"| Site conformance checks | {mixed['conformanceChecks']} |", f"| Arrival orders per window | {mixed['mergeOrders']} |", f"| Exact merge byte comparisons | {mixed['mergeByteComparisons']} |",
             f"| Numerical qualification cases | {sketch['cases']} |", f"| Numerical merge comparisons | {sketch['mergeComparisons']} |", "",
             f"Maximum relative mean error: {mixed['maxMeanRelativeError']:.17g}. "
             f"Maximum relative M2 error: {mixed['maxM2RelativeError']:.17g}. Retained tolerance: {TOLERANCE:.17g}.", "",
             f"Maximum W3 quantile relative error outside the zero bucket: {mixed['maxQuantileRelativeError']:.17g}. "
             f"Retained alpha: {ALPHA}. Actual and reference W3 stores remain below 4096 bins.", "",
             f"Numerical boundary sketch differences observed: {sketch['boundaryDifferences']}. "
             f"Collapsed-tail cases: {sketch['collapsedCases']}. Collapsed low-magnitude tails retain their explicit accuracy exception.", "",
             "Read [windows.csv](mixed/windows.csv) for actual and centralized moments, raw lower quantiles, accuracy domains and bin spans. "
             "Read [partials.csv](mixed/partials.csv) for all source identities, statistics and sketch bytes.", "",
             "Read [mixed execution](mixed/execution.json) for authenticated status histories, runtime pins, signed-command hashes and actual run/replay outcomes. "
             "Read [numerical results](sketch/result.json), [requests](sketch/input.json) and [Rust output](sketch/rust-output.json) for all cases.", "",
             "[test-evidence.json](test-evidence.json) records exact successful JUnit methods and zero-skip counts, the explicit source-commit property and actual executable hashes. "
             "This records the owner-run source association and is not an attestation of the build process.", "",
             "[metadata.json](metadata.json) records source hashes, collection hardware, executed host identity, toolchain commands and the KafScale fixture source pin. "
             "[manifest.json](manifest.json) pins every copied raw file and generated report. KafScale binary hashes are present only for supplied existing runtime paths.", ""]
    return "\n".join(lines)


def collect(root, output):
    root = root.resolve(strict=True)
    output = output.absolute()
    require(".." not in output.parts and not output.exists(), "output must be a new directory without traversal")
    require(not any(path.is_symlink() for path in (output, *output.parents)), "output path must not contain symbolic links")
    snapshot = source_snapshot(root, output)
    raw = {name: regular_file(root, "opentsx-wayang/target/rust-b4-" + name) for name in FILES}
    mixed_result = document(raw["mixed/result.json"])
    mixed = validate_mixed(mixed_result, document(raw["mixed/execution.json"]), raw["mixed/windows.csv"], raw["mixed/partials.csv"])
    sketch_result = document(raw["sketch/result.json"])
    sketch = validate_sketch(sketch_result, document(raw["sketch/input.json"]), document(raw["sketch/rust-output.json"]))
    tests = test_evidence(root, snapshot["commit"], mixed_result, sketch_result)
    metadata = provenance(root, sketch_result)
    require(source_snapshot(root, output) == snapshot, "source changed while collecting qualification evidence")
    require(all(regular_file(root, "opentsx-wayang/target/rust-b4-" + name) == data for name, data in raw.items()), "raw evidence changed during collection")
    require(all(FEDERATION.LATENCY.sha256(Path(pin["file"])) == pin["sha256"] for pin in tests["executables"].values()), "qualified executable changed during collection")
    metadata.update(schema="opentsx.rust-b4-qualification-metadata.v1", sourceSnapshot=snapshot,
                    summary={"mixed": mixed, "sketch": sketch}, physicalLinuxBoardAcceptance=False)
    output.mkdir(parents=True, exist_ok=False)
    for name, data in raw.items():
        path = output / name
        path.parent.mkdir(exist_ok=True)
        path.write_bytes(data)
    (output / "REPORT.md").write_text(report(snapshot, metadata, mixed, sketch), encoding="utf-8")
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    (output / "test-evidence.json").write_text(json.dumps(tests, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    manifest = {"schema": "opentsx.rust-b4-qualification-manifest.v1", "sourceCommit": snapshot["commit"],
                "files": {name: FEDERATION.LATENCY.sha256(output / name) for name in (*FILES, "REPORT.md", "metadata.json", "test-evidence.json")}}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args(argv)
    try:
        manifest = collect(arguments.source_root, arguments.output)
        print(json.dumps({"sourceCommit": manifest["sourceCommit"], "files": len(manifest["files"]), "physicalLinuxBoardAcceptance": False}, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError, RuntimeError, ET.ParseError, subprocess.CalledProcessError) as error:
        print("Mixed qualification collection failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
