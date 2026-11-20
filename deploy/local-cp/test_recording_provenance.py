from contextlib import redirect_stderr, redirect_stdout
import importlib.util
from io import StringIO
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


class RecordingProvenanceTests(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).with_name("recording_provenance.py")
        spec = importlib.util.spec_from_file_location("recording_provenance", path)
        self.api = importlib.util.module_from_spec(spec)
        with patch.object(sys, "path", [str(path.parent), *sys.path]):
            spec.loader.exec_module(self.api)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "checkout"
        self.local = self.root / "deploy/local-cp"
        self.local.mkdir(parents=True)
        self.package = self.root / "python-package/opentsx"
        self.package.mkdir(parents=True)
        self.version = self.package / "__init__.py"
        self.version.write_text(
            '__version__ = "1.2.3"\nraise RuntimeError("must not import")\n'
        )
        self.settings = {"python_image": "python-fixture", "node_image": "node-fixture"}
        (self.local / "settings.json").write_text(json.dumps(self.settings))
        self.revision = "a" * 40
        self.source = "b" * 64
        self.epoch = "1790000000"
        self.keys = {"deps": "c" * 64, "wheel": "d" * 64, "frontend": "e" * 64}
        self.commands = []
        self.status = ""
        self.ignored = True
        self.git_failure = None
        self.addCleanup(patch.stopall)
        patch.object(self.api.workbench, "ROOT", self.root).start()
        patch.object(self.api.workbench, "LOCAL", self.local).start()
        self.git = patch.object(
            self.api.workbench, "output", side_effect=self.output
        ).start()
        self.digest = patch.object(
            self.api.workbench, "source_digest", return_value=self.source
        ).start()
        self.build = patch.object(
            self.api.workbench,
            "build_key",
            side_effect=lambda kind, settings, epoch: self.keys[kind],
        ).start()

    def output(self, argv):
        self.commands.append(argv)
        if self.git_failure is not None:
            raise self.git_failure
        if argv[1] == "status":
            return self.status
        if argv[1] == "check-ignore":
            if not self.ignored:
                raise subprocess.CalledProcessError(1, argv)
            return argv[-1]
        if argv[1] == "rev-parse":
            return self.revision
        if argv[1] == "show":
            return self.epoch
        raise AssertionError(argv)

    def test_clean_capture_reuses_all_existing_provenance_and_build_inputs(self):
        destination = self.root / "target/capture"
        result = self.api.provenance(destination)
        self.assertEqual(
            {
                "revision": self.revision,
                "source_sha256": self.source,
                "source_date_epoch": self.epoch,
                "build_inputs": self.keys,
                "images": {"python": "python-fixture", "node": "node-fixture"},
                "dirty": False,
                "version": "1.2.3",
            },
            result,
        )
        self.assertIn(
            ["git", "check-ignore", "--", "target/capture/recording.json"],
            self.commands,
        )
        self.assertIn(
            ["git", "status", "--porcelain", "--untracked-files=all"], self.commands
        )
        self.assertEqual(
            [(kind, self.settings, self.epoch) for kind in self.keys],
            [call.args for call in self.build.call_args_list],
        )
        self.assertFalse(destination.exists())

    def test_output_outside_checkout_needs_no_ignore_rule(self):
        self.ignored = False
        destination = self.root.parent / "capture"
        self.api.provenance(destination)
        self.assertFalse(any(argv[1] == "check-ignore" for argv in self.commands))
        self.assertFalse(destination.exists())

    def test_tracked_changes_and_untracked_sources_reject_before_fingerprinting(self):
        for status in (" M file.py", "?? added.py", "A  staged.py"):
            self.status = status
            with self.subTest(status=status), self.assertRaisesRegex(
                ValueError, "clean Git"
            ):
                self.api.provenance(self.root / "target/capture")
        self.digest.assert_not_called()
        self.build.assert_not_called()

    def test_nonignored_output_inside_checkout_rejects_before_fingerprinting(self):
        self.ignored = False
        with self.assertRaisesRegex(ValueError, "ignored by Git"):
            self.api.provenance(self.root / "capture")
        self.digest.assert_not_called()
        self.assertFalse((self.root / "capture").exists())

    def test_empty_ignore_confirmation_is_rejected(self):
        def output(argv):
            return "" if argv[1] == "check-ignore" else self.output(argv)

        self.git.side_effect = output
        with self.assertRaisesRegex(ValueError, "did not confirm"):
            self.api.provenance(self.root / "target/capture")

    def test_git_failures_are_propagated_instead_of_replaced(self):
        self.git_failure = subprocess.CalledProcessError(128, ["git", "status"])
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            self.api.provenance(self.root / "target/capture")
        self.assertIs(self.git_failure, raised.exception)
        self.git_failure = None

        def output(argv):
            if argv[1] == "check-ignore":
                raise subprocess.CalledProcessError(128, argv)
            return self.output(argv)

        self.git.side_effect = output
        with self.assertRaises(subprocess.CalledProcessError):
            self.api.provenance(self.root / "target/capture")

    def test_build_or_source_digest_failure_is_propagated(self):
        failure = RuntimeError("build input failed")
        self.build.side_effect = failure
        with self.assertRaises(RuntimeError) as raised:
            self.api.provenance(self.root / "target/capture")
        self.assertIs(failure, raised.exception)
        self.build.side_effect = lambda kind, settings, epoch: self.keys[kind]
        self.digest.side_effect = OSError("source read failed")
        with self.assertRaisesRegex(OSError, "source read failed"):
            self.api.provenance(self.root / "target/capture")

    def test_version_is_literal_and_never_imports_the_package(self):
        result = self.api.provenance(self.root / "target/capture")
        self.assertEqual("1.2.3", result["version"])
        for source in (
            "__version__ = get_version()\n",
            '__version__ = "dev"\n',
            "__version__ = 123\n",
            'value = "1.2.3"\n',
            '__version__ = "1.2.3"\n__version__ = "1.2.4"\n',
        ):
            self.version.write_text(source)
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.api.provenance(self.root / "target/capture")

    def test_invalid_git_or_build_metadata_is_rejected(self):
        self.revision = "short"
        with self.assertRaises(ValueError):
            self.api.provenance(self.root / "target/capture")
        self.revision = "a" * 40
        self.epoch = "not-an-epoch"
        with self.assertRaises(ValueError):
            self.api.provenance(self.root / "target/capture")
        self.epoch = "1790000000"
        self.digest.return_value = "bad-digest"
        with self.assertRaises(ValueError):
            self.api.provenance(self.root / "target/capture")
        self.digest.return_value = self.source
        self.keys["frontend"] = "bad-key"
        with self.assertRaises(ValueError):
            self.api.provenance(self.root / "target/capture")

    def test_cli_prints_only_json_or_a_clear_failure(self):
        stdout, stderr = StringIO(), StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            self.api.main([str(self.root / "target/capture")])
        self.assertEqual(self.revision, json.loads(stdout.getvalue())["revision"])
        self.assertEqual("", stderr.getvalue())
        stdout, stderr = StringIO(), StringIO()
        self.status = "?? added.py"
        with redirect_stdout(stdout), redirect_stderr(stderr), self.assertRaises(
            SystemExit
        ) as raised:
            self.api.main([str(self.root / "target/capture")])
        self.assertEqual(1, raised.exception.code)
        self.assertEqual("", stdout.getvalue())
        self.assertIn("recording provenance failed", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
