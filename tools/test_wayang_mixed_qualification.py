import copy
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("mixed_qualification", Path(__file__).with_name("wayang_mixed_qualification.py"))
QUALIFICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALIFICATION)


class MixedQualificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.commit = "a" * 40
        self.runner = self.root / "runner"
        self.helper = self.root / "helper"
        self.runner.write_bytes(b"synthetic runner executable")
        self.helper.write_bytes(b"synthetic numerical helper executable")
        self.runner_digest = "sha256:" + hashlib.sha256(self.runner.read_bytes()).hexdigest()
        self.helper_digest = "sha256:" + hashlib.sha256(self.helper.read_bytes()).hexdigest()
        self.snapshot = {"commit": self.commit, "files": {"synthetic-source": "b" * 64}}
        self.mixed, self.execution, self.windows, self.partials = self.mixed_fixture()
        self.sketch, self.request, self.output = self.sketch_fixture()

    @staticmethod
    def csv_data(rows):
        buffer = io.StringIO()
        writer = csv.DictWriter(buffer, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
        return buffer.getvalue().encode()

    def mixed_fixture(self):
        execution = {"schema": "opentsx.rust-b4-mixed-execution.v1", "route": "signal-cluster", "sites": {},
                     "rustOutcomes": {name: {"state": "completed", "completed": True, "forwardedRecords": 420} for name in ("run", "replay")}}
        result = {"schema": "opentsx.rust-b4-mixed-acceptance.v1", "sourceCommit": self.commit, "route": "signal-cluster",
                  "episodes": 3600, "samples": 2_160_000, "series": 100, "partialCount": 1260,
                  "finalWindowCount": 420, "uniqueFinalWindowCount": 420, "driverWindowCount": 420, "mergeOrders": 6,
                  "mergeByteComparisons": 2520, "conformanceChecks": 30, "meanM2RelativeTolerance": 3e-12,
                  "alpha": 0.01, "conformance": {}, "traffic": [], "maxMeanRelativeError": 1e-14, "maxM2RelativeError": 2e-14,
                  "maxPartialMeanRelativeError": 1e-14, "maxPartialM2RelativeError": 2e-14, "centralSketchByteDifferences": 0}
        for site, (runtime, track, count) in QUALIFICATION.SITES.items():
            artifact = {"version": "0.1.0", "digest": self.runner_digest, "stateAbi": "synthetic-state-v1"}
            result["conformance"][site] = {"schema": "opentsx.site-conformance.v1", "site": site, "track": track, "artifact": artifact,
                                           "checks": [{"id": "synthetic-check-" + str(index), "result": "pass"} for index in range(10)]}
            execution["sites"][site] = {"runtime": runtime, "track": track, "inputRecords": count, **artifact,
                                        "commandHash": "b" * 64, "specHash": "c" * 64, "fragmentHash": "d" * 64,
                                        "statuses": [{"site": site, "state": state, "track": track, "records": count,
                                                      "specHash": "c" * 64, "artifact": {key: artifact[key] for key in ("version", "digest")}}
                                                     for state in ("running", "stopped")]}
            result["traffic"].append({"site": site, "produceRecords": 420, "produceErrors": 0, "produceFrameBytes": 1000})
        result["execution"] = execution
        windows, partials = [], []
        for index in range(420):
            row = {"key": "synthetic-window-" + str(index), "group_id": f"{index:016x}", "window_type": "PT10M",
                   "window_start_micros": "0", "window_end_micros": "600000000", "mean_relative_error": "1e-14", "m2_relative_error": "2e-14",
                   "actual_positive_bins": "1", "actual_negative_bins": "0", "expected_positive_bins": "1", "expected_negative_bins": "0"}
            windows.append({**row, "exact_fields_equal": "true", "sketch_equal": "true", "final": "true", "revision": "0", "missing_sites": ""})
            for q in ("q01", "q50", "q99"):
                for runtime in ("actual", "expected"):
                    name = q + "_" + runtime
                    windows[-1].update({name: "1", name + "_within_alpha": "true", name + "_domain": "outside-zero-without-collapse",
                                        name + "_absolute_error": "0.001", name + "_relative_error": "0.001"})
            for site, (_, track, _) in QUALIFICATION.SITES.items():
                partials.append({**row, "key": row["key"] + "-" + site, "site": site, "track": track,
                                 "spec_hash": "c" * 64, "fragment_hash": "d" * 64, "payload_sha256": "e" * 64})
        return result, execution, windows, partials

    def sketch_fixture(self):
        result = {"schema": "opentsx.rust-b4-sketch-qualification.v1", "sourceCommit": self.commit, "caseCount": 58,
                  "mixedMergeOrders": 348, "alpha": 0.01, "maxBins": 4096, "rustVersion": "0.1.0",
                  "rustArtifactDigest": self.helper_digest, "boundaryDifferencesObserved": 0, "javaVersion": "21.0.9",
                  "osName": "Mac OS X", "osArch": "aarch64", "cases": []}
        request = {"schema": "opentsx.rust-b4-sketch-input.v1", "cases": []}
        output = {"schema": "opentsx.rust-b4-sketch-output.v1", "version": "0.1.0", "alpha": 0.01, "maxBins": 4096, "cases": []}
        domains = ["ordinary"] * 3 + ["zero"] * 3 + ["threshold"] * 6 + ["boundary"] * 42
        domains += ["collapsed-" + sign + "-" + location for sign in ("negative", "positive") for location in ("merge", "site")]
        for index, domain in enumerate(domains):
            name = "synthetic-case-" + str(index)
            value = {"validCount": 1, "nanCount": 0, "gapCount": 0, "zeroCount": 1, "minBits": "3ff0000000000000", "maxBits": "3ff0000000000000", "positive": {}, "negative": {}}
            sample = {"timestampMicros": 0, "valueBits": "3ff0000000000000", "site": "s3"}
            case = {"name": name, "domain": domain, "mergeOrders": 6, "sampleCount": 1, "samples": [sample], "javaRustSketchEqual": True,
                    "java": value, "mixed": value, "meanRelativeError": 0.0, "m2RelativeError": 0.0, "quantileChecks": []}
            for suffix in ("all", "s3"):
                native = {"name": name + "-" + suffix, **value}
                output["cases"].append(native)
                request["cases"].append({"name": native["name"], "samples": [{key: sample[key] for key in ("timestampMicros", "valueBits")}]})
                case["rust" if suffix == "all" else "rustSitePartial"] = native
            for runtime in ("java", "rust", "mixed"):
                for q in (0, 0.01, 0.5, 0.99, 1):
                    case["quantileChecks"].append({"runtime": runtime, "q": q, "valueBits": "3ff0000000000000", "withinAlphaInterval": True,
                                                    "accuracyDomain": "excluded-collapsed-tail" if domain.startswith("collapsed") else "outside-zero-without-collapse"})
            if domain.startswith("collapsed"):
                case.update(lowMagnitudeTailExceedsAlpha=True, highMagnitudeTailWithinAlpha=True, uncollapsedBinCount=5001)
            result["cases"].append(case)
        return result, request, output

    def validate_mixed(self):
        return QUALIFICATION.validate_mixed(self.mixed, self.execution, self.csv_data(self.windows), self.csv_data(self.partials))

    def write_inputs(self):
        documents = {"mixed/result.json": self.mixed, "mixed/execution.json": self.execution,
                     "sketch/result.json": self.sketch, "sketch/input.json": self.request, "sketch/rust-output.json": self.output}
        for name in QUALIFICATION.FILES:
            path = self.root / ("opentsx-wayang/target/rust-b4-" + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.csv_data(self.windows if name == "mixed/windows.csv" else self.partials)
                             if name.endswith(".csv") else json.dumps(documents[name]).encode())
        for name, methods in QUALIFICATION.TESTS.items():
            suite = ET.Element("testsuite", name="org.opentsx.wayang." + name, tests=str(len(methods)), failures="0", errors="0", skipped="0")
            properties = ET.SubElement(suite, "properties")
            for key, value in {"opentsx.sourceCommit": self.commit, "opentsx.rust.b3.runner": str(self.runner),
                               "opentsx.rust.b4.sketch": str(self.helper), "unrelated.private.property": "excluded-synthetic-value"}.items():
                ET.SubElement(properties, "property", name=key, value=value)
            for method in methods:
                ET.SubElement(suite, "testcase", name=method)
            path = self.root / ("opentsx-wayang/target/surefire-reports/TEST-org.opentsx.wayang." + name + ".xml")
            path.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(suite).write(path)

    def test_valid_synthetic_cases_preserve_counts_and_measured_maxima(self):
        mixed = self.validate_mixed()
        sketch = QUALIFICATION.validate_sketch(self.sketch, self.request, self.output)
        self.assertEqual(2520, mixed["mergeByteComparisons"])
        self.assertEqual(2e-14, mixed["maxM2RelativeError"])
        self.assertEqual({"cases": 58, "mergeComparisons": 348, "boundaryDifferences": 0, "collapsedCases": 4}, sketch)

    def test_mixed_rejects_failed_moments_alpha_collapse_and_duplicates(self):
        for field, value in (("mean_relative_error", "4e-12"), ("q01_actual_within_alpha", "false"),
                             ("actual_positive_bins", "4096"), ("key", self.windows[1]["key"])):
            with self.subTest(field=field):
                previous = self.windows[0][field]
                self.windows[0][field] = value
                with self.assertRaises(ValueError):
                    self.validate_mixed()
                self.windows[0][field] = previous

    def test_mixed_rejects_missing_membership_and_false_status_identity(self):
        self.partials[0]["site"] = "s2"
        with self.assertRaisesRegex(ValueError, "site or track|duplicated"):
            self.validate_mixed()
        self.partials[0]["site"] = "s1"
        self.execution["sites"]["s3"]["statuses"][-1]["track"] = "A"
        with self.assertRaisesRegex(ValueError, "status identity"):
            self.validate_mixed()

    def test_sketch_rejects_missing_cases_and_failed_accuracy_checks(self):
        for mutate in (lambda case: case.update(mergeOrders=5),
                       lambda case: case.update(meanRelativeError=4e-12),
                       lambda case: case["quantileChecks"][0].update(withinAlphaInterval=False),
                       lambda case: case.update(domain="unknown")):
            previous = copy.deepcopy(self.sketch)
            mutate(self.sketch["cases"][0])
            with self.assertRaises(ValueError):
                QUALIFICATION.validate_sketch(self.sketch, self.request, self.output)
            self.sketch = previous

    def test_json_rejects_duplicate_keys_and_nonfinite_values(self):
        for data in (b'{"schema":"a","schema":"b"}', b'{"value":NaN}', b'[]'):
            with self.assertRaises(ValueError):
                QUALIFICATION.document(data)

    def test_bounded_files_reject_missing_symlinks_and_oversized_inputs(self):
        with self.assertRaises(ValueError):
            QUALIFICATION.regular_file(self.root, "missing")
        (self.root / "link").symlink_to(self.runner)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            QUALIFICATION.regular_file(self.root, "link")
        with patch.object(QUALIFICATION, "LIMIT", 4), self.assertRaisesRegex(ValueError, "oversized"):
            QUALIFICATION.regular_file(self.root, "runner")

    def test_junit_binds_exact_methods_commit_and_actual_binaries(self):
        self.write_inputs()
        proof = QUALIFICATION.test_evidence(self.root, self.commit, self.mixed, self.sketch)
        self.assertEqual(3, sum(suite["tests"] for suite in proof["suites"].values()))
        self.assertNotIn("unrelated.private.property", str(proof))
        self.runner.write_bytes(b"changed synthetic runtime")
        with self.assertRaisesRegex(ValueError, "actual executable differs"):
            QUALIFICATION.test_evidence(self.root, self.commit, self.mixed, self.sketch)

    def test_junit_rejects_stale_commit_missing_method_and_skips(self):
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "source commit"):
            QUALIFICATION.test_evidence(self.root, "f" * 40, self.mixed, self.sketch)
        path = self.root / "opentsx-wayang/target/surefire-reports/TEST-org.opentsx.wayang.FederatedStatsIntegrationTest.xml"
        for field, value in (("skipped", "1"), ("tests", "0")):
            tree = ET.parse(path)
            tree.getroot().set(field, value)
            tree.write(path)
            with self.assertRaisesRegex(ValueError, "zero skips"):
                QUALIFICATION.test_evidence(self.root, self.commit, self.mixed, self.sketch)
            self.write_inputs()
        tree = ET.parse(path)
        tree.getroot().find("testcase").set("name", "unrelated")
        tree.write(path)
        with self.assertRaisesRegex(ValueError, "did not execute"):
            QUALIFICATION.test_evidence(self.root, self.commit, self.mixed, self.sketch)

    def test_collect_copies_raw_bytes_and_records_hashes_without_hardware_acceptance(self):
        self.write_inputs()
        destination = self.root / "collection"
        metadata = {"executionHost": {"osName": "Mac OS X", "osArch": "aarch64"}, "platform": "synthetic macOS host", "hardware": "synthetic hardware"}
        with patch.object(QUALIFICATION, "source_snapshot", return_value=self.snapshot), patch.object(QUALIFICATION, "provenance", return_value=metadata):
            manifest = QUALIFICATION.collect(self.root, destination)
            with self.assertRaisesRegex(ValueError, "new directory"):
                QUALIFICATION.collect(self.root, destination)
        self.assertEqual(10, len(manifest["files"]))
        for name in QUALIFICATION.FILES:
            self.assertEqual((self.root / ("opentsx-wayang/target/rust-b4-" + name)).read_bytes(), (destination / name).read_bytes())
        self.assertIn("ARMv7 hardware is not required (TASK-020 D-T14)", (destination / "REPORT.md").read_text())
        self.assertFalse(json.loads((destination / "metadata.json").read_text())["physicalLinuxBoardAcceptance"])

    def test_provenance_marks_armv7_not_required_without_claiming_physical_acceptance(self):
        versions = ['openjdk version "21.0.9"', "rustc 1.99.1", "cargo 1.99.1",
                    "go version go1.25.2 darwin/arm64", "synthetic ARM64 hardware"]
        with patch.object(QUALIFICATION.FEDERATION.LATENCY, "run", side_effect=versions), \
                patch.object(QUALIFICATION.platform, "system", return_value="Darwin"), \
                patch.object(QUALIFICATION.platform, "machine", return_value="arm64"), \
                patch.object(QUALIFICATION.platform, "platform", return_value="synthetic macOS host"), \
                patch.dict(QUALIFICATION.os.environ, {}, clear=True):
            metadata = QUALIFICATION.provenance(self.root, self.sketch)
        self.assertEqual("not-required", metadata["armv7HardwareAcceptance"])
        self.assertFalse(metadata["physicalLinuxBoardAcceptance"])

    def test_collect_rejects_dirty_source_before_creating_output(self):
        destination = self.root / "collection"
        with patch.object(QUALIFICATION, "source_snapshot", side_effect=RuntimeError("dirty source")), self.assertRaisesRegex(RuntimeError, "dirty source"):
            QUALIFICATION.collect(self.root, destination)
        self.assertFalse(destination.exists())

    def test_source_snapshot_adds_tracked_rust_sources_and_rejects_untracked_rust(self):
        path = self.root / "rust/tsx-runner/Cargo.lock"
        path.parent.mkdir(parents=True)
        path.write_text("synthetic lockfile\n")
        with patch.object(QUALIFICATION.FEDERATION, "source_snapshot", return_value=copy.deepcopy(self.snapshot)), \
                patch.object(QUALIFICATION.FEDERATION.LATENCY, "require_clean_checkout"), \
                patch.object(QUALIFICATION.FEDERATION.LATENCY, "run", side_effect=["", "rust/tsx-runner/Cargo.lock\0"]):
            snapshot = QUALIFICATION.source_snapshot(self.root, self.root / "output")
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), snapshot["files"]["rust/tsx-runner/Cargo.lock"])
        with patch.object(QUALIFICATION.FEDERATION, "source_snapshot", return_value=copy.deepcopy(self.snapshot)), \
                patch.object(QUALIFICATION.FEDERATION.LATENCY, "run", return_value="rust/untracked.rs\0"), self.assertRaisesRegex(ValueError, "untracked Rust"):
            QUALIFICATION.source_snapshot(self.root, self.root / "output")


if __name__ == "__main__":
    unittest.main()
