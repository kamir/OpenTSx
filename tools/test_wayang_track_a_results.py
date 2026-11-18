import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


class TrackAResultsTest(unittest.TestCase):
    def test_resource_csv_bytes_and_whitespace_checks_preserve_crlf(self):
        root = Path(__file__).resolve().parents[1]
        relative = Path("docs/datamodel-2026/measurements/wayang-track-a-evaluation-2026-11-18/federation/k1-r1/resources.csv")
        for mode in ("false", "true"):
            with self.subTest(autocrlf=mode), tempfile.TemporaryDirectory() as temporary:
                repository = Path(temporary)
                attributes = root / ".gitattributes"
                if attributes.is_file():
                    (repository / ".gitattributes").write_bytes(attributes.read_bytes())
                git = ["git", "-c", "core.autocrlf=" + mode, "-c", "core.attributesFile=/dev/null",
                       "-c", "core.whitespace=blank-at-eol,blank-at-eof,space-before-tab"]
                subprocess.run([*git, "init", "--quiet"], cwd=repository, check=True, capture_output=True)
                path = repository / relative
                path.parent.mkdir(parents=True)
                original = b"elapsed_ms,pid\r\n0,1\r\n"
                path.write_bytes(original)
                subprocess.run([*git, "add", relative.as_posix()], cwd=repository, check=True, capture_output=True)
                check = subprocess.run([*git, "diff", "--cached", "--check"], cwd=repository, capture_output=True)
                self.assertEqual(0, check.returncode, check.stdout[:500])
                staged = subprocess.run([*git, "show", ":" + relative.as_posix()], cwd=repository, check=True, capture_output=True)
                self.assertEqual(original, staged.stdout)
                path.unlink()
                subprocess.run([*git, "checkout", "--", relative.as_posix()], cwd=repository, check=True, capture_output=True)
                self.assertEqual(original, path.read_bytes())
                path.write_bytes(b"elapsed_ms,pid\r\n0,1 \r\n")
                subprocess.run([*git, "add", relative.as_posix()], cwd=repository, check=True, capture_output=True)
                check = subprocess.run([*git, "diff", "--cached", "--check"], cwd=repository, capture_output=True)
                self.assertNotEqual(0, check.returncode)
                self.assertIn(b"trailing whitespace", check.stdout)

    def test_committed_results_are_complete_public_and_reproducible(self):
        root = Path(__file__).resolve().parents[1]
        results = root / "docs/datamodel-2026/measurements/wayang-track-a-evaluation-2026-11-18"
        metadata = json.loads((results / "metadata.json").read_text(encoding="utf-8"))
        self.assertEqual("opentsx.track-a-evaluation.v1", metadata["schema"])
        self.assertEqual("complete", metadata["status"])
        self.assertEqual("A5 bounded baseline", metadata["profile"])
        self.assertNotIn("--pilot", metadata["invocation"]["argv"])
        self.assertIsNotNone(re.fullmatch("[0-9a-f]{40}", metadata["openTsxCommit"]))
        self.assertEqual(metadata["openTsxCommit"], metadata["sourceSnapshot"]["commit"])

        spec = importlib.util.spec_from_file_location("track_a_evaluation", root / "tools/wayang_track_a_evaluation.py")
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)
        matrix = tool.cases(3)
        self.assertEqual(63, len(matrix))
        self.assertEqual(json.dumps(matrix, separators=(",", ":")),
                         json.dumps(metadata["matrix"], separators=(",", ":")))
        self.assertEqual([row[0] for row in matrix], metadata["completed"])

        public = set()
        for name, kind, _, _ in matrix:
            filenames = ("bytes.csv", "windows.csv", "run.json", "resources.csv") if kind == "federation" else (
                "case.json", "measurement.csv", "records.csv", "produce-attempts.csv", "resources.csv")
            public.update(f"{name}/{filename}" for filename in filenames)
        self.assertEqual(306, len(public))
        self.assertIsInstance(metadata["artifactsSha256"], dict)
        self.assertEqual(public, set(metadata["artifactsSha256"]))
        paths = list(results.rglob("*"))
        for path in paths:
            self.assertFalse(path.is_symlink(), str(path))
        expected = public | {"metadata.json", "REPORT.md"}
        if (results / "README.md").is_file():
            expected.add("README.md")
        self.assertEqual(expected, {path.relative_to(results).as_posix() for path in paths if path.is_file()})
        for name, digest in metadata["artifactsSha256"].items():
            self.assertIsNotNone(re.fullmatch("[0-9a-f]{64}", digest), name)
            self.assertEqual(digest, hashlib.sha256((results / name).read_bytes()).hexdigest(), name)

        scenario = "opentsx-windpark/scenarios/ostsee-demo.yaml"
        scenario_hash = hashlib.sha256((root / scenario).read_bytes()).hexdigest()
        self.assertEqual(scenario_hash, metadata["sourceSnapshot"]["files"][scenario])
        for name, kind, _, _ in matrix:
            if kind == "federation":
                run = json.loads((results / name / "run.json").read_text(encoding="utf-8"))
                self.assertEqual(scenario_hash, run["scenarioSha256"], name)

        with tempfile.TemporaryDirectory() as temporary:
            generated = Path(temporary)
            for name in ("federation", "streaming"):
                (generated / name).symlink_to(results / name, target_is_directory=True)
            tool.report(generated, metadata)
            self.assertEqual((results / "REPORT.md").read_bytes(), (generated / "REPORT.md").read_bytes())
