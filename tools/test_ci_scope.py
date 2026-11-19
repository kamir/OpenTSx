import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import ci_scope as scope


class CiScopeSelectionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "--quiet")
        self.write("README.md", "Initial documentation\n")
        self.base = self.commit()

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.root, capture_output=True,
                              text=True, check=True, timeout=30).stdout.strip()

    def write(self, name, text="Changed content\n"):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self):
        self.git("add", "--all")
        self.git("-c", "user.name=Test Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--quiet", "-m", "test(ci): create change fixture")
        return self.git("rev-parse", "HEAD")

    def select(self, head=None):
        return scope.select_scope("pull_request", self.base, head or self.commit(), self.root)

    def test_only_the_seven_exact_demo_paths_can_reduce_scope(self):
        allowed = {
            "README.md", "CHANGELOG.md", "docs/devguide/standalone-workbench.md",
            "opentsx-saas-frontend/src/workbench/deviceSample.ts",
            "opentsx-saas-frontend/src/workbench/deviceSample.test.ts",
            "opentsx-saas-frontend/src/workbench/WorkbenchDemo.test.tsx",
            "opentsx-saas-frontend/scripts/demo-smoke.mjs",
        }
        self.assertEqual(allowed, scope.DEMO_PATHS)
        for name in allowed:
            self.write(name)
        selected = self.select()
        self.assertEqual("demo", selected["scope"])
        self.assertEqual(["operator-learning", "saas-frontend", "documentation"], selected["jobs"])
        self.assertTrue(selected["reason"])

    def test_deletion_of_an_allowlisted_regular_file_is_a_demo_change(self):
        (self.root / "README.md").unlink()
        self.assertEqual("demo", self.select()["scope"])

    def test_renames_consider_both_source_and_destination(self):
        (self.root / "README.md").rename(self.root / "CHANGELOG.md")
        self.assertEqual("demo", self.select()["scope"])
        self.git("reset", "--hard", self.base)
        (self.root / "README.md").rename(self.root / "shared.txt")
        self.assertEqual("full", self.select()["scope"])
        self.base = self.git("rev-parse", "HEAD")
        (self.root / "shared.txt").rename(self.root / "README.md")
        selected = self.select()
        self.assertEqual("full", selected["scope"])
        self.assertIn("shared.txt", selected["reason"])

    def test_shared_runtime_dependencies_and_unknown_paths_require_full_ci(self):
        for name in ["unknown.txt", "opentsx-saas-frontend/src/pages/Workbench.tsx",
                     "opentsx-saas-frontend/src/workbench/ops.ts", "opentsx-saas-frontend/package.json",
                     "opentsx-saas-frontend/package-lock.json", "opentsx-saas-frontend/.nvmrc",
                     ".github/workflows/ci.yml", "tools/ci_scope.py", "docs/datamodel-2026/testvectors/case.json"]:
            with self.subTest(path=name):
                self.git("reset", "--hard", self.base)
                self.git("clean", "-fd")
                self.write("README.md")
                self.write(name)
                selected = self.select()
                self.assertEqual("full", selected["scope"])
                self.assertIn(name, selected["reason"])

    def test_all_other_events_require_full_ci_without_reading_git(self):
        for event in ["push", "workflow_dispatch", "schedule", "", "pull_request_target"]:
            with self.subTest(event=event), patch.object(scope, "git", side_effect=AssertionError("Git must not run")):
                selected = scope.select_scope(event, "", "", self.root)
                self.assertEqual("full", selected["scope"])
                self.assertEqual(list(scope.JOBS), selected["jobs"])

    def test_immutable_commit_hashes_missing_history_and_empty_diff_require_full_ci(self):
        for base, head in [("master", self.base), (self.base, "HEAD"), ("--all", self.base),
                           ("0" * 40, self.base), (self.base, "f" * 40), (self.base, self.base)]:
            with self.subTest(base=base, head=head):
                selected = scope.select_scope("pull_request", base, head, self.root)
                self.assertEqual("full", selected["scope"])
                self.assertTrue(selected["reason"])

    def test_noncommit_objects_and_unrelated_history_require_full_ci(self):
        blob = self.git("rev-parse", f"{self.base}:README.md")
        self.assertEqual("full", scope.select_scope("pull_request", blob, self.base, self.root)["scope"])
        self.git("checkout", "--orphan", "unrelated", "--quiet")
        self.write("README.md")
        self.assertEqual("full", self.select()["scope"])

    def test_ambiguous_merge_base_and_malformed_git_output_require_full_ci(self):
        cases = [
            [b"false\n", b"commit\n", b"commit\n", b"a" * 40 + b"\n" + b"b" * 40 + b"\n"],
            [b"false\n", b"commit\n", b"commit\n", b"a" * 40 + b"\n", b"M\0README.md"],
            [b"false\n", b"commit\n", b"commit\n", b"a" * 40 + b"\n", b"A\0README.md\0", b"", b"120000 blob " + b"a" * 40 + b"\tREADME.md\0"],
        ]
        for outputs in cases:
            with self.subTest(outputs=outputs), patch.object(scope, "git", side_effect=outputs):
                selected = scope.select_scope("pull_request", self.base, self.base, self.root)
                self.assertEqual("full", selected["scope"])
                self.assertIn("could not be trusted", selected["reason"])

    def test_shallow_history_requires_full_ci_even_when_both_commits_are_present(self):
        self.write("README.md", "Second revision\n")
        base = self.commit()
        self.write("README.md", "Third revision\n")
        head = self.commit()
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["git", "clone", "--quiet", "--depth", "2", self.root.as_uri(), directory],
                           check=True, capture_output=True, timeout=30)
            for oid in (base, head):
                self.assertEqual(b"commit\n", scope.git(directory, "cat-file", "-t", oid))
            selected = scope.select_scope("pull_request", base, head, directory)
            self.assertEqual("full", selected["scope"])
            self.assertIn("history is shallow", selected["reason"])

    def test_git_execution_failures_are_explicit_full_scope_reasons(self):
        for error in [OSError("unavailable"), subprocess.TimeoutExpired("git", 30)]:
            with self.subTest(error=type(error).__name__), patch.object(scope.subprocess, "run", side_effect=error):
                selected = scope.select_scope("pull_request", self.base, self.base, self.root)
                self.assertEqual("full", selected["scope"])
                self.assertIn(type(error).__name__, selected["reason"])

    def test_new_allowlisted_symlinks_cannot_reduce_scope(self):
        target = self.root / "opentsx-saas-frontend/src/workbench/deviceSample.ts"
        target.parent.mkdir(parents=True)
        target.symlink_to("../../../../shared.ts")
        selected = self.select()
        self.assertEqual("full", selected["scope"])
        self.assertIn("regular non-executable file", selected["reason"])


class CiScopeInputTest(unittest.TestCase):
    def test_nul_diff_rejects_malformed_records_and_unsafe_paths(self):
        for data in [b"M\0README.md", b"M\0", b"R100\0README.md\0CHANGELOG.md\0",
                     b"T\0README.md\0", b"M\0README.md\0M\0README.md\0", b"M\0\xff\0"]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                scope.changes(data)
        for name in ["/README.md", "../README.md", "opentsx-saas-frontend/../README.md",
                     "./README.md", "docs//README.md", "README.md/", "docs\\README.md",
                     "README.md\nother", "README.md\tother", "C:README.md", ""]:
            with self.subTest(path=name), self.assertRaises(ValueError):
                scope.changes(b"M\0" + name.encode() + b"\0")

    def test_regular_tree_entries_are_required_and_complete(self):
        sha = "a" * 40
        good = f"100644 blob {sha}\tREADME.md\0".encode()
        self.assertEqual({"README.md"}, scope.tree_paths(good, {"README.md"}))
        for data in [good[:-1], good + good, good.replace(b"README.md", b"unknown.md"),
                     good.replace(b"100644", b"100755"), good.replace(b"100644", b"120000"),
                     good.replace(b"100644 blob", b"160000 commit"), b"malformed\0"]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                scope.tree_paths(data, {"README.md"})

    def test_json_rejects_duplicate_keys_and_nonfinite_constants(self):
        for text in ['{"scope":"demo","scope":"full"}', '{"value":NaN}', '{"value":Infinity}', "{"]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                scope.read_json(text)


class CiScopeGateTest(unittest.TestCase):
    def needs(self, name="demo"):
        selected = scope.manifest(name, "Verified fixture")
        needs = {job: {"result": "success" if job in selected["jobs"] else "skipped", "outputs": {}}
                 for job in scope.JOBS}
        needs["ci-selection"] = {"result": "success", "outputs": {"scope": name, "selection": json.dumps(selected)}}
        return needs

    def test_successful_demo_full_and_extra_conservative_jobs_are_accepted(self):
        for name in ["demo", "full"]:
            self.assertEqual(name, scope.gate(self.needs(name))["scope"])
        needs = self.needs()
        needs["java"]["result"] = "success"
        self.assertEqual("demo", scope.gate(needs)["scope"])

    def test_every_selected_job_must_succeed_and_other_failures_are_not_hidden(self):
        for job in scope.JOBS:
            for result in ["failure", "cancelled", "", "unknown"]:
                with self.subTest(job=job, result=result), self.assertRaises(ValueError):
                    needs = self.needs()
                    needs[job]["result"] = result
                    scope.gate(needs)
        for job in scope.DEMO_JOBS:
            with self.subTest(selected=job), self.assertRaises(ValueError):
                needs = self.needs()
                needs[job]["result"] = "skipped"
                scope.gate(needs)

    def test_missing_unknown_or_malformed_job_results_fail(self):
        for job in [*scope.JOBS, "ci-selection"]:
            with self.subTest(missing=job), self.assertRaises(ValueError):
                needs = self.needs()
                del needs[job]
                scope.gate(needs)
        for value in [None, [], "success", {}, {"result": "success"},
                      {"result": "success", "outputs": [], "unknown": True}]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                needs = self.needs()
                needs["saas-frontend"] = value
                scope.gate(needs)
        needs = self.needs()
        needs["unlisted"] = {"result": "success", "outputs": {}}
        with self.assertRaises(ValueError):
            scope.gate(needs)

    def test_failed_selector_and_missing_unknown_outputs_fail(self):
        for result in ["failure", "cancelled", "skipped", None]:
            with self.subTest(result=result), self.assertRaises(ValueError):
                needs = self.needs()
                needs["ci-selection"]["result"] = result
                scope.gate(needs)
        for outputs in [{}, {"scope": "demo"}, {"selection": "{}"},
                        {"scope": "demo", "selection": "{}", "unknown": "true"},
                        {"scope": True, "selection": "{}"}, {"scope": "demo", "selection": []}]:
            with self.subTest(outputs=outputs), self.assertRaises(ValueError):
                needs = self.needs()
                needs["ci-selection"]["outputs"] = outputs
                scope.gate(needs)

    def test_malformed_inconsistent_or_weakened_manifests_fail(self):
        correct = scope.manifest("demo", "Verified fixture")
        malformed = [None, {}, [], {**correct, "unknown": True}, {**correct, "schema": "other"},
                     {**correct, "scope": "full"}, {**correct, "reason": ""}, {**correct, "reason": 1},
                     {**correct, "jobs": []}, {**correct, "jobs": ["saas-frontend"]},
                     {**correct, "jobs": [*correct["jobs"], "java"]},
                     {**correct, "jobs": [*correct["jobs"], correct["jobs"][0]]},
                     {**correct, "jobs": [None, "saas-frontend", "documentation"]}]
        for value in malformed:
            with self.subTest(value=value), self.assertRaises(ValueError):
                needs = self.needs()
                needs["ci-selection"]["outputs"]["selection"] = json.dumps(value)
                scope.gate(needs)

    def test_cli_writes_exact_outputs_and_gate_uses_the_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "outputs"
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, scope.main(["select", "--event", "push", "--base", "", "--head", "",
                                               "--github-output", str(target)]))
            selected = json.loads(output.getvalue())
            lines = target.read_text().splitlines()
            self.assertEqual("scope=full", lines[0])
            self.assertEqual(selected, json.loads(lines[1].removeprefix("selection=")))
            self.assertEqual(2, len(lines))
        for value, expected in [(json.dumps(self.needs()), 0), ("", 1), ("{}", 1), ("{", 1)]:
            with self.subTest(value=value), patch.dict(os.environ, {"CI_NEEDS_JSON": value}), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(expected, scope.main(["gate"]))


if __name__ == "__main__":
    unittest.main()
