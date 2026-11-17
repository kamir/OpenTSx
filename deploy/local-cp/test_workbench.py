from contextlib import redirect_stdout
import importlib.util
from io import StringIO
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


def builder():
    path = Path(__file__).with_name("workbench.py")
    spec = importlib.util.spec_from_file_location("local_cp_builder", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WorkbenchTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_bundle_rejects_missing_frontend_assets(self):
        api = builder()
        (self.root / "index.html").write_text('<link rel="icon" href="/missing.svg">')
        with self.assertRaisesRegex(ValueError, "missing"):
            api.validate_frontend(self.root)

    def test_bundle_accepts_existing_local_assets(self):
        api = builder()
        (self.root / "index.html").write_text(
            '<link href="/style.css"><script src="/app.js"></script>'
        )
        (self.root / "style.css").write_text("body {}")
        (self.root / "app.js").write_text("const value = 1;")
        api.validate_frontend(self.root)

    def test_bundle_rejects_assets_outside_web_root(self):
        api = builder()
        (self.root / "index.html").write_text('<script src="/../outside.js"></script>')
        with self.assertRaisesRegex(ValueError, "outside"):
            api.validate_frontend(self.root)

    def test_generated_bundles_do_not_change_the_source_digest(self):
        api = builder()
        (self.root / ".gitignore").write_text((api.ROOT / ".gitignore").read_text())
        (self.root / "source.py").write_text("value = 1\n")
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        subprocess.run(
            ["git", "add", ".gitignore", "source.py"], cwd=self.root, check=True
        )
        with patch.object(api, "ROOT", self.root):
            before = api.source_digest()
            generated = self.root / "deploy/local-cp/.build/stage-fixture"
            generated.mkdir(parents=True)
            (generated / "manifest.json").write_text('{"built":true}')
            self.assertEqual(api.source_digest(), before)
            (self.root / "source.py").write_text("value = 2\n")
            self.assertNotEqual(api.source_digest(), before)

    def test_frontend_tests_mount_shared_model_reference_vectors(self):
        api = builder()
        with patch.object(api, "run") as run:
            api.node(
                {"platform": "linux/arm64", "node_image": "fixture-image"},
                self.root,
                test=True,
            )
        mounts = [
            str(value)
            for value in run.call_args.args[0]
            if str(value).startswith("type=bind")
        ]
        self.assertIn(
            f"type=bind,source={api.ROOT / 'opentsx-model'},target=/tmp/opentsx-model,readonly",
            mounts,
        )
        self.assertIn(
            f"type=bind,source={api.ROOT / 'docs/datamodel-2026'},target=/tmp/docs/datamodel-2026,readonly",
            mounts,
        )

    def test_frontend_entrypoint_references_existing_assets(self):
        api = builder()
        api.validate_frontend(api.ROOT / "opentsx-saas-frontend")

    def test_up_reports_the_current_workbench_route(self):
        api = builder()
        captured = StringIO()
        with patch.object(api, "run"), redirect_stdout(captured):
            api.main(["up"])
        self.assertIn("http://127.0.0.1:18081/workbench", captured.getvalue())

    def test_profile_developer_guide_link_exists(self):
        api = builder()
        readme = api.LOCAL / "README.md"
        self.assertIn("../../docs/devguide/local-cp-workbench.md", readme.read_text())
        self.assertTrue(
            (readme.parent / "../../docs/devguide/local-cp-workbench.md").is_file()
        )

    def test_profile_keeps_registry_and_scoring_state_in_durable_volumes(self):
        api = builder()
        compose = (api.LOCAL / "compose.yaml").read_text()
        settings = json.loads((api.LOCAL / "settings.json").read_text())
        self.assertRegex(
            settings["registry_image"], r"^ghcr\.io/mlflow/mlflow@sha256:[a-f0-9]{64}$"
        )
        self.assertEqual(settings["registry_url"], "http://registry:5000")
        self.assertIn("state:/state", compose)
        self.assertIn("registry:/mlflow", compose)
        self.assertIn("--registry-url", compose)
        self.assertIn("--state-dir", compose)
        registry = compose.split("  registry:\n", 1)[1].split("\nnetworks:", 1)[0]
        self.assertNotIn("ports:", registry)
        self.assertIn("--allowed-hosts", registry)

    def test_build_records_the_registry_runtime_and_url(self):
        api = builder()
        settings = {
            "python_image": "python-fixture",
            "node_image": "node-fixture",
            "registry_image": "registry-fixture",
            "registry_url": "http://registry:5000",
            "platform": "linux/arm64",
            "bootstrap": "broker:29092",
            "prefix": "tsx.test.",
            "network": "cp",
            "port": 18081,
        }
        build_root = self.root / "build"
        local = self.root / "local"
        local.mkdir()
        (local / "entrypoint.sh").write_text("exit 0\n")

        def run(command):
            if "/source/deploy/local-cp/build_python.py" in command:
                stage = next(build_root.glob("stage-*"))
                (stage / "runtime.lock").write_text("fixture-lock\n")
                (stage / "python-build.json").write_text("{}")

        def node(settings, target):
            (target / "index.html").write_text("<html></html>")

        with (
            patch.object(api, "BUILD", build_root),
            patch.object(api, "LOCAL", local),
            patch.object(api, "source_digest", return_value="source-fixture"),
            patch.object(api, "output", return_value="123"),
            patch.object(api, "run", side_effect=run),
            patch.object(api, "node", side_effect=node),
            redirect_stdout(StringIO()),
        ):
            api.build(settings)
        env = dict(
            line.split("=", 1)
            for line in (build_root / "compose.env").read_text().splitlines()
        )
        self.assertEqual(env["OPENTSX_REGISTRY_IMAGE"], "registry-fixture")
        self.assertEqual(env["OPENTSX_REGISTRY_URL"], "http://registry:5000")
        manifest = json.loads(
            (Path(env["OPENTSX_BUNDLE"]) / "manifest.json").read_text()
        )
        self.assertEqual(manifest["images"]["registry"], "registry-fixture")

    def test_launcher_runs_host_checks_before_container_tests(self):
        api = builder()
        bundle = self.root / "bundle"
        bundle.mkdir()
        (bundle / "manifest.json").write_text(
            json.dumps({"source_sha256": "fixture-source"})
        )
        (self.root / "compose.env").write_text(f"OPENTSX_BUNDLE={bundle}\n")
        with (
            patch.object(api, "BUILD", self.root),
            patch.object(api, "source_digest", return_value="fixture-source"),
            patch.object(api, "run") as run,
            patch.object(api, "node"),
        ):
            api.main(["test"])
        self.assertEqual(
            run.call_args_list[0].args[0],
            [
                sys.executable,
                "-m",
                "unittest",
                "discover",
                "-s",
                str(api.LOCAL),
                "-p",
                "test_workbench.py",
                "-v",
            ],
        )
        self.assertEqual(run.call_args_list[1].args[0][0], "docker")


if __name__ == "__main__":
    unittest.main()
