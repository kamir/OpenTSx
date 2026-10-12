#!/usr/bin/env python3
"""Checks relative links in the maintained Markdown documentation (not in archive/).

Usage: python3 tools/check_links.py [root]   - exits 1 if a link target does not exist.
External links (http, https, mailto) are not fetched; anchors are not checked.
"""
import pathlib
import re
import sys

SKIP_DIRS = {"archive", "target", "node_modules", ".git", "research_notes", "reports", ".pytest_cache",
             "opentsx-saas-frontend", "opentsx-saas-backend", "exercises", "tests", "data", "sample_data"}
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"```.*?```", re.S)


def main(root: pathlib.Path) -> int:
    broken = []
    files = [p for p in root.rglob("*.md") if not (set(p.relative_to(root).parts[:-1]) & SKIP_DIRS)]
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
