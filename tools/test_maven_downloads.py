import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_maven_downloads as check


class MavenDownloadCheckTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        self.libraries = self.home / "lib"
        self.libraries.mkdir()
        for name in ("maven-core-3.9.8.jar", "maven-resolver-transport-http-1.9.20.jar"):
            (self.libraries / name).touch()
        self.banner = f"Apache Maven 3.9.8 (revision)\nMaven home: {self.home}\n"

    def test_active_distribution_compiles_and_executes_the_actual_transport_checks(self):
        for banner in (self.banner, "\x1b[1m" + self.banner + "\x1b[0m"):
            output = io.StringIO()
            with self.subTest(banner=banner), patch.object(check.subprocess, "run", return_value=
                    subprocess.CompletedProcess([], 0, stdout=banner)) as execute, \
                    contextlib.redirect_stdout(output):
                check.verify()
                self.assertIn("Maven 3.9.8; Maven Resolver 1.9.20", output.getvalue())
                commands = [call.args[0] for call in execute.call_args_list]
                self.assertEqual(["mvn", "--version"], commands[0])
                self.assertEqual(["javac", "-cp", str(self.libraries / "*"), "-d"], commands[1][:4])
                self.assertEqual(["--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror"], commands[1][5:-1])
                self.assertEqual(str(check.ROOT / "tools/MavenDownloadRetryTest.java"), commands[1][-1])
                self.assertEqual(["java", "-cp"], commands[2][:2])
                self.assertEqual(os.pathsep.join((commands[1][4], str(self.libraries / "*"))), commands[2][2])
                self.assertEqual(["MavenDownloadRetryTest", str(check.ROOT / ".mvn/maven.config")], commands[2][3:])
                self.assertTrue(all(call.kwargs["check"] for call in execute.call_args_list))

    def test_unknown_or_different_active_maven_versions_fail_before_compilation(self):
        for banner in ("", self.banner.replace("3.9.8", "3.10.0"),
                       self.banner.replace("Maven home:", "Unknown:"),
                       self.banner.replace(str(self.home), "relative/path")):
            with self.subTest(banner=banner), patch.object(check.subprocess, "run", return_value=
                    subprocess.CompletedProcess([], 0, stdout=banner)) as execute:
                with self.assertRaises(ValueError):
                    check.verify()
                self.assertEqual(1, execute.call_count)

    def test_incomplete_distribution_fails_before_compilation(self):
        (self.libraries / "maven-resolver-transport-http-1.9.20.jar").unlink()
        with patch.object(check.subprocess, "run", return_value=
                subprocess.CompletedProcess([], 0, stdout=self.banner)) as execute:
            with self.assertRaisesRegex(ValueError, "incomplete"):
                check.verify()
            self.assertEqual(1, execute.call_count)

    def test_maven_compiler_and_native_policy_failures_are_not_retried_or_ignored(self):
        success = subprocess.CompletedProcess([], 0, stdout=self.banner)
        for index in range(3):
            outcomes = [success] * index + [subprocess.CalledProcessError(17, ["failure"])]
            with self.subTest(index=index), patch.object(check.subprocess, "run", side_effect=outcomes) as execute, \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(subprocess.CalledProcessError):
                    check.verify()
                self.assertEqual(index + 1, execute.call_count)

    def test_cli_reports_missing_tools_and_policy_failures_as_errors(self):
        for error in (FileNotFoundError("missing executable"), ValueError("invalid policy"),
                      subprocess.CalledProcessError(17, ["failure"])):
            output = io.StringIO()
            with self.subTest(error=type(error).__name__), patch.object(check, "verify", side_effect=error), \
                    contextlib.redirect_stderr(output):
                self.assertEqual(1, check.main())
            self.assertIn("Maven download retry verification failed:", output.getvalue())


if __name__ == "__main__":
    unittest.main()
