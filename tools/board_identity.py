#!/usr/bin/env python3
# Runs on the board itself; keep it compatible with Python 3.6 (Ubuntu 18.04, JetPack 4.6).
"""Record the identity of a B4.2 measurement board as JSON identity evidence.

It reads the kernel, CPU, memory, CPU frequency governors, device model and OS
release from the board itself. Storage and network cannot be detected
reliably, so the operator names them. On a Jetson the power mode (`nvpmodel`)
and the thermal zones are recorded as well, because both change measured
throughput. The `board` object of the output matches a `boards` entry of the
B4.2 evidence manifest except for `identityEvidence`, which names this file.
"""

import argparse
import json
import platform
from pathlib import Path
import subprocess
import sys


def read(root, name):
    path = root / name.lstrip("/")
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None


def os_release(root):
    text = read(root, "/etc/os-release") or ""
    for line in text.splitlines():
        if line.startswith("PRETTY_NAME="):
            return line.split("=", 1)[1].strip().strip('"')
    return None


def governors(root):
    values = {}
    for path in sorted((root / "sys/devices/system/cpu").glob("cpu[0-9]*/cpufreq/scaling_governor")):
        values[path.parent.parent.name] = path.read_text(encoding="utf-8").strip()
    return values


def thermal(root):
    zones = {}
    for zone in sorted((root / "sys/class/thermal").glob("thermal_zone*")):
        kind, temperature = (path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None
                             for path in (zone / "type", zone / "temp"))
        if kind and temperature and temperature.strip().lstrip("-").isdigit():
            zones[kind.strip()] = int(temperature.strip()) / 1000
    return zones


def nvpmodel():
    try:
        result = subprocess.run(["nvpmodel", "-q"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                universal_newlines=True, timeout=5, check=True)
        return result.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def identity(root, storage, network, *, uname=platform.uname, power_mode=nvpmodel):
    info = uname()
    per_cpu = governors(root)
    distinct = set(per_cpu.values())
    model = read(root, "/proc/device-tree/model")
    meminfo = read(root, "/proc/meminfo") or ""
    memory = next((int(line.split()[1]) * 1024 for line in meminfo.splitlines() if line.startswith("MemTotal:")), None)
    board = {
        "architecture": "arm64" if info.machine == "aarch64" else info.machine,
        "deviceModel": model.replace("\x00", "").strip() if model else None,
        "os": os_release(root),
        "kernel": info.release,
        "cpuGovernor": distinct.pop() if len(distinct) == 1 else ("mixed" if distinct else None),
        "storage": storage,
        "network": network,
        "executionMode": "physical",
        "system": info.system,
        "machine": info.machine,
    }
    detail = {"cpuGovernors": per_cpu, "memoryBytes": memory, "cpuinfo": read(root, "/proc/cpuinfo"),
              "nvpmodel": power_mode(), "thermalCelsius": thermal(root)}
    problems = [name for name, value in board.items() if value in (None, "")]
    if board["machine"] != "aarch64":
        problems.append("machine is not aarch64 (D-T14: B4.2 needs a physical ARM64 board)")
    if board["cpuGovernor"] != "performance":
        problems.append("cpuGovernor is not performance on every CPU")
    return {"schema": "opentsx.board-identity.v1", "board": board, "detail": detail, "problems": problems}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--storage", required=True, help='storage of the run, for example "Samsung T7 USB 3 SSD, ext4"')
    parser.add_argument("--network", required=True,
                        help='broker connection, for example "local KafScale" or "1 GbE to KafScale host"')
    parser.add_argument("--output", type=Path, required=True, help="new JSON file; an existing file is never overwritten")
    parser.add_argument("--root", type=Path, default=Path("/"), help=argparse.SUPPRESS)
    arguments = parser.parse_args(argv)
    result = identity(arguments.root, arguments.storage, arguments.network)
    with arguments.output.open("x", encoding="utf-8") as target:
        target.write(json.dumps(result, indent=2, sort_keys=True) + "\n")
    for problem in result["problems"]:
        print("Not ready for B4.2: " + problem, file=sys.stderr)
    return 1 if result["problems"] else 0


if __name__ == "__main__":
    sys.exit(main())
