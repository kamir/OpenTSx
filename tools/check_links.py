#!/usr/bin/env python3
"""Checks relative links and image links in the maintained Markdown documentation.

Usage: python3 tools/check_links.py [root]
Exits 0 when every link target exists, 1 when a target does not exist, and 2 when the root is not a directory or
holds no .md file to check. Skips every directory named in SKIP_DIRS, among them archive/ and external/ (the
Wayang submodule).
External links (http, https, mailto) are not fetched; anchors are not checked.
"""
import argparse
import pathlib
import re
import sys

SKIP_DIRS = {"archive", "external", "target", "node_modules", ".git", "research_notes", "reports", ".pytest_cache",
             "sample_data", ".stage"}  # .stage: deploy/docker/stage.sh output (copies of READMEs with relative links)
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"```.*?```", re.S)
ROOT_HINT = "pass the repository root or another directory that holds .md files"


def usage_error(message: str) -> int:
    print(f"check_links.py: {message}", file=sys.stderr)
    return 2


def main(root: pathlib.Path) -> int:
    if not root.is_dir():
        return usage_error(f"root: {root} is not a directory; {ROOT_HINT}")
    broken = []
    files = [p for p in root.rglob("*.md") if not (set(p.relative_to(root).parts[:-1]) & SKIP_DIRS)]
    if not files:
        return usage_error(f"root: {root} holds no .md file to check; {ROOT_HINT}")
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
    parser = argparse.ArgumentParser(prog="check_links.py", description=__doc__.splitlines()[0])
    parser.add_argument("root", nargs="?", default=".", help="directory to check, the current directory by default")
    sys.exit(main(pathlib.Path(parser.parse_args().root).resolve()))
