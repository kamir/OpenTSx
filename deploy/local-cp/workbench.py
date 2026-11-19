"""Build, test and operate the connected or finite research workbench."""

import argparse
import fnmatch
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from email.parser import BytesParser

ROOT = Path(__file__).resolve().parents[2]
LOCAL = ROOT / "deploy/local-cp"
BUILD = LOCAL / ".build"
PYTHON_EXCLUDES = ("__pycache__", "*.egg-info", "build", "dist", ".pytest_cache")
FRONTEND_EXCLUDES = ("node_modules", "dist", "coverage", ".vite", "*.tsbuildinfo")


def run(command, **kwargs):
    print("+ " + " ".join(str(value) for value in command), flush=True)
    subprocess.run([str(value) for value in command], check=True, **kwargs)


def output(command):
    return subprocess.check_output(command, cwd=ROOT, text=True).strip()


def digest_tree(root):
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_file():
            digest.update(
                str(path.relative_to(root)).encode() + b"\0" + path.read_bytes()
            )
    return digest.hexdigest()


def validate_frontend(root):
    class Assets(HTMLParser):
        def handle_starttag(self, tag, attributes):
            for key, value in attributes:
                if key in ("src", "href") and value and value.startswith("/"):
                    target = (root / value.lstrip("/")).resolve()
                    if (
                        not target.is_relative_to(root.resolve())
                        or not target.is_file()
                    ):
                        raise ValueError(
                            f"built frontend asset is missing or outside its root: {value}"
                        )

    Assets().feed((root / "index.html").read_text())


def source_digest():
    digest = hashlib.sha256()
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
    )
    for name in sorted(set(names.split(b"\0")) - {b""}):
        path = ROOT / os.fsdecode(name)
        digest.update(
            name + b"\0" + (path.read_bytes() if path.is_file() else b"DELETED")
        )
    return digest.hexdigest()


def docker(settings, *args):
    return ["docker", "run", "--rm", "--platform", settings["platform"], *args]


def file_record(path):
    if path.is_symlink():
        raise RuntimeError("symbolic links are not valid build inputs or cache files")
    mode = path.stat().st_mode & 0o777
    if path.is_dir():
        return {"kind": "directory", "mode": mode}
    if not path.is_file():
        raise RuntimeError("unsupported build input or cache file")
    return {
        "kind": "file",
        "mode": mode,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def tree_records(root, excludes=(), omit=()):
    if root.is_symlink() or not root.is_dir():
        raise RuntimeError("invalid build input or cache directory")
    records = {}
    pending = [root]
    while pending:
        for path in sorted(pending.pop().iterdir()):
            relative = path.relative_to(root).as_posix()
            if relative in omit or any(
                fnmatch.fnmatchcase(path.name, pattern) for pattern in excludes
            ):
                continue
            record = file_record(path)
            records[relative] = record
            if record["kind"] == "directory":
                pending.append(path)
    return records


def build_key(kind, settings, epoch):
    inputs = {
        "platform": settings["platform"],
        "recipe": file_record(LOCAL / "workbench.py"),
    }
    if kind == "deps":
        inputs.update(
            image=settings["python_image"],
            lock=file_record(LOCAL / "requirements.lock"),
        )
    elif kind == "wheel":
        inputs.update(
            deps=build_key("deps", settings, epoch),
            epoch=epoch,
            builder=file_record(LOCAL / "build_python.py"),
            package=tree_records(ROOT / "python-package", PYTHON_EXCLUDES),
        )
    elif kind == "frontend":
        frontend = ROOT / "opentsx-saas-frontend"
        generated = tuple(
            path.name
            for path in frontend.iterdir()
            if any(
                fnmatch.fnmatchcase(path.name, pattern) for pattern in FRONTEND_EXCLUDES
            )
        )
        inputs.update(
            image=settings["node_image"],
            source=tree_records(frontend, omit=generated),
        )
    else:
        raise ValueError("unknown build cache kind")
    return hashlib.sha256(
        json.dumps(inputs, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def cache_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate cache manifest key")
        result[key] = value
    return result


def validate_cache(path, key):
    try:
        if path.is_symlink() or path.stat().st_mode & 0o777 != 0o755:
            raise ValueError("invalid cache directory mode or type")
        manifest = path / "manifest.json"
        if manifest.is_symlink() or manifest.stat().st_mode & 0o777 != 0o644:
            raise ValueError("invalid cache manifest mode or type")
        document = json.loads(manifest.read_text(), object_pairs_hook=cache_object)
        if (
            not isinstance(document, dict)
            or set(document) != {"key", "files"}
            or document["key"] != key
        ):
            raise ValueError("invalid cache manifest")
        records = tree_records(path, omit=("manifest.json",))
        if records != document["files"] or not any(
            record["kind"] == "file" for record in records.values()
        ):
            raise ValueError("cache files do not match their manifest")
        return records
    except (OSError, UnicodeError, ValueError, RuntimeError) as error:
        raise RuntimeError(f"invalid build cache: {path}") from error


def cache_stage(kind, key, stage, produce, paths, check):
    parent = BUILD / "cache" / kind
    for ancestor in (BUILD / "cache", parent):
        if ancestor.is_symlink():
            raise RuntimeError(f"invalid build cache: {ancestor}")
    entry = parent / key
    if entry.exists() or entry.is_symlink():
        validate_cache(entry, key)
        for path in entry.iterdir():
            if path.name == "manifest.json":
                continue
            target = stage / path.name
            if path.is_dir():
                shutil.copytree(path, target, dirs_exist_ok=True)
            else:
                shutil.copy2(path, target)
        check()
        print(f"build cache {kind}: hit", flush=True)
        return
    produce()
    check()
    parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="pending-", dir=parent))
    temporary.chmod(0o755)
    try:
        for path in paths():
            relative = path.relative_to(stage)
            file_record(path)
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if path.is_dir():
                tree_records(path)
                shutil.copytree(path, target)
            else:
                shutil.copy2(path, target)
        records = tree_records(temporary)
        if not any(record["kind"] == "file" for record in records.values()):
            raise RuntimeError("build cache producer returned no files")
        manifest = temporary / "manifest.json"
        manifest.write_text(
            json.dumps({"key": key, "files": records}, sort_keys=True) + "\n"
        )
        manifest.chmod(0o644)
        check()
        if entry.exists() or entry.is_symlink():
            if validate_cache(entry, key) != records:
                raise RuntimeError("build cache identity collision")
        else:
            temporary.rename(entry)
        print(f"build cache {kind}: miss", flush=True)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def lock(settings):
    BUILD.mkdir(exist_ok=True)
    wheels = BUILD / "locked-wheels"
    if wheels.exists():
        shutil.rmtree(wheels)
    wheels.mkdir()
    run(
        docker(
            settings,
            "--mount",
            f"type=bind,source={ROOT},target=/source,readonly",
            "--mount",
            f"type=bind,source={wheels},target=/wheels",
            settings["python_image"],
            "python",
            "-m",
            "pip",
            "download",
            "--only-binary=:all:",
            "--dest",
            "/wheels",
            "-r",
            "/source/deploy/local-cp/requirements.in",
        )
    )
    entries = []
    for wheel in wheels.glob("*.whl"):
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
        entries.append(
            f"{metadata['Name']}=={metadata['Version']} --hash=sha256:{hashlib.sha256(wheel.read_bytes()).hexdigest()}"
        )
    (LOCAL / "requirements.lock").write_text(
        "\n".join(sorted(entries, key=str.lower)) + "\n"
    )


def node(settings, target, test=False):
    frontend = ROOT / "opentsx-saas-frontend"
    (frontend / "node_modules").mkdir(exist_ok=True)
    (frontend / "dist").mkdir(exist_ok=True)
    command = (
        "npm ci --no-audit --no-fund; "
        + ("npm test -- --run; " if test else "")
        + "npm run build"
    )
    run(
        docker(
            settings,
            "--mount",
            f"type=bind,source={ROOT},target=/source,readonly",
            "--mount",
            "type=volume,target=/source/opentsx-saas-frontend/node_modules,volume-nocopy",
            "--mount",
            f"type=bind,source={target},target=/source/opentsx-saas-frontend/dist",
            "--workdir",
            "/source/opentsx-saas-frontend",
            settings["node_image"],
            "sh",
            "-ec",
            command,
        )
    )


def build(settings, profile="connected"):
    initial_source = source_digest()
    initial_revision = output(["git", "rev-parse", "HEAD"])
    BUILD.mkdir(exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="stage-", dir=BUILD))
    stage.chmod(0o755)
    (stage / "wheels").mkdir()
    (stage / "frontend").mkdir()
    shutil.copy2(LOCAL / "entrypoint.sh", stage / "entrypoint.sh")
    epoch = output(["git", "show", "-s", "--format=%ct", "HEAD"])
    keys = {
        kind: build_key(kind, settings, epoch) for kind in ("deps", "wheel", "frontend")
    }

    def check(kind):
        if (
            output(["git", "rev-parse", "HEAD"]) != initial_revision
            or build_key(kind, settings, epoch) != keys[kind]
        ):
            raise RuntimeError("source inputs changed during build")
        if kind == "frontend":
            validate_frontend(stage / "frontend")
        if kind == "wheel" and len(list(stage.glob("wheels/opentsx-*.whl"))) != 1:
            raise RuntimeError("application build must produce exactly one wheel")

    cache_stage(
        "deps",
        keys["deps"],
        stage,
        lambda: run(
            docker(
                settings,
                "--mount",
                f"type=bind,source={ROOT},target=/source,readonly",
                "--mount",
                f"type=bind,source={stage},target=/out",
                settings["python_image"],
                "python",
                "-m",
                "pip",
                "download",
                "--only-binary=:all:",
                "--require-hashes",
                "--dest",
                "/out/wheels",
                "-r",
                "/source/deploy/local-cp/requirements.lock",
            )
        ),
        lambda: [stage / "wheels"],
        lambda: check("deps"),
    )
    cache_stage(
        "wheel",
        keys["wheel"],
        stage,
        lambda: run(
            docker(
                settings,
                "--env",
                f"SOURCE_DATE_EPOCH={epoch}",
                "--mount",
                f"type=bind,source={ROOT},target=/source,readonly",
                "--mount",
                f"type=bind,source={stage},target=/out",
                settings["python_image"],
                "python",
                "/source/deploy/local-cp/build_python.py",
            )
        ),
        lambda: [
            *stage.glob("wheels/opentsx-*.whl"),
            stage / "runtime.lock",
            stage / "python-build.json",
        ],
        lambda: check("wheel"),
    )
    cache_stage(
        "frontend",
        keys["frontend"],
        stage,
        lambda: node(settings, stage / "frontend"),
        lambda: [stage / "frontend"],
        lambda: check("frontend"),
    )
    validate_frontend(stage / "frontend")
    if (
        source_digest() != initial_source
        or output(["git", "rev-parse", "HEAD"]) != initial_revision
    ):
        raise RuntimeError("source changed during build; rebuild a fixed source tree")
    images = {"python": settings["python_image"], "node": settings["node_image"]}
    if profile == "connected":
        images["registry"] = settings["registry_image"]
    manifest = {
        "build_inputs": keys,
        "profile": profile,
        "revision": output(["git", "rev-parse", "HEAD"]),
        "source_sha256": initial_source,
        "dirty": bool(output(["git", "status", "--porcelain"])),
        "frontend_sha256": digest_tree(stage / "frontend"),
        "runtime_lock_sha256": hashlib.sha256(
            (stage / "runtime.lock").read_bytes()
        ).hexdigest(),
        "images": images,
        **json.loads((stage / "python-build.json").read_text()),
    }
    raw = json.dumps(manifest, sort_keys=True, indent=2) + "\n"
    (stage / "manifest.json").write_text(raw)
    bundle = BUILD / "bundles" / hashlib.sha256(raw.encode()).hexdigest()
    bundle.parent.mkdir(exist_ok=True)
    if bundle.exists():
        if digest_tree(bundle) != digest_tree(stage):
            raise RuntimeError("bundle identity collision")
        shutil.rmtree(stage)
    else:
        stage.rename(bundle)
    env = {
        "OPENTSX_BUNDLE": str(bundle),
        "OPENTSX_PYTHON_IMAGE": settings["python_image"],
        "OPENTSX_WORKBENCH_PORT": settings["port"],
    }
    if profile == "connected":
        env.update(
            {
                "KAFKA_BOOTSTRAP": settings["bootstrap"],
                "OPENTSX_TOPIC_PREFIX": settings["prefix"],
                "OPENTSX_CP_NETWORK": settings["network"],
                "OPENTSX_REGISTRY_IMAGE": settings["registry_image"],
                "OPENTSX_REGISTRY_URL": settings["registry_url"],
            }
        )
    name = "compose.finite.env" if profile == "finite" else "compose.env"
    temporary = BUILD / (name + ".tmp")
    temporary.write_text("".join(f"{key}={value}\n" for key, value in env.items()))
    temporary.replace(BUILD / name)
    print(json.dumps(manifest, indent=2))


def compose(profile="connected"):
    finite = profile == "finite"
    return [
        "docker",
        "compose",
        "--project-name",
        "opentsx-workbench-finite" if finite else "opentsx-workbench",
        "--env-file",
        str(BUILD / ("compose.finite.env" if finite else "compose.env")),
        "--file",
        str(LOCAL / ("compose.finite.yaml" if finite else "compose.yaml")),
    ]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=(
            "lock",
            "build",
            "test",
            "up",
            "seed",
            "demo-seed",
            "demo-append",
            "demo-target",
            "demo-context",
            "status",
            "logs",
            "down",
        ),
    )
    parser.add_argument("--scenario", choices=("ar1", "sarimax"), default="ar1")
    parser.add_argument(
        "--profile", choices=("connected", "finite"), default="connected"
    )
    args = parser.parse_args(argv)
    if args.profile == "finite" and (
        args.command == "seed" or args.command.startswith("demo-")
    ):
        parser.error(f"finite profile does not support {args.command}")
    settings = json.loads((LOCAL / "settings.json").read_text())
    env_file = BUILD / (
        "compose.finite.env" if args.profile == "finite" else "compose.env"
    )
    if args.command == "lock":
        lock(settings)
    elif args.command == "build":
        build(settings, args.profile)
    elif args.command == "test":
        if not env_file.exists():
            parser.error("build the bundle before running tests")
        env = dict(line.split("=", 1) for line in env_file.read_text().splitlines())
        bundle = Path(env["OPENTSX_BUNDLE"])
        manifest = json.loads((bundle / "manifest.json").read_text())
        if manifest["source_sha256"] != source_digest():
            parser.error("source differs from the built bundle; run build before test")
        epoch = output(["git", "show", "-s", "--format=%ct", "HEAD"])
        keys = {
            kind: build_key(kind, settings, epoch)
            for kind in ("deps", "wheel", "frontend")
        }
        if manifest.get("build_inputs") != keys:
            parser.error(
                "build inputs differ from the built bundle; run build before test"
            )
        run(
            [
                sys.executable,
                "-m",
                "unittest",
                "discover",
                "-s",
                str(LOCAL),
                "-p",
                "test_workbench*.py",
                "-v",
            ]
        )
        run(
            docker(
                settings,
                "--mount",
                f"type=bind,source={bundle},target=/artifacts,readonly",
                "--mount",
                f"type=bind,source={ROOT},target=/source,readonly",
                "--env",
                "PYTHONDONTWRITEBYTECODE=1",
                "--workdir",
                "/tmp",
                settings["python_image"],
                "sh",
                "-ec",
                "python -m pip install --no-index --find-links /artifacts/wheels --require-hashes -r /artifacts/runtime.lock; "
                "python -m pytest /source/python-package/tests -q -p no:cacheprovider",
            )
        )
        target = Path(tempfile.mkdtemp(prefix="frontend-test-", dir=BUILD))
        node(settings, target, test=True)
    elif args.command == "up":
        run(
            compose(args.profile)
            + ["up", "--detach", "--wait", "--wait-timeout", "180"]
        )
        address = output(compose(args.profile) + ["port", "workbench", "8080"])
        match = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", address)
        if match is None or not 1 <= int(match[1]) <= 65535:
            raise RuntimeError("invalid published workbench address")
        print(f"http://{address}/workbench")
    elif args.command in (
        "seed",
        "demo-seed",
        "demo-append",
        "demo-target",
        "demo-context",
    ):
        scenario_args = (
            ["--scenario", args.scenario] if args.command.startswith("demo-") else []
        )
        run(
            compose(args.profile)
            + [
                "run",
                "--rm",
                "--no-deps",
                "workbench",
                args.command,
                "--bootstrap",
                settings["bootstrap"],
                "--prefix",
                settings["prefix"],
            ]
            + scenario_args
        )
    elif args.command == "status":
        run(compose(args.profile) + ["ps"])
    elif args.command == "logs":
        run(compose(args.profile) + ["logs", "--tail", "50", "workbench"])
    elif args.command == "down":
        run(compose(args.profile) + ["down"])


if __name__ == "__main__":
    main()
