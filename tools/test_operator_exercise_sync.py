import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TOOL = Path(__file__).with_name("sync_operator_exercises.py")


class OperatorExerciseSyncTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / "opentsx"
        self.student = self.root / "student"
        self.source.mkdir()
        self.student.mkdir()
        for name in ("manual.md", "spec.md", "source.py", "test.py", "vectors.json"):
            (self.source / name).write_text("# Contract\n\noperator_symbol\n", encoding="utf-8")
        self.lesson = {
            "id": "ui-scale", "operator": "scale", "title": "Scale a series", "surface": "ui",
            "objectives": ["Apply a factor."], "prerequisites": ["Read the contract."],
            "tasks": ["Execute the starter input."], "starter": {"values": [1, 2], "factor": 2},
            "expected": ["The result is [2, 4]."], "pitfalls": ["Preserve the sample order."],
            "references": {"manual": ["manual.md#contract"], "spec": ["spec.md#contract"],
                           "source": ["source.py#operator_symbol"], "tests": ["test.py#operator_symbol"],
                           "vectors": ["vectors.json"]},
            "parameterKeys": ["factor"], "expectedOutput": [2, 4],
        }
        self.write_metadata()
        self.git("init", "--quiet")
        self.commit()

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.source), *arguments], check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self):
        self.git("add", ".")
        self.git("-c", "user.name=Test Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--quiet", "-m", "test(learning): create source fixture")

    def write_metadata(self, lessons=None, library_starter=None):
        directory = self.source / "docs/learning"
        directory.mkdir(parents=True, exist_ok=True)
        ui = {"schema": "opentsx.learning.v1", "lessons": lessons or [self.lesson]}
        library = copy.deepcopy(self.lesson)
        library.update(id="library-scale", surface="library")
        library.pop("parameterKeys")
        library.pop("expectedOutput")
        library["starter"] = library_starter or {
            "language": "python", "code": "from math import prod\nresult = prod([2, 3])",
            "data": {"values": [2, 3]},
        }
        for name, data in (("ui-operators.v1.json", ui),
                           ("library-operators.v1.json", {"schema": "opentsx.learning.v1", "lessons": [library]})):
            (directory / name).write_text(json.dumps(data) + "\n", encoding="utf-8")

    def run_tool(self, check=False, succeeds=True):
        command = [sys.executable, str(TOOL), "--opentsx-root", str(self.source),
                   "--student-root", str(self.student)]
        if check:
            command.append("--check")
        result = subprocess.run(command, capture_output=True, text=True)
        if succeeds:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_generates_deterministic_lessons_and_content_lock(self):
        self.run_tool()
        before = {path.relative_to(self.student): path.read_bytes()
                  for path in self.student.rglob("*") if path.is_file()}
        self.run_tool(check=True)
        self.run_tool()
        after = {path.relative_to(self.student): path.read_bytes()
                 for path in self.student.rglob("*") if path.is_file()}
        self.assertEqual(before, after)
        lock = json.loads((self.student / "alignment.json").read_text())
        self.assertEqual(lock["source"]["commit"], self.git("rev-parse", "HEAD"))
        self.assertFalse(lock["source"]["dirty"])
        self.assertIn("source.py", lock["source"]["files"])
        self.assertTrue((self.student / "docs/operators/ui-scale.md").is_file())

    def test_library_starter_renders_copyable_code_and_separate_json_data(self):
        for language, code in (("python", "from math import prod\nresult = prod([2, 3])"),
                               ("java", "import java.util.List;\nvar values = List.of(2, 3);")):
            with self.subTest(language=language):
                data = {"values": [2, 3]}
                self.write_metadata(library_starter={"language": language, "code": code, "data": data})
                self.run_tool()
                document = (self.student / "docs/operators/library-scale.md").read_text()
                self.assertIn(f"```{language}\n{code}\n```", document)
                self.assertIn("```json\n" + json.dumps(data, indent=2) + "\n```", document)
                self.assertNotIn('"code":', document)
                self.run_tool(check=True)

    def test_library_starter_rejects_unsupported_language_or_missing_code(self):
        for starter in ({"language": "shell", "code": "echo example", "data": {}},
                        {"language": "python", "code": "", "data": {}},
                        {"language": "java", "data": {}}):
            with self.subTest(starter=starter):
                self.write_metadata(library_starter=starter)
                self.assertIn("invalid library lesson starter", self.run_tool(succeeds=False).stderr)

    def test_library_code_cannot_close_its_markdown_fence(self):
        code = 'from math import prod\nlabel = "```"\nresult = prod([2, 3])'
        self.write_metadata(library_starter={"language": "python", "code": code, "data": {}})
        self.run_tool()
        document = (self.student / "docs/operators/library-scale.md").read_text()
        self.assertIn(f"````python\n{code}\n````", document)

    def test_wraps_long_prose_bullets_without_breaking_inline_tokens_or_fenced_data(self):
        prose = ("Compare the finite samples and original coordinates before interpreting the result. "
                 "Keep `sample count and order` intact and read [the required contract](manual.md#contract) "
                 "before selecting a numerical tolerance for each expected value.")
        self.lesson["objectives"] = [prose]
        self.lesson["tasks"] = ["Read the contract."] * 9 + [prose]
        self.lesson["parameterKeys"] = [f"parameter{index}" for index in range(12)]
        code = 'label = "' + "value " * 25 + '"'
        data = {"label": "value " * 25}
        self.write_metadata(library_starter={"language": "python", "code": code, "data": data})
        self.run_tool()
        document = (self.student / "docs/operators/ui-scale.md").read_text()
        for heading, end, prefix in (("## Set learning objectives", "## Prepare", "- "),
                                     ("## Complete the tasks", "## Use the starter workload", "10. ")):
            with self.subTest(heading=heading):
                section = document.split(heading + "\n\n", 1)[1].split(end, 1)[0].strip()
                lines = section.splitlines()
                start = next(index for index, line in enumerate(lines) if line.startswith(prefix))
                wrapped = lines[start:]
                self.assertGreater(len(wrapped), 1)
                self.assertTrue(all(len(line) <= 120 for line in wrapped), wrapped)
                self.assertTrue(all(line.startswith(" " * len(prefix)) for line in wrapped[1:]), wrapped)
                self.assertEqual(" ".join(line[len(prefix):] for line in wrapped), prose)
                self.assertIn("`sample count and order`", "\n".join(wrapped))
                self.assertIn("[the required contract](manual.md#contract)", "\n".join(wrapped))
        parameters = document.split("Parameter keys: ", 1)[1].split("\n\n", 1)[0]
        self.assertGreater(len(parameters.splitlines()), 1)
        self.assertTrue(all(len(line) <= 120 for line in ("Parameter keys: " + parameters).splitlines()))
        self.assertEqual(" ".join(parameters.splitlines()),
                         ", ".join(f"`{key}`" for key in self.lesson["parameterKeys"]) + ".")
        library = (self.student / "docs/operators/library-scale.md").read_text()
        self.assertIn(f"```python\n{code}\n```", library)
        self.assertIn("```json\n" + json.dumps(data, indent=2) + "\n```", library)

    def test_rejects_missing_reference(self):
        (self.source / "source.py").unlink()
        self.assertIn("reference does not exist", self.run_tool(succeeds=False).stderr)

    def test_rejects_unknown_generated_lesson(self):
        self.run_tool()
        (self.student / "docs/operators/unknown.md").write_text("Unknown lesson.\n")
        self.assertIn("unknown generated lesson", self.run_tool(check=True, succeeds=False).stderr)

    def test_rejects_missing_generated_lesson(self):
        self.run_tool()
        (self.student / "docs/operators/ui-scale.md").unlink()
        self.assertIn("generated content differs", self.run_tool(check=True, succeeds=False).stderr)

    def test_regeneration_removes_only_previously_generated_retired_lessons(self):
        second = copy.deepcopy(self.lesson)
        second.update(id="ui.other", operator="other")
        self.write_metadata([self.lesson, second])
        self.run_tool()
        self.write_metadata()
        self.assertIn("unknown generated lesson", self.run_tool(check=True, succeeds=False).stderr)
        self.run_tool()
        self.assertFalse((self.student / "docs/operators/ui.other.md").exists())
        self.run_tool(check=True)

    def test_regeneration_preserves_unknown_material_and_fails(self):
        self.run_tool()
        path = self.student / "docs/operators/unknown.md"
        path.write_text("Unrecognized material.\n")
        self.assertIn("unknown generated lesson", self.run_tool(succeeds=False).stderr)
        self.assertEqual(path.read_text(), "Unrecognized material.\n")

    def test_rejects_stale_source_manual_spec_tests_and_vectors(self):
        for name in ("source.py", "manual.md", "spec.md", "test.py", "vectors.json"):
            with self.subTest(reference=name):
                self.run_tool()
                path = self.source / name
                previous = path.read_bytes()
                path.write_bytes(previous + b"changed\n")
                self.assertIn("generated content differs", self.run_tool(check=True, succeeds=False).stderr)
                path.write_bytes(previous)

    def test_rejects_tampered_generated_document(self):
        self.run_tool()
        (self.student / "docs/operators/ui-scale.md").write_text("Edited expected answer.\n")
        self.assertIn("generated content differs", self.run_tool(check=True, succeeds=False).stderr)

    def test_rejects_missing_or_duplicate_lesson(self):
        for lessons in ([dict(self.lesson, tasks=[])], [self.lesson, self.lesson]):
            with self.subTest(lessons=lessons):
                self.write_metadata(lessons)
                self.run_tool(succeeds=False)

    def test_rejects_reference_traversal(self):
        self.lesson["references"]["source"] = ["../outside.py"]
        self.write_metadata()
        self.assertIn("invalid reference", self.run_tool(succeeds=False).stderr)

    def test_rejects_reference_symlink_escape(self):
        outside = self.root / "outside.py"
        outside.write_text("operator_symbol\n")
        (self.source / "source.py").unlink()
        (self.source / "source.py").symlink_to(outside)
        self.assertIn("reference escapes", self.run_tool(succeeds=False).stderr)

    def test_rejects_missing_anchor_symbol_and_line(self):
        for value in ("manual.md#absent", "source.py#absent", "source.py#L99"):
            with self.subTest(reference=value):
                self.lesson["references"]["source"] = [value]
                self.write_metadata()
                self.assertIn("reference fragment does not exist", self.run_tool(succeeds=False).stderr)

    def test_rejects_stale_source_commit(self):
        self.run_tool()
        (self.source / "unrelated.txt").write_text("New commit.\n")
        self.commit()
        self.assertIn("generated content differs", self.run_tool(check=True, succeeds=False).stderr)

    def test_records_dirty_source_without_claiming_clean_commit(self):
        (self.source / "source.py").write_text("operator_symbol\nchanged\n")
        self.run_tool()
        lock = json.loads((self.student / "alignment.json").read_text())
        self.assertTrue(lock["source"]["dirty"])
        self.run_tool(check=True)

    def test_rejects_duplicate_metadata_json_keys(self):
        path = self.source / "docs/learning/ui-operators.v1.json"
        path.write_text('{"schema":"opentsx.learning.v1","schema":"opentsx.learning.v1","lessons":[]}')
        self.assertIn("duplicate JSON key", self.run_tool(succeeds=False).stderr)

    def test_rejects_output_symlink_escape(self):
        outside = self.root / "outside"
        outside.mkdir()
        (self.student / "docs").symlink_to(outside, target_is_directory=True)
        self.assertIn("output escapes", self.run_tool(succeeds=False).stderr)


if __name__ == "__main__":
    unittest.main()
