#!/usr/bin/env python3
"""Checks relative links in the maintained Markdown documentation.

Usage: python3 tools/check_links.py [root]   - exits 1 if a link target does not exist.
External links (http, https, mailto) are not fetched; anchors are not checked.
A file whose bytes a measurement manifest pins is generated evidence and stays as generated; its links name raw files
that are kept outside the repository, so it is not checked.
"""
import json
import pathlib
import re
import sys

SKIP_DIRS = {"archive", "target", "node_modules", ".git", "research_notes", "reports", ".pytest_cache",
             "opentsx-saas-frontend", "opentsx-saas-backend", "exercises", "tests", "data", "sample_data",
             ".stage"}  # .stage: deploy/docker/stage.sh output (copies of READMEs with relative links)
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"```.*?```", re.S)


def pinned(root: pathlib.Path) -> set:
    files = set()
    for manifest in root.glob("docs/datamodel-2026/measurements/*/manifest.json"):
        for name in json.loads(manifest.read_text(encoding="utf-8")).get("files", {}):
            files.add((manifest.parent / name).resolve())
    return files


def main(root: pathlib.Path) -> int:
    broken = []
    skip = pinned(root)
    files = [p for p in root.rglob("*.md")
             if not (set(p.relative_to(root).parts[:-1]) & SKIP_DIRS) and p.resolve() not in skip]
    for md in files:
        text = FENCE.sub("", md.read_text(encoding="utf-8", errors="replace"))
        for target in LINK.findall(text):
            if re.match(r"^[a-z]+:", target) or target.startswith("#"):
                continue
            path = target.split("#", 1)[0]
            if not path:
                continue
            resolved = (md.parent / path).resolve()
            if not resolved.exists():
                broken.append(f"{md.relative_to(root)}: {target}")
    for b in broken:
        print("broken link:", b)
    print(f"checked {len(files)} files, {len(broken)} broken links")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()))
