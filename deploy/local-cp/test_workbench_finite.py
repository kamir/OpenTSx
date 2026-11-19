import json
from pathlib import Path
import re
import unittest


class FiniteComposeTests(unittest.TestCase):
    def profile(self):
        path = Path(__file__).with_name("compose.finite.yaml")
        self.assertTrue(path.is_file(), "The finite Compose profile must exist")
        return path.read_text(encoding="utf-8")

    def test_finite_profile_contains_only_the_workbench_service(self):
        services = self.profile().split("\nnetworks:", 1)[0].split("\nvolumes:", 1)[0]
        self.assertEqual(
            re.findall(r"^  ([a-z0-9_-]+):$", services, re.M), ["workbench"]
        )

    def test_finite_profile_has_no_connected_runtime_inputs(self):
        text = self.profile()
        for forbidden in (
            "--bootstrap",
            "--registry-url",
            "--state-dir",
            "KAFKA_",
            "OPENTSX_CP_NETWORK",
            "OPENTSX_REGISTRY",
            "depends_on:",
            "external:",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, text)

    def test_container_bind_and_host_publication_are_explicit(self):
        text = self.profile()
        self.assertIn("- serve-finite", text)
        self.assertRegex(text, r"- --bind\s+- [\"']?0\.0\.0\.0[\"']?")
        self.assertIn(
            '"127.0.0.1:${OPENTSX_WORKBENCH_PORT:?run the build command first}:8080"',
            text,
        )

    def test_profile_reuses_the_pinned_runtime_and_offline_artifacts(self):
        text = self.profile()
        settings = json.loads(Path(__file__).with_name("settings.json").read_text())
        for key in ("python_image", "node_image"):
            self.assertRegex(settings[key], r"@sha256:[a-f0-9]{64}$")
        self.assertIn("platform: " + settings["platform"], text)
        self.assertIn("${OPENTSX_PYTHON_IMAGE:?run the build command first}", text)
        self.assertIn("/artifacts:ro", text)
        self.assertIn("/entrypoint.sh:ro", text)
        self.assertIn("runtime:/runtime", text)
        self.assertNotRegex(text, r"(?m)^\s+build:")

    def test_finite_network_and_volume_are_compose_scoped(self):
        text = self.profile()
        self.assertIn("\nnetworks:\n", text)
        self.assertIn("\nvolumes:\n  runtime:", text)
        self.assertNotIn("state:/state", text)
        self.assertNotIn("registry:/", text)
        self.assertNotRegex(text, r"(?m)^\s+name:")

    def test_read_only_resource_and_health_boundaries_remain(self):
        text = self.profile()
        for required in (
            "read_only: true",
            "cap_drop: [ALL]",
            "no-new-privileges:true",
            "mem_limit: 2g",
            "cpus: 2",
            "healthcheck:",
            "/analysis/v1/health",
        ):
            with self.subTest(required=required):
                self.assertIn(required, text)


if __name__ == "__main__":
    unittest.main()
