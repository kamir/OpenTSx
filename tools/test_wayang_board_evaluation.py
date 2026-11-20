import copy
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch
import xml.etree.ElementTree as ET


class BoardEvaluationTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location(
            "board_evaluation", Path(__file__).with_name("wayang_board_evaluation.py"))
        self.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tool)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.checker = "opentsx-wayang/src/test/java/org/opentsx/wayang/SyntheticFixtureTest.java"
        self.checker_class = "org.opentsx.wayang.SyntheticFixtureTest"
        path = self.source / self.checker
        path.parent.mkdir(parents=True)
        path.write_text("package org.opentsx.wayang;\nclass SyntheticFixtureTest { }\n")
        self.snapshot = {"commit": "a" * 40, "files": {self.checker: self.digest(path)}}

    @staticmethod
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def file(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return {"file": name, "sha256": self.digest(path)}

    def fixture(self):
        evidence = {"schema": self.tool.EVIDENCE_SCHEMA, "status": "complete",
                    "evidenceKind": "synthetic-test-fixture", "protocol": self.tool.protocol(),
                    "sourceSnapshot": self.snapshot, "boards": [], "runs": []}
        for architecture, machine in (("arm64", "aarch64"), ("armv7", "armv7l")):
            evidence["boards"].append({"architecture": architecture, "machine": machine,
                "executionMode": "physical", "system": "Linux", "deviceModel": "synthetic board fixture",
                "os": "synthetic Linux fixture", "kernel": "synthetic kernel fixture",
                "cpuGovernor": "performance", "storage": "synthetic storage fixture",
                "network": "synthetic fixed KafScale Proxy network fixture",
                "identityEvidence": self.file(f"{architecture}/identity.txt", "synthetic hardware fixture\n")})
        resources = io.StringIO()
        writer = csv.writer(resources)
        writer.writerow(self.tool.RESOURCE_FIELDS)
        for second in range(2101):
            writer.writerow((second + 0.1, 4096, second / 10, 2100 - second))
        for condition in self.tool.conditions():
            identity = condition["id"]
            runtime = condition["runtime"]
            version = "0.1.0" if runtime == "rust" else "3.0.1"
            runtime_version = "1.99.1" if runtime == "rust" else runtime.removeprefix("java") + ".0.1"
            artifact = self.file(f"artifacts/{condition['architecture']}-{runtime}", "synthetic executable fixture\n")
            artifact["version"] = version
            run = {"condition": condition, "durationSeconds": 2100.2,
                   "firstFetchSeconds": 0.1, "firstOutputSeconds": 0.2,
                   "command": ["synthetic-fixture-command", identity], "runtimeVersion": runtime_version,
                   "javaHeap": "synthetic explicit heap fixture" if runtime != "rust" else None,
                   "artifact": artifact,
                   "input": self.file(f"input/{condition['series']}.txt", f"synthetic input fixture: {condition['series']} series\n"),
                   "resources": self.file(f"{identity}/resources.csv", resources.getvalue())}
            suite = ET.Element("testsuite", name=self.checker_class, tests="1", failures="0", errors="0", skipped="0")
            properties = ET.SubElement(suite, "properties")
            for name, value in (("opentsx.sourceCommit", self.snapshot["commit"]), ("opentsx.conditionId", identity),
                                ("opentsx.artifactSha256", artifact["sha256"]), ("opentsx.inputSha256", run["input"]["sha256"])):
                ET.SubElement(properties, "property", name=name, value=value)
            ET.SubElement(suite, "testcase", name="synthetic_fixture", classname=self.checker_class)
            run["outputProof"] = self.file(f"{identity}/proof.xml", ET.tostring(suite, encoding="unicode"))
            run["outputProof"]["checkerSource"] = self.checker
            run["outputProof"]["checkerClass"] = self.checker_class
            evidence["runs"].append(run)
        return evidence

    def validate(self, value):
        with patch.object(self.tool.FEDERATION, "source_snapshot", return_value=self.snapshot):
            return self.tool.validate(value, self.root, self.source)

    def test_plan_is_complete_and_never_claims_measured_or_assigned_hardware(self):
        plan = self.tool.plan()
        self.assertEqual("planned", plan["status"])
        self.assertFalse(plan["hardwareQualification"])
        self.assertEqual({"warmupSeconds": 300, "measurementSeconds": 1800, "sampleEverySeconds": 1}, plan["protocol"])
        self.assertEqual(54, len(plan["conditions"]))
        self.assertEqual(54, len({row["id"] for row in plan["conditions"]}))
        self.assertTrue(all(board["deviceModel"] is None and board["executionMode"] == "unassigned" for board in plan["boards"]))
        self.assertEqual({"arm64", "armv7"}, {row["architecture"] for row in plan["conditions"]})
        self.assertEqual({100, 1000, 10000}, {row["series"] for row in plan["conditions"]})
        self.assertEqual({"java17", "java21", "rust"}, {row["runtime"] for row in plan["conditions"]})

    def test_complete_synthetic_fixture_only_qualifies_evidence_integrity(self):
        result = self.validate(self.fixture())
        self.assertEqual(54, result["conditions"])
        self.assertEqual("synthetic-test-fixture", result["evidenceKind"])
        self.assertFalse(result["hardwareQualification"])
        self.assertTrue(result["ownerReviewRequired"])

    def test_planned_missing_and_duplicate_conditions_are_rejected(self):
        value = self.fixture()
        variants = [self.tool.plan(), dict(value, runs=value["runs"][:-1]),
                    dict(value, runs=value["runs"][:-1] + [value["runs"][0]])]
        for variant in variants:
            with self.subTest(schema=variant["schema"]), self.assertRaises(ValueError):
                self.validate(variant)

    def test_protocol_cannot_shorten_the_warmup_measurement_or_sample_cadence(self):
        value = self.fixture()
        for field in ("warmupSeconds", "measurementSeconds", "sampleEverySeconds"):
            changed = copy.deepcopy(value)
            changed["protocol"][field] = 2
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "protocol"):
                self.validate(changed)

    def test_condition_numeric_fields_cannot_be_boolean_or_float_aliases(self):
        value = self.fixture()
        for field, replacement in (("series", 100.0), ("repetition", True)):
            changed = copy.deepcopy(value)
            changed["runs"][0]["condition"][field] = replacement
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "condition"):
                self.validate(changed)

    def test_missing_board_identity_and_hosted_or_emulated_execution_are_rejected(self):
        value = self.fixture()
        for field, replacement in (("deviceModel", ""), ("os", ""), ("kernel", ""),
                ("storage", ""), ("network", ""), ("cpuGovernor", "powersave"),
                ("executionMode", "emulated"), ("executionMode", "hosted"),
                ("machine", "x86_64"), ("system", "Darwin")):
            changed = copy.deepcopy(value)
            changed["boards"][0][field] = replacement
            with self.subTest(field=field, value=replacement), self.assertRaisesRegex(ValueError, "board"):
                self.validate(changed)

    def test_changed_source_or_artifact_cannot_be_claimed_as_verified(self):
        value = self.fixture()
        changed = copy.deepcopy(value)
        changed["sourceSnapshot"]["commit"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "source"):
            self.validate(changed)
        artifact = self.root / value["runs"][0]["artifact"]["file"]
        artifact.write_text("changed synthetic executable\n")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            self.validate(value)

    def test_dirty_source_failure_is_preserved(self):
        with patch.object(self.tool.FEDERATION, "source_snapshot", side_effect=RuntimeError("dirty source")):
            with self.assertRaisesRegex(RuntimeError, "dirty source"):
                self.tool.validate(self.fixture(), self.root, self.source)

    def test_output_proof_needs_actual_source_bound_successful_test_evidence(self):
        value = self.fixture()
        for content in ('<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
                        '<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase/></testsuite>',
                        '<testsuite tests="1" failures="-1" errors="1" skipped="0"><testcase/></testsuite>',
                        '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase><skipped/></testcase></testsuite>',
                        '{"correctnessPassed":true}'):
            changed = copy.deepcopy(value)
            proof = changed["runs"][0]["outputProof"]
            proof.update(self.file(proof["file"], content))
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.validate(changed)
        value = self.fixture()
        value["runs"][0]["outputProof"]["checkerSource"] = "unknown/test.py"
        with self.assertRaisesRegex(ValueError, "checker"):
            self.validate(value)

    def test_resource_coverage_must_be_complete_and_cannot_hide_bad_samples(self):
        value = self.fixture()
        first = value["runs"][0]
        path = first["resources"]["file"]
        rows = (self.root / path).read_text().splitlines()
        for content in ("\n".join(rows[:-2]) + "\n", "\n".join(rows + [rows[-1]]) + "\n",
                        "\n".join(rows[:2] + ["1.1,4096,NaN,1"] + rows[3:]) + "\n",
                        "\n".join(rows[:2] + ["1.1,-1,0.1,1"] + rows[3:]) + "\n",
                        "\n".join(rows[:2] + ["1.1,4096,0.1,-1"] + rows[3:]) + "\n",
                        "\n".join(rows[:2] + ["1.1,4096,0.1,1,extra"] + rows[3:]) + "\n",
                        "\n".join(rows[:2] + ["1.1,4096,100,1"] + rows[3:]) + "\n"):
            changed = copy.deepcopy(value)
            changed["runs"][0]["resources"] = self.file(path, content)
            with self.subTest(last=content.splitlines()[-1]), self.assertRaisesRegex(ValueError, "resource"):
                self.validate(changed)

    def test_declared_duration_cannot_outlast_raw_sample_coverage(self):
        value = self.fixture()
        value["runs"][0]["durationSeconds"] = 3600
        with self.assertRaisesRegex(ValueError, "resource"):
            self.validate(value)

    def test_startup_clocks_cannot_exceed_the_last_actual_resource_sample(self):
        value = self.fixture()
        value["runs"][0]["firstFetchSeconds"] = 2100.15
        value["runs"][0]["firstOutputSeconds"] = 2100.15
        with self.assertRaisesRegex(ValueError, "covered"):
            self.validate(value)

    def test_fractional_final_sampling_bucket_is_optional_but_timings_stay_observed(self):
        value = self.fixture()
        run = value["runs"][0]
        path = run["resources"]["file"]
        rows = (self.root / path).read_text().splitlines()
        run["resources"] = self.file(path, "\n".join(rows[:-1]) + "\n")
        self.assertEqual(54, self.validate(value)["conditions"])
        run["durationSeconds"] = 2100
        self.assertEqual(54, self.validate(value)["conditions"])

    def test_application_version_is_separate_from_the_jdk_or_rust_compiler_version(self):
        value = self.fixture()
        self.assertEqual("3.0.1", value["runs"][0]["artifact"]["version"])
        self.assertEqual("17.0.1", value["runs"][0]["runtimeVersion"])
        self.assertEqual(54, self.validate(value)["conditions"])
        for runtime_version in (None, "dev", "21.0.1"):
            changed = copy.deepcopy(value)
            changed["runs"][0]["runtimeVersion"] = runtime_version
            with self.subTest(version=runtime_version), self.assertRaisesRegex(ValueError, "runtime version"):
                self.validate(changed)
        rust = next(run for run in value["runs"] if run["condition"]["runtime"] == "rust")
        rust["runtimeVersion"] = "1.99.0"
        with self.assertRaisesRegex(ValueError, "runtime version"):
            self.validate(value)

    def test_application_and_runtime_pins_cannot_change_between_repetitions(self):
        value = self.fixture()
        for field, version in (("runtimeVersion", "17.0.2"), ("applicationVersion", "3.0.2")):
            changed = copy.deepcopy(value)
            if field == "applicationVersion":
                changed["runs"][1]["artifact"]["version"] = version
            else:
                changed["runs"][1][field] = version
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "pins changed"):
                self.validate(changed)

    def test_successful_junit_copies_cannot_be_reused_for_other_conditions(self):
        value = self.fixture()
        first = value["runs"][0]["outputProof"]
        second = value["runs"][1]["outputProof"]
        copied = (self.root / first["file"]).read_text()
        second.update(self.file(second["file"], copied))
        with self.assertRaisesRegex(ValueError, "binding"):
            self.validate(value)

    def test_junit_properties_bind_source_condition_artifact_and_input(self):
        value = self.fixture()
        proof = value["runs"][0]["outputProof"]
        original = ET.fromstring((self.root / proof["file"]).read_text())
        for name in ("opentsx.sourceCommit", "opentsx.conditionId", "opentsx.artifactSha256", "opentsx.inputSha256"):
            changed = copy.deepcopy(value)
            document = copy.deepcopy(original)
            next(prop for prop in document.find("properties") if prop.attrib["name"] == name).set("value", "wrong")
            changed["runs"][0]["outputProof"].update(self.file(proof["file"], ET.tostring(document, encoding="unicode")))
            with self.subTest(property=name), self.assertRaisesRegex(ValueError, "binding"):
                self.validate(changed)
        self.fixture()
        changed = copy.deepcopy(value)
        document = copy.deepcopy(original)
        document.remove(document.find("properties"))
        changed["runs"][0]["outputProof"].update(self.file(proof["file"], ET.tostring(document, encoding="unicode")))
        with self.assertRaisesRegex(ValueError, "binding"):
            self.validate(changed)

    def test_junit_checker_must_match_the_full_source_package_and_class(self):
        value = self.fixture()
        proof = value["runs"][0]["outputProof"]
        document = ET.fromstring((self.root / proof["file"]).read_text())
        document.set("name", "unrelated.package.SyntheticFixtureTest")
        proof.update(self.file(proof["file"], ET.tostring(document, encoding="unicode")))
        with self.assertRaisesRegex(ValueError, "checker"):
            self.validate(value)
        value = self.fixture()
        value["runs"][0]["outputProof"]["checkerClass"] = "unrelated.package.SyntheticFixtureTest"
        with self.assertRaisesRegex(ValueError, "checker"):
            self.validate(value)

    def test_invalid_startup_clocks_versions_and_heap_are_rejected(self):
        value = self.fixture()
        for field, replacement in (("durationSeconds", 2099), ("firstFetchSeconds", -1),
                ("firstOutputSeconds", 2200), ("firstFetchSeconds", True), ("javaHeap", "")):
            changed = copy.deepcopy(value)
            changed["runs"][0][field] = replacement
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(changed)
        value["runs"][0]["artifact"]["version"] = "dev"
        with self.assertRaisesRegex(ValueError, "version"):
            self.validate(value)

    def test_evidence_paths_cannot_escape_the_bundle_or_reuse_run_samples(self):
        value = self.fixture()
        for reference in ({"file": "../escape", "sha256": "a" * 64},
                          {"file": "/absolute", "sha256": "a" * 64}):
            changed = copy.deepcopy(value)
            changed["runs"][0]["resources"] = reference
            with self.assertRaisesRegex(ValueError, "path"):
                self.validate(changed)
        value["runs"][1]["resources"] = value["runs"][0]["resources"]
        with self.assertRaisesRegex(ValueError, "reused"):
            self.validate(value)

    def test_symbolic_link_evidence_is_rejected(self):
        value = self.fixture()
        first = value["runs"][0]
        original = first["resources"]
        alias = self.root / "alias.csv"
        alias.symlink_to(self.root / original["file"])
        first["resources"] = {"file": "alias.csv", "sha256": original["sha256"]}
        with self.assertRaisesRegex(ValueError, "symbolic"):
            self.validate(value)

    def test_validation_cli_distinguishes_synthetic_evidence_and_invalid_json(self):
        path = self.root / "evidence.json"
        path.write_text(json.dumps(self.fixture()))
        output = io.StringIO()
        with patch.object(self.tool.FEDERATION, "source_snapshot", return_value=self.snapshot), redirect_stdout(output):
            self.assertEqual(0, self.tool.main(["validate", "--input", str(path), "--source-root", str(self.source)]))
        self.assertFalse(json.loads(output.getvalue())["hardwareQualification"])
        for content in ('{"schema":1,"schema":2}', '{"unused":NaN}'):
            path.write_text(content)
            with self.subTest(content=content), redirect_stdout(io.StringIO()):
                self.assertEqual(1, self.tool.main(["validate", "--input", str(path), "--source-root", str(self.source)]))

    def test_plan_cli_writes_only_new_files_and_validate_rejects_that_plan(self):
        output = self.root / "plan.json"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.tool.main(["plan", "--output", str(output)]))
        self.assertEqual(54, len(json.loads(output.read_text())["conditions"]))
        with redirect_stdout(io.StringIO()):
            self.assertEqual(1, self.tool.main(["plan", "--output", str(output)]))
            self.assertEqual(1, self.tool.main(["validate", "--input", str(output), "--source-root", str(self.source)]))


if __name__ == "__main__":
    unittest.main()
