"""Build the Python wheel using the locked native runtime."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
from email.parser import BytesParser

source, output = Path("/source"), Path("/out")
subprocess.run(
    [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--no-index",
        "--find-links",
        "/out/wheels",
        "--require-hashes",
        "-r",
        "/source/deploy/local-cp/requirements.lock",
    ],
    check=True,
)
shutil.copytree(
    source / "python-package",
    "/tmp/package",
    ignore=shutil.ignore_patterns(
        "__pycache__", "*.egg-info", "build", "dist", ".pytest_cache"
    ),
)
subprocess.run(
    [
        sys.executable,
        "-m",
        "pip",
        "wheel",
        "/tmp/package",
        "--no-deps",
        "--no-build-isolation",
        "--wheel-dir",
        "/out/wheels",
    ],
    check=True,
)
wheel = next((output / "wheels").glob("opentsx-*.whl"))
lock = (source / "deploy/local-cp/requirements.lock").read_text()
with zipfile.ZipFile(wheel) as archive:
    metadata = BytesParser().parsebytes(
        archive.read(
            next(
                name
                for name in archive.namelist()
                if name.endswith(".dist-info/METADATA")
            )
        )
    )
lock += f"{metadata['Name']}=={metadata['Version']} --hash=sha256:{hashlib.sha256(wheel.read_bytes()).hexdigest()}\n"
(output / "runtime.lock").write_text(lock)
(output / "python-build.json").write_text(
    json.dumps(
        {
            "python": sys.version.split()[0],
            "wheel_sha256": hashlib.sha256(wheel.read_bytes()).hexdigest(),
            "source_date_epoch": os.environ["SOURCE_DATE_EPOCH"],
        },
        indent=2,
    )
    + "\n"
)
