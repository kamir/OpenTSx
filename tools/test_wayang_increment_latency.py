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
                        "machine": "fixture", "cpuCount": 1, "hardware": "fixture", "conditions": 18}
            LATENCY.report(root, metadata)
            text = (root / "REPORT.md").read_text()
            for prefix in ("P", "C"):
                for interval in (5, 30, 60):
                    for repetition in (1, 2, 3):
                        self.assertEqual(3, text.count(f"| {prefix}{interval}-r{repetition} "))
            self.assertTrue(all(len(line) <= 80 for line in text.splitlines()))

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
