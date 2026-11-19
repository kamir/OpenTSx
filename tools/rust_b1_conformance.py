import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
CASES = (
    ("topics.admin", "createsTopicsViaAdminApi"),
    ("topics.batch", "retainsEveryAcknowledgedTopicAfterBatchCreation"),
    ("records.order", "producesAndConsumesInOrderPerKey"),
    ("records.codec", "roundTripsEveryCompressionCodec:none"),
    ("records.codec", "roundTripsEveryCompressionCodec:snappy"),
    ("records.codec", "roundTripsEveryCompressionCodec:lz4"),
    ("records.codec", "roundTripsEveryCompressionCodec:zstd"),
    ("records.large", "roundTripsOneMegabyteEpisode"),
    ("offsets.committed", "committedOffsetsAreReadableAfterRestart"),
    ("offsets.explicit", "seeksToExplicitOffsetForIndexBasedReplay"),
)


def endpoint(value):
    if not isinstance(value, str) or not value or len(value) > 4096:
        raise RuntimeError("KAFSCALE_BOOTSTRAP must name an Apache Kafka or KafScale Proxy endpoint")
    addresses = value.split(",")
    if len(addresses) > 16:
        raise RuntimeError("KAFSCALE_BOOTSTRAP has too many addresses")
    for address in addresses:
        match = re.fullmatch(r"(?:[A-Za-z0-9][A-Za-z0-9._-]{0,252}|\[[0-9A-Fa-f:]+\]):([1-9][0-9]{0,4})", address)
        if match is None or int(match.group(1)) > 65535:
            raise RuntimeError("KAFSCALE_BOOTSTRAP must contain canonical host:port addresses without credentials")
        if address.startswith("["):
            try:
                ipaddress.IPv6Address(address[1:address.index("]")])
            except ipaddress.AddressValueError as error:
                raise RuntimeError("KAFSCALE_BOOTSTRAP contains an invalid IPv6 address") from error
    return value


def runner_version():
    text = (ROOT / "rust/tsx-runner/Cargo.toml").read_text()
    package = re.search(r"(?ms)^\[package\]\n(.*?)(?=^\[|\Z)", text)
    if package is None:
        raise RuntimeError("the runner manifest has no package section")
    versions = re.findall(r'^version = "([^"]+)"$', package.group(1), re.MULTILINE)
    if len(versions) != 1 or re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", versions[0]) is None:
        raise RuntimeError("the runner package must declare one stable version")
    return versions[0]


def validate_report(value, bootstrap):
    fields = {"schema", "runnerVersion", "endpoint", "requiredFamilies", "requiredCases", "executedFamilies",
              "executedCases", "passedCases", "failedCases", "cases"}
    if not isinstance(value, dict) or set(value) != fields:
        raise RuntimeError("the Rust B1 report has invalid fields")
    if (value["schema"] != "opentsx.rust-b1-conformance.v1" or value["runnerVersion"] != runner_version()
            or value["endpoint"] != endpoint(bootstrap)):
        raise RuntimeError("the Rust B1 report does not bind this version and endpoint")
    for key, expected in (("requiredFamilies", 7), ("requiredCases", 10), ("executedFamilies", 7),
                          ("executedCases", 10), ("passedCases", 10), ("failedCases", 0)):
        if type(value[key]) is not int or value[key] != expected:
            raise RuntimeError("all seven Rust B1 families and ten executions must pass")
    rows = value["cases"]
    if not isinstance(rows, list) or len(rows) != len(CASES):
        raise RuntimeError("the Rust B1 report omits required executions")
    for row, (family, case) in zip(rows, CASES):
        if (not isinstance(row, dict) or set(row) != {"family", "case", "status", "detail"}
                or (row["family"], row["case"]) != (family, case) or row["status"] != "pass"
                or not isinstance(row["detail"], str) or not row["detail"].strip()):
            raise RuntimeError("a Rust B1 execution failed or has invalid evidence")
    return value


def log_bytes(value):
    if isinstance(value, bytes):
        return value
    if isinstance(value, str):
        return value.encode("utf-8")
    if value is None:
        return b""
    raise RuntimeError("the acceptance process returned invalid captured output")


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise RuntimeError("the Rust B1 acceptance report repeats a JSON field")
        value[key] = item
    return value


def execute(binary, output):
    bootstrap = endpoint(os.environ.get("KAFSCALE_BOOTSTRAP"))
    binary, output = Path(binary), Path(output)
    if binary.is_symlink() or not binary.is_file() or not os.access(binary, os.X_OK):
        raise RuntimeError("the acceptance binary must be an existing executable regular file")
    if any(path.is_symlink() for path in (output, *output.parents)):
        raise RuntimeError("the acceptance evidence directory must not traverse symbolic links")
    if output.exists():
        raise RuntimeError("the acceptance evidence directory already exists")
    output.mkdir(parents=True, mode=0o700)
    environment = dict(os.environ, KAFSCALE_BOOTSTRAP=bootstrap)
    try:
        process = subprocess.run([str(binary.resolve())], env=environment, capture_output=True, timeout=900, check=False)
    except subprocess.TimeoutExpired as error:
        (output / "stdout.log").write_bytes(log_bytes(error.stdout))
        (output / "stderr.log").write_bytes(log_bytes(error.stderr))
        raise RuntimeError("the Rust B1 acceptance process exceeded 900 seconds; read its retained logs") from error
    stdout, stderr = log_bytes(process.stdout), log_bytes(process.stderr)
    (output / "stdout.log").write_bytes(stdout)
    (output / "stderr.log").write_bytes(stderr)
    if process.returncode != 0:
        raise RuntimeError(f"the Rust B1 acceptance process exited {process.returncode}; read its retained logs")
    try:
        value = json.loads(stdout.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError("the Rust B1 acceptance process did not return one UTF-8 JSON report") from error
    validated = validate_report(value, bootstrap)
    (output / "result.json").write_text(json.dumps(validated, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return validated


def main():
    parser = argparse.ArgumentParser(description="Run the Rust B1 fixture against KAFSCALE_BOOTSTRAP and retain strict execution evidence.")
    parser.add_argument("--binary", type=Path, required=True, help="compiled b1_acceptance example executable")
    parser.add_argument("--output", type=Path, required=True, help="new directory for original logs and the validated report")
    arguments = parser.parse_args()
    value = execute(arguments.binary, arguments.output)
    print(json.dumps({"executedFamilies": value["executedFamilies"], "executedCases": value["executedCases"],
                      "passedCases": value["passedCases"], "failedCases": value["failedCases"]}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
