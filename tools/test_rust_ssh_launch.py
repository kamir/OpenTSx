import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def helper():
    spec = importlib.util.spec_from_file_location(
        "rust_ssh_launch", Path(__file__).with_name("rust_ssh_launch.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SshLaunchTest(unittest.TestCase):
    def setUp(self):
        self.module = helper()
        self.uid = 501
        self.outcome = {"schema": "opentsx.rust-run-outcome.v1", "job": "ssh-fixture", "planVersion": 1,
                        "records": 0, "outputRecords": 0,
                        "positions": [{"partition": "0", "next": "0"}, {"partition": "1", "next": "0"}]}

    def process(self, name):
        code = self.module.EXIT_CODES[name]
        stdout = (json.dumps(self.outcome) + "\n").encode() if code == 0 else b""
        if code == 0:
            stderr = b""
        elif code == 2:
            stderr = b"plan.signature: plan signature is invalid\n"
        elif code == 3:
            stderr = b"checkpoint checksum mismatch\n"
        elif name == "host-key-mismatch":
            stderr = b"Host key verification failed.\n"
        else:
            stderr = b"runtime@127.0.0.1: Permission denied (publickey).\n"
        return subprocess.CompletedProcess(["ssh"], code, stdout, stderr)

    def checked(self, name):
        uid = None if self.module.EXIT_CODES[name] == 255 else self.uid
        return self.module.check_case(name, self.process(name), 255, self.uid, uid, self.outcome)

    def report(self):
        return {"schema": "opentsx.rust-ssh-acceptance.v1", "runnerVersion": "0.1.0",
                "artifactDigest": "sha256:" + "a" * 64, "runtimeUid": self.uid,
                "hostVerification": "strict", "certificateForcedCommand": True,
                "cases": [self.checked(name) for name in self.module.REQUIRED_CASES],
                "physicalArmv7Qualified": False}

    def test_all_eight_real_session_outcomes_are_required(self):
        report = self.report()
        self.module.validate_report(report)
        self.assertEqual(8, len(report["cases"]))
        self.assertEqual([0, 2, 3, 0, 0, 255, 255, 255], [r["exitCode"] for r in report["cases"]])

    def test_missing_duplicate_reordered_and_unknown_cases_fail(self):
        report = self.report()
        for cases in (report["cases"][:-1], report["cases"] + [report["cases"][0]],
                      list(reversed(report["cases"])), [{**report["cases"][0], "name": "unknown"},
                                                       *report["cases"][1:]]):
            with self.subTest(cases=cases):
                with self.assertRaises(RuntimeError):
                    self.module.validate_report({**report, "cases": cases})

    def test_wrong_remote_exit_fails_every_case(self):
        for name in self.module.REQUIRED_CASES:
            with self.subTest(name=name):
                process = self.process(name)
                process.returncode = 1
                with self.assertRaises(RuntimeError):
                    self.module.check_case(name, process, 255, self.uid, self.uid, self.outcome)

    def test_refusal_and_runtime_stderr_are_exact_and_stdout_is_empty(self):
        for name in ("refused", "runtime-failure"):
            for stdout, suffix in ((b"extra", b""), (b"", b"extra"), (b"", b"\n")):
                with self.subTest(name=name, stdout=stdout, suffix=suffix):
                    process = self.process(name)
                    process.stdout = stdout
                    process.stderr += suffix
                    with self.assertRaises(RuntimeError):
                        self.module.check_case(name, process, 255, self.uid, self.uid, self.outcome)

    def test_stopped_and_override_commands_must_return_the_actual_saved_outcome(self):
        for name in ("stopped", "command-override", "extra-arguments"):
            for stdout, stderr in ((b"BYPASS", b""), (b"{}", b""),
                                   (json.dumps(self.outcome).encode(), b"warning")):
                with self.subTest(name=name, stdout=stdout):
                    process = subprocess.CompletedProcess(["ssh"], 0, stdout, stderr)
                    with self.assertRaises(RuntimeError):
                        self.module.check_case(name, process, 255, self.uid, self.uid, self.outcome)

    def test_stopped_json_rejects_boolean_versions_and_counters(self):
        for field, value in (("planVersion", True), ("records", False), ("outputRecords", False)):
            with self.subTest(field=field):
                outcome = {**self.outcome, field: value}
                process = subprocess.CompletedProcess(["ssh"], 0, json.dumps(outcome).encode(), b"")
                with self.assertRaises(RuntimeError):
                    self.module.check_case("stopped", process, 255, self.uid, self.uid, self.outcome)

    def test_root_changed_or_missing_runtime_identity_fails(self):
        for uid in (None, 0, self.uid + 1, True):
            with self.subTest(uid=uid):
                with self.assertRaises(RuntimeError):
                    self.module.check_case("stopped", self.process("stopped"), 255, self.uid, uid, self.outcome)

    def test_rejected_authentication_cannot_execute_the_runtime(self):
        for name in ("wrong-principal", "untrusted-certificate", "host-key-mismatch"):
            with self.subTest(name=name):
                with self.assertRaises(RuntimeError):
                    self.module.check_case(name, self.process(name), 255, self.uid, self.uid, self.outcome)

    def test_runtime_identity_is_bounded_numeric_and_cannot_follow_links(self):
        with tempfile.TemporaryDirectory(prefix="opentsx-ssh-identity-") as name:
            path = Path(name).resolve() / "uid"
            self.assertIsNone(self.module.read_uid(path))
            path.write_bytes(b"501\n")
            self.assertEqual(501, self.module.read_uid(path))
            for value in (b"501", b"501\nextra", b"not-an-id\n", b"1" * 100):
                path.write_bytes(value)
                with self.assertRaises(RuntimeError):
                    self.module.read_uid(path)
            path.unlink()
            path.symlink_to(path.parent / "absent")
            with self.assertRaises(RuntimeError):
                self.module.read_uid(path)

    def test_host_rejection_cannot_be_confused_with_authentication_rejection(self):
        for name, stderr in (("host-key-mismatch", b"Permission denied (publickey).\n"),
                             ("wrong-principal", b"Host key verification failed.\n")):
            with self.subTest(name=name):
                process = self.process(name)
                process.stderr = stderr
                with self.assertRaises(RuntimeError):
                    self.module.check_case(name, process, 255, self.uid, None, self.outcome)

    def test_report_rejects_root_weakened_host_trust_and_claimed_hardware_qualification(self):
        for field, value in (("runtimeUid", 0), ("hostVerification", "accept-new"),
                             ("certificateForcedCommand", False), ("physicalArmv7Qualified", True)):
            with self.subTest(field=field):
                report = self.report()
                report[field] = value
                with self.assertRaises(RuntimeError):
                    self.module.validate_report(report)

    def test_report_rejects_changed_case_identity_or_exit(self):
        for field, value in (("runtimeUid", 0), ("exitCode", 1), ("stdoutSha256", "not-a-hash"),
                             ("message", "a different outcome")):
            with self.subTest(field=field):
                report = copy.deepcopy(self.report())
                report["cases"][0][field] = value
                with self.assertRaises(RuntimeError):
                    self.module.validate_report(report)

    def test_manifest_rejects_changed_digest_traversal_and_symlinked_authority(self):
        with tempfile.TemporaryDirectory(prefix="opentsx-ssh-manifest-") as name:
            root = Path(name).resolve()
            for path in ("run.json", "plan.jws", "refused.jws", ".checkpoint.json.run-intent"):
                (root / path).write_text("public fixture placeholder")
            artifact = "sha256:" + "a" * 64
            manifest = {"schema": "opentsx.rust-ssh-fixture.v1", "runnerVersion": "0.1.0",
                        "artifactDigest": artifact, "config": "run.json", "plan": "plan.jws",
                        "refusedPlan": "refused.jws", "checkpoint": "checkpoint.json",
                        "intent": ".checkpoint.json.run-intent", "outcome": self.outcome}
            self.module.validate_manifest(manifest, artifact, root)
            for field, value in (("artifactDigest", "sha256:" + "b" * 64), ("config", "../outside"),
                                 ("plan", "/private/plan.jws"), ("intent", "other-intent")):
                with self.subTest(field=field):
                    with self.assertRaises(RuntimeError):
                        self.module.validate_manifest({**manifest, field: value}, artifact, root)
            (root / "run.json").unlink()
            (root / "run.json").symlink_to(root / "plan.jws")
            with self.assertRaises(RuntimeError):
                self.module.validate_manifest(manifest, artifact, root)

    def test_client_uses_explicit_certificate_and_pinned_host_without_fallback_authentication(self):
        command = self.module.ssh_command(Path("/usr/bin/ssh"), 39090, "runtime", Path("/fixture/key"),
                                          Path("/fixture/key-cert.pub"), Path("/fixture/known_hosts"),
                                          "printf BYPASS; run --config /untrusted")
        options = [command[index + 1] for index, value in enumerate(command[:-1]) if value == "-o"]
        for value in ("StrictHostKeyChecking=yes", "IdentitiesOnly=yes", "IdentityAgent=none", "BatchMode=yes",
                      "PasswordAuthentication=no", "KbdInteractiveAuthentication=no", "PreferredAuthentications=publickey"):
            self.assertIn(value, options)
        self.assertIn("CertificateFile=/fixture/key-cert.pub", options)
        self.assertIn("UserKnownHostsFile=/fixture/known_hosts", options)
        self.assertEqual(["runtime@127.0.0.1", "printf BYPASS; run --config /untrusted"], command[-2:])

    def test_client_rejects_invalid_port_or_account_before_process_creation(self):
        for port, user in ((0, "runtime"), (65536, "runtime"), (True, "runtime"),
                           (39090, "runtime\nPermitRootLogin yes"), (39090, "root")):
            with self.subTest(port=port, user=user):
                with self.assertRaises(RuntimeError):
                    self.module.ssh_command(Path("/usr/bin/ssh"), port, user, Path("key"),
                                            Path("cert"), Path("known"), "ignored")

    def test_fixed_launcher_ignores_original_command_and_quotes_local_paths(self):
        with tempfile.TemporaryDirectory(prefix="opentsx-ssh-unit-") as name:
            root = Path(name)
            captured = root / "captured.json"
            runner = root / "runner with spaces"
            runner.write_text("#!/usr/bin/env python3\nimport json,os,sys\n"
                              f"open({str(captured)!r},'w').write(json.dumps(sys.argv[1:]))\n")
            runner.chmod(0o700)
            config = root / "config 'quoted'.json"
            uid = root / "uid"
            launcher = root / "launch"
            marker = root / "bypassed"
            self.module.write_launcher(launcher, runner, config, uid)
            environment = dict(os.environ, SSH_ORIGINAL_COMMAND=f"touch {marker}")
            result = subprocess.run([str(launcher), "--config", "/untrusted"],
                                    env=environment, check=True, capture_output=True)
            self.assertEqual(b"", result.stdout)
            self.assertEqual(b"", result.stderr)
            self.assertEqual(["run", "--plan", "-", "--config", str(config)], json.loads(captured.read_text()))
            self.assertEqual(os.geteuid(), int(uid.read_text()))
            self.assertFalse(marker.exists())

    def test_snapshot_is_an_independent_executable_with_matching_provenance(self):
        with tempfile.TemporaryDirectory(prefix="opentsx-ssh-snapshot-") as name:
            root = Path(name).resolve()
            source, destination = root / "source", root / "snapshot"
            source.write_bytes(b"#!/bin/sh\nexit 0\n")
            source.chmod(0o700)
            copied = self.module.snapshot_executable(source, destination)
            self.assertEqual(source.read_bytes(), destination.read_bytes())
            self.assertEqual(copied["sourceDigestBefore"], copied["snapshotDigest"])
            self.assertEqual(copied["sourceDigestAfter"], copied["snapshotDigest"])
            source.write_bytes(b"replacement after the verified copy")
            self.assertNotEqual(source.read_bytes(), destination.read_bytes())
            self.assertTrue(os.access(destination, os.X_OK))

    def test_snapshot_rejects_changed_source_and_mismatched_copy_and_removes_output(self):
        for hashes in (("a" * 64, "b" * 64, "a" * 64), ("a" * 64, "a" * 64, "b" * 64)):
            with self.subTest(hashes=hashes), tempfile.TemporaryDirectory(prefix="opentsx-ssh-unstable-") as name:
                root = Path(name).resolve()
                source, destination = root / "source", root / "snapshot"
                source.write_bytes(b"#!/bin/sh\nexit 0\n")
                source.chmod(0o700)
                with patch.object(self.module.common, "digest", side_effect=hashes):
                    with self.assertRaises(RuntimeError):
                        self.module.snapshot_executable(source, destination)
                self.assertFalse(destination.exists())

    def test_snapshot_copy_failure_cleans_partial_output(self):
        with tempfile.TemporaryDirectory(prefix="opentsx-ssh-copy-failure-") as name:
            root = Path(name).resolve()
            source, destination = root / "source", root / "snapshot"
            source.write_bytes(b"#!/bin/sh\nexit 0\n")
            source.chmod(0o700)
            with patch.object(self.module.shutil, "copyfileobj", side_effect=OSError("injected copy failure")):
                with self.assertRaises(OSError):
                    self.module.snapshot_executable(source, destination)
            self.assertFalse(destination.exists())

    def test_snapshot_refuses_an_existing_destination(self):
        with tempfile.TemporaryDirectory(prefix="opentsx-ssh-copy-existing-") as name:
            root = Path(name).resolve()
            source, destination = root / "source", root / "snapshot"
            source.write_bytes(b"#!/bin/sh\nexit 0\n")
            source.chmod(0o700)
            destination.write_bytes(b"retained evidence")
            with self.assertRaises(RuntimeError):
                self.module.snapshot_executable(source, destination)
            self.assertEqual(b"retained evidence", destination.read_bytes())


if __name__ == "__main__":
    unittest.main()
