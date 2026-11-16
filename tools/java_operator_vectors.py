#!/usr/bin/env python3
"""Regenerate the shared statistics, segmentation, SAX and data-quality vectors."""

import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    subprocess.run(
        [
            "mvn", "-B", "-ntp", "-pl", "opentsx-stats,opentsx-retrieval", "-am", "test",
            "-Dtest=StatsWindowVectorsTest,DqResultVectorsTest,SegmentThresholdVectorsTest,"
            "SegmentExtremumVectorsTest,SaxWordVectorsTest",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "-Dopentsx.vectors.generate=true",
        ],
        cwd=ROOT,
        check=True,
    )


if __name__ == "__main__":
    main()
