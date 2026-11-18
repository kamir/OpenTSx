#!/usr/bin/env python3
"""Measure the bounded Track A federation and Java catch-up baselines."""

import argparse
import csv
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("wayang_federation_measurement", Path(__file__).with_name("wayang_federation_measurement.py"))
FEDERATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FEDERATION)
COMMON = FEDERATION.LATENCY
CHAINS = ("passthrough", "clean", "stats")
RUNTIMES = ("wayang", "java")
SITES = (1, 3, 10)
PARTITIONS = (1, 4, 12)
RESOURCES = ("elapsed_ms", "pid", "ppid", "role", "rss_bytes", "cpu_seconds")
SOURCES = ("tools/wayang_track_a_evaluation.py",
           "opentsx-wayang/src/test/java/org/opentsx/wayang/FederationEvaluation.java",
           "opentsx-wayang/src/test/java/org/opentsx/wayang/StreamingEvaluation.java")
TRANSPORT_SOURCES = ("tools/build_kafscale_proxy.py", "tools/kafscale_proxy_cluster.go",
                     "tools/kafscale_metadata_overlay.py", "tools/kafscale_proxy_protocol_overlay.py",
                     "tools/kafscale_proxy_protocol_test.go")
ERRORS = {"mean_relative_error": "maxRelativeMeanError", "m2_relative_error": "maxRelativeM2Error",
          "central_native_mean_relative_error": "maxCentralRelativeMeanError",
          "central_native_m2_relative_error": "maxCentralRelativeM2Error",
          "federated_central_mean_relative_error": "maxFederatedCentralRelativeMeanError",
          "federated_central_m2_relative_error": "maxFederatedCentralRelativeM2Error"}


def cases(repetitions, sites=SITES, partitions=PARTITIONS, chains=CHAINS, runtimes=RUNTIMES):
    if type(repetitions) is not int or repetitions < 1:
        raise ValueError("repetitions must be a positive integer")
    for values, allowed in ((sites, SITES), (partitions, PARTITIONS), (chains, CHAINS), (runtimes, RUNTIMES)):
        if not values or len(set(values)) != len(values) or any(value not in allowed for value in values):
            raise ValueError("the measurement matrix has unsupported or duplicate conditions")
    result = []
    for repetition in range(1, repetitions + 1):
        for site in sites:
            result.append((f"federation/k{site}-r{repetition}", "federation", site, repetition))
        for partition in partitions:
            for chain in chains:
                for runtime in runtimes:
                    result.append((f"streaming/{chain}-p{partition}-{runtime}-r{repetition}", "streaming",
                                   (chain, runtime, partition), repetition))
    return result


def number(value, name, *, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name} must be finite")
    if value < 0 or positive and value == 0:
        raise ValueError(f"{name} must be {'positive' if positive else 'non-negative'}")
    return value


def integer(value, name, *, positive=False):
    number(value, name, positive=positive)
    if type(value) is not int:
        raise ValueError(f"{name} must be an integer")
    return value


def cpu_seconds(text):
    if not re.fullmatch(r"(?:\d+-)?\d+:\d{2}(?::\d{2})?(?:\.\d+)?", text):
        raise ValueError("ps returned an unsupported CPU time")
    days, clock = text.split("-", 1) if "-" in text else ("0", text)
    parts = list(map(float, clock.split(":")))
    if parts[-1] >= 60 or len(parts) == 3 and parts[-2] >= 60:
        raise ValueError("ps returned an invalid CPU clock component")
    seconds = sum(part * 60 ** index for index, part in enumerate(reversed(parts)))
    return int(days) * 86400 + seconds


def process_rows(text, roots, elapsed_ms):
    rows = {}
    for line in text.splitlines():
        fields = line.split(None, 4)
        if len(fields) != 5:
            raise ValueError("ps returned an incomplete process row")
        pid, parent, rss = map(int, fields[:3])
        if pid < 1 or parent < 0 or rss < 0 or pid in rows:
            raise ValueError("ps returned invalid process identity or memory")
        rows[pid] = (parent, rss * 1024, cpu_seconds(fields[3]), Path(fields[4]).name)
    owned = set(roots)
    while True:
        expanded = owned | {pid for pid, row in rows.items() if row[0] in owned}
        if expanded == owned:
            break
        owned = expanded
    result = []
    for pid in sorted(owned & rows.keys()):
        parent, rss, cpu, command = rows[pid]
        role = roots.get(pid, "broker" if command == "kafscale-broker" else "subprocess")
        result.append((elapsed_ms, pid, parent, role, rss, cpu))
    return result


def stop_group(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def measured_command(command, directory, env, extra_roots, record_command):
    record_command(command)
    started = time.monotonic_ns()
    with (directory / "driver.log").open("w") as log, (directory / "resources.csv").open("w", newline="") as resource:
        writer = csv.writer(resource)
        writer.writerow(RESOURCES)
        process = subprocess.Popen(command, cwd=ROOT / "opentsx-wayang", env=env,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        failure = []
        stop = threading.Event()

        def sample():
            try:
                while True:
                    observed = COMMON.run(["ps", "-axo", "pid=,ppid=,rss=,time=,comm="], cwd=ROOT)
                    writer.writerows(process_rows(observed, {process.pid: "driver", **extra_roots},
                                                  (time.monotonic_ns() - started) / 1000000))
                    resource.flush()
                    if stop.wait(1):
                        return
            except Exception as error:
                failure.append(error)
                stop_group(process)

        thread = threading.Thread(target=sample)
        thread.start()
        try:
            code = process.wait()
        finally:
            stop.set()
            thread.join(timeout=15)
            stop_group(process)
        if thread.is_alive():
            raise RuntimeError("resource sampler did not stop")
        if failure:
            raise RuntimeError("resource sampler failed") from failure[0]
        if code != 0:
            raise RuntimeError(f"measurement process exited with code {code}; read {directory / 'driver.log'}")


def federation_result(directory, site_count):
    run = json.loads((directory / "run.json").read_text())
    if run.get("schema") != "opentsx.federation-evaluation.v1" or integer(run.get("siteCount"), "siteCount") != site_count:
        raise ValueError("federation identity differs from its requested condition")
    if run.get("correctnessPassed") is not True or run.get("routeSAdaptersEqualJ") is not True or run.get("centralizedPlanPassed") is not True:
        raise ValueError("federation correctness checks did not pass")
    topology = run.get("topology", {})
    if tuple(integer(topology.get(name), name) for name in ("siteAgentInstances", "brokers", "jvmProcesses")) != (site_count, site_count + 1, 1):
        raise ValueError("federation topology differs from its requested condition")
    for name, expected in (("episodes", 3600), ("values", 2160000), ("rawSeries", 100), ("windows", 420), ("partials", 420 * site_count)):
        if type(run.get(name)) is not int or run[name] != expected:
            raise ValueError(f"federation {name} differs from the complete W scenario")
    if run.get("dt2Tolerance") != 3e-12:
        raise ValueError("federation D-T2 tolerance differs from the recorded contract")
    for name in ERRORS.values():
        if number(run.get(name), name) > 3e-12:
            raise ValueError(f"{name} exceeds D-T2")
    for name in ("sourceSpecHash", "scenarioSha256"):
        if not isinstance(run.get(name), str) or not re.fullmatch("[0-9a-f]{64}", run[name]):
            raise ValueError(f"{name} must be a SHA256 digest")
    with (directory / "windows.csv").open(newline="") as source:
        windows = list(csv.DictReader(source))
        if len(windows) != 420:
            raise ValueError("federation window evidence is incomplete")
    identities = set()
    maxima = dict.fromkeys(ERRORS, 0)
    for row in windows:
        identity = tuple(row[name] for name in ("group_id", "window_type", "window_start_micros", "window_end_micros"))
        if identity in identities or identity[1] not in ("PT10M", "PT1H") or int(identity[3]) <= int(identity[2]):
            raise ValueError("federation window identity is invalid or duplicated")
        identities.add(identity)
        for field in ("sketch_equal", "exact_fields_equal", "central_native_sketch_equal", "central_native_exact_fields_equal"):
            if row.get(field) != "true":
                raise ValueError("federation exact fields or sketches differ")
        for field in ERRORS:
            value = number(float(row[field]), field)
            if value > 3e-12:
                raise ValueError("federation window error exceeds D-T2")
            maxima[field] = max(maxima[field], value)
    if any(maxima[field] != run[name] for field, name in ERRORS.items()):
        raise ValueError("federation error maxima differ from raw window evidence")
    with (directory / "bytes.csv").open(newline="") as source:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != FEDERATION.FIELDS:
            raise ValueError("federation byte schema changed")
        rows = list(reader)
    expected = {(route, f"s{site}") for route in FEDERATION.ROUTES for site in range(1, site_count + 1)}
    if len(rows) != len(expected) or {(r["route"], r["site"]) for r in rows} != expected:
        raise ValueError("federation site/route evidence is incomplete or duplicated")
    for row in rows:
        http = row["route"] == "job-state"
        for field in FEDERATION.FIELDS[3:-1]:
            applicable = field in FEDERATION.FIELDS[6:9] if http else field in FEDERATION.FIELDS[3:6]
            if not applicable:
                if row[field] != "":
                    raise ValueError("inapplicable federation byte counters must be blank")
            elif not row[field].isdigit():
                raise ValueError("federation counters must be non-negative integers")
        number(float(row["elapsed_ms"]), "elapsed_ms", positive=True)
        site = int(row["site"][1:])
        if http:
            if not row["topic"] or int(row["http_records"]) != 420 or min(int(row[f]) for f in ("http_body_bytes", "tls_client_bytes")) < 1:
                raise ValueError("job-state evidence is incomplete")
        else:
            turbines = sum((turbine * site_count + 9) // 10 == site for turbine in range(1, 11))
            expected_records = 360 * turbines if row["route"] == "centralized" else 420
            if not row["topic"] or int(row["produce_frame_bytes"]) < 1 or int(row["produce_records"]) != expected_records or row["produce_errors"] != "0":
                raise ValueError("Kafka route evidence is incomplete or contains failed acknowledgements")
    if any(len({r["elapsed_ms"] for r in rows if r["route"] == route}) != 1 for route in FEDERATION.ROUTES):
        raise ValueError("federation route rows disagree about their elapsed clock")
    for field in ("centralizedTransferElapsedNanos", "centralizedPlanElapsedNanos", "centralizedPlanCpuNanos"):
        integer(run.get(field), field, positive=True)
    return run, rows


def streaming_result(directory, settings):
    chain, runtime, partitions = settings
    run = json.loads((directory / "case.json").read_text())
    integer(run.get("partitions"), "partitions", positive=True)
    if run.get("schema") != "opentsx.streaming-evaluation.v1" or (run.get("chain"), run.get("runtime"), run.get("partitions")) != settings:
        raise ValueError("streaming identity differs from its requested condition")
    if run.get("correctnessPassed") is not True or run.get("checkpointVerified") is not True:
        raise ValueError("streaming correctness or checkpoint verification did not pass")
    if run.get("inputRecords") != 3600 or run.get("inputValues") != 2160000:
        raise ValueError("streaming input differs from the complete W scenario")
    expected = "java-decode" if chain == "passthrough" else "VectorProcessor" if chain == "clean" else "StatsProcessor"
    if runtime == "java" and run.get("nativeImplementation") != expected:
        raise ValueError("streaming baseline implementation differs from its documented binding")
    for field in ("elapsedNanos", "cpuNanos", "availableProcessors", "outputRecords"):
        integer(run.get(field), field, positive=True)
    for field in ("firstFetchNanos", "firstOutputNanos"):
        if integer(run.get(field), field) > run["elapsedNanos"]:
            raise ValueError(f"{field} lies outside the measured activation")
    started, ended = run.get("startedNanos"), run.get("endedNanos")
    if type(started) is not int or type(ended) is not int or ended - started != run["elapsedNanos"]:
        raise ValueError("streaming activation duration differs from its monotonic clocks")
    with (directory / "records.csv").open(newline="") as source:
        records = list(csv.DictReader(source))
    if len(records) != run["outputRecords"]:
        raise ValueError("streaming latency records do not cover the outputs")
    if len({row["output_id"] for row in records}) != len(records):
        raise ValueError("streaming output identities are duplicated")
    for row in records:
        if not row.get("input_ids"):
            raise ValueError("streaming output lacks input provenance")
        request = int(row["output_request_ns"]) - int(row["input_request_ns"])
        if request < 0 or not math.isclose(number(float(row["latency_ms"]), "latency_ms"), request / 1000000, rel_tol=1e-12, abs_tol=1e-12):
            raise ValueError("streaming latency differs from its raw monotonic clocks")
        for prefix in ("input", "output"):
            if int(row[f"{prefix}_ack_ns"]) < int(row[f"{prefix}_request_ns"]):
                raise ValueError("streaming acknowledgement predates its request")
        if int(row["input_ack_ns"]) > started or int(row["output_request_ns"]) < started or int(row["output_ack_ns"]) > ended:
            raise ValueError("streaming wire clocks lie outside the acknowledged backlog activation")
    if run["firstOutputNanos"] != min(int(row["output_request_ns"]) for row in records) - started \
            or run.get("lastOutputAckNanos") != max(int(row["output_ack_ns"]) for row in records) - started:
        raise ValueError("streaming first output or last acknowledgement differs from raw clocks")
    input_topic, output_topic = run.get("inputTopic"), run.get("outputTopic")
    if not input_topic or not output_topic or input_topic == output_topic:
        raise ValueError("streaming input/output topic evidence is missing")
    with (directory / "produce-attempts.csv").open(newline="") as source:
        attempts = list(csv.DictReader(source))
    observed = {input_topic: {}, output_topic: {}}
    positions = set()
    for row in attempts:
        if row["topic"] not in observed or row["error_code"] != "0" or int(row["ack_ns"]) < int(row["request_ns"]):
            raise ValueError("streaming Produce observation is unknown or failed")
        selected = observed[row["topic"]]
        position = (row["topic"], int(row["partition"]), int(row["offset"]))
        if row["identity"] in selected or position in positions or not 0 <= position[1] < partitions or position[2] < 0:
            raise ValueError("streaming Produce identity or position is invalid or duplicated")
        positions.add(position)
        selected[row["identity"]] = row
    if len(observed[input_topic]) != 3600 or set(observed[output_topic]) != {r["output_id"] for r in records}:
        raise ValueError("streaming wire observations do not cover every input and output")
    for row in records:
        inputs = [observed[input_topic].get(identity) for identity in row["input_ids"].split(";")]
        if not inputs or any(value is None for value in inputs):
            raise ValueError("streaming output references an unobserved input")
        latest = max(inputs, key=lambda value: int(value["request_ns"]))
        output = observed[output_topic][row["output_id"]]
        if any(row[f"input_{field}"] != latest[field] or row[f"output_{field}"] != output[field] for field in ("request_ns", "ack_ns")):
            raise ValueError("streaming raw clocks differ from their wire observations")
        if row["output_partition"] != output["partition"] or row["output_offset"] != output["offset"] or row["output_type"] != output["type"]:
            raise ValueError("streaming output position or type differs from its wire observation")
    with (directory / "measurement.csv").open(newline="") as source:
        measurements = list(csv.DictReader(source))
    if len(measurements) != 1:
        raise ValueError("streaming measurement summary must contain one row")
    seconds = run["elapsedNanos"] / 1000000000
    expected = {"wall_ms": seconds * 1000, "episodes_per_second": 3600 / seconds, "values_per_second": 2160000 / seconds,
                "episodes_per_second_per_core": 3600 / seconds / run["availableProcessors"],
                "values_per_second_per_core": 2160000 / seconds / run["availableProcessors"]}
    expected.update({f"p{int(q * 100)}_ms": COMMON.percentile([float(r["latency_ms"]) for r in records], q) for q in (0.5, 0.95, 0.99)})
    for field, value in expected.items():
        if not math.isclose(number(float(measurements[0][field]), field), value, rel_tol=1e-12, abs_tol=1e-12):
            raise ValueError("streaming summary differs from raw observations")
    return run, records


def resource_result(directory):
    with (directory / "resources.csv").open(newline="") as source:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != RESOURCES:
            raise ValueError("resource sample columns changed")
        rows = list(reader)
    if not rows or not any(row["role"] == "driver" for row in rows):
        raise ValueError("resource samples do not cover the driver")
    driver_rss, driver_cpu = 0, 0
    broker_rss = {}
    for row in rows:
        elapsed = number(float(row["elapsed_ms"]), "elapsed_ms")
        rss = integer(int(row["rss_bytes"]), "rss_bytes")
        cpu = number(float(row["cpu_seconds"]), "cpu_seconds")
        if row["role"] == "driver":
            driver_rss, driver_cpu = max(driver_rss, rss), max(driver_cpu, cpu)
        elif row["role"] == "broker":
            broker_rss[elapsed] = broker_rss.get(elapsed, 0) + rss
        elif row["role"] != "subprocess":
            raise ValueError("resource samples contain an unknown process role")
    return driver_rss, max(broker_rss.values(), default=0), driver_cpu


def report(output, metadata):
    lines = ["# Evaluate the bounded Track A baselines", ""]
    paragraph = lambda value: COMMON.paragraph(lines, value)
    paragraph(f"Measured with OpenTSx {metadata['openTsxCommit']} at {metadata['startedAt']}. Profile: {metadata['profile']}.")
    paragraph(f"Hardware: {metadata['hardware']}. OS: {metadata['platform']}. JDK: {metadata['javaVersion'].strip().splitlines()[0]}.")
    paragraph(f"KafScale v1.6.0 source: {COMMON.KAFSCALE_COMMIT}. Go: {metadata['goVersion'].strip()}. JVM heap: -Xms512m -Xmx2g.")
    paragraph("All inputs are the complete synthetic ostsee-demo.yaml W scenario, seed 42. Conditions run sequentially with fresh execution JVMs and in-memory brokers. No warm-up samples are discarded. No performance threshold is applied.")
    paragraph("Federation uses one execution JVM with 1, 3 or 10 signed site instances and one broker per site plus the central broker. Complete turbines remain together. Build and transient conformance subprocesses are additional setup processes. The centralized plan executes actual received raw episodes; the raw transfer and statistics computation clocks remain separate.")
    paragraph("Kafka counters contain Produce request frames including the length prefix, excluding other requests, responses and TCP/IP overhead. HTTP counters contain aggregate bodies. TLS counters contain the encrypted client stream during execution, including polls, status and ACKs. They are distinct protocol layers and are not total network egress.")
    paragraph("Streaming is a bounded backlog/catch-up profile at 1, 4 and 12 partitions. Every input is acknowledged before activation. Track A runs one real TsxIncrement; native clean runs VectorProcessor, native statistics runs StatsProcessor, and native decode uses the existing SOE decoder in a plain Kafka loop. Both decode paths include canonical re-encoding and acknowledged writes, not only decoding CPU. VectorProcessor rejects an empty operator chain, so decode is not labeled a VectorProcessor binding.")
    paragraph("Activation wall time covers runtime construction, planning or deployment, input processing, acknowledged output and checkpoint persistence. Independent checkpoint inspection, output scans and reference validation are excluded. Rates count input episodes and values. Per-core rates divide by the recorded available logical processors and do not infer single-core speed. Job process CPU time includes the in-JVM observer and harness; it excludes Go broker CPU.")
    paragraph("Latency subtracts successful input Produce ingress from successful output Produce ingress at the loopback observer. Aggregate outputs use their latest contributing input; records.csv retains all contributing ids and both acknowledgement clocks. Kafka event/envelope timestamps are not latency clocks. Clean equivalence checks ids, keys, event times and values, without requiring createdAt or Kafka envelope timestamp equality.")
    paragraph("resources.csv samples RSS and cumulative CPU for the driver, owned brokers and observed subprocesses once per second across setup, execution and independent validation. Short-lived processes between samples can be missed. Sampled driver CPU is the largest observed cumulative process CPU time, not the exact CPU-at-exit total. JVM process start, main entry, activation and I/O timings are distinct. The host is shared; no CPU reservation or remote-network result is claimed.")
    if metadata.get("transport") == "kafscale-proxy":
        paragraph("Every Kafka client uses a KafScale Proxy. Each isolated cluster has one private broker and one launcher with embedded etcd. The pinned v1.6.0 source uses the guarded metadata and Proxy protocol overlays. Proxy and launcher resources are subprocess rows, outside the broker RSS column. metadata.json records transport source and binary hashes; these samples do not establish performance for an unmodified upstream broker or a multi-broker deployment.")
    paragraph(f"Frozen test-runtime classpath size: {metadata['runtimeBytes']} bytes, including dependencies and harness classes. Broker binary size: {metadata['brokerBinaryBytes']} bytes. These are measured file sizes, not a minimal deployment package. Their hashes are in metadata.json.")
    federation_rows, route_rows, error_rows, central_rows = [], [], [], []
    streaming_rows, latency_rows, activation_rows, footprint_rows = [], [], [], []
    paragraph("Case labels use D for decode, C for clean and S for statistics, followed by the partition count, W for Wayang or J for native Java, and the repetition. C4J-r2 means streaming/clean-p4-java-r2. F10-r3 means federation/k10-r3. Raw directory names retain the full chain and runtime names.")
    for name, kind, settings, repetition in metadata["matrix"]:
        directory = output / name
        if kind == "federation":
            label = f"F{settings}-r{repetition}"
            run, byte_rows = federation_result(directory, settings)
            error_rows.append([label, f"{max(run[name] for name in ERRORS.values() if 'Mean' in name):.17g}",
                               f"{max(run[name] for name in ERRORS.values() if 'M2' in name):.17g}"])
            central_rows.append([str(settings), str(repetition),
                                 *(f"{run[field] / 1000000:.3f}" for field in ("centralizedTransferElapsedNanos", "centralizedPlanElapsedNanos", "centralizedPlanCpuNanos", "centralizedFirstFetchNanos", "centralizedFirstStatisticsNanos"))])
            for route in FEDERATION.ROUTES:
                selected = [r for r in byte_rows if r["route"] == route]
                field = "http_body_bytes" if route == "job-state" else "produce_frame_bytes"
                federation_rows.append([label, route, str(sum(int(r[field]) for r in selected))])
                route_rows.append([label, route, f"{float(selected[0]['elapsed_ms']):.3f}"])
        else:
            label = f"{dict(passthrough='D', clean='C', stats='S')[settings[0]]}{settings[2]}{'W' if settings[1] == 'wayang' else 'J'}-r{repetition}"
            run, records = streaming_result(directory, tuple(settings))
            latency = [float(r["latency_ms"]) for r in records]
            seconds = run["elapsedNanos"] / 1000000000
            streaming_rows.append([label,
                                   f"{run['inputRecords'] / seconds:.3f}", f"{run['inputValues'] / seconds:.3f}",
                                   f"{run['inputRecords'] / seconds / run['availableProcessors']:.3f}", f"{run['inputValues'] / seconds / run['availableProcessors']:.3f}"])
            latency_rows.append([label, *(f"{COMMON.percentile(latency, q):.3f}" for q in (0.5, 0.95, 0.99))])
            activation_rows.append([label,
                                    *(f"{run[field] / 1000000:.3f}" for field in ("elapsedNanos", "cpuNanos", "firstFetchNanos", "firstOutputNanos"))])
        rss, brokers, cpu = resource_result(directory)
        footprint_rows.append([label, f"{rss / 1048576:.3f}", f"{brokers / 1048576:.3f}", f"{cpu:.3f}"])
    lines.extend(["## Read federation samples", "", *COMMON.table(["Case", "Route", "Layer bytes"], federation_rows), ""])
    lines.extend(COMMON.table(["Case", "Route", "Elapsed ms"], route_rows) + [""])
    paragraph("Layer bytes are Kafka Produce frames for centralized and signal-cluster rows, HTTP bodies for job-state rows. Route milliseconds cover different work and are not a speed comparison. Separate central plan clocks are retained in each run.json; every byte/window sample remains in the case directory.")
    lines.extend(["## Read numerical errors", "", *COMMON.table(["Case", "Max mean error", "Max M2 error"], error_rows), ""])
    paragraph("Errors are the largest relative errors among reference-to-federated, reference-to-central and federated-to-central comparisons. All three comparisons retain separate raw fields and the same 3e-12 bound. Exact count, extrema and sketch checks precede reporting.")
    lines.extend(["## Read central plan clocks", "", *COMMON.table(["Sites", "Rep", "Transfer", "Plan", "CPU", "Fetch", "Statistics"], central_rows), ""])
    paragraph("Fetch and first-statistics durations start at central plan invocation. Statistics is the first native callback before grouping, not an output Produce clock. Plan time includes compilation, native execution and grouping, excluding transfer and independent correctness checks.")
    lines.extend(["## Read streaming rates", "", *COMMON.table(["Case", "Episodes/s", "Values/s", "Ep/s/core", "Val/s/core"], streaming_rows), ""])
    lines.extend(["## Read streaming latency", "", *COMMON.table(["Case", "p50 ms", "p95 ms", "p99 ms"], latency_rows), ""])
    lines.extend(["## Read streaming activation clocks", "", *COMMON.table(["Case", "Wall ms", "CPU ms", "Fetch ms", "Output ms"], activation_rows), ""])
    paragraph("The central plan and streaming activation clock tables use milliseconds for every duration column.")
    lines.extend(["## Read process samples", "", *COMMON.table(["Case", "Driver MiB", "Brokers MiB", "Driver CPU s"], footprint_rows), ""])
    paragraph("Driver and broker columns are the largest sampled RSS values. Broker RSS sums simultaneously observed owned brokers. Driver CPU is cumulative since process start, including input setup and reference validation, and remains distinct from activation CPU above.")
    lines.extend(["## Repeat the measurement", ""])
    paragraph("You need a clean source checkout, JDK 17 or 21, Maven 3.9+, Python 3.10+, Git and Go with go1.25.2 available. metadata.json records the exact invocation, commands, heap, classpath and hashes. Use a new output directory.")
    lines.extend(["```bash", "python3 tools/wayang_track_a_evaluation.py --help", "```", ""])
    paragraph("The default matrix runs three repetitions, 1/3/10 federation sites and both runtimes for every chain and partition count. --pilot labels any reduced matrix as excluded from A5. The paced 5/30/60-second increment measurements are separate A1.7 evidence; this report does not claim paced statistics/partition coverage, the 30-kill recovery protocol, Track B parity or the section 14.2 small-device matrix.")
    paragraph(f"Completed conditions: {len(metadata['matrix'])}. Raw files and their hashes are listed in metadata.json. Private logs, certificates and runtime directories are excluded from the public artifact list.")
    (output / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--sites", type=int, nargs="+", default=list(SITES))
    parser.add_argument("--partitions", type=int, nargs="+", default=list(PARTITIONS))
    parser.add_argument("--chains", nargs="+", default=list(CHAINS))
    parser.add_argument("--runtimes", nargs="+", default=list(RUNTIMES))
    parser.add_argument("--pilot", action="store_true")
    parser.add_argument("--kafscale-source", type=Path)
    args = parser.parse_args()
    try:
        matrix = cases(args.repetitions, args.sites, args.partitions, args.chains, args.runtimes)
    except ValueError as error:
        parser.error(str(error))
    if not args.pilot and (args.repetitions < 3 or tuple(args.sites) != SITES or tuple(args.partitions) != PARTITIONS
                           or tuple(args.chains) != CHAINS or tuple(args.runtimes) != RUNTIMES):
        parser.error("an A5 run needs three repetitions and the complete site/partition/chain/runtime matrix")
    output = args.output.resolve()
    snapshot = FEDERATION.source_snapshot(ROOT, output)
    if not set((*SOURCES, *TRANSPORT_SOURCES)).issubset(snapshot["files"]):
        raise RuntimeError("evaluation harness sources must be tracked")
    output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.java_home.resolve())
    env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    java = str(args.java_home.resolve() / "bin/java")
    java_version = COMMON.run([java, "-version"], cwd=ROOT, env=env)
    if not re.search(r'version "(?:17|21)\.', java_version):
        raise RuntimeError("the evaluation requires JDK 17 or 21")
    hardware = COMMON.run(["sysctl", "-n", "hw.model", "machdep.cpu.brand_string", "hw.memsize"]).strip() \
        if platform.system() == "Darwin" else Path("/proc/cpuinfo").read_text()
    metadata = {"schema": "opentsx.track-a-evaluation.v1", "status": "building", "profile": "pilot, excluded from A5" if args.pilot else "A5 bounded baseline",
                "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "openTsxCommit": snapshot["commit"],
                "sourceSnapshot": snapshot, "platform": platform.platform(), "hardware": " / ".join(hardware.splitlines()),
                "javaVersion": java_version, "cpuCount": os.cpu_count(), "kafScaleCommit": COMMON.KAFSCALE_COMMIT,
                "transport": "kafscale-proxy", "transportSourcesSha256": {name: snapshot["files"][name] for name in TRANSPORT_SOURCES},
                "invocation": {"cwd": str(Path.cwd()), "argv": sys.argv, "python": sys.executable},
                "matrix": matrix, "commands": [], "completed": []}

    def save():
        (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")

    def record(command):
        metadata["commands"].append(command)
        save()

    def command(argv, **kwargs):
        record(argv)
        return COMMON.run(argv, **kwargs)

    try:
        with tempfile.TemporaryDirectory(prefix="opentsx-a5-") as temporary:
            work = Path(temporary)
            source = work / "kafscale"
            with (output / "build.log").open("w") as log:
                clone = ["git", "clone", "--no-hardlinks", str(args.kafscale_source.resolve()), str(source)] if args.kafscale_source else \
                    ["git", "clone", "--branch", "v1.6.0", "--single-branch", COMMON.KAFSCALE_REPOSITORY, str(source)]
                command(clone, cwd=ROOT, log=log)
                command(["git", "checkout", "--detach", COMMON.KAFSCALE_COMMIT], cwd=source, log=log)
                if COMMON.run(["git", "rev-parse", "HEAD"], cwd=source).strip() != COMMON.KAFSCALE_COMMIT:
                    raise RuntimeError("KafScale source differs from its pinned commit")
                go_env = env | {"GOTOOLCHAIN": "go1.25.2"}
                metadata["goVersion"] = command(["go", "version"], cwd=source, env=go_env).strip()
                overlay = COMMON.build_kafscale_proxy.transport_overlay(source, work)
                command(["go", "test", f"-overlay={overlay}", "-run", "^TestOpenTSxProxy", "-count=1", "./cmd/proxy"], cwd=source, env=go_env, log=log)
                binary = work / "kafscale-broker"
                command(["go", "build", "-o", str(binary), f"-overlay={overlay}", "./cmd/broker"], cwd=source, env=go_env, log=log)
                proxy = work / "kafscale-proxy"
                command(["go", "build", "-o", str(proxy), f"-overlay={overlay}", "./cmd/proxy"], cwd=source, env=go_env, log=log)
                cluster = work / "kafscale-cluster"
                command(["go", "build", "-o", str(cluster), f"-overlay={overlay}", str(ROOT / "tools/kafscale_proxy_cluster.go")], cwd=source, env=go_env, log=log)
                metadata["brokerBinarySha256"] = COMMON.sha256(binary)
                metadata["brokerBinaryBytes"] = binary.stat().st_size
                metadata["proxyBinarySha256"] = COMMON.sha256(proxy)
                metadata["proxyBinaryBytes"] = proxy.stat().st_size
                metadata["clusterBinarySha256"] = COMMON.sha256(cluster)
                metadata["clusterBinaryBytes"] = cluster.stat().st_size
                command(["mvn", "--batch-mode", "--no-transfer-progress", "--projects", "opentsx-wayang", "--also-make", "install", "-DskipTests"], cwd=ROOT, env=env, log=log)
                cp = work / "test.cp"
                command(["mvn", "--batch-mode", "--no-transfer-progress", "--projects", "opentsx-wayang",
                         "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath", "-DincludeScope=test", f"-Dmdep.outputFile={cp}"], cwd=ROOT, env=env, log=log)
            dependencies = cp.read_text().strip().split(os.pathsep)
            if any(not item for item in dependencies):
                raise RuntimeError("resolved classpath contains an empty entry")
            classpath, metadata["runtimeSnapshot"] = COMMON.freeze_classpath(ROOT,
                    [ROOT / "opentsx-wayang/target/test-classes", ROOT / "opentsx-wayang/target/classes", *dependencies], work / "runtime")
            metadata["runtimeBytes"] = sum(p.stat().st_size for p in (work / "runtime").rglob("*") if p.is_file())
            metadata["status"] = "measuring"
            with COMMON.power_assertion(metadata) as assertion:
                for name, kind, settings, repetition in matrix:
                    FEDERATION.require_same_source(ROOT, snapshot, output)
                    directory = output / name
                    directory.mkdir(parents=True)
                    launch = [java, "-Xms512m", "-Xmx2g", "-cp", classpath]
                    if kind == "federation":
                        measured_command([*launch, "org.opentsx.wayang.FederationEvaluation", str(directory), str(settings)],
                                         directory, env | {"KAFSCALE_BROKER_BINARY": str(binary),
                                                           "KAFSCALE_PROXY_BINARY": str(proxy), "KAFSCALE_CLUSTER_BINARY": str(cluster)}, {}, record)
                        federation_result(directory, settings)
                    else:
                        port = COMMON.free_port()
                        transport_env = {k: v for k, v in env.items() if not k.startswith("KAFSCALE_")}
                        transport_command = [str(cluster), "--listen", f"127.0.0.1:{port}",
                                             "--advertised-host", "127.0.0.1", "--advertised-port", str(port),
                                             "--data-dir", str(directory / "metadata"), "--broker-binary", str(binary),
                                             "--proxy-binary", str(proxy)]
                        record(transport_command)
                        with (directory / "broker.log").open("w") as log:
                            transport = subprocess.Popen(transport_command, env=transport_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                            try:
                                deadline = time.monotonic() + 30
                                while True:
                                    if transport.poll() is not None:
                                        raise RuntimeError(f"Proxy cluster exited with code {transport.returncode}")
                                    try:
                                        with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                                            break
                                    except (ConnectionRefusedError, TimeoutError):
                                        if time.monotonic() >= deadline:
                                            raise TimeoutError("Proxy startup exceeded 30 seconds")
                                        time.sleep(0.1)
                                chain, runtime, partition = settings
                                measured_command([*launch, "org.opentsx.wayang.StreamingEvaluation", str(port), str(directory), chain, runtime, str(partition)],
                                                 directory, env, {transport.pid: "subprocess"}, record)
                                streaming_result(directory, settings)
                            finally:
                                stop_group(transport)
                    COMMON.require_power_assertion(assertion)
                    metadata["completed"].append(name)
                    save()
                    print(f"completed {name}", flush=True)
            FEDERATION.require_same_source(ROOT, snapshot, output)
            if metadata["completed"] != [row[0] for row in matrix]:
                raise RuntimeError("the measurement matrix did not complete")
            public = {name: COMMON.sha256(output / name) for directory, kind, _, _ in matrix
                      for filename in (("bytes.csv", "windows.csv", "run.json", "resources.csv") if kind == "federation" else
                                       ("case.json", "measurement.csv", "records.csv", "produce-attempts.csv", "resources.csv"))
                      for name in (f"{directory}/{filename}",)}
            metadata.update(status="complete", artifactsSha256=public)
            report(output, metadata)
    except Exception as error:
        metadata.update(status="failed", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        metadata["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        save()


if __name__ == "__main__":
    main()
