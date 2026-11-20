import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys


def file_facts(path, repository):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError("Cache evidence must be a regular file")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return {"path": path.relative_to(repository).as_posix(), "size": info.st_size,
            "mtime_ns": info.st_mtime_ns, "sha256": digest.hexdigest()}


def report(repository, output):
    if repository.is_symlink() or output.is_symlink():
        raise ValueError("Report paths must not be symbolic links")
    repository = repository.resolve()
    output = output.resolve()
    if output.is_relative_to(repository):
        raise ValueError("Report output must be outside the Maven repository")
    if repository.exists() and not repository.is_dir():
        raise ValueError("Maven repository must be a directory")
    tracking = []

    def walk_error(error):
        raise error

    parents = os.walk(repository, onerror=walk_error, followlinks=False) if repository.exists() else ()
    for parent, directories, names in parents:
        directory = Path(parent)
        if any((directory / name).is_symlink() for name in directories):
            raise ValueError("Maven repository directories must not be symbolic links")
        for name in sorted(names):
            if name != "_remote.repositories" and not name.endswith(".lastUpdated"):
                continue
            path = directory / name
            entry = file_facts(path, repository)
            if name.endswith(".lastUpdated"):
                errors = []
                for value in re.findall(r"(?m)^[^#!\r\n].*\.error=(.*)$", path.read_text(encoding="iso-8859-1")):
                    status = re.search(r"status code:\s*([1-5][0-9]{2})\b", value)
                    errors.append({"kind": "transfer-error" if value else "not-found",
                                   "http_status": int(status.group(1)) if status else None})
                entry["errors"] = errors
                artifact = path.with_suffix("")
                entry["artifact"] = (file_facts(artifact, repository)
                                     if artifact.exists() or artifact.is_symlink() else None)
            tracking.append(entry)
    result = {"repository_exists": repository.exists(),
              "tracking_files": sorted(tracking, key=lambda item: item["path"])}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Recorded " + str(len(tracking)) + " Maven tracking files")


def main():
    try:
        if len(sys.argv) != 3:
            raise ValueError("Expected the Maven repository directory and report output path")
        report(Path(sys.argv[1]), Path(sys.argv[2]))
    except (OSError, ValueError) as error:
        print("Maven cache report failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
