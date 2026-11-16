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
