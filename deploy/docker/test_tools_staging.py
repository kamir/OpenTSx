import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


class ToolsStagingTests(unittest.TestCase):
    def test_classpath_plugin_uses_a_pinned_stable_version(self):
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        plugins = (
            ET.parse(ROOT / "pom.xml")
            .getroot()
            .findall("m:build/m:pluginManagement/m:plugins/m:plugin", namespace)
        )
        versions = [
            plugin.findtext("m:version", namespaces=namespace)
            for plugin in plugins
            if plugin.findtext("m:artifactId", namespaces=namespace)
            == "maven-dependency-plugin"
        ]
        self.assertEqual(1, len(versions))
        self.assertRegex(versions[0], r"^[0-9]+\.[0-9]+\.[0-9]+$")

    def test_runtime_classpath_excludes_provided_and_test_dependencies(self):
        commands = [
            line.strip()
            for line in (ROOT / "deploy/docker/stage.sh").read_text().splitlines()
            if "dependency:build-classpath" in line
        ]
        self.assertEqual(1, len(commands))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "image context with spaces"
            binary = root / "bin"
            binary.mkdir(parents=True)
            runtime = root / "runtime.jar"
            provided = root / "provided.jar"
            test = root / "opentsx-store-spi-3.1.0-tests.jar"
            for dependency in (runtime, provided, test):
                dependency.touch()
            maven = binary / "mvn"
            maven.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, pathlib, sys\n"
                "args = sys.argv[1:]\n"
                "assert 'dependency:build-classpath' in args\n"
                "assert args[args.index('-pl') + 1] == 'opentsx-tools-fixture'\n"
                "properties = dict(value[2:].split('=', 1) for value in args if value.startswith('-D'))\n"
                "dependencies = [os.environ['OPENTSX_TEST_RUNTIME']]\n"
                "if properties.get('includeScope') != 'runtime':\n"
                "    dependencies += [os.environ['OPENTSX_TEST_PROVIDED'], os.environ['OPENTSX_TEST_DEPENDENCY']]\n"
                "pathlib.Path(properties['mdep.outputFile']).write_text(os.pathsep.join(dependencies))\n"
                "pathlib.Path(os.environ['OPENTSX_TEST_ARGUMENTS']).write_text(json.dumps(args))\n"
            )
            maven.chmod(0o755)
            output = root / "runtime classpath.txt"
            arguments = root / "arguments.json"
            result = subprocess.run(
                ["bash", "-e", "-c", commands[0]],
                capture_output=True,
                text=True,
                env=dict(
                    os.environ,
                    PATH=str(binary) + os.pathsep + os.environ["PATH"],
                    ROOT=str(root),
                    m="opentsx-tools-fixture",
                    raw=str(output),
                    OPENTSX_TEST_RUNTIME=str(runtime),
                    OPENTSX_TEST_PROVIDED=str(provided),
                    OPENTSX_TEST_DEPENDENCY=str(test),
                    OPENTSX_TEST_ARGUMENTS=str(arguments),
                ),
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(
                "dependency:build-classpath", json.loads(arguments.read_text())
            )
            self.assertEqual([str(runtime)], output.read_text().split(os.pathsep))


if __name__ == "__main__":
    unittest.main()
