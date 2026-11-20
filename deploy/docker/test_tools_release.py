from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
HELPER = Path(__file__).with_name("check_tools_release.py")
NAMESPACE = 'xmlns="http://maven.apache.org/POM/4.0.0"'


class ToolsReleaseTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("check_tools_release", HELPER)
        self.helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.helper)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.project = (
            f"<project {NAMESPACE}><groupId>org.opentsx</groupId>"
            "<artifactId>opentsx</artifactId><version>3.1.0</version>"
            "<modules><module>first</module><module>second</module></modules></project>"
        )
        self.child = (
            f"<project {NAMESPACE}><parent><groupId>org.opentsx</groupId>"
            "<artifactId>opentsx</artifactId><version>3.1.0</version></parent>"
            "<artifactId>opentsx-first</artifactId><dependencies><dependency>"
            "<groupId>org.opentsx</groupId><artifactId>opentsx-second</artifactId>"
            "<version>${project.version}</version></dependency></dependencies></project>"
        )
        (self.root / "pom.xml").write_text(self.project)
        (self.root / "first").mkdir()
        (self.root / "second").mkdir()
        (self.root / "first/pom.xml").write_text(self.child)
        (self.root / "second/pom.xml").write_text(
            self.child.replace("opentsx-first", "opentsx-second")
        )
        self.staged = self.root / "stage"
        self.jar = (
            self.staged
            / "opt/opentsx/lib/m2/org/opentsx/opentsx-first/3.1.0/opentsx-first-3.1.0.jar"
        )

    def stage(self, properties=None):
        self.jar.parent.mkdir(parents=True, exist_ok=True)
        if properties is None:
            properties = "# Maven identity\ngroupId=org.opentsx\nartifactId=opentsx-first\nversion=3.1.0\n"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr(
                "META-INF/maven/org.opentsx/opentsx-first/pom.properties", properties
            )

    def test_source_accepts_inherited_and_explicit_current_versions(self):
        self.assertEqual("3.1.0", self.helper.verify_release(self.root, "v3.1.0"))
        child = self.child.replace("${project.version}", "3.1.0").replace(
            "<dependencies>", "<version>3.1.0</version><dependencies>"
        )
        (self.root / "first/pom.xml").write_text(child)
        self.assertEqual("3.1.0", self.helper.verify_release(self.root))

    def test_nonstable_root_versions_and_mismatched_tags_fail(self):
        for version in (
            "dev",
            "3.1",
            "03.1.0",
            "3.1.0-rc1",
            "3.1.0-beta",
            "3.1.0-SNAPSHOT",
            "3.1.0+build",
            "${revision}",
        ):
            with self.subTest(version=version):
                (self.root / "pom.xml").write_text(
                    self.project.replace("3.1.0", version)
                )
                with self.assertRaises(ValueError):
                    self.helper.verify_release(self.root)
        (self.root / "pom.xml").write_text(self.project)
        for tag in ("3.1.0", "v3.0.1", "v3.1.0-rc1", ""):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.helper.verify_release(self.root, tag)

    def test_missing_duplicate_escaping_and_symbolic_modules_fail(self):
        for module in (
            "",
            "../escape",
            "/outside",
            "first/../second",
            "first\\second",
            "first",
        ):
            project = self.project.replace(
                "<module>second</module>", f"<module>{module}</module>"
            )
            with self.subTest(module=module):
                (self.root / "pom.xml").write_text(project)
                with self.assertRaises(ValueError):
                    self.helper.verify_release(self.root)
        (self.root / "pom.xml").write_text(self.project)
        (self.root / "first/pom.xml").unlink()
        (self.root / "first/pom.xml").symlink_to(self.root / "second/pom.xml")
        with self.assertRaises(ValueError):
            self.helper.verify_release(self.root)

    def test_parent_own_version_group_and_artifact_drift_fail(self):
        changes = (
            self.child.replace("<version>3.1.0</version>", "<version>3.0.1</version>"),
            self.child.replace(
                "<dependencies>", "<version>3.1.0-SNAPSHOT</version><dependencies>"
            ),
            self.child.replace(
                "<groupId>org.opentsx</groupId>", "<groupId>other</groupId>", 1
            ),
            self.child.replace(
                "<artifactId>opentsx</artifactId>", "<artifactId>other</artifactId>", 1
            ),
            self.child.replace(
                "</parent>", "<relativePath>../../pom.xml</relativePath></parent>"
            ),
            self.child.replace("opentsx-first", "opentsx-second"),
            self.child.replace(
                "<dependencies>", "<groupId>other</groupId><dependencies>"
            ),
        )
        for child in changes:
            with self.subTest(child=child):
                (self.root / "first/pom.xml").write_text(child)
                with self.assertRaises(ValueError):
                    self.helper.verify_release(self.root)

    def test_internal_dependencies_reject_unstable_unknown_and_local_overrides(self):
        for version in (
            "3.0.1",
            "3.1.0-SNAPSHOT",
            "3.1.0-rc1",
            "v0.0.0-20261120-abcdef",
            "[3,4)",
            "${unknown}",
        ):
            with self.subTest(version=version):
                (self.root / "first/pom.xml").write_text(
                    self.child.replace("${project.version}", version)
                )
                with self.assertRaises(ValueError):
                    self.helper.verify_release(self.root)
        changes = (
            self.child.replace("<version>${project.version}</version>", ""),
            self.child.replace(
                "<artifactId>opentsx-second</artifactId>",
                "<artifactId>unknown</artifactId>",
            ),
            self.child.replace(
                "<groupId>org.opentsx</groupId>", "<groupId>${unknown}</groupId>"
            ),
            self.child.replace("</dependency>", "<scope>system</scope></dependency>"),
            self.child.replace(
                "</dependency>", "<systemPath>/outside.jar</systemPath></dependency>"
            ),
        )
        for child in changes:
            with self.subTest(child=child):
                (self.root / "first/pom.xml").write_text(child)
                with self.assertRaises(ValueError):
                    self.helper.verify_release(self.root)

    def test_staged_known_artifacts_require_matching_embedded_identity(self):
        self.stage()
        self.assertEqual(
            "3.1.0", self.helper.verify_release(self.root, "v3.1.0", self.staged)
        )
        original = "groupId=org.opentsx\nartifactId=opentsx-first\nversion=3.1.0\n"
        changes = (
            original.replace("3.1.0", "3.0.1"),
            original.replace("3.1.0", "3.1.0-rc1"),
            original.replace("org.opentsx", "other"),
            original.replace("opentsx-first", "unknown"),
            original + "version=3.1.0\n",
            original.replace("version=3.1.0\n", ""),
            original + "unexpected=value\n",
            "malformed property",
        )
        for properties in changes:
            with self.subTest(properties=properties):
                self.stage(properties)
                with self.assertRaises(ValueError):
                    self.helper.verify_release(self.root, staged=self.staged)

    def test_staged_missing_empty_corrupt_symbolic_and_wrong_paths_fail(self):
        with self.assertRaises(ValueError):
            self.helper.verify_release(self.root, staged=self.staged)
        self.stage()
        self.jar.unlink()
        with self.assertRaises(ValueError):
            self.helper.verify_release(self.root, staged=self.staged)
        self.jar.write_bytes(b"not a JAR")
        with self.assertRaises(zipfile.BadZipFile):
            self.helper.verify_release(self.root, staged=self.staged)
        for name in ("other.jar", "opentsx-first-3.1.0-rc1.jar"):
            self.jar.rename(self.jar.with_name(name))
            with self.assertRaises(ValueError):
                self.helper.verify_release(self.root, staged=self.staged)
            self.jar.with_name(name).rename(self.jar)
        self.jar.unlink()
        self.jar.symlink_to(self.root / "pom.xml")
        with self.assertRaises(ValueError):
            self.helper.verify_release(self.root, staged=self.staged)

    def test_staged_missing_duplicate_and_unknown_maven_identity_fail(self):
        self.stage()
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/other", "missing")
        with self.assertRaises(ValueError):
            self.helper.verify_release(self.root, staged=self.staged)
        self.stage()
        with zipfile.ZipFile(self.jar, "a") as archive, self.assertWarns(UserWarning):
            archive.writestr(
                "META-INF/maven/org.opentsx/opentsx-first/pom.properties", "duplicate"
            )
        with self.assertRaises(ValueError):
            self.helper.verify_release(self.root, staged=self.staged)
        self.stage()
        unknown = self.jar.parents[2] / "unknown/3.1.0/unknown-3.1.0.jar"
        unknown.parent.mkdir(parents=True)
        unknown.write_bytes(self.jar.read_bytes())
        with self.assertRaises(ValueError):
            self.helper.verify_release(self.root, staged=self.staged)

    def test_cli_prints_only_version_and_explains_failures_and_help(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(self.helper, "ROOT", self.root), redirect_stdout(
            stdout
        ), redirect_stderr(stderr):
            self.helper.main(["--tag", "v3.1.0"])
        self.assertEqual("3.1.0\n", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())
        with patch.object(self.helper, "ROOT", self.root), redirect_stderr(
            stderr
        ), self.assertRaises(SystemExit) as failure:
            self.helper.main(["--tag", "v3.0.1"])
        self.assertEqual(1, failure.exception.code)
        self.assertIn("tools release check failed:", stderr.getvalue())
        with redirect_stdout(stdout), self.assertRaises(SystemExit) as help_exit:
            self.helper.main(["--help"])
        self.assertEqual(0, help_exit.exception.code)
        self.assertIn("--staged", stdout.getvalue())

    def test_workflow_version_is_not_contaminated_by_maven_capture_stdout(self):
        workflow = (ROOT / ".github/workflows/image.yml").read_text()
        block = workflow.split("      - name: Version\n", 1)[1].split(
            "      - name: Build jars\n", 1
        )[0]
        script = "\n".join(
            line[10:] for line in block.split("        run: |\n", 1)[1].splitlines()
        )
        version = self.helper.verify_release(ROOT)
        binary = self.root / "bin"
        binary.mkdir()
        maven = binary / "mvn"
        maven.write_text(
            f"#!/bin/sh\nprintf 'Maven HTTP response capture enabled\\n{version}'\n"
        )
        maven.chmod(0o755)
        output = self.root / "github-output"
        result = subprocess.run(
            ["bash", "-e", "-c", script],
            cwd=ROOT,
            capture_output=True,
            text=True,
            env=dict(
                os.environ,
                PATH=str(binary) + os.pathsep + os.environ["PATH"],
                GITHUB_REF_TYPE="tag",
                GITHUB_REF_NAME="v" + version,
                GITHUB_OUTPUT=str(output),
            ),
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("version=" + version + "\n", output.read_text())


if __name__ == "__main__":
    unittest.main()
