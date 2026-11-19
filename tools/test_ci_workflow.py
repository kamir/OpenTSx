from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
JOBS = {
    "operator-learning", "execution-contracts", "rust-runner", "rust-runner-native",
    "rust-runner-static", "rust-b1-acceptance", "rust-runner-compose", "wayang-rust-compose",
    "java", "python", "saas-frontend", "saas-backend", "deploy", "kafscale-conformance",
    "cross-language", "documentation",
}
DEMO_JOBS = {"operator-learning", "saas-frontend", "documentation"}
FULL_CONDITION = "${{ !cancelled() && (needs.ci-selection.result != 'success' || needs.ci-selection.outputs.scope != 'demo') }}"


class CiWorkflowTest(unittest.TestCase):
    def test_change_selector_and_always_completion_are_present(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        jobs = text.split("\njobs:\n", 1)[1]
        self.assertTrue(re.search(r"(?m)^  ci-selection:[ \t]*$", jobs), "The CI change selector is missing")
        complete = re.search(r"^  ci-complete:[ \t]*\n(.*?)(?=^  \S|\Z)", jobs, re.M | re.S)
        self.assertIsNotNone(complete, "The CI completion gate is missing")
        self.assertTrue(re.search(r"(?m)^    if: always\(\)[ \t]*$", complete.group(1)),
                        "The completion gate must run after failed or skipped dependencies")

    def blocks(self, text):
        parts = re.split(r"(?m)^jobs:[ \t]*$", text)
        self.assertEqual(2, len(parts), "Expected one jobs block")
        headers = list(re.finditer(r"^  ([^\s#][^:\r\n]*):[^\r\n]*$", parts[1], re.M))
        names = [header[1].strip() for header in headers]
        self.assertEqual(len(names), len(set(names)), "Duplicate CI job IDs")
        return {name: parts[1][header.end():headers[index + 1].start() if index + 1 < len(headers) else len(parts[1])]
                for index, (name, header) in enumerate(zip(names, headers))}

    def scalar(self, block, field):
        values = re.findall(r"^    " + re.escape(field) + r":[ \t]*(.*)$", block, re.M)
        self.assertEqual(1, len(values), f"Expected one job-level {field}")
        return values[0].strip()

    def assert_contract(self, text):
        jobs = self.blocks(text)
        self.assertEqual(JOBS | {"ci-selection", "ci-complete"}, set(jobs), "The job inventory changed")
        selector = jobs["ci-selection"]
        self.assertTrue(re.search(r"(?m)^          fetch-depth: 0[ \t]*$", selector),
                        "Selection needs complete Git history")
        self.assertTrue(re.search(r"(?m)^          filter: blob:none[ \t]*$", selector),
                        "Selection must omit historical blobs from the full-history fetch")
        for name, expression in {
            "CI_EVENT": "${{ github.event_name }}",
            "CI_BASE_SHA": "${{ github.event.pull_request.base.sha }}",
            "CI_HEAD_SHA": "${{ github.event.pull_request.head.sha }}",
        }.items():
            self.assertTrue(re.search(r"(?m)^          " + name + ": " + re.escape(expression) + r"[ \t]*$", selector),
                            f"{name} must use the immutable event value")
        for argument in ['--event "$CI_EVENT"', '--base "$CI_BASE_SHA"', '--head "$CI_HEAD_SHA"',
                         '--github-output "$GITHUB_OUTPUT"']:
            self.assertIn(argument, selector)
        self.assertIn("python3 tools/ci_scope.py select", selector)
        self.assertIn("test_ci_*.py", selector)
        self.assertTrue(re.search(r"(?m)^        id: scope[ \t]*$", selector))
        for output in ["scope", "selection"]:
            self.assertIn(f"      {output}: ${{{{ steps.scope.outputs.{output} }}}}", selector)
        for name in JOBS:
            self.assertEqual("ci-selection", self.scalar(jobs[name], "needs"), name)
            expected = "${{ !cancelled() }}" if name in DEMO_JOBS else FULL_CONDITION
            self.assertEqual(expected, self.scalar(jobs[name], "if"), name)
        complete = jobs["ci-complete"]
        self.assertEqual("always()", self.scalar(complete, "if"))
        needs = re.search(r"^    needs:[ \t]*\n((?:      - [A-Za-z_][A-Za-z0-9_-]*[ \t]*\n)+)", complete, re.M)
        self.assertIsNotNone(needs, "The completion gate must enumerate all dependencies")
        dependencies = re.findall(r"^      - ([A-Za-z_][A-Za-z0-9_-]*)[ \t]*$", needs[1], re.M)
        self.assertEqual(len(dependencies), len(set(dependencies)), "Duplicate gate dependencies")
        self.assertEqual(JOBS | {"ci-selection"}, set(dependencies))
        self.assertIn("CI_NEEDS_JSON: ${{ toJSON(needs) }}", complete)
        self.assertIn("run: python3 tools/ci_scope.py gate", complete)
        self.assertIn("run: python3 tools/check_links.py", jobs["documentation"])
        self.assertEqual(1, text.count("python3 tools/check_links.py"), "Run the link checker once per workflow")
        self.assertNotIn("continue-on-error:", selector + complete)
        self.assertFalse(re.search(r"(?m)^    paths(?:-ignore)?:", text.split("\njobs:\n", 1)[0]),
                         "The completion workflow must run for every pull request")

    def test_selection_conditions_immutable_inputs_and_gate_cover_every_job(self):
        self.assert_contract(WORKFLOW.read_text(encoding="utf-8"))

    def test_wrong_conditions_missing_dependencies_and_mutable_refs_are_rejected(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assert_contract(text)
        mutations = {
            "gate success only": text.replace("    if: always()", "    if: success()"),
            "missing Java gate dependency": text.replace("      - java\n", ""),
            "shallow history": text.replace("fetch-depth: 0", "fetch-depth: 1"),
            "historical blobs downloaded": text.replace("          filter: blob:none\n", ""),
            "mutable head": text.replace("github.event.pull_request.head.sha", "github.head_ref"),
            "mutable base": text.replace("github.event.pull_request.base.sha", "github.base_ref"),
            "selector failure skips full suite": text.replace(
                FULL_CONDITION, "${{ !cancelled() && needs.ci-selection.outputs.scope != 'demo' }}"),
            "unlisted new job": text + "\n  unlisted:\n    runs-on: ubuntu-24.04\n    steps: []\n",
            "unlisted underscore job": text + "\n  unlisted_job:\n    runs-on: ubuntu-24.04\n    steps: []\n",
            "unlisted uppercase job": text + "\n  UnlistedJob:\n    runs-on: ubuntu-24.04\n    steps: []\n",
            "unlisted quoted job": text + "\n  'quoted_job':\n    runs-on: ubuntu-24.04\n    steps: []\n",
            "unlisted inline job": text + "\n  inline_job: {runs-on: ubuntu-24.04, steps: []}\n",
            "missing results environment": text.replace("CI_NEEDS_JSON: ${{ toJSON(needs) }}", "CI_NEEDS_JSON: '{}'"),
            "conditional documentation": text.replace("    if: ${{ !cancelled() }}", "    if: " + FULL_CONDITION, 1),
            "missing gate validation": text.replace("run: python3 tools/ci_scope.py gate", "run: true"),
            "unknown output binding": text.replace("steps.scope.outputs.selection", "steps.scope.outputs.other"),
            "unknown dependency": text.replace("      - java\n", "      - unlisted\n"),
        }
        for name, mutation in mutations.items():
            self.assertNotEqual(text, mutation, f"The {name} fixture did not change the workflow")
            with self.subTest(mutation=name), self.assertRaises(AssertionError):
                self.assert_contract(mutation)


if __name__ == "__main__":
    unittest.main()
