from pathlib import Path
import fnmatch
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
PRIVATE_REPOSITORY = "opentsx" + "-meta([^a-z0-9]|$)"
OWNER_ONLY = ("docs/research/*", "docs/business/*", "docs/roadmap/*", "archive/*", "EVOLUTION/*")
ALLOWED = {"EVOLUTION/TASK-020-wayang-ts-operators.md"}
MEASUREMENT_RECORDS = {"README.md", "REPORT.md", "metadata.json", "manifest.json", "test-evidence.json"}


def tracked():
    listed = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True).stdout
    return [path for path in listed.decode("utf-8").split("\0") if path]


class PublicScopeTest(unittest.TestCase):
    def test_no_tracked_file_points_to_the_private_repository(self):
        found = subprocess.run(["git", "grep", "-l", "-i", "-I", "-E", PRIVATE_REPOSITORY], cwd=ROOT,
                               capture_output=True, text=True)
        self.assertIn(found.returncode, (0, 1), found.stderr)
        self.assertEqual([], found.stdout.split())

    def test_research_planning_and_drafts_are_not_tracked(self):
        paths = tracked()
        self.assertIn("pom.xml", paths)
        offending = sorted(path for path in paths if path not in ALLOWED
                           and any(fnmatch.fnmatchcase(path, pattern) for pattern in OWNER_ONLY))
        self.assertEqual([], offending, "AGENTS.md rule 15: research, plans and drafts stay out of this repository")

    def test_measurement_runs_keep_only_reports_and_records(self):
        paths = [path for path in tracked() if path.startswith("docs/datamodel-2026/measurements/")]
        self.assertGreater(len(paths), 0)
        raw = sorted(path for path in paths if path.rsplit("/", 1)[-1] not in MEASUREMENT_RECORDS)
        self.assertEqual([], raw, "raw measurement data stays out of this repository")


if __name__ == "__main__":
    unittest.main()
