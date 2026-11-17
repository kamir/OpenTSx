import csv
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import sys
import subprocess
import unittest
from unittest.mock import patch


class FederationMeasurementTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("federation_measurement", Path(__file__).with_name("wayang_federation_measurement.py"))
        self.measurement = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.measurement)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name)

    def fixture(self):
        rows = []
        for route in self.measurement.ROUTES:
            for site in ("site-a", "site-b", "site-c"):
                http = route == "job-state"
                rows.append({"route": route, "site": site, "topic": "" if http else "tsx.wind.aggregates.v2",
                             "produce_frame_bytes": "" if http else "100", "produce_records": "" if http else "2",
                             "produce_errors": "" if http else "0", "http_body_bytes": "20" if http else "", "http_records": "2" if http else "",
                             "tls_client_bytes": "40" if http else "", "elapsed_ms": "1.25"})
        self.write_rows(rows)
        run = {"topology": {"siteAgentInstances": 3, "brokers": 4, "jvmProcesses": 1}, "correctnessPassed": True,
               "episodes": 6, "values": 12, "rawSeries": 3, "windows": 2, "partials": 6, "comparisons": 12,
               "maxRelativeMeanError": 1e-15, "maxRelativeM2Error": 1e-15, "dt2Tolerance": 1e-12,
               "routeSAdaptersEqualJ": True, "sourceSpecHash": "a" * 64, "scenarioSha256": "b" * 64,
               "referenceOrdering": "synthetic fixture ordering"}
        (self.output / "run.json").write_text(json.dumps(run))
        (self.output / "windows.csv").write_text("id,mean\nfirst,2\nsecond,3\n")
        return rows, run

    def write_rows(self, rows):
        with (self.output / "bytes.csv").open("w", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=self.measurement.FIELDS)
            writer.writeheader()
            writer.writerows(rows)

    def test_validates_measured_transport_categories_and_one_route_elapsed_time(self):
        self.fixture()
        run, rows = self.measurement.read_results(self.output)
        self.assertEqual(9, len(rows))
        self.assertEqual(300, sum(r["produce_frame_bytes"] for r in rows if r["route"] == "centralized"))
        self.assertEqual(120, sum(r["tls_client_bytes"] for r in rows if r["route"] == "job-state"))
        self.assertEqual(1, run["topology"]["jvmProcesses"])

    def test_rejects_produce_errors_even_when_the_run_claims_success(self):
        rows, _ = self.fixture()
        rows[0]["produce_errors"] = "1"
        self.write_rows(rows)
        with self.assertRaisesRegex(ValueError, "Produce errors"):
            self.measurement.read_results(self.output)

    def test_rejects_missing_sites_duplicate_rows_and_inconsistent_route_clocks(self):
        rows, _ = self.fixture()
        for changed in (rows[:-1], rows + [rows[0]], [dict(r, elapsed_ms="2") if i == 0 else r for i, r in enumerate(rows)]):
            with self.subTest(rows=len(changed)):
                self.write_rows(changed)
                with self.assertRaises(ValueError):
                    self.measurement.read_results(self.output)

    def test_rejects_incomplete_correctness_and_window_evidence(self):
        _, run = self.fixture()
        for name in ("correctnessPassed", "routeSAdaptersEqualJ"):
            changed = dict(run, **{name: False})
            (self.output / "run.json").write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, "correctness"):
                self.measurement.read_results(self.output)
        (self.output / "run.json").write_text(json.dumps(run))
        (self.output / "windows.csv").write_text("id,mean\nfirst,2\n")
        with self.assertRaisesRegex(ValueError, "window evidence"):
            self.measurement.read_results(self.output)

    def test_rejects_invalid_numbers_and_unmeasured_tls(self):
        rows, _ = self.fixture()
        for field, value in (("produce_records", "-1"), ("produce_frame_bytes", "1.5"), ("elapsed_ms", "NaN")):
            changed = [dict(r) for r in rows]
            changed[0][field] = value
            self.write_rows(changed)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.measurement.read_results(self.output)
        rows[-1]["tls_client_bytes"] = "0"
        self.write_rows(rows)
        with self.assertRaisesRegex(ValueError, "TLS"):
            self.measurement.read_results(self.output)

    def test_report_uses_measured_topology_and_separates_http_body_from_tls(self):
        self.fixture()
        metadata = {"openTsxCommit": "c" * 40, "javaVersion": "synthetic JDK fixture", "platform": "synthetic OS",
                    "hardware": "synthetic hardware", "startedAt": "2000-01-01T00:00:00Z"}
        self.measurement.report(self.output, metadata)
        text = (self.output / "REPORT.md").read_text()
        self.assertIn("3 site-agent instances", text)
        self.assertIn("1 JVM process", text)
        self.assertIn("4 Go broker processes", text)
        self.assertIn("Execution topology excludes build and transient", text)
        self.assertIn("HTTP body bytes", text)
        self.assertIn("TLS client bytes", text)
        self.assertTrue(all(len(line) <= 120 for line in text.splitlines()))

    def test_dirty_checkout_stops_before_any_build_or_output(self):
        target = self.output / "results"
        argv = ["wayang_federation_measurement.py", "--output", str(target), "--java-home", "/synthetic-jdk"]
        with patch.object(sys, "argv", argv), \
                patch.object(self.measurement.LATENCY, "require_clean_checkout", side_effect=RuntimeError("dirty source")), \
                patch.object(self.measurement.LATENCY, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "dirty source"):
                self.measurement.main()
            run.assert_not_called()
        self.assertFalse(target.exists())

    def test_inapplicable_transport_metrics_stay_blank_and_measured_fields_are_required(self):
        rows, _ = self.fixture()
        _, parsed = self.measurement.read_results(self.output)
        self.assertIsNone(parsed[0]["http_body_bytes"])
        self.assertIsNone(parsed[-1]["produce_records"])
        rows[0]["produce_frame_bytes"] = ""
        self.write_rows(rows)
        with self.assertRaises(ValueError):
            self.measurement.read_results(self.output)

    def repository(self):
        root = self.output / "source"
        root.mkdir()
        env = os.environ | {"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
                            "GIT_AUTHOR_DATE": "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE": "2000-01-01T00:00:00Z"}

        def git(*args):
            subprocess.run(["git", *args], cwd=root, env=env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        git("init", "-q")
        git("config", "user.name", "Measurement test")
        git("config", "user.email", "test@example.invalid")
        source = root / "opentsx-wayang/src/main/java/Example.java"
        source.parent.mkdir(parents=True)
        source.write_text("class Example {}\n")
        (root / "tools").mkdir()
        (root / "tools/helper.py").write_text("value = 1\n")
        git("add", ".")
        git("commit", "-q", "-m", "test(measurement): add synthetic source")
        return root, source, git

    def test_untracked_java_source_refuses_the_run_before_build_or_output(self):
        root, source, _ = self.repository()
        source.with_name("Untracked.java").write_text("class Untracked {}\n")
        target = self.output / "results"
        argv = ["wayang_federation_measurement.py", "--output", str(target), "--java-home", "/synthetic-jdk"]
        with patch.object(sys, "argv", argv), patch.object(self.measurement, "ROOT", root), \
                patch.object(self.measurement, "SOURCE_FILES", ("tools/helper.py",)), \
                patch.object(self.measurement.LATENCY.subprocess, "run", wraps=subprocess.run) as run:
            with self.assertRaisesRegex(RuntimeError, "untracked source"):
                self.measurement.main()
            self.assertTrue(all(call.args[0][0] == "git" for call in run.call_args_list))
        self.assertFalse(target.exists())

    def test_post_run_guard_refuses_changed_source_or_head_and_allows_raw_output(self):
        root, source, git = self.repository()
        with patch.object(self.measurement, "SOURCE_FILES", ("tools/helper.py",)):
            snapshot = self.measurement.source_snapshot(root)
            output = root / "tools/results"
            output.mkdir()
            (output / "run.json").write_text('{"synthetic":true}\n')
            self.measurement.require_same_source(root, snapshot, output)
            source.write_text("class Example { int changed; }\n")
            with self.assertRaises(RuntimeError):
                self.measurement.require_same_source(root, snapshot, output)
            git("add", str(source.relative_to(root)))
            git("commit", "-q", "-m", "test(measurement): change synthetic source")
            with self.assertRaisesRegex(RuntimeError, "source changed"):
                self.measurement.require_same_source(root, snapshot, output)

    def test_a_source_commit_during_java_execution_prevents_a_complete_report(self):
        root, source, git = self.repository()
        scenario = root / "opentsx-windpark/scenarios/ostsee-demo.yaml"
        scenario.parent.mkdir(parents=True)
        scenario.write_text("synthetic: true\n")
        git("add", ".")
        git("commit", "-q", "-m", "test(measurement): add synthetic scenario")
        target = self.output / "results"
        original_run = self.measurement.LATENCY.run

        def command(argv, *, cwd=None, env=None, log=None):
            if argv[:2] == ["git", "clone"]:
                Path(argv[-1]).mkdir()
                return ""
            if argv[0] == "git" and Path(cwd).name == "kafscale":
                return self.measurement.LATENCY.KAFSCALE_COMMIT if argv[1] == "rev-parse" else ""
            if argv[0] == "git":
                return original_run(argv, cwd=cwd, env=env, log=log)
            if argv[0] == "sysctl":
                return "synthetic hardware"
            if argv[0] == "go":
                if argv[1] == "build":
                    Path(argv[3]).write_bytes(b"synthetic broker")
                return "synthetic Go"
            if argv[0] == "mvn":
                for value in argv:
                    if value.startswith("-Dmdep.outputFile="):
                        Path(value.split("=", 1)[1]).write_text("/synthetic.jar")
                return ""
            if "-version" in argv:
                return 'openjdk version "21.0.8"\n'
            if "org.opentsx.wayang.FederationMeasurement" in argv:
                previous = self.output
                try:
                    self.output = target
                    _, run = self.fixture()
                    run["scenarioSha256"] = self.measurement.LATENCY.sha256(scenario)
                    (target / "run.json").write_text(json.dumps(run))
                finally:
                    self.output = previous
                source.write_text("class Example { int changed; }\n")
                git("add", str(source.relative_to(root)))
                git("commit", "-q", "-m", "test(measurement): change source during synthetic execution")
                return ""
            self.fail("unexpected build command: " + str(argv))

        argv = ["wayang_federation_measurement.py", "--output", str(target), "--java-home", "/synthetic-jdk"]
        with patch.object(sys, "argv", argv), patch.object(self.measurement, "ROOT", root), \
                patch.object(self.measurement, "SOURCE_FILES", ("tools/helper.py", str(scenario.relative_to(root)))), \
                patch.object(self.measurement.LATENCY, "run", side_effect=command), \
                patch.object(self.measurement.LATENCY, "freeze_classpath", return_value=("/synthetic.jar", [])), \
                patch.object(self.measurement.LATENCY, "power_assertion", return_value=contextlib.nullcontext(None)), \
                patch.object(self.measurement.platform, "system", return_value="Darwin"):
            with self.assertRaisesRegex(RuntimeError, "source changed"):
                self.measurement.main()
        self.assertFalse((target / "REPORT.md").exists())
        self.assertEqual("failed", json.loads((target / "metadata.json").read_text())["status"])


if __name__ == "__main__":
    unittest.main()
