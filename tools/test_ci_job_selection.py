import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import ci_job_selection as selection


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools/ci_job_selection.py"
JOBS = {
    "operator-learning", "execution-contracts", "rust-runner", "rust-runner-native",
    "rust-runner-static", "rust-b1-acceptance", "rust-runner-compose", "wayang-rust-compose",
    "java", "python", "saas-frontend", "saas-backend", "deploy", "kafscale-conformance", "cross-language",
}
RUST_RUNNER = {
    "rust-runner", "rust-runner-native", "rust-runner-static", "rust-b1-acceptance",
    "rust-runner-compose", "wayang-rust-compose", "kafscale-conformance",
}
WAYANG = {"java", "kafscale-conformance", "cross-language", "rust-b1-acceptance",
          "rust-runner-compose", "wayang-rust-compose"}


def flags(*enabled):
    chosen = {"operator-learning", *enabled}
    return {job: job in chosen for job in JOBS}


class JobSelectionTest(unittest.TestCase):
    def assert_selected(self, paths, *enabled):
        actual = selection.select_jobs(ROOT, paths)
        self.assertEqual(flags(*enabled), actual)
        self.assertTrue(all(type(value) is bool for value in actual.values()))

    def test_output_contract_contains_each_existing_job_once(self):
        self.assertIsInstance(selection.JOBS, tuple)
        self.assertEqual(JOBS, set(selection.JOBS))
        self.assertEqual(len(JOBS), len(selection.JOBS))

    def test_unreferenced_documentation_needs_only_the_always_check(self):
        for path in ("README.md", "LICENSE", "docs/new-guide.md", "docs/space \u0394\nname.md"):
            with self.subTest(path=path):
                self.assert_selected([path])

    def test_learning_references_select_their_actual_language_consumers(self):
        cases = {
            "docs/devguide/integration/wayang-shared-contracts.md": {"python"},
            "docs/devguide/integration/wayang-rust-compose.md": {"python", "saas-frontend"},
        }
        for path, enabled in cases.items():
            with self.subTest(path=path):
                self.assert_selected([path], *enabled)

    def test_manual_contracts_read_by_java_availability_keep_full_coverage(self):
        for path in ("docs/user-manual/14-saas-service-layer.md", "docs/user-manual/16-engineering-method.md"):
            with self.subTest(path=path):
                self.assertEqual({job: True for job in JOBS}, selection.select_jobs(ROOT, [path]))

    def test_native_core_selects_legacy_bridge_and_pfd_but_not_unrelated_wayang_jobs(self):
        for module in ("opentsx-core", "opentsx-data"):
            with self.subTest(module=module):
                self.assert_selected([module + "/src/main/java/fixture/Changed.java"],
                                     "java", "kafscale-conformance")

    def test_ui_reference_is_combined_with_its_native_module_dependencies(self):
        self.assert_selected([
            "opentsx-core/src/main/java/org/opentsx/app/bucketanalyser/TSOperationControlerPanel.java"
        ], "java", "kafscale-conformance", "saas-frontend")

    def test_unrelated_java_modules_do_not_start_transport_acceptance(self):
        for module in ("opentsx-modbus", "opentsx-connectors", "opentsx-lg", "opentsx-store-opentsdb-v2"):
            with self.subTest(module=module):
                self.assert_selected([module + "/src/test/java/fixture/ChangedTest.java"], "java")

    def test_transitive_reactor_dependencies_select_every_affected_acceptance(self):
        for module in ("opentsx-model", "opentsx-kafka-v2", "opentsx-store-spi", "opentsx-store-iceberg",
                       "opentsx-twin", "opentsx-windpark", "opentsx-retrieval"):
            with self.subTest(module=module):
                self.assert_selected([module + "/src/main/java/fixture/Changed.java"], *WAYANG)

    def test_flink_and_wayang_roots_keep_their_distinct_reactor_inputs(self):
        self.assert_selected(["opentsx-flink-core/src/test/java/fixture/ChangedTest.java"],
                             "java", "kafscale-conformance")
        self.assert_selected(["opentsx-wayang/src/main/java/fixture/Changed.java"],
                             "java", "kafscale-conformance", "rust-b1-acceptance",
                             "rust-runner-compose", "wayang-rust-compose")
        self.assert_selected(["opentsx-stats/src/main/java/fixture/Changed.java"],
                             "java", "kafscale-conformance", "rust-b1-acceptance",
                             "rust-runner-compose", "wayang-rust-compose")

    def test_model_wire_resources_reach_python_browser_and_rust_consumers(self):
        for path in ("opentsx-model/src/main/avro/fixture.avsc",
                     "opentsx-model/src/test/resources/testvectors/fixture.json"):
            with self.subTest(path=path):
                self.assert_selected([path], *WAYANG, *RUST_RUNNER, "execution-contracts", "python",
                                     "saas-frontend")

    def test_twin_avro_resources_also_reach_python_schema_checks(self):
        self.assert_selected(["opentsx-twin/src/main/resources/twin/avro/fixture.avsc"],
                             *WAYANG, "python")

    def test_python_package_selects_its_java_interoperability_job(self):
        self.assert_selected(["python-package/opentsx/client/fixture.py"], "python", "cross-language")

    def test_frontend_and_backend_changes_select_the_combined_real_ui_profile(self):
        self.assert_selected(["opentsx-saas-frontend/src/fixture.ts"],
                             "saas-frontend", "wayang-rust-compose", "python")
        self.assert_selected(["opentsx-saas-backend/app/fixture.py"], "saas-backend", "wayang-rust-compose")

    def test_local_launcher_frontend_asset_checks_are_selected_with_the_assets(self):
        for path in ("opentsx-saas-frontend/index.html", "opentsx-saas-frontend/src/main.tsx"):
            with self.subTest(path=path):
                self.assert_selected([path], "saas-frontend", "wayang-rust-compose", "python")

    def test_rust_dependency_direction_does_not_run_contracts_for_runner_only_changes(self):
        self.assert_selected(["rust/tsx-runner/src/fixture.rs"], *RUST_RUNNER)
        self.assert_selected(["rust/tsx-contracts/src/fixture.rs"], *RUST_RUNNER, "execution-contracts")

    def test_b2_parity_harness_inputs_select_the_java_conformance_consumer(self):
        for path in ("rust/tsx-runner/examples/b2_parity.rs", "rust/tsx-runner/Cargo.toml",
                     "rust/tsx-runner/src/catalog.rs", "rust/tsx-contracts/Cargo.lock"):
            with self.subTest(path=path):
                selected = selection.select_jobs(ROOT, [path])
                self.assertTrue(selected["kafscale-conformance"])

    def test_compose_and_local_launcher_inputs_select_their_consumers(self):
        self.assert_selected(["deploy/local-cp/workbench.py"], "deploy", "python")
        self.assert_selected(["deploy/compose/wayang-rust-nginx.conf"],
                             "deploy", "rust-runner-compose", "wayang-rust-compose")
        self.assert_selected(["docker-compose.rust-runner.yml"], "rust-runner-compose")
        self.assert_selected(["docker-compose.wayang-rust.yml"], "wayang-rust-compose")

    def test_multiple_paths_take_the_union_without_dropping_a_selected_job(self):
        self.assert_selected(["opentsx-core/src/fixture.java", "opentsx-saas-backend/app/fixture.py", "README.md"],
                             "java", "kafscale-conformance", "saas-backend", "wayang-rust-compose")

    def test_shared_build_contract_and_unmapped_paths_keep_full_coverage(self):
        paths = (
            "pom.xml", "opentsx-core/pom.xml", ".mvn/maven.config", ".github/workflows/ci.yml",
            "tools/ci_job_selection.py", "tools/new-helper.py", "bin/opentsx", ".gitignore", ".gitmodules",
            "AGENTS.md", "DOCS.md", "docs/datamodel-2026/testvectors/fixture.json",
            "docs/user-manual/03-data-model.md", "docs/user-manual/11-classic-analytics.md",
            "docs/learning/ui-operators.v1.json", "docs/devguide/schemas/fixture.json", "systems/fixture.yaml",
            "future-runtime/src/fixture.py", "rust/future-crate/src/fixture.rs", "docker-compose.future.yml",
        )
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual({job: True for job in JOBS}, selection.select_jobs(ROOT, [path]))

    def test_unknown_module_markdown_cannot_bypass_full_coverage(self):
        for path in ("future-runtime/README.md", "rust/future-crate/README.md"):
            with self.subTest(path=path):
                self.assertEqual({job: True for job in JOBS}, selection.select_jobs(ROOT, [path]))

    def test_malformed_paths_fail_instead_of_selecting_no_work(self):
        for path in ("", "/absolute.java", "../escape.java", "docs/../escape.md", "docs/./guide.md",
                     "docs//guide.md", "docs\\guide.md", "docs/nul\0guide.md", None, 42):
            with self.subTest(path=path), self.assertRaises(ValueError):
                selection.select_jobs(ROOT, [path])

    def test_master_push_and_manual_runs_are_full_without_a_graph_or_git_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for event, ref in (("push", "refs/heads/master"), ("workflow_dispatch", "refs/heads/topic"),
                               ("workflow_dispatch", "refs/tags/v3.0.1")):
                with self.subTest(event=event):
                    actual = selection.jobs_for_event(root, event, ref, "0" * 40, {})
                    self.assertEqual({job: True for job in JOBS}, actual)

    def test_unsupported_events_and_push_refs_fail_closed(self):
        for event, ref in (("push", "refs/heads/topic"), ("pull_request_target", "refs/heads/master"),
                           ("workflow_run", "refs/heads/master"), ("", "refs/heads/master"),
                           ("pull_request", "refs/pull/7/head"), ("pull_request", "refs/pull/0/merge")):
            with self.subTest(event=event), self.assertRaises(ValueError):
                selection.jobs_for_event(ROOT, event, ref, "0" * 40, {})


class MavenDependencyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.write_pom("pom.xml", "opentsx", modules=["opentsx-base", "opentsx-middle", "opentsx-leaf"])
        self.write_pom("opentsx-base/pom.xml", "base-api")
        self.write_pom("opentsx-middle/pom.xml", "middle-api", dependencies=[("org.opentsx", "base-api", "compile")])
        self.write_pom("opentsx-leaf/pom.xml", "leaf-api", dependencies=[("org.opentsx", "middle-api", "test")])

    def write_pom(self, filename, artifact, modules=(), dependencies=(), extra=""):
        path = self.root / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        body = ["<project xmlns=\"http://maven.apache.org/POM/4.0.0\">", "<modelVersion>4.0.0</modelVersion>",
                "<groupId>org.opentsx</groupId>", f"<artifactId>{artifact}</artifactId>", "<version>1.0.0</version>"]
        if modules:
            body.extend(["<modules>", *(f"<module>{module}</module>" for module in modules), "</modules>"])
        if dependencies:
            body.append("<dependencies>")
            for group, name, scope in dependencies:
                body.append(f"<dependency><groupId>{group}</groupId><artifactId>{name}</artifactId>"
                            f"<version>1.0.0</version><scope>{scope}</scope></dependency>")
            body.append("</dependencies>")
        path.write_text("\n".join([*body, extra, "</project>"]) + "\n", encoding="utf-8")

    def test_internal_artifacts_map_to_module_directories_including_test_scope(self):
        self.assertEqual({"opentsx-base": set(), "opentsx-middle": {"opentsx-base"},
                          "opentsx-leaf": {"opentsx-middle"}}, selection.maven_dependencies(self.root))

    def test_external_artifacts_with_the_same_name_are_not_internal_edges(self):
        self.write_pom("opentsx-leaf/pom.xml", "leaf-api", dependencies=[("example.external", "base-api", "test")])
        self.assertEqual(set(), selection.maven_dependencies(self.root)["opentsx-leaf"])

    def test_profile_dependencies_are_retained_for_conservative_selection(self):
        self.write_pom("opentsx-leaf/pom.xml", "leaf-api", extra=(
            "<profiles><profile><id>acceptance</id><dependencies><dependency><groupId>org.opentsx</groupId>"
            "<artifactId>base-api</artifactId><version>1.0.0</version></dependency></dependencies>"
            "</profile></profiles>"))
        self.assertEqual({"opentsx-base"}, selection.maven_dependencies(self.root)["opentsx-leaf"])

    def test_unknown_internal_dependency_cannot_disappear_from_the_graph(self):
        self.write_pom("opentsx-leaf/pom.xml", "leaf-api", dependencies=[("org.opentsx", "missing-api", "compile")])
        with self.assertRaises(ValueError):
            selection.maven_dependencies(self.root)

    def test_cycles_fail_instead_of_hiding_or_looping(self):
        self.write_pom("opentsx-base/pom.xml", "base-api", dependencies=[("org.opentsx", "leaf-api", "test")])
        with self.assertRaises(ValueError):
            selection.maven_dependencies(self.root)

    def test_missing_and_malformed_poms_are_reported(self):
        path = self.root / "opentsx-base/pom.xml"
        for content, error in ((None, FileNotFoundError), ("<project>", ET.ParseError)):
            with self.subTest(content=content):
                if content is None:
                    path.unlink()
                else:
                    path.write_text(content, encoding="utf-8")
                with self.assertRaises(error):
                    selection.maven_dependencies(self.root)

    def test_duplicate_artifact_names_are_rejected(self):
        self.write_pom("opentsx-leaf/pom.xml", "base-api")
        with self.assertRaises(ValueError):
            selection.maven_dependencies(self.root)


class GitFixture:
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for source in [ROOT / "pom.xml", *ROOT.glob("opentsx-*/pom.xml"),
                       *ROOT.glob("docs/learning/*-operators.v1.json")]:
            target = self.root / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        self.write("opentsx-core/src/original.java", "original fixture\n")
        self.git("init", "--quiet")
        self.initial = self.commit("initial fixture")
        self.git("branch", "-M", "base")

    def git(self, *arguments):
        return subprocess.run([
            "git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false",
            "-c", "user.name=Test Fixture", "-c", "user.email=fixture@example.invalid",
            "-c", "core.autocrlf=false", "-C", str(self.root), *arguments,
        ], check=True, capture_output=True, text=True).stdout.strip()

    def write(self, filename, content):
        path = self.root / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def commit(self, message):
        self.git("add", "--all")
        self.git("commit", "--quiet", "-m", "test(ci): " + message)
        return self.git("rev-parse", "HEAD")

    def merge(self, change):
        self.git("checkout", "--quiet", "-b", "feature")
        change()
        head = self.commit("change feature fixture")
        self.git("checkout", "--quiet", "base")
        self.write("opentsx-saas-frontend/src/base-only.ts", "base fixture\n")
        base = self.commit("advance base fixture")
        self.git("merge", "--quiet", "--no-ff", "-m", "test(ci): merge fixture", "feature")
        return base, head, self.git("rev-parse", "HEAD")


class PullRequestDiffTest(GitFixture, unittest.TestCase):

    def test_base_only_changes_are_excluded_from_the_pull_request_diff(self):
        values = self.merge(lambda: self.write("opentsx-core/src/original.java", "changed fixture\n"))
        self.assertEqual(["opentsx-core/src/original.java"], selection.pr_changed_paths(self.root, *values))

    def test_depth_two_checkout_has_the_required_parents_without_older_history(self):
        values = self.merge(lambda: self.write("opentsx-core/src/original.java", "changed fixture\n"))
        with tempfile.TemporaryDirectory() as directory:
            shallow = Path(directory) / "checkout"
            self.git("clone", "--quiet", "--depth=2", self.root.as_uri(), str(shallow))
            probe = subprocess.run(["git", "-C", str(shallow), "rev-parse", "--is-shallow-repository"],
                                   check=True, capture_output=True, text=True)
            self.assertEqual("true", probe.stdout.strip())
            missing = subprocess.run(["git", "-C", str(shallow), "cat-file", "-e", self.initial + "^{commit}"],
                                     capture_output=True)
            self.assertNotEqual(0, missing.returncode)
            self.assertEqual(["opentsx-core/src/original.java"], selection.pr_changed_paths(shallow, *values))

    def test_cross_module_renames_select_both_old_and_new_paths(self):
        target = "opentsx-saas-frontend/src/renamed.ts"
        def change():
            (self.root / target).parent.mkdir(parents=True, exist_ok=True)
            self.git("mv", "opentsx-core/src/original.java", target)
        values = self.merge(change)
        self.assertEqual({"opentsx-core/src/original.java", target},
                         set(selection.pr_changed_paths(self.root, *values)))

    def test_deleted_paths_are_retained_without_requiring_them_to_exist(self):
        values = self.merge(lambda: (self.root / "opentsx-core/src/original.java").unlink())
        self.assertEqual(["opentsx-core/src/original.java"], selection.pr_changed_paths(self.root, *values))

    def test_type_changes_are_not_lost_by_a_diff_status_filter(self):
        def change():
            path = self.root / "opentsx-core/src/original.java"
            path.unlink()
            path.symlink_to("fixture-target")
        values = self.merge(change)
        self.assertEqual(["opentsx-core/src/original.java"], selection.pr_changed_paths(self.root, *values))

    def test_null_delimiters_preserve_spaces_tabs_newlines_and_unicode(self):
        name = "opentsx-core/src/space \u0394\tline\nname.java"
        values = self.merge(lambda: self.write(name, "fixture\n"))
        self.assertEqual([name], selection.pr_changed_paths(self.root, *values))

    def test_merge_identity_and_both_ordered_parents_must_match(self):
        base, head, merge = self.merge(lambda: self.write("opentsx-core/src/original.java", "changed fixture\n"))
        for values in ((head, base, merge), (base, base, merge), (head, head, merge),
                       (base, head, head), (base, head, "0" * 40)):
            with self.subTest(values=values), self.assertRaises(ValueError):
                selection.pr_changed_paths(self.root, *values)
        self.git("checkout", "--quiet", "--detach", base)
        with self.assertRaises(ValueError):
            selection.pr_changed_paths(self.root, base, head, merge)

    def test_malformed_and_missing_commit_ids_fail_closed(self):
        base, head, merge = self.merge(lambda: self.write("opentsx-core/src/original.java", "changed fixture\n"))
        for sha in ("short", "x" * 40, "0" * 40, "HEAD", None):
            for index in range(3):
                values = [base, head, merge]
                values[index] = sha
                with self.subTest(index=index, sha=sha), self.assertRaises(ValueError):
                    selection.pr_changed_paths(self.root, *values)

    def test_actual_pull_request_event_routes_only_its_merge_delta(self):
        base, head, merge = self.merge(lambda: self.write("opentsx-core/src/original.java", "changed fixture\n"))
        event = {"pull_request": {"base": {"sha": base}, "head": {"sha": head}}}
        actual = selection.jobs_for_event(self.root, "pull_request", "refs/pull/7/merge", merge, event)
        self.assertEqual(flags("java", "kafscale-conformance"), actual)

    def test_select_command_uses_the_verified_pull_request_merge(self):
        base, head, merge = self.merge(lambda: self.write("opentsx-core/src/original.java", "changed fixture\n"))
        event = self.root / "event.json"
        event.write_text(json.dumps({"pull_request": {"base": {"sha": base}, "head": {"sha": head}}}),
                         encoding="utf-8")
        output = self.root / "job-output"
        result = subprocess.run([sys.executable, str(TOOL), "select"], cwd=self.root, capture_output=True, text=True,
                                env={"PATH": os.defpath, "GITHUB_EVENT_NAME": "pull_request",
                                     "GITHUB_REF": "refs/pull/7/merge", "GITHUB_SHA": merge,
                                     "GITHUB_EVENT_PATH": str(event), "GITHUB_OUTPUT": str(output)})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        lines = output.read_text(encoding="utf-8").splitlines()
        self.assertEqual(1, len(lines))
        self.assertTrue(lines[0].startswith("jobs="))
        self.assertEqual(flags("java", "kafscale-conformance"), json.loads(lines[0].removeprefix("jobs=")))
        self.assertEqual("affected", json.loads(result.stdout)["mode"])

    def test_malformed_pull_request_payloads_do_not_create_a_partial_selection(self):
        base, head, merge = self.merge(lambda: self.write("opentsx-core/src/original.java", "changed fixture\n"))
        events = ({}, {"pull_request": None}, {"pull_request": {}},
                  {"pull_request": {"base": {"sha": base}}},
                  {"pull_request": {"base": {"sha": base}, "head": {"sha": 42}}})
        for event in events:
            with self.subTest(event=event), self.assertRaises(ValueError):
                selection.jobs_for_event(self.root, "pull_request", "refs/pull/7/merge", merge, event)


class SelectionResultTest(unittest.TestCase):
    def needs(self, chosen):
        return {
            "ci-selection": {"result": "success", "outputs": {"jobs": json.dumps(chosen, separators=(",", ":"))}},
            **{job: {"result": "success" if enabled else "skipped"} for job, enabled in chosen.items()},
        }

    def check(self, needs, event="pull_request", ref="refs/pull/7/merge"):
        return selection.check_results(event, ref, needs)

    def test_selected_success_and_explicitly_excluded_skips_are_accepted(self):
        needs = self.needs(flags("java"))
        self.assertIsNone(self.check(needs))
        needs["python"]["result"] = "success"
        self.assertIsNone(self.check(needs))

    def test_every_selected_job_must_succeed(self):
        for job in JOBS:
            for result in ("skipped", "failure", "cancelled", "unknown"):
                needs = self.needs({name: True for name in JOBS})
                needs[job]["result"] = result
                with self.subTest(job=job, result=result), self.assertRaises(ValueError):
                    self.check(needs)

    def test_failure_or_cancellation_cannot_hide_behind_exclusion(self):
        for result in ("failure", "cancelled", "unknown"):
            needs = self.needs(flags())
            needs["java"]["result"] = result
            with self.subTest(result=result), self.assertRaises(ValueError):
                self.check(needs)

    def test_selector_failure_cancellation_or_skip_is_always_fatal(self):
        for result in ("failure", "cancelled", "skipped", "unknown"):
            needs = self.needs(flags())
            needs["ci-selection"]["result"] = result
            with self.subTest(result=result), self.assertRaises(ValueError):
                self.check(needs)

    def test_needs_contains_exactly_the_selector_and_each_workload_job(self):
        baseline = self.needs(flags())
        variants = []
        for name in ("ci-selection", "java", "operator-learning"):
            value = copy.deepcopy(baseline)
            del value[name]
            variants.append(value)
        variants.append({**baseline, "unexpected-job": {"result": "success"}})
        variants.extend([None, [], {"ci-selection": []}])
        for value in variants:
            with self.subTest(needs=value), self.assertRaises(ValueError):
                self.check(value)

    def test_selection_output_requires_exact_job_keys_and_strict_booleans(self):
        baseline = flags()
        invalid = [[], None, "true", {**baseline, "unexpected-job": True},
                   {name: enabled for name, enabled in baseline.items() if name != "java"},
                   {**baseline, "operator-learning": False}]
        invalid.extend({**baseline, "java": value} for value in (0, 1, "true", "false", None, [], {}))
        for value in invalid:
            needs = self.needs(baseline)
            needs["ci-selection"]["outputs"]["jobs"] = json.dumps(value)
            with self.subTest(output=value), self.assertRaises(ValueError):
                self.check(needs)

    def test_missing_malformed_or_non_string_output_is_rejected(self):
        for outputs in ({}, {"jobs": "{"}, {"jobs": None}, {"jobs": flags()}, None, []):
            needs = self.needs(flags())
            needs["ci-selection"]["outputs"] = outputs
            with self.subTest(outputs=outputs), self.assertRaises(ValueError):
                self.check(needs)

    def test_duplicate_selection_keys_cannot_be_silently_overwritten(self):
        needs = self.needs(flags())
        encoded = needs["ci-selection"]["outputs"]["jobs"]
        needs["ci-selection"]["outputs"]["jobs"] = encoded[:-1] + ',"java":false}'
        with self.assertRaises(ValueError):
            self.check(needs)

    def test_missing_or_malformed_individual_job_results_fail_closed(self):
        for value in (None, "success", {}, {"result": True}, {"result": None}):
            needs = self.needs(flags("java"))
            needs["java"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.check(needs)

    def test_master_and_manual_results_require_every_job_to_be_selected(self):
        for event, ref in (("push", "refs/heads/master"), ("workflow_dispatch", "refs/heads/topic")):
            with self.subTest(event=event):
                self.assertIsNone(self.check(self.needs({name: True for name in JOBS}), event, ref))
                with self.assertRaises(ValueError):
                    self.check(self.needs(flags("java")), event, ref)

    def test_unknown_events_and_non_master_pushes_cannot_pass_the_gate(self):
        for event, ref in (("push", "refs/heads/topic"), ("workflow_run", "refs/heads/master"),
                           ("pull_request", "refs/pull/7/head")):
            with self.subTest(event=event), self.assertRaises(ValueError):
                self.check(self.needs({name: True for name in JOBS}), event, ref)


class CommandLineTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.event = self.root / "event.json"
        self.event.write_text("{}", encoding="utf-8")
        self.output = self.root / "job-output"
        self.output.write_text("existing=keep\n", encoding="utf-8")
        self.environment = {
            "PATH": os.defpath, "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/master",
            "GITHUB_SHA": "0" * 40, "GITHUB_EVENT_PATH": str(self.event), "GITHUB_OUTPUT": str(self.output),
        }
        chosen = flags("java")
        self.needs = {
            "ci-selection": {"result": "success", "outputs": {"jobs": json.dumps(chosen)}},
            **{job: {"result": "success" if enabled else "skipped"} for job, enabled in chosen.items()},
        }

    def run_tool(self, command, overrides=None, omitted=()):
        environment = {**self.environment, **(overrides or {})}
        for name in omitted:
            environment.pop(name, None)
        return subprocess.run([sys.executable, str(TOOL), command], cwd=self.root,
                              env=environment, capture_output=True, text=True)

    def assert_failed_without_output(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertIn("CI selection failed:", result.stderr)
        self.assertEqual("", result.stdout)
        self.assertEqual("existing=keep\n", self.output.read_text(encoding="utf-8"))

    def test_full_selection_appends_one_compact_strict_output_without_a_git_checkout(self):
        for event, ref in (("push", "refs/heads/master"), ("workflow_dispatch", "refs/heads/topic")):
            with self.subTest(event=event):
                self.output.write_text("existing=keep\n", encoding="utf-8")
                result = self.run_tool("select", {"GITHUB_EVENT_NAME": event, "GITHUB_REF": ref})
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                lines = self.output.read_text(encoding="utf-8").splitlines()
                self.assertEqual(2, len(lines))
                self.assertEqual("existing=keep", lines[0])
                self.assertTrue(lines[1].startswith("jobs="))
                encoded = lines[1].removeprefix("jobs=")
                decoded = json.loads(encoded)
                self.assertEqual({job: True for job in JOBS}, decoded)
                self.assertTrue(all(type(value) is bool for value in decoded.values()))
                self.assertEqual(json.dumps(decoded, separators=(",", ":")), encoded)
                self.assertEqual({"mode": "full", "jobs": decoded}, json.loads(result.stdout))

    def test_invalid_context_or_git_checkout_never_writes_success_output(self):
        self.assert_failed_without_output(self.run_tool("select", {"GITHUB_REF": "refs/heads/topic"}))
        for missing in ("GITHUB_EVENT_NAME", "GITHUB_REF", "GITHUB_SHA", "GITHUB_EVENT_PATH", "GITHUB_OUTPUT"):
            with self.subTest(missing=missing):
                self.assert_failed_without_output(self.run_tool("select", omitted=(missing,)))
        self.event.write_text(json.dumps({"pull_request": {"base": {"sha": "1" * 40},
                                                          "head": {"sha": "2" * 40}}}), encoding="utf-8")
        self.assert_failed_without_output(self.run_tool("select", {
            "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REF": "refs/pull/7/merge", "GITHUB_SHA": "3" * 40,
        }))

    def test_malformed_duplicate_or_non_object_events_do_not_write_success_output(self):
        for content in ("{", "[]", '{"duplicate":1,"duplicate":1}'):
            with self.subTest(content=content):
                self.event.write_text(content, encoding="utf-8")
                self.assert_failed_without_output(self.run_tool("select"))

    def test_check_command_accepts_selected_success_and_explicit_skips(self):
        result = self.run_tool("check", {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_REF": "refs/pull/7/merge",
                                         "CI_NEEDS_JSON": json.dumps(self.needs)})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("All selected CI jobs completed successfully\n", result.stdout)
        self.assertEqual("existing=keep\n", self.output.read_text(encoding="utf-8"))

    def test_check_command_rejects_bad_results_json_and_missing_context(self):
        overrides = {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_REF": "refs/pull/7/merge"}
        self.assert_failed_without_output(self.run_tool("check", overrides))
        for content in ("{", "[]", '{"duplicate":1,"duplicate":1}'):
            with self.subTest(content=content):
                self.assert_failed_without_output(self.run_tool("check", {**overrides, "CI_NEEDS_JSON": content}))
        for job in ("ci-selection", "java"):
            with self.subTest(job=job):
                needs = copy.deepcopy(self.needs)
                needs[job]["result"] = "failure"
                self.assert_failed_without_output(self.run_tool("check", {
                    **overrides, "CI_NEEDS_JSON": json.dumps(needs),
                }))

    def test_check_command_rejects_partial_master_selection_even_with_successful_jobs(self):
        for result in self.needs.values():
            result["result"] = "success"
        self.assert_failed_without_output(self.run_tool("check", {"CI_NEEDS_JSON": json.dumps(self.needs)}))


if __name__ == "__main__":
    unittest.main()
