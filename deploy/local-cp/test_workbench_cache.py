from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
from io import StringIO
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def builder():
    path = Path(__file__).with_name("workbench.py")
    spec = importlib.util.spec_from_file_location("cache_workbench", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildCacheTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.api = builder()
        original = self.api.LOCAL
        self.local = self.root / "deploy/local-cp"
        self.local.mkdir(parents=True)
        for name in (
            "workbench.py",
            "build_python.py",
            "entrypoint.sh",
            "requirements.lock",
        ):
            shutil.copy2(original / name, self.local / name)
        shutil.copy2(self.api.ROOT / ".gitignore", self.root / ".gitignore")
        self.python = self.root / "python-package"
        (self.python / "opentsx").mkdir(parents=True)
        (self.python / "opentsx/example.py").write_text("value = 1\n")
        (self.python / "pyproject.toml").write_text('[project]\nname = "opentsx"\n')
        self.frontend = self.root / "opentsx-saas-frontend"
        (self.frontend / "src").mkdir(parents=True)
        (self.frontend / "src/app.tsx").write_text("export const value = 1;\n")
        (self.frontend / "package.json").write_text('{"name":"fixture"}\n')
        (self.frontend / "package-lock.json").write_text('{"lockfileVersion":3}\n')
        (self.frontend / ".gitignore").write_text("node_modules/\ndist/\n.env*.local\n")
        self.settings = {
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
        (self.local / "settings.json").write_text(json.dumps(self.settings))
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        self.calls = []
        self.epoch = "1795003200"
        self.revision = "a" * 40
        self.after_stage = None
        for name, value in (
            ("ROOT", self.root),
            ("LOCAL", self.local),
            ("BUILD", self.local / ".build"),
            ("run", self.execute),
            ("node", self.node),
            ("output", self.output),
        ):
            replacement = patch.object(self.api, name, value)
            replacement.start()
            self.addCleanup(replacement.stop)

    def output(self, command):
        if command == ["git", "rev-parse", "HEAD"]:
            return self.revision
        if command == ["git", "show", "-s", "--format=%ct", "HEAD"]:
            return self.epoch
        if command == ["git", "status", "--porcelain"]:
            return ""
        raise AssertionError(f"Unexpected command: {command}")

    def execute(self, command):
        if command[0] != "docker":
            self.calls.append("host-tests")
            return
        if "python -m pytest" in command[-1]:
            self.calls.append("python-tests")
            return
        mount = next(value for value in command if str(value).endswith(",target=/out"))
        stage = Path(
            mount.removeprefix("type=bind,source=").removesuffix(",target=/out")
        )
        if "download" in command:
            self.calls.append("deps")
            (stage / "wheels/dependency.whl").write_bytes(
                (self.local / "requirements.lock").read_bytes()
            )
        elif "/source/deploy/local-cp/build_python.py" in command:
            self.calls.append("wheel")
            wheel = (
                self.python / "opentsx/example.py"
            ).read_bytes() + self.epoch.encode()
            (stage / "wheels/opentsx-fixture.whl").write_bytes(wheel)
            (stage / "runtime.lock").write_bytes(
                (self.local / "requirements.lock").read_bytes() + wheel
            )
            (stage / "python-build.json").write_text(
                json.dumps(
                    {
                        "python": "3.12.15",
                        "source_date_epoch": self.epoch,
                        "wheel_sha256": hashlib.sha256(wheel).hexdigest(),
                    }
                )
            )
        else:
            raise AssertionError(f"Unexpected Docker command: {command}")
        if self.after_stage is not None:
            self.after_stage(self.calls[-1])

    def node(self, settings, target, test=False):
        self.calls.append("frontend-tests" if test else "frontend")
        content = (self.frontend / "src/app.tsx").read_bytes()
        for path in sorted(self.frontend.glob(".env*")):
            content += path.name.encode() + path.read_bytes()
        (target / "index.html").write_text('<script src="/app.js"></script>')
        (target / "app.js").write_bytes(content)
        if self.after_stage is not None:
            self.after_stage(self.calls[-1])

    def build(self, profile="finite"):
        with redirect_stdout(StringIO()):
            self.api.build(self.settings, profile)
        name = "compose.finite.env" if profile == "finite" else "compose.env"
        env = dict(
            line.split("=", 1)
            for line in (self.api.BUILD / name).read_text().splitlines()
        )
        return Path(env["OPENTSX_BUNDLE"])

    def entry(self, kind):
        root = self.api.BUILD / "cache" / kind
        self.assertTrue(root.is_dir(), f"The {kind} artifact cache is missing")
        entries = list(root.iterdir())
        self.assertEqual(len(entries), 1)
        self.assertRegex(entries[0].name, r"^[a-f0-9]{64}$")
        return entries[0]

    def rebuild(self, change, expected):
        self.build()
        self.calls.clear()
        change()
        self.build()
        self.assertEqual(self.calls, expected)

    def test_unchanged_build_reuses_three_immutable_artifact_caches(self):
        before = self.build()
        before_digest = self.api.digest_tree(before)
        provenance = json.loads((before / "manifest.json").read_text())
        self.assertIn("build_inputs", provenance)
        self.assertEqual(set(provenance["build_inputs"]), {"deps", "wheel", "frontend"})
        for value in provenance["build_inputs"].values():
            self.assertRegex(value, r"^[a-f0-9]{64}$")
        self.assertEqual(self.calls, ["deps", "wheel", "frontend"])
        self.assertEqual(
            set(path.name for path in (self.api.BUILD / "cache").iterdir()),
            {"deps", "wheel", "frontend"},
        )
        receipts = {
            kind: (self.entry(kind) / "manifest.json").read_bytes()
            for kind in ("deps", "wheel", "frontend")
        }
        self.calls.clear()
        self.assertEqual(self.build(), before)
        self.assertEqual(self.api.digest_tree(before), before_digest)
        self.assertEqual(self.calls, [])
        for kind, receipt in receipts.items():
            self.assertEqual((self.entry(kind) / "manifest.json").read_bytes(), receipt)

    def test_python_edit_rebuilds_only_the_application_wheel(self):
        self.rebuild(
            lambda: (self.python / "opentsx/example.py").write_text("value = 2\n"),
            ["wheel"],
        )

    def test_frontend_edit_rebuilds_only_the_frontend(self):
        self.rebuild(
            lambda: (self.frontend / "src/app.tsx").write_text(
                "export const value = 2;\n"
            ),
            ["frontend"],
        )

    def test_generated_directory_names_inside_public_assets_are_still_inputs(self):
        asset = self.frontend / "public/dist/application.txt"
        asset.parent.mkdir(parents=True)
        asset.write_text("first asset\n")
        self.rebuild(lambda: asset.write_text("changed asset\n"), ["frontend"])

    def test_lock_change_rebuilds_dependency_and_application_wheels(self):
        self.rebuild(
            lambda: (self.local / "requirements.lock").write_text("changed-lock\n"),
            ["deps", "wheel"],
        )

    def test_source_date_epoch_change_rebuilds_only_the_application_wheel(self):
        self.rebuild(lambda: setattr(self, "epoch", "1795003201"), ["wheel"])

    def test_python_image_change_rebuilds_dependency_and_application_wheels(self):
        self.rebuild(
            lambda: self.settings.update(python_image="python-second"),
            ["deps", "wheel"],
        )

    def test_node_image_change_rebuilds_only_the_frontend(self):
        self.rebuild(
            lambda: self.settings.update(node_image="node-second"), ["frontend"]
        )

    def test_platform_change_rebuilds_every_artifact(self):
        self.rebuild(
            lambda: self.settings.update(platform="linux/amd64"),
            ["deps", "wheel", "frontend"],
        )

    def test_python_source_mode_is_an_application_wheel_input(self):
        self.rebuild(
            lambda: (self.python / "opentsx/example.py").chmod(0o755), ["wheel"]
        )

    def test_ignored_vite_environment_presence_contents_and_removal_invalidate_frontend(
        self,
    ):
        original = self.build()
        entry = self.entry("frontend")
        receipt = (entry / "manifest.json").read_bytes()
        env = self.frontend / ".env.production.local"
        for change in (
            lambda: env.write_text("VITE_FIXTURE=first\n"),
            lambda: env.write_text("VITE_FIXTURE=second\n"),
        ):
            self.calls.clear()
            change()
            self.build()
            self.assertEqual(self.calls, ["frontend"])
        self.calls.clear()
        env.unlink()
        self.assertEqual(self.build(), original)
        self.assertEqual(self.calls, [])
        self.assertEqual((entry / "manifest.json").read_bytes(), receipt)

    def test_python_copy_exclusions_do_not_invalidate_the_wheel(self):
        def change():
            for name in (
                "__pycache__",
                ".pytest_cache",
                "fixture.egg-info",
                "build",
                "dist",
            ):
                target = self.python / name
                target.mkdir()
                (target / "generated.txt").write_text("generated\n")

        self.rebuild(change, [])

    def test_profile_change_reuses_artifacts_but_keeps_a_new_profile_manifest(self):
        self.build()
        self.calls.clear()
        bundle = self.build("connected")
        self.assertEqual(self.calls, [])
        self.assertEqual(
            json.loads((bundle / "manifest.json").read_text())["profile"], "connected"
        )

    def test_unrelated_source_changes_refresh_bundle_provenance_without_rebuilding(
        self,
    ):
        before = self.build()
        original = json.loads((before / "manifest.json").read_text())
        (self.root / "notes.md").write_text("Unrelated source fixture\n")
        self.calls.clear()
        after = self.build()
        current = json.loads((after / "manifest.json").read_text())
        self.assertEqual(self.calls, [])
        self.assertNotEqual(before, after)
        self.assertNotEqual(original["source_sha256"], current["source_sha256"])
        self.assertEqual(current["source_sha256"], self.api.source_digest())
        self.assertEqual(current["revision"], self.revision)
        self.assertEqual(current["build_inputs"], original["build_inputs"])

    def test_entrypoint_is_assembled_fresh_without_rebuilding_artifacts(self):
        self.build()
        (self.local / "entrypoint.sh").write_text("exit 7\n")
        self.calls.clear()
        bundle = self.build()
        self.assertEqual(self.calls, [])
        self.assertEqual((bundle / "entrypoint.sh").read_text(), "exit 7\n")

    def test_external_frontend_test_fixtures_do_not_cache_test_execution(self):
        self.build()
        reference = self.root / "docs/datamodel-2026/fixture.json"
        reference.parent.mkdir(parents=True)
        reference.write_text("{}\n")
        self.calls.clear()
        self.build()
        self.assertEqual(self.calls, [])
        self.api.main(["test", "--profile", "finite"])
        self.assertEqual(self.calls, ["host-tests", "python-tests", "frontend-tests"])

    def test_selected_bundle_tests_reject_changed_ignored_environment_before_suites(
        self,
    ):
        self.build()
        original = self.api.source_digest()
        (self.frontend / ".env.production.local").write_text("VITE_FIXTURE=changed\n")
        self.assertEqual(self.api.source_digest(), original)
        self.calls.clear()
        errors = StringIO()
        with redirect_stderr(errors):
            with self.assertRaises(SystemExit) as error:
                self.api.main(["test", "--profile", "finite"])
        self.assertEqual(error.exception.code, 2)
        self.assertIn(
            "build inputs differ from the built bundle; run build before test",
            errors.getvalue(),
        )
        self.assertEqual(self.calls, [])

    def test_corrupted_missing_extra_or_unreadable_cache_files_fail_without_rebuilding(
        self,
    ):
        base = self.api.BUILD
        for kind in ("deps", "wheel", "frontend"):
            for damage in (
                "bytes",
                "missing",
                "extra",
                "mode",
                "symlink",
                "broken-symlink",
                "entry-symlink",
                "directory-symlink",
                "manifest",
            ):
                with self.subTest(kind=kind, damage=damage):
                    self.api.BUILD = base / (kind + "-" + damage)
                    self.api.BUILD.mkdir(parents=True)
                    self.build()
                    entry = self.entry(kind)
                    artifact = next(
                        path
                        for path in entry.rglob("*")
                        if path.is_file() and path != entry / "manifest.json"
                    )
                    if damage == "bytes":
                        artifact.write_bytes(b"corrupted")
                    elif damage == "missing":
                        artifact.unlink()
                    elif damage == "extra":
                        (entry / "unexpected").write_text("extra\n")
                    elif damage == "mode":
                        artifact.chmod(0o600)
                    elif damage in ("symlink", "broken-symlink"):
                        artifact.unlink()
                        artifact.symlink_to(
                            self.root
                            / (".gitignore" if damage == "symlink" else "absent")
                        )
                    elif damage == "entry-symlink":
                        moved = self.api.BUILD / "moved-entry"
                        entry.rename(moved)
                        entry.symlink_to(moved, target_is_directory=True)
                    elif damage == "directory-symlink":
                        directory = next(
                            path for path in entry.iterdir() if path.is_dir()
                        )
                        moved = self.api.BUILD / "moved-directory"
                        directory.rename(moved)
                        directory.symlink_to(moved, target_is_directory=True)
                    else:
                        (entry / "manifest.json").write_text("{")
                    self.calls.clear()
                    with self.assertRaisesRegex(RuntimeError, "cache"):
                        self.build()
                    self.assertEqual(self.calls, [])

    def test_partial_producer_failure_is_not_published_and_retry_reuses_completed_stages(
        self,
    ):
        def fail(stage):
            if stage == "frontend":
                raise RuntimeError("producer fixture failure")

        self.after_stage = fail
        with self.assertRaisesRegex(RuntimeError, "producer fixture failure"):
            self.build()
        self.assertFalse(
            (self.api.BUILD / "cache/frontend").exists()
            and list((self.api.BUILD / "cache/frontend").iterdir())
        )
        self.after_stage = None
        self.calls.clear()
        self.build()
        self.assertEqual(self.calls, ["frontend"])

    def test_frontend_with_a_missing_referenced_asset_is_not_published(self):
        def invalid_frontend(settings, target, test=False):
            (target / "index.html").write_text('<script src="/missing.js"></script>')

        with patch.object(self.api, "node", side_effect=invalid_frontend):
            with self.assertRaisesRegex(ValueError, "missing"):
                self.build()
        cache = self.api.BUILD / "cache/frontend"
        self.assertFalse(cache.exists() and list(cache.iterdir()))

    def test_application_wheel_stage_requires_exactly_one_wheel_before_publication(
        self,
    ):
        def remove_wheel(stage):
            if stage == "wheel":
                directory = next(self.api.BUILD.glob("stage-*/wheels"))
                for wheel in directory.glob("opentsx-*.whl"):
                    wheel.unlink()

        self.after_stage = remove_wheel
        with self.assertRaisesRegex(RuntimeError, "exactly one"):
            self.build()
        cache = self.api.BUILD / "cache/wheel"
        self.assertFalse(cache.exists() and list(cache.iterdir()))

    def test_changed_inputs_are_rejected_before_frontend_cache_publication(self):
        def change(stage):
            if stage == "frontend":
                (self.frontend / "src/app.tsx").write_text("changed during build\n")

        self.after_stage = change
        with self.assertRaisesRegex(RuntimeError, "changed|input"):
            self.build()
        cache = self.api.BUILD / "cache/frontend"
        self.assertFalse(cache.exists() and list(cache.iterdir()))
        self.assertFalse((self.api.BUILD / "compose.finite.env").exists())


if __name__ == "__main__":
    unittest.main()
