from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
PRIVATE_REPOSITORY = "opentsx" + "-meta([^a-z0-9]|$)"


class PublicScopeTest(unittest.TestCase):
    def test_no_tracked_file_points_to_the_private_repository(self):
        found = subprocess.run(["git", "grep", "-l", "-i", "-I", "-E", PRIVATE_REPOSITORY], cwd=ROOT,
                               capture_output=True, text=True)
        self.assertIn(found.returncode, (0, 1), found.stderr)
        self.assertEqual([], found.stdout.split())


if __name__ == "__main__":
    unittest.main()
