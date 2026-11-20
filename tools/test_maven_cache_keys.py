import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from ci_job_selection import MAVEN_ROOTS
import test_ci_workflow_selection as selection
from test_ci_workflow_selection import job_blocks, section
from test_maven_ci import MAVEN_JOBS, steps


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PATTERNS = ("**/pom.xml", "**/.mvn/wrapper/maven-wrapper.properties", "**/.mvn/extensions.xml")


class MavenCacheKeysTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        for name, content in {
            "pom.xml": "<project><modelVersion>4.0.0</modelVersion><artifactId>root</artifactId></project>",
            "module/pom.xml": "<project><modelVersion>4.0.0</modelVersion><artifactId>module</artifactId></project>",
            ".mvn/extensions.xml": "<extensions/>",
            ".mvn/maven.config": (ROOT / ".mvn/maven.config").read_text(),
            "tools/check_maven_downloads.py": (ROOT / "tools/check_maven_downloads.py").read_text(),
        }.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)

    def key(self, job, environment=None):
        block = job_blocks(self.workflow)[job]
        setup = None
        for step in steps(block):
            if "uses: actions/setup-java@v6" in step:
                setup = step
                break
            if "name: Scope Maven cache to its workload" in step:
                self.assertNotRegex(step, r"(?m)^        (?:if|continue-on-error):")
                script = section(step, "run", 8)
                subprocess.run(["bash", "-e", "-c", script], cwd=self.root,
                               env={"PATH": os.defpath, "GITHUB_JOB": job} if environment is None else environment,
                               check=True, capture_output=True)
        self.assertIsNotNone(setup)
        patterns = (section(setup, "cache-dependency-path", 10).splitlines()
                    if "cache-dependency-path:" in setup else DEFAULT_PATTERNS)
        paths = {path for pattern in patterns for path in self.root.glob(pattern.strip()) if path.is_file()}
        self.assertTrue(paths)
        digest = hashlib.sha256()
        for path in sorted(paths):
            digest.update(hashlib.sha256(path.read_bytes()).digest())
        return digest.hexdigest()

    def test_small_first_writer_cannot_freeze_another_workloads_dependency_subset(self):
        small = frozenset({"iceberg", "retrieval"})
        full = small | {"wayang-core", "wayang-basic", "wayang-java"}
        cache = {self.key("cross-language"): small}
        key = self.key("wayang-rust-compose")
        if key not in cache:
            cache[key] = full
        self.assertTrue(full.issubset(cache[self.key("wayang-rust-compose")]),
                        "An exact cache hit keeps the first writer's incomplete dependency subset")
        self.assertEqual(small, cache[self.key("cross-language")])

    def test_all_maven_workloads_have_distinct_stable_cache_keys(self):
        keys = {job: self.key(job) for job in sorted(MAVEN_JOBS)}
        self.assertEqual(len(MAVEN_JOBS), len(set(keys.values())))
        self.assertEqual(keys, {job: self.key(job) for job in sorted(MAVEN_JOBS)})

    def test_runtime_configuration_extensions_and_module_poms_invalidate_the_key(self):
        for name in (".mvn/maven.config", "tools/check_maven_downloads.py", ".mvn/extensions.xml", "module/pom.xml"):
            path = self.root / name
            original = path.read_bytes()
            before = self.key("java")
            path.write_bytes(original + b"\n")
            with self.subTest(input=name):
                self.assertNotEqual(before, self.key("java"))
            path.write_bytes(original)

    def test_empty_workload_identity_fails_before_cache_restore(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.key("java", {"PATH": os.defpath, "GITHUB_JOB": ""})
        self.assertFalse((self.root / "target/maven-downloads/cache-context").exists())

    def test_workload_root_checks_accept_cache_paths_and_reject_unscoped_builds(self):
        workflow = ["jobs:\n"]
        for job in ("rust-b1-acceptance", "kafscale-conformance", "cross-language"):
            modules = ",".join(sorted(MAVEN_ROOTS[job]))
            workflow.append(f"  {job}:\n    steps:\n      - uses: actions/setup-java@v6\n"
                            "        with:\n          cache-dependency-path: |\n"
                            "            **/.mvn/wrapper/maven-wrapper.properties\n"
                            "            **/.mvn/extensions.xml\n"
                            f"      - run: mvn -B -pl {modules} -am test\n")
        fixture = self.root / "workflow.yml"
        fixture.write_text("".join(workflow))
        with patch.object(selection, "WORKFLOW", fixture):
            selection.CiWorkflowSelectionTest().test_declared_maven_roots_match_inline_workflow_commands()
            fixture.write_text("".join(workflow) + "      - run: mvn verify\n")
            with self.assertRaises(AssertionError):
                selection.CiWorkflowSelectionTest().test_declared_maven_roots_match_inline_workflow_commands()


if __name__ == "__main__":
    unittest.main()
