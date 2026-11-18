import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def helper():
    spec = importlib.util.spec_from_file_location(
        "rust_b1_conformance", Path(__file__).with_name("rust_b1_conformance.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def report():
    return {
        "schema": "opentsx.rust-b1-conformance.v1",
        "runnerVersion": "0.1.0",
        "endpoint": "127.0.0.1:39092",
        "requiredFamilies": 7,
        "requiredCases": 10,
        "executedFamilies": 7,
        "executedCases": 10,
        "passedCases": 10,
        "failedCases": 0,
        "cases": [
            {"family": family, "case": case, "status": "pass", "detail": "verified"}
            for family, case in (
                ("topics.admin", "createsTopicsViaAdminApi"),
                ("topics.batch", "retainsEveryAcknowledgedTopicAfterBatchCreation"),
                ("records.order", "producesAndConsumesInOrderPerKey"),
                ("records.codec", "roundTripsEveryCompressionCodec:none"),
                ("records.codec", "roundTripsEveryCompressionCodec:snappy"),
                ("records.codec", "roundTripsEveryCompressionCodec:lz4"),
                ("records.codec", "roundTripsEveryCompressionCodec:zstd"),
                ("records.large", "roundTripsOneMegabyteEpisode"),
                ("offsets.committed", "committedOffsetsAreReadableAfterRestart"),
                ("offsets.explicit", "seeksToExplicitOffsetForIndexBasedReplay"),
            )
        ],
    }


class ReportValidationTest(unittest.TestCase):
    def test_complete_report_has_all_original_and_batch_cases(self):
        value = report()
        self.assertEqual(value, helper().validate_report(value, value["endpoint"]))

    def test_missing_duplicate_unknown_or_reordered_cases_are_rejected(self):
        module = helper()
        mutations = []
        missing = report()
        missing["cases"].pop()
        mutations.append(missing)
        duplicate = report()
        duplicate["cases"][-1] = copy.deepcopy(duplicate["cases"][0])
        mutations.append(duplicate)
        unknown = report()
        unknown["cases"][0]["case"] = "unknown"
        mutations.append(unknown)
        reordered = report()
        reordered["cases"].reverse()
        mutations.append(reordered)
        for value in mutations:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                module.validate_report(value, "127.0.0.1:39092")

    def test_counts_must_be_exact_integers_not_boolean_or_skipped(self):
        module = helper()
        for key in ("requiredFamilies", "requiredCases", "executedFamilies", "executedCases", "passedCases", "failedCases"):
            for replacement in (True, "10", None, -1, 999):
                value = report()
                value[key] = replacement
                with self.subTest(key=key, replacement=replacement), self.assertRaises(RuntimeError):
                    module.validate_report(value, value["endpoint"])

    def test_failed_empty_or_unrecognized_rows_cannot_pass(self):
        module = helper()
        for field, replacement in (("status", "fail"), ("status", "skipped"), ("detail", ""),
                                   ("detail", None), ("family", "unknown")):
            value = report()
            value["cases"][0][field] = replacement
            with self.subTest(field=field, replacement=replacement), self.assertRaises(RuntimeError):
                module.validate_report(value, value["endpoint"])

    def test_endpoint_version_schema_and_extra_fields_are_bound(self):
        module = helper()
        for key, replacement in (("endpoint", "127.0.0.1:1"), ("runnerVersion", "dev"),
                                 ("schema", "unknown"), ("unrecognized", True)):
            value = report()
            value[key] = replacement
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                module.validate_report(value, "127.0.0.1:39092")
        value = report()
        value["cases"][0]["unexpected"] = True
        with self.assertRaises(RuntimeError):
            module.validate_report(value, value["endpoint"])


class ExecutionTest(unittest.TestCase):
    def test_missing_or_unsafe_endpoints_are_refused_without_process_execution(self):
        module = helper()
        for endpoint in (None, "", "identity@localhost:9092", "https://localhost:9092",
                         "localhost:0", "localhost:65536", "localhost:09092", "localhost:9092\n", "[:]:9092"):
            environment = {} if endpoint is None else {"KAFSCALE_BOOTSTRAP": endpoint}
            with self.subTest(endpoint=endpoint), patch.dict(module.os.environ, environment, clear=True), \
                    patch.object(module.subprocess, "run") as execute, self.assertRaises(RuntimeError):
                module.execute(Path("fixture"), Path("output"))
            execute.assert_not_called()

    def test_success_keeps_original_stdout_and_stderr_and_writes_validated_report(self):
        module = helper()
        value = report()
        stdout = json.dumps(value) + "\n"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            binary = directory / "acceptance"
            binary.write_text("fixture")
            binary.chmod(0o700)
            with patch.dict(module.os.environ, {"KAFSCALE_BOOTSTRAP": value["endpoint"]}), \
                    patch.object(module.subprocess, "run", return_value=subprocess.CompletedProcess(
                        [str(binary)], 0, stdout, "native diagnostics\n")) as execute:
                module.execute(binary, directory / "results")
            self.assertEqual(stdout, (directory / "results/stdout.log").read_text())
            self.assertEqual("native diagnostics\n", (directory / "results/stderr.log").read_text())
            self.assertEqual(value, json.loads((directory / "results/result.json").read_text()))
            self.assertEqual([str(binary.resolve())], execute.call_args.args[0])
            self.assertEqual(value["endpoint"], execute.call_args.kwargs["env"]["KAFSCALE_BOOTSTRAP"])
            self.assertTrue(execute.call_args.kwargs["capture_output"])
            self.assertGreater(execute.call_args.kwargs["timeout"], 0)

    def test_failure_retains_logs_but_does_not_write_a_success_report(self):
        module = helper()
        for exit_code, stdout in ((3, json.dumps(report())), (0, "not json"), (0, json.dumps({}))):
            with self.subTest(exit_code=exit_code, stdout=stdout), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary).resolve()
                binary = directory / "acceptance"
                binary.write_text("fixture")
                binary.chmod(0o700)
                with patch.dict(module.os.environ, {"KAFSCALE_BOOTSTRAP": "127.0.0.1:39092"}), \
                        patch.object(module.subprocess, "run", return_value=subprocess.CompletedProcess(
                            [str(binary)], exit_code, stdout, "failure details")), self.assertRaises(RuntimeError):
                    module.execute(binary, directory / "results")
                self.assertTrue((directory / "results/stdout.log").is_file())
                self.assertTrue((directory / "results/stderr.log").is_file())
                self.assertFalse((directory / "results/result.json").exists())

    def test_existing_results_and_symlinks_are_not_overwritten(self):
        module = helper()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            binary = directory / "acceptance"
            binary.write_text("fixture")
            binary.chmod(0o700)
            output = directory / "results"
            output.mkdir()
            original = output / "result.json"
            original.write_text("preserve")
            with patch.dict(module.os.environ, {"KAFSCALE_BOOTSTRAP": "127.0.0.1:39092"}), \
                    patch.object(module.subprocess, "run") as execute, self.assertRaises(RuntimeError):
                module.execute(binary, output)
            execute.assert_not_called()
            self.assertEqual("preserve", original.read_text())
            linked = directory / "linked"
            linked.symlink_to(output, target_is_directory=True)
            with patch.dict(module.os.environ, {"KAFSCALE_BOOTSTRAP": "127.0.0.1:39092"}), \
                    patch.object(module.subprocess, "run") as execute, self.assertRaises(RuntimeError):
                module.execute(binary, linked)
            execute.assert_not_called()

    def test_timeout_retains_partial_raw_logs_without_a_success_report(self):
        module = helper()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            binary = directory / "acceptance"
            binary.write_text("fixture")
            binary.chmod(0o700)
            failure = subprocess.TimeoutExpired([str(binary)], 900, output=b"partial\xff", stderr=b"diagnostics")
            with patch.dict(module.os.environ, {"KAFSCALE_BOOTSTRAP": "127.0.0.1:39092"}), \
                    patch.object(module.subprocess, "run", side_effect=failure), self.assertRaisesRegex(RuntimeError, "exceeded"):
                module.execute(binary, directory / "results")
            self.assertEqual(b"partial\xff", (directory / "results/stdout.log").read_bytes())
            self.assertEqual(b"diagnostics", (directory / "results/stderr.log").read_bytes())
            self.assertFalse((directory / "results/result.json").exists())

    def test_duplicate_json_fields_and_non_utf8_output_are_refused(self):
        module = helper()
        original = json.dumps(report()).encode("utf-8")
        duplicate = original.replace(b'{"schema":', b'{"failedCases": 99, "schema":', 1)
        for stdout in (duplicate, b"\xff"):
            with self.subTest(stdout=stdout), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary).resolve()
                binary = directory / "acceptance"
                binary.write_text("fixture")
                binary.chmod(0o700)
                with patch.dict(module.os.environ, {"KAFSCALE_BOOTSTRAP": "127.0.0.1:39092"}), \
                        patch.object(module.subprocess, "run", return_value=subprocess.CompletedProcess(
                            [str(binary)], 0, stdout, b"")), self.assertRaises(RuntimeError):
                    module.execute(binary, directory / "results")
                self.assertEqual(stdout, (directory / "results/stdout.log").read_bytes())
                self.assertFalse((directory / "results/result.json").exists())


if __name__ == "__main__":
    unittest.main()
