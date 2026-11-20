import argparse
import hashlib
import json
import math
import re
import subprocess
import uuid
from pathlib import Path


def command(argv, log=None):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=600)
    except subprocess.TimeoutExpired as failure:
        if log is not None:
            log.write_bytes((failure.stdout or b"") + (failure.stderr or b""))
        raise RuntimeError(f"{argv[0]} {argv[1]} timed out") from failure
    if log is not None:
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{argv[0]} {argv[1]} failed with exit {result.returncode}")
    return result.stdout


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError(f"invalid JSON constant: {value}")


def number(actual, expected, field):
    if type(actual) not in (int, float) or not math.isfinite(actual):
        raise ValueError(f"invalid numeric field: {field}")
    if not math.isclose(actual, expected, rel_tol=0, abs_tol=1e-9):
        raise ValueError(f"unexpected numeric field: {field}")


def validate_receipts(original, replay):
    if original != replay:
        raise ValueError("run and replay receipts differ")
    report = json.loads(
        original, object_pairs_hook=unique_object, parse_constant=reject_constant
    )
    if not isinstance(report, dict):
        raise ValueError("receipt must be a JSON object")
    expected = {
        "schemaVersion": 1,
        "scenario": "windpark-replay",
        "seed": 31,
        "fromMicros": 1790812800000000,
        "toMicros": 1790814600000000,
        "episodes": 90,
        "values": 900,
    }
    for key, value in expected.items():
        if type(report.get(key)) is not type(value) or report[key] != value:
            raise ValueError(f"unexpected receipt field: {key}")
    hashes = report.get("episodeSha256")
    if not isinstance(hashes, dict) or len(hashes) != 90:
        raise ValueError("receipt must identify 90 episode payloads")
    for key, value in hashes.items():
        if not re.fullmatch(r"[0-7][0-9A-HJKMNP-TV-Z]{25}", key):
            raise ValueError("invalid episode id")
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
            raise ValueError("invalid episode SHA-256")
    truth = report.get("groundTruth")
    if not isinstance(truth, list) or len(truth) != 2:
        raise ValueError("receipt must identify two injected operations")
    expected_truth = {
        "T02": ("FAULT", {}),
        "T03": ("CURTAILMENT", {"limitKw": "1500.0"}),
    }
    seen = set()
    for event in truth:
        if not isinstance(event, dict) or event.get("turbine") not in expected_truth:
            raise ValueError("unexpected ground truth turbine")
        turbine = event["turbine"]
        kind, params = expected_truth[turbine]
        if turbine in seen or event != {
            "category": "operation",
            "type": kind,
            "turbine": turbine,
            "channel": None,
            "fromMicros": 1790813400000000,
            "toMicros": 1790814000000000,
            "params": params,
        }:
            raise ValueError("unexpected injected operation")
        seen.add(turbine)
    analysis = report.get("analysis")
    if not isinstance(analysis, dict) or set(analysis) != {"T01", "T02", "T03"}:
        raise ValueError("receipt must analyze exactly three turbines")
    for turbine, produced, ratio, lost, mean, std in (
        ("T01", 1500, 1, 0, 3000, 0),
        ("T02", 1000, 0, 500, 2000, math.sqrt(3000000)),
        ("T03", 1250, 0.5, 250, 2500, math.sqrt(750000)),
    ):
        result = analysis[turbine]
        if not isinstance(result, dict) or type(result.get("intervals")) is not int:
            raise ValueError("invalid turbine analysis")
        number(result["intervals"], 3, "intervals")
        for key, value in (
            ("producedKwh", produced),
            ("expectedKwh", 1500),
            ("performanceIndex", produced / 1500),
        ):
            number(result.get(key), value, key)
        periods = result.get("underperformance")
        if not isinstance(periods, list) or len(periods) != (1 if lost else 0):
            raise ValueError("unexpected underperformance periods")
        if lost:
            if not isinstance(periods[0], dict):
                raise ValueError("invalid underperformance period")
            for key, value in (
                ("fromMicros", 1790813400000000),
                ("toMicros", 1790814000000000),
                ("meanRatio", ratio),
                ("lostEnergyKwh", lost),
            ):
                number(periods[0].get(key), value, key)
        bins = result.get("bins")
        if (
            not isinstance(bins, list)
            or len(bins) != 1
            or not isinstance(bins[0], dict)
        ):
            raise ValueError("unexpected power-curve bins")
        for key, value in (
            ("windFromMs", 15.5),
            ("windToMs", 16),
            ("count", 3),
            ("meanWindMs", 15.85),
            ("meanPowerKw", mean),
            ("stdPowerKw", std),
            ("referenceKw", 3000),
        ):
            number(bins[0].get(key), value, key)
    return {
        "episodes": 90,
        "values": 900,
        "report_sha256": hashlib.sha256(original).hexdigest(),
    }


def verify_image(image, platform, version, revision, output):
    registry = re.fullmatch(r"ghcr\.io/kamir/opentsx-tools@sha256:[0-9a-f]{64}", image)
    if not registry and not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
        raise ValueError("image must be an immutable tools digest or local image id")
    if platform not in ("linux/amd64", "linux/arm64"):
        raise ValueError("platform must be linux/amd64 or linux/arm64")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("version must be a stable numeric version")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("revision must be a full commit id")
    output = Path(output).resolve()
    if output.exists():
        raise ValueError("output already exists")
    output.mkdir(parents=True)
    if registry:
        command(["docker", "pull", "--platform", platform, image], output / "pull.log")
    inspected = json.loads(command(["docker", "image", "inspect", image]))
    if (
        not isinstance(inspected, list)
        or len(inspected) != 1
        or not isinstance(inspected[0], dict)
    ):
        raise ValueError("expected one inspected image")
    metadata = inspected[0]
    config = metadata.get("Config")
    if not isinstance(config, dict) or not isinstance(config.get("Labels"), dict):
        raise ValueError("image inspection has no runtime configuration or labels")
    labels = config["Labels"]
    if labels.get("org.opencontainers.image.version") != version:
        raise ValueError("image version does not match")
    if labels.get("org.opencontainers.image.revision") != revision:
        raise ValueError("image revision does not match")
    if f"{metadata.get('Os')}/{metadata.get('Architecture')}" != platform:
        raise ValueError("image platform does not match")
    if config.get("User") != "10001:10001":
        raise ValueError("image user does not match")
    image_id = metadata.get("Id")
    if not isinstance(image_id, str) or not re.fullmatch(
        r"sha256:[0-9a-f]{64}", image_id
    ):
        raise ValueError("image inspection has no immutable image id")
    if not registry and image_id != image:
        raise ValueError("local image id does not match")
    if registry and image not in (metadata.get("RepoDigests") or []):
        raise ValueError("downloaded image digest does not match")
    summary = {
        "image": image,
        "image_id": image_id,
        "platform": platform,
        "version": version,
        "revision": revision,
        "transport": "registry" if registry else "local",
    }
    (output / "image.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )
    volume = "opentsx-image-check-" + uuid.uuid4().hex
    containers = []
    created_volume = False
    failure = None
    cleanup_errors = []
    try:
        created_volume = True
        command(["docker", "volume", "create", volume])
        for phase, receipt in (("run", "report.json"), ("replay", "replay.json")):
            container = volume + "-" + phase
            containers.append(container)
            command(
                [
                    "docker",
                    "create",
                    "--name",
                    container,
                    "--platform",
                    platform,
                    "--network",
                    "none",
                    "--read-only",
                    "--cap-drop",
                    "ALL",
                    "--security-opt",
                    "no-new-privileges",
                    "--mount",
                    f"type=volume,source={volume},target=/tmp",
                    image_id,
                    "windpark-demo",
                    phase,
                    "--output",
                    "/tmp/windpark-demo",
                ]
            )
            command(["docker", "start", "--attach", container], output / f"{phase}.log")
            state = json.loads(command(["docker", "container", "inspect", container]))
            if (
                not isinstance(state, list)
                or len(state) != 1
                or not isinstance(state[0], dict)
                or not isinstance(state[0].get("State"), dict)
                or state[0]["State"].get("Status") != "exited"
                or type(state[0]["State"].get("ExitCode")) is not int
                or state[0]["State"]["ExitCode"] != 0
            ):
                raise ValueError("demo container did not exit successfully")
            command(
                [
                    "docker",
                    "cp",
                    f"{container}:/tmp/windpark-demo/{receipt}",
                    str(output / receipt),
                ]
            )
        result = validate_receipts(
            (output / "report.json").read_bytes(), (output / "replay.json").read_bytes()
        )
    except (Exception, KeyboardInterrupt) as error:
        failure = error
    finally:
        for container in reversed(containers):
            try:
                command(
                    ["docker", "rm", "--force", container],
                    output / f"cleanup-{container}.log",
                )
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                cleanup_errors.append(str(error))
        if created_volume:
            try:
                command(
                    ["docker", "volume", "rm", volume], output / "cleanup-volume.log"
                )
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                cleanup_errors.append(str(error))
    if cleanup_errors:
        raise RuntimeError(
            f"acceptance failed: {failure}; cleanup failed: {'; '.join(cleanup_errors)}"
        ) from failure
    if failure is not None:
        raise failure
    summary.update(result, status="passed")
    (output / "verification.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )
    return summary


def main():
    parser = argparse.ArgumentParser(
        description="Check a packaged tools image with the deterministic windpark demo."
    )
    parser.add_argument("--image", required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = verify_image(
            args.image, args.platform, args.version, args.revision, args.output
        )
    except (OSError, ValueError, RuntimeError) as failure:
        parser.exit(1, f"image acceptance failed: {failure}\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
