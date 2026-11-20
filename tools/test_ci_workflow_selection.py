import ast
from pathlib import Path
import re
import shlex
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
EXISTING_JOBS = {
    "operator-learning", "execution-contracts", "rust-runner", "rust-runner-native",
    "rust-runner-static", "rust-b1-acceptance", "rust-runner-compose", "wayang-rust-compose",
    "java", "python", "saas-frontend", "saas-backend", "deploy", "kafscale-conformance",
    "cross-language", "standalone-analysis",
}


def section(text, name, indentation):
    padding = " " * indentation
    match = re.search(rf"^{padding}{re.escape(name)}:[^\n]*\n(.*?)(?=^{padding}\S|\Z)",
                      text, re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError(f"Missing configuration section: {name}")
    return match.group(1)


def job_blocks(workflow):
    source = section(workflow, "jobs", 0)
    matches = list(re.finditer(r"^  ([A-Za-z0-9_-]+):[^\n]*\n", source, re.MULTILINE))
    names = [match.group(1) for match in matches]
    if len(names) != len(set(names)):
        raise AssertionError("CI jobs must have unique identifiers")
    return {match.group(1): source[match.end():matches[index + 1].start()
            if index + 1 < len(matches) else len(source)] for index, match in enumerate(matches)}


def field(block, name):
    match = re.search(rf"^    {re.escape(name)}:[ \t]*(.*)$", block, re.MULTILINE)
    if match is None:
        raise AssertionError(f"Missing job field: {name}")
    value = match.group(1).strip()
    if value in {"|", "|-", ">", ">-"}:
        value = " ".join(line.strip() for line in section(block, name, 4).splitlines())
    return value


def needs(block):
    value = field(block, "needs")
    if value.startswith("[") and value.endswith("]"):
        return [name.strip().strip("\"'") for name in value[1:-1].split(",") if name.strip()]
    if value:
        return [value.strip("\"'")]
    return re.findall(r"^      - ([A-Za-z0-9_-]+)\s*$", section(block, "needs", 4), re.MULTILINE)


def condition(block):
    value = field(block, "if")
    if value.startswith("${{") and value.endswith("}}"):
        value = value[3:-2]
    return re.sub(r"\s+", "", value)


def step_with_command(block, command):
    matches = [step for step in re.split(r"(?m)(?=^      - )", block)
               if re.search(rf"^(?:      - |        )run: {re.escape(command)}\s*$",
                            step, re.MULTILINE)]
    if len(matches) != 1:
        raise AssertionError(f"Expected one command step: {command}")
    return matches[0]


def maven_commands(block):
    return [shlex.split(command) for command in re.findall(
        r"\bmvn(?=[ \t]|$)[^\n]*", block.replace("\\\n", " "), re.MULTILINE)]


class CiWorkflowSelectionTest(unittest.TestCase):
    def test_maven_command_detection_excludes_paths_and_retains_empty_invocations(self):
        source = ("          cache-dependency-path: |\n"
                  "            **/.mvn/wrapper/maven-wrapper.properties\n"
                  "            **/.mvn/extensions.xml\n"
                  "            .mvn/maven.config\n"
                  "        run: mvn -B -pl opentsx-wayang -am install\n"
                  "          mvn\n")
        commands = maven_commands(source)
        self.assertEqual([["mvn", "-B", "-pl", "opentsx-wayang", "-am", "install"], ["mvn"]], commands)
        with self.assertRaises(AssertionError):
            self.assert_maven_roots("rust-b1-acceptance", commands)

    def assert_maven_roots(self, job, commands):
        from ci_job_selection import MAVEN_ROOTS

        self.assertTrue(commands, job)
        roots = set()
        for arguments in commands:
            self.assertIn("-pl", arguments, job)
            index = arguments.index("-pl") + 1
            self.assertLess(index, len(arguments), job)
            roots.update(arguments[index].split(","))
        self.assertEqual(roots, set(MAVEN_ROOTS[job]), job)

    def assert_inventory(self, workflow):
        jobs = job_blocks(workflow)
        self.assertEqual(EXISTING_JOBS | {"ci-selection", "ci-result"}, set(jobs))
        return jobs

    def assert_triggers(self, workflow):
        triggers = section(workflow, "on", 0)
        self.assertEqual({"push", "pull_request", "workflow_dispatch"},
                         set(re.findall(r"^  ([A-Za-z0-9_-]+):", triggers, re.MULTILINE)))
        self.assertRegex(section(triggers, "push", 2), r"(?m)^    branches: \[master\]\s*$")
        self.assertFalse(section(triggers, "pull_request", 2).strip())
        self.assertFalse(section(triggers, "workflow_dispatch", 2).strip())
        self.assertNotRegex(triggers, r"(?m)^\s+(?:paths|paths-ignore|branches-ignore):")
        self.assertNotRegex(workflow, r"(?m)^\s+continue-on-error: (?:true|\$\{\{)")

    def assert_permissions(self, workflow):
        permissions = section(workflow, "permissions", 0)
        self.assertEqual(["contents: read"], [line.strip() for line in permissions.splitlines() if line.strip()])
        self.assertNotRegex(workflow, r"(?m)^    permissions:")

    def assert_selector(self, jobs):
        selector = jobs["ci-selection"]
        self.assertNotRegex(selector, r"(?m)^    if:")
        checkout = [step for step in re.split(r"(?m)(?=^      - )", selector)
                    if re.search(r"^      - uses: actions/checkout@v7\s*$", step, re.MULTILINE)]
        self.assertEqual(1, len(checkout))
        self.assertRegex(checkout[0], r"(?m)^          fetch-depth: 2\s*$")
        self.assertIn("uses: actions/setup-python@v7", selector)
        self.assertRegex(selector, r'(?m)^          python-version: ["\']?3\.12["\']?\s*$')
        outputs = section(selector, "outputs", 4)
        self.assertRegex(outputs, r"(?m)^      jobs: \$\{\{\s*steps\.select\.outputs\.jobs\s*\}\}\s*$")
        select = step_with_command(selector, "python3 tools/ci_job_selection.py select")
        self.assertRegex(select, r"(?m)^        id: select\s*$")
        self.assertNotRegex(select, r"(?m)^        (?:if|continue-on-error):")

    def assert_job_selection(self, jobs, name):
        self.assertEqual(["ci-selection"], needs(jobs[name]), name)
        expected = ("!cancelled()&&(github.event_name!='pull_request'||"
                    "(needs.ci-selection.result=='success'&&"
                    f"fromJSON(needs.ci-selection.outputs.jobs)['{name}']))")
        self.assertEqual(expected, condition(jobs[name]), name)

    def assert_result(self, jobs):
        result = jobs["ci-result"]
        self.assertEqual("CI result", field(result, "name"))
        self.assertEqual("always()", condition(result))
        dependencies = needs(result)
        self.assertEqual(EXISTING_JOBS | {"ci-selection"}, set(dependencies))
        self.assertEqual(len(EXISTING_JOBS) + 1, len(dependencies))
        self.assertIn("uses: actions/checkout@v7", result)
        self.assertIn("uses: actions/setup-python@v7", result)
        check = step_with_command(result, "python3 tools/ci_job_selection.py check")
        self.assertNotRegex(check, r"(?m)^        (?:if|continue-on-error):")
        self.assertRegex(result, r"(?m)^\s+CI_NEEDS_JSON: \$\{\{\s*toJSON\(needs\)\s*\}\}\s*$")

    def assert_contract(self, workflow):
        jobs = self.assert_inventory(workflow)
        self.assert_triggers(workflow)
        self.assert_permissions(workflow)
        self.assert_selector(jobs)
        for name in sorted(EXISTING_JOBS):
            self.assert_job_selection(jobs, name)
        self.assert_result(jobs)

    def test_workflow_contains_selection_and_one_stable_result_for_all_existing_jobs(self):
        self.assert_inventory(WORKFLOW.read_text(encoding="utf-8"))

    def test_selector_has_both_merge_parents_and_exports_the_executed_step_output(self):
        jobs = job_blocks(WORKFLOW.read_text(encoding="utf-8"))
        self.assertIn("ci-selection", set(jobs), "PR job selection is missing")
        self.assert_selector(jobs)

    def test_every_existing_job_is_selected_for_prs_and_unconditional_for_other_events(self):
        jobs = job_blocks(WORKFLOW.read_text(encoding="utf-8"))
        for name in sorted(EXISTING_JOBS):
            with self.subTest(job=name):
                self.assert_job_selection(jobs, name)

    def test_master_and_manual_workflow_runs_are_not_removed_by_trigger_filters(self):
        self.assert_triggers(WORKFLOW.read_text(encoding="utf-8"))

    def test_result_runs_after_failure_and_checks_every_job_with_the_real_validator(self):
        jobs = job_blocks(WORKFLOW.read_text(encoding="utf-8"))
        self.assertIn("ci-result", set(jobs), "A stable aggregate result is missing")
        self.assert_result(jobs)

    def test_selection_retains_the_existing_read_only_permission_boundary(self):
        self.assert_permissions(WORKFLOW.read_text(encoding="utf-8"))

    def test_declared_maven_roots_match_inline_workflow_commands(self):
        jobs = job_blocks(WORKFLOW.read_text(encoding="utf-8"))
        for job in ("rust-b1-acceptance", "kafscale-conformance", "cross-language"):
            with self.subTest(job=job):
                self.assert_maven_roots(job, maven_commands(jobs[job]))

    def test_kafscale_b3_qualifies_the_final_artifact_and_requires_the_full_offline_case(self):
        job = job_blocks(WORKFLOW.read_text(encoding="utf-8"))["kafscale-conformance"]
        self.assertIn("shell: bash\n        run: |\n          set -euo pipefail", job)
        self.assertIn("cargo +1.99.1 test --locked --all-targets --manifest-path rust/tsx-runner/Cargo.toml -- --nocapture", job)
        self.assertIn("cargo +1.99.1 test --locked --manifest-path rust/tsx-contracts/Cargo.toml -- --nocapture", job)
        for path in ("target/rust-b3-evidence/runner.log", "target/rust-b3-evidence/contracts.log"):
            self.assertIn("tee " + path, job)
        self.assertIn("cargo +1.99.1 build --locked --examples --bins", job)
        for property_name, path in {
            "opentsx.rust.b3.runner": "target/rust-b2/debug/tsx-runner",
            "opentsx.rust.b3.offline": "target/rust-b2/debug/examples/b3_offline",
            "opentsx.rust.b3.testLog": "target/rust-b3-evidence/runner.log",
            "opentsx.rust.b3.contractsLog": "target/rust-b3-evidence/contracts.log",
        }.items():
            self.assertIn(f'-D{property_name}="$GITHUB_WORKSPACE/{path}"', job)
        self.assertIn('"RustAgentDaemonIntegrationTest": 1,', job)
        self.assertIn("realDaemonConformsPullsExecutesAcknowledgesAndStops", job)
        self.assertIn("opentsx-wayang/target/rust-b3-daemon/result.json", job)
        self.assertIn('"RustOfflineCatchupIntegrationTest": 1,', job)
        self.assertIn('"site.RustSiteAdmissionTest": 6,', job)
        self.assertIn("fullWindparkCutSurvivesOfflineSignalAndAcknowledgementLossWithoutMissingFinalWindows", job)
        self.assertIn('for k in ("failures", "errors", "skipped")', job)
        for filename in ("result.json", "protocol.json", "placement.json", "qualification.json"):
            self.assertIn("opentsx-wayang/target/rust-b3-offline/" + filename, job)

    def test_kafscale_b4_requires_both_mixed_and_numeric_cases_and_keeps_the_raw_evidence(self):
        job = job_blocks(WORKFLOW.read_text(encoding="utf-8"))["kafscale-conformance"]
        self.assertIn('-Dopentsx.rust.b4.sketch="$GITHUB_WORKSPACE/target/rust-b2/debug/examples/b4_sketch"', job)
        for name, methods in {
            "FederatedStatsIntegrationTest": {
                "everyActualW3WindowMatchesCentralizedAndAllSixMergeOrders",
                "everyActualMixedW3WindowMatchesCentralizedAndAllSixMergeOrders",
            },
            "MixedSketchQualificationTest": {
                "mixedSketchesQualifySharedBoundaryZeroBucketAndCollapsedTailCases",
                "helperReportsItsVersionAndRejectsMalformedOrOversizedInput",
            },
        }.items():
            self.assertIn(f'"{name}": 2,', job)
            self.assertIn(f'if name == "{name}" and (int(suite.attrib["tests"]) != 2 or executed != {{', job)
            for method in methods:
                self.assertIn(f'"{method}",', job)
        for folder in ("rust-b4-mixed", "rust-b4-sketch"):
            self.assertIn(f"opentsx-wayang/target/{folder}/**", job)

    def test_kafscale_b3_runs_real_ssh_and_uploads_only_public_ssh_files(self):
        job = job_blocks(WORKFLOW.read_text(encoding="utf-8"))["kafscale-conformance"]
        self.assertIn("openssh-server", job)
        self.assertIn("python3 tools/rust_ssh_launch.py", job)
        self.assertIn('--runner target/rust-b2/debug/tsx-runner', job)
        self.assertIn('--fixture-binary target/rust-b2/debug/examples/b3_ssh', job)
        self.assertIn('--sshd /usr/sbin/sshd', job)
        self.assertIn('--output target/rust-b3-evidence/ssh', job)
        for filename in ("report.json", "metadata.json", "sshd.log"):
            self.assertIn("target/rust-b3-evidence/ssh/" + filename, job)
        uploads = job[job.index("uses: actions/upload-artifact"):]
        self.assertNotIn("target/rust-b3-evidence/\n", uploads)
        self.assertNotIn("*.pem", uploads)

    def test_declared_maven_roots_match_compose_helper_commands(self):
        jobs = job_blocks(WORKFLOW.read_text(encoding="utf-8"))
        helpers = {
            "rust-runner-compose": "tools/verify_rust_runner_compose.py",
            "wayang-rust-compose": "tools/verify_wayang_rust_compose.py",
        }
        for job, helper in helpers.items():
            with self.subTest(job=job):
                self.assertIn("python3 " + helper, jobs[job])
                source = ast.parse((WORKFLOW.parents[2] / helper).read_text(encoding="utf-8"))
                commands = []
                for node in ast.walk(source):
                    if not isinstance(node, ast.Call) or not node.args:
                        continue
                    arguments = node.args[0]
                    if (isinstance(arguments, ast.List) and arguments.elts
                            and isinstance(arguments.elts[0], ast.Constant)
                            and arguments.elts[0].value == "mvn"):
                        commands.append(ast.literal_eval(arguments))
                self.assert_maven_roots(job, commands)

    def test_contract_rejects_bypasses_missing_jobs_and_suppressed_failures(self):
        workflow = self.valid_workflow()
        self.assert_contract(workflow)
        mutations = {
            "missing selector": workflow.replace("  ci-selection:\n", "  unrelated:\n", 1),
            "missing existing job": workflow.replace("  java:\n", "  renamed-java:\n", 1),
            "unknown job": workflow + "  unrelated:\n    runs-on: ubuntu-24.04\n    steps:\n      - run: exit 0\n",
            "duplicate job": workflow + "  java:\n    steps:\n      - run: exit 0\n",
            "shallow selector checkout": workflow.replace("fetch-depth: 2", "fetch-depth: 1"),
            "unexecuted output source": workflow.replace("steps.select.outputs.jobs", "steps.absent.outputs.jobs"),
            "conditional selector": workflow.replace("        id: select\n", "        id: select\n        if: false\n"),
            "conditional selector job": workflow.replace("  ci-selection:\n", "  ci-selection:\n    if: false\n"),
            "master also requires selector success": workflow.replace("!cancelled() && (github.event_name", "success() && (github.event_name", 1),
            "cancelled work starts": workflow.replace("!cancelled()", "always()", 1),
            "selection bypass": workflow.replace("['java']", "['python']"),
            "selector failure bypass": workflow.replace("needs.ci-selection.result == 'success'", "true", 1),
            "conditional result": workflow.replace("    if: always()\n", "    if: success()\n"),
            "missing aggregate dependency": workflow.replace("    needs: [ci-selection, " + ", ".join(sorted(EXISTING_JOBS)) + "]",
                                                                "    needs: [ci-selection, java]"),
            "missing aggregate input": workflow.replace("CI_NEEDS_JSON:", "UNRELATED_JSON:"),
            "conditional result check": workflow.replace("      - run: python3 tools/ci_job_selection.py check\n",
                                                          "      - run: python3 tools/ci_job_selection.py check\n        if: false\n"),
            "suppressed result failure": workflow.replace("      - run: python3 tools/ci_job_selection.py check\n",
                                                           "      - run: python3 tools/ci_job_selection.py check\n        continue-on-error: true\n"),
            "privileged event": workflow.replace("  pull_request:\n", "  pull_request_target:\n"),
            "workflow path filter": workflow.replace("  pull_request:\n", "  pull_request:\n    paths: ['tools/**']\n"),
            "PR branch filter": workflow.replace("  pull_request:\n", "  pull_request:\n    branches: [other]\n"),
            "manual run removed": workflow.replace("  workflow_dispatch:\n", ""),
            "master push removed": workflow.replace("branches: [master]", "branches: [other]"),
            "write token": workflow.replace("contents: read", "contents: write"),
            "job write token": workflow.replace("  java:\n", "  java:\n    permissions:\n      contents: write\n"),
        }
        for name, mutation in mutations.items():
            with self.subTest(mutation=name):
                self.assertNotEqual(workflow, mutation)
                with self.assertRaises((AssertionError, KeyError)):
                    self.assert_contract(mutation)

    def valid_workflow(self):
        lines = ["name: CI", "on:", "  push:", "    branches: [master]", "  pull_request:",
                 "  workflow_dispatch:", "permissions:", "  contents: read", "jobs:",
                 "  ci-selection:", "    runs-on: ubuntu-24.04", "    outputs:",
                 "      jobs: ${{ steps.select.outputs.jobs }}", "    steps:",
                 "      - uses: actions/checkout@v7", "        with:", "          fetch-depth: 2",
                 "      - uses: actions/setup-python@v7", "        with:", '          python-version: "3.12"',
                 "      - run: python3 tools/ci_job_selection.py select", "        id: select"]
        for name in sorted(EXISTING_JOBS):
            lines.extend([f"  {name}:", "    needs: ci-selection",
                          "    if: ${{ !cancelled() && (github.event_name != 'pull_request' || "
                          "(needs.ci-selection.result == 'success' && "
                          f"fromJSON(needs.ci-selection.outputs.jobs)['{name}']))" + " }}",
                          "    runs-on: ubuntu-24.04", "    steps:", "      - run: exit 0"])
        lines.extend(["  ci-result:", "    name: CI result", "    if: always()",
                      "    needs: [ci-selection, " + ", ".join(sorted(EXISTING_JOBS)) + "]",
                      "    runs-on: ubuntu-24.04", "    env:", "      CI_NEEDS_JSON: ${{ toJSON(needs) }}",
                      "    steps:", "      - uses: actions/checkout@v7", "      - uses: actions/setup-python@v7",
                      "      - run: python3 tools/ci_job_selection.py check", ""])
        return "\n".join(lines)


if __name__ == "__main__":
    unittest.main()
