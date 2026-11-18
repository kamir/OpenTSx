import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

import build_kafscale_proxy as BUILD


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("runner_compose", ROOT / "tools/verify_rust_runner_compose.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class KafScaleProxyTransportTest(unittest.TestCase):
    def test_runner_fixture_resolves_the_proxy_without_a_direct_broker_alias(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "target/rust-runner-compose"
            with patch.object(RUNNER, "ROOT", root), patch.object(RUNNER, "DIRECTORY", directory):
                RUNNER.prepare_directories()
                hosts = (directory / "hosts").read_text()
                self.assertIn("kafscale-proxy", hosts)
                self.assertNotIn("kafscale-broker", hosts)

    def test_fresh_standalone_runs_clear_only_their_disposable_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "target/rust-runner-compose"
            old = directory / "transport/etcd/member/snapshot"
            old.parent.mkdir(parents=True)
            old.write_text("old metadata")
            retained = directory / "retained.log"
            retained.write_text("evidence")
            with patch.object(RUNNER, "ROOT", root), patch.object(RUNNER, "DIRECTORY", directory):
                RUNNER.prepare_directories()
            self.assertEqual([], list((directory / "transport").iterdir()))
            self.assertEqual("evidence", retained.read_text())

    def test_standalone_and_federated_fixtures_select_proxy_endpoints(self):
        compose = (ROOT / "docker-compose.rust-runner.yml").read_text()
        self.assertIn("  kafscale-proxy:", compose)
        self.assertNotIn("  kafscale-broker:", compose)
        fixture = (ROOT / "opentsx-wayang/src/test/java/org/opentsx/wayang/RustRunnerComposeFixture.java").read_text()
        self.assertIn('binding.put("bootstrap", "kafscale-proxy:" + port)', fixture)
        federation = (ROOT / "opentsx-wayang/src/test/java/org/opentsx/wayang/FederationBrokers.java").read_text()
        self.assertIn("KAFSCALE_CLUSTER_BINARY", federation)
        self.assertNotIn('"KAFSCALE_BROKER_ADDR"', federation)

    def test_track_a_measurement_uses_the_guarded_proxy_cluster(self):
        measurement = (ROOT / "tools/wayang_track_a_evaluation.py").read_text()
        self.assertIn(".transport_overlay(", measurement)
        self.assertIn('"KAFSCALE_PROXY_BINARY"', measurement)
        self.assertIn('"KAFSCALE_CLUSTER_BINARY"', measurement)
        self.assertIn('"--proxy-binary"', measurement)
        self.assertNotIn('"KAFSCALE_BROKER_ADDR"', measurement)

    def test_wayang_workers_and_host_fixture_use_the_same_proxy(self):
        compose = (ROOT / "docker-compose.wayang-rust.yml").read_text()
        self.assertEqual(3, compose.count('--bootstrap, "kafscale-proxy:'))
        self.assertIn("entrypoint: [/transport/kafscale-cluster]", compose)
        self.assertNotIn("  kafscale-broker:", compose)
        helper = (ROOT / "tools/verify_wayang_rust_compose.py").read_text()
        self.assertNotIn("kafscale-broker", helper)

    def test_binary_compilation_has_explicit_resource_limits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "target/kafscale-v1.6.0/cmd/broker"
            source.mkdir(parents=True)
            (source / "main.go").write_text('package main\nconst brokerVersion        = "dev"\n')
            (root / "tools").mkdir()
            (root / "tools/kafscale_proxy_cluster.go").write_text("pinned launcher")
            (root / "tools/kafscale_metadata_overlay.py").write_text("pinned metadata overlay")
            (root / "tools/kafscale_metadata_test.go").write_text("pinned metadata tests")
            (root / "tools/kafscale_proxy_protocol_overlay.py").write_text("pinned protocol overlay")
            (root / "tools/kafscale_proxy_protocol_test.go").write_text("pinned protocol tests")
            directory = root / "target/bundle"
            commands = []

            def execute(command, **kwargs):
                commands.append(command)
                if command[0] == "docker":
                    for name in BUILD.BINARIES:
                        path = directory / name
                        path.write_bytes(name.encode())
                        path.chmod(0o700)
                return subprocess.CompletedProcess(command, 0, stdout=BUILD.SOURCE_COMMIT + "\n")

            with patch.object(BUILD, "ROOT", root), patch.object(BUILD, "DIRECTORY", directory), \
                    patch.object(BUILD.shutil, "which", return_value="/tool"), patch.object(BUILD.subprocess, "run", side_effect=execute), \
                    patch.object(BUILD, "metadata_overlay", return_value={}), patch.object(BUILD, "protocol_overlay", return_value={}):
                BUILD.build()
            compiler = next(command for command in commands if command[0] == "docker")
            self.assertEqual("2", compiler[compiler.index("--cpus") + 1])
            self.assertEqual("3g", compiler[compiler.index("--memory") + 1])
            self.assertIn("GOMAXPROCS=2", compiler)
            program = compiler[-1]
            self.assertIn("./cmd/proxy ./pkg/metadata", program)
            self.assertIn("^TestOpenTSx(Proxy|Metadata)", program)

    def test_bundle_verification_rejects_changed_binaries_and_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "bundle"
            directory.mkdir()
            (root / "tools").mkdir()
            launcher = root / "tools/kafscale_proxy_cluster.go"
            launcher.write_text("pinned launcher")
            (root / "tools/kafscale_metadata_overlay.py").write_text("pinned metadata overlay")
            metadata_tests = root / "tools/kafscale_metadata_test.go"
            metadata_tests.write_text("pinned metadata tests")
            (root / "tools/kafscale_proxy_protocol_overlay.py").write_text("pinned protocol overlay")
            (root / "tools/kafscale_proxy_protocol_test.go").write_text("pinned protocol tests")
            with patch.object(BUILD, "ROOT", root), patch.object(BUILD, "DIRECTORY", directory):
                for name in BUILD.BINARIES:
                    path = directory / name
                    path.write_bytes(name.encode())
                    path.chmod(0o700)
                manifest = {"identity": BUILD.identity(), "binaries": {name: BUILD.digest(directory / name) for name in BUILD.BINARIES}}
                (directory / "manifest.json").write_text(json.dumps(manifest))
                self.assertEqual(manifest, BUILD.verify())
                self.assertEqual(BUILD.digest(metadata_tests), manifest["identity"]["metadataTestsSha256"])
                metadata_tests.write_text("changed metadata tests")
                with self.assertRaisesRegex(RuntimeError, "sources differ"):
                    BUILD.verify()
                metadata_tests.write_text("pinned metadata tests")
                (directory / "kafscale-proxy").write_bytes(b"changed")
                with self.assertRaisesRegex(RuntimeError, "binary differs"):
                    BUILD.verify()
                launcher.write_text("changed launcher")
                with self.assertRaisesRegex(RuntimeError, "sources differ"):
                    BUILD.verify()

    def test_version_overlay_preserves_the_pinned_source_and_rejects_unknown_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            output = root / "output"
            output.mkdir()
            path = source / "cmd/broker/main.go"
            path.parent.mkdir(parents=True)
            original = 'package main\nconst brokerVersion        = "dev"\n'
            path.write_text(original)
            overlay = BUILD.broker_overlay(source, output)
            self.assertEqual(original, path.read_text())
            replacement = json.loads(overlay.read_text())["Replace"][str(path)]
            self.assertIn('brokerVersion        = "v1.6.0"', Path(replacement).read_text())
            path.write_text("unexpected source")
            with self.assertRaisesRegex(RuntimeError, "version declaration differs"):
                BUILD.broker_overlay(source, output)


if __name__ == "__main__":
    unittest.main()
