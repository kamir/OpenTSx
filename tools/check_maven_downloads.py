import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def verify():
    result = subprocess.run(["mvn", "--version"], check=True, capture_output=True, text=True)
    banner = re.sub(r"\x1b\[[0-9;]*m", "", result.stdout)
    version = re.search(r"(?m)^Apache Maven ([^\s]+)", banner)
    location = re.search(r"(?m)^Maven home: (.+)$", banner)
    if version is None or version.group(1) != "3.9.8" or location is None:
        raise ValueError("Maven 3.9.8 is required for the verified download retry policy")
    directory = Path(location.group(1).strip())
    if not directory.is_absolute():
        raise ValueError("Maven home must be an absolute path")
    libraries = directory / "lib"
    for name in ("maven-core-3.9.8.jar", "maven-resolver-transport-http-1.9.20.jar"):
        if not (libraries / name).is_file():
            raise ValueError("The pinned Maven distribution is incomplete")
    print("Maven 3.9.8; Maven Resolver 1.9.20", flush=True)
    with tempfile.TemporaryDirectory(prefix="opentsx-maven-retries-") as temporary:
        subprocess.run(["javac", "-cp", str(libraries / "*"), "-d", temporary,
                        "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                        str(ROOT / "tools/MavenDownloadRetryTest.java")], check=True)
        subprocess.run(["java", "-cp", os.pathsep.join((temporary, str(libraries / "*"))),
                        "MavenDownloadRetryTest", str(ROOT / ".mvn/maven.config")], check=True)


def main():
    try:
        verify()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("Maven download retry verification failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
