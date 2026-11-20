import copy
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = Path(__file__).with_name("finite-smoke.mjs")
EXAMPLE = ROOT / "python-package/examples/finite_modelling_request.json"
ORIGIN = 1795003200000000
URL = "http://127.0.0.1:18081"


class FiniteSmokeTests(unittest.TestCase):
    def run_node(self, arguments, code=None):
        self.assertTrue(
            HELPER.is_file(), "The finite browser acceptance helper is missing"
        )
        node = shutil.which("node")
        self.assertIsNotNone(node, "Node is required for the browser acceptance helper")
        command = [node]
        if code is not None:
            command.extend(["--input-type=module", "-e", code])
        else:
            command.append(str(HELPER))
        return subprocess.run(
            command + arguments, cwd=ROOT, text=True, capture_output=True, check=False
        )

    def call(self, name, *arguments):
        code = """
const [url, name, raw] = process.argv.slice(1);
const helper = await import(url);
const result = await helper[name](...JSON.parse(raw));
process.stdout.write(JSON.stringify(result === undefined ? null : result));
"""
        return self.run_node([HELPER.as_uri(), name, json.dumps(arguments)], code)

    def result(self):
        example = json.loads(EXAMPLE.read_text(encoding="utf-8"))
        digest = hashlib.sha256(
            struct.pack("<16d", *example["target"]["values"])
        ).hexdigest()
        return {
            "schema": "opentsx.modelling-result.v1",
            "source": {
                "series_id": "synthetic-voltage",
                "unit": "V",
                "time_kind": "absolute",
                "episode_ids": ["synthetic-episode-1"],
                "nobs": 16,
                "interval_micros": 1000000,
                "t_start_micros": ORIGIN,
                "t_end_micros": ORIGIN + 16000000,
                "values_sha256": digest,
            },
            "forecast": {
                "result": {
                    "mean": [16, 16, 16, 16],
                    "timestamps_micros": [
                        ORIGIN + step * 1000000 for step in (16, 17, 18, 19)
                    ],
                }
            },
            "baselines": {"naive": {"result": {"mean": [16, 16, 16, 16]}}},
            "backtest": {
                "result": {
                    "folds": [
                        {
                            "training_start": 0,
                            "training_stop": 12,
                            "test_start": 12,
                            "test_stop": 14,
                        },
                        {
                            "training_start": 0,
                            "training_stop": 14,
                            "test_start": 14,
                            "test_stop": 16,
                        },
                    ],
                    "metrics": {"naive": {"mae": 2.5}},
                }
            },
        }

    def test_help_needs_no_browser_dependency(self):
        result = self.run_node(["--help", "--playwright-module", "/missing-playwright"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--url", result.stdout)
        self.assertIn("--output", result.stdout)

    def test_invalid_options_fail_before_loading_the_browser_dependency(self):
        for arguments in [
            ["--unknown"],
            ["--url"],
            ["--url", URL, "--url", URL],
            ["--url", "https://127.0.0.1:18081"],
            ["--url", "http://remote.example:18081"],
            ["--url", "http://127.0.0.1:0"],
            ["--url", "http://user:password@127.0.0.1:18081"],
            ["--url", URL + "/workbench"],
            ["--url", URL + "?source=remote"],
            ["--url", URL + "#fragment"],
        ]:
            with self.subTest(arguments=arguments):
                result = self.run_node(
                    arguments + ["--playwright-module", "/missing-playwright"]
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("Cannot find module", result.stderr)
                self.assertRegex(
                    result.stderr.lower(), "argument|option|url|loopback|port"
                )

    def test_valid_options_and_fixture_use_the_declared_utc_origin(self):
        options = self.call(
            "parseOptions", ["--url", URL, "--output", "target/standalone-analysis"]
        )
        self.assertEqual(options.returncode, 0, options.stderr)
        self.assertEqual(json.loads(options.stdout)["url"], URL)
        example = json.loads(EXAMPLE.read_text(encoding="utf-8"))
        result = self.call("episodeFixture", example)
        self.assertEqual(result.returncode, 0, result.stderr)
        episode = json.loads(result.stdout)
        self.assertEqual(episode["seriesId"], "synthetic-voltage")
        self.assertEqual(episode["episodeId"], "synthetic-episode-1")
        self.assertEqual(episode["unit"], "V")
        self.assertEqual(episode["labels"]["origin"], "SYNTHETIC")
        self.assertEqual(episode["values"], example["target"]["values"])
        self.assertEqual(
            episode["timestampsMicros"], [ORIGIN + i * 1000000 for i in range(16)]
        )

    def test_independent_random_walk_reference_and_input_hash_are_accepted(self):
        result = self.call(
            "validateResult",
            self.result(),
            json.loads(EXAMPLE.read_text(encoding="utf-8")),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        close = self.result()
        close["forecast"]["result"]["mean"] = [16 + 5e-9] * 4
        result = self.call(
            "validateResult", close, json.loads(EXAMPLE.read_text(encoding="utf-8"))
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_wrong_numerical_results_clock_identity_and_hash_are_rejected(self):
        changes = [
            (("forecast", "result", "mean"), [16, 16, 16, 16.000001]),
            (("forecast", "result", "mean"), [16, 16, 16]),
            (
                ("forecast", "result", "timestamps_micros"),
                [ORIGIN + i * 1000000 for i in (15, 16, 17, 18)],
            ),
            (("source", "values_sha256"), "0" * 64),
            (("source", "unit"), "A"),
            (("source", "time_kind"), "relative"),
            (("source", "t_start_micros"), 0),
            (("source", "series_id"), "another-series"),
            (("source", "episode_ids"), ["another-episode"]),
            (("backtest", "result", "folds"), []),
            (("backtest", "result", "metrics", "naive", "mae"), 0),
        ]
        example = json.loads(EXAMPLE.read_text(encoding="utf-8"))
        for keys, value in changes:
            with self.subTest(keys=keys, value=value):
                result = copy.deepcopy(self.result())
                field = result
                for key in keys[:-1]:
                    field = field[key]
                field[keys[-1]] = value
                rejected = self.call("validateResult", result, example)
                self.assertNotEqual(rejected.returncode, 0)

    def test_browser_request_policy_allows_only_static_assets_and_finite_modelling(
        self,
    ):
        for method, target, expected in [
            ("GET", URL + "/workbench", True),
            ("GET", URL + "/assets/application.js", True),
            ("POST", URL + "/analysis/v1/modelling/runs", True),
            ("GET", URL + "/analysis/v1/modelling/runs", False),
            ("GET", URL + "/analysis/v1/modelling/models", False),
            ("GET", URL + "/api/v1/auth/mode", False),
            ("GET", URL + "/%61nalysis/v1/topics", False),
            ("GET", URL + "/analysis%2Fv1%2Fresults", False),
            ("GET", URL + "/%61pi/v1/auth/mode", False),
            ("GET", URL + "/%61nalysis/v1/modelling/runs", False),
            ("POST", URL + "/%61nalysis/v1/modelling/runs", False),
            ("GET", URL + "/assets/%ZZ.js", False),
            ("GET", URL + "/assets/%E0%A4%A.js", False),
            ("GET", URL + "/retrieval/v1/episodes", False),
            ("GET", "http://remote.example/application.js", False),
            ("POST", URL + "/assets/application.js", False),
        ]:
            with self.subTest(method=method, target=target):
                result = self.call("allowedRequest", method, target, URL)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout), expected)


if __name__ == "__main__":
    unittest.main()
