from contextlib import redirect_stderr, redirect_stdout
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
            if str(value).startswith(("type=bind", "type=volume"))
        ]
        self.assertEqual(
            mounts,
            [
                f"type=bind,source={api.ROOT},target=/source,readonly",
                "type=volume,target=/source/opentsx-saas-frontend/node_modules,volume-nocopy",
                f"type=bind,source={self.root},target=/source/opentsx-saas-frontend/dist",
            ],
        )
        command = run.call_args.args[0]
        self.assertIn("--rm", command)
        self.assertEqual(
            command[command.index("--workdir") + 1], "/source/opentsx-saas-frontend"
        )
        self.assertIn("npm ci --no-audit --no-fund", command[-1])
        self.assertIn("npm test -- --run", command[-1])
        self.assertIn("npm run build", command[-1])
        self.assertNotIn("tar ", command[-1])

    def test_frontend_prepares_nested_mount_targets_before_docker_on_a_clean_checkout(
        self,
    ):
        api = builder()
        frontend = self.root / "opentsx-saas-frontend"
        frontend.mkdir()
        target = self.root / "output"
        target.mkdir()
        self.assertEqual(list(frontend.iterdir()), [])

        def check_mount_targets(command):
            self.assertTrue((frontend / "node_modules").is_dir())
            self.assertTrue((frontend / "dist").is_dir())
            self.assertEqual(list((frontend / "node_modules").iterdir()), [])
            self.assertEqual(list((frontend / "dist").iterdir()), [])
            self.assertIn(
                f"type=bind,source={self.root},target=/source,readonly", command
            )
            self.assertIn(
                "type=volume,target=/source/opentsx-saas-frontend/node_modules,volume-nocopy",
                command,
            )

        with (
            patch.object(api, "ROOT", self.root),
            patch.object(api, "run", side_effect=check_mount_targets) as run,
        ):
            api.node({"platform": "linux/arm64", "node_image": "fixture-image"}, target)
        run.assert_called_once()

    def test_frontend_entrypoint_references_existing_assets(self):
        api = builder()
        api.validate_frontend(api.ROOT / "opentsx-saas-frontend")

    def test_up_reports_the_current_workbench_route(self):
        api = builder()
        captured = StringIO()
        with (
            patch.object(api, "run"),
            patch.object(api, "output", return_value="127.0.0.1:18081"),
            redirect_stdout(captured),
        ):
            api.main(["up"])
        self.assertIn("http://127.0.0.1:18081/workbench", captured.getvalue())

    def test_up_reports_the_deployed_binding_for_both_profiles(self):
        api = builder()
        for arguments, profile in (
            (["up"], "connected"),
            (["up", "--profile", "finite"], "finite"),
        ):
            captured = StringIO()
            with (
                self.subTest(profile=profile),
                patch.object(api, "run"),
                patch.object(api, "output", return_value="127.0.0.1:18083") as output,
                redirect_stdout(captured),
            ):
                api.main(arguments)
                self.assertIn("http://127.0.0.1:18083/workbench", captured.getvalue())
                self.assertNotIn(":18081/", captured.getvalue())
                output.assert_called_once_with(
                    api.compose(profile) + ["port", "workbench", "8080"]
                )

    def test_up_rejects_invalid_or_multiple_published_bindings(self):
        api = builder()
        for address in (
            "",
            "0.0.0.0:18083",
            "[::]:18083",
            "localhost:18083",
            "127.0.0.1:0",
            "127.0.0.1:65536",
            "127.0.0.1:-1",
            "127.0.0.1:not-a-port",
            "127.0.0.1:18083 extra",
            "127.0.0.1:18083\n127.0.0.1:18084",
        ):
            captured = StringIO()
            with (
                self.subTest(address=address),
                patch.object(api, "run"),
                patch.object(api, "output", return_value=address),
                redirect_stdout(captured),
            ):
                with self.assertRaisesRegex(
                    RuntimeError, "invalid published workbench address"
                ):
                    api.main(["up", "--profile", "finite"])
                self.assertNotIn("/workbench", captured.getvalue())

    def test_sarimax_demo_commands_forward_the_selected_scenario(self):
        api = builder()
        for command in ("demo-seed", "demo-append", "demo-target", "demo-context"):
            with self.subTest(command=command), patch.object(api, "run") as run:
                api.main([command, "--scenario", "sarimax"])
                arguments = run.call_args.args[0]
                self.assertIn(command, arguments)
                self.assertEqual(arguments[-2:], ["--scenario", "sarimax"])

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
            stage = next(build_root.glob("stage-*"))
            self.assertEqual(stage.stat().st_mode & 0o777, 0o755)
            if "/source/deploy/local-cp/build_python.py" in command:
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
        self.assertEqual(Path(env["OPENTSX_BUNDLE"]).stat().st_mode & 0o777, 0o755)
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
                "test_workbench*.py",
                "-v",
            ],
        )
        self.assertEqual(run.call_args_list[1].args[0][0], "docker")

    def test_default_connected_compose_arguments_are_preserved(self):
        api = builder()
        self.assertEqual(
            api.compose(),
            [
                "docker",
                "compose",
                "--project-name",
                "opentsx-workbench",
                "--env-file",
                str(api.BUILD / "compose.env"),
                "--file",
                str(api.LOCAL / "compose.yaml"),
            ],
        )

    def test_finite_compose_uses_an_independent_project_file_and_environment(self):
        api = builder()
        self.assertEqual(
            api.compose("finite"),
            [
                "docker",
                "compose",
                "--project-name",
                "opentsx-workbench-finite",
                "--env-file",
                str(api.BUILD / "compose.finite.env"),
                "--file",
                str(api.LOCAL / "compose.finite.yaml"),
            ],
        )

    def test_finite_operational_commands_select_only_the_finite_project(self):
        api = builder()
        for command, suffix in (
            ("up", ["up", "--detach", "--wait", "--wait-timeout", "180"]),
            ("status", ["ps"]),
            ("logs", ["logs", "--tail", "50", "workbench"]),
            ("down", ["down"]),
        ):
            with (
                self.subTest(command=command),
                patch.object(api, "run") as run,
                patch.object(api, "output", return_value="127.0.0.1:18081"),
                redirect_stdout(StringIO()),
            ):
                api.main([command, "--profile", "finite"])
                self.assertEqual(
                    run.call_args.args[0],
                    [
                        "docker",
                        "compose",
                        "--project-name",
                        "opentsx-workbench-finite",
                        "--env-file",
                        str(api.BUILD / "compose.finite.env"),
                        "--file",
                        str(api.LOCAL / "compose.finite.yaml"),
                    ]
                    + suffix,
                )

    def test_finite_build_command_forwards_the_selected_profile(self):
        api = builder()
        settings = json.loads((api.LOCAL / "settings.json").read_text())
        with patch.object(api, "build") as build:
            api.main(["build", "--profile", "finite"])
        build.assert_called_once_with(settings, "finite")

    def test_finite_profile_rejects_seed_commands_before_docker(self):
        api = builder()
        for command in (
            "seed",
            "demo-seed",
            "demo-append",
            "demo-target",
            "demo-context",
        ):
            errors = StringIO()
            with (
                self.subTest(command=command),
                patch.object(api, "run") as run,
                redirect_stderr(errors),
            ):
                with self.assertRaises(SystemExit) as error:
                    api.main([command, "--profile", "finite"])
                self.assertEqual(error.exception.code, 2)
                self.assertIn("finite profile does not support", errors.getvalue())
                run.assert_not_called()

    def test_finite_bundle_requires_no_connected_settings_and_keeps_connected_selection(
        self,
    ):
        api = builder()
        settings = {
            "python_image": "python-fixture",
            "node_image": "node-fixture",
            "platform": "linux/arm64",
            "port": 18081,
        }
        build_root = self.root / "build"
        build_root.mkdir()
        connected = build_root / "compose.env"
        connected.write_text("CONNECTED_SELECTION=preserved\n")
        local = self.root / "local"
        local.mkdir()
        (local / "entrypoint.sh").write_text("exit 0\n")

        def run(command):
            stage = next(build_root.glob("stage-*"))
            self.assertEqual(stage.stat().st_mode & 0o777, 0o755)
            if "/source/deploy/local-cp/build_python.py" in command:
                (stage / "runtime.lock").write_text("fixture-lock\n")
                (stage / "python-build.json").write_text(
                    '{"wheel_sha256":"wheel-fixture"}'
                )

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
            api.build(settings, "finite")
        env = dict(
            line.split("=", 1)
            for line in (build_root / "compose.finite.env").read_text().splitlines()
        )
        self.assertEqual(
            set(env),
            {"OPENTSX_BUNDLE", "OPENTSX_PYTHON_IMAGE", "OPENTSX_WORKBENCH_PORT"},
        )
        self.assertEqual(env["OPENTSX_PYTHON_IMAGE"], "python-fixture")
        self.assertEqual(env["OPENTSX_WORKBENCH_PORT"], "18081")
        self.assertEqual(connected.read_text(), "CONNECTED_SELECTION=preserved\n")
        self.assertEqual(Path(env["OPENTSX_BUNDLE"]).stat().st_mode & 0o777, 0o755)
        manifest = json.loads(
            (Path(env["OPENTSX_BUNDLE"]) / "manifest.json").read_text()
        )
        self.assertEqual(manifest["profile"], "finite")
        self.assertEqual(
            manifest["images"], {"python": "python-fixture", "node": "node-fixture"}
        )
        self.assertEqual(manifest["source_sha256"], "source-fixture")
        self.assertEqual(manifest["wheel_sha256"], "wheel-fixture")
        self.assertEqual(
            manifest["runtime_lock_sha256"],
            api.hashlib.sha256(b"fixture-lock\n").hexdigest(),
        )
        self.assertTrue((Path(env["OPENTSX_BUNDLE"]) / "entrypoint.sh").is_file())

    def test_finite_test_command_uses_the_selected_bundle_and_existing_checks(self):
        api = builder()
        bundle = self.root / "finite-bundle"
        bundle.mkdir()
        (bundle / "manifest.json").write_text(
            json.dumps({"source_sha256": "fixture-source"})
        )
        (self.root / "compose.finite.env").write_text(f"OPENTSX_BUNDLE={bundle}\n")
        (self.root / "compose.env").write_text(
            "OPENTSX_BUNDLE=/unselected/connected/bundle\n"
        )
        with (
            patch.object(api, "BUILD", self.root),
            patch.object(api, "source_digest", return_value="fixture-source"),
            patch.object(api, "run") as run,
            patch.object(api, "node") as node,
        ):
            api.main(["test", "--profile", "finite"])
        self.assertEqual(len(run.call_args_list), 2)
        self.assertEqual(
            run.call_args_list[0].args[0][1:4], ["-m", "unittest", "discover"]
        )
        container = run.call_args_list[1].args[0]
        self.assertIn(
            f"type=bind,source={bundle},target=/artifacts,readonly", container
        )
        self.assertIn("--require-hashes", container[-1])
        self.assertIn("python -m pytest /source/python-package/tests", container[-1])
        self.assertEqual(node.call_count, 1)
        self.assertEqual(node.call_args.kwargs, {"test": True})

    def test_finite_tests_do_not_fall_back_to_a_connected_bundle(self):
        api = builder()
        (self.root / "compose.env").write_text("OPENTSX_BUNDLE=/connected/bundle\n")
        errors = StringIO()
        with (
            patch.object(api, "BUILD", self.root),
            patch.object(api, "run") as run,
            redirect_stderr(errors),
        ):
            with self.assertRaises(SystemExit) as error:
                api.main(["test", "--profile", "finite"])
        self.assertEqual(error.exception.code, 2)
        self.assertIn("build the bundle before running tests", errors.getvalue())
        run.assert_not_called()

    def test_finite_tests_reject_a_stale_built_source_before_checks(self):
        api = builder()
        bundle = self.root / "finite-bundle"
        bundle.mkdir()
        (bundle / "manifest.json").write_text(
            json.dumps({"source_sha256": "stale-source"})
        )
        (self.root / "compose.finite.env").write_text(f"OPENTSX_BUNDLE={bundle}\n")
        errors = StringIO()
        with (
            patch.object(api, "BUILD", self.root),
            patch.object(api, "source_digest", return_value="current-source"),
            patch.object(api, "run") as run,
            redirect_stderr(errors),
        ):
            with self.assertRaises(SystemExit) as error:
                api.main(["test", "--profile", "finite"])
        self.assertEqual(error.exception.code, 2)
        self.assertIn("source differs from the built bundle", errors.getvalue())
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
