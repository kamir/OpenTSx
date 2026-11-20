import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPORT = Path(__file__).with_name("maven_cache_report.py")


class MavenCacheReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.directory = self.repository / "org/example/library/1.0"
        self.directory.mkdir(parents=True)
        self.output = self.root / "reports/cache.json"

    def run_report(self, repository=None, output=None):
        return subprocess.run([sys.executable, str(REPORT), str(repository or self.repository),
                               str(output or self.output)], capture_output=True, text=True)

    def test_missing_and_empty_repositories_are_distinguished(self):
        for repository, exists in ((self.root / "absent", False), (self.repository, True)):
            with self.subTest(exists=exists):
                result = self.run_report(repository)
                self.assertEqual(0, result.returncode, result.stderr)
                report = json.loads(self.output.read_text())
                self.assertEqual(exists, report["repository_exists"])
                self.assertEqual([], report["tracking_files"])

    def test_reports_cache_evidence_without_copying_credentials_or_error_text(self):
        marker = self.directory / "library-1.0.jar.lastUpdated"
        marker.write_text("# private metadata\nhttps\\://user\\:secret@example.invalid/.error=\n"
                          "https\\://example.invalid/.error=Could not transfer: status code: 429, token=secret\n"
                          "https\\://example.invalid/.lastUpdated=42\n", encoding="utf-8")
        artifact = marker.with_suffix("")
        artifact.write_bytes(b"artifact bytes")
        (self.directory / "_remote.repositories").write_text("# secret\nlibrary-1.0.jar>central=\n")
        result = self.run_report()
        self.assertEqual(0, result.returncode, result.stderr)
        text = self.output.read_text()
        self.assertNotIn("secret", text + result.stdout + result.stderr)
        self.assertNotIn("https", text)
        report = json.loads(text)
        self.assertEqual(2, len(report["tracking_files"]))
        entry = next(item for item in report["tracking_files"] if item["path"].endswith(".lastUpdated"))
        self.assertEqual([{"kind": "not-found", "http_status": None},
                          {"kind": "transfer-error", "http_status": 429}], entry["errors"])
        self.assertEqual("org/example/library/1.0/library-1.0.jar", entry["artifact"]["path"])
        self.assertEqual(artifact.stat().st_size, entry["artifact"]["size"])
        self.assertEqual(64, len(entry["sha256"]))
        self.assertEqual(64, len(entry["artifact"]["sha256"]))

    def test_changed_tracking_bytes_and_absent_artifacts_remain_visible(self):
        marker = self.directory / "library-1.0.pom.lastUpdated"
        reports = []
        for timestamp in ("42", "43"):
            marker.write_text("https\\://example.invalid/.error=\n"
                              "https\\://example.invalid/.lastUpdated=" + timestamp + "\n")
            result = self.run_report()
            self.assertEqual(0, result.returncode, result.stderr)
            reports.append(json.loads(self.output.read_text())["tracking_files"][0])
        self.assertNotEqual(reports[0]["sha256"], reports[1]["sha256"])
        self.assertIsNone(reports[0]["artifact"])
        self.assertIsNone(reports[1]["artifact"])
        self.assertEqual(1, len(list(self.directory.iterdir())))

    def test_symbolic_links_and_non_directory_inputs_fail_explicitly(self):
        outside = self.root / "outside"
        outside.write_text("secret")
        link = self.directory / "library-1.0.pom.lastUpdated"
        link.symlink_to(outside)
        for repository in (self.repository, outside):
            with self.subTest(repository=repository.name):
                result = self.run_report(repository)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Maven cache report failed:", result.stderr)
                self.assertNotIn("secret", result.stdout + result.stderr)
                self.assertFalse(self.output.exists())

    def test_output_cannot_modify_the_maven_repository(self):
        output = self.directory / "report.json"
        result = self.run_report(output=output)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Maven cache report failed:", result.stderr)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
