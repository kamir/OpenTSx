#!/usr/bin/env python3
# Runs on the board itself; keep it compatible with Python 3.6 (Ubuntu 18.04, JetPack 4.6).
"""Sample RSS, CPU time and lag of one process once per second for the B4.2 board protocol.

The CSV has exactly the columns `wayang_board_evaluation.py validate` accepts:
elapsed_seconds, rss_bytes, cpu_seconds, lag_records. Sample i is taken in
second i after the start, so every whole second of the run is covered. A missed
second, a process that ends early or a failing lag command stops the run with
an error instead of writing evidence the validator would reject later.
"""

import argparse
import csv
import os
from pathlib import Path
import subprocess
import sys
import time

FIELDS = ("elapsed_seconds", "rss_bytes", "cpu_seconds", "lag_records")
OFFSET = 0.05


def process_sample(proc, pid, ticks):
    """RSS in bytes and CPU seconds (user plus system) of one process from /proc."""
    status = (proc / str(pid) / "status").read_text(encoding="utf-8")
    rss = None
    for line in status.splitlines():
        if line.startswith("VmRSS:"):
            parts = line.split()
            if len(parts) != 3 or parts[2] != "kB" or not parts[1].isdigit():
                raise ValueError("VmRSS has an unexpected format")
            rss = int(parts[1]) * 1024
    if rss is None:
        raise ValueError(f"process {pid} reports no VmRSS; it may have exited")
    stat = (proc / str(pid) / "stat").read_text(encoding="utf-8")
    # the command name may contain spaces and parentheses: fields start after the last ')'
    fields = stat[stat.rindex(")") + 2:].split()
    utime, stime = int(fields[11]), int(fields[12])
    return rss, (utime + stime) / ticks


def lag(command, timeout):
    if not command:
        return 0
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True,
                            timeout=timeout, check=True)
    value = result.stdout.strip()
    if not value.isdigit():
        raise ValueError(f"lag command must print one non-negative integer, printed {value!r}")
    return int(value)


def sample(pid, duration, output, *, proc=Path("/proc"), lag_command=None, ticks=None,
           clock=time.monotonic, sleep=time.sleep):
    if duration < 1 or duration != int(duration):
        raise ValueError("duration must be a positive whole number of seconds")
    ticks = ticks or os.sysconf("SC_CLK_TCK")
    with output.open("x", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, lineterminator="\n")
        writer.writerow(FIELDS)
        start = clock()
        previous_cpu = -1.0
        for second in range(int(duration)):
            due = start + second + OFFSET
            now = clock()
            if now < due:
                sleep(due - now)
            rss, cpu = process_sample(proc, pid, ticks)
            records = lag(lag_command, 0.5)
            elapsed = clock() - start
            if int(elapsed) != second:
                raise RuntimeError(f"sample {second} was taken at {elapsed:.3f} s; the one-second cadence was missed")
            if cpu < previous_cpu:
                raise RuntimeError("CPU time decreased; the process was replaced")
            previous_cpu = cpu
            writer.writerow((f"{elapsed:.3f}", rss, f"{cpu:.2f}", records))
            target.flush()
    return int(duration)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pid", type=int, required=True, help="process to measure (the JVM or tsx-runner)")
    parser.add_argument("--duration", type=int, required=True,
                        help="seconds to sample, the run's durationSeconds (2100 = warm-up plus measurement)")
    parser.add_argument("--output", type=Path, required=True, help="new CSV file; an existing file is never overwritten")
    parser.add_argument("--lag-command", nargs=argparse.REMAINDER,
                        help="command that prints the current lag in records; must answer within 0.5 s")
    arguments = parser.parse_args(argv)
    try:
        count = sample(arguments.pid, arguments.duration, arguments.output, lag_command=arguments.lag_command)
        print(f"Wrote {count} one-second samples to {arguments.output}.")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print("Sampling failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
