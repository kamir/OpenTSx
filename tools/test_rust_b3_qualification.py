import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def helper():
    spec = importlib.util.spec_from_file_location(
        "rust_b3_qualification", Path(__file__).with_name("rust_b3_qualification.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class QualificationTest(unittest.TestCase):
    def setUp(self):
        self.module = helper()
        self.temporary = tempfile.TemporaryDirectory(prefix="opentsx-b3-qualification-")
        self.root = Path(self.temporary.name).resolve()
        self.addCleanup(self.temporary.cleanup)
        self.runner = self.root / "runner"
        version = self.module.conformance.runner_version()
        self.runner.write_text(f"#!/bin/sh\nprintf 'tsx-runner {version}\\n'\n")
        self.runner.chmod(0o700)
        self.protocol_path = self.root / "protocol.json"
        self.placement_path = self.root / "placement.json"
        self.runner_log = self.root / "runner.log"
        self.contracts_log = self.root / "contracts.log"
        self.output = self.root / "qualification.json"
        self.protocol = {"schema": "opentsx.rust-b1-conformance.v1", "runnerVersion": version,
                         "endpoint": "127.0.0.1:39092", "requiredFamilies": 7, "requiredCases": 10,
                         "executedFamilies": 7, "executedCases": 10, "passedCases": 10, "failedCases": 0,
                         "cases": [{"family": f, "case": c, "status": "pass", "detail": "unit fixture"}
                                   for f, c in self.module.conformance.CASES]}
        result = {"schema": "opentsx.rust-b1-placement-result.v1", "keys": 10_000,
                  "partitions": 12, "matched": 10_000}
        self.placement = {"java": result, "rust": {**result, "fixtureSha256": "a" * 64}}
        self.protocol_path.write_text(json.dumps(self.protocol))
        self.placement_path.write_text(json.dumps(self.placement))
        self.write_logs()

    def write_logs(self, zero_suite=None):
        primitive = [n for n in self.module.artifacts.VECTOR_TESTS if n != "shared_execution_contract_vectors"]
        names = [*primitive, *self.module.artifacts.EXAMPLE_TESTS]
        names.extend(f"fixture::case_{i}" for i in range(260 - len(names)))
        lines = ["     Running unittests src/lib.rs (target/debug/deps/runner)", "running 260 tests",
                 *(f"test {name} ... ok" for name in names),
                 "test result: ok. 260 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;"]
        for name in self.module.VECTOR_SUITES:
            lines.extend([f"     Running tests/{name}.rs (target/debug/deps/{name})",
                          f"running {0 if name == zero_suite else 1} tests"])
            if name != zero_suite:
                lines.append(f"test fixture_{name} ... ok")
            lines.append(f"test result: ok. {0 if name == zero_suite else 1} passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;")
        self.runner_log.write_text("\n".join(lines) + "\n")
        self.contracts_log.write_text("     Running tests/shared_vectors.rs (target/debug/deps/shared_vectors)\n"
                                      "running 1 test\ntest shared_execution_contract_vectors ... ok\n"
                                      "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;\n")

    def generate(self, **changes):
        arguments = dict(runner=self.runner, bootstrap="127.0.0.1:39092",
                         test_logs=[self.runner_log, self.contracts_log], protocol_report=self.protocol_path,
                         placement_report=self.placement_path, expires="2026-11-20T00:00:00Z", output=self.output)
        arguments.update(changes)
        with patch.object(self.module, "utc_now", return_value=datetime(2026, 11, 19, 12, tzinfo=timezone.utc)):
            return self.module.generate(**arguments)

    def test_manifest_uses_actual_log_counts_binary_and_all_fourteen_shared_vector_files(self):
        result = self.generate()
        self.assertEqual(267, result["tests"]["passed"])
        self.assertEqual(list(self.module.VECTOR_SUITES), result["tests"]["vectorSuites"])
        self.assertEqual("sha256:" + hashlib.sha256(self.runner.read_bytes()).hexdigest(), result["artifact"]["digest"])
        self.assertEqual(14, len(result["vectors"]))
        for name, digest in result["vectors"].items():
            self.assertEqual(hashlib.sha256((self.module.ROOT / name).read_bytes()).hexdigest(), digest)
        self.assertEqual([hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.runner_log, self.contracts_log)],
                         result["logs"])
        self.assertEqual(result, json.loads(self.output.read_text()))
        self.assertEqual(0o600, self.output.stat().st_mode & 0o777)

    def test_failed_ignored_measured_filtered_or_missing_primitive_logs_fail(self):
        original = self.runner_log.read_text()
        for old, new in (("0 failed", "1 failed"), ("0 ignored", "1 ignored"),
                         ("0 measured", "1 measured"), ("0 filtered out", "1 filtered out"),
                         (self.module.artifacts.VECTOR_TESTS[0], "unrelated_test")):
            with self.subTest(new=new):
                self.runner_log.write_text(original.replace(old, new))
                with self.assertRaises((RuntimeError, ValueError)):
                    self.generate()
                self.assertFalse(self.output.exists())
        self.runner_log.write_text(original)

    def test_missing_and_zero_case_vector_suites_fail_even_when_the_total_is_large(self):
        original = self.runner_log.read_text()
        self.runner_log.write_text(original.replace("Running tests/stats_vectors.rs", "Running tests/other.rs"))
        with self.assertRaises(RuntimeError):
            self.generate()
        self.write_logs(zero_suite="stats_vectors")
        with self.assertRaises(RuntimeError):
            self.generate()

    def test_a_compiler_failure_cannot_follow_passing_summary_evidence(self):
        with self.runner_log.open("a") as stream:
            stream.write("error: could not compile the selected target\n")
        with self.assertRaises(RuntimeError):
            self.generate()

    def test_duplicate_logs_cannot_inflate_the_executed_test_count(self):
        for logs in ([self.runner_log, self.runner_log], [self.runner_log]):
            with self.subTest(logs=logs), self.assertRaises(RuntimeError):
                self.generate(test_logs=logs)
        copied = self.root / "copied.log"
        copied.write_bytes(self.runner_log.read_bytes())
        with self.assertRaises(RuntimeError):
            self.generate(test_logs=[self.runner_log, self.contracts_log, copied])

    def test_endpoint_version_and_protocol_boolean_counts_are_rejected(self):
        for field, value in (("endpoint", "127.0.0.1:39093"), ("runnerVersion", "dev"),
                             ("failedCases", False), ("executedCases", True)):
            with self.subTest(field=field):
                changed = {**self.protocol, field: value}
                self.protocol_path.write_text(json.dumps(changed))
                with self.assertRaises(RuntimeError):
                    self.generate()
        self.protocol_path.write_text(json.dumps(self.protocol))

    def test_missing_duplicate_or_failed_protocol_cases_are_rejected(self):
        for rows in (self.protocol["cases"][:-1], [self.protocol["cases"][0]] * 10,
                     [{**self.protocol["cases"][0], "status": "fail"}, *self.protocol["cases"][1:]]):
            with self.subTest(rows=rows):
                self.protocol_path.write_text(json.dumps({**self.protocol, "cases": rows}))
                with self.assertRaises(RuntimeError):
                    self.generate()

    def test_placement_requires_both_actual_ten_thousand_key_results(self):
        for role in ("java", "rust"):
            for field, value in (("keys", True), ("partitions", 1), ("matched", 9999)):
                with self.subTest(role=role, field=field):
                    changed = copy.deepcopy(self.placement)
                    changed[role][field] = value
                    self.placement_path.write_text(json.dumps(changed))
                    with self.assertRaises(RuntimeError):
                        self.generate()
        changed = copy.deepcopy(self.placement)
        changed["rust"]["fixtureSha256"] = "not-a-sha256"
        self.placement_path.write_text(json.dumps(changed))
        with self.assertRaises(RuntimeError):
            self.generate()

    def test_expiry_must_be_an_explicit_future_utc_instant(self):
        for value in ("2026-11-19T11:00:00Z", "2026-11-19T12:00:00Z", "2026-11-20T00:00:00",
                      "2026-11-20T00:00:00+01:00", "2026-02-30T00:00:00Z", "not-a-date"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                self.generate(expires=value)

    def test_changed_or_missing_evidence_is_rejected_without_partial_output(self):
        original = self.module.version
        def changed(binary, expected):
            result = original(binary, expected)
            self.protocol_path.write_text("changed while qualification was generated")
            return result
        with patch.object(self.module, "version", side_effect=changed):
            with self.assertRaisesRegex(RuntimeError, "inputs changed"):
                self.generate()
        self.assertFalse(self.output.exists())
        self.protocol_path.unlink()
        with self.assertRaises((RuntimeError, OSError)):
            self.generate()

    def test_runner_changes_during_version_verification_are_rejected(self):
        original = self.module.version
        def changed(binary, expected):
            result = original(binary, expected)
            self.runner.write_bytes(b"replacement after --version")
            return result
        with patch.object(self.module, "version", side_effect=changed):
            with self.assertRaisesRegex(RuntimeError, "inputs changed"):
                self.generate()
        self.assertFalse(self.output.exists())

    def test_only_the_version_command_is_executed_without_cargo_or_broker_probes(self):
        original = self.module.subprocess.run
        with patch.object(self.module.subprocess, "run", wraps=original) as invoked:
            self.generate()
        self.assertEqual(1, invoked.call_count)
        self.assertEqual([str(self.runner), "--version"], invoked.call_args.args[0])

    def test_original_java_placement_manifest_is_checked_when_supplied(self):
        manifest = {"schema": "opentsx.rust-b1-placement.v1", "count": 10_000, "partitions": 12,
                    "javaTopic": "tsx.b1placement.java.v2", "rustTopic": "tsx.b1placement.rust.v2",
                    "records": [{"key": f"{i:032x}", "index": i, "partition": i % 12} for i in range(10_000)]}
        path = self.root / "placement-manifest.json"
        path.write_text(json.dumps(manifest))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        self.placement["rust"]["fixtureSha256"] = digest
        self.placement_path.write_text(json.dumps(self.placement))
        result = self.generate(placement_manifest=path)
        self.assertEqual(digest, result["placement"]["rust"]["fixtureSha256"])
        self.placement["rust"]["fixtureSha256"] = "b" * 64
        self.placement_path.write_text(json.dumps(self.placement))
        with self.assertRaises(RuntimeError):
            self.generate(placement_manifest=path, output=self.root / "different.json")

    def test_traversal_symlinked_logs_and_missing_shared_vector_inputs_fail(self):
        with self.assertRaises(RuntimeError):
            self.generate(runner=self.root / "untrusted" / ".." / "runner")
        linked = self.root / "linked.log"
        linked.symlink_to(self.runner_log)
        with self.assertRaises(RuntimeError):
            self.generate(test_logs=[linked, self.contracts_log])
        with patch.object(self.module, "VECTORS", (*self.module.VECTORS[:-1], "docs/missing-vector.json")):
            with self.assertRaises(OSError):
                self.generate()

    def test_fractional_utc_expiry_is_preserved_without_an_implicit_clock_setting(self):
        expires = "2026-11-20T00:00:00.123456789Z"
        result = self.generate(expires=expires)
        self.assertEqual(expires, result["expires"])
        self.assertEqual("2026-11-19T12:00:00.000000Z", result["issued"])

    def test_bad_parser_counter_types_and_small_suites_cannot_qualify(self):
        for value in (True, "267", 0, 258):
            with self.subTest(value=value):
                result = self.module.artifacts.test_counts([self.runner_log.read_text(), self.contracts_log.read_text()])
                result["passed"] = value
                with patch.object(self.module.artifacts, "test_counts", return_value=result):
                    with self.assertRaises(RuntimeError):
                        self.generate()

    def test_an_unversioned_or_changed_binary_cannot_qualify(self):
        self.runner.write_text("#!/bin/sh\nprintf 'tsx-runner dev\\n'\n")
        with self.assertRaises(RuntimeError):
            self.generate()

    def test_existing_output_is_never_overwritten(self):
        self.output.write_bytes(b"retained owner evidence")
        with self.assertRaises(RuntimeError):
            self.generate()
        self.assertEqual(b"retained owner evidence", self.output.read_bytes())


if __name__ == "__main__":
    unittest.main()
