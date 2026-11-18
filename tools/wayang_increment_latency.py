#!/usr/bin/env python3
"""Measure isolated single-partition Wayang increments through a disposable KafScale Proxy."""

import argparse
import contextlib
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import textwrap
import time

import build_kafscale_proxy


ROOT = Path(__file__).resolve().parents[1]
KAFSCALE_COMMIT = "2809a3a20bf40ed8a2b82cd93215cb59396efb0a"
KAFSCALE_REPOSITORY = "https://github.com/KafScale/platform.git"


def run(command, *, cwd=ROOT, env=None, log=None):
    return subprocess.run(command, cwd=cwd, env=env, check=True, text=True,
                          stdout=log if log else subprocess.PIPE,
                          stderr=subprocess.STDOUT).stdout


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def percentile(values, quantile):
    if not values:
        return None
    return sorted(values)[max(0, math.ceil(len(values) * quantile) - 1)]


def require_clean_checkout(root):
    for options in ([], ["--cached"]):
        result = subprocess.run(["git", "diff", "--quiet", *options], cwd=root, check=False,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode == 1:
            raise RuntimeError("reported measurements require a clean tracked checkout")
        result.check_returncode()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def directory_hashes(directory):
    return {str(path.relative_to(directory)): sha256(path) for path in sorted(directory.rglob("*")) if path.is_file()}


def freeze_classpath(root, entries, destination):
    destination.mkdir(parents=True)
    frozen, manifest = [], []
    for index, entry in enumerate(entries):
        source = Path(entry).absolute()
        target = destination / f"{index:04d}-{source.name}"
        reactor = None
        if source.is_dir():
            expected = directory_hashes(source)
            shutil.copytree(source, target)
            actual = directory_hashes(target)
        elif source.is_file():
            expected = sha256(source)
            if tuple(source.parts[-5:-3]) == ("org", "opentsx"):
                candidate = root / source.parent.parent.name / "target" / source.name
                if candidate.is_file():
                    reactor = str(candidate)
                    if expected != sha256(candidate):
                        raise RuntimeError(f"{source} differs from the reactor build {candidate}")
            shutil.copy2(source, target)
            actual = sha256(target)
        else:
            raise FileNotFoundError(f"classpath entry does not exist: {source}")
        if actual != expected:
            raise RuntimeError(f"classpath entry changed while copying: {source}")
        frozen.append(str(target))
        manifest.append({"source": str(source), "snapshot": str(target), "sha256": actual, "reactorTarget": reactor})
    return os.pathsep.join(frozen), manifest


def require_power_assertion(process):
    if process is not None and process.poll() is not None:
        raise RuntimeError(f"idle-sleep assertion exited with code {process.returncode}")


@contextlib.contextmanager
def power_assertion(metadata):
    system = platform.system()
    if system != "Darwin":
        metadata["powerAssertion"] = {"mode": "none", "platform": system, "command": [], "released": True}
        yield None
        return
    command = ["/usr/bin/caffeinate", "-i", "-w", str(os.getpid())]
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    metadata["powerAssertion"] = {"mode": "prevent idle sleep", "platform": system,
                                  "command": command, "pid": process.pid, "released": False}
    try:
        require_power_assertion(process)
        yield process
    finally:
        if process.poll() is None:
            process.terminate()
        try:
            code = process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            code = process.wait(timeout=10)
        if process.stderr is not None:
            process.stderr.close()
        metadata["powerAssertion"].update({"released": True, "exitCode": code})


def paragraph(lines, text):
    lines.extend(textwrap.wrap(text, width=80, break_long_words=False, break_on_hyphens=False))
    lines.append("")


def table(headers, rows):
    widths = [max(3, len(header), *(len(row[i]) for row in rows)) for i, header in enumerate(headers)]
    def line(values, numeric=False):
        return "| " + " | ".join(value.rjust(widths[i]) if numeric and i else value.ljust(widths[i])
                                  for i, value in enumerate(values)) + " |"
    return [line(headers), "| " + " | ".join("-" * width if i == 0 else "-" * (width - 1) + ":"
                                              for i, width in enumerate(widths)) + " |",
            *(line(row, numeric=True) for row in rows)]


def report(output, metadata):
    rows = []
    for case in sorted(output.glob("*/case.json")):
        settings = json.loads(case.read_text())
        with (case.parent / "records.csv").open(newline="") as source:
            values = [float(row["latency_ms"]) for row in csv.DictReader(source)]
        with (case.parent / "increments.csv").open(newline="") as source:
            increments = list(csv.DictReader(source))
        prefix = "P" if settings["chain"] == "passthrough" else "C"
        repetition = case.parent.name.rsplit("-r", 1)[1]
        label = f"{prefix}{settings['intervalSeconds']}-r{repetition}"
        rows.append((label, settings, increments, values))
    lines = ["# Wayang increment latency", ""]
    paragraphs = [
        f"Measured on {metadata['startedAt']} with {metadata['javaVersion'].splitlines()[0]}.",
        f"Mode: {metadata['mode']}. Single-partition conditions run sequentially on a disposable local broker.",
        f"OpenTSx commit: `{metadata['openTsxCommit']}`. KafScale v1.6.0: `{KAFSCALE_COMMIT}`.",
        f"Hardware and OS: {metadata['platform']}, {metadata['machine']}, {metadata['cpuCount']} logical CPUs.",
        f"Hardware detail: {metadata['hardware']}. Explicit JVM heap: `-Xms512m -Xmx2g`.",
        "Produce time means arrival of a complete Produce request at the loopback protocol observer, before forwarding. "
        "Only requests whose partition response succeeded contribute to latency. Separate request and acknowledgement clocks are retained.",
        "Kafka record timestamps are event times and are preserved. They are not used for transport latency. "
        "The observer rewrites only broker endpoints in Metadata responses; Produce requests and payloads are forwarded unchanged. "
        "Measurements include the observer's parsing and forwarding overhead.",
        "First fetch means the first Fetch request for the input topic during TsxIncrement.run, measured from its invocation. "
        "Wall time includes source metadata, planning, reading, processing, acknowledged writes and checkpoint persistence.",
        "Inputs are the complete ostsee-demo.yaml simulation, fixed seed 42, paced at uniform phases from 5% to 90% of each interval. "
        "Each condition writes every input episode exactly once. The first increment starts after one full interval.",
        "Clean outputs join raw metric and tags to every input episode whose event-time range overlaps the output range. "
        "Latency starts at the latest contributing input Produce request; records.csv retains every contributing episode id.",
        "Percentiles use nearest rank. Empty output sets have blank percentiles, with output count zero. "
        "Tables round milliseconds to three decimal places; CSV files retain every recorded digit.",
        "Case P means passthrough. Case C means dq.checks followed by resample PT1M. "
        "The number is the interval in seconds; r1, r2 and r3 identify repetitions."
    ]
    for text in paragraphs:
        paragraph(lines, text)
    counts, quantiles, timings = [], [], []
    for name, settings, increments, values in rows:
        counts.append([name, str(len(increments)), str(settings["inputRecords"]), str(len(values))])
        quantiles.append([name, *("" if not values else f"{percentile(values, q):.3f}" for q in (0.5, 0.95, 0.99))])
        late = max(float(row["lateness_ms"]) for row in increments)
        fetch = percentile([float(row["first_fetch_ms"]) for row in increments], 0.5)
        wall = percentile([float(row["wall_ms"]) for row in increments], 0.5)
        timings.append([name, f"{fetch:.3f}", f"{wall:.3f}", f"{late:.3f}"])
    for heading, headers, data in (
            ("Read counts", ["Case", "Inc", "In", "Out"], counts),
            ("Read transport latency", ["Case", "p50 ms", "p95 ms", "p99 ms"], quantiles),
            ("Read increment timings", ["Case", "Fetch p50", "Wall p50", "Late max"], timings)):
        lines.extend([f"## {heading}", "", *table(headers, data), ""])
    paragraph(lines, "Increment timings are milliseconds. Fetch and wall columns show medians across increments; "
              "Late max is the maximum observed delay after a scheduled increment start.")
    lines.extend(["## Repeat the measurement", ""])
    paragraph(lines, "You need JDK 17 or 21, Maven 3.9 or later, Go, git, Python 3.10 or later, and network access for dependencies. "
              "The script selects Go 1.25.2 and builds the pinned KafScale v1.6.0 source. It uses only the existing project dependencies.")
    paragraph(lines, "Run from the repository root. Read the arguments with this command:")
    lines.extend(["```bash", "python tools/wayang_increment_latency.py --help", "```", ""])
    paragraph(lines, "Select the JDK with --java-home and a new result directory with --output. "
              "The default matrix runs both chains at 5, 30 and 60 seconds, 30 increments per condition, and three repetitions. "
              "Normal runs require a clean tracked and staged checkout. --pilot labels a shorter run as excluded from the baseline.")
    if "invocation" in metadata:
        paragraph(lines, "metadata.json records the top-level invocation arguments, working directory, interpreter, "
                  "source hashes, build commands and exact JVM command. Repeat that invocation with a new --output directory.")
    else:
        paragraph(lines, "metadata.json records the build commands, source hashes and exact JVM command for this run.")
    if "runtimeSnapshot" in metadata:
        paragraph(lines, "Every JVM condition uses a private copy of its class directories and dependency JARs. "
                  "metadata.json records the SHA256 manifest. Available reactor JARs must match their resolved "
                  "OpenTSx dependencies before copying, and every copied entry is verified again after copying.")
    if "powerAssertion" in metadata:
        if metadata["powerAssertion"]["mode"] == "none":
            paragraph(lines, f"No idle-sleep assertion is applied on {metadata['powerAssertion']['platform']}.")
        else:
            paragraph(lines, "On macOS, the built-in caffeinate tool prevents idle sleep during the measurement. "
                      "Its assertion follows the Python process and is released in cleanup. "
                      "metadata.json records the actual command and exit status.")
    paragraph(lines, "Read increments.csv, records.csv and produce-attempts.csv inside each case directory for raw evidence. "
              "Case directory names retain the full chain, interval and repetition, such as passthrough-5s-r1.")
    paragraph(lines, "Reference validation checks episode ids, series keys, timestamps and values against SeriesStream; "
              "passthrough also checks payload bytes.")
    paragraph(lines, f"Completed conditions: {len(rows)} of {metadata['conditions']}.")
    (output / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--increments", type=int, default=30)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--intervals", type=int, nargs="+", default=[5, 30, 60])
    parser.add_argument("--pilot", action="store_true")
    parser.add_argument("--kafscale-source", type=Path)
    args = parser.parse_args()
    if args.increments < 1 or args.repetitions < 1 or any(i < 1 for i in args.intervals):
        parser.error("increments, repetitions and intervals must be positive")
    if not args.pilot and (args.increments < 30 or args.repetitions < 3 or args.intervals != [5, 30, 60]):
        parser.error("a reported run needs at least 30 increments, three repetitions and intervals 5 30 60")
    if not args.pilot:
        require_clean_checkout(ROOT)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.java_home.resolve())
    env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    java = str(args.java_home.resolve() / "bin" / "java")
    java_version = run([java, "-version"], env=env)
    if not re.search(r'version "(?:17|21)\.', java_version):
        raise RuntimeError("the benchmark requires JDK 17 or 21")
    hardware = run(["sysctl", "-n", "hw.model", "machdep.cpu.brand_string", "hw.memsize"]).strip() if platform.system() == "Darwin" else Path("/proc/cpuinfo").read_text()
    metadata = {"startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "mode": "pilot, potentially contended and excluded from the A1.7 baseline" if args.pilot else "A1.7 baseline",
                "platform": platform.platform(), "machine": platform.machine(), "cpuCount": os.cpu_count(),
                "hardware": " / ".join(hardware.splitlines()), "javaVersion": java_version,
                "openTsxCommit": run(["git", "rev-parse", "HEAD"]).strip(), "commands": [],
                "invocation": {"cwd": str(Path.cwd()), "argv": list(sys.argv), "python": sys.executable},
                "conditions": 2 * len(args.intervals) * args.repetitions,
                "scheduledSeconds": 2 * args.repetitions * args.increments * sum(args.intervals),
                "scenarioSha256": hashlib.sha256((ROOT / "opentsx-windpark/scenarios/ostsee-demo.yaml").read_bytes()).hexdigest()}
    metadata["harnessSha256"] = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in (
        "tools/wayang_increment_latency.py",
        "opentsx-wayang/src/test/java/org/opentsx/wayang/IncrementLatency.java",
        "opentsx-wayang/src/test/java/org/opentsx/wayang/LatencyProtocolObserver.java")}
    def command(argv, **kwargs):
        metadata["commands"].append(argv)
        (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
        return run(argv, **kwargs)
    with tempfile.TemporaryDirectory(prefix="opentsx-latency-") as temporary:
        work = Path(temporary)
        source = work / "kafscale"
        with (output / "build.log").open("w") as log:
            if args.kafscale_source:
                command(["git", "clone", "--no-hardlinks", str(args.kafscale_source.resolve()), str(source)], log=log)
            else:
                command(["git", "clone", "--branch", "v1.6.0", "--single-branch", KAFSCALE_REPOSITORY, str(source)], log=log)
            command(["git", "checkout", "--detach", KAFSCALE_COMMIT], cwd=source, log=log)
            if run(["git", "rev-parse", "HEAD"], cwd=source).strip() != KAFSCALE_COMMIT:
                raise RuntimeError("KafScale source does not match the pinned commit")
            go_env = env | {"GOTOOLCHAIN": "go1.25.2"}
            metadata["goVersion"] = command(["go", "version"], cwd=source, env=go_env).strip()
            binary = work / "kafscale-broker"
            overlay = build_kafscale_proxy.broker_overlay(source, work)
            command(["go", "build", "-o", str(binary), f"-overlay={overlay}", "./cmd/broker"], cwd=source, env=go_env, log=log)
            proxy = work / "kafscale-proxy"
            command(["go", "build", "-o", str(proxy), "./cmd/proxy"], cwd=source, env=go_env, log=log)
            cluster = work / "kafscale-cluster"
            command(["go", "build", "-o", str(cluster), str(ROOT / "tools/kafscale_proxy_cluster.go")], cwd=source, env=go_env, log=log)
            command(["mvn", "-B", "-ntp", "-pl", "opentsx-wayang", "-am", "install", "-DskipTests"], env=env, log=log)
            classpath_file = work / "test.cp"
            command(["mvn", "-B", "-ntp", "-pl", "opentsx-wayang", "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath",
                     "-DincludeScope=test", f"-Dmdep.outputFile={classpath_file}"], env=env, log=log)
        dependencies = classpath_file.read_text().strip().split(os.pathsep)
        if any(not entry for entry in dependencies):
            raise RuntimeError("resolved classpath contains an empty entry")
        classpath, metadata["runtimeSnapshot"] = freeze_classpath(ROOT,
                [ROOT / "opentsx-wayang/target/test-classes", ROOT / "opentsx-wayang/target/classes", *dependencies],
                work / "runtime")
        try:
            with power_assertion(metadata) as assertion:
                for repetition in range(1, args.repetitions + 1):
                    for interval in args.intervals:
                        for chain in ("passthrough", "clean"):
                            require_power_assertion(assertion)
                            case = output / f"{chain}-{interval}s-r{repetition}"
                            case.mkdir()
                            port = free_port()
                            broker_env = {k: v for k, v in env.items() if not k.startswith("KAFSCALE_")}
                            transport_command = [str(cluster), "--listen", f"127.0.0.1:{port}",
                                                 "--advertised-host", "127.0.0.1", "--advertised-port", str(port),
                                                 "--data-dir", str(case / "metadata"), "--broker-binary", str(binary),
                                                 "--proxy-binary", str(proxy)]
                            metadata.setdefault("brokerConfigurations", []).append({"transport": "kafscale-proxy", "command": transport_command})
                            with (case / "broker.log").open("w") as log:
                                broker = subprocess.Popen(transport_command, env=broker_env, stdout=log, stderr=subprocess.STDOUT)
                                try:
                                    deadline = time.monotonic() + 30
                                    while True:
                                        if broker.poll() is not None:
                                            raise RuntimeError(f"broker exited with code {broker.returncode}; inspect {case / 'broker.log'}")
                                        try:
                                            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                                                break
                                        except (ConnectionRefusedError, TimeoutError):
                                            if time.monotonic() >= deadline:
                                                raise TimeoutError("broker startup exceeded 30 seconds")
                                            time.sleep(0.1)
                                    with (case / "driver.log").open("w") as driver_log:
                                        command([java, "-Xms512m", "-Xmx2g", "-cp", classpath,
                                                 "org.opentsx.wayang.IncrementLatency", str(port), str(case), chain,
                                                 str(interval), str(args.increments)], cwd=ROOT / "opentsx-wayang", env=env, log=driver_log)
                                    require_power_assertion(assertion)
                                finally:
                                    broker.terminate()
                                    try:
                                        broker.wait(timeout=10)
                                    except subprocess.TimeoutExpired:
                                        broker.kill()
                                        broker.wait(timeout=10)
                            print(f"completed {case.name}", flush=True)
        finally:
            (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
            report(output, metadata)


if __name__ == "__main__":
    main()
