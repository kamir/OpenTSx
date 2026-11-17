#!/usr/bin/env python3
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / "target/rust-runner-compose"
BINARY = ROOT / "target/rust-runner-linux/release/tsx-runner"
WORKERS = [f"runner-{index}" for index in range(1, 4)]
COMPOSE = [
    "docker", "compose", "-p", "opentsx-rust-runner",
    "-f", "docker-compose.rust-runner.yml",
]
ALL_PROFILES = ["--profile", "workers", "--profile", "build"]
FIXTURE = "org.opentsx.wayang.RustRunnerComposeFixture"


def run(command, environment, name, timeout=900):
    path = DIRECTORY / f"{name}.log"
    print(f"{name}: {shlex.quote(str(command[0]))}", flush=True)
    with path.open("w", encoding="utf-8") as output:
        try:
            result = subprocess.run(command, cwd=ROOT, env=environment, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=timeout, check=False)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(f"{name} timed out; read {path}") from error
    if result.returncode != 0:
        detail = "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-40:])
        raise RuntimeError(f"{name} exited {result.returncode}; read {path}\n{detail}")
    return path.read_text(encoding="utf-8")


def java_command(environment, classpath):
    java_home = environment.get("JAVA_HOME")
    executable = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not executable or not Path(executable).is_file():
        raise RuntimeError("JDK 21 is required; set JAVA_HOME to its installation directory")
    return [executable, f"-Djdk.net.hosts.file={DIRECTORY / 'hosts'}", "-cp", classpath, FIXTURE]


def checkpoint_paths():
    return [DIRECTORY / f"state/worker-{index}/completed.json" for index in range(1, 4)]


def prepare_directories():
    for path in [DIRECTORY / "requests", DIRECTORY / "state", DIRECTORY / "checkpoints-initial",
                 ROOT / "target/rust-runner-linux"]:
        if path.is_symlink():
            raise RuntimeError(f"fixture directory must not be a symbolic link: {path}")
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    (DIRECTORY / "requests").mkdir(exist_ok=True)
    (DIRECTORY / "checkpoints-initial").mkdir(exist_ok=True)
    for path in checkpoint_paths():
        if path.parent.is_symlink():
            raise RuntimeError(f"checkpoint directory must not be a symbolic link: {path.parent}")
        path.parent.mkdir(parents=True, exist_ok=True)
        lock_path = path.parent / ".completed.json.lock"
        if lock_path.is_symlink():
            raise RuntimeError(f"checkpoint lock must not be a symbolic link: {lock_path}")
        with lock_path.open("a+b") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RuntimeError(f"the fixture checkpoint has a live owner: {path}") from error
            for existing in path.parent.iterdir():
                if existing.is_symlink() or not existing.is_file() or existing.name not in {
                    "completed.json", ".completed.json.pending", ".completed.json.lock"
                }:
                    raise RuntimeError(f"unexpected file in the dedicated checkpoint directory: {existing}")
                if existing != lock_path:
                    existing.unlink()
    (ROOT / "target/rust-runner-linux").mkdir(exist_ok=True)
    (DIRECTORY / "hosts").write_text("127.0.0.1 localhost kafscale-broker\n", encoding="utf-8")


def fixture_classpath(environment, skip_build):
    classes = ROOT / "opentsx-wayang/target/test-classes"
    fixture_class = classes / "org/opentsx/wayang/RustRunnerComposeFixture.class"
    dependency_file = ROOT / "opentsx-wayang/target/classpath.txt"
    if not skip_build:
        run(["mvn", "-B", "-ntp", "-pl", "opentsx-wayang", "-am", "-DskipTests", "install"],
            environment, "java-build", timeout=1800)
        run([str(ROOT / "bin/opentsx"), "classpath", "opentsx-wayang"], environment, "java-classpath")
    if not fixture_class.is_file() or not dependency_file.is_file():
        raise RuntimeError("Java fixture classes or classpath are missing; omit --skip-build")
    classpath = os.pathsep.join([str(classes), str(ROOT / "opentsx-wayang/target/classes"),
                                dependency_file.read_text(encoding="utf-8").strip()])
    if not dependency_file.read_text(encoding="utf-8").strip():
        raise RuntimeError("the Java dependency classpath is empty")
    return classpath


def run_workers(environment, phase):
    run(COMPOSE + ["--profile", "workers", "up", "--detach", "--force-recreate"] + WORKERS,
        environment, f"workers-{phase}-start", timeout=180)
    failure = None
    try:
        run(COMPOSE + ["--profile", "workers", "wait"] + WORKERS,
            environment, f"workers-{phase}-wait", timeout=1000)
    except RuntimeError as error:
        failure = error
    run(COMPOSE + ["--profile", "workers", "logs", "--no-color"] + WORKERS,
        environment, f"workers-{phase}", timeout=60)
    output = run(COMPOSE + ["--profile", "workers", "ps", "--all", "--format", "json"],
                 environment, f"workers-{phase}-status", timeout=60)
    try:
        parsed = json.loads(output)
    except json.JSONDecodeError:
        parsed = [json.loads(line) for line in output.splitlines() if line.strip()]
    records = parsed if isinstance(parsed, list) else [parsed]
    services = {record["Service"]: record for record in records}
    for worker in WORKERS:
        status = services.get(worker)
        if not status or status.get("State") != "exited" or status.get("ExitCode") != 0:
            raise RuntimeError(f"{worker} did not complete successfully; read {DIRECTORY / f'workers-{phase}.log'}") from failure
    if failure is not None:
        raise failure
    for index, worker in enumerate(WORKERS, start=1):
        output = run(COMPOSE + ["--profile", "workers", "logs", "--no-color", "--no-log-prefix", worker],
                     environment, f"{worker}-{phase}", timeout=60)
        receipts = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
        if len(receipts) != 1:
            raise RuntimeError(f"{worker} did not return exactly one completion receipt")
        verify_receipt(index, receipts[0])
        (DIRECTORY / f"receipt-{worker}-{phase}.json").write_text(
            json.dumps(receipts[0], sort_keys=True, indent=2) + "\n", encoding="utf-8")


def completion(path):
    value = json.loads(path.read_text(encoding="utf-8"))
    if set(value) != {"schema", "checksum", "payload"} or value["schema"] != "opentsx.local-checkpoint.v1":
        raise RuntimeError(f"invalid completed checkpoint envelope: {path}")
    payload = value["payload"]
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if hashlib.sha256(canonical).hexdigest() != value["checksum"]:
        raise RuntimeError(f"completed checkpoint checksum differs: {path}")
    if set(payload) != {"requestHash", "receipt", "state"}:
        raise RuntimeError(f"invalid completed checkpoint payload: {path}")
    if payload["requestHash"] != payload["receipt"]["requestHash"]:
        raise RuntimeError(f"completed checkpoint receipt identity differs: {path}")
    return payload


def verify_receipt(index, receipt):
    request_path = DIRECTORY / f"requests/worker-{index}.json"
    request_bytes = request_path.read_bytes()
    request = json.loads(request_bytes)
    assignment = json.loads(request["assignmentText"])
    saved = completion(DIRECTORY / f"state/worker-{index}/completed.json")
    expected = json.loads((DIRECTORY / "expected.json").read_text(encoding="utf-8"))["workers"][index - 1]
    positions = [{"partition": value["partition"], "next": value["to"]}
                 for value in assignment["inputs"][0]["positions"]]
    if receipt != saved["receipt"] or receipt["schema"] != "opentsx.local-receipt.v1":
        raise RuntimeError(f"worker {index} returned an outcome different from its durable receipt")
    for field in ["artifactDigest", "deployment", "generation", "assignment", "node"]:
        if receipt[field] != assignment[field]:
            raise RuntimeError(f"worker {index} receipt has a different {field}")
    if receipt["requestHash"] != hashlib.sha256(request_bytes).hexdigest():
        raise RuntimeError(f"worker {index} receipt has a different request hash")
    if (receipt["inputTopic"] != assignment["inputs"][0]["topic"]
            or receipt["outputTopic"] != request["sink"]["topic"]
            or receipt["positions"] != positions
            or receipt["inputRecords"] != expected["inputRecords"]
            or receipt["outputRecords"] != len(expected["expected"])):
        raise RuntimeError(f"worker {index} receipt does not cover its input cut and expected output")
    state = json.dumps(saved["state"], sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if receipt["stateDigest"] != hashlib.sha256(state).hexdigest():
        raise RuntimeError(f"worker {index} retained state digest differs")


def main():
    parser = argparse.ArgumentParser(description="Verify three isolated whole-topic Rust workers against Java output.")
    parser.add_argument("--keep", action="store_true", help="keep this Compose project and its volumes after verification")
    parser.add_argument("--skip-build", action="store_true", help="reuse the existing Linux binary and compiled Java fixture")
    args = parser.parse_args()
    raw_port = os.environ.get("KAFSCALE_BROKER_PORT", "39092")
    if not raw_port.isascii() or not raw_port.isdecimal() or raw_port != str(int(raw_port)) or not 1 <= int(raw_port) <= 65535:
        raise RuntimeError("KAFSCALE_BROKER_PORT must be a canonical integer from 1 to 65535")
    port = int(raw_port)
    bootstrap = f"127.0.0.1:{port}"
    if os.getuid() == 0:
        raise RuntimeError("run the local verification as an unprivileged user")
    for executable in ["docker"] + ([] if args.skip_build else ["mvn"]):
        if shutil.which(executable) is None:
            raise RuntimeError(f"required executable is missing: {executable}")
    environment = dict(os.environ, RUST_RUNNER_UID=str(os.getuid()), RUST_RUNNER_GID=str(os.getgid()),
                       KAFSCALE_BROKER_PORT=raw_port)
    if DIRECTORY.is_symlink() or (ROOT / "target").is_symlink():
        raise RuntimeError("the dedicated fixture directory and target must not be symbolic links")
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    run(COMPOSE + ALL_PROFILES + ["config", "--quiet"], environment, "compose-config", timeout=60)
    existing = run(COMPOSE + ALL_PROFILES + ["ps", "--all", "--quiet"], environment, "existing-project", timeout=60)
    if existing.strip():
        raise RuntimeError("project opentsx-rust-runner already has containers; remove only that project before a fresh verification")
    with socket.socket() as listener:
        try:
            listener.bind(("127.0.0.1", port))
        except OSError as error:
            raise RuntimeError(f"{bootstrap} is already in use; no existing service was stopped") from error
    prepare_directories()
    owned_project = False
    failure = None
    try:
        classpath = fixture_classpath(environment, args.skip_build)
        java = java_command(environment, classpath)
        if not args.skip_build:
            owned_project = True
            run(COMPOSE + ["--profile", "build", "run", "--rm", "--no-deps", "runner-build"],
                environment, "linux-build", timeout=1800)
        if not BINARY.is_file():
            raise RuntimeError("Linux runner binary is missing; omit --skip-build")
        artifact = "sha256:" + hashlib.sha256(BINARY.read_bytes()).hexdigest()
        (DIRECTORY / "artifact-digest.txt").write_text(artifact + "\n", encoding="utf-8")
        owned_project = True
        run(COMPOSE + ["up", "--detach", "--wait", "--wait-timeout", "180", "kafscale-broker"],
            environment, "broker-start", timeout=210)
        run(java + ["ready", bootstrap], environment, "broker-ready", timeout=210)
        run(java + ["prepare", bootstrap, str(DIRECTORY), artifact], environment, "fixture-prepare")
        run_workers(environment, "initial")
        run(java + ["verify", bootstrap, str(DIRECTORY), "initial"], environment, "fixture-initial")
        completed = {path: path.read_bytes() for path in checkpoint_paths()}
        payloads = {path: completion(path) for path in checkpoint_paths()}
        for path, content in completed.items():
            (DIRECTORY / "checkpoints-initial" / f"{path.parent.name}.json").write_bytes(content)
        run_workers(environment, "completed-retry")
        run(java + ["verify", bootstrap, str(DIRECTORY), "completed-retry"], environment, "fixture-completed-retry")
        for path, content in completed.items():
            if path.read_bytes() != content:
                raise RuntimeError(f"completed retry changed its checkpoint: {path}")
            path.unlink()
        run_workers(environment, "checkpoint-replay")
        run(java + ["verify", bootstrap, str(DIRECTORY), "checkpoint-replay"], environment, "fixture-checkpoint-replay")
        for path, payload in payloads.items():
            if completion(path) != payload:
                raise RuntimeError(f"whole-cut replay changed its receipt or retained state: {path}")
        for phase in ["initial", "completed-retry", "checkpoint-replay"]:
            report = json.loads((DIRECTORY / f"verify-{phase}.json").read_text(encoding="utf-8"))
            print(json.dumps(report, sort_keys=True), flush=True)
    except (OSError, RuntimeError, ValueError) as error:
        failure = error
    finally:
        if owned_project and not args.keep:
            try:
                run(COMPOSE + ALL_PROFILES + ["down", "--volumes", "--remove-orphans"], environment, "compose-cleanup", timeout=180)
            except RuntimeError as error:
                failure = RuntimeError(f"verification error: {failure}; cleanup error: {error}") if failure else error
        elif owned_project:
            print("Kept project opentsx-rust-runner and its volumes.", flush=True)
    if failure is not None:
        raise failure
    print(f"Verified three whole-topic Rust workers; evidence: {DIRECTORY}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
