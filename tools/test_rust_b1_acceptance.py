import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]


def helper():
    spec = importlib.util.spec_from_file_location(
        "rust_b1_acceptance", Path(__file__).with_name("rust_b1_acceptance.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def acceptance_job(text):
    match = re.search(r"(?ms)^  rust-b1-acceptance:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)", text)
    if match is None:
        raise AssertionError("the required Rust B1 Proxy acceptance job is missing")
    return match[1]


def run_manifest(artifact, directory):
    rows = []
    for name in ("normal", "crash-1", "crash-150", "crash-299", "unknown", "malformed", "stdin"):
        bad = name in ("unknown", "malformed")
        success = not bad and name != "stdin"
        prefix = int(name[6:]) if name.startswith("crash-") else None
        input_name = "normal" if success else name
        case = {"name": name, "plan": f"{name}/plan.jws", "config": f"{name}/config.json",
                "inputTopic": f"tsx.b1run.repartition.{input_name}.input.v2",
                "outputTopic": f"tsx.b1run.repartition.{name}.out.v2",
                "partitions": 3 if success else 1, "inputCount": 300 if success else 2 if bad else 0,
                "expectedDistinct": 300 if success else 0, "expectedRecords": 300 + (prefix or 0) if success else 0,
                "failAfter": prefix, "expectedError": f"exact {name} error" if bad else None}
        for field in ("plan", "config"):
            path = directory / case[field]
            path.parent.mkdir(exist_ok=True)
            path.write_text("test subprocess fixture")
        rows.append(case)
    return {"schema": "opentsx.rust-b1-run-fixture.v1", "artifactDigest": artifact,
            "inputRecords": 300, "series": 100, "samples": 180_000,
            "statusTopic": "tsx.b1run.dag-status.v1", "cases": rows[:6], "stdin": rows[6]}


def run_report(manifest):
    rows = []
    for case in [*manifest["cases"], manifest["stdin"]]:
        name = case["name"]
        bad = case["expectedError"] is not None
        success = not bad and name != "stdin"
        rows.append({"name": name, "records": 300 if success else 0,
                     "outputRecords": case["expectedRecords"], "failedPrefix": case["failAfter"] or 0,
                     "checkpoint": success, "exitCode": 3 if bad else 0,
                     "statusState": "failed" if bad else "stopped", "error": case["expectedError"],
                     "signal": None if bad else "SIGINT" if name in ("crash-1", "stdin") else "SIGTERM",
                     "stoppedReplay": not bad, "matched": True})
    return {"schema": "opentsx.rust-b1-run-acceptance.v1", "runnerVersion": "0.1.0",
            "artifactDigest": manifest["artifactDigest"], "executedCases": 7, "passedCases": 7,
            "failedCases": 0, "cases": rows[:6], "stdin": rows[6]}


def java_report(case):
    bad = case["expectedError"] is not None
    success = not bad and case["name"] != "stdin"
    return {"schema": "opentsx.rust-b1-run-verified.v1", "case": case["name"],
            "records": case["expectedRecords"], "distinct": case["expectedDistinct"],
            "duplicates": case["failAfter"] or 0, "checkpoint": success,
            "statusState": "failed" if bad else "stopped", "statusRecords": 300 if success else 0,
            "error": case["expectedError"], "matched": 300 if success else 0}


class CombinedAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.module = helper()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.module.ROOT = self.root
        target = self.root / "opentsx-wayang/target"
        (target / "classes").mkdir(parents=True)
        for name in ("RustB1Fixture", "RustB1RunFixture"):
            path = target / f"test-classes/org/opentsx/wayang/{name}.class"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"compiled fixture")
        dependency = self.root / "dependency.jar"
        dependency.write_bytes(b"dependency")
        (target / "classpath.txt").write_text(str(dependency))
        self.binaries = []
        for name in ("runner", "conformance", "placement", "run"):
            path = self.root / name
            path.write_text(name)
            path.chmod(0o700)
            self.binaries.append(path)
        self.output = self.root / "evidence"
        self.source = {"commit": "a" * 40, "dirty": False, "files": {"source.rs": "b" * 64}}

    def process(self, command, **kwargs):
        self.assertEqual(self.root / "opentsx-wayang", kwargs["cwd"])
        self.assertEqual("127.0.0.1:39092", kwargs["env"]["KAFSCALE_BOOTSTRAP"])
        self.assertTrue(kwargs["capture_output"])
        self.assertGreater(kwargs["timeout"], 0)
        stdout = b""
        if command == [str(self.binaries[0]), "--version"]:
            stdout = b"tsx-runner 0.1.0\n"
        elif command[0] == str(self.binaries[2]):
            path = Path(command[1])
            stdout = json.dumps({"schema": "opentsx.rust-b1-placement-result.v1", "keys": 10_000,
                                 "partitions": 12, "matched": 10_000,
                                 "fixtureSha256": self.module.digest(path)}).encode()
        elif command[0] == str(self.binaries[3]):
            self.assertEqual(str(self.binaries[0]), command[1])
            directory = Path(command[2])
            value = run_report(json.loads((directory / "run-fixture.json").read_text()))
            stdout = json.dumps(value).encode()
            (directory / "rust-run-report.json").write_bytes(stdout)
        elif command[0] == "java":
            self.assertEqual("-cp", command[1])
            fixture, mode, bootstrap, directory = command[3:7]
            self.assertEqual("127.0.0.1:39092", bootstrap)
            directory = Path(directory)
            if fixture == "org.opentsx.wayang.RustB1Fixture":
                if mode == "placement-prepare":
                    value = {"schema": "opentsx.rust-b1-placement.v1", "count": 10_000, "partitions": 12,
                             "javaTopic": "tsx.b1placement.java.v2", "rustTopic": "tsx.b1placement.rust.v2",
                             "records": [{"key": f"{index:032x}", "index": index, "partition": index % 12}
                                         for index in range(10_000)]}
                    (directory / "placement.json").write_text(json.dumps(value))
                elif mode == "placement-verify":
                    (directory / "placement-verified.json").write_text(json.dumps({
                        "schema": "opentsx.rust-b1-placement-result.v1", "keys": 10_000,
                        "partitions": 12, "matched": 10_000}))
                else:
                    self.fail("unexpected placement command")
            elif fixture == "org.opentsx.wayang.RustB1RunFixture":
                if mode == "prepare":
                    value = run_manifest(command[7], directory)
                    (directory / "run-fixture.json").write_text(json.dumps(value))
                elif mode == "verify":
                    manifest = json.loads((directory / "run-fixture.json").read_text())
                    case = next(case for case in [*manifest["cases"], manifest["stdin"]] if case["name"] == command[7])
                    (directory / f'verified-{case["name"]}.json').write_text(json.dumps(java_report(case)))
                else:
                    self.fail("unexpected run fixture command")
            else:
                self.fail("unexpected Java fixture class")
        else:
            self.fail("unexpected subprocess")
        return subprocess.CompletedProcess(command, 0, stdout, b"original native diagnostics\n")

    def conformance(self, binary, output):
        self.assertEqual(self.binaries[1], binary)
        output.mkdir()
        value = {"schema": "opentsx.rust-b1-conformance.v1", "runnerVersion": "0.1.0",
                 "endpoint": "127.0.0.1:39092", "requiredFamilies": 7, "requiredCases": 10,
                 "executedFamilies": 7, "executedCases": 10, "passedCases": 10, "failedCases": 0,
                 "cases": [{"family": family, "case": case, "status": "pass", "detail": "executed"}
                           for family, case in self.module.conformance.CASES]}
        self.module.conformance.validate_report(value, value["endpoint"])
        (output / "stdout.log").write_text(json.dumps(value))
        (output / "stderr.log").write_text("conformance diagnostics")
        (output / "result.json").write_text(json.dumps(value))
        return value

    def execute(self, process=None, source=None):
        with patch.dict(os.environ, {"KAFSCALE_BOOTSTRAP": "127.0.0.1:39092"}), \
                patch.object(self.module.subprocess, "run", side_effect=process or self.process) as native, \
                patch.object(self.module.conformance, "execute", side_effect=self.conformance) as conform, \
                patch.object(self.module, "source_state", side_effect=source or [self.source, self.source]):
            value = self.module.execute(*self.binaries, self.output)
        return value, native, conform

    def test_success_executes_fresh_probes_and_all_seven_independent_java_verifications(self):
        value, native, conform = self.execute()
        self.assertEqual(13, native.call_count)
        conform.assert_called_once()
        self.assertEqual(10, value["conformance"]["passedCases"])
        self.assertEqual(10_000, value["placement"]["java"]["matched"])
        self.assertEqual(6, len(value["javaWorkloadCases"]))
        self.assertEqual("stdin", value["javaStdinCase"]["case"])
        self.assertFalse(value["physicalArmv7Qualified"])
        self.assertEqual(28, len(value["evidence"]))
        self.assertEqual(self.module.digest(self.binaries[0]), value["binaries"]["runner"])
        self.assertEqual(value, json.loads((self.output / "report.json").read_text()))

    def test_wrong_actual_binary_version_preserves_logs_without_a_combined_report(self):
        def process(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, b"tsx-runner dev\n", b"version diagnostics")
        with self.assertRaisesRegex(RuntimeError, "version differs"):
            self.execute(process)
        self.assertEqual(b"tsx-runner dev\n", (self.output / "version/stdout.log").read_bytes())
        self.assertFalse((self.output / "report.json").exists())

    def test_failed_timeout_and_invalid_json_steps_retain_original_partial_evidence(self):
        for failure in ("exit", "timeout", "unicode", "duplicate", "partial"):
            with self.subTest(failure=failure):
                self.output = self.root / failure
                def process(command, **kwargs):
                    if command[0] != str(self.binaries[3]):
                        return self.process(command, **kwargs)
                    if failure == "timeout":
                        raise subprocess.TimeoutExpired(command, 1800, output=b"partial\xff", stderr=b"timeout diagnostics")
                    result = self.process(command, **kwargs)
                    if failure == "exit":
                        return subprocess.CompletedProcess(command, 3, result.stdout, b"native failure")
                    if failure == "unicode":
                        return subprocess.CompletedProcess(command, 0, b"\xff", b"invalid encoding")
                    if failure == "duplicate":
                        return subprocess.CompletedProcess(command, 0, result.stdout.replace(b'{"schema":', b'{"passedCases":0,"schema":', 1), b"duplicate")
                    return subprocess.CompletedProcess(command, 0, b'{"passedCases":7}', b"incomplete")
                with self.assertRaises(RuntimeError):
                    self.execute(process)
                self.assertTrue((self.output / "conformance/result.json").is_file())
                self.assertTrue((self.output / "placement/result.json").is_file())
                self.assertTrue((self.output / "run/probe/stdout.log").is_file())
                self.assertTrue((self.output / "run/probe/stderr.log").is_file())
                self.assertFalse((self.output / "report.json").exists())
                if failure == "timeout":
                    self.assertEqual(b"partial\xff", (self.output / "run/probe/stdout.log").read_bytes())

    def test_saved_report_must_exist_and_equal_original_stdout(self):
        for operation in ("missing", "different"):
            with self.subTest(operation=operation):
                self.output = self.root / operation
                def process(command, **kwargs):
                    result = self.process(command, **kwargs)
                    if command[0] == str(self.binaries[3]):
                        saved = Path(command[2]) / "rust-run-report.json"
                        if operation == "missing":
                            saved.unlink()
                        else:
                            saved.write_text("{}")
                    return result
                with self.assertRaises(RuntimeError):
                    self.execute(process)
                self.assertFalse((self.output / "report.json").exists())

    def test_independent_java_failure_cannot_be_replaced_by_a_successful_rust_report(self):
        def process(command, **kwargs):
            result = self.process(command, **kwargs)
            if command[0] == "java" and command[3].endswith("RustB1RunFixture") and command[4] == "verify":
                path = Path(command[6]) / f"verified-{command[7]}.json"
                value = json.loads(path.read_text())
                value["matched"] = 299
                path.write_text(json.dumps(value))
            return result
        with self.assertRaisesRegex(RuntimeError, "Java run matched"):
            self.execute(process)
        self.assertTrue((self.output / "run/rust-run-report.json").is_file())
        self.assertFalse((self.output / "report.json").exists())

    def test_changed_source_or_binary_prevents_a_combined_success_report(self):
        changed = copy.deepcopy(self.source)
        changed["files"]["source.rs"] = "c" * 64
        with self.assertRaisesRegex(RuntimeError, "bytes changed"):
            self.execute(source=[self.source, changed])
        self.assertFalse((self.output / "report.json").exists())

        self.output = self.root / "changed-binary"
        def process(command, **kwargs):
            result = self.process(command, **kwargs)
            if command[0] == "java" and command[-1] == "stdin":
                self.binaries[0].write_text("changed executable")
            return result
        with self.assertRaisesRegex(RuntimeError, "bytes changed"):
            self.execute(process)
        self.assertFalse((self.output / "report.json").exists())

    def test_child_process_logs_are_bound_to_the_combined_report(self):
        def process(command, **kwargs):
            result = self.process(command, **kwargs)
            if command[0] == str(self.binaries[3]):
                directory = Path(command[2]) / "normal"
                (directory / "activation.stdout").write_bytes(b"stopped outcome")
                (directory / "activation.stderr").write_bytes(b"runtime diagnostics")
            return result
        report, _, _ = self.execute(process)
        for name in ("activation.stdout", "activation.stderr"):
            relative = "run/normal/" + name
            self.assertEqual(self.module.digest(self.output / relative), report["evidence"][relative])

    def test_source_provenance_includes_the_metadata_regressions(self):
        results = [subprocess.CompletedProcess([], 0, stdout="a" * 40),
                   subprocess.CompletedProcess([], 0, stdout="")]
        with patch.object(self.module.subprocess, "run", side_effect=results), \
                patch.object(self.module, "digest", return_value="b" * 64):
            state = self.module.source_state()
        self.assertEqual("b" * 64, state["files"]["tools/kafscale_metadata_test.go"])

    def test_existing_evidence_unsafe_paths_and_missing_classpath_execute_nothing(self):
        self.output.mkdir()
        original = self.output / "report.json"
        original.write_text("preserve")
        with patch.object(self.module.subprocess, "run") as native, \
                patch.dict(os.environ, {"KAFSCALE_BOOTSTRAP": "127.0.0.1:39092"}):
            with self.assertRaises(RuntimeError):
                self.module.execute(*self.binaries, self.output)
            self.assertEqual("preserve", original.read_text())
            linked = self.root / "linked"
            linked.symlink_to(self.output, target_is_directory=True)
            for output in (linked, linked / "child", self.root / ".." / "outside"):
                with self.subTest(output=output), self.assertRaises(RuntimeError):
                    self.module.execute(*self.binaries, output)
            self.output = self.root / "new-output"
            (self.root / "opentsx-wayang/target/classpath.txt").write_text("")
            with self.assertRaisesRegex(RuntimeError, "classpath"):
                self.module.execute(*self.binaries, self.output)
        native.assert_not_called()

    def test_missing_or_credential_bearing_endpoint_is_rejected_before_execution(self):
        for endpoint in (None, "", "name:secret@localhost:39092", "https://localhost:39092"):
            environment = {} if endpoint is None else {"KAFSCALE_BOOTSTRAP": endpoint}
            with self.subTest(endpoint=endpoint), patch.dict(os.environ, environment, clear=True), \
                    patch.object(self.module.subprocess, "run") as native, self.assertRaises(RuntimeError):
                self.module.execute(*self.binaries, self.output)
            native.assert_not_called()

    def test_json_evidence_rejects_duplicate_fields_nonfinite_values_and_invalid_utf8(self):
        for data in (b'{"count":1,"count":2}', b'{"count":NaN}', b'{"count":Infinity}', b"\xff", b'{} {}'):
            with self.subTest(data=data), self.assertRaises(RuntimeError):
                self.module.parse_json(data)
        self.assertEqual({"count": 1}, self.module.parse_json(b'{"count":1}\n'))

    def test_placement_requires_actual_twelve_partition_metadata_for_every_unique_key(self):
        original = {"schema": "opentsx.rust-b1-placement.v1", "count": 10_000, "partitions": 12,
                    "javaTopic": "tsx.b1placement.java.v2", "rustTopic": "tsx.b1placement.rust.v2",
                    "records": [{"key": f"{index:032x}", "index": index, "partition": index % 12}
                                for index in range(10_000)]}
        self.module.validate_placement_manifest(original)
        for field, replacement in (("partition", None), ("partition", "0"), ("partition", False),
                                   ("partition", -1), ("partition", 12), ("index", False),
                                   ("index", 1), ("key", "0"), ("extra", 0)):
            value = copy.deepcopy(original); value["records"][0][field] = replacement
            with self.subTest(field=field, replacement=replacement), self.assertRaises(RuntimeError):
                self.module.validate_placement_manifest(value)
        value = copy.deepcopy(original); value["records"][1]["key"] = value["records"][0]["key"]
        with self.assertRaisesRegex(RuntimeError, "key or partition"):
            self.module.validate_placement_manifest(value)
        value = copy.deepcopy(original); del value["records"][0]["partition"]
        with self.assertRaisesRegex(RuntimeError, "fields differ"):
            self.module.validate_placement_manifest(value)

    def test_placement_results_reject_partial_counts_coercion_and_other_fixture_hashes(self):
        original = {"schema": "opentsx.rust-b1-placement-result.v1", "keys": 10_000,
                    "partitions": 12, "matched": 10_000, "fixtureSha256": "a" * 64}
        self.module.validate_placement(original, "a" * 64)
        for field, replacement in (("keys", 9999), ("partitions", "12"), ("matched", True),
                                   ("fixtureSha256", "b" * 64), ("schema", "old"), ("extra", 1)):
            value = dict(original); value[field] = replacement
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.module.validate_placement(value, "a" * 64)

    def test_run_report_rejects_missing_duplicate_renamed_coerced_or_incomplete_cases(self):
        directory = self.root / "run-fixture"
        directory.mkdir()
        manifest = run_manifest("sha256:" + "a" * 64, directory)
        original = run_report(manifest)
        self.assertEqual(original, self.module.validate_run_report(original, manifest))
        mutations = []
        for key, replacement in (("runnerVersion", "dev"), ("artifactDigest", "sha256:" + "b" * 64),
                                 ("executedCases", True), ("passedCases", "7"), ("failedCases", 1),
                                 ("extra", "ignored")):
            value = copy.deepcopy(original); value[key] = replacement; mutations.append(value)
        for field, replacement in (("name", "other"), ("records", 299), ("outputRecords", "300"),
                                   ("checkpoint", 1), ("exitCode", True), ("signal", "SIGINT"),
                                   ("matched", False), ("stoppedReplay", False), ("error", "ignored"),
                                   ("statusState", "running"), ("extra", 0)):
            value = copy.deepcopy(original); value["cases"][0][field] = replacement; mutations.append(value)
        value = copy.deepcopy(original); value["cases"].pop(); mutations.append(value)
        value = copy.deepcopy(original); value["cases"][1] = value["cases"][0]; mutations.append(value)
        value = copy.deepcopy(original); del value["stdin"]; mutations.append(value)
        value = copy.deepcopy(original); value["stdin"]["checkpoint"] = True; mutations.append(value)
        value = copy.deepcopy(original); value["cases"][1]["failedPrefix"] = 0; mutations.append(value)
        for index, value in enumerate(mutations):
            with self.subTest(index=index), self.assertRaises(RuntimeError):
                self.module.validate_run_report(value, manifest)

    def test_manifest_rejects_missing_zero_null_coerced_or_unsafe_case_metadata(self):
        directory = self.root / "run-fixture"
        directory.mkdir()
        original = run_manifest("sha256:" + "a" * 64, directory)
        self.module.validate_run_manifest(original, original["artifactDigest"], directory)
        for field, replacement in (("name", "other"), ("plan", "../plan.jws"), ("config", None),
                                   ("partitions", True), ("inputCount", "300"), ("expectedDistinct", 0),
                                   ("expectedRecords", 299), ("failAfter", False), ("expectedError", ""),
                                   ("inputTopic", "tsx.other.input.v2")):
            value = copy.deepcopy(original); value["cases"][0][field] = replacement
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.module.validate_run_manifest(value, original["artifactDigest"], directory)

    def test_independent_java_counts_status_and_checkpoint_are_exact(self):
        directory = self.root / "run-fixture"
        directory.mkdir()
        manifest = run_manifest("sha256:" + "a" * 64, directory)
        for case in [*manifest["cases"], manifest["stdin"]]:
            self.module.validate_java_run(java_report(case), case)
        case = manifest["cases"][1]
        for field, replacement in (("records", 300), ("distinct", "300"), ("duplicates", 0),
                                   ("matched", 299), ("checkpoint", 1), ("statusRecords", 0),
                                   ("statusState", "idle"), ("error", "ignored"), ("extra", True)):
            value = java_report(case); value[field] = replacement
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.module.validate_java_run(value, case)


class WorkflowAcceptanceTest(unittest.TestCase):
    def test_ci_executes_all_three_compiled_probes_with_the_actual_runner(self):
        job = acceptance_job((ROOT / ".github/workflows/ci.yml").read_text())
        for required in (
            "name: Rust B1 Proxy acceptance", "runs-on: ubuntu-24.04", "timeout-minutes: 45",
            'java-version: "21"', 'go-version: "1.25.2"', "rustup toolchain install 1.99.1",
            "2809a3a20bf40ed8a2b82cd93215cb59396efb0a", "build_kafscale_proxy.transport_overlay",
            "./cmd/proxy ./pkg/metadata -run '^TestOpenTSx(Proxy|Metadata)'",
            '"$RUNNER_TEMP/kafscale-cluster" --listen 127.0.0.1:39092',
            "mvn -B -ntp -pl opentsx-wayang -am -DskipTests install", "bin/opentsx classpath opentsx-wayang",
            "cargo +1.99.1 build --locked --release --examples --bins", "--target-dir target/rust-b1",
            "KAFSCALE_BOOTSTRAP: 127.0.0.1:39092", "python3 tools/rust_b1_acceptance.py",
            "--runner target/rust-b1/release/tsx-runner",
            "--conformance-binary target/rust-b1/release/examples/b1_acceptance",
            "--placement-binary target/rust-b1/release/examples/b1_placement",
            "--run-binary target/rust-b1/release/examples/b1_run_acceptance",
            "--output target/rust-b1-evidence",
        ):
            with self.subTest(required=required):
                self.assertIn(required, job)
        self.assertNotIn("continue-on-error", job)
        self.assertNotIn("privileged", job)

    def test_ci_retains_allowlisted_evidence_after_failure_without_uploading_fixture_state(self):
        job = acceptance_job((ROOT / ".github/workflows/ci.yml").read_text())
        upload = job[job.index("- uses: actions/upload-artifact@v7"):]
        for required in ("if: always()", "retention-days: 7", "if-no-files-found: error",
                         "target/rust-b1-evidence/report.json", "target/rust-b1-evidence/**/stdout.log",
                         "target/rust-b1-evidence/**/stderr.log", "target/rust-b1-evidence/**/result.json",
                         "target/rust-b1-evidence/run/rust-run-report.json",
                         "target/rust-b1-evidence/run/**/*.stdout",
                         "target/rust-b1-evidence/run/**/*.stderr",
                         "target/rust-b1-evidence/run/verified-*.json", "kafscale-broker.log"):
            with self.subTest(required=required):
                self.assertIn(required, upload)
        self.assertNotIn("target/rust-b1-evidence/\n", upload)
        self.assertNotIn("target/rust-b1-evidence/**\n", upload)
        for forbidden in (".pem", "plan.jws", "config.json", "checkpoint.json", "private"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, upload)


if __name__ == "__main__":
    unittest.main()
