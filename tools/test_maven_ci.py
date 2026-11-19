from pathlib import Path
import re
import unittest

from test_ci_workflow_selection import job_blocks, step_with_command


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
MAVEN_JOBS = {
    "rust-b1-acceptance", "rust-runner-compose", "wayang-rust-compose",
    "java", "kafscale-conformance", "cross-language",
}
CHECKSUM = "7d171def9b85846bf757a2cec94b7529371068a0670df14682447224e57983528e97a6d1b850327e4ca02b139abaab7fcb93c4315119e6f0ffb3f0cbc0d0b9a2"
INSTALL = "\n".join([
    "      - name: Install Maven 3.9.8",
    "        run: |",
    "          curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 \\",
    '            -o "$RUNNER_TEMP/apache-maven-3.9.8-bin.tar.gz" \\',
    "            https://archive.apache.org/dist/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.tar.gz",
    "          printf '%s  %s\\n' '" + CHECKSUM + "' \"$RUNNER_TEMP/apache-maven-3.9.8-bin.tar.gz\" | sha512sum --check",
    '          tar xzf "$RUNNER_TEMP/apache-maven-3.9.8-bin.tar.gz" -C "$RUNNER_TEMP"',
    '          echo "$RUNNER_TEMP/apache-maven-3.9.8/bin" >> "$GITHUB_PATH"',
    "",
])
VERIFY = ("      - name: Verify Maven download retries\n"
          "        run: python3 tools/check_maven_downloads.py\n")
HELPERS = r"python3 tools/(?:verify_rust_runner_compose|verify_wayang_rust_compose|aggregate_transport_vectors)\.py"


def steps(block):
    return [step for step in re.split(r"(?m)(?=^      - )", block)
            if re.match(r"^      - ", step)]


class MavenCiTest(unittest.TestCase):
    def assert_jobs(self, workflow):
        jobs = job_blocks(workflow)
        actual = {job for job, block in jobs.items() if re.search(r"\bmvn\b|" + HELPERS, block)}
        self.assertEqual(MAVEN_JOBS, actual)
        return jobs

    def assert_pinned_and_verified(self, job, block):
        commands = steps(block)
        installers = [step for step in commands if re.match(r"^      - name: Install Maven 3\.9\.8\s*\n", step)]
        self.assertEqual(1, len(installers), job)
        install = installers[0]
        self.assertNotRegex(install, r"(?m)^        (?:if|continue-on-error):")
        for line in INSTALL.splitlines()[2:]:
            self.assertIn(line, install, job)
        verification = step_with_command(block, "python3 tools/check_maven_downloads.py")
        self.assertRegex(verification, r"^      - name: Verify Maven download retries\s*\n")
        self.assertNotRegex(verification, r"(?m)^        (?:if|continue-on-error):")
        index = commands.index(verification)
        self.assertEqual(commands.index(install) + 1, index, job)
        workloads = [position for position, step in enumerate(commands)
                     if re.search(r"\bmvn\b[^\n]*(?:\bverify\b|\binstall\b|\btest\b|dependency:build-classpath)|" + HELPERS, step)]
        self.assertTrue(workloads, job)
        self.assertTrue(all(index < position for position in workloads), job)

    def assert_log(self, jobs, job, name):
        artifacts = [step for step in steps(jobs[job])
                     if re.search(r"^      - uses: actions/upload-artifact@v7\s*$", step, re.MULTILINE)
                     and re.search(r"^        if: always\(\)\s*$", step, re.MULTILINE)]
        paths = ["target/" + job + "/" + name]
        if job == "rust-runner-compose":
            paths.append("target/rust-runner-compose/")
        self.assertTrue(any(re.search(r"^\s+" + re.escape(path) + r"\s*$", artifact, re.MULTILINE)
                            for artifact in artifacts for path in paths), job + ": " + name)

    def assert_contract(self, workflow):
        jobs = self.assert_jobs(workflow)
        for job in sorted(MAVEN_JOBS):
            self.assert_pinned_and_verified(job, jobs[job])
        for job in ("rust-runner-compose", "wayang-rust-compose"):
            for name in ("java-build.log", "java-classpath.log"):
                self.assert_log(jobs, job, name)

    def test_every_maven_job_uses_the_verified_distribution_before_builds(self):
        jobs = self.assert_jobs(WORKFLOW.read_text(encoding="utf-8"))
        for job in sorted(MAVEN_JOBS):
            with self.subTest(job=job):
                self.assert_pinned_and_verified(job, jobs[job])

    def test_compose_maven_logs_are_retained_after_failed_builds(self):
        jobs = job_blocks(WORKFLOW.read_text(encoding="utf-8"))
        for job in ("rust-runner-compose", "wayang-rust-compose"):
            for name in ("java-build.log", "java-classpath.log"):
                with self.subTest(job=job, log=name):
                    self.assert_log(jobs, job, name)

    def test_contract_rejects_unpinned_unverified_and_missing_failure_evidence(self):
        workflow = self.valid_workflow()
        self.assert_contract(workflow)
        mutations = {
            "missing pin": workflow.replace(INSTALL, "", 1),
            "wrong version": workflow.replace("3.9.8", "3.9.9", 1),
            "wrong checksum": workflow.replace(CHECKSUM, "0" * len(CHECKSUM), 1),
            "missing verification": workflow.replace(VERIFY, "", 1),
            "skipped verification": workflow.replace(VERIFY, VERIFY + "        if: false\n", 1),
            "suppressed verification": workflow.replace(VERIFY, VERIFY + "        continue-on-error: true\n", 1),
            "build before verification": workflow.replace(VERIFY, "      - run: mvn -B -ntp verify\n" + VERIFY, 1),
            "missing build log": workflow.replace("            target/wayang-rust-compose/java-build.log\n", ""),
            "missing classpath log": workflow.replace("            target/wayang-rust-compose/java-classpath.log\n", ""),
            "conditional artifacts": workflow.replace("        if: always()\n", "        if: success()\n"),
            "uncovered Maven job": workflow + "  extra:\n    steps:\n      - run: mvn verify\n",
        }
        for name, mutation in mutations.items():
            with self.subTest(mutation=name):
                self.assertNotEqual(workflow, mutation)
                with self.assertRaises((AssertionError, KeyError)):
                    self.assert_contract(mutation)

    def valid_workflow(self):
        blocks = ["jobs:\n"]
        for job in sorted(MAVEN_JOBS):
            command = "mvn -B -ntp verify"
            if job == "rust-runner-compose":
                command = "python3 tools/verify_rust_runner_compose.py"
            if job == "wayang-rust-compose":
                command = "python3 tools/verify_wayang_rust_compose.py"
            blocks.append(f"  {job}:\n    steps:\n" + INSTALL + VERIFY + "      - run: " + command + "\n")
            if job in {"rust-runner-compose", "wayang-rust-compose"}:
                blocks.append("      - uses: actions/upload-artifact@v7\n        if: always()\n        with:\n          path: |\n")
                for name in ("java-build.log", "java-classpath.log"):
                    blocks.append("            target/" + job + "/" + name + "\n")
        return "".join(blocks)


if __name__ == "__main__":
    unittest.main()
