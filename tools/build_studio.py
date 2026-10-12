#!/usr/bin/env python3
"""Builds docs/prototypes/smart-topics-studio.html from the template and the scenario data (all StudioScenarios).

    java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.studio.StudioExport /tmp/studio.json
    python3 tools/build_studio.py /tmp/studio.json
"""
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parent.parent
template = (root / "docs/prototypes/smart-topics-studio.template.html").read_text(encoding="utf-8")
data = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
head = '<!doctype html>\n<html lang="de">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1">\n'
body = template.replace("/*DATA*/null", data)
style_end = body.index("</style>") + len("</style>")
out = head + body[:style_end] + "\n</head>\n<body>\n" + body[style_end:] + "\n</body>\n</html>\n"
(root / "docs/prototypes/smart-topics-studio.html").write_text(out, encoding="utf-8")
print("wrote docs/prototypes/smart-topics-studio.html")
