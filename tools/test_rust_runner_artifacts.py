import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("runner_artifacts", Path(__file__).with_name("rust_runner_artifacts.py"))
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)
WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
EXAMPLE_TESTS = (
    "tests::missing_or_credential_shaped_endpoints_are_rejected",
    "tests::every_attempt_is_recorded_and_failures_are_not_converted_to_success",
    "control::tests::frames_and_correlations_are_bounded_and_exact",
    "control::tests::malformed_lengths_varints_utf8_and_tags_are_rejected",
    "control::tests::committed_response_requires_the_exact_topic_partition_offset_and_success",
    "control::tests::request_versions_and_membership_fields_match_the_test_contract",
    "tests::placement_evidence_requires_every_exact_key_and_partition",
    "tests::placement_evidence_refuses_missing_duplicate_or_unknown_rows",
    "tests::all_seven_required_manifest_invocations_are_counted_without_empty_success",
    "tests::wrong_prefixes_case_order_paths_counts_and_error_expectations_are_rejected",
    "tests::stopped_outcomes_require_exact_schema_counts_and_whole_topic_cursors",
)


def elf(target, extra=None, dynamic=None):
    bits, machine, _, _ = HELPER.MUSL[target]
    header_size, program_size = (64, 56) if bits == 2 else (52, 32)
    count = 1 + (extra is not None or dynamic is not None)
    size = header_size + count * program_size + (16 if bits == 2 else 8) * len(dynamic or [])
    data = bytearray(size)
    data[:7] = b"\x7fELF" + bytes((bits, 1, 1))
    address = 0x400000
    header = (2, machine, 1, address + header_size, header_size, 0,
              0x05000400 if bits == 1 else 0, header_size, program_size, count, 0, 0, 0)
    struct.pack_into("<HHIQQQIHHHHHH" if bits == 2 else "<HHIIIIIHHHHHH", data, 16, *header)

    def program(index, tag, offset, length):
        values = (tag, 5, offset, address, address, length, length, 8) if bits == 2 else (
            tag, offset, address, address, length, length, 5, 4)
        struct.pack_into("<IIQQQQQQ" if bits == 2 else "<IIIIIIII", data,
                         header_size + index * program_size, *values)

    program(0, 1, 0, size)
    if extra is not None:
        program(1, extra, 0, size)
    if dynamic is not None:
        offset = header_size + count * program_size
        program(1, 2, offset, size - offset)
        for index, (tag, value) in enumerate(dynamic):
            struct.pack_into("<qQ" if bits == 2 else "<iI", data,
                             offset + index * (16 if bits == 2 else 8), tag, value)
    return data


def logs():
    names = HELPER.VECTOR_TESTS
    return ["test " + names[-1] + " ... ok\ntest result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;\n",
            "\n".join("test " + name + " ... ok" for name in names[:-1]) +
            "\ntest result: ok. 5 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;\n"
            "test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;\n",
            "\n".join("test " + name + " ... ok" for name in EXAMPLE_TESTS) +
            "\ntest result: ok. 11 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;\n"]


class ElfArtifactTest(unittest.TestCase):
    def test_all_three_static_target_headers_are_recognized(self):
        for target in HELPER.MUSL:
            with self.subTest(target=target):
                actual = HELPER.inspect_elf(elf(target), target)
                self.assertFalse(actual["interpreter"])
                self.assertEqual([], actual["neededLibraries"])

    def test_interpreters_needed_libraries_and_unterminated_dynamic_tables_reject(self):
        for target in HELPER.MUSL:
            for data in (elf(target, extra=3), elf(target, dynamic=[(1, 1), (0, 0)]),
                         elf(target, dynamic=[(5, 1)])):
                with self.subTest(target=target), self.assertRaises(ValueError):
                    HELPER.inspect_elf(data, target)
            HELPER.inspect_elf(elf(target, dynamic=[(5, 1), (0, 0)]), target)

    def test_malformed_wrong_architecture_and_non_executable_headers_reject(self):
        original = elf("x86_64-unknown-linux-musl")
        mutations = [original[:40], b"not an ELF file" + original[15:]]
        for offset, data in [(4, b"\x01"), (5, b"\x02"), (16, b"\x01\x00"), (18, b"\xb7\x00"),
                             (24, b"\x00" * 8), (32, b"\xff" * 8), (54, b"\x00\x00"),
                             (56, b"\x00\x00"), (56, b"\xff\xff")]:
            altered = bytearray(original)
            altered[offset:offset + len(data)] = data
            mutations.append(altered)
        for data in mutations:
            with self.subTest(data=data[:64]), self.assertRaises(ValueError):
                HELPER.inspect_elf(data, "x86_64-unknown-linux-musl")

    def test_arm_soft_float_old_eabi_and_truncated_segments_reject(self):
        target = "armv7-unknown-linux-musleabihf"
        for flags in (0x05000200, 0x04000400, 0):
            data = elf(target)
            struct.pack_into("<I", data, 36, flags)
            with self.subTest(flags=flags), self.assertRaisesRegex(ValueError, "hard-float"):
                HELPER.inspect_elf(data, target)
        data = elf(target)
        struct.pack_into("<I", data, 52 + 16, len(data) + 1)
        with self.assertRaisesRegex(ValueError, "truncated"):
            HELPER.inspect_elf(data, target)

    def test_compiler_dispatch_keeps_exact_target_tool_and_argument_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            wrapper = Path(directory) / "target-cc"
            wrapper.write_text("fixture")
            with patch.dict(HELPER.os.environ, {"TARGET": "armv7-unknown-linux-musleabihf",
                            "CC_armv7_unknown_linux_musleabihf": str(wrapper)}, clear=True):
                with patch.object(HELPER.os, "execv") as execute:
                    HELPER.compiler("cc", ["input file.c", "-DVALUE=$literal"])
                    execute.assert_called_once_with(str(wrapper), [str(wrapper), "input file.c", "-DVALUE=$literal"])

    def test_missing_target_and_compiler_wrapper_never_fall_back_to_host_cc(self):
        for environment in ({}, {"TARGET": "x86_64-unknown-linux-gnu"},
                            {"TARGET": "armv7-unknown-linux-musleabihf"},
                            {"TARGET": "armv7-unknown-linux-musleabihf", "CC_armv7_unknown_linux_musleabihf": "cc"}):
            with self.subTest(environment=environment), patch.dict(HELPER.os.environ, environment, clear=True):
                with self.assertRaises(ValueError), patch.object(HELPER.os, "execv") as execute:
                    HELPER.compiler("cc", ["--version"])
                execute.assert_not_called()

    def test_ci_global_compiler_wrappers_execute_the_exact_target_wrapper(self):
        script = re.search(r'^          mkdir -p "\$RUNNER_TEMP/rust-target-tools"\n.*?^          done\n',
                           WORKFLOW.read_text(), re.M | re.S)
        self.assertIsNotNone(script)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target compiler"
            target.write_text("#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n")
            target.chmod(0o755)
            environment = {"PATH": HELPER.os.environ["PATH"], "RUNNER_TEMP": directory,
                           "GITHUB_WORKSPACE": str(HELPER.ROOT), "TARGET": "armv7-unknown-linux-musleabihf",
                           "CC_armv7_unknown_linux_musleabihf": str(target)}
            subprocess.run(["bash", "-c", textwrap.dedent(script.group())], env=environment, check=True,
                           capture_output=True, text=True)
            wrapper = Path(directory) / "rust-target-tools/cc"
            result = subprocess.run([str(wrapper), "input file.c", "-DVALUE=$literal"], env=environment,
                                    check=True, capture_output=True, text=True)
            self.assertEqual(["input file.c", "-DVALUE=$literal"], json.loads(result.stdout))

    def test_artifact_report_rejects_tool_disagreement_and_records_exact_bytes(self):
        target = "x86_64-unknown-linux-musl"
        data = elf(target)
        with tempfile.TemporaryDirectory() as directory:
            binary = (Path(directory) / "tsx-runner").resolve()
            binary.write_bytes(data)

            def output(command):
                return {"file": "ELF 64-bit executable, statically linked",
                        "readelf": "", "git": "a" * 40, str(binary): "tsx-runner 0.1.0",
                        "rustc": "rustc 1.99.1", "cargo": "cargo 1.99.1", "zig": "0.15.2",
                        "cargo-zigbuild": "cargo-zigbuild 0.23.4"}[command[0]]

            with patch.object(HELPER, "run", side_effect=output), \
                    patch.object(HELPER.platform, "system", return_value="Linux"), \
                    patch.object(HELPER.platform, "machine", return_value="x86_64"):
                report = HELPER.artifact_report(binary, target)
                self.assertEqual(hashlib.sha256(data).hexdigest(), report["sha256"])
                self.assertEqual(len(data), report["sizeBytes"])
                self.assertEqual("tsx-runner 0.1.0", report["binaryVersion"])
            for bad in ("ELF dynamically linked", "ELF static-pie linked"):
                with patch.object(HELPER, "run", side_effect=lambda command: bad if command[0] == "file" else ""), \
                        patch.object(HELPER.platform, "system", return_value="Linux"), \
                        patch.object(HELPER.platform, "machine", return_value="x86_64"):
                    with self.subTest(description=bad), self.assertRaisesRegex(ValueError, "file/readelf"):
                        HELPER.artifact_report(binary, target)

    def test_arm_artifact_version_is_executed_under_the_reported_emulator(self):
        target = "aarch64-unknown-linux-musl"
        with tempfile.TemporaryDirectory() as directory:
            binary = (Path(directory) / "tsx-runner").resolve()
            binary.write_bytes(elf(target))

            def output(command):
                return {"file": "ELF 64-bit executable, statically linked", "readelf": "",
                        "git": "a" * 40, "qemu-aarch64": "tsx-runner 0.1.0" if command[-1] == "--version"
                        and len(command) == 3 else "qemu-aarch64 version 8.2.2",
                        "rustc": "rustc 1.99.1", "cargo": "cargo 1.99.1", "zig": "0.15.2",
                        "cargo-zigbuild": "cargo-zigbuild 0.23.4"}[command[0]]

            with patch.object(HELPER, "run", side_effect=output) as execute, \
                    patch.object(HELPER.platform, "system", return_value="Linux"), \
                    patch.object(HELPER.platform, "machine", return_value="x86_64"):
                report = HELPER.artifact_report(binary, target)
                self.assertEqual("emulated", report["binaryVersionExecution"])
                self.assertEqual("qemu-aarch64 version 8.2.2", report["emulator"])
                self.assertIn(["qemu-aarch64", str(binary), "--version"],
                              [call.args[0] for call in execute.call_args_list])

    def test_external_dynamic_headers_and_wrong_versions_cannot_pass_binary_inspection(self):
        target = "x86_64-unknown-linux-musl"
        with tempfile.TemporaryDirectory() as directory:
            binary = (Path(directory) / "tsx-runner").resolve()
            binary.write_bytes(elf(target))
            for mutation in ("interpreter", "needed", "binary version", "zig version"):
                def output(command):
                    if command[:3] == ["readelf", "--wide", "--program-headers"] and mutation == "interpreter":
                        return "INTERP"
                    if command[:3] == ["readelf", "--wide", "--dynamic"] and mutation == "needed":
                        return "(NEEDED)"
                    values = {"file": "ELF executable, statically linked", "readelf": "", "git": "a" * 40,
                              str(binary): "tsx-runner dev" if mutation == "binary version" else "tsx-runner 0.1.0",
                              "rustc": "rustc 1.99.1", "cargo": "cargo 1.99.1",
                              "zig": "0.0.0" if mutation == "zig version" else "0.15.2",
                              "cargo-zigbuild": "cargo-zigbuild 0.23.4"}
                    return values[command[0]]

                with self.subTest(mutation=mutation), patch.object(HELPER, "run", side_effect=output), \
                        patch.object(HELPER.platform, "system", return_value="Linux"), \
                        patch.object(HELPER.platform, "machine", return_value="x86_64"), self.assertRaises(ValueError):
                    HELPER.artifact_report(binary, target)


class RustTestEvidenceTest(unittest.TestCase):
    def test_shared_vectors_and_real_positive_summaries_are_required(self):
        result = HELPER.test_counts(logs())
        self.assertEqual(17, result["passed"])
        self.assertEqual(0, result["ignored"])
        for invalid in ([""], ["test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;"],
                        [text.replace(HELPER.VECTOR_TESTS[0], "other_test") for text in logs()]):
            with self.subTest(logs=invalid), self.assertRaises(ValueError):
                HELPER.test_counts(invalid)

    def test_every_acceptance_example_parser_must_execute(self):
        for name in EXAMPLE_TESTS:
            altered = [text.replace("test " + name + " ... ok", "test unrelated ... ok") for text in logs()]
            with self.subTest(test=name), self.assertRaisesRegex(ValueError, "did not execute"):
                HELPER.test_counts(altered)

    def test_failed_ignored_filtered_and_measured_cases_cannot_qualify(self):
        for mutation in ("FAILED. 5 passed", "ok. 5 passed; 1 failed", "ok. 5 passed; 0 failed; 1 ignored",
                         "ok. 5 passed; 0 failed; 0 ignored; 1 measured",
                         "ok. 5 passed; 0 failed; 0 ignored; 0 measured; 1 filtered out"):
            altered = logs()
            marker = mutation.split(";", 1)[0]
            if "FAILED" in mutation:
                altered[1] = altered[1].replace("ok. 5 passed", mutation)
            else:
                length = len(mutation.split(";"))
                fields = "ok. 5 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out".split(";")
                altered[1] = altered[1].replace(";".join(fields[:length]), mutation)
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                HELPER.test_counts(altered)

    def test_native_mislabelling_rejects_and_emulation_cannot_claim_hardware(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, text in enumerate(logs()):
                path = Path(directory) / f"{index}.log"
                path.write_text(text)
                paths.append(path)
            with patch.object(HELPER.platform, "system", return_value="Linux"), \
                    patch.object(HELPER.platform, "machine", return_value="x86_64"), \
                    patch.object(HELPER, "source_commit", return_value="a" * 40), \
                    patch.object(HELPER, "run", return_value="fixture version"):
                with self.assertRaisesRegex(ValueError, "actual host"):
                    HELPER.tests_report(paths, "aarch64-unknown-linux-gnu", "native")
                result = HELPER.tests_report(paths, "armv7-unknown-linux-musleabihf", "emulated")
                self.assertFalse(result["hardwareQualification"])
                self.assertEqual("emulated", result["executionMode"])


class RustCiEvidenceTest(unittest.TestCase):
    def test_compiler_paths_are_exported_from_a_running_step(self):
        text = WORKFLOW.read_text()
        cross = re.search(r"^  rust-runner-static:\n(.*?)(?=^  \S|\Z)", text, re.M | re.S)
        self.assertIsNotNone(cross)
        environment = re.search(r"^    env:\n(.*?)(?=^    steps:)", cross.group(1), re.M | re.S)
        self.assertIsNotNone(environment)
        self.assertNotRegex(environment.group(1), r"\$\{\{\s*runner\.")
        script = re.search(r'^          mkdir -p "\$RUNNER_TEMP/rust-target-tools"\n.*?(?=^          curl )',
                           cross.group(1), re.M | re.S)
        self.assertIsNotNone(script)
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory) / "runner temp $literal"
            temporary.mkdir()
            exported = Path(directory) / "github.env"
            subprocess.run(["bash", "-c", textwrap.dedent(script.group())], check=True,
                           capture_output=True, text=True,
                           env={"PATH": HELPER.os.environ["PATH"], "RUNNER_TEMP": str(temporary),
                                "GITHUB_WORKSPACE": str(HELPER.ROOT), "GITHUB_ENV": str(exported)})
            entries = [line.split("=", 1) for line in exported.read_text().splitlines()]
            self.assertEqual(4, len(entries))
            self.assertEqual({"CC", "CXX", "AR", "RANLIB"}, {entry[0] for entry in entries})
            for variable, path in entries:
                self.assertTrue(Path(path).is_absolute(), variable)
                self.assertTrue(Path(path).is_file(), variable)
                self.assertTrue(HELPER.os.access(path, HELPER.os.X_OK), variable)
                self.assertEqual(temporary / "rust-target-tools" / variable.lower(), Path(path))

    def assert_workflow(self, text):
        def job(name):
            match = re.search(r"^  " + name + r":\s*\n(.*?)(?=^  \S|\Z)", text, re.M | re.S)
            self.assertIsNotNone(match, name + " job is missing")
            return match.group(1)
        native = job("rust-runner-native")
        cross = job("rust-runner-static")
        self.assertEqual([("ubuntu-24.04", "x86_64-unknown-linux-gnu"),
                          ("ubuntu-24.04-arm", "aarch64-unknown-linux-gnu")],
                         re.findall(r"^          - runner: (\S+)\n            target: (\S+)$", native, re.M))
        target_list = re.search(r"^        target:\n((?:          - \S+\n)+)", cross, re.M)
        self.assertIsNotNone(target_list)
        self.assertEqual(list(HELPER.MUSL), re.findall(r"^          - (\S+)$", target_list.group(1), re.M))
        for block in (native, cross):
            self.assertNotIn("continue-on-error", block)
            self.assertNotIn("self-hosted", block)
            self.assertIn("retention-days: 7", block)
            self.assertIn("if-no-files-found: error", block)
            for manifest in ("rust/tsx-runner/Cargo.toml", "rust/tsx-contracts/Cargo.toml"):
                self.assertIn(manifest, block)
            self.assertNotRegex(block, r"--(?:test|lib|exclude|skip|ignored)(?:\s|$)")
            self.assertNotIn("--no-run", block)
            self.assertNotRegex(block, re.compile(r"^\s+exclude:", re.M))
        self.assertIn("tests --mode native", native)
        self.assertIn("tests --mode emulated", cross)
        self.assertIn("CARGO_TARGET_ARMV7_UNKNOWN_LINUX_MUSLEABIHF_RUNNER: qemu-arm", cross)
        self.assertIn("cargo-zigbuild test --locked", cross)
        self.assertIn("rust_runner_artifacts.py inspect", cross)
        self.assertIn("02aa270f183da276e5b5920b1dac44a63f1a49e55050ebde3aecc9eb82f93239", cross)
        self.assertIn("9e3cf73485edbd45905c8aadbc0fdf869c7ddc3848f0c898229f2680db52e44b", cross)
        self.assertIn("9655f3e7e50af597e1b845822c47af9231f9f1bcb8c99e586db9da775d70c041", cross)

    def test_workflow_requires_both_native_architectures_three_static_artifacts_and_emulated_evidence(self):
        self.assert_workflow(WORKFLOW.read_text())

    def test_workflow_mutations_cannot_remove_execution_architecture_or_retention_gates(self):
        text = WORKFLOW.read_text()
        self.assert_workflow(text)
        changes = {
            "missing native": ("  rust-runner-native:", "  unused-native:"),
            "missing target": ("aarch64-unknown-linux-musl", "aarch64-unknown-linux-gnu"),
            "ignored failure": ("    name: Rust static", "    continue-on-error: true\n    name: Rust static"),
            "wrong native CPU": ("runner: ubuntu-24.04-arm", "runner: ubuntu-24.04"),
            "unbounded retention": ("retention-days: 7", "retention-days: 90"),
            "missing static check": ("rust_runner_artifacts.py inspect", "other.py inspect"),
            "unexecuted tests": ("cargo-zigbuild test --locked", "cargo-zigbuild test --locked --no-run"),
            "excluded native CPU": ("        include:\n          - runner: ubuntu-24.04",
                                    "        exclude:\n          - runner: ubuntu-24.04-arm\n"
                                    "        include:\n          - runner: ubuntu-24.04"),
        }
        for name, (old, new) in changes.items():
            altered = text.replace(old, new)
            self.assertNotEqual(text, altered, name)
            with self.subTest(mutation=name), self.assertRaises(AssertionError):
                self.assert_workflow(altered)


if __name__ == "__main__":
    unittest.main()
