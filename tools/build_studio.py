#!/usr/bin/env python3
"""Builds docs/prototypes/smart-topics-studio.html from the template and the scenario data (all StudioScenarios).

    java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.studio.StudioExport /tmp/studio.json
    python3 tools/build_studio.py /tmp/studio.json

Exit codes: 0 written, 1 the template or the data is not usable, 2 usage error.
"""
import argparse
import json
import pathlib
import sys

MARKER = "/*DATA*/null"
TEMPLATE = "docs/prototypes/smart-topics-studio.template.html"
PAGE = "docs/prototypes/smart-topics-studio.html"


def fail(message: str) -> None:
    print(f"build_studio.py: {message}", file=sys.stderr)
    sys.exit(1)


parser = argparse.ArgumentParser(prog="build_studio.py", description=__doc__.splitlines()[0])
parser.add_argument("data", help="scenario data as JSON, written by org.opentsx.twin.studio.StudioExport")
args = parser.parse_args()

root = pathlib.Path(__file__).resolve().parent.parent
template = (root / TEMPLATE).read_text(encoding="utf-8")
if template.count(MARKER) != 1:
    fail(f"{TEMPLATE}: the marker {MARKER} occurs {template.count(MARKER)} times; expected exactly once")
try:
    data = pathlib.Path(args.data).read_text(encoding="utf-8")
except OSError as e:
    fail(f"{args.data}: cannot be read ({e.strerror}); expected the output of org.opentsx.twin.studio.StudioExport")
try:
    json.loads(data)
except json.JSONDecodeError as e:
    fail(f"{args.data}: not JSON ({e}); expected the output of org.opentsx.twin.studio.StudioExport")
head = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n')
body = template.replace(MARKER, data)
style_end = body.index("</style>") + len("</style>")
out = head + body[:style_end] + "\n</head>\n<body>\n" + body[style_end:] + "\n</body>\n</html>\n"
(root / PAGE).write_text(out, encoding="utf-8")
print(f"wrote {PAGE}")
