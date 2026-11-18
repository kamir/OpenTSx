#!/usr/bin/env python3
"""Measure bounded W3 federation traffic through pinned KafScale Proxies."""

import argparse
import csv
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import re
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("wayang_increment_latency", Path(__file__).with_name("wayang_increment_latency.py"))
LATENCY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LATENCY)
ROUTES = ("centralized", "signal-cluster", "job-state")
FIELDS = ("route", "site", "topic", "produce_frame_bytes", "produce_records", "produce_errors",
          "http_body_bytes", "http_records", "tls_client_bytes", "elapsed_ms")
SOURCE_FILES = ("tools/wayang_federation_measurement.py", "tools/wayang_increment_latency.py",
                "tools/kafscale_proxy_cluster.go", "tools/build_kafscale_proxy.py", "tools/kafscale_metadata_overlay.py",
                "tools/kafscale_proxy_protocol_overlay.py", "tools/kafscale_proxy_protocol_test.go",
                "opentsx-wayang/src/test/java/org/opentsx/wayang/FederationMeasurement.java",
                "opentsx-wayang/src/test/java/org/opentsx/wayang/FederatedWindparkData.java",
                "opentsx-wayang/src/test/java/org/opentsx/wayang/LatencyProtocolObserver.java",
                "opentsx-windpark/scenarios/ostsee-demo.yaml")


def source_snapshot(root, output=None):
    root = root.resolve()
    LATENCY.require_clean_checkout(root)
    paths = ["pom.xml", ".mvn", "tools", "systems", "docs/datamodel-2026/testvectors"]
    for module in sorted(root.glob("opentsx-*")):
        if not module.is_dir():
            continue
        for name in ("src", "pom.xml", "scenarios"):
            if (module / name).exists():
                paths.append(str((module / name).relative_to(root)))

    def included(name):
        return output is None or not (root / name).is_relative_to(output.resolve())

    untracked = LATENCY.run(["git", "ls-files", "-z", "--others", "--exclude-standard", "--", *paths], cwd=root)
    unknown = [name for name in untracked.split("\0") if name and included(name)]
    if unknown:
        raise RuntimeError("reported measurements reject untracked source: " + ", ".join(unknown))
    tracked = LATENCY.run(["git", "ls-files", "-z", "--", *paths], cwd=root)
    names = [name for name in tracked.split("\0") if name and included(name)]
    if not set(SOURCE_FILES).issubset(names):
        raise RuntimeError("measurement runtime and fixture sources must be tracked")
    commit = LATENCY.run(["git", "rev-parse", "HEAD"], cwd=root).strip()
    files = {name: LATENCY.sha256(root / name) for name in names}
    LATENCY.require_clean_checkout(root)
    if LATENCY.run(["git", "rev-parse", "HEAD"], cwd=root).strip() != commit:
        raise RuntimeError("source changed while recording its snapshot")
    return {"commit": commit, "files": files}


def require_same_source(root, snapshot, output):
    if source_snapshot(root, output) != snapshot:
        raise RuntimeError("source changed during measurement")


def numeric(value, name, *, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name} must be finite")
    if value < 0 or positive and value == 0:
        raise ValueError(f"{name} must be {'positive' if positive else 'non-negative'}")
    return value


def read_results(output):
    run = json.loads((output / "run.json").read_text())
    if run.get("correctnessPassed") is not True or run.get("routeSAdaptersEqualJ") is not True:
        raise ValueError("measurement correctness checks did not pass")
    topology = run.get("topology", {})
    if topology.get("siteAgentInstances") != 3 or topology.get("brokers") != 4:
        raise ValueError("measurement requires three site-agent instances and four brokers")
    if type(topology.get("jvmProcesses")) is not int or topology["jvmProcesses"] < 1:
        raise ValueError("measurement needs its actual JVM process count")
    for name in ("episodes", "values", "rawSeries", "windows", "partials", "comparisons"):
        if type(run.get(name)) is not int or run[name] < 1:
            raise ValueError(f"{name} must be a positive integer")
    tolerance = numeric(run.get("dt2Tolerance"), "dt2Tolerance", positive=True)
    for name in ("maxRelativeMeanError", "maxRelativeM2Error"):
        if numeric(run.get(name), name) > tolerance:
            raise ValueError(f"{name} exceeds the recorded D-T2 tolerance")
    for name in ("sourceSpecHash", "scenarioSha256"):
        if not isinstance(run.get(name), str) or not re.fullmatch("[0-9a-f]{64}", run[name]):
            raise ValueError(f"{name} must be a SHA256 digest")
    if not isinstance(run.get("referenceOrdering"), str) or not run["referenceOrdering"].strip():
        raise ValueError("measurement needs its reference ordering")
    with (output / "windows.csv").open(newline="") as source:
        evidence = csv.DictReader(source)
        if not evidence.fieldnames or sum(1 for _ in evidence) != run["windows"]:
            raise ValueError("window evidence does not match run.json")
    with (output / "bytes.csv").open(newline="") as source:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != FIELDS:
            raise ValueError("bytes.csv has an unsupported column schema")
        rows = list(reader)
    identities = set()
    sites = {route: set() for route in ROUTES}
    clocks = {route: set() for route in ROUTES}
    for row in rows:
        route, site = row["route"], row["site"]
        if route not in ROUTES or not site or (route, site) in identities:
            raise ValueError("measurement has an unsupported or duplicate route/site row")
        identities.add((route, site))
        sites[route].add(site)
        unused = set(FIELDS[3:6] if route == "job-state" else FIELDS[6:9])
        for name in FIELDS[3:-1]:
            if name in unused:
                if row[name] != "":
                    raise ValueError(f"inapplicable {name} must be blank")
                row[name] = None
                continue
            if not isinstance(row[name], str) or not row[name].isdigit():
                raise ValueError(f"{name} must be a non-negative integer")
            row[name] = int(row[name])
        row["elapsed_ms"] = numeric(float(row["elapsed_ms"]), "elapsed_ms", positive=True)
        clocks[route].add(row["elapsed_ms"])
        if route != "job-state" and row["produce_errors"] != 0:
            raise ValueError("measurement contains Produce errors")
        if route == "job-state":
            if row["http_body_bytes"] < 1 or row["http_records"] < 1 or row["tls_client_bytes"] < 1:
                raise ValueError("job-state rows need measured HTTP and TLS traffic")
        else:
            if not row["topic"] or row["produce_frame_bytes"] < 1 or row["produce_records"] < 1:
                raise ValueError("Kafka routes need measured Produce bytes and records")
    if any(len(value) != 3 for value in sites.values()) or any(value != sites[ROUTES[0]] for value in sites.values()):
        raise ValueError("every route needs the same three measured sites")
    if any(len(value) != 1 for value in clocks.values()):
        raise ValueError("every route needs one measured elapsed time")
    return run, rows


def report(output, metadata):
    run, rows = read_results(output)
    topology = run["topology"]
    lines = ["# Measure bounded W3 federation traffic", ""]
    paragraph = lambda text: LATENCY.paragraph(lines, text)
    paragraph(f"Measured with OpenTSx {metadata['openTsxCommit']} at {metadata['startedAt']}.")
    paragraph(f"Topology: {topology['siteAgentInstances']} site-agent instances within "
              f"{topology['jvmProcesses']} JVM process{'es' if topology['jvmProcesses'] != 1 else ''} and "
              f"{topology['brokers']} Go broker processes. This is the bounded local execution profile.")
    paragraph("Execution topology excludes build and transient conformance-check subprocesses.")
    paragraph(f"KafScale v1.6.0 source: {LATENCY.KAFSCALE_COMMIT}. JDK: {metadata['javaVersion'].strip().splitlines()[0]}.")
    paragraph(f"OS: {metadata['platform']}. Hardware: {metadata['hardware']}.")
    paragraph("Kafka bytes are complete PRODUCE request frames observed before forwarding, including the four-byte "
              "length prefix. Record counts include every request attempt. Failed-record acknowledgements are zero.")
    paragraph("HTTP body bytes count only aggregate-delivery application bodies. TLS client bytes count the actual encrypted "
              "client-to-server TCP stream measured by the transparent proxy. These counters describe separate layers. "
              "Inapplicable transport cells remain blank.")
    paragraph("TLS client bytes include plan polls, lifecycle status and delivery acknowledgements during the execution interval.")
    paragraph("Elapsed time is measured once per route and repeated on its three site rows. Each table displays that route clock once.")
    paragraph("Centralized elapsed time covers raw-record transfer and acknowledgements. "
              "Signal-cluster and job-state elapsed time also includes site computation and central merging. "
              "These clocks measure different work and do not establish a latency or speed comparison.")
    totals = []
    http = []
    for route in ROUTES:
        selected = [r for r in rows if r["route"] == route]
        elapsed = f"{selected[0]['elapsed_ms']:.3f}"
        totals.append([route, "" if route == "job-state" else str(sum(r["produce_frame_bytes"] for r in selected)),
                       "" if route == "job-state" else str(sum(r["produce_records"] for r in selected)), elapsed])
        http.append([route, str(sum(r["http_body_bytes"] for r in selected)) if route == "job-state" else "",
                     str(sum(r["http_records"] for r in selected)) if route == "job-state" else "",
                     str(sum(r["tls_client_bytes"] for r in selected)) if route == "job-state" else ""])
    lines.extend(LATENCY.table(["Route", "Kafka bytes", "Records", "Elapsed ms"], totals) + [""])
    lines.extend(LATENCY.table(["Route", "HTTP body bytes", "HTTP records", "TLS client bytes"], http) + [""])
    paragraph(f"Correctness checks passed for {run['episodes']} episodes, {run['values']} values, "
              f"{run['rawSeries']} raw series, {run['windows']} windows, {run['partials']} partials and {run['comparisons']} comparisons.")
    paragraph(f"Maximum relative mean error: {run['maxRelativeMeanError']:.17g}. Maximum relative M2 error: "
              f"{run['maxRelativeM2Error']:.17g}. Recorded D-T2 tolerance: {run['dt2Tolerance']:.17g}.")
    paragraph(f"Reference ordering: {run['referenceOrdering']}. Both signal-cluster adapters equal the job-state route.")
    paragraph(f"Source specHash: {run['sourceSpecHash']}. Scenario SHA256: {run['scenarioSha256']}.")
    paragraph("Read bytes.csv for every measured site and transport counter, windows.csv for numerical comparisons, "
              "and run.json for executed workload and topology evidence. metadata.json records source hashes, "
              "pinned broker source, hardware, commands and the SHA256 manifest of the private runtime snapshot.")
    (output / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--kafscale-source", type=Path)
    args = parser.parse_args()
    snapshot = source_snapshot(ROOT)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.java_home.resolve())
    env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    java = str(args.java_home.resolve() / "bin/java")
    java_version = LATENCY.run([java, "-version"], cwd=ROOT, env=env)
    if not re.search(r'version "(?:17|21)\.', java_version):
        raise ValueError("the measurement requires JDK 17 or 21")
    hardware = LATENCY.run(["sysctl", "-n", "hw.model", "machdep.cpu.brand_string", "hw.memsize"]).strip() \
        if platform.system() == "Darwin" else Path("/proc/cpuinfo").read_text()
    metadata = {"startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "status": "building",
                "openTsxCommit": snapshot["commit"],
                "platform": platform.platform(), "machine": platform.machine(), "cpuCount": os.cpu_count(),
                "hardware": " / ".join(hardware.splitlines()), "javaVersion": java_version,
                "kafScaleCommit": LATENCY.KAFSCALE_COMMIT, "commands": [],
                "invocation": {"cwd": str(Path.cwd()), "argv": list(sys.argv), "python": sys.executable},
                "sourceSha256": {name: snapshot["files"][name] for name in SOURCE_FILES}, "sourceSnapshot": snapshot}

    def save():
        (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")

    def command(argv, **kwargs):
        metadata["commands"].append(argv)
        save()
        return LATENCY.run(argv, **kwargs)

    try:
        with tempfile.TemporaryDirectory(prefix="opentsx-federation-") as temporary:
            work = Path(temporary)
            source = work / "kafscale"
            with (output / "build.log").open("w") as log:
                clone = ["git", "clone", "--no-hardlinks", str(args.kafscale_source.resolve()), str(source)] if args.kafscale_source else \
                    ["git", "clone", "--branch", "v1.6.0", "--single-branch", LATENCY.KAFSCALE_REPOSITORY, str(source)]
                command(clone, cwd=ROOT, log=log)
                command(["git", "checkout", "--detach", LATENCY.KAFSCALE_COMMIT], cwd=source, log=log)
                if LATENCY.run(["git", "rev-parse", "HEAD"], cwd=source).strip() != LATENCY.KAFSCALE_COMMIT:
                    raise ValueError("KafScale source does not match the pinned commit")
                go_env = env | {"GOTOOLCHAIN": "go1.25.2"}
                metadata["goVersion"] = command(["go", "version"], cwd=source, env=go_env).strip()
                binary = work / "kafscale-broker"
                overlay = LATENCY.build_kafscale_proxy.transport_overlay(source, work)
                command(["go", "build", "-o", str(binary), f"-overlay={overlay}", "./cmd/broker"], cwd=source, env=go_env, log=log)
                proxy = work / "kafscale-proxy"
                command(["go", "build", "-o", str(proxy), f"-overlay={overlay}", "./cmd/proxy"], cwd=source, env=go_env, log=log)
                cluster = work / "kafscale-cluster"
                command(["go", "build", "-o", str(cluster), f"-overlay={overlay}", str(ROOT / "tools/kafscale_proxy_cluster.go")], cwd=source, env=go_env, log=log)
                metadata["brokerBinarySha256"] = LATENCY.sha256(binary)
                metadata["proxyBinarySha256"] = LATENCY.sha256(proxy)
                metadata["clusterBinarySha256"] = LATENCY.sha256(cluster)
                command(["mvn", "--batch-mode", "--no-transfer-progress", "--projects", "opentsx-wayang", "--also-make", "install", "-DskipTests"], cwd=ROOT, env=env, log=log)
                classpath_file = work / "test.cp"
                command(["mvn", "--batch-mode", "--no-transfer-progress", "--projects", "opentsx-wayang",
                         "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath", "-DincludeScope=test",
                         f"-Dmdep.outputFile={classpath_file}"], cwd=ROOT, env=env, log=log)
            dependencies = classpath_file.read_text().strip().split(os.pathsep)
            if any(not item for item in dependencies):
                raise ValueError("resolved classpath contains an empty entry")
            classpath, metadata["runtimeSnapshot"] = LATENCY.freeze_classpath(ROOT,
                    [ROOT / "opentsx-wayang/target/test-classes", ROOT / "opentsx-wayang/target/classes", *dependencies], work / "runtime")
            require_same_source(ROOT, snapshot, output)
            metadata["status"] = "measuring"
            with LATENCY.power_assertion(metadata) as assertion:
                with (output / "driver.log").open("w") as log:
                    command([java, "-Xms512m", "-Xmx2g", "-cp", classpath, "org.opentsx.wayang.FederationMeasurement", str(output)],
                            cwd=ROOT / "opentsx-wayang", env=env | {"KAFSCALE_BROKER_BINARY": str(binary),
                            "KAFSCALE_PROXY_BINARY": str(proxy), "KAFSCALE_CLUSTER_BINARY": str(cluster)}, log=log)
                LATENCY.require_power_assertion(assertion)
            require_same_source(ROOT, snapshot, output)
            run, _ = read_results(output)
            if run["scenarioSha256"] != metadata["sourceSha256"]["opentsx-windpark/scenarios/ostsee-demo.yaml"]:
                raise ValueError("measured scenario differs from the recorded source")
            metadata["artifactsSha256"] = {name: LATENCY.sha256(output / name) for name in ("bytes.csv", "windows.csv", "run.json")}
            metadata["status"] = "complete"
            report(output, metadata)
    except Exception as error:
        metadata.update(status="failed", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        metadata["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        save()


if __name__ == "__main__":
    main()
