import fnmatch
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = ("config/private/*", "*/config/private/*", ".DS_Store", "*/.DS_Store", ".idea/workspace.xml",
             "*/.idea/workspace.xml")


class RepoHygieneTest(unittest.TestCase):
    def test_private_and_local_files_are_not_tracked(self):
        tracked = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True).stdout
        paths = [p for p in tracked.decode("utf-8").split("\0") if p]
        self.assertGreater(len(paths), 0)
        offending = sorted(p for p in paths if any(fnmatch.fnmatchcase(p, pattern) for pattern in FORBIDDEN))
        self.assertEqual([], offending)


if __name__ == "__main__":
    unittest.main()
