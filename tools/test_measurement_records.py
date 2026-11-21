from pathlib import Path
import hashlib
import importlib.util
import json
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
MEASUREMENTS = ROOT / "docs/datamodel-2026/measurements"


def check_links():
    spec = importlib.util.spec_from_file_location("check_links", ROOT / "tools/check_links.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MeasurementRecordsTest(unittest.TestCase):
    def test_committed_files_match_the_digests_their_manifest_pins(self):
        checked = 0
        for manifest in sorted(MEASUREMENTS.glob("*/manifest.json")):
            for name, digest in json.loads(manifest.read_text(encoding="utf-8"))["files"].items():
                path = manifest.parent / name
                if path.is_file():
                    self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest(),
                                     f"{path.relative_to(ROOT)} differs from the digest its manifest pins")
                    checked += 1
        self.assertGreater(checked, 0)

    def test_link_check_leaves_out_reports_a_manifest_pins(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run = root / "docs/datamodel-2026/measurements/run"
            run.mkdir(parents=True)
            report = run / "REPORT.md"
            report.write_text("Read [samples](samples.csv).\n", encoding="utf-8")
            self.assertEqual(1, check_links().main(root))
            digest = hashlib.sha256(report.read_bytes()).hexdigest()
            (run / "manifest.json").write_text(json.dumps({"files": {"REPORT.md": digest}}), encoding="utf-8")
            self.assertEqual(0, check_links().main(root))


if __name__ == "__main__":
    unittest.main()
