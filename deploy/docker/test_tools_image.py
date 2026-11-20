import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


START = 1_790_812_800_000_000
REVISION = "8fcf386704412f4f2b691b3abaee8125718654d7"
DIGEST = "sha256:" + "a" * 64
REGISTRY_IMAGE = "ghcr.io/kamir/opentsx-tools@" + DIGEST


def receipt():
    analysis = {}
    for turbine, produced, ratio, lost in (
        ("T01", 1500, 1, 0),
        ("T02", 1000, 0, 500),
        ("T03", 1250, 0.5, 250),
    ):
        analysis[turbine] = {
            "bins": [
                {
                    "windFromMs": 15.5,
                    "windToMs": 16.0,
                    "count": 3,
                    "meanWindMs": 15.85,
                    "meanPowerKw": produced * 2,
                    "stdPowerKw": (
                        0
                        if not lost
                        else math.sqrt(3_000_000 if turbine == "T02" else 750_000)
                    ),
                    "referenceKw": 3000,
                }
            ],
            "intervals": 3,
            "producedKwh": produced,
            "expectedKwh": 1500,
            "performanceIndex": produced / 1500,
            "underperformance": (
                []
                if not lost
                else [
                    {
                        "fromMicros": START + 600_000_000,
                        "toMicros": START + 1_200_000_000,
                        "meanRatio": ratio,
                        "lostEnergyKwh": lost,
                    }
                ]
            ),
        }
    return {
        "schemaVersion": 1,
        "scenario": "windpark-replay",
        "seed": 31,
        "fromMicros": START,
        "toMicros": START + 1_800_000_000,
        "episodes": 90,
        "values": 900,
        "episodeSha256": {
            f"{index:026d}": hashlib.sha256(str(index).encode()).hexdigest()
            for index in range(90)
        },
        "groundTruth": [
            {
                "category": "operation",
                "type": kind,
                "turbine": turbine,
                "channel": None,
                "fromMicros": START + 600_000_000,
                "toMicros": START + 1_200_000_000,
                "params": {} if kind == "FAULT" else {"limitKw": "1500.0"},
            }
            for kind, turbine in (("FAULT", "T02"), ("CURTAILMENT", "T03"))
        ],
        "analysis": analysis,
    }


def payload(value):
    return json.dumps(value, sort_keys=True, allow_nan=False).encode()


class ToolsImageTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).with_name("check_tools_image.py")
        spec = importlib.util.spec_from_file_location("check_tools_image", path)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.calls = []
        self.failure = None
        self.exit_code = 0
        self.image = DIGEST
        self.original = payload(receipt())
        self.replayed = self.original
        self.metadata = {
            "Id": DIGEST,
            "Os": "linux",
            "Architecture": "amd64",
            "RepoDigests": [REGISTRY_IMAGE],
            "Config": {
                "Labels": {
                    "org.opencontainers.image.version": "3.0.1",
                    "org.opencontainers.image.revision": REVISION,
                },
                "User": "10001:10001",
            },
        }

    def docker(self, argv, log=None):
        self.calls.append(list(argv))
        if self.failure is not None and self.failure(argv):
            raise RuntimeError("fixture Docker command failed")
        if argv[:3] == ["docker", "image", "inspect"]:
            return json.dumps([self.metadata])
        if argv[:3] == ["docker", "container", "inspect"] or argv[:2] == [
            "docker",
            "inspect",
        ]:
            return json.dumps(
                [{"State": {"ExitCode": self.exit_code, "Status": "exited"}}]
            )
        if argv[:3] == ["docker", "volume", "create"]:
            return argv[-1] + "\n"
        if argv[:2] == ["docker", "create"]:
            return f"{len(self.calls):064x}\n"
        if argv[:2] == ["docker", "cp"]:
            source, target = argv[-2:]
            path = Path(target)
            if path.is_dir():
                path = path / source.rsplit("/", 1)[-1]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(
                self.replayed if "replay.json" in source else self.original
            )
        return ""

    def verify(
        self,
        output=None,
        image=None,
        platform="linux/amd64",
        version="3.0.1",
        revision=REVISION,
    ):
        with patch.object(self.module, "command", side_effect=self.docker):
            return self.module.verify_image(
                image or self.image,
                platform,
                version,
                revision,
                output or self.root / "evidence",
            )

    def assert_cleanup(self):
        self.assertTrue(
            any(
                call[:2] == ["docker", "rm"]
                or call[:3] == ["docker", "container", "rm"]
                for call in self.calls
            ),
            self.calls,
        )
        self.assertTrue(
            any(call[:3] == ["docker", "volume", "rm"] for call in self.calls),
            self.calls,
        )

    def test_accepts_independent_expected_receipt(self):
        result = self.module.validate_receipts(self.original, self.original)
        self.assertIsInstance(result, dict)

    def test_rejects_different_bytes_duplicate_keys_and_nonfinite_json(self):
        with self.assertRaises(ValueError):
            self.module.validate_receipts(self.original, self.original + b"\n")
        duplicated = self.original[:-1] + b',"seed":31}'
        with self.assertRaises(ValueError):
            self.module.validate_receipts(duplicated, duplicated)
        for number in ("NaN", "Infinity", "-Infinity"):
            changed = self.original.replace(
                b'"producedKwh": 1500', b'"producedKwh": ' + number.encode(), 1
            )
            self.assertNotEqual(self.original, changed)
            with self.subTest(number=number), self.assertRaises(ValueError):
                self.module.validate_receipts(changed, changed)

    def test_rejects_changed_scenario_clock_counts_and_inventory(self):
        for key, value in (
            ("schemaVersion", 2),
            ("schemaVersion", True),
            ("scenario", "other"),
            ("seed", 32),
            ("fromMicros", START + 1),
            ("toMicros", START + 1_800_000_001),
            ("episodes", 89),
            ("values", 901),
        ):
            changed = receipt()
            changed[key] = value
            data = payload(changed)
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.module.validate_receipts(data, data)
        for mutation in ("missing", "id", "digest"):
            changed = receipt()
            inventory = changed["episodeSha256"]
            key = next(iter(inventory))
            if mutation == "missing":
                del inventory[key]
            elif mutation == "id":
                inventory["../invalid"] = inventory.pop(key)
            else:
                inventory[key] = "not-a-digest"
            data = payload(changed)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.module.validate_receipts(data, data)

    def test_rejects_changed_power_oracle_and_detected_periods(self):
        cases = (
            ("intervals", 2),
            ("producedKwh", 999),
            ("expectedKwh", 1499),
            ("performanceIndex", 0.5),
        )
        for key, value in cases:
            changed = receipt()
            changed["analysis"]["T02"][key] = value
            data = payload(changed)
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.module.validate_receipts(data, data)
        for key, value in (
            ("fromMicros", START),
            ("toMicros", START + 1_800_000_000),
            ("meanRatio", 1),
            ("meanRatio", False),
            ("lostEnergyKwh", 499),
        ):
            changed = receipt()
            changed["analysis"]["T02"]["underperformance"][0][key] = value
            data = payload(changed)
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.module.validate_receipts(data, data)
        changed = receipt()
        changed["analysis"]["T01"]["underperformance"] = changed["analysis"]["T02"][
            "underperformance"
        ]
        data = payload(changed)
        with self.assertRaises(ValueError):
            self.module.validate_receipts(data, data)

    def test_rejects_changed_ground_truth(self):
        for key, value in (
            ("type", "MAINTENANCE"),
            ("turbine", "T01"),
            ("fromMicros", START),
            ("toMicros", START + 1_800_000_000),
        ):
            changed = receipt()
            changed["groundTruth"][0][key] = value
            data = payload(changed)
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.module.validate_receipts(data, data)

    def test_local_image_verifies_two_packaged_commands_and_cleanup(self):
        result = self.verify()
        self.assertIsInstance(result, dict)
        self.assertFalse(any(call[:2] == ["docker", "pull"] for call in self.calls))
        created = [call for call in self.calls if call[:2] == ["docker", "create"]]
        self.assertEqual(2, len(created))
        self.assertTrue(
            any("run" in call and "/tmp/windpark-demo" in call for call in created)
        )
        self.assertTrue(
            any("replay" in call and "/tmp/windpark-demo" in call for call in created)
        )
        self.assertTrue(all("windpark-demo" in call for call in created))
        self.assert_cleanup()
        self.assertTrue((self.root / "evidence/verification.json").is_file())

    def test_registry_reference_is_downloaded_before_inspection(self):
        self.verify(image=REGISTRY_IMAGE)
        pull = next(
            index
            for index, call in enumerate(self.calls)
            if call[:2] == ["docker", "pull"]
        )
        inspect = next(
            index
            for index, call in enumerate(self.calls)
            if call[:3] == ["docker", "image", "inspect"]
        )
        self.assertLess(pull, inspect)
        self.assertIn(REGISTRY_IMAGE, self.calls[pull])
        self.assertIn("linux/amd64", self.calls[pull])
        self.assert_cleanup()

    def test_arm64_metadata_matches_the_requested_platform(self):
        self.metadata["Architecture"] = "arm64"
        self.verify(platform="linux/arm64")
        self.assert_cleanup()
        self.assertTrue((self.root / "evidence/verification.json").is_file())

    def test_rejects_mutable_or_malformed_inputs_before_docker(self):
        for field, value in (
            ("image", "ghcr.io/kamir/opentsx-tools:3.0.1"),
            ("image", "other.example/tools@" + DIGEST),
            ("image", "sha256:short"),
            ("platform", "linux/ppc64le"),
            ("version", "dev"),
            ("version", "3.0.1-rc1"),
            ("version", "3.0"),
            ("revision", "not-a-revision"),
        ):
            self.calls = []
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.verify(
                    output=self.root / field / str(len(value)), **{field: value}
                )
            self.assertEqual([], self.calls)

    def test_rejects_incorrect_image_identity_platform_or_runtime_user(self):
        cases = (
            ("version", "3.0.2"),
            ("revision", "b" * 40),
            ("Os", "windows"),
            ("Architecture", "arm64"),
            ("User", "0:0"),
            ("Id", "not-a-digest"),
        )
        initial = copy.deepcopy(self.metadata)
        for key, value in cases:
            self.metadata = copy.deepcopy(initial)
            if key in ("version", "revision"):
                self.metadata["Config"]["Labels"][
                    "org.opencontainers.image." + key
                ] = value
            elif key == "User":
                self.metadata["Config"][key] = value
            else:
                self.metadata[key] = value
            target = self.root / key
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.verify(output=target)
            self.assertFalse((target / "verification.json").exists())

    def test_registry_digest_must_match_inspected_image(self):
        self.metadata["RepoDigests"] = [
            "ghcr.io/kamir/opentsx-tools@sha256:" + "b" * 64
        ]
        with self.assertRaises(ValueError):
            self.verify(image=REGISTRY_IMAGE)
        self.assertFalse((self.root / "evidence/verification.json").exists())

    def test_image_inspection_works_with_the_runner_docker_28_cli(self):
        self.failure = (
            lambda argv: argv[:3] == ["docker", "image", "inspect"]
            and "--platform" in argv
        )
        self.verify()
        inspect = next(
            call for call in self.calls if call[:3] == ["docker", "image", "inspect"]
        )
        self.assertEqual(["docker", "image", "inspect", DIGEST], inspect)

    def test_malformed_image_metadata_is_rejected_before_startup(self):
        initial = copy.deepcopy(self.metadata)
        for name in ("Config", "Labels", "Id"):
            self.metadata = copy.deepcopy(initial)
            if name == "Labels":
                self.metadata["Config"][name] = []
            else:
                self.metadata[name] = None
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.verify(output=self.root / name)
            self.assertFalse(
                any(call[:2] == ["docker", "create"] for call in self.calls)
            )

    def test_unknown_container_state_still_cleans_up_and_fails_explicitly(self):
        for index, state in enumerate(({}, [], [{"State": None}])):
            self.calls = []

            def docker(argv, log=None):
                if argv[:3] == ["docker", "container", "inspect"]:
                    return json.dumps(state)
                return self.docker(argv, log)

            with self.subTest(state=state), patch.object(
                self.module, "command", side_effect=docker
            ):
                with self.assertRaises(ValueError):
                    self.module.verify_image(
                        DIGEST, "linux/amd64", "3.0.1", REVISION, self.root / str(index)
                    )
            self.assert_cleanup()

    def test_failed_create_attempts_cleanup_of_its_owned_name(self):
        self.failure = lambda argv: argv[:2] == ["docker", "create"]
        with self.assertRaises(RuntimeError):
            self.verify()
        self.assert_cleanup()

    def test_failed_volume_creation_attempts_cleanup_of_its_owned_name(self):
        self.failure = lambda argv: argv[:3] == ["docker", "volume", "create"]
        with self.assertRaises(RuntimeError):
            self.verify()
        self.assertTrue(
            any(call[:3] == ["docker", "volume", "rm"] for call in self.calls)
        )

    def test_docker_start_or_copy_failures_clean_up_owned_resources(self):
        for operation in ("start", "cp"):
            self.calls = []
            self.failure = lambda argv, expected=operation: argv[:2] == [
                "docker",
                expected,
            ]
            target = self.root / operation
            with self.subTest(operation=operation), self.assertRaises(RuntimeError):
                self.verify(output=target)
            self.assert_cleanup()
            self.assertFalse((target / "verification.json").exists())

    def test_cleanup_failure_prevents_success_receipt_and_attempts_volume_cleanup(self):
        self.failure = lambda argv: argv[:2] == ["docker", "rm"] or argv[:3] == [
            "docker",
            "container",
            "rm",
        ]
        with self.assertRaises(RuntimeError):
            self.verify()
        self.assert_cleanup()
        self.assertFalse((self.root / "evidence/verification.json").exists())

    def test_nonzero_container_exit_prevents_success_and_cleans_up(self):
        self.exit_code = 2
        with self.assertRaises(ValueError):
            self.verify()
        self.assert_cleanup()
        self.assertFalse((self.root / "evidence/verification.json").exists())

    def test_changed_downloaded_receipts_fail_and_still_clean_up(self):
        altered = receipt()
        altered["analysis"]["T02"]["producedKwh"] = 1500
        self.replayed = payload(altered)
        with self.assertRaises(ValueError):
            self.verify()
        self.assert_cleanup()
        self.assertFalse((self.root / "evidence/verification.json").exists())

    def test_existing_output_is_preserved_without_docker(self):
        target = self.root / "existing"
        target.mkdir()
        marker = target / "keep.txt"
        marker.write_text("existing evidence\n")
        with self.assertRaises((ValueError, FileExistsError)):
            self.verify(output=target)
        self.assertEqual("existing evidence\n", marker.read_text())
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
