#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


METADATA = ("docs/learning/ui-operators.v1.json", "docs/learning/library-operators.v1.json")
REQUIRED = {"id", "operator", "title", "surface", "objectives", "prerequisites", "tasks",
            "starter", "expected", "pitfalls", "references"}
REFERENCES = {"manual", "spec", "source", "tests", "vectors"}
SOURCE_REPOSITORY = "kamir/OpenTSx"


class SyncError(Exception):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def object_pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise SyncError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=object_pairs,
                          parse_constant=lambda value: invalid(f"invalid JSON number: {value}"))
    except (OSError, UnicodeError, ValueError) as error:
        raise SyncError(f"cannot read JSON: {path.name}: {error}") from error


def invalid(message):
    raise SyncError(message)


def nonempty_text(value):
    return isinstance(value, str) and bool(value.strip())


def strings(value):
    return isinstance(value, list) and bool(value) and all(nonempty_text(item) for item in value)


def contained(root, path, message):
    try:
        path.resolve().relative_to(root)
    except ValueError as error:
        raise SyncError(message) from error
    return path


def reference(root, value):
    if not nonempty_text(value):
        invalid("invalid reference: expected a relative path")
    name, marker, fragment = value.partition("#")
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or ".." in path.parts or "\\" in name
            or ":" in name or "?" in name or "%" in name or (marker and not fragment)):
        invalid(f"invalid reference: {value}")
    target = contained(root, root / path, f"reference escapes source root: {value}")
    if not target.is_file():
        invalid(f"reference does not exist: {value}")
    if marker:
        try:
            text = target.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as error:
            raise SyncError(f"cannot read reference text: {value}") from error
        line = re.fullmatch(r"L([1-9][0-9]*)(?:-L([1-9][0-9]*))?", fragment)
        if line:
            start = int(line[1])
            end = int(line[2] or line[1])
            found = start <= end <= len(text.splitlines())
        elif target.suffix.lower() == ".md":
            anchors = []
            counts = {}
            for heading in re.findall(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$", text, re.MULTILINE):
                anchor = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
                count = counts.get(anchor, 0)
                counts[anchor] = count + 1
                anchors.append(anchor + (f"-{count}" if count else ""))
            found = fragment in anchors or f'id="{fragment}"' in text
        else:
            found = fragment in text
        if not found:
            invalid(f"reference fragment does not exist: {value}")
    return name, target


def load_lessons(root):
    lessons = []
    files = {}
    ids = set()
    operators = set()
    for name, surface in zip(METADATA, ("ui", "library")):
        _, path = reference(root, name)
        data = read_json(path)
        if (not isinstance(data, dict) or set(data) != {"schema", "lessons"}
                or data["schema"] != "opentsx.learning.v1"
                or not isinstance(data["lessons"], list) or not data["lessons"]):
            invalid(f"invalid learning metadata: {name}")
        files[name] = sha256(path.read_bytes())
        for lesson in data["lessons"]:
            if (not isinstance(lesson, dict) or not REQUIRED <= set(lesson)
                    or set(lesson) - REQUIRED - {"parameterKeys", "expectedOutput"}):
                invalid(f"invalid lesson fields: {name}")
            lesson_id = lesson["id"]
            if not isinstance(lesson_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,95}", lesson_id):
                invalid(f"invalid lesson id: {name}")
            if lesson_id in ids:
                invalid(f"duplicate lesson id: {lesson_id}")
            ids.add(lesson_id)
            if (lesson["surface"] != surface or not nonempty_text(lesson["operator"])
                    or not nonempty_text(lesson["title"])):
                invalid(f"invalid lesson identity: {lesson_id}")
            operator = (surface, lesson["operator"])
            if operator in operators:
                invalid(f"duplicate operator lesson: {lesson_id}")
            operators.add(operator)
            for field in ("objectives", "prerequisites", "tasks", "expected", "pitfalls"):
                if not strings(lesson[field]):
                    invalid(f"invalid lesson {field}: {lesson_id}")
            if not isinstance(lesson["starter"], dict) or not lesson["starter"]:
                invalid(f"invalid lesson starter: {lesson_id}")
            refs = lesson["references"]
            if not isinstance(refs, dict) or set(refs) != REFERENCES:
                invalid(f"invalid lesson references: {lesson_id}")
            if surface == "ui":
                keys = lesson.get("parameterKeys")
                if (not isinstance(keys, list) or not all(nonempty_text(key) for key in keys)
                        or len(set(keys)) != len(keys) or "expectedOutput" not in lesson):
                    invalid(f"invalid UI lesson parameters: {lesson_id}")
            elif "parameterKeys" in lesson or "expectedOutput" in lesson:
                invalid(f"invalid library lesson fields: {lesson_id}")
            if surface == "library":
                starter = lesson["starter"]
                if (set(starter) != {"language", "code", "data"}
                        or starter["language"] not in ("java", "python")
                        or not nonempty_text(starter["code"])):
                    invalid(f"invalid library lesson starter: {lesson_id}")
            for kind, values in refs.items():
                if (not isinstance(values, list) or not all(nonempty_text(value) for value in values)
                        or (kind != "vectors" and not values) or len(set(values)) != len(values)):
                    invalid(f"invalid lesson {kind} references: {lesson_id}")
                for value in values:
                    ref_name, ref_path = reference(root, value)
                    files[ref_name] = sha256(ref_path.read_bytes())
            lessons.append(lesson)
    tool = Path(__file__).resolve()
    try:
        tool_name = tool.relative_to(root).as_posix()
    except ValueError:
        tool_name = None
    if tool_name is not None:
        files[tool_name] = sha256(tool.read_bytes())
    return sorted(lessons, key=lambda item: item["id"]), dict(sorted(files.items()))


def git(root, *arguments):
    result = subprocess.run(["git", "-C", str(root), *arguments], capture_output=True, text=True)
    if result.returncode:
        invalid("cannot inspect OpenTSx git revision")
    return result.stdout.strip()


def json_bytes(value):
    try:
        return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    except (TypeError, ValueError, UnicodeError) as error:
        raise SyncError("metadata contains an invalid JSON value") from error


def wrap_prose(text, prefix=""):
    lines = []
    indent = " " * len(prefix)
    line = prefix
    for token in re.findall(r"(?:`+[^`]*`+|\[[^\]]+\]\([^)]+\)|\S)+", text):
        separator = " " if len(line) > len(prefix) else ""
        if separator and len(line) + 1 + len(token) > 120:
            lines.append(line)
            line = indent + token
        else:
            line += separator + token
    lines.append(line)
    return lines


def bullets(items, numbered=False):
    lines = []
    for index, item in enumerate(items, 1):
        lines.extend(wrap_prose(item, f"{index}. " if numbered else "- "))
    return lines


def render_lesson(lesson):
    lines = [f'# {lesson["title"]}', "", "This lesson is generated from OpenTSx learning metadata.", "",
             f'Operator: `{lesson["operator"]}`. Surface: `{lesson["surface"]}`.', "",
             "## Set learning objectives", "", *bullets(lesson["objectives"]), "",
             "## Prepare", "", *bullets(lesson["prerequisites"]), "",
             "## Complete the tasks", "", *bullets(lesson["tasks"], numbered=True), "",
             "## Use the starter workload", ""]
    starter = lesson["starter"]
    if lesson["surface"] == "library":
        code = starter["code"].rstrip("\n")
        fence = "`" * max(3, 1 + max((len(run) for run in re.findall(r"`+", code)), default=0))
        lines.extend([f'{fence}{starter["language"]}', code, fence, "", "Starter data:", "",
                      "```json", json_bytes(starter["data"]).decode().rstrip(), "```", ""])
    else:
        lines.extend(["```json", json_bytes(starter).decode().rstrip(), "```", ""])
    if lesson["surface"] == "ui":
        keys = ", ".join(f"`{key}`" for key in lesson["parameterKeys"]) or "None"
        lines.extend(wrap_prose(f"Parameter keys: {keys}."))
        lines.extend(["", "Expected output:", "", "```json",
                      json_bytes(lesson["expectedOutput"]).decode().rstrip(), "```", ""])
    lines.extend(["## Check the expected results", "", *bullets(lesson["expected"]), "",
                  "## Validate your work", "",
                  "- [ ] Execute the referenced regression cases against the pinned OpenTSx source.",
                  "- [ ] Compare results using the tolerances stated in the workload and SPEC.",
                  "- [ ] Run the student synchronization check.", "",
                  "## Avoid common pitfalls", "", *bullets(lesson["pitfalls"]), "",
                  "## Read the source contracts", "",
                  "Resolve these paths from the OpenTSx checkout supplied to the synchronization command.", ""])
    for key in sorted(REFERENCES):
        values = lesson["references"][key]
        if values:
            lines.extend(f'- {key.capitalize()}: `OpenTSx/{value}`' for value in values)
        else:
            lines.append(f"- {key.capitalize()}: No shared vector file is referenced by this lesson.")
    return ("\n".join(lines) + "\n").encode("utf-8")


def generate(root):
    lessons, files = load_lessons(root)
    commit = git(root, "rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        invalid("invalid OpenTSx git revision")
    dirty = bool(git(root, "status", "--porcelain", "--untracked-files=all"))
    output = {f'docs/operators/{lesson["id"]}.md': render_lesson(lesson) for lesson in lessons}
    index = ["# Complete the operator exercises", "",
             "Use these generated lessons with the OpenTSx revision recorded in [alignment.json](../../alignment.json).", "",
             "You need Python 3.9 or later and matching local OpenTSx and student checkouts.", "",
             "## Choose a lesson", "", "| Lesson | Operator | Surface |", "| --- | --- | --- |"]
    index.extend(f'| [{lesson["title"]}]({lesson["id"]}.md) | `{lesson["operator"]}` | `{lesson["surface"]}` |'
                 for lesson in lessons)
    index.extend(["", "## Check source alignment", "",
                  "Run the command in the [repository instructions](../../README.md#check-source-alignment).", "",
                  "The lessons describe implemented UI execution and public library families.",
                  "A numerical workload does not establish a processor, Wayang or Rust runtime binding.",
                  "A whole topic is the smallest deployment unit, regardless of its partition count.",
                  "Partition positions describe internal reads and checkpoints; they do not split deployment ownership."])
    output["docs/operators/README.md"] = ("\n".join(index) + "\n").encode()
    readme = ["# Practice OpenTSx operators", "",
              "These exercises are generated from the source-content lock in [alignment.json](alignment.json).", "",
              "You need Python 3.9 or later, Git and a local OpenTSx checkout at the locked source revision.", "",
              "## Choose the exercises", "",
              "- [Implemented UI and public library operator lessons](docs/operators/README.md)",
              "- [Original ATM exercise](docs/intro.adoc)",
              "- [Original ATM workbook](raw_data/2024-12-09_ATM_Branch_Data.xlsx)", "",
              "The original ATM material is separate from the generated operator lessons.", "",
              "## Check source alignment", "",
              "Run from this repository root. Replace `<OpenTSx-root>` with the absolute path to the matching checkout.", "",
              "```bash", "python3 tools/check_alignment.py --opentsx-root <OpenTSx-root> --student-root .", "```", "",
              "The check compares the source commit, referenced file hashes, lesson inventory and generated bytes.",
              "Missing references, stale source files and edited generated lessons fail the check.",
              "A dirty source lock identifies local content that is not reproduced by the recorded commit alone.",
              "The publication workflow requires a clean, published OpenTSx source commit.", "",
              "## Regenerate the lessons", "",
              "Edit the learning metadata in OpenTSx, then run from this repository root:", "",
              "```bash", "python3 <OpenTSx-root>/tools/sync_operator_exercises.py --opentsx-root <OpenTSx-root> --student-root .",
              "python3 tools/check_alignment.py --opentsx-root <OpenTSx-root> --student-root .", "```", "",
              "Do not edit generated lesson files, the generated index or alignment.json.",
              "The checker uses local files only. CI checks out the exact published source commit from the lock."]
    output["README.md"] = ("\n".join(readme) + "\n").encode()
    alignment = {"schema": "opentsx.student-alignment.v1",
                 "source": {"repository": SOURCE_REPOSITORY, "commit": commit, "dirty": dirty, "files": files},
                 "lessons": [{"id": lesson["id"], "operator": lesson["operator"], "surface": lesson["surface"]}
                             for lesson in lessons],
                 "generated": {name: sha256(data) for name, data in sorted(output.items())}}
    output["alignment.json"] = json_bytes(alignment)
    return output, len(lessons), len(files)


def synchronize(source, student, check):
    source = source.resolve(strict=True)
    student = student.resolve(strict=True)
    if source == student or source in student.parents or student in source.parents:
        invalid("OpenTSx and student roots must be separate checkouts")
    output, lessons, files = generate(source)
    directory = contained(student, student / "docs/operators", "output escapes student root")
    actual = set()
    if directory.exists():
        for path in directory.rglob("*"):
            contained(student, path, "output escapes student root")
            if path.is_file():
                actual.add(path.relative_to(student).as_posix())
    unknown = actual - set(output)
    if unknown:
        lock_path = contained(student, student / "alignment.json", "output escapes student root")
        previous = read_json(lock_path) if not check and lock_path.is_file() else {}
        generated = previous.get("generated", {}) if isinstance(previous, dict) else {}
        if (check or not isinstance(generated, dict)
                or any(generated.get(name) != sha256((student / name).read_bytes()) for name in unknown)):
            invalid("unknown generated lesson: " + ", ".join(sorted(unknown)))
        for name in unknown:
            (student / name).unlink()
    differences = []
    for name, data in output.items():
        path = contained(student, student / name, "output escapes student root")
        if check:
            if not path.is_file() or path.read_bytes() != data:
                differences.append(name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if differences:
        invalid("generated content differs: " + ", ".join(differences))
    print(f'{"Checked" if check else "Generated"} {lessons} lessons and {files} source hashes.')


def main():
    parser = argparse.ArgumentParser(description="Generate or check student lessons from OpenTSx learning metadata.")
    parser.add_argument("--opentsx-root", required=True, type=Path)
    parser.add_argument("--student-root", required=True, type=Path)
    parser.add_argument("--check", action="store_true", help="Fail when generated content or source hashes differ.")
    arguments = parser.parse_args()
    try:
        synchronize(arguments.opentsx_root, arguments.student_root, arguments.check)
    except (SyncError, OSError, UnicodeError, RecursionError) as error:
        print(f"operator exercise sync: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
