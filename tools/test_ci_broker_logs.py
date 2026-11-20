from pathlib import Path
import re
import tempfile
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
BROKER_GLOB = "opentsx-wayang/target/federation-test-logs/**/brokers/*.log"
PRIMARY_LOG = "${{ runner.temp }}/kafscale-broker.log"
CAPABILITY_PATHS = {
    "opentsx-kafscale-conformance/target/kafscale-capabilities.properties",
    "opentsx-kafscale-conformance/target/surefire-reports/",
    "opentsx-kafka-v2/target/surefire-reports/",
    "opentsx-legacy-bridge/target/surefire-reports/",
    "opentsx-windpark/target/surefire-reports/",
    "opentsx-stats/target/surefire-reports/",
    "opentsx-retrieval/target/surefire-reports/",
    "opentsx-flink-core/target/pfd-acceptance/",
    "opentsx-wayang/target/surefire-reports/",
    BROKER_GLOB,
    "opentsx-wayang/target/rust-b3-daemon/result.json",
    "opentsx-wayang/target/rust-b3-offline/result.json",
    "opentsx-wayang/target/rust-b3-offline/protocol.json",
    "opentsx-wayang/target/rust-b3-offline/placement.json",
    "opentsx-wayang/target/rust-b3-offline/qualification.json",
    "target/rust-b3-evidence/runner.log",
    "target/rust-b3-evidence/contracts.log",
    "target/rust-b3-evidence/ssh/report.json",
    "target/rust-b3-evidence/ssh/metadata.json",
    "target/rust-b3-evidence/ssh/sshd.log",
}


class CiBrokerLogsTest(unittest.TestCase):
    def artifact_paths(self, workflow, name):
        job = re.search(r"^  kafscale-conformance:\s*\n(.*?)(?=^  \S|\Z)", workflow, re.M | re.S)
        self.assertIsNotNone(job, "KafScale conformance job is missing")
        matches = []
        for step in re.split(r"(?m)(?=^      - )", job.group(1)):
            if not re.search(r"^(?:      - |        )uses: actions/upload-artifact@\S+\s*$", step, re.M):
                continue
            artifact = re.search(r"^          name: (.+)$", step, re.M)
            if artifact and artifact.group(1).strip().strip("\"'") == name:
                matches.append(step)
        self.assertEqual(1, len(matches), f"Expected one {name} upload in KafScale conformance")
        step = matches[0]
        condition = re.search(r"^        if: (.+)$", step, re.M)
        self.assertIsNotNone(condition, f"{name} must upload after earlier step failures")
        self.assertEqual("always()", condition.group(1).strip(), name)
        path = re.search(r"^          path: (.+)$", step, re.M)
        self.assertIsNotNone(path, f"{name} paths are missing")
        if path.group(1).strip() != "|":
            return [path.group(1).strip().strip("\"'")]
        paths = []
        for line in step[path.end():].splitlines():
            if not line.strip():
                continue
            if not line.startswith("            "):
                break
            paths.append(line.strip())
        return paths

    def assert_contract(self, workflow):
        paths = self.artifact_paths(workflow, "kafscale-capabilities")
        self.assertEqual(len(CAPABILITY_PATHS), len(paths))
        self.assertEqual(CAPABILITY_PATHS, set(paths))
        self.assertEqual([PRIMARY_LOG], self.artifact_paths(workflow, "kafscale-broker-log"))

    def test_capabilities_keep_reports_and_only_disposable_broker_logs_after_failure(self):
        paths = self.artifact_paths(WORKFLOW.read_text(encoding="utf-8"), "kafscale-capabilities")
        self.assertEqual(len(CAPABILITY_PATHS), len(paths))
        self.assertEqual(CAPABILITY_PATHS, set(paths))

    def test_primary_broker_log_upload_is_separate_and_runs_after_failure(self):
        paths = self.artifact_paths(WORKFLOW.read_text(encoding="utf-8"), "kafscale-broker-log")
        self.assertEqual([PRIMARY_LOG], paths)

    def test_fixture_glob_keeps_direct_and_nested_logs_without_private_state(self):
        paths = self.artifact_paths(WORKFLOW.read_text(encoding="utf-8"), "kafscale-capabilities")
        self.assertIn(BROKER_GLOB, paths)
        base = "opentsx-wayang/target/federation-test-logs/"
        logs = {base + name for name in ["brokers/s1.log", "direct/brokers/signal.log",
                "nested/fingerprint/brokers/s2.log", "nested/overlap/brokers/s3.log"]}
        excluded = {base + name for name in ["direct/private.key", "direct/tls/client.p12",
                    "direct/tls/handshake.log", "direct/checkpoint.json", "direct/session/state.json",
                    "direct/brokers/private.key", "direct/s1.log", "direct/brokers/nested/s1.log"]}
        excluded.add("opentsx-wayang/target/federation-private/brokers/s1.log")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in logs | excluded:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture\n", encoding="utf-8")
            actual = {path.relative_to(root).as_posix() for path in root.glob(BROKER_GLOB)}
            self.assertEqual(logs, actual)

    def test_contract_rejects_wrong_job_conditional_uploads_and_broad_paths(self):
        workflow = "\n".join([
            "jobs:", "  kafscale-conformance:", "    steps:", "      - run: exit 1",
            "      - uses: actions/upload-artifact@v7", "        if: always()", "        with:",
            "          name: kafscale-capabilities", "          path: |",
            *("            " + path for path in sorted(CAPABILITY_PATHS)),
            "      - uses: actions/upload-artifact@v7", "        if: always()", "        with:",
            "          name: kafscale-broker-log", "          path: " + PRIMARY_LOG,
            "  another-job:", "    steps:", "      - run: true", "",
        ])
        self.assert_contract(workflow)
        mutations = {
            "wrong job": workflow.replace("  kafscale-conformance:", "  unrelated:"),
            "success only": workflow.replace("if: always()", "if: success()", 1),
            "failure only": workflow.replace("if: always()", "if: failure()", 1),
            "missing primary guard": workflow.replace("        if: always()\n        with:\n"
                                                     "          name: kafscale-broker-log", "        with:\n"
                                                     "          name: kafscale-broker-log"),
            "private fixture state": workflow.replace(BROKER_GLOB, "opentsx-wayang/target/federation-test-logs/"),
            "private SSH state": workflow.replace("target/rust-b3-evidence/ssh/report.json", "target/rust-b3-evidence/ssh/"),
            "private TLS files": workflow.replace("opentsx-wayang/target/rust-b3-offline/qualification.json", "opentsx-wayang/target/rust-b3-offline/*.pem"),
            "missing Wayang reports": workflow.replace("            opentsx-wayang/target/surefire-reports/\n", ""),
            "primary directory": workflow.replace(PRIMARY_LOG, "${{ runner.temp }}/"),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.assert_contract(mutation)


if __name__ == "__main__":
    unittest.main()
