import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/image.yml"
ENTRYPOINT = ROOT / "deploy/docker/opentsx-entrypoint.sh"
CHECKSUM = "7d171def9b85846bf757a2cec94b7529371068a0670df14682447224e57983528e97a6d1b850327e4ca02b139abaab7fcb93c4315119e6f0ffb3f0cbc0d0b9a2"


class ImageWorkflowTest(unittest.TestCase):
    def test_image_build_reuses_verified_maven_transport_and_scoped_cache(self):
        workflow = WORKFLOW.read_text()
        build = workflow.split("\n  published-acceptance:\n", 1)[0]
        steps = re.split(r"\n(?=      - )", build)
        scope = next(
            (
                step
                for step in steps
                if "name: Scope Maven cache to its workload\n" in step
            ),
            "",
        )
        self.assertTrue(scope, "Image builds need a workload-specific Maven cache")
        setup = next(step for step in steps if "actions/setup-java@v6" in step)
        for value in (
            'test -n "$GITHUB_JOB"',
            "printf '%s\\n' \"$GITHUB_JOB\" > target/maven-downloads/cache-context",
        ):
            self.assertIn(value, scope)
        for value in (
            "**/pom.xml",
            "**/.mvn/wrapper/maven-wrapper.properties",
            "**/.mvn/extensions.xml",
            ".mvn/maven.config",
            ".mvn/ci-settings.xml",
            "tools/check_maven_downloads.py",
            "target/maven-downloads/cache-context",
        ):
            self.assertIn(value, setup)
        capture = next(
            (
                step
                for step in steps
                if "run: python3 tools/check_maven_http_capture.py" in step
            ),
            "",
        )
        self.assertTrue(capture, "Image builds need the verified CI repository mirror")
        before = next(
            step for step in steps if "target/maven-downloads/before.json" in step
        )
        after = next(
            step for step in steps if "target/maven-downloads/after.json" in step
        )
        upload = next(step for step in steps if "path: target/maven-downloads/" in step)
        build_jars = next(step for step in steps if "name: Build jars\n" in step)
        stage = next(step for step in steps if "run: deploy/docker/stage.sh" in step)
        for step in (scope, setup, capture, before):
            self.assertNotRegex(step, r"(?m)^        (?:if|continue-on-error):")
        self.assertLess(steps.index(scope), steps.index(setup))
        self.assertLess(steps.index(capture), steps.index(before))
        self.assertLess(steps.index(before), steps.index(build_jars))
        self.assertLess(steps.index(stage), steps.index(after))
        self.assertLess(steps.index(after), steps.index(upload))
        self.assertLess(build.index(upload), build.index("docker/login-action@v4"))
        for step in (after, upload):
            self.assertIn("if: always()", step)
        self.assertIn("if-no-files-found: error", upload)

    def test_container_dispatcher_preserves_demo_modes_and_spaced_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "installation with spaces"
            (home / "cp").mkdir(parents=True)
            (home / "cp/opentsx-windpark.txt").write_text("lib/windpark.jar\n")
            fake_bin = root / "bin"
            fake_bin.mkdir()
            java = fake_bin / "java"
            java.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                "Path(os.environ['OPENTSX_TEST_INVOCATION']).write_text(json.dumps(sys.argv[1:]))\n"
            )
            java.chmod(0o755)
            invocation = root / "invocation.json"
            env = dict(
                os.environ,
                PATH=str(fake_bin) + os.pathsep + os.environ["PATH"],
                OPENTSX_HOME=str(home),
                OPENTSX_TEST_INVOCATION=str(invocation),
                OPENTSX_EXTRA_ARGS="",
                JAVA_OPTS="",
            )
            output = str(root / "demo output")
            for mode in ("run", "replay"):
                with self.subTest(mode=mode):
                    result = subprocess.run(
                        [
                            "bash",
                            str(ENTRYPOINT),
                            "windpark-demo",
                            mode,
                            "--output",
                            output,
                        ],
                        env=env,
                        text=True,
                        capture_output=True,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(
                        [
                            "-cp",
                            str(home / "lib/windpark.jar"),
                            "org.opentsx.windpark.WindparkDemo",
                            mode,
                            "--output",
                            output,
                        ],
                        json.loads(invocation.read_text()),
                    )

    def assert_contract(self, workflow):
        self.assertIn("\n  published-acceptance:\n", workflow)
        build, published = workflow.split("\n  published-acceptance:\n", 1)
        build = build.split("\n  opentsx-tools:\n", 1)[1]
        self.assertNotIn("continue-on-error:", workflow)
        self.assertIn('python-version: "3.12"', build)
        self.assertIn("actions/setup-python@v7", build)
        fast = "python3 -m unittest discover -s deploy/docker -p 'test_*.py' -v"
        for required in (
            fast,
            "mvn -B -ntp install",
            "python3 tools/check_maven_downloads.py",
            "name: Build the native candidate\n",
            "name: Verify candidate startup and replay\n",
            "id: publish\n",
            "python3 deploy/docker/check_tools_release.py",
        ):
            self.assertIn(required, build)
        self.assertLess(build.index(fast), build.index("mvn -B -ntp install"))
        self.assertIn("Install Maven 3.9.8", build)
        self.assertIn("apache-maven-3.9.8-bin.tar.gz", build)
        self.assertIn(CHECKSUM, build)
        self.assertIn("sha512sum --check", build)
        self.assertIn(
            'echo "$RUNNER_TEMP/apache-maven-3.9.8/bin" >> "$GITHUB_PATH"', build
        )
        self.assertLess(
            build.index("Install Maven 3.9.8"),
            build.index("Verify Maven download retries"),
        )
        self.assertLess(
            build.index("python3 tools/check_maven_downloads.py"),
            build.index("python3 deploy/docker/check_tools_release.py"),
        )
        steps = re.split(r"\n(?=      - )", build)
        source_gate = next(step for step in steps if "name: Version\n" in step)
        staged_gate = next(
            step for step in steps if "name: Verify staged release versions\n" in step
        )
        build_jars = next(step for step in steps if "name: Build jars\n" in step)
        stage = next(step for step in steps if "run: deploy/docker/stage.sh" in step)
        for step in (source_gate, staged_gate):
            self.assertNotRegex(step, r"(?m)^        (?:if|continue-on-error):")
        self.assertIn('args=(--tag "$GITHUB_REF_NAME")', source_gate)
        self.assertIn('check_tools_release.py "${args[@]}"', source_gate)
        self.assertIn(
            "run: python3 deploy/docker/check_tools_release.py --staged deploy/docker/.stage",
            staged_gate,
        )
        self.assertLess(steps.index(source_gate), steps.index(build_jars))
        self.assertLess(steps.index(stage), steps.index(staged_gate))
        self.assertLess(
            build.index(staged_gate), build.index("name: Build the native candidate\n")
        )
        candidate = next(
            step for step in steps if "name: Build the native candidate\n" in step
        )
        verify = next(
            step
            for step in steps
            if "name: Verify candidate startup and replay\n" in step
        )
        publish = next(step for step in steps if "id: publish\n" in step)
        fast_step = next(step for step in steps if fast in step)
        installer = next(
            step for step in steps if "name: Install Maven 3.9.8\n" in step
        )
        retries = next(
            step for step in steps if "name: Verify Maven download retries\n" in step
        )
        for step in (candidate, verify, fast_step, installer, retries):
            self.assertNotRegex(step, r"(?m)^        if:")
        self.assertIn("platforms: linux/amd64\n", candidate)
        self.assertIn("load: true", candidate)
        self.assertIn("push: false", candidate)
        self.assertIn(
            "org.opencontainers.image.version=${{ steps.version.outputs.version }}",
            candidate,
        )
        self.assertIn("org.opencontainers.image.revision=${{ github.sha }}", candidate)
        self.assertIn(
            "docker image inspect --format '{{.Id}}' opentsx-tools:acceptance", verify
        )
        self.assertIn('check_tools_image.py --image "$image_id"', verify)
        self.assertIn('--version "$OPENTSX_VERSION" --revision "$GITHUB_SHA"', verify)
        self.assertLess(build.index(candidate), build.index(verify))
        self.assertLess(build.index(verify), build.index("docker/login-action@v4"))
        self.assertLess(build.index(verify), build.index(publish))
        self.assertIn("platforms: linux/amd64,linux/arm64", publish)
        self.assertIn("push: ${{ github.ref_type == 'tag' }}", publish)
        self.assertIn("provenance: true", publish)
        self.assertIn("sbom: true", publish)
        self.assertIn("version: ${{ steps.version.outputs.version }}", build)
        self.assertIn("digest: ${{ steps.publish.outputs.digest }}", build)
        self.assertIn("needs: opentsx-tools", published)
        self.assertIn("if: github.ref_type == 'tag'", published)
        self.assertIn("runs-on: ${{ matrix.runner }}", published)
        for runner, arch in (("ubuntu-24.04", "amd64"), ("ubuntu-24.04-arm", "arm64")):
            self.assertIn(
                f"- runner: {runner}\n            platform: linux/{arch}\n            arch: {arch}",
                published,
            )
        self.assertIn(
            "OPENTSX_IMAGE: ghcr.io/kamir/opentsx-tools@${{ needs.opentsx-tools.outputs.digest }}",
            published,
        )
        self.assertIn(
            "OPENTSX_VERSION: ${{ needs.opentsx-tools.outputs.version }}", published
        )
        self.assertIn("OPENTSX_PLATFORM: ${{ matrix.platform }}", published)
        self.assertIn('check_tools_image.py --image "$OPENTSX_IMAGE"', published)
        self.assertIn(
            '--platform "$OPENTSX_PLATFORM" --version "$OPENTSX_VERSION" --revision "$GITHUB_SHA"',
            published,
        )
        self.assertNotRegex(published, r"\bmvn\b|stage\.sh|build-push-action")
        self.assertIn("packages: read", published)
        for block, path in ((build, "candidate"), (published, "published")):
            upload = next(
                step
                for step in re.split(r"\n(?=      - )", block)
                if "uses: actions/upload-artifact@v7\n" in step
            )
            self.assertIn("if: always()", upload)
            self.assertIn(f"path: target/image-acceptance/{path}/", upload)
            if path == "candidate":
                self.assertLess(build.index(upload), build.index(publish))

    def test_workflow_requires_candidate_acceptance_and_downloads_published_digest_natively(
        self,
    ):
        self.assert_contract(WORKFLOW.read_text())

    def test_workflow_contract_rejects_bypassed_checks_mutable_references_and_missing_platforms(
        self,
    ):
        workflow = WORKFLOW.read_text()
        self.assert_contract(workflow)
        mutations = {
            "bypassed acceptance": workflow.replace(
                "name: Verify candidate startup and replay\n",
                "name: Verify candidate startup and replay\n        if: false\n",
            ),
            "ignored failure": workflow.replace(
                "name: Build the native candidate\n",
                "name: Build the native candidate\n        continue-on-error: true\n",
            ),
            "wrong Maven checksum": workflow.replace(CHECKSUM, "0" * len(CHECKSUM)),
            "missing retry verification": workflow.replace(
                "python3 tools/check_maven_downloads.py", "true"
            ),
            "bypassed staged release gate": workflow.replace(
                "name: Verify staged release versions\n",
                "name: Verify staged release versions\n        if: false\n",
            ),
            "lost staged release verification": workflow.replace(
                "check_tools_release.py --staged deploy/docker/.stage",
                "check_tools_release.py",
            ),
            "lost tag verification": workflow.replace(
                'args=(--tag "$GITHUB_REF_NAME")', "args=()"
            ),
            "mutable registry reference": workflow.replace(
                "opentsx-tools@${{ needs.opentsx-tools.outputs.digest }}",
                "opentsx-tools:${{ needs.opentsx-tools.outputs.version }}",
            ),
            "missing digest output": workflow.replace(
                "digest: ${{ steps.publish.outputs.digest }}", "digest: ignored"
            ),
            "emulated ARM runner": workflow.replace(
                "runner: ubuntu-24.04-arm", "runner: ubuntu-24.04"
            ),
            "lost failure evidence": workflow.replace("if: always()", "if: success()"),
        }
        for name, mutated in mutations.items():
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.assert_contract(mutated)


if __name__ == "__main__":
    unittest.main()
