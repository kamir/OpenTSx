from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


class RepositoryGitlinksTest(unittest.TestCase):
    def test_each_tracked_gitlink_has_a_submodule_url(self):
        result = subprocess.run(["git", "ls-files", "--stage", "-z"], cwd=ROOT,
                                check=True, capture_output=True, text=True)
        links = [entry.split("\t", 1)[1] for entry in result.stdout.split("\0")
                 if entry.startswith("160000 ")]
        for path in links:
            with self.subTest(path=path):
                modules = ROOT / ".gitmodules"
                self.assertTrue(modules.is_file(), f"Gitlink has no .gitmodules entry: {path}")
                entries = subprocess.run(["git", "config", "--file", str(modules), "--get-regexp",
                                          r"^submodule\..*\.path$"], cwd=ROOT,
                                         capture_output=True, text=True)
                self.assertEqual(0, entries.returncode, f"Cannot read submodule paths for {path}")
                names = [line.split(None, 1)[0][:-len(".path")]
                         for line in entries.stdout.splitlines()
                         if len(line.split(None, 1)) == 2 and line.split(None, 1)[1] == path]
                self.assertEqual(1, len(names), f"Gitlink needs exactly one submodule path entry: {path}")
                url = subprocess.run(["git", "config", "--file", str(modules), "--get", names[0] + ".url"],
                                     cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(0, url.returncode, f"Gitlink has no submodule URL: {path}")
                self.assertTrue(url.stdout.strip(), f"Gitlink has an empty submodule URL: {path}")


if __name__ == "__main__":
    unittest.main()
