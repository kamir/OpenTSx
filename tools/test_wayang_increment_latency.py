import contextlib
import csv
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("latency", Path(__file__).with_name("wayang_increment_latency.py"))
LATENCY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LATENCY)


class LatencyReportTest(unittest.TestCase):
    def test_classpath_snapshot_survives_changes_to_original_jars_and_classes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            classes = root / "project/opentsx-wayang/target/classes"
            classes.mkdir(parents=True)
            (classes / "Example.class").write_bytes(b"original class")
            jar = root / "m2/org/opentsx/opentsx-model/3.0.1/opentsx-model-3.0.1.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"original jar")
            own = root / "project/opentsx-model/target" / jar.name
            own.parent.mkdir(parents=True)
            own.write_bytes(b"original jar")
            frozen = root / "runtime"
            classpath, manifest = LATENCY.freeze_classpath(root / "project", [classes, jar], frozen)
            paths = [Path(entry) for entry in classpath.split(os.pathsep)]
            (classes / "Example.class").write_bytes(b"changed class")
            jar.write_bytes(b"foreign replacement")
            self.assertEqual(b"original class", (paths[0] / "Example.class").read_bytes())
            self.assertEqual(b"original jar", paths[1].read_bytes())
            self.assertTrue(all(path.is_relative_to(frozen) for path in paths))
            self.assertEqual(str(own), manifest[1]["reactorTarget"])
            self.assertEqual(manifest[1]["sha256"], LATENCY.sha256(own))

    def test_classpath_snapshot_rejects_foreign_same_version_reactor_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / "m2/org/opentsx/opentsx-model/3.0.1/opentsx-model-3.0.1.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"foreign replacement")
            own = root / "project/opentsx-model/target" / jar.name
            own.parent.mkdir(parents=True)
            own.write_bytes(b"own build")
            with self.assertRaisesRegex(RuntimeError, "differs from the reactor build"):
                LATENCY.freeze_classpath(root / "project", [jar], root / "runtime")

    def test_power_assertion_is_tied_to_python_and_released_after_failure(self):
        metadata = {}
        with patch.object(LATENCY.platform, "system", return_value="Darwin"), patch.object(LATENCY.subprocess, "Popen") as start:
            start.return_value.poll.return_value = None
            start.return_value.wait.return_value = -15
            with self.assertRaisesRegex(RuntimeError, "synthetic failure"):
                with LATENCY.power_assertion(metadata) as process:
                    self.assertIs(start.return_value, process)
                    raise RuntimeError("synthetic failure")
            self.assertEqual(["/usr/bin/caffeinate", "-i", "-w", str(os.getpid())], start.call_args.args[0])
            start.return_value.terminate.assert_called_once()
        self.assertTrue(metadata["powerAssertion"]["released"])

    def test_linux_records_no_power_assertion_without_starting_a_tool(self):
        metadata = {}
        with patch.object(LATENCY.platform, "system", return_value="Linux"), patch.object(LATENCY.subprocess, "Popen") as start:
            with LATENCY.power_assertion(metadata) as process:
                self.assertIsNone(process)
            start.assert_not_called()
        self.assertEqual("none", metadata["powerAssertion"]["mode"])
        self.assertEqual("Linux", metadata["powerAssertion"]["platform"])

    def test_nearest_rank_and_empty_samples(self):
        self.assertEqual(3, LATENCY.percentile([5, 2, 4, 1, 3], 0.5))
        self.assertEqual(5, LATENCY.percentile([5, 2, 4, 1, 3], 0.99))
        self.assertIsNone(LATENCY.percentile([], 0.5))

    def test_short_runs_require_the_explicit_pilot_flag(self):
        argv = ["wayang_increment_latency.py", "--output", "/unused", "--java-home", "/unused", "--increments", "2"]
        with patch.object(sys, "argv", argv), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                LATENCY.main()
        self.assertEqual(2, error.exception.code)

    def test_report_keeps_empty_output_percentiles_blank(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case = root / "clean-5s-r1"
            case.mkdir()
            (case / "case.json").write_text(json.dumps({"inputRecords": 2, "chain": "clean", "intervalSeconds": 5}))
            with (case / "records.csv").open("w", newline="") as target:
                csv.writer(target).writerow(["latency_ms"])
            with (case / "increments.csv").open("w", newline="") as target:
                csv.writer(target).writerows([["lateness_ms", "first_fetch_ms", "wall_ms"], ["0.25", "1", "2"]])
            metadata = {"startedAt": "2000-01-01T00:00:00Z", "javaVersion": "synthetic JDK fixture",
                        "mode": "synthetic fixture", "openTsxCommit": "fixture", "platform": "fixture",
                        "machine": "fixture", "cpuCount": 1, "hardware": "fixture", "conditions": 1}
            LATENCY.report(root, metadata)
            text = (root / "REPORT.md").read_text()
            rows = [[cell.strip() for cell in line.split("|")[1:-1]] for line in text.splitlines()
                    if line.startswith("| C5-r1")]
            self.assertEqual(["C5-r1", "1", "2", "0"], rows[0])
            self.assertEqual(["C5-r1", "", "", ""], rows[1])
            self.assertEqual(["C5-r1", "1.000", "2.000", "0.250"], rows[2])
            self.assertIn("Completed conditions: 1 of 1.", text)
            self.assertTrue(all(len(line) <= 80 for line in text.splitlines()))

    def test_all_eighteen_conditions_appear_in_each_short_table(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for chain in ("passthrough", "clean"):
                for interval in (5, 30, 60):
                    for repetition in (1, 2, 3):
                        case = root / f"{chain}-{interval}s-r{repetition}"
                        case.mkdir()
                        (case / "case.json").write_text(json.dumps({"inputRecords": 2, "chain": chain, "intervalSeconds": interval}))
                        (case / "records.csv").write_text("latency_ms\n1500.123456\n")
                        (case / "increments.csv").write_text("lateness_ms,first_fetch_ms,wall_ms\n0.25,1.23456,2.34567\n")
            metadata = {"startedAt": "2000-01-01T00:00:00Z", "javaVersion": "synthetic JDK fixture",
                        "mode": "synthetic fixture", "openTsxCommit": "a" * 40, "platform": "fixture",
                        "machine": "fixture", "cpuCount": 1, "hardware": "fixture", "conditions": 18,
                        "runtimeSnapshot": [], "powerAssertion": {"mode": "prevent idle sleep"},
                        "invocation": {"cwd": "/synthetic", "argv": ["synthetic"], "python": "/synthetic"}}
            LATENCY.report(root, metadata)
            text = (root / "REPORT.md").read_text()
            for prefix in ("P", "C"):
                for interval in (5, 30, 60):
                    for repetition in (1, 2, 3):
                        self.assertEqual(3, text.count(f"| {prefix}{interval}-r{repetition} "))
            self.assertTrue(all(len(line) <= 80 for line in text.splitlines()))
            self.assertIn("metadata.json records the SHA256 manifest.", text)
            self.assertIn("caffeinate tool prevents idle sleep", text)

    def repository(self, root):
        env = os.environ | {"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
                            "GIT_AUTHOR_DATE": "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE": "2000-01-01T00:00:00Z"}
        def git(*args):
            subprocess.run(["git", *args], cwd=root, env=env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        git("init", "-q")
        git("config", "user.name", "Latency test")
        git("config", "user.email", "test@example.invalid")
        (root / "tracked.txt").write_text("synthetic fixture\n")
        git("add", "tracked.txt")
        git("commit", "-q", "-m", "test(latency): add synthetic fixture")
        return git

    def test_checkout_guard_allows_untracked_measurements(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.repository(root)
            (root / "untracked.csv").write_text("synthetic fixture\n")
            LATENCY.require_clean_checkout(root)

    def test_checkout_guard_rejects_tracked_and_staged_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            git = self.repository(root)
            (root / "tracked.txt").write_text("changed synthetic fixture\n")
            with self.assertRaisesRegex(RuntimeError, "clean tracked checkout"):
                LATENCY.require_clean_checkout(root)
            git("add", "tracked.txt")
            with self.assertRaisesRegex(RuntimeError, "clean tracked checkout"):
                LATENCY.require_clean_checkout(root)


if __name__ == "__main__":
    unittest.main()
