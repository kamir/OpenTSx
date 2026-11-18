import os
from pathlib import Path
import secrets
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KafScaleProbePreflightTest(unittest.TestCase):
    def run_step(self, credentials, bundle_status):
        source = (ROOT / "deploy/k3s/60-probe.sh").read_text()
        body = source.split("step_kafscale-local() {\n", 1)[1].split("\n}\n", 1)[0]
        script = "\n".join([
            "set -eu", "ROOT=/unused", "KS_HOST=192.0.2.1", "note() { printf '%s\\n' \"$*\"; }",
            "compose_cmd() { printf 'compose_stub\\n'; }", "compose_stub() { printf 'COMPOSE_INVOKED\\n'; }",
            f"python3() {{ return {bundle_status}; }}", "tcp_check() { return 0; }",
            "step_kafscale-local() {", body, "}", "step_kafscale-local",
        ])
        environment = {key: value for key, value in os.environ.items() if key not in {"MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD"}}
        if credentials:
            environment.update(MINIO_ROOT_USER=secrets.token_hex(12), MINIO_ROOT_PASSWORD=secrets.token_hex(24))
        result = subprocess.run(["bash", "-c", script], env=environment, text=True, capture_output=True, check=False)
        for name in {"MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD"} & environment.keys():
            self.assertNotIn(environment[name], result.stdout + result.stderr)
        return result

    def test_missing_private_credentials_stop_before_compose(self):
        result = self.run_step(False, 0)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("MINIO_ROOT_USER", result.stdout)
        self.assertNotIn("COMPOSE_INVOKED", result.stdout)

    def test_unverified_proxy_bundle_stops_before_compose(self):
        result = self.run_step(True, 1)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("build_kafscale_proxy.py", result.stdout)
        self.assertNotIn("COMPOSE_INVOKED", result.stdout)

    def test_verified_bundle_and_private_credentials_allow_the_proxy_step(self):
        result = self.run_step(True, 0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("COMPOSE_INVOKED", result.stdout)


if __name__ == "__main__":
    unittest.main()
