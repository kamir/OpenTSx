"""Build, test and operate the local CP research workbench."""

import argparse
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile
from email.parser import BytesParser

ROOT = Path(__file__).resolve().parents[2]
LOCAL = ROOT / "deploy/local-cp"
BUILD = LOCAL / ".build"


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
    command = (
        "mkdir /tmp/ui; tar -C /source --exclude=node_modules --exclude=dist -cf - . | tar -C /tmp/ui -xf -; "
        "cd /tmp/ui; npm ci --no-audit --no-fund; "
        + ("npm test -- --run; npm run build; " if test else "npm run build; ")
        + "cp -R dist/. /out/"
    )
    run(
        docker(
            settings,
            "--mount",
            f"type=bind,source={ROOT / 'opentsx-saas-frontend'},target=/source,readonly",
            "--mount",
            f"type=bind,source={ROOT / 'docs/datamodel-2026'},target=/tmp/docs/datamodel-2026,readonly",
            "--mount",
            f"type=bind,source={ROOT / 'opentsx-model'},target=/tmp/opentsx-model,readonly",
            "--mount",
            f"type=bind,source={target},target=/out",
            settings["node_image"],
            "sh",
            "-ec",
            command,
        )
    )


def build(settings):
    initial_source = source_digest()
    initial_revision = output(["git", "rev-parse", "HEAD"])
    BUILD.mkdir(exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="stage-", dir=BUILD))
    (stage / "wheels").mkdir()
    (stage / "frontend").mkdir()
    shutil.copy2(LOCAL / "entrypoint.sh", stage / "entrypoint.sh")
    run(
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
    )
    epoch = output(["git", "show", "-s", "--format=%ct", "HEAD"])
    run(
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
    )
    node(settings, stage / "frontend")
    validate_frontend(stage / "frontend")
    if (
        source_digest() != initial_source
        or output(["git", "rev-parse", "HEAD"]) != initial_revision
    ):
        raise RuntimeError("source changed during build; rebuild a fixed source tree")
    manifest = {
        "revision": output(["git", "rev-parse", "HEAD"]),
        "source_sha256": initial_source,
        "dirty": bool(output(["git", "status", "--porcelain"])),
        "frontend_sha256": digest_tree(stage / "frontend"),
        "runtime_lock_sha256": hashlib.sha256(
            (stage / "runtime.lock").read_bytes()
        ).hexdigest(),
        "images": {"python": settings["python_image"], "node": settings["node_image"]},
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
        "KAFKA_BOOTSTRAP": settings["bootstrap"],
        "OPENTSX_TOPIC_PREFIX": settings["prefix"],
        "OPENTSX_CP_NETWORK": settings["network"],
        "OPENTSX_WORKBENCH_PORT": settings["port"],
    }
    temporary = BUILD / "compose.env.tmp"
    temporary.write_text("".join(f"{key}={value}\n" for key, value in env.items()))
    temporary.replace(BUILD / "compose.env")
    print(json.dumps(manifest, indent=2))


def compose():
    return [
        "docker",
        "compose",
        "--project-name",
        "opentsx-workbench",
        "--env-file",
        str(BUILD / "compose.env"),
        "--file",
        str(LOCAL / "compose.yaml"),
    ]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=("lock", "build", "test", "up", "seed", "status", "logs", "down"),
    )
    args = parser.parse_args(argv)
    settings = json.loads((LOCAL / "settings.json").read_text())
    if args.command == "lock":
        lock(settings)
    elif args.command == "build":
        build(settings)
    elif args.command == "test":
        if not (BUILD / "compose.env").exists():
            parser.error("build the bundle before running tests")
        env = dict(
            line.split("=", 1)
            for line in (BUILD / "compose.env").read_text().splitlines()
        )
        bundle = Path(env["OPENTSX_BUNDLE"])
        if (
            json.loads((bundle / "manifest.json").read_text())["source_sha256"]
            != source_digest()
        ):
            parser.error("source differs from the built bundle; run build before test")
        run(
            [
                sys.executable,
                "-m",
                "unittest",
                "discover",
                "-s",
                str(LOCAL),
                "-p",
                "test_workbench.py",
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
        run(compose() + ["up", "--detach", "--wait", "--wait-timeout", "180"])
        print(f"http://127.0.0.1:{settings['port']}/workbench")
    elif args.command == "seed":
        run(
            compose()
            + [
                "run",
                "--rm",
                "--no-deps",
                "workbench",
                "seed",
                "--bootstrap",
                settings["bootstrap"],
                "--prefix",
                settings["prefix"],
            ]
        )
    elif args.command == "status":
        run(compose() + ["ps"])
    elif args.command == "logs":
        run(compose() + ["logs", "--tail", "50", "workbench"])
    elif args.command == "down":
        run(compose() + ["down"])


if __name__ == "__main__":
    main()
