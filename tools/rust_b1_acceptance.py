import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

import rust_b1_conformance as conformance


ROOT = Path(__file__).resolve().parents[1]
CASES = ("normal", "crash-1", "crash-150", "crash-299", "unknown", "malformed")
CASE_FIELDS = {"name", "plan", "config", "inputTopic", "outputTopic", "partitions", "inputCount",
               "expectedDistinct", "expectedRecords", "failAfter", "expectedError"}
ROW_FIELDS = {"name", "records", "outputRecords", "failedPrefix", "checkpoint", "exitCode",
              "statusState", "error", "signal", "stoppedReplay", "matched"}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require_fields(value, fields, name):
    if not isinstance(value, dict) or set(value) != fields:
        raise RuntimeError(f"{name} fields differ")


def require_int(value, expected, name):
    if type(value) is not int or value != expected:
        raise RuntimeError(f"{name} must equal {expected}")


def read_json(path):
    path = Path(path)
    safe_path(path)
    if not path.is_file() or path.stat().st_size > 2_097_152:
        raise RuntimeError("acceptance JSON evidence is missing or exceeds two MiB")
    try:
        return parse_json(path.read_bytes())
    except OSError as error:
        raise RuntimeError("cannot read acceptance JSON evidence") from error


def parse_json(data):
    def invalid_constant(value):
        raise RuntimeError("acceptance JSON evidence contains a non-finite number")
    try:
        return json.loads(conformance.log_bytes(data).decode("utf-8"),
                          object_pairs_hook=conformance.unique_object, parse_constant=invalid_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError("acceptance evidence must be one UTF-8 JSON document") from error


def safe_path(path):
    path = Path(path)
    if ".." in path.parts or any(part.is_symlink() for part in (path, *path.parents)):
        raise RuntimeError("acceptance paths must not traverse symbolic links or parent components")
    return path.absolute()


def executable(path):
    path = safe_path(path)
    if not path.is_file() or not os.access(path, os.X_OK):
        raise RuntimeError("each acceptance binary must be an executable regular file")
    return path


def classpath():
    target = ROOT / "opentsx-wayang/target"
    tests, classes = target / "test-classes", target / "classes"
    cache = target / "classpath.txt"
    if not tests.is_dir() or not classes.is_dir() or not cache.is_file():
        raise RuntimeError("build the Wayang test classes and bin/opentsx classpath cache first")
    for name in ("RustB1Fixture", "RustB1RunFixture"):
        if not (tests / f"org/opentsx/wayang/{name}.class").is_file():
            raise RuntimeError("the required compiled Java B1 fixture is missing")
    entries = cache.read_text(encoding="utf-8").strip().split(os.pathsep)
    if not entries or any(not entry or not Path(entry).is_absolute() or not Path(entry).exists()
                          for entry in entries):
        raise RuntimeError("the cached Java dependency classpath is empty, relative or missing")
    return os.pathsep.join([str(tests), str(classes), *entries])


def source_state():
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
                              capture_output=True, text=True).stdout.strip()
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        raise RuntimeError("acceptance source revision is not a full Git object id")
    dirty = bool(subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT,
                                check=True, capture_output=True, text=True).stdout)
    names = {
        ".github/workflows/ci.yml", "tools/rust_b1_acceptance.py", "tools/rust_b1_conformance.py",
        "tools/build_kafscale_proxy.py", "tools/kafscale_proxy_cluster.go", "tools/kafscale_proxy_cluster_test.go",
        "tools/kafscale_metadata_overlay.py", "tools/kafscale_metadata_test.go",
        "tools/kafscale_proxy_protocol_overlay.py",
        "tools/kafscale_proxy_protocol_test.go", "opentsx-wayang/pom.xml", "pom.xml",
        "opentsx-wayang/src/test/java/org/opentsx/wayang/RustB1Fixture.java",
        "opentsx-wayang/src/test/java/org/opentsx/wayang/RustB1RunFixture.java",
        "opentsx-windpark/scenarios/ostsee-demo.yaml",
    }
    for crate in ("tsx-contracts", "tsx-runner"):
        for name in ("Cargo.toml", "Cargo.lock"):
            names.add(f"rust/{crate}/{name}")
        for directory in ("src", "examples"):
            names.update(path.relative_to(ROOT).as_posix()
                         for path in (ROOT / f"rust/{crate}/{directory}").rglob("*.rs"))
    return {"commit": revision, "dirty": dirty,
            "files": {name: digest(ROOT / name) for name in sorted(names)}}


def invoke(command, output, bootstrap, timeout=900):
    output.mkdir(mode=0o700)
    environment = dict(os.environ, KAFSCALE_BOOTSTRAP=bootstrap)
    try:
        result = subprocess.run(command, cwd=ROOT / "opentsx-wayang", env=environment,
                                capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        (output / "stdout.log").write_bytes(conformance.log_bytes(error.stdout))
        (output / "stderr.log").write_bytes(conformance.log_bytes(error.stderr))
        raise RuntimeError("a B1 acceptance step timed out; read its retained logs") from error
    stdout, stderr = conformance.log_bytes(result.stdout), conformance.log_bytes(result.stderr)
    (output / "stdout.log").write_bytes(stdout)
    (output / "stderr.log").write_bytes(stderr)
    if result.returncode != 0:
        raise RuntimeError(f"a B1 acceptance step exited {result.returncode}; read its retained logs")
    return stdout


def validate_placement_manifest(value):
    require_fields(value, {"schema", "count", "partitions", "javaTopic", "rustTopic", "records"}, "placement manifest")
    if (value["schema"] != "opentsx.rust-b1-placement.v1" or value["javaTopic"] != "tsx.b1placement.java.v2"
            or value["rustTopic"] != "tsx.b1placement.rust.v2"):
        raise RuntimeError("placement manifest identity differs")
    require_int(value["count"], 10_000, "placement count")
    require_int(value["partitions"], 12, "placement partitions")
    rows = value["records"]
    if not isinstance(rows, list) or len(rows) != 10_000:
        raise RuntimeError("placement manifest must contain exactly 10000 records")
    keys = set()
    for index, row in enumerate(rows):
        require_fields(row, {"key", "index", "partition"}, "placement record")
        require_int(row["index"], index, "placement index")
        if (not isinstance(row["key"], str) or re.fullmatch(r"[0-9a-f]{32}", row["key"]) is None
                or row["key"] in keys or type(row["partition"]) is not int or not 0 <= row["partition"] < 12):
            raise RuntimeError("placement key or partition differs")
        keys.add(row["key"])


def validate_placement(value, fixture_hash=None):
    fields = {"schema", "keys", "partitions", "matched"}
    if fixture_hash is not None:
        fields.add("fixtureSha256")
    require_fields(value, fields, "placement result")
    if value["schema"] != "opentsx.rust-b1-placement-result.v1":
        raise RuntimeError("placement result schema differs")
    for name, expected in (("keys", 10_000), ("partitions", 12), ("matched", 10_000)):
        require_int(value[name], expected, f"placement {name}")
    if fixture_hash is not None and value["fixtureSha256"] != fixture_hash:
        raise RuntimeError("the Rust placement report names another Java fixture")
    return value


def validate_run_manifest(value, artifact, directory):
    require_fields(value, {"schema", "artifactDigest", "inputRecords", "series", "samples", "statusTopic", "cases", "stdin"},
                   "run fixture")
    if (value["schema"] != "opentsx.rust-b1-run-fixture.v1" or value["artifactDigest"] != artifact
            or value["statusTopic"] != "tsx.b1run.dag-status.v1"):
        raise RuntimeError("run fixture identity differs")
    for name, expected in (("inputRecords", 300), ("series", 100), ("samples", 180_000)):
        require_int(value[name], expected, f"run fixture {name}")
    if not isinstance(value["cases"], list) or len(value["cases"]) != 6:
        raise RuntimeError("run fixture must contain all six workload cases")
    for name, case in zip((*CASES, "stdin"), [*value["cases"], value["stdin"]]):
        require_fields(case, CASE_FIELDS, "run fixture case")
        if case["name"] != name or case["plan"] != f"{name}/plan.jws" or case["config"] != f"{name}/config.json":
            raise RuntimeError("run fixture case identity or paths differ")
        for field in ("plan", "config"):
            path = directory / case[field]
            safe_path(path)
            if not path.is_file() or path.stat().st_size == 0:
                raise RuntimeError("a run fixture plan or configuration is missing")
        bad = name in ("unknown", "malformed")
        success = not bad and name != "stdin"
        input_name = "normal" if success else name
        if (case["inputTopic"] != f"tsx.b1run.repartition.{input_name}.input.v2"
                or case["outputTopic"] != f"tsx.b1run.repartition.{name}.out.v2"):
            raise RuntimeError("a run fixture topic differs from the Java layout")
        prefix = int(name[6:]) if name.startswith("crash-") else None
        for field, expected in (("partitions", 3 if success else 1), ("inputCount", 300 if success else 2 if bad else 0),
                                ("expectedDistinct", 300 if success else 0),
                                ("expectedRecords", 300 + (prefix or 0) if success else 0)):
            require_int(case[field], expected, f"run fixture {field}")
        if (case["failAfter"] is not None and type(case["failAfter"]) is not int) or case["failAfter"] != prefix:
            raise RuntimeError("the crash boundary differs")
        if bad:
            if not isinstance(case["expectedError"], str) or not case["expectedError"].strip():
                raise RuntimeError("a negative run case is missing its exact error")
        elif case["expectedError"] is not None:
            raise RuntimeError("a successful run case expects an error")
    return value


def validate_run_report(value, manifest):
    require_fields(value, {"schema", "runnerVersion", "artifactDigest", "executedCases", "passedCases", "failedCases", "cases", "stdin"},
                   "Rust run report")
    if (value["schema"] != "opentsx.rust-b1-run-acceptance.v1" or value["runnerVersion"] != conformance.runner_version()
            or value["artifactDigest"] != manifest["artifactDigest"]):
        raise RuntimeError("Rust run report version or artifact differs")
    for name, expected in (("executedCases", 7), ("passedCases", 7), ("failedCases", 0)):
        require_int(value[name], expected, f"Rust run {name}")
    if not isinstance(value["cases"], list) or len(value["cases"]) != 6:
        raise RuntimeError("the Rust run report omits workload cases")
    for case, row in zip([*manifest["cases"], manifest["stdin"]], [*value["cases"], value["stdin"]]):
        require_fields(row, ROW_FIELDS, "Rust run result")
        name = case["name"]
        bad = case["expectedError"] is not None
        success = not bad and name != "stdin"
        for field, expected in (("records", 300 if success else 0), ("outputRecords", case["expectedRecords"]),
                                ("failedPrefix", case["failAfter"] or 0), ("exitCode", 3 if bad else 0)):
            require_int(row[field], expected, f"Rust run {field}")
        signal = None if bad else "SIGINT" if name in ("crash-1", "stdin") else "SIGTERM"
        if (row["name"] != name or row["statusState"] != ("failed" if bad else "stopped")
                or row["error"] != case["expectedError"] or row["signal"] != signal):
            raise RuntimeError("the Rust run result identity, error or Stop evidence differs")
        for field, expected in (("checkpoint", success), ("stoppedReplay", not bad), ("matched", True)):
            if type(row[field]) is not bool or row[field] != expected:
                raise RuntimeError("the Rust run result omits completed lifecycle evidence")
    return value


def validate_java_run(value, case):
    require_fields(value, {"schema", "case", "records", "distinct", "duplicates", "checkpoint", "statusState",
                           "statusRecords", "error", "matched"}, "Java run verification")
    bad = case["expectedError"] is not None
    success = not bad and case["name"] != "stdin"
    if (value["schema"] != "opentsx.rust-b1-run-verified.v1" or value["case"] != case["name"]
            or value["error"] != case["expectedError"] or value["statusState"] != ("failed" if bad else "stopped")):
        raise RuntimeError("the independent Java run verification identity or status differs")
    for field, expected in (("records", case["expectedRecords"]), ("distinct", case["expectedDistinct"]),
                            ("duplicates", case["failAfter"] or 0), ("matched", 300 if success else 0)):
        require_int(value[field], expected, f"Java run {field}")
    if type(value["checkpoint"]) is not bool or value["checkpoint"] != success:
        raise RuntimeError("the independent Java progress checkpoint result differs")
    require_int(value["statusRecords"], 300 if success else 0, "Java status records")
    return value


def execute(runner, conformance_binary, placement_binary, run_binary, output):
    bootstrap = conformance.endpoint(os.environ.get("KAFSCALE_BOOTSTRAP"))
    binaries = {name: executable(path) for name, path in (
        ("runner", runner), ("conformance", conformance_binary), ("placement", placement_binary), ("run", run_binary))}
    if len(set(binaries.values())) != 4:
        raise RuntimeError("the runner and three acceptance probes must be distinct binaries")
    output = safe_path(output)
    if output.exists():
        raise RuntimeError("the combined B1 evidence directory already exists")
    java = ["java", "-cp", classpath()]
    before = source_state()
    hashes = {name: digest(path) for name, path in binaries.items()}
    output.mkdir(parents=True, mode=0o700)
    version = invoke([str(binaries["runner"]), "--version"], output / "version", bootstrap).decode("utf-8").strip()
    if version != f"tsx-runner {conformance.runner_version()}":
        raise RuntimeError("the actual runner version differs from its manifest")
    conform = conformance.execute(binaries["conformance"], output / "conformance")
    placement = output / "placement"
    placement.mkdir(mode=0o700)
    fixture = [*java, "org.opentsx.wayang.RustB1Fixture"]
    invoke([*fixture, "placement-prepare", bootstrap, str(placement)], placement / "prepare", bootstrap)
    manifest_path = placement / "placement.json"
    validate_placement_manifest(read_json(manifest_path))
    rust_placement = validate_placement(parse_json(invoke([str(binaries["placement"]), str(manifest_path)],
                                                       placement / "probe", bootstrap)), digest(manifest_path))
    invoke([*fixture, "placement-verify", bootstrap, str(placement)], placement / "verify", bootstrap)
    java_placement = validate_placement(read_json(placement / "placement-verified.json"))
    placement_result = {"rust": rust_placement, "java": java_placement}
    (placement / "result.json").write_text(json.dumps(placement_result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    run = output / "run"
    run.mkdir(mode=0o700)
    fixture = [*java, "org.opentsx.wayang.RustB1RunFixture"]
    artifact = "sha256:" + hashes["runner"]
    invoke([*fixture, "prepare", bootstrap, str(run), artifact], run / "prepare", bootstrap)
    manifest = validate_run_manifest(read_json(run / "run-fixture.json"), artifact, run)
    rust_run = validate_run_report(parse_json(invoke([str(binaries["run"]), str(binaries["runner"]), str(run)],
                                                    run / "probe", bootstrap, timeout=1800)), manifest)
    if read_json(run / "rust-run-report.json") != rust_run:
        raise RuntimeError("the saved Rust run report differs from its original stdout")
    java_runs = []
    for case in [*manifest["cases"], manifest["stdin"]]:
        name = case["name"]
        invoke([*fixture, "verify", bootstrap, str(run), name], run / f"java-{name}", bootstrap)
        java_runs.append(validate_java_run(read_json(run / f"verified-{name}.json"), case))
    if hashes != {name: digest(path) for name, path in binaries.items()} or before != source_state():
        raise RuntimeError("acceptance source or executable bytes changed during execution")
    evidence = {path.relative_to(output).as_posix(): digest(path) for path in sorted(output.rglob("*"))
                if path.suffix in (".log", ".stdout", ".stderr")}
    result = {"schema": "opentsx.rust-b1-acceptance.v1", "runnerVersion": conformance.runner_version(),
              "endpoint": bootstrap, "source": before, "binaries": hashes,
              "host": {"os": platform.system(), "architecture": platform.machine()},
              "conformance": conform, "placement": placement_result, "run": rust_run,
              "javaWorkloadCases": java_runs[:6], "javaStdinCase": java_runs[6], "evidence": evidence,
              "physicalArmv7Qualified": False}
    (output / "report.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description="Run all B1 Proxy acceptance probes and retain independently checked execution evidence.")
    parser.add_argument("--runner", type=Path, required=True, help="the actual tsx-runner executable admitted by signed plans")
    parser.add_argument("--conformance-binary", type=Path, required=True, help="compiled b1_acceptance executable")
    parser.add_argument("--placement-binary", type=Path, required=True, help="compiled b1_placement executable")
    parser.add_argument("--run-binary", type=Path, required=True, help="compiled b1_run_acceptance executable")
    parser.add_argument("--output", type=Path, required=True, help="new combined evidence directory")
    args = parser.parse_args()
    result = execute(args.runner, args.conformance_binary, args.placement_binary, args.run_binary, args.output)
    print(json.dumps({"report": str(args.output / "report.json"), "schema": result["schema"],
                      "protocolExecutions": result["conformance"]["passedCases"], "placementKeys": 10_000,
                      "workloadCases": 6, "stdinCases": 1, "physicalArmv7Qualified": False}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, UnicodeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
