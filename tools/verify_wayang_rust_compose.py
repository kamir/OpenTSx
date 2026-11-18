import argparse
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
CONTINUOUS_PHASES = [("initial", 1), ("retry", 1), ("new-data", 2), ("restart", 4),
                     ("after-restart", 5), ("worker-unavailable", 5), ("worker-restart", 6),
                     ("after-worker-restart", 7), ("stopped", 7)]
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
                 "tools/verify_wayang_rust_compose.py", "tools/build_kafscale_proxy.py", "tools/kafscale_proxy_cluster.go",
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
    names = {"result.json", "fixture.json", "submitted-dag.txt", "verified.json", "worker-outage.json"}
    for phase in {"initial", "retry", "restart", *(name for name, _ in CONTINUOUS_PHASES)}:
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
    probe = "import urllib.request; urllib.request.urlopen('http://wayang:8095/v1/capabilities',timeout=5).read()"
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
    live = {"backend", "coordinator", "frontend", "kafscale-proxy", "postgres", "runner-1", "runner-3", "wayang"}
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
    arguments = ["-cp", driver_classpath, "org.opentsx.wayang.rust.RustRolloutServer", "--listen", "0.0.0.0:8095",
                 "--bootstrap", f"kafscale-proxy:{environment['KAFSCALE_BROKER_PORT']}", "--catalog", "/workspace/systems/processors"]
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
    if outage != {"worker": "worker-2", "throughBlocks": 6, "liveServices": ["backend", "coordinator", "frontend",
            "kafscale-proxy", "postgres", "runner-1", "runner-3", "wayang"]}:
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


def main():
    parser = argparse.ArgumentParser(description="Verify an actual UI graph through Wayang and three whole-topic Rust workers.")
    parser.add_argument("--skip-build", action="store_true", help="reuse matching Java, Rust and frontend build artifacts")
    parser.add_argument("--keep", action="store_true", help="retain this isolated project and its private state after verification")
    parser.add_argument("--profile", choices=["bounded", "continuous"], default="bounded",
                        help="verify bounded replay or continuous input and restart")
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
    values = {"POSTGRES_PASSWORD": secrets.token_hex(24), "SECRET_KEY": secrets.token_hex(32),
              "FIRST_SUPERUSER_EMAIL": "bounded-rust@example.com", "FIRST_SUPERUSER_PASSWORD": secrets.token_urlsafe(32)}
    SECRET_VALUES.extend(value for key, value in values.items() if key != "FIRST_SUPERUSER_EMAIL")
    environment.update(values)
    repository = (Path.home() / ".m2/repository").resolve()
    environment.update(WAYANG_UID=str(os.getuid()), WAYANG_GID=str(os.getgid()), WAYANG_PRIVATE_DIR=str(private),
                       WAYANG_M2_REPOSITORY=str(repository), KAFSCALE_BROKER_PORT=str(broker_port), WAYANG_UI_PORT=str(ui_port))
    private_file(private / "generated.env", "".join(f"{key}={value}\n" for key, value in values.items()))
    private_file(private / "credentials.json", json.dumps({"email": values["FIRST_SUPERUSER_EMAIL"],
                                                          "password": values["FIRST_SUPERUSER_PASSWORD"]}))
    private_file(private / "hosts", "127.0.0.1 localhost kafscale-proxy\n")
    (private / "transport").mkdir(mode=0o700)
    for number in range(1, 4):
        (private / f"state/worker-{number}").mkdir(parents=True)
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
        java = java_setup(environment, private, repository, args.skip_build,
                          CONTINUOUS_FIXTURE if args.profile == "continuous" else FIXTURE)
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
        run(compose + ["up", "--detach", "postgres", "kafscale-proxy"], environment, "data-start", timeout=180)
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
        if args.profile == "continuous":
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
