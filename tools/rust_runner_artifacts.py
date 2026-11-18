import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import struct
import subprocess
import sys
import tomllib


ROOT = Path(__file__).resolve().parents[1]
RUST = tomllib.loads((ROOT / "rust/tsx-runner/rust-toolchain.toml").read_text())["toolchain"]["channel"]
MUSL = {
    "x86_64-unknown-linux-musl": (2, 62, "x86_64", None),
    "aarch64-unknown-linux-musl": (2, 183, "aarch64", "qemu-aarch64"),
    "armv7-unknown-linux-musleabihf": (1, 40, "armv7", "qemu-arm"),
}
NATIVE = {"x86_64-unknown-linux-gnu": "x86_64", "aarch64-unknown-linux-gnu": "aarch64"}
VECTOR_TESTS = (
    "wire::tests::episode_wire_vectors_round_trip_byte_for_byte_and_preserve_full_fields",
    "wire::tests::all_series_identity_vectors_match_without_unicode_normalization",
    "operators::tests::all_shared_time_delta_cases_encode_and_decode_exactly",
    "all_shared_xxh64_cases_match_exactly",
    "all_shared_bucket_checksum_cases_match_exactly",
    "shared_execution_contract_vectors",
)
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


def run(command):
    try:
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=120).stdout.strip()
    except subprocess.CalledProcessError as error:
        raise ValueError(f"{Path(command[0]).name} failed with exit {error.returncode}: {error.stderr[:1000]}") from error
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError(f"cannot execute {Path(command[0]).name}: {error}") from error


def source_commit():
    commit = run(["git", "-C", str(ROOT), "rev-parse", "HEAD"])
    if not re.fullmatch("[0-9a-f]{40}", commit):
        raise ValueError("source commit is not a full Git object id")
    return commit


def inspect_elf(data, target):
    if target not in MUSL:
        raise ValueError("unsupported musl target")
    elf_class, machine, architecture, _ = MUSL[target]
    header_size, program_size = (64, 56) if elf_class == 2 else (52, 32)
    if len(data) < header_size or data[:4] != b"\x7fELF" or data[4:7] != bytes((elf_class, 1, 1)):
        raise ValueError("artifact is not the target's little-endian ELF")
    header = struct.unpack_from("<HHIQQQIHHHHHH" if elf_class == 2 else "<HHIIIIIHHHHHH", data, 16)
    kind, actual_machine, version, entry, offset = header[:5]
    flags, actual_header_size, entry_size, count = header[6:10]
    if kind not in (2, 3) or actual_machine != machine or version != 1 or actual_header_size != header_size:
        raise ValueError("ELF executable header differs from the target")
    if not count or count == 65535 or entry_size != program_size or offset < header_size:
        raise ValueError("ELF program-header inventory is invalid")
    if offset + count * entry_size > len(data):
        raise ValueError("ELF program headers are truncated")
    if machine == 40 and (flags & 0xFF000000 != 0x05000000 or flags & 0x400 == 0 or flags & 0x200):
        raise ValueError("ARM artifact does not use EABI5 hard-float")
    executable_entry = False
    for index in range(count):
        program = struct.unpack_from("<IIQQQQQQ" if elf_class == 2 else "<IIIIIIII", data, offset + index * entry_size)
        if elf_class == 2:
            tag, permissions, start, address, _, size, memory_size, _ = program
        else:
            tag, start, address, _, size, memory_size, permissions, _ = program
        if start + size > len(data) or size > memory_size:
            raise ValueError("ELF segment is truncated")
        if tag == 3:
            raise ValueError("ELF artifact has a dynamic interpreter")
        if tag == 1 and permissions & 1 and address <= entry < address + memory_size:
            executable_entry = True
        if tag == 2:
            dynamic_size = 16 if elf_class == 2 else 8
            if not size or size % dynamic_size:
                raise ValueError("ELF dynamic table is truncated")
            terminated = False
            for position in range(start, start + size, dynamic_size):
                dynamic_tag, _ = struct.unpack_from("<qQ" if elf_class == 2 else "<iI", data, position)
                if dynamic_tag == 1:
                    raise ValueError("ELF artifact needs a dynamic library")
                if dynamic_tag == 0:
                    terminated = True
                    break
            if not terminated:
                raise ValueError("ELF dynamic table has no terminator")
    if not executable_entry:
        raise ValueError("ELF entry is outside executable load segments")
    return {"architecture": architecture, "elfBits": 32 if elf_class == 1 else 64,
            "interpreter": False, "neededLibraries": []}


def test_counts(logs):
    totals = [0, 0, 0, 0, 0]
    combined = "\n".join(logs)
    for text in logs:
        summaries = re.findall(
            r"^test result: (ok|FAILED)\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out;",
            text, re.M)
        if not summaries or sum(int(row[1]) for row in summaries) == 0:
            raise ValueError("every test log must contain executed Rust tests")
        for outcome, *numbers in summaries:
            values = [int(value) for value in numbers]
            if outcome != "ok" or any(values[1:]):
                raise ValueError("failed, ignored, measured or filtered tests cannot qualify")
            totals = [old + value for old, value in zip(totals, values)]
    for name in (*VECTOR_TESTS, *EXAMPLE_TESTS):
        if not re.search(r"^test " + re.escape(name) + r" \.\.\. ok$", combined, re.M):
            raise ValueError("qualification test did not execute: " + name)
    return {"passed": totals[0], "failed": totals[1], "ignored": totals[2],
            "measured": totals[3], "filtered": totals[4], "sharedVectorTests": list(VECTOR_TESTS),
            "acceptanceExampleTests": list(EXAMPLE_TESTS)}


def compiler(tool, arguments):
    target = os.environ.get("TARGET")
    if target not in MUSL or tool not in ("cc", "cxx", "ar", "ranlib"):
        raise ValueError("compiler dispatch requires a declared musl target and tool")
    prefix = {"cc": "CC", "cxx": "CXX", "ar": "AR", "ranlib": "RANLIB"}[tool]
    executable = os.environ.get(prefix + "_" + target.replace("-", "_"))
    if not executable or not Path(executable).is_absolute() or not Path(executable).is_file():
        raise ValueError("cargo-zigbuild did not supply the target compiler wrapper")
    if target == "armv7-unknown-linux-musleabihf" and tool in ("cc", "cxx") and arguments == ["--version"]:
        # Compiler-only mode prevents an AEABI source from entering the version probe.
        # https://github.com/rust-cross/cargo-zigbuild/blob/v0.23.4/src/zig/mod.rs#L183
        arguments = ["-c", *arguments]
    if (tool in ("cc", "cxx") and "-shared" in arguments
            and any(argument.startswith("-Wl,--version-script=") for argument in arguments)
            and any(left == "-o" and Path(right).name == "librdkafka.so.1"
                    for left, right in zip(arguments, arguments[1:]))
            and "-Wl,--undefined-version" not in arguments):
        # The unused shared target exports an absent unit-test symbol.
        # https://github.com/llvm/llvm-project/blob/main/lld/docs/ld.lld.1
        arguments = [*arguments, "-Wl,--undefined-version"]
    os.execv(executable, [executable, *arguments])


def artifact_report(binary, target):
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("static artifact inspection requires the declared Linux x86_64 CI host")
    binary = binary.resolve(strict=True)
    if binary.stat().st_size > 268435456:
        raise ValueError("artifact exceeds the runner executable byte limit")
    data = binary.read_bytes()
    result = inspect_elf(data, target)
    description = run(["file", "--brief", "--", str(binary)])
    programs = run(["readelf", "--wide", "--program-headers", str(binary)])
    dynamic = run(["readelf", "--wide", "--dynamic", str(binary)])
    if "statically linked" not in description or re.search(r"\bINTERP\b", programs) or "(NEEDED)" in dynamic:
        raise ValueError("file/readelf did not confirm a static artifact")
    attributes = run(["readelf", "--arch-specific", str(binary)])
    if target == "armv7-unknown-linux-musleabihf" and not re.search(r"Tag_CPU_arch:\s+v7(?:\s|$)", attributes):
        raise ValueError("readelf did not confirm ARMv7 CPU attributes")
    runner = MUSL[target][3]
    expected = tomllib.loads((ROOT / "rust/tsx-runner/Cargo.toml").read_text())["package"]["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", expected):
        raise ValueError("runner package version must be a stable version")
    actual = run(([runner] if runner else []) + [str(binary), "--version"])
    if actual != "tsx-runner " + expected:
        raise ValueError("artifact version differs from the runner package")
    result.update(schema="opentsx.rust-artifact.v1", sourceCommit=source_commit(), target=target,
                  hostArchitecture=platform.machine(), hostSystem=platform.system(),
                  sha256=hashlib.sha256(data).hexdigest(), sizeBytes=len(data), binaryVersion=actual,
                  binaryVersionExecution="emulated" if runner else "native", file=description,
                  programHeaders=programs, dynamicSection=dynamic, architectureAttributes=attributes,
                  rustc=run(["rustc", "+" + RUST, "--version", "--verbose"]),
                  cargo=run(["cargo", "+" + RUST, "--version"]),
                  zig=run(["zig", "version"]), cargoZigbuild=run(["cargo-zigbuild", "--version"]))
    if result["zig"] != "0.15.2" or result["cargoZigbuild"] != "cargo-zigbuild 0.23.4":
        raise ValueError("cross-build tool version differs from the pinned version")
    if runner:
        result["emulator"] = run([runner, "--version"])
    return result


def tests_report(logs, target, mode):
    machine = platform.machine()
    if platform.system() != "Linux":
        raise ValueError("CI test reports require a Linux host")
    if mode == "native":
        if target not in NATIVE or machine != NATIVE[target]:
            raise ValueError("native test target differs from the actual host architecture")
    elif target != "armv7-unknown-linux-musleabihf" or machine != "x86_64":
        raise ValueError("emulated tests require the declared ARMv7-on-x86_64 route")
    result = test_counts([path.read_text() for path in logs])
    result.update(schema="opentsx.rust-tests.v1", sourceCommit=source_commit(), target=target,
                  executionMode=mode, hostArchitecture=machine, hostSystem=platform.system(),
                  rustc=run(["rustc", "+" + RUST, "--version", "--verbose"]))
    if mode == "emulated":
        result.update(emulator=run(["qemu-arm", "--version"]), hardwareQualification=False)
    return result


def build_diagnostics(directory, target):
    if target not in MUSL:
        raise ValueError("unsupported diagnostic target")
    root = directory / target / "release/build"
    configurations = sorted(root.glob("rdkafka-sys-*/out/Makefile.config"))
    if len(configurations) > 8:
        raise ValueError("cross-build diagnostic inventory exceeds eight attempts")
    attempts = []
    for config in configurations:
        if config.is_symlink() or config.stat().st_size > 65_536:
            raise ValueError("cross-build configuration is unsafe or exceeds 65536 bytes")
        lines = [line for line in config.read_text().splitlines() if re.match(
            r"^(?:CC|CXX|AR|RANLIB|LD|ARCH|CPU|TARGET|HOST|BUILD|MBITS|CFLAGS|CPPFLAGS|CXXFLAGS|LDFLAGS|LIB_LDFLAGS)\s*[:+?]?=", line)]
        record = {"build": config.parent.parent.name, "configuration": lines}
        obj = config.parent / "src/rdkafka.o"
        record["objectPresent"] = obj.is_file()
        if obj.is_symlink():
            raise ValueError("cross-build diagnostic object must not be a symbolic link")
        if record["objectPresent"]:
            record["file"] = run(["file", "--brief", "--", str(obj)])
            record["elfHeader"] = run(["readelf", "--wide", "--file-header", str(obj)])
        attempts.append(record)
    return {"schema": "opentsx.rust-build-diagnostics.v1", "target": target,
            "sourceCommit": source_commit(), "buildDirectoryPresent": root.is_dir(),
            "qualifiesArtifact": False, "attempts": attempts}


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    inspect = commands.add_parser("inspect")
    inspect.add_argument("--binary", type=Path, required=True)
    inspect.add_argument("--target", choices=MUSL, required=True)
    inspect.add_argument("--report", type=Path, required=True)
    tests = commands.add_parser("tests")
    tests.add_argument("--log", type=Path, action="append", required=True)
    tests.add_argument("--target", choices=(*NATIVE, "armv7-unknown-linux-musleabihf"), required=True)
    tests.add_argument("--mode", choices=("native", "emulated"), required=True)
    tests.add_argument("--report", type=Path, required=True)
    diagnostics = commands.add_parser("diagnostics")
    diagnostics.add_argument("--directory", type=Path, required=True)
    diagnostics.add_argument("--target", choices=MUSL, required=True)
    diagnostics.add_argument("--report", type=Path, required=True)
    dispatch = commands.add_parser("compiler")
    dispatch.add_argument("tool", choices=("cc", "cxx", "ar", "ranlib"))
    dispatch.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        if args.command == "compiler":
            compiler(args.tool, args.arguments)
            return
        if args.report.exists():
            raise ValueError("report already exists")
        if args.command == "inspect":
            result = artifact_report(args.binary, args.target)
        elif args.command == "diagnostics":
            result = build_diagnostics(args.directory, args.target)
        else:
            result = tests_report(args.log, args.target, args.mode)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"report": str(args.report), "schema": result["schema"]}))
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
