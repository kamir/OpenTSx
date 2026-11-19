from pathlib import Path
import re
import unittest

import ci_job_selection as selection


ROOT = Path(__file__).resolve().parents[1]


class StandalonePackageSelectionTests(unittest.TestCase):
    def test_every_package_input_selects_the_real_package_acceptance(self):
        for path in (
            "deploy/local-cp/workbench.py",
            "deploy/local-cp/compose.finite.yaml",
            "deploy/local-cp/finite-smoke.mjs",
            "python-package/opentsx/client/research.py",
            "opentsx-saas-frontend/src/workbench/ModellingPanel.tsx",
        ):
            with self.subTest(path=path):
                jobs = selection.select_jobs(ROOT, [path])
                self.assertIn("standalone-analysis", jobs)
                self.assertTrue(jobs["standalone-analysis"])

    def test_documentation_does_not_schedule_the_container_package(self):
        jobs = selection.select_jobs(ROOT, ["README.md"])
        self.assertIn("standalone-analysis", jobs)
        self.assertFalse(jobs["standalone-analysis"])

    def test_shared_changes_keep_the_package_in_full_validation(self):
        jobs = selection.select_jobs(ROOT, [".github/workflows/ci.yml"])
        self.assertIn("standalone-analysis", jobs)
        self.assertTrue(jobs["standalone-analysis"])

    def test_workflow_runs_and_requires_package_acceptance(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("\n  standalone-analysis:\n", workflow)
        package = re.split(
            r"\n(?=  [a-z][a-z0-9-]*:\n)",
            workflow.split("\n  standalone-analysis:\n", 1)[1],
            1,
        )[0]
        self.assertIn("needs: ci-selection", package)
        self.assertIn("outputs.jobs)['standalone-analysis']", package)
        self.assertIn("runs-on: ubuntu-24.04-arm", package)
        self.assertIn("--profile finite", package)
        self.assertIn("finite-smoke.mjs", package)
        self.assertIn("include-hidden-files: true", package)
        self.assertIn("deploy/local-cp/.build/bundles/*/manifest.json", package)
        result = workflow.split("\n  ci-result:\n", 1)[1]
        self.assertIn("standalone-analysis", result.split("\n    runs-on:", 1)[0])


if __name__ == "__main__":
    unittest.main()
