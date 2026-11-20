import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_maven_http_capture as capture


class MavenHttpCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def test_ci_selects_its_verified_settings_without_copying_existing_arguments(self):
        output = self.directory / "environment"
        with patch.dict(os.environ, {"GITHUB_ENV": str(output), "MAVEN_OPTS": "", "MAVEN_ARGS": ""}):
            capture.enable_ci(Path("/safe/capture.jar"))
        values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual("--settings " + str(capture.ROOT / ".mvn/ci-settings.xml"), values.get("MAVEN_ARGS"))
        output.unlink()
        with patch.dict(os.environ, {"GITHUB_ENV": str(output), "MAVEN_OPTS": "",
                                     "MAVEN_ARGS": "private-cli-value"}):
            with self.assertRaisesRegex(ValueError, "reviewed") as error:
                capture.enable_ci(Path("/safe/capture.jar"))
        self.assertNotIn("private-cli-value", str(error.exception))
        self.assertFalse(output.exists())

    def test_ci_rejects_settings_paths_that_could_change_cli_arguments(self):
        for path in ("/safe/private settings", "/safe/private\nsettings"):
            with self.subTest(path=path), patch.object(capture, "ROOT", Path(path)), \
                    patch.dict(os.environ, {"GITHUB_ENV": str(self.directory / "environment"),
                                            "MAVEN_OPTS": "", "MAVEN_ARGS": ""}):
                with self.assertRaises(ValueError):
                    capture.enable_ci(Path("/safe/capture.jar"))

    def test_ci_enables_only_the_verified_extension_without_copying_existing_options(self):
        output = self.directory / "environment"
        with patch.dict(os.environ, {"GITHUB_ENV": str(output), "MAVEN_OPTS": "", "MAVEN_ARGS": ""}):
            capture.enable_ci(Path("/safe/capture.jar"))
        self.assertEqual("MAVEN_OPTS=-Dmaven.ext.class.path=/safe/capture.jar\nMAVEN_ARGS=--settings "
                         + str(capture.ROOT / ".mvn/ci-settings.xml") + "\n", output.read_text())
        output.unlink()
        with patch.dict(os.environ, {"GITHUB_ENV": str(output), "MAVEN_OPTS": "private-jvm-value", "MAVEN_ARGS": ""}):
            with self.assertRaisesRegex(ValueError, "reviewed") as error:
                capture.enable_ci(Path("/safe/capture.jar"))
        self.assertNotIn("private-jvm-value", str(error.exception))
        self.assertFalse(output.exists())

    def test_ci_rejects_extension_paths_that_could_change_jvm_arguments(self):
        for value in ("/safe/capture other.jar", "/safe/one.jar:other.jar", "/safe/capture\n.jar"):
            with self.subTest(value=value), patch.dict(os.environ, {
                    "GITHUB_ENV": str(self.directory / "environment"), "MAVEN_OPTS": "", "MAVEN_ARGS": ""}):
                with self.assertRaises(ValueError):
                    capture.enable_ci(Path(value))

    def test_offline_extension_fixture_requires_loading_failure_and_file_transport(self):
        success = ("Maven HTTP response capture enabled\nUsing transporter FileTransporter\n"
                   "org.apache.maven.plugin.PluginResolutionException\n")
        for code, text in ((1, success), (0, success), (1, success.replace("capture enabled", "missing")),
                           (1, success.replace("FileTransporter", "HttpTransporter")),
                           (1, success.replace("PluginResolutionException", "missing"))):
            with self.subTest(code=code, text=text), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(capture.subprocess, "run", return_value=
                    subprocess.CompletedProcess([], code, stdout=text, stderr="")) as execute:
                if code == 1 and text == success:
                    capture.verify_extension(self.directory / "capture.jar", Path(temporary))
                else:
                    with self.assertRaises(ValueError):
                        capture.verify_extension(self.directory / "capture.jar", Path(temporary))
                self.assertEqual(1, execute.call_count)
                command = execute.call_args.args[0]
                self.assertIn("--offline", command)
                self.assertIn("-Daether.offline.protocols=file", command)
                self.assertIn("--settings", command)
                self.assertIn("--global-settings", command)

    def test_offline_ci_settings_fixture_requires_mirrored_plugin_resolution(self):
        success = ("org.apache.maven.plugin.PluginResolutionException\nCannot access "
                   "google-maven-central (https://maven-central.storage-download.googleapis.com/maven2/) "
                   "in offline mode\n")
        for code, text in ((1, success), (0, success), (1, success.replace("google-maven-central", "central")),
                           (1, success.replace("in offline mode", "other failure")),
                           (1, success.replace("PluginResolutionException", "other failure"))):
            with self.subTest(code=code, text=text), patch.object(capture.subprocess, "run", return_value=
                    subprocess.CompletedProcess([], code, stdout=text, stderr="")) as execute:
                if code == 1 and text == success:
                    capture.verify_ci_mirror(self.directory)
                else:
                    with self.assertRaises(ValueError):
                        capture.verify_ci_mirror(self.directory)
                command = execute.call_args.args[0]
                self.assertIn("--offline", command)
                self.assertEqual(str(capture.ROOT / ".mvn/ci-settings.xml"), command[command.index("--settings") + 1])
                self.assertIn("--global-settings", command)

    def test_cli_does_not_ignore_build_or_verification_errors(self):
        for error in (FileNotFoundError("missing executable"), ValueError("invalid capture"),
                      subprocess.CalledProcessError(17, ["failure"])):
            output = io.StringIO()
            with self.subTest(error=type(error).__name__), patch.object(capture, "verify", side_effect=error), \
                    contextlib.redirect_stderr(output):
                self.assertEqual(1, capture.main())
            self.assertIn("Maven HTTP response capture verification failed:", output.getvalue())


if __name__ == "__main__":
    unittest.main()
