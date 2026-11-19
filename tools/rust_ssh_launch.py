import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile

import rust_b1_acceptance as common


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_CASES = ("stopped", "refused", "runtime-failure", "command-override", "extra-arguments",
                  "wrong-principal", "untrusted-certificate", "host-key-mismatch")
EXIT_CODES = dict(zip(REQUIRED_CASES, (0, 2, 3, 0, 0, 255, 255, 255)))
ROW_FIELDS = {"name", "exitCode", "serverExitCode", "runtimeUid", "stdoutSha256", "stderrSha256", "message"}
REPORT_FIELDS = {"schema", "runnerVersion", "artifactDigest", "runtimeUid", "hostVerification",
                 "certificateForcedCommand", "cases", "physicalArmv7Qualified"}
REFUSAL = b"plan.signature: plan signature is invalid\n"
RUNTIME_FAILURE = b"checkpoint checksum mismatch\n"
MESSAGES = {"stopped": "stopped", "refused": REFUSAL.decode("utf-8"),
            "runtime-failure": RUNTIME_FAILURE.decode("utf-8"), "command-override": "stopped",
            "extra-arguments": "stopped", "wrong-principal": "certificate rejected",
            "untrusted-certificate": "certificate rejected", "host-key-mismatch": "host key rejected"}


def positive_uid(value):
    if type(value) is not int or value <= 0:
        raise RuntimeError("the SSH runtime must use an unprivileged numeric user identity")
    return value


def server_exit_allowed(name, value):
    if type(value) is not int:
        return False
    if value in (0, 255):
        return True
    return value == 5 and name in ("wrong-principal", "untrusted-certificate")


def validate_outcome(value):
    common.require_fields(value, {"schema", "job", "planVersion", "records", "outputRecords", "positions"},
                          "SSH stopped outcome")
    if (value["schema"] != "opentsx.rust-run-outcome.v1" or not isinstance(value["job"], str)
            or re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", value["job"]) is None
            or value["positions"] != [{"partition": "0", "next": "0"}, {"partition": "1", "next": "0"}]):
        raise RuntimeError("SSH stopped identity or internal positions differ")
    for field, expected in (("planVersion", 1), ("records", 0), ("outputRecords", 0)):
        common.require_int(value[field], expected, f"SSH stopped {field}")


def check_case(name, process, server_exit, expected_uid, runtime_uid, outcome):
    positive_uid(expected_uid)
    if name not in EXIT_CODES or process.returncode != EXIT_CODES[name]:
        raise RuntimeError(f"SSH case {name} returned the wrong remote exit code")
    if not server_exit_allowed(name, server_exit):
        raise RuntimeError(f"SSH case {name} had an unexpected server exit {server_exit!r}")
    stdout, stderr = process.stdout, process.stderr
    if not isinstance(stdout, bytes) or not isinstance(stderr, bytes) or max(len(stdout), len(stderr)) > 65_536:
        raise RuntimeError(f"SSH case {name} output is invalid or too large")
    code = EXIT_CODES[name]
    if code == 255:
        if runtime_uid is not None or stdout:
            raise RuntimeError(f"SSH rejection {name} executed a runtime or wrote stdout")
        expected = b"Host key verification failed" if name == "host-key-mismatch" else b"Permission denied (publickey"
        if expected not in stderr:
            raise RuntimeError(f"SSH rejection {name} has the wrong diagnostic")
    else:
        if type(runtime_uid) is not int or runtime_uid != expected_uid:
            raise RuntimeError(f"SSH case {name} did not execute as the admitted unprivileged user")
        if code == 0:
            if stderr:
                raise RuntimeError(f"SSH case {name} did not return its exact saved stopped outcome")
            parsed = common.parse_json(stdout)
            validate_outcome(parsed)
            if parsed != outcome:
                raise RuntimeError(f"SSH case {name} did not return its exact saved stopped outcome")
        else:
            expected = REFUSAL if code == 2 else RUNTIME_FAILURE
            if stdout or stderr != expected:
                raise RuntimeError(f"SSH case {name} stdout or stderr differs")
    return {"name": name, "exitCode": code, "serverExitCode": server_exit, "runtimeUid": runtime_uid,
            "stdoutSha256": hashlib.sha256(stdout).hexdigest(),
            "stderrSha256": hashlib.sha256(stderr).hexdigest(), "message": MESSAGES[name]}


def validate_report(value):
    common.require_fields(value, REPORT_FIELDS, "SSH report")
    if (value["schema"] != "opentsx.rust-ssh-acceptance.v1"
            or not isinstance(value["runnerVersion"], str)
            or re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value["runnerVersion"]) is None
            or not isinstance(value["artifactDigest"], str)
            or re.fullmatch(r"sha256:[0-9a-f]{64}", value["artifactDigest"]) is None
            or value["hostVerification"] != "strict" or value["certificateForcedCommand"] is not True
            or value["physicalArmv7Qualified"] is not False):
        raise RuntimeError("SSH report identity or security qualification differs")
    uid = positive_uid(value["runtimeUid"])
    rows = value["cases"]
    if not isinstance(rows, list) or len(rows) != len(REQUIRED_CASES):
        raise RuntimeError("SSH report must contain all eight executed cases")
    for name, row in zip(REQUIRED_CASES, rows):
        common.require_fields(row, ROW_FIELDS, "SSH case")
        if (row["name"] != name or type(row["exitCode"]) is not int or row["exitCode"] != EXIT_CODES[name]
                or not server_exit_allowed(name, row["serverExitCode"])
                or (row["runtimeUid"] is not None if EXIT_CODES[name] == 255
                    else type(row["runtimeUid"]) is not int or row["runtimeUid"] != uid)
                or row["message"] != MESSAGES[name]):
            raise RuntimeError(f"SSH report case {name} differs")
        for field in ("stdoutSha256", "stderrSha256"):
            if not isinstance(row[field], str) or re.fullmatch(r"[0-9a-f]{64}", row[field]) is None:
                raise RuntimeError(f"SSH report case {name} omitted its output digest")


def ssh_command(ssh, port, user, key, certificate, known_hosts, command):
    if (type(port) is not int or not 1 <= port <= 65_535 or not isinstance(user, str)
            or user == "root" or re.fullmatch(r"[a-z_][a-z0-9_-]{0,62}", user) is None):
        raise RuntimeError("SSH endpoint or runtime account is invalid")
    return [str(ssh), "-F", "/dev/null", "-T", "-p", str(port), "-i", str(key),
            "-o", f"CertificateFile={certificate}", "-o", f"UserKnownHostsFile={known_hosts}",
            "-o", "GlobalKnownHostsFile=/dev/null", "-o", "StrictHostKeyChecking=yes",
            "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=none",
            "-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no",
            "-o", "PreferredAuthentications=publickey", "-o", "ControlMaster=no", "-o", "ControlPath=none",
            "-o", "ConnectionAttempts=1", "-o", "ConnectTimeout=10", "-o", "LogLevel=ERROR",
            f"{user}@127.0.0.1", command]


def write_launcher(path, runner, config, uid):
    text = ("#!/bin/sh\nset -euC\numask 077\n"
            f"/usr/bin/id -u > {shlex.quote(str(uid))}\n"
            f"exec {shlex.quote(str(runner))} run --plan - --config {shlex.quote(str(config))}\n")
    path.write_text(text, encoding="utf-8")
    path.chmod(0o700)


def executable(value):
    found = shutil.which(str(value))
    if found is None:
        raise RuntimeError(f"required SSH acceptance executable is unavailable: {value}")
    return common.executable(Path(found).resolve())


def snapshot_executable(source, destination):
    source = executable(source)
    common.safe_path(destination)
    if destination.exists() or destination.is_symlink():
        raise RuntimeError("SSH executable snapshots require new private files")
    before = common.digest(source)
    try:
        with source.open("rb") as reader, destination.open("xb") as writer:
            shutil.copyfileobj(reader, writer, length=1_048_576)
            writer.flush()
            os.fsync(writer.fileno())
        destination.chmod(0o500)
        copied = common.digest(destination)
        after = common.digest(source)
        if before != copied or copied != after:
            raise RuntimeError("the SSH executable changed while its snapshot was copied")
    except (OSError, RuntimeError):
        destination.unlink(missing_ok=True)
        raise
    return {"source": str(source), "snapshot": destination.name,
            "sourceDigestBefore": "sha256:" + before, "snapshotDigest": "sha256:" + copied,
            "sourceDigestAfter": "sha256:" + after}


def checked(command, label, timeout=30):
    try:
        result = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(f"{label} could not complete") from error
    if result.returncode != 0:
        raise RuntimeError(f"{label} exited {result.returncode}")
    return result


def private_write(path, data):
    with path.open("xb") as stream:
        path.chmod(0o600)
        stream.write(data)


def read_uid(path):
    common.safe_path(path)
    if not path.exists():
        return None
    if not path.is_file():
        raise RuntimeError("SSH runtime identity must be a regular file")
    with path.open("rb") as stream:
        data = stream.read(33)
    if re.fullmatch(rb"[0-9]{1,20}\n", data) is None:
        raise RuntimeError("SSH runtime identity must contain one numeric user ID")
    return int(data)


def validate_manifest(value, artifact, directory):
    common.require_fields(value, {"schema", "runnerVersion", "artifactDigest", "config", "plan", "refusedPlan",
                                  "checkpoint", "intent", "outcome"}, "SSH fixture")
    if value["schema"] != "opentsx.rust-ssh-fixture.v1" or value["artifactDigest"] != artifact:
        raise RuntimeError("SSH fixture does not bind the actual runner executable")
    for field, name in (("config", "run.json"), ("plan", "plan.jws"), ("refusedPlan", "refused.jws"),
                        ("checkpoint", "checkpoint.json"), ("intent", ".checkpoint.json.run-intent")):
        if value[field] != name:
            raise RuntimeError(f"SSH fixture {field} path differs")
        common.safe_path(directory / name)
        if field != "checkpoint" and not (directory / name).is_file():
            raise RuntimeError(f"SSH fixture {field} is missing")
    validate_outcome(value["outcome"])


def source_state():
    value = common.source_state()
    for name in ("tools/rust_ssh_launch.py", "tools/test_rust_ssh_launch.py", "rust/tsx-runner/tests/ssh_launch.rs"):
        value["files"][name] = common.digest(ROOT / name)
    value["files"] = dict(sorted(value["files"].items()))
    return value


def terminate(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait(timeout=5)
        return
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def remote(listener, client_command, sshd, config, payload, log):
    client = server = None
    connection = None
    try:
        client = subprocess.Popen(client_command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, start_new_session=True)
        connection, address = listener.accept()
        if address[0] != "127.0.0.1":
            raise RuntimeError("SSH acceptance received a non-loopback connection")
        server = subprocess.Popen([str(sshd), "-i", "-e", "-f", str(config)], stdin=connection,
                                  stdout=connection, stderr=log, start_new_session=True)
        connection.close()
        connection = None
        stdout, stderr = client.communicate(payload, timeout=30)
        server_exit = server.wait(timeout=10)
        return subprocess.CompletedProcess(client_command, client.returncode, stdout, stderr), server_exit
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("the isolated SSH session failed to complete") from error
    finally:
        if connection is not None:
            connection.close()
        terminate(client)
        terminate(server)


def server_config(root, user):
    lines = [f"HostKey {root / 'host'}", f"TrustedUserCAKeys {root / 'ca.pub'}",
             f"AllowUsers {user}", "AuthorizedKeysFile none", "AuthorizedPrincipalsFile none",
             "UsePAM no", "AuthenticationMethods publickey", "PubkeyAuthentication yes",
             "PasswordAuthentication no", "KbdInteractiveAuthentication no", "PermitRootLogin no",
             "StrictModes yes", "DisableForwarding yes", "PermitTTY no", "PermitUserRC no",
             "PermitUserEnvironment no", "UseDNS no", "PrintMotd no", "PrintLastLog no", "LogLevel VERBOSE"]
    path = root / "sshd_config"
    private_write(path, ("\n".join(lines) + "\n").encode())
    return path


def execute(runner, fixture_binary, output, sshd="sshd", ssh="ssh", ssh_keygen="ssh-keygen"):
    uid = positive_uid(os.geteuid())
    if os.getuid() != uid:
        raise RuntimeError("SSH acceptance must not run with a set-user-ID identity")
    user = pwd.getpwuid(uid).pw_name
    runner, fixture_binary, sshd, ssh, ssh_keygen = map(executable, (runner, fixture_binary, sshd, ssh, ssh_keygen))
    output = common.safe_path(Path(output).resolve())
    output.mkdir(parents=True, mode=0o700)
    before = source_state()
    ssh_version = subprocess.run([str(ssh), "-V"], check=True, capture_output=True).stderr.decode().strip()
    with tempfile.TemporaryDirectory(prefix="opentsx-b3-ssh-") as name:
        root = Path(name).resolve()
        if root.is_relative_to(ROOT):
            raise RuntimeError("temporary SSH credentials must remain outside the repository")
        snapshots = {"runner": snapshot_executable(runner, root / "runner"),
                     "fixture": snapshot_executable(fixture_binary, root / "fixture-builder")}
        runner, fixture_binary = root / "runner", root / "fixture-builder"
        artifact = snapshots["runner"]["snapshotDigest"]
        version = checked([str(runner), "--version"], "runner version").stdout.decode("utf-8").strip()
        fixture = root / "runtime"
        checked([str(fixture_binary), str(runner), str(fixture)], "signed SSH fixture", timeout=60)
        manifest = common.read_json(fixture / "fixture.json")
        validate_manifest(manifest, artifact, fixture)
        if version != f"tsx-runner {manifest['runnerVersion']}":
            raise RuntimeError("SSH fixture and actual executable versions differ")
        launcher, runtime_uid = root / "launch", root / "runtime.uid"
        write_launcher(launcher, runner, fixture / manifest["config"], runtime_uid)
        for key in ("host", "ca", "untrusted-ca", "valid", "wrong", "untrusted"):
            checked([str(ssh_keygen), "-q", "-t", "ed25519", "-N", "", "-C", "opentsx-b3-fixture",
                     "-f", str(root / key)], "ephemeral SSH key generation")
        for key, ca, principal in (("valid", "ca", user), ("wrong", "ca", "unrelated-runtime"),
                                   ("untrusted", "untrusted-ca", user)):
            checked([str(ssh_keygen), "-q", "-s", str(root / ca), "-I", f"b3-{key}", "-n", principal,
                     "-V", "-5m:+30m", "-O", "clear", "-O", f"force-command={launcher}",
                     "-O", "source-address=127.0.0.1/32", str(root / f"{key}.pub")],
                    "restricted SSH user certificate signing")
        config = server_config(root, user)
        checked([str(sshd), "-t", "-f", str(config)], "isolated SSH server configuration")
        effective = checked([str(sshd), "-T", "-f", str(config)], "effective SSH server policy").stdout.decode()
        for line in ("forcecommand none", "authorizedkeysfile none", "authorizedprincipalsfile none",
                     "usepam no", "passwordauthentication no", "kbdinteractiveauthentication no",
                     "disableforwarding yes", "permitrootlogin no", "permituserrc no"):
            if line not in effective.splitlines():
                raise RuntimeError("effective SSH server policy differs from the isolated contract")
        plan = (fixture / manifest["plan"]).read_bytes()
        refused = (fixture / manifest["refusedPlan"]).read_bytes()
        intent = fixture / manifest["intent"]
        intent_bytes = intent.read_bytes()
        config_bytes = (fixture / manifest["config"]).read_bytes()
        checkpoint = fixture / manifest["checkpoint"]
        marker = root / "command-bypassed"
        cases = []
        with socket.socket() as listener, (output / "sshd.log").open("xb") as log:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(15)
            port = listener.getsockname()[1]
            for filename, public in (("known_hosts", "host.pub"), ("wrong_hosts", "untrusted-ca.pub")):
                key_type, public_key, *_ = (root / public).read_text().split()
                private_write(root / filename, f"[127.0.0.1]:{port} {key_type} {public_key}\n".encode())
            for case in REQUIRED_CASES:
                if runtime_uid.exists():
                    runtime_uid.unlink()
                key = "valid"
                if case == "wrong-principal":
                    key = "wrong"
                elif case == "untrusted-certificate":
                    key = "untrusted"
                known = root / ("wrong_hosts" if case == "host-key-mismatch" else "known_hosts")
                requested = "ignored"
                if case == "command-override":
                    requested = f"printf BYPASS > {shlex.quote(str(marker))}"
                elif case == "extra-arguments":
                    requested = "tsx-runner run --plan /untrusted --config /untrusted extra"
                corrupt = None
                if case == "runtime-failure":
                    corrupt = json.dumps({"schema": "opentsx.local-checkpoint.v1", "checksum": "0" * 64,
                                          "payload": {}}).encode()
                    private_write(checkpoint, corrupt)
                command = ssh_command(ssh, port, user, root / key, root / f"{key}-cert.pub", known, requested)
                process, server_exit = remote(listener, command, sshd, config,
                                              refused if case == "refused" else plan, log)
                ran_as = read_uid(runtime_uid)
                cases.append(check_case(case, process, server_exit, uid, ran_as, manifest["outcome"]))
                if intent.read_bytes() != intent_bytes or (fixture / manifest["config"]).read_bytes() != config_bytes or marker.exists():
                    raise RuntimeError("the fixed SSH launch changed authority or executed the original command")
                if corrupt is not None:
                    if checkpoint.read_bytes() != corrupt:
                        raise RuntimeError("runtime failure rewrote corrupt progress")
                    checkpoint.unlink()
                elif checkpoint.exists():
                    raise RuntimeError("stopped SSH replay or rejection advanced progress")
        report = {"schema": "opentsx.rust-ssh-acceptance.v1", "runnerVersion": manifest["runnerVersion"],
                  "artifactDigest": artifact, "runtimeUid": uid, "hostVerification": "strict",
                  "certificateForcedCommand": True, "cases": cases, "physicalArmv7Qualified": False}
        validate_report(report)
        for record in snapshots.values():
            if "sha256:" + common.digest(root / record["snapshot"]) != record["snapshotDigest"]:
                raise RuntimeError("a pinned SSH executable changed while sessions ran")
        metadata = {"schema": "opentsx.rust-ssh-metadata.v1", "source": before,
                    "fixtureBinaryDigest": "sha256:" + common.digest(fixture_binary), "opensshVersion": ssh_version,
                    "executableSnapshots": snapshots,
                    "runtimeUser": user, "runtimeUid": uid, "serverMode": "inetd", "listenHost": "127.0.0.1",
                    "hostPublicKeySha256": common.digest(root / "host.pub"),
                    "trustedCaPublicKeySha256": common.digest(root / "ca.pub")}
    if before != source_state():
        raise RuntimeError("SSH acceptance source changed while the sessions ran")
    private_write(output / "report.json", (json.dumps(report, indent=2, sort_keys=True) + "\n").encode())
    private_write(output / "metadata.json", (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode())
    return report


def main():
    parser = argparse.ArgumentParser(description="Verify certificate-forced signed Rust launches against isolated OpenSSH.")
    parser.add_argument("--runner", type=Path, required=True)
    parser.add_argument("--fixture-binary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sshd", default="sshd")
    parser.add_argument("--ssh", default="ssh")
    parser.add_argument("--ssh-keygen", default="ssh-keygen")
    arguments = parser.parse_args()
    result = execute(arguments.runner, arguments.fixture_binary, arguments.output,
                     arguments.sshd, arguments.ssh, arguments.ssh_keygen)
    print(json.dumps({"schema": result["schema"], "report": str(arguments.output / "report.json"),
                      "executedCases": len(result["cases"]), "passedCases": len(result["cases"]),
                      "runtimeUid": result["runtimeUid"], "physicalArmv7Qualified": False}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, UnicodeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
