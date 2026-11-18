import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE_COMMIT = "2809a3a20bf40ed8a2b82cd93215cb59396efb0a"
SOURCE_URL = "https://github.com/KafScale/platform.git"
COMPILER_IMAGE = "golang:1.25.2-alpine@sha256:06cdd34bd531b810650e47762c01e025eb9b1c7eadd191553b91c9f2d549fae8"
DIRECTORY = ROOT / "target/kafscale-proxy-linux"
BINARIES = ("kafscale-broker", "kafscale-proxy", "kafscale-cluster")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def identity():
    return {"sourceCommit": SOURCE_COMMIT, "sourceVersion": "v1.6.0", "compilerImage": COMPILER_IMAGE,
            "launcherSha256": digest(ROOT / "tools/kafscale_proxy_cluster.go"),
            "builderSha256": digest(Path(__file__).resolve())}


def broker_overlay(source, output, source_root=None, output_root=None):
    path = source / "cmd/broker/main.go"
    original = path.read_text()
    marker = 'brokerVersion        = "dev"'
    if original.count(marker) != 1:
        raise RuntimeError("pinned KafScale broker version declaration differs")
    stamped = output / "broker-main.go"
    stamped.write_text(original.replace(marker, 'brokerVersion        = "v1.6.0"'))
    overlay = output / "broker-overlay.json"
    overlay.write_text(json.dumps({"Replace": {
        str(Path(source_root or source) / "cmd/broker/main.go"): str(Path(output_root or output) / stamped.name)
    }}) + "\n")
    return overlay


def verify():
    manifest = DIRECTORY / "manifest.json"
    if not manifest.is_file() or manifest.is_symlink():
        raise RuntimeError("KafScale Proxy bundle is missing; run python3 tools/build_kafscale_proxy.py")
    value = json.loads(manifest.read_text())
    if value.get("identity") != identity() or set(value.get("binaries", {})) != set(BINARIES):
        raise RuntimeError("KafScale Proxy bundle sources differ; rebuild the bundle")
    for name in BINARIES:
        path = DIRECTORY / name
        if path.is_symlink() or not path.is_file() or not os.access(path, os.X_OK) or digest(path) != value["binaries"][name]:
            raise RuntimeError(f"KafScale Proxy bundle binary differs: {name}")
    return value


def build():
    for executable in ("docker", "git"):
        if shutil.which(executable) is None:
            raise RuntimeError(f"required executable is missing: {executable}")
    source = ROOT / "target/kafscale-v1.6.0"
    cache = ROOT / "target/kafscale-go-cache"
    for path in (ROOT / "target", source, cache, DIRECTORY):
        if path.is_symlink():
            raise RuntimeError(f"build directory must not be a symbolic link: {path}")
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    cache.mkdir(exist_ok=True)
    if not source.exists():
        subprocess.run(["git", "clone", "--branch", "v1.6.0", "--single-branch", SOURCE_URL, str(source)], check=True)
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=source, text=True, capture_output=True, check=True).stdout.strip()
    if revision != SOURCE_COMMIT:
        raise RuntimeError("KafScale source checkout differs from the pinned v1.6.0 commit")
    subprocess.run(["git", "diff", "--exit-code", "HEAD"], cwd=source, check=True)
    broker_overlay(source, DIRECTORY, "/source", "/artifacts")
    (DIRECTORY / "manifest.json").unlink(missing_ok=True)
    command = ["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
               "--mount", f"type=bind,src={source},dst=/source,readonly",
               "--mount", f"type=bind,src={ROOT / 'tools/kafscale_proxy_cluster.go'},dst=/cluster.go,readonly",
               "--mount", f"type=bind,src={DIRECTORY},dst=/artifacts",
               "--mount", f"type=bind,src={cache},dst=/cache",
               "--workdir", "/source", "--env", "CGO_ENABLED=0", "--env", "GOTOOLCHAIN=local",
               "--env", "GOPATH=/cache/modules", "--env", "GOCACHE=/cache/build", COMPILER_IMAGE,
               "sh", "-ec", "go build -overlay=/artifacts/broker-overlay.json -trimpath -buildvcs=false -ldflags=-buildid= -o /artifacts/kafscale-broker ./cmd/broker; "
               "go build -trimpath -buildvcs=false -ldflags=-buildid= -o /artifacts/kafscale-proxy ./cmd/proxy; "
               "go build -trimpath -buildvcs=false -ldflags=-buildid= -o /artifacts/kafscale-cluster /cluster.go"]
    subprocess.run(command, check=True)
    manifest = {"identity": identity(), "binaries": {name: digest(DIRECTORY / name) for name in BINARIES}}
    (DIRECTORY / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return verify()


def main():
    parser = argparse.ArgumentParser(description="Build pinned KafScale v1.6.0 broker and Proxy binaries without building an image.")
    parser.add_argument("--check", action="store_true", help="verify the existing bundle without Docker or network access")
    args = parser.parse_args()
    value = verify() if args.check else build()
    print(json.dumps({"directory": str(DIRECTORY), **value}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
