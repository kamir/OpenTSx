import argparse
from concurrent.futures import ThreadPoolExecutor
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

import build_kafscale_proxy


ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / "target/wayang-rust-compose"
BINARY = ROOT / "target/wayang-rust-linux/release/tsx-runner"
PROJECT = "opentsx-wayang-rust"
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", "docker-compose.wayang-rust.yml"]
FIXTURE = "org.opentsx.wayang.RustRolloutComposeFixture"
CONTINUOUS_FIXTURE = "org.opentsx.wayang.RustContinuousComposeFixture"
FENCING_FIXTURE = "org.opentsx.wayang.RustFencingComposeFixture"
DENIAL_PROBE = "org.opentsx.wayang.RustFencingDenialProbe"
REPLACEMENT_OUTPUT = "tsx.rust-fencing-restarted.clean.v2"
DATA_SERVICES = ["etcd", "kafscale-broker", "kafscale-read", "kafscale-write", "postgres"]
RUNTIME_SERVICES = [*DATA_SERVICES, "runner-1", "runner-2", "runner-3", "wayang", "backend", "coordinator", "frontend"]
CONTINUOUS_PHASES = [("initial", 1), ("retry", 1), ("new-data", 2), ("restart", 4),
                     ("after-restart", 5), ("worker-unavailable", 5), ("worker-restart", 6),
                     ("after-worker-restart", 7), ("stopped", 7)]
FENCING_PHASES = [("initial", 1), ("fence-resume", 1), ("resume-data", 2), ("runtime-restart", 3),
                  ("changed-dag", 3), ("changed-data", 4), ("stopped", 4)]
SECRET_VALUES = []


def sanitize(value):
    for secret in SECRET_VALUES:
        value = value.replace(secret, "<redacted>")
    return re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "<redacted>", value)


def run(command, environment, name, timeout=900, cwd=ROOT):
    print(f"{name}: {Path(command[0]).name}", flush=True)
    try:
        result = subprocess.run(command, cwd=cwd, env=environment, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f"{name} timed out") from error
    output = sanitize(result.stdout.decode("utf-8", errors="replace"))
    (DIRECTORY / f"{name}.log").write_text(output, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{name} exited {result.returncode}:\n" + "\n".join(output.splitlines()[-30:]))
    return output


def port_number(value, name):
    if not value.isascii() or not value.isdecimal() or value != str(int(value)) or not 1 <= int(value) <= 65535:
        raise RuntimeError(f"{name} must be a canonical integer from 1 to 65535")
    return int(value)


def free_port(port):
    with socket.socket() as listener:
        try:
            listener.bind(("127.0.0.1", port))
        except OSError as error:
            raise RuntimeError(f"127.0.0.1:{port} is occupied; no existing service was stopped") from error


def empty_project(environment):
    output = run(["docker", "ps", "--all", "--filter", f"label=com.docker.compose.project={PROJECT}",
                  "--format", "{{.ID}}"], environment, "existing-project", timeout=60)
    if output.strip():
        raise RuntimeError(f"project {PROJECT} already has containers; no existing project was changed")
    volumes = run(["docker", "volume", "ls", "--filter", f"label=com.docker.compose.project={PROJECT}",
                   "--format", "{{.Name}}"], environment, "existing-volumes", timeout=60)
    if any(name not in {f"{PROJECT}_runner-cargo", f"{PROJECT}_backend-python"}
           for name in volumes.splitlines() if name):
        raise RuntimeError(f"project {PROJECT} has retained data volumes; no existing data was changed")


def private_file(path, text):
    if path.is_symlink() or path.exists():
        raise RuntimeError(f"private file already exists: {path.name}")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(text)


def container_classpath(classpath, repository):
    result = []
    for item in classpath.split(os.pathsep):
        path = Path(item).resolve()
        if path.is_relative_to(ROOT):
            result.append("/workspace/" + path.relative_to(ROOT).as_posix())
        elif path.is_relative_to(repository):
            result.append("/m2/" + path.relative_to(repository).as_posix())
        else:
            raise RuntimeError("Java classpath contains an entry outside the mounted source and Maven repository")
    return ":".join(result)


def source_signature():
    digest = hashlib.sha256()
    names = set()
    for folder in ["rust/tsx-runner", "rust/tsx-contracts", "opentsx-wayang/src", "opentsx-model/src",
                   "opentsx-saas-frontend/src", "opentsx-saas-backend/app", "systems/processors", "systems/catalogs"]:
        for path in (ROOT / folder).rglob("*"):
            if path.is_file() and "target" not in path.parts and path.suffix in {
                    ".rs", ".toml", ".lock", ".java", ".properties", ".json", ".avsc", ".ts", ".tsx", ".css", ".py", ".yaml"}:
                names.add(path)
    for name in ["pom.xml", "opentsx-wayang/pom.xml", "opentsx-model/pom.xml",
                 "opentsx-saas-frontend/package-lock.json", "opentsx-saas-backend/requirements.txt",
                 "docker-compose.wayang-rust.yml", "deploy/compose/wayang-rust-nginx.conf",
                 "tools/verify_wayang_rust_compose.py", "tools/wayang_rust_browser.mjs",
                 "tools/build_kafscale_proxy.py", "tools/kafscale_proxy_cluster.go",
                 "tools/kafscale_metadata_overlay.py", "tools/kafscale_metadata_test.go",
                 "tools/kafscale_proxy_protocol_overlay.py", "tools/kafscale_proxy_protocol_test.go",
                 "bin/opentsx", "opentsx-saas-frontend/package.json",
                 "opentsx-saas-frontend/vite.config.ts", "opentsx-saas-frontend/tsconfig.json",
                 "opentsx-saas-frontend/tsconfig.node.json", "opentsx-saas-frontend/postcss.config.js",
                 "opentsx-saas-frontend/tailwind.config.js", "opentsx-saas-frontend/index.html"]:
        names.add(ROOT / name)
    for path in sorted(names):
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def verify_build_sources(expected):
    if source_signature() != expected:
        raise RuntimeError("runtime sources changed during the build; rebuild this profile")


def clear_run_artifacts():
    names = {"result.json", "fixture.json", "submitted-dag.txt", "submitted-dag-replacement.txt", "verified.json",
             "worker-outage.json", "deployment-predecessor.json", "fencing-denials.json", "fencing-clone.json"}
    for phase in {"initial", "retry", "restart", *(name for name, _ in CONTINUOUS_PHASES), *(name for name, _ in FENCING_PHASES)}:
        names.update({f"deployment-{phase}.json", f"browser-{phase}.json", f"browser-{phase}.png",
                      f"browser-{phase}-failed.png", f"verified-{phase}.json"})
    for name in names:
        (DIRECTORY / name).unlink(missing_ok=True)


def wait_http(url, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError("the isolated frontend/backend did not become healthy")


def wait_driver(compose, environment):
    probe = ("import os,urllib.request; request=urllib.request.Request(os.environ['WAYANG_URL'].rstrip('/')+'/v1/capabilities',"
             "headers={'X-OpenTSx-Run-Token':os.environ['DAG_RUN_TOKEN']}); urllib.request.urlopen(request,timeout=5).read()")
    deadline = time.monotonic() + 180
    while True:
        try:
            run(compose + ["exec", "-T", "backend", "/opt/venv/bin/python", "-c", probe],
                environment, "driver-ready", timeout=15)
            return
        except RuntimeError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


def restart_runtime(compose, environment):
    run(compose + ["restart", "runner-1", "runner-2", "runner-3", "wayang", "coordinator"],
        environment, "runtime-restart", timeout=180)
    wait_driver(compose, environment)


def resume_with_backlog(java, bootstrap, compose, environment, blocks):
    services = ["coordinator", "wayang", "runner-1", "runner-2", "runner-3"]
    run(compose + ["stop", *services], environment, "runtime-stop", timeout=180)
    run(java + ["append", bootstrap, str(DIRECTORY), str(blocks)], environment, "fixture-backlog")
    run(compose + ["start", *services], environment, "runtime-start-again", timeout=180)
    wait_driver(compose, environment)


def interrupt_one_worker(java, bootstrap, compose, environment, blocks):
    command = compose + ["ps", "--all", "--format", "{{.Service}} {{.ID}} {{.State}}"]
    before = run(command, environment, "worker-outage-before", timeout=60)
    run(compose + ["stop", "runner-2"], environment, "worker-outage-stop", timeout=180)
    run(java + ["append", bootstrap, str(DIRECTORY), str(blocks)], environment, "fixture-worker-outage")
    after = run(command, environment, "worker-outage-during", timeout=60)
    snapshots = []
    for output in (before, after):
        snapshot = {}
        for line in output.splitlines():
            fields = line.split()
            if len(fields) != 3 or fields[0] in snapshot:
                raise RuntimeError("single-worker outage inventory is invalid")
            snapshot[fields[0]] = fields[1:]
        snapshots.append(snapshot)
    original, current = snapshots
    live = set(RUNTIME_SERVICES) - {"runner-2"}
    if (set(original) != live | {"runner-2"} or set(current) != set(original)
            or any(state != "running" for _, state in original.values())
            or any(current[name] != original[name] for name in live)
            or current["runner-2"] != [original["runner-2"][0], "exited"]):
        raise RuntimeError("single-worker outage changed another service or did not stop only runner-2")
    (DIRECTORY / "worker-outage.json").write_text(json.dumps({"worker": "worker-2", "throughBlocks": blocks,
        "liveServices": sorted(live)}, indent=2) + "\n")


def java_setup(environment, private, repository, skip_build, fixture=FIXTURE):
    if not skip_build:
        run(["mvn", "-B", "-ntp", "-pl", "opentsx-wayang", "-am", "-DskipTests", "install"],
            environment, "java-build", timeout=1800)
        run([str(ROOT / "bin/opentsx"), "classpath", "opentsx-wayang"], environment, "java-classpath")
    dependency_file = ROOT / "opentsx-wayang/target/classpath.txt"
    classes = ROOT / "opentsx-wayang/target/classes"
    test_classes = ROOT / "opentsx-wayang/target/test-classes"
    if not dependency_file.is_file() or not (test_classes / (fixture.replace(".", "/") + ".class")).is_file():
        raise RuntimeError("Java classes are missing; omit --skip-build")
    dependencies = dependency_file.read_text().strip()
    if not dependencies:
        raise RuntimeError("Java dependency classpath is empty")
    driver_classpath = container_classpath(os.pathsep.join([str(classes), dependencies]), repository)
    arguments = ["-cp", driver_classpath, "org.opentsx.wayang.rust.RustRolloutServer", "--listen", "172.29.76.10:8095",
                 "--bootstrap", f"kafscale-read:{environment['KAFSCALE_BROKER_PORT']}", "--catalog", "/workspace/systems/processors",
                 "--publication-listen", "172.29.74.10:8096", "--publication-bootstrap", f"kafscale-write:{environment['KAFSCALE_BROKER_PORT']}",
                 "--publication-state", "/publication", "--run-token-file", "/settings/run-token"]
    for number in range(1, 4):
        arguments.extend(["--worker", f"worker-{number}=http://runner-{number}:8090"])
    private_file(private / "driver.args", "\n".join(json.dumps(value) for value in arguments) + "\n")
    java_home = environment.get("JAVA_HOME")
    executable = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not executable:
        raise RuntimeError("JDK 21 is required")
    return [executable, f"-Djdk.net.hosts.file={private / 'hosts'}", "-cp",
            os.pathsep.join([str(test_classes), str(classes), dependencies]), fixture]


def verify_views():
    fixture = json.loads((DIRECTORY / "fixture.json").read_text())
    initial = json.loads((DIRECTORY / "deployment-initial.json").read_text())
    graph = (DIRECTORY / "submitted-dag.txt").read_bytes()
    if initial["specHash"] != hashlib.sha256(graph).hexdigest() or initial["state"] != "completed":
        raise RuntimeError("the UI source hash does not match its completed durable deployment")
    expected = {item["topic"]: str(item["partitions"]) for item in fixture["sources"]}
    for phase in ["initial", "retry", "restart"]:
        value = json.loads((DIRECTORY / f"deployment-{phase}.json").read_text())
        browser = json.loads((DIRECTORY / f"browser-{phase}.json").read_text())
        verified = json.loads((DIRECTORY / f"verified-{phase}.json").read_text())
        if value != initial or browser["phase"] != phase or browser["assignments"] != 3:
            raise RuntimeError("a completed retry changed its deployment, cut, clock or receipt")
        assignments = value["assignments"]
        if len(assignments) != 3 or len({item["node"] for item in assignments}) != 3:
            raise RuntimeError("expected three distinct whole-topic worker assignments")
        if {item["inputTopic"]: item["partitionCount"] for item in assignments} != expected:
            raise RuntimeError("a worker split or changed the complete topic inventory")
        if verified["verifiedRecords"] != 12 or verified["distinctEpisodes"] != 12:
            raise RuntimeError("Java parity did not verify exactly twelve immutable output episodes")
    return {"schema": "opentsx.wayang-rust-compose-result.v1", "deployment": initial["id"],
            "specHash": initial["specHash"], "planHash": initial["planHash"],
            "createdAtMicros": initial["createdAtMicros"], "workers": 3, "phases": ["initial", "retry", "restart"],
            "verifiedRecords": 12, "distinctEpisodes": 12}


def verify_continuous_views():
    fixture = json.loads((DIRECTORY / "fixture.json").read_text())
    initial = json.loads((DIRECTORY / "deployment-initial.json").read_text())
    source_hash = hashlib.sha256((DIRECTORY / "submitted-dag.txt").read_bytes()).hexdigest()
    sources = {source["topic"]: source["partitions"] for source in fixture["sources"]}
    if len(sources) != 3 or initial["specHash"] != source_hash:
        raise RuntimeError("the continuous source identity or complete-topic inventory changed")
    outage = json.loads((DIRECTORY / "worker-outage.json").read_text())
    if outage != {"worker": "worker-2", "throughBlocks": 6, "liveServices": sorted(set(RUNTIME_SERVICES) - {"runner-2"})}:
        raise RuntimeError("the single-worker outage evidence is incomplete")
    placement = {item["inputTopic"]: item["node"] for item in initial["assignments"]}
    previous = None
    last_count = 0
    for phase, blocks in CONTINUOUS_PHASES:
        value = json.loads((DIRECTORY / f"deployment-{phase}.json").read_text())
        browser = json.loads((DIRECTORY / f"browser-{phase}.json").read_text())
        verified = json.loads((DIRECTORY / f"verified-{phase}.json").read_text())
        if (any(value[key] != initial[key] for key in ("id", "specHash", "createdAtMicros"))
                or value["profile"] != "continuous" or value["state"] != ("stopped" if phase == "stopped" else "idle")
                or value["stopRequested"] != (phase == "stopped")
                or not re.fullmatch(r"[0-9a-f]{64}", value["planHash"])):
            raise RuntimeError("the continuous deployment changed its identity, clock or execution state")
        if (browser["phase"] != phase or browser["assignments"] != 3 or browser["profile"] != "continuous"
                or browser["blocks"] != str(blocks) or browser["submissions"] != int(phase in ("initial", "retry"))):
            raise RuntimeError("continuous input required an unexpected browser resubmission")
        cycle = decimal_counter(value["completedCycles"])
        if cycle < last_count or cycle < 1 or value["currentCycle"] != str(cycle):
            raise RuntimeError("the continuous cycle watermark moved backwards or remains unconfirmed")
        last_count = cycle
        total_input, total_output = 0, 0
        assignments = value["assignments"]
        if (len(assignments) != 3 or len({item["node"] for item in assignments}) != 3
                or {item["inputTopic"]: item["node"] for item in assignments} != placement
                or set(placement) != set(sources)):
            raise RuntimeError("continuous placement split or moved a complete topic")
        for assignment in assignments:
            count = sources[assignment["inputTopic"]]
            expected_positions = [{"partition": str(partition), "next": str(blocks)} for partition in range(count)]
            input_count = decimal_counter(assignment["cumulativeInputRecords"])
            if (assignment["partitionCount"] != str(count) or assignment["positions"] != expected_positions
                    or input_count != count * blocks):
                raise RuntimeError("continuous progress omits or replays part of a whole topic")
            total_input += input_count
            total_output += decimal_counter(assignment["cumulativeOutputRecords"])
        if (value["inputRecords"] != str(total_input) or value["outputRecords"] != str(total_output)
                or verified["inputRecords"] != total_input or verified["throughBlocks"] != blocks
                or verified["verifiedRecords"] != total_output or verified["distinctEpisodes"] != total_output
                or verified["workerReceipts"] != 3):
            raise RuntimeError("continuous Java parity or cumulative record counts do not match")
        if phase == "retry" and value != initial:
            raise RuntimeError("an exact continuous retry changed its idle outcome")
        if phase == "worker-unavailable" and not isinstance(value.get("error"), str):
            raise RuntimeError("the unavailable worker was not observed by the coordinator")
        if phase != "worker-unavailable" and value.get("error") is not None:
            raise RuntimeError("the continuous deployment retains an unresolved error")
        if phase in ("worker-unavailable", "stopped") and any(value[key] != previous[key] for key in (
                "planHash", "currentCycle", "completedCycles", "inputRecords", "outputRecords", "assignments")):
            raise RuntimeError("stopping or interrupting the continuous deployment changed its confirmed output")
        previous = value
    return {"schema": "opentsx.wayang-rust-continuous-compose-result.v1", "profile": "continuous",
            "deployment": initial["id"], "specHash": source_hash, "createdAtMicros": initial["createdAtMicros"],
            "planHash": previous["planHash"], "workers": 3, "completedCycles": previous["completedCycles"],
            "inputRecords": previous["inputRecords"], "phases": [phase for phase, _ in CONTINUOUS_PHASES],
            "verifiedRecords": total_output, "distinctEpisodes": total_output}


def decimal_counter(value):
    if not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]*", value):
        raise RuntimeError("continuous counters must be canonical nonnegative decimal strings")
    return int(value)


def verify_fencing_views():
    fixture = json.loads((DIRECTORY / "fixture.json").read_text())
    initial = json.loads((DIRECTORY / "deployment-initial.json").read_text())
    hashes = {False: hashlib.sha256((DIRECTORY / "submitted-dag.txt").read_bytes()).hexdigest(),
              True: hashlib.sha256((DIRECTORY / "submitted-dag-replacement.txt").read_bytes()).hexdigest()}
    if hashes[False] == hashes[True]:
        raise RuntimeError("the replacement DAG must differ from the original UI graph")
    sources = {item["topic"]: item["partitions"] for item in fixture["sources"]}
    placement = {item["inputTopic"]: item["node"] for item in initial["assignments"]}
    if len(sources) != 3 or set(placement) != set(sources):
        raise RuntimeError("fencing requires three complete topic assignments")
    previous, target = None, None
    for phase, blocks in FENCING_PHASES:
        replacement = phase in ("changed-dag", "changed-data", "stopped")
        value = json.loads((DIRECTORY / f"deployment-{phase}.json").read_text())
        browser = json.loads((DIRECTORY / f"browser-{phase}.json").read_text())
        verified = json.loads((DIRECTORY / f"verified-{phase}.json").read_text())
        if phase == "changed-dag":
            target = value
        identity = target if replacement else initial
        epoch = decimal_counter(value["publicationEpoch"])
        cycles = decimal_counter(value["completedCycles"])
        if (value["profile"] != "continuous" or value["state"] != ("stopped" if phase == "stopped" else "idle")
                or value["stopRequested"] != (phase == "stopped") or value["publicationRetired"] != (phase == "stopped")
                or epoch < 1 or cycles < 1 or value["currentCycle"] != str(cycles)
                or value["error"] is not None or value["specHash"] != hashes[replacement]
                or any(value[key] != identity[key] for key in ("id", "flowId", "createdAtMicros"))):
            raise RuntimeError("the fenced deployment changed its source identity, clock or confirmed state")
        if (browser["phase"] != phase or browser["profile"] != "fencing" or browser["assignments"] != 3
                or browser["blocks"] != str(blocks) or browser["publicationEpoch"] != value["publicationEpoch"]
                or browser["submissions"] != int(phase == "initial")
                or browser["switches"] != int(phase in ("fence-resume", "changed-dag"))):
            raise RuntimeError("fencing used an unexpected browser deployment or replacement request")
        if previous is not None:
            previous_epoch = decimal_counter(previous["publicationEpoch"])
            if (epoch <= previous_epoch if phase in ("fence-resume", "changed-dag", "stopped") else epoch != previous_epoch):
                raise RuntimeError("publication authority did not rotate only at the required whole-topic boundary")
        if phase in ("fence-resume", "changed-dag"):
            switch = value.get("switch", {})
            if (switch.get("state") != "active" or switch.get("mode") != ("restart" if replacement else "resume")
                    or switch.get("publicationEpoch") != value["publicationEpoch"] or switch.get("deployment") != value["id"]):
                raise RuntimeError("the publication replacement lacks an active confirmed operation")
        checkpoint = ("planHash", "currentCycle", "completedCycles", "inputRecords", "outputRecords", "assignments")
        if phase in ("fence-resume", "stopped") and any(value[key] != previous[key] for key in checkpoint):
            raise RuntimeError("resuming or stopping changed the last verified checkpoint")
        if phase == "changed-dag" and (value["id"] == initial["id"] or value["createdAtMicros"] == initial["createdAtMicros"]):
            raise RuntimeError("the changed DAG reused the previous deployment identity or clock")
        assignments = value["assignments"]
        if (len(assignments) != 3 or {item["inputTopic"]: item["node"] for item in assignments} != placement
                or any(item["outputTopic"] != (REPLACEMENT_OUTPUT if replacement else fixture["outputTopic"]) for item in assignments)):
            raise RuntimeError("fencing split a topic, changed placement or reused the previous output")
        total_input, total_output = 0, 0
        for item in assignments:
            count = sources[item["inputTopic"]]
            if (item["partitionCount"] != str(count) or item["cumulativeInputRecords"] != str(count * blocks)
                    or item["positions"] != [{"partition": str(p), "next": str(blocks)} for p in range(count)]):
                raise RuntimeError("fenced execution omitted or replayed part of a complete topic")
            total_input += count * blocks
            total_output += decimal_counter(item["cumulativeOutputRecords"])
        if (value["inputRecords"] != str(total_input) or value["outputRecords"] != str(total_output)
                or verified["inputRecords"] != total_input or verified["throughBlocks"] != blocks
                or verified["verifiedRecords"] != total_output or verified["distinctEpisodes"] != total_output
                or verified["workerReceipts"] != 3):
            raise RuntimeError("fenced output failed direct Java parity or cumulative count verification")
        previous = value
    predecessor = json.loads((DIRECTORY / "deployment-predecessor.json").read_text())
    if predecessor["id"] != initial["id"] or predecessor["state"] != "replaced" or predecessor["publicationRetired"] is not True:
        raise RuntimeError("the preceding deployment is not retired after the changed-DAG restart")
    denial = json.loads((DIRECTORY / "fencing-denials.json").read_text())
    clone = json.loads((DIRECTORY / "fencing-clone.json").read_text())
    resume = json.loads((DIRECTORY / "deployment-fence-resume.json").read_text())
    if (denial.get("schema") != "opentsx.fencing-denials.v1" or denial.get("oldGrantHttpStatus") != 403
            or denial.get("forgedEpochHttpStatus") != 403 or denial.get("capturedEpoch") != initial["publicationEpoch"]
            or denial.get("currentEpoch") != resume["publicationEpoch"] or denial.get("outputTopic") != fixture["outputTopic"]
            or any(denial.get(key) is not True for key in ("readProxyProduceDenied", "directBrokerBlocked",
                "writeProxyBlocked", "hostGatewayBlocked", "outputOffsetsUnchanged"))
            or clone.get("state") != "failed" or clone.get("activationRetryable") is not True
            or clone.get("publicationEpoch") != denial["capturedEpoch"] or clone.get("requestHash") != denial.get("requestHash")
            or clone.get("error") != "publication authority refused the captured grant or request"):
        raise RuntimeError("the restored old worker or a publication bypass was not denied")
    ends = denial.get("endOffsets")
    if (not isinstance(ends, dict) or set(ends) != {str(p) for p in range(fixture["outputPartitions"])}
            or sum(decimal_counter(value) for value in ends.values()) != decimal_counter(initial["outputRecords"])):
        raise RuntimeError("the denied old worker changed the output end offsets")
    return {"schema": "opentsx.wayang-rust-fencing-compose-result.v1", "profile": "fencing", "workers": 3,
            "deployment": target["id"], "predecessor": initial["id"], "publicationEpoch": previous["publicationEpoch"],
            "phases": [phase for phase, _ in FENCING_PHASES], "verifiedRecords": total_output,
            "distinctEpisodes": total_output, "inputRecords": total_input, "staleWorkerDenied": True,
            "oldGrantHttpStatus": denial["oldGrantHttpStatus"], "forgedEpochHttpStatus": denial["forgedEpochHttpStatus"],
            "outputOffsetsUnchanged": True}


def verify_continuous(java, browser, compose, bootstrap, environment):
    for phase, blocks in CONTINUOUS_PHASES:
        if phase == "restart":
            resume_with_backlog(java, bootstrap, compose, environment, blocks)
        elif phase == "worker-unavailable":
            interrupt_one_worker(java, bootstrap, compose, environment, blocks + 1)
        elif phase == "worker-restart":
            run(compose + ["start", "runner-2"], environment, "worker-outage-resume", timeout=180)
            wait_driver(compose, environment)
        elif phase in ("new-data", "after-restart", "after-worker-restart"):
            run(java + ["append", bootstrap, str(DIRECTORY), str(blocks)], environment, f"fixture-{phase}")
        run(browser + ["--phase", phase, "--blocks", str(blocks)], environment, f"browser-{phase}", timeout=900)
        run(java + ["verify", bootstrap, str(DIRECTORY), str(DIRECTORY / f"deployment-{phase}.json"),
                    str(DIRECTORY / "submitted-dag.txt"), str(blocks)], environment, f"parity-{phase}", timeout=240)
        shutil.copyfile(DIRECTORY / "verified.json", DIRECTORY / f"verified-{phase}.json")
    return verify_continuous_views()


def container_image(service):
    indentation = "" if service.startswith("x-") else "  "
    match = re.search(r"^" + indentation + re.escape(service) + r":[^\n]*\n" + indentation
                      + r"  image: ([^\n]+)$", (ROOT / "docker-compose.wayang-rust.yml").read_text(), re.MULTILINE)
    if match is None or not re.fullmatch(r"[a-z0-9:/.-]+@sha256:[0-9a-f]{64}", match[1]):
        raise RuntimeError("the acceptance container image must match its pinned Compose service")
    return match[1]


def controller_probe(java, compose, environment, *arguments):
    repository = Path(environment["WAYANG_M2_REPOSITORY"]).resolve()
    classpath = container_classpath(java[3], repository)
    result = run(compose + ["exec", "-T", "wayang", "java", "-cp", classpath, DENIAL_PROBE, *arguments],
                 environment, "worker-" + arguments[0], timeout=240)
    value = json.loads(result)
    if not isinstance(value, dict):
        raise RuntimeError("the worker control probe returned an invalid public status")
    return value


def denial_classpath_volumes(classpath, repository):
    result = ["--volume", str(repository) + ":/m2:ro"]
    for entry in classpath.split(os.pathsep):
        source = Path(entry).resolve()
        if not source.is_relative_to(ROOT):
            if not source.is_relative_to(repository):
                raise RuntimeError("the denial classpath contains an unmounted entry")
            continue
        relative = source.relative_to(ROOT)
        if source.name not in ("classes", "test-classes") and source.suffix != ".jar":
            raise RuntimeError("the denial probe may mount only compiled classes and dependencies")
        result.extend(["--volume", str(source) + ":/workspace/" + relative.as_posix() + ":ro"])
    return result


def prepare_fencing_snapshot(java, compose, bootstrap, environment, private):
    run(compose + ["stop", "coordinator"], environment, "fencing-pause", timeout=180)
    run(java + ["append", bootstrap, str(DIRECTORY), "2"], environment, "fencing-pending-data")
    deployment = json.loads((DIRECTORY / "deployment-initial.json").read_text())["id"]
    program = """import asyncio,json,sys
from sqlalchemy import select
from app.db.session import AsyncSessionLocal
from app.models.wayang import WayangDeployment
from app.services.wayang_service import Journal,driver,strict_json
async def main():
    async with AsyncSessionLocal() as session:
        row=(await session.execute(select(WayangDeployment).where(WayangDeployment.id==sys.argv[1]))).scalar_one()
        cycle=await Journal(session).cycle(row)
        if row.state!='idle' or cycle.state!='completed': raise RuntimeError('The snapshot requires a verified idle cycle.')
        _,value=await driver('/v1/next',json.dumps({'plan':strict_json(cycle.plan_text),'result':cycle.result}))
        if value['state']!='ready': raise RuntimeError('The snapshot requires a real successor cut.')
        print(json.dumps(value['plan'],separators=(',',':')))
asyncio.run(main())
"""
    plan = json.loads(run(compose + ["exec", "-T", "backend", "/opt/venv/bin/python", "-c", program, deployment],
                          environment, "fencing-successor", timeout=240))
    private_file(private / "next-plan.json", json.dumps(plan) + "\n")
    units = [item for item in plan["assignments"] if item["node"] == "worker-1"]
    if len(units) != 1:
        raise RuntimeError("the stale snapshot must contain exactly worker-1's complete topic")
    text = units[0]["requestText"]
    digest = hashlib.sha256(text.encode()).hexdigest()
    prepared = controller_probe(java, compose, environment, "prepare", "http://runner-1:8090", text)
    if (prepared.get("state") != "prepared" or prepared.get("requestHash") != digest
            or decimal_counter(prepared.get("preparedOutputRecords")) < 1):
        raise RuntimeError("the stale worker snapshot requires actual prepared output")
    source = private / "state/worker-1"
    snapshot = private / "old-worker-1"
    shutil.copytree(source, snapshot)
    snapshot.chmod(0o700)
    candidate = json.loads((snapshot / "increments/checkpoint.json").read_text())["payload"]["candidate"]
    if (candidate["phase"] != "prepared" or candidate["requestText"] != text
            or not candidate["prepared"]["records"] or not candidate.get("publicationGrant")):
        raise RuntimeError("the saved worker snapshot lacks its immutable captured publication grant")
    aborted = controller_probe(java, compose, environment, "abort", "http://runner-1:8090", digest)
    if aborted.get("state") != "cancelled":
        raise RuntimeError("the live snapshot preparation was not cancelled before fencing")
    return digest, prepared["publicationEpoch"]


def reconcile_browser_switch(compose, browser, environment, deployment):
    program = """import asyncio,sys
from sqlalchemy import select,text
from app.db.session import AsyncSessionLocal,async_engine
from app.models.wayang import WayangDeployment
from app.services.wayang_service import Journal,pending_switch,reconcile_once
from app.wayang_worker import LOCK_ID
async def main():
    async with async_engine.connect() as guard:
        if not await guard.scalar(text('SELECT pg_try_advisory_lock(:key)'),{'key':LOCK_ID}):
            raise RuntimeError('The normal coordinator must be stopped for the fencing snapshot.')
        await guard.commit()
        try:
            for attempt in range(3000):
                async with AsyncSessionLocal() as session:
                    row=(await session.execute(select(WayangDeployment).where(WayangDeployment.id==sys.argv[1]))).scalar_one()
                    if pending_switch(row.publication):
                        await reconcile_once(Journal(session))
                        if row.publication.operation.state=='active':
                            print('The persisted publication switch is active.')
                            return
                await asyncio.sleep(0.2)
            raise RuntimeError('No publication switch reached its durable active state.')
        finally:
            await guard.execute(text('SELECT pg_advisory_unlock(:key)'),{'key':LOCK_ID})
            await guard.commit()
asyncio.run(main())
"""
    with ThreadPoolExecutor(max_workers=2) as executor:
        controller = executor.submit(run, compose + ["exec", "-T", "backend", "/opt/venv/bin/python", "-c", program, deployment],
                                     environment, "fencing-switch-once", timeout=660)
        browser_run = executor.submit(run, browser + ["--phase", "fence-resume", "--blocks", "1"],
                                      environment, "browser-fence-resume", timeout=660)
        for future in (browser_run, controller):
            future.result()


def deny_stale_worker(java, compose, environment, private, request_hash, old_epoch):
    current = json.loads((DIRECTORY / "deployment-fence-resume.json").read_text())["publicationEpoch"]
    if decimal_counter(current) <= decimal_counter(old_epoch):
        raise RuntimeError("the stale candidate epoch was not revoked")
    name = PROJECT + "-old-worker"
    restored = private / "restored-worker-1"
    shutil.copytree(private / "old-worker-1", restored)
    restored.chmod(0o700)
    owned = False
    try:
        run(["docker", "run", "--rm", "--detach", "--name", name, "--network", PROJECT + "_worker_net",
             "--label", "com.docker.compose.project=" + PROJECT, "--label", "com.docker.compose.service=old-worker",
             "--user", environment["WAYANG_UID"] + ":" + environment["WAYANG_GID"], "--read-only", "--cap-drop", "ALL",
             "--security-opt", "no-new-privileges:true", "--volume", str(ROOT / "target/wayang-rust-linux") + ":/runner:ro",
             "--volume", str(restored) + ":/state", container_image("x-runner"),
             "/runner/release/tsx-runner", "serve", "--listen", "0.0.0.0:8090", "--node", "worker-1", "--site", "compose",
             "--bootstrap", "kafscale-read:" + environment["KAFSCALE_BROKER_PORT"], "--state", "/state",
             "--publisher", "http://wayang-publication:8096", "--controller", "172.29.74.10"], environment, "old-worker-start", timeout=180)
        owned = True
        deadline = time.monotonic() + 60
        while True:
            try:
                status = controller_probe(java, compose, environment, "activate", "http://" + name + ":8090", request_hash)
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        if (status.get("state") != "failed" or status.get("activationRetryable") is not True
                or status.get("publicationEpoch") != old_epoch
                or status.get("error") != "publication authority refused the captured grant or request"):
            raise RuntimeError("the restored old candidate was not rejected at the publication boundary")
        (DIRECTORY / "fencing-clone.json").write_text(json.dumps(status, indent=2) + "\n")
        repository = Path(environment["WAYANG_M2_REPOSITORY"]).resolve()
        classpath = container_classpath(java[3], repository)
        run(["docker", "run", "--rm", "--network", PROJECT + "_worker_net", "--add-host", "host.docker.internal:host-gateway",
             "--user", environment["WAYANG_UID"] + ":" + environment["WAYANG_GID"], "--read-only", "--cap-drop", "ALL",
             "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=64m,mode=1777",
             "--security-opt", "no-new-privileges:true", *denial_classpath_volumes(java[3], repository),
             "--volume", str(private / "old-worker-1") + ":/probe:ro",
             "--volume", str(DIRECTORY) + ":/reports", container_image("wayang"), "java", "-cp", classpath, DENIAL_PROBE, "deny",
             "kafscale-read:" + environment["KAFSCALE_BROKER_PORT"], "http://wayang-publication:8096",
             "/probe/increments/checkpoint.json", current, "/reports/fencing-denials.json"], environment, "fencing-denials", timeout=240)
    finally:
        if owned:
            run(["docker", "rm", "--force", name], environment, "old-worker-cleanup", timeout=60)


def verify_fencing(java, browser, compose, bootstrap, environment, private):
    snapshot = None
    for phase, blocks in FENCING_PHASES:
        if phase == "fence-resume":
            snapshot = prepare_fencing_snapshot(java, compose, bootstrap, environment, private)
            deployment = json.loads((DIRECTORY / "deployment-initial.json").read_text())["id"]
            reconcile_browser_switch(compose, browser, environment, deployment)
            deny_stale_worker(java, compose, environment, private, *snapshot)
        else:
            if phase == "resume-data":
                run(compose + ["start", "coordinator"], environment, "fencing-resume-coordinator", timeout=180)
            elif phase == "runtime-restart":
                resume_with_backlog(java, bootstrap, compose, environment, blocks)
            elif phase == "changed-data":
                run(java + ["append", bootstrap, str(DIRECTORY), str(blocks)], environment, "fencing-changed-data")
            run(browser + ["--phase", phase, "--blocks", str(blocks)], environment, f"browser-{phase}", timeout=900)
        replacement = phase in ("changed-dag", "changed-data", "stopped")
        source = DIRECTORY / ("submitted-dag-replacement.txt" if replacement else "submitted-dag.txt")
        run(java + ["verify", bootstrap, str(DIRECTORY), str(DIRECTORY / f"deployment-{phase}.json"), str(source), str(blocks)]
            + ([REPLACEMENT_OUTPUT] if replacement else []), environment, f"parity-{phase}", timeout=240)
        shutil.copyfile(DIRECTORY / "verified.json", DIRECTORY / f"verified-{phase}.json")
    return verify_fencing_views()


def main():
    parser = argparse.ArgumentParser(description="Verify an actual UI graph through Wayang and three whole-topic Rust workers.")
    parser.add_argument("--skip-build", action="store_true", help="reuse matching Java, Rust and frontend build artifacts")
    parser.add_argument("--keep", action="store_true", help="retain this isolated project and its private state after verification")
    parser.add_argument("--profile", choices=["bounded", "continuous", "fencing"], default="bounded",
                        help="verify bounded replay, continuous recovery or fenced deployment replacement")
    parser.add_argument("--node", default=shutil.which("node"), help="Node 24.22.2 executable")
    parser.add_argument("--playwright-module", default="playwright", help="Playwright 1.62.1 package name or absolute module directory")
    args = parser.parse_args()
    if os.getuid() == 0:
        raise RuntimeError("run this local verification as an unprivileged user")
    broker_port = port_number(os.environ.get("KAFSCALE_BROKER_PORT", "39093"), "KAFSCALE_BROKER_PORT")
    ui_port = port_number(os.environ.get("WAYANG_UI_PORT", "18081"), "WAYANG_UI_PORT")
    if ui_port == broker_port:
        raise RuntimeError("frontend and broker require distinct loopback ports")
    if not args.node or not Path(args.node).is_file():
        raise RuntimeError("Node 24.22.2 is required; supply --node")
    for command in ["docker", "mvn", "npm"]:
        if not shutil.which(command):
            raise RuntimeError(f"required executable is missing: {command}")
    if (ROOT / "target").is_symlink() or DIRECTORY.is_symlink():
        raise RuntimeError("fixture directories must not be symbolic links")
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ)
    environment["PATH"] = str(Path(args.node).parent) + os.pathsep + environment.get("PATH", "")
    if run([args.node, "--version"], environment, "node-version").strip() != "v24.22.2":
        raise RuntimeError("Node 24.22.2 is required")
    empty_project(environment)
    free_port(broker_port)
    free_port(ui_port)
    lock_path = ROOT / "target/.wayang-rust-compose.lock"
    if lock_path.is_symlink():
        raise RuntimeError("the fixture lock must not be a symbolic link")
    with lock_path.open("a+b") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("another local Wayang Compose verification owns the fixture") from error
        clear_run_artifacts()
        execute(args, environment, broker_port, ui_port)


def execute(args, environment, broker_port, ui_port):
    private_root = ROOT / "target/wayang-rust-private"
    if private_root.is_symlink():
        raise RuntimeError("the private fixture root must not be a symbolic link")
    private_root.mkdir(mode=0o700, exist_ok=True)
    private = private_root / secrets.token_hex(12)
    private.mkdir(mode=0o700)
    values = {"POSTGRES_PASSWORD": secrets.token_hex(24), "SECRET_KEY": secrets.token_hex(32), "DAG_RUN_TOKEN": secrets.token_hex(32),
              "FIRST_SUPERUSER_EMAIL": "bounded-rust@example.com", "FIRST_SUPERUSER_PASSWORD": secrets.token_urlsafe(32)}
    SECRET_VALUES.extend(value for key, value in values.items() if key != "FIRST_SUPERUSER_EMAIL")
    environment.update(values)
    repository = (Path.home() / ".m2/repository").resolve()
    environment.update(WAYANG_UID=str(os.getuid()), WAYANG_GID=str(os.getgid()), WAYANG_PRIVATE_DIR=str(private),
                       WAYANG_M2_REPOSITORY=str(repository), KAFSCALE_BROKER_PORT=str(broker_port), WAYANG_UI_PORT=str(ui_port))
    private_file(private / "generated.env", "".join(f"{key}={value}\n" for key, value in values.items()))
    private_file(private / "credentials.json", json.dumps({"email": values["FIRST_SUPERUSER_EMAIL"],
                                                          "password": values["FIRST_SUPERUSER_PASSWORD"]}))
    private_file(private / "hosts", "127.0.0.1 localhost kafscale-write\n")
    private_file(private / "run-token", values["DAG_RUN_TOKEN"] + "\n")
    private_file(private / "publication-acl.json", json.dumps({"enabled": True, "default_policy": "deny", "principals": [
        {"name": "172.29.73.10", "allow": [{"action": "fetch", "resource": "topic", "name": "*"}]},
        {"name": "172.29.73.11", "allow": [{"action": "*", "resource": "*", "name": "*"}]}]}) + "\n")
    (private / "transport").mkdir(mode=0o700)
    (private / "transport/etcd").mkdir(mode=0o700)
    (private / "publication").mkdir(mode=0o700)
    for number in range(1, 4):
        (private / f"state/worker-{number}").mkdir(parents=True, mode=0o700)
    compose = COMPOSE + ["--env-file", str(private / "generated.env")]
    owned = False
    failure = None
    try:
        run(compose + ["--profile", "build", "config", "--quiet"], environment, "compose-config", timeout=60)
        if args.skip_build:
            build_kafscale_proxy.verify()
        else:
            run([sys.executable, str(ROOT / "tools/build_kafscale_proxy.py")], environment, "proxy-build", timeout=1800)
        stamp = DIRECTORY / "build-source.sha256"
        signature = source_signature()
        if args.skip_build and (not stamp.is_file() or stamp.read_text().strip() != signature):
            raise RuntimeError("build artifacts do not match current sources; omit --skip-build")
        fixtures = {"bounded": FIXTURE, "continuous": CONTINUOUS_FIXTURE, "fencing": FENCING_FIXTURE}
        java = java_setup(environment, private, repository, args.skip_build, fixtures[args.profile])
        if not args.skip_build:
            frontend_environment = dict(environment, VITE_API_BASE_URL="")
            run([shutil.which("npm"), "ci", "--no-audit", "--no-fund"], frontend_environment, "frontend-install",
                cwd=ROOT / "opentsx-saas-frontend")
            run([shutil.which("npm"), "run", "build"], frontend_environment, "frontend-build",
                cwd=ROOT / "opentsx-saas-frontend")
            (ROOT / "target/wayang-rust-linux").mkdir(exist_ok=True)
            owned = True
            run(compose + ["--profile", "build", "run", "--rm", "--no-deps", "runner-build"],
                environment, "linux-build", timeout=1800)
            verify_build_sources(signature)
            stamp.write_text(signature + "\n")
        if not BINARY.is_file() or not (ROOT / "opentsx-saas-frontend/dist/index.html").is_file():
            raise RuntimeError("runtime artifacts are missing; omit --skip-build")
        owned = True
        run(compose + ["--profile", "build", "run", "--rm", "--no-deps", "python-deps"],
            environment, "backend-install", timeout=1200)
        verify_build_sources(signature)
        run(compose + ["up", "--detach", *DATA_SERVICES], environment, "data-start", timeout=180)
        bootstrap = f"127.0.0.1:{broker_port}"
        run(java[:-1] + [FIXTURE, "ready", bootstrap], environment, "broker-ready", timeout=210)
        run(java + ["prepare", bootstrap, str(DIRECTORY)], environment, "fixture-prepare")
        run(compose + ["up", "--detach", "runner-1", "runner-2", "runner-3", "wayang", "backend", "coordinator", "frontend"],
            environment, "runtime-start", timeout=240)
        base = f"http://127.0.0.1:{ui_port}"
        wait_http(base + "/health")
        wait_driver(compose, environment)
        browser = [args.node, str(ROOT / "tools/wayang_rust_browser.mjs"), "--base", base,
                   "--fixture", str(DIRECTORY / "fixture.json"), "--credentials", str(private / "credentials.json"),
                   "--output", str(DIRECTORY), "--playwright-module", args.playwright_module, "--profile", args.profile]
        if args.profile == "fencing":
            result = verify_fencing(java, browser, compose, bootstrap, environment, private)
        elif args.profile == "continuous":
            result = verify_continuous(java, browser, compose, bootstrap, environment)
        else:
            for phase in ["initial", "retry", "restart"]:
                if phase == "restart":
                    restart_runtime(compose, environment)
                run(browser + ["--phase", phase], environment, f"browser-{phase}", timeout=900)
                run(java + ["verify", bootstrap, str(DIRECTORY), str(DIRECTORY / f"deployment-{phase}.json"),
                            str(DIRECTORY / "submitted-dag.txt")], environment, f"parity-{phase}", timeout=240)
                shutil.copyfile(DIRECTORY / "verified.json", DIRECTORY / f"verified-{phase}.json")
            result = verify_views()
        (DIRECTORY / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, sort_keys=True), flush=True)
    except (OSError, RuntimeError, ValueError) as error:
        failure = error
    finally:
        if owned:
            try:
                run(compose + ["logs", "--no-color", "--tail", "200", "backend", "coordinator", "wayang", "runner-1", "runner-2", "runner-3"],
                    environment, "runtime", timeout=60)
            except RuntimeError as error:
                failure = failure or error
        if owned and not args.keep:
            try:
                run(compose + ["--profile", "build", "down", "--remove-orphans"], environment, "compose-cleanup", timeout=180)
                volumes = run(["docker", "volume", "ls", "--filter", f"label=com.docker.compose.project={PROJECT}",
                               "--format", "{{.Name}}"], environment, "cleanup-volumes", timeout=60)
                if f"{PROJECT}_postgres-data" in volumes.splitlines():
                    run(["docker", "volume", "rm", f"{PROJECT}_postgres-data"], environment, "database-cleanup", timeout=60)
            except RuntimeError as error:
                failure = RuntimeError(f"{failure}; cleanup failed: {error}") if failure else error
            else:
                shutil.rmtree(private)
        elif not owned:
            shutil.rmtree(private)
    if failure:
        raise failure
    print(f"Verified UI, Wayang and three Rust workers; sanitized evidence: {DIRECTORY}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as error:
        print(sanitize(str(error)), file=sys.stderr)
        sys.exit(1)
