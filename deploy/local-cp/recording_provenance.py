import argparse
import ast
import json
from pathlib import Path
import re
import subprocess

import workbench


def provenance(output):
    root = workbench.ROOT.resolve()
    if workbench.output(["git", "status", "--porcelain", "--untracked-files=all"]):
        raise ValueError("recording requires a clean Git checkout")
    destination = Path(output).resolve()
    if destination.is_relative_to(root):
        candidate = (destination / "recording.json").relative_to(root).as_posix()
        try:
            ignored = workbench.output(["git", "check-ignore", "--", candidate])
        except subprocess.CalledProcessError as failure:
            if failure.returncode == 1:
                raise ValueError(
                    "recording output inside the repository must be ignored by Git"
                ) from failure
            raise
        if not ignored:
            raise ValueError("Git did not confirm an ignored recording output")
    revision = workbench.output(["git", "rev-parse", "HEAD"])
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Git did not return a full source revision")
    epoch = workbench.output(["git", "show", "-s", "--format=%ct", "HEAD"])
    if not re.fullmatch(r"[0-9]+", epoch):
        raise ValueError("Git did not return a source date epoch")
    settings = json.loads((workbench.LOCAL / "settings.json").read_text())
    keys = {
        kind: workbench.build_key(kind, settings, epoch)
        for kind in ("deps", "wheel", "frontend")
    }
    source = workbench.source_digest()
    if not re.fullmatch(r"[0-9a-f]{64}", source) or any(
        not re.fullmatch(r"[0-9a-f]{64}", value) for value in keys.values()
    ):
        raise ValueError("build inputs did not return valid source hashes")
    module = ast.parse((root / "python-package/opentsx/__init__.py").read_text())
    versions = [
        statement.value
        for statement in module.body
        if isinstance(statement, ast.Assign)
        and any(
            isinstance(target, ast.Name) and target.id == "__version__"
            for target in statement.targets
        )
    ]
    if len(versions) != 1:
        raise ValueError("Python package must declare one literal version")
    version = ast.literal_eval(versions[0])
    if not isinstance(version, str) or not re.fullmatch(
        r"[0-9]+\.[0-9]+\.[0-9]+", version
    ):
        raise ValueError("Python package must declare a stable literal version")
    images = {"python": settings["python_image"], "node": settings["node_image"]}
    if any(not isinstance(value, str) or not value for value in images.values()):
        raise ValueError("build settings must declare Python and Node images")
    return {
        "revision": revision,
        "source_sha256": source,
        "source_date_epoch": epoch,
        "build_inputs": keys,
        "images": images,
        "dirty": False,
        "version": version,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Read clean local provenance for a finite demo recording."
    )
    parser.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    try:
        result = provenance(args.output)
    except (
        OSError,
        ValueError,
        RuntimeError,
        KeyError,
        subprocess.CalledProcessError,
    ) as failure:
        parser.exit(1, f"recording provenance failed: {failure}\n")
    print(json.dumps(result, sort_keys=True, allow_nan=False))


if __name__ == "__main__":
    main()
