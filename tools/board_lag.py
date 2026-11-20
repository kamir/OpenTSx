#!/usr/bin/env python3
# Runs on the board itself; keep it compatible with Python 3.6 (Ubuntu 18.04, JetPack 4.6).
"""Print the lag of a running B4.2 condition for board_resource_sampler.py --lag-command.

The workload driver (BoardWorkload track-a or track-b with --lag-file) writes the
lag twice per second: the records in the input topic minus the records the run
has processed. This command prints that number. It fails instead of printing a
stale or malformed value, so the sampler stops rather than record a wrong lag.
"""

import argparse
from pathlib import Path
import sys
import time

MAX_AGE_SECONDS = 3.0


def read(path, max_age=MAX_AGE_SECONDS, now=time.time):
    age = now() - path.stat().st_mtime
    if age > max_age:
        raise ValueError("lag file is {:.1f} s old; the driver stopped writing it".format(age))
    text = path.read_text(encoding="ascii")
    if not text.endswith("\n") or not text[:-1].isdigit():
        raise ValueError("lag file must hold one non-negative integer line, holds {!r}".format(text))
    return int(text[:-1])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--file", type=Path, required=True, help="the --lag-file of the running condition")
    parser.add_argument("--max-age", type=float, default=MAX_AGE_SECONDS, help="oldest accepted value in seconds")
    arguments = parser.parse_args(argv)
    try:
        print(read(arguments.file, arguments.max_age))
        return 0
    except (OSError, ValueError) as error:
        print("Lag unavailable: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
