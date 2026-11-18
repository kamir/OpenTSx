import importlib.util
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("wayang_compose", Path(__file__).with_name("verify_wayang_rust_compose.py"))
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class WayangComposeGuardsTest(unittest.TestCase):
    def test_single_worker_outage_preserves_other_containers_and_appends_before_restart(self):
        events = []
        live = ["backend", "coordinator", "frontend", "kafscale-proxy", "postgres", "runner-1", "runner-3", "wayang"]
        before = "\n".join(f"{name} id-{name} running" for name in [*live, "runner-2"])
        during = "\n".join(f"{name} id-{name} running" for name in live) + "\nrunner-2 id-runner-2 exited"
        def execute(command, *args, **kwargs):
            events.append(command)
            if command[1:3] == ["ps", "--all"]:
                return before if len(events) == 1 else during
            return ""
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "DIRECTORY", Path(directory)), \
                patch.object(HELPER, "run", side_effect=execute):
            HELPER.interrupt_one_worker(["java", "fixture"], "127.0.0.1:39093", ["compose"], {}, 6)
            proof = json.loads((Path(directory) / "worker-outage.json").read_text())
            self.assertEqual(live, proof["liveServices"])
            self.assertEqual("worker-2", proof["worker"])
            self.assertEqual(6, proof["throughBlocks"])
        self.assertEqual(["compose", "stop", "runner-2"], events[1])
        self.assertEqual(["java", "fixture", "append", "127.0.0.1:39093"], events[2][:4])
        self.assertEqual("6", events[2][-1])
        self.assertFalse(any(command[1:2] == ["start"] for command in events))

    def test_single_worker_outage_rejects_another_restarted_or_stopped_service(self):
        names = ["backend", "coordinator", "frontend", "kafscale-proxy", "postgres", "runner-1", "runner-2", "runner-3", "wayang"]
        before = "\n".join(f"{name} id-{name} running" for name in names)
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "DIRECTORY", Path(directory)):
            for changed in ["runner-1 replaced running", "runner-1 id-runner-1 exited"]:
                during = before.replace("runner-1 id-runner-1 running", changed).replace(
                    "runner-2 id-runner-2 running", "runner-2 id-runner-2 exited")
                with self.subTest(changed=changed), patch.object(HELPER, "run", side_effect=[
                        before, "", "", during]):
                    with self.assertRaisesRegex(RuntimeError, "single-worker outage"):
                        HELPER.interrupt_one_worker(["java"], "bootstrap", ["compose"], {}, 6)
                    self.assertFalse((Path(directory) / "worker-outage.json").exists())

    def test_continuous_acceptance_requires_single_worker_outage_and_post_recovery_input(self):
        self.assertEqual([("initial", 1), ("retry", 1), ("new-data", 2), ("restart", 4),
                          ("after-restart", 5), ("worker-unavailable", 5), ("worker-restart", 6),
                          ("after-worker-restart", 7), ("stopped", 7)], HELPER.CONTINUOUS_PHASES)

    def test_continuous_evidence_requires_fixed_topics_clock_and_confirmed_progress(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "DIRECTORY", Path(directory)):
            root = Path(directory)
            graph = b"original continuous UI text\n"
            sources = [{"topic": f"tsx.test.input{n}.v2", "partitions": n} for n in range(1, 4)]
            (root / "fixture.json").write_text(json.dumps({"sources": sources}))
            (root / "submitted-dag.txt").write_bytes(graph)
            (root / "worker-outage.json").write_text(json.dumps({"worker": "worker-2", "throughBlocks": 6,
                "liveServices": ["backend", "coordinator", "frontend", "kafscale-proxy", "postgres", "runner-1", "runner-3", "wayang"]}))
            for phase, blocks in HELPER.CONTINUOUS_PHASES:
                value = {"id": "local-test", "specHash": hashlib.sha256(graph).hexdigest(),
                         "createdAtMicros": "1700000000000000", "profile": "continuous",
                         "planHash": hashlib.sha256(str(blocks).encode()).hexdigest(),
                         "state": "stopped" if phase == "stopped" else "idle", "stopRequested": phase == "stopped",
                         "currentCycle": str(blocks), "completedCycles": str(blocks),
                         "error": "worker could not be reached" if phase == "worker-unavailable" else None,
                         "inputRecords": str(6 * blocks), "outputRecords": str(6 * blocks), "assignments": [
                             {"node": f"worker-{n}", "inputTopic": source["topic"], "partitionCount": str(n),
                              "cumulativeInputRecords": str(n * blocks), "cumulativeOutputRecords": str(n * blocks),
                              "positions": [{"partition": str(p), "next": str(blocks)} for p in range(n)]}
                             for n, source in enumerate(sources, 1)]}
                (root / f"deployment-{phase}.json").write_text(json.dumps(value))
                (root / f"browser-{phase}.json").write_text(json.dumps({"phase": phase, "assignments": 3,
                    "profile": "continuous", "blocks": str(blocks), "submissions": int(phase in ("initial", "retry"))}))
                (root / f"verified-{phase}.json").write_text(json.dumps({"verifiedRecords": 6 * blocks,
                    "distinctEpisodes": 6 * blocks, "workerReceipts": 3, "throughBlocks": blocks,
                    "inputRecords": 6 * blocks}))
            self.assertEqual(42, HELPER.verify_continuous_views()["verifiedRecords"])
            path = root / "deployment-restart.json"
            original = json.loads(path.read_text())
            for key, changed in [("createdAtMicros", "1700000000000001"), ("inputRecords", "12"),
                                 ("id", "local-another")]:
                with self.subTest(key=key):
                    path.write_text(json.dumps(dict(original, **{key: changed})))
                    with self.assertRaises(RuntimeError):
                        HELPER.verify_continuous_views()
            path.write_text(json.dumps(original))
            browser = root / "browser-new-data.json"
            changed = json.loads(browser.read_text())
            changed["submissions"] = 1
            browser.write_text(json.dumps(changed))
            with self.assertRaisesRegex(RuntimeError, "resubmission"):
                HELPER.verify_continuous_views()

    def test_continuous_restart_appends_data_while_only_execution_services_are_stopped(self):
        events = []
        with patch.object(HELPER, "run", side_effect=lambda command, *args, **kwargs: events.append(command)), \
                patch.object(HELPER, "wait_driver", side_effect=lambda *args: events.append(["qualified"])):
            HELPER.resume_with_backlog(["java", "fixture"], "127.0.0.1:39093", ["compose"], {}, 4)
        services = ["coordinator", "wayang", "runner-1", "runner-2", "runner-3"]
        self.assertEqual(["compose", "stop", *services], events[0])
        self.assertEqual(["java", "fixture", "append", "127.0.0.1:39093", str(HELPER.DIRECTORY), "4"], events[1])
        self.assertEqual(["compose", "start", *services], events[2])
        self.assertEqual(["qualified"], events[3])

    def test_continuous_success_artifacts_are_invalidated_without_removing_caches(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "DIRECTORY", Path(directory)):
            root = Path(directory)
            for phase in ["new-data", "after-restart", "stopped"]:
                for prefix, suffix in [("deployment", "json"), ("browser", "json"), ("browser", "png"), ("verified", "json")]:
                    (root / f"{prefix}-{phase}.{suffix}").write_text("previous run")
            (root / "build-source.sha256").write_text("cache")
            HELPER.clear_run_artifacts()
            self.assertEqual(["build-source.sha256"], [path.name for path in root.iterdir()])

    def assert_credentials_required(self, compose):
        for name, count in {"POSTGRES_PASSWORD": 2, "SECRET_KEY": 1, "FIRST_SUPERUSER_PASSWORD": 1}.items():
            values = [line.split(":", 1)[1].strip() for line in compose.splitlines()
                      if line.lstrip().startswith(name + ":")]
            self.assertEqual(count, len(values), f"{name} substitution count")
            for occurrence, value in enumerate(values, 1):
                self.assertTrue(value.startswith('"${' + name + ':?') and value.endswith('}"'),
                                f"{name} occurrence {occurrence} requires a nonempty environment value")

    def test_compose_credential_fields_require_nonempty_environment_values(self):
        self.assert_credentials_required((HELPER.ROOT / "docker-compose.wayang-rust.yml").read_text())

    def test_credential_guard_rejects_fallback_missing_operator_and_empty_value_mutations(self):
        compose = (HELPER.ROOT / "docker-compose.wayang-rust.yml").read_text()
        checked = 0
        for name, count in {"POSTGRES_PASSWORD": 2, "SECRET_KEY": 1, "FIRST_SUPERUSER_PASSWORD": 1}.items():
            matches = list(re.finditer(r"\$\{" + name + r":\?[^}]*\}", compose))
            self.assertEqual(count, len(matches), f"{name} required substitution count")
            replacements = {"fallback": "${" + name + ":-}", "missing operator": "${" + name + "}",
                            "allows empty": "${" + name + "?Set " + name + "}"}
            for occurrence, match in enumerate(matches, 1):
                for mode, replacement in replacements.items():
                    mutated = compose[:match.start()] + replacement + compose[match.end():]
                    with self.subTest(field=name, occurrence=occurrence, mode=mode):
                        with self.assertRaisesRegex(AssertionError, name):
                            self.assert_credentials_required(mutated)
                    checked += 1
        self.assertEqual(12, checked)

    def test_helper_supplies_required_credentials_before_compose_validation(self):
        names = {"POSTGRES_PASSWORD", "SECRET_KEY", "FIRST_SUPERUSER_PASSWORD"}
        environment = {}
        def validate(command, supplied, label, timeout):
            self.assertEqual("compose-config", label)
            self.assertEqual(["--profile", "build", "config", "--quiet"], command[-4:])
            path = Path(command[command.index("--env-file") + 1])
            values = dict(line.split("=", 1) for line in path.read_text().splitlines())
            for name in names:
                self.assertTrue(name in supplied and bool(supplied[name]), f"{name} process environment")
                self.assertTrue(name in values and values[name] == supplied[name], f"{name} private environment file")
            raise RuntimeError("stop before Docker execution")
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "ROOT", Path(directory)), \
                patch.object(HELPER, "SECRET_VALUES", []), patch.object(HELPER, "run", side_effect=validate) as run, \
                patch.object(HELPER.secrets, "token_hex", side_effect=lambda size: format(size, "x")[0] * (2 * size)) as hexadecimal, \
                patch.object(HELPER.secrets, "token_urlsafe", side_effect=lambda size: "u" * size) as urlsafe:
            (Path(directory) / "target").mkdir()
            with self.assertRaisesRegex(RuntimeError, "stop before Docker execution"):
                HELPER.execute(None, environment, 39093, 18081)
            self.assertEqual(1, run.call_count)
            self.assertEqual([12, 24, 32], [call.args[0] for call in hexadecimal.call_args_list])
            self.assertEqual([32], [call.args[0] for call in urlsafe.call_args_list])
            self.assertEqual([], list((Path(directory) / "target/wayang-rust-private").iterdir()))

    def test_helper_provides_port_validation_before_any_docker_mutation(self):
        self.assertEqual(39093, HELPER.port_number("39093", "KAFSCALE_BROKER_PORT"))
        for value in ["", "0", "01", "+1", "65536", " 1", "١"]:
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "canonical integer"):
                HELPER.port_number(value, "PORT")

    def test_occupied_ports_are_refused_without_stopping_anything(self):
        with patch.object(HELPER.socket, "socket") as create:
            create.return_value.__enter__.return_value.bind.side_effect = OSError("occupied")
            with self.assertRaisesRegex(RuntimeError, "no existing service was stopped"):
                HELPER.free_port(18081)

    def test_existing_project_is_refused_after_read_only_inventory(self):
        with patch.object(HELPER, "run", return_value="existing-container\n") as run:
            with self.assertRaisesRegex(RuntimeError, "no existing project was changed"):
                HELPER.empty_project({})
            self.assertEqual(1, run.call_count)
            self.assertEqual(["docker", "ps"], run.call_args.args[0][:2])

    def test_private_files_are_exclusive_and_owner_readable_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated.env"
            HELPER.private_file(path, "private-value")
            self.assertEqual(0o600, os.stat(path).st_mode & 0o777)
            with self.assertRaisesRegex(RuntimeError, "already exists"):
                HELPER.private_file(path, "replacement")
            self.assertEqual("private-value", path.read_text())

    def test_logs_redact_private_values_and_never_print_command_arguments(self):
        command = ["/runtime/node", "classpath:" * 8192]
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "DIRECTORY", Path(directory)), \
                patch.object(HELPER, "SECRET_VALUES", ["private-value"]), patch.object(
                    HELPER.subprocess, "run", return_value=subprocess.CompletedProcess(command, 0, stdout=b"private-value output")) as run:
            console = io.StringIO()
            with contextlib.redirect_stdout(console):
                self.assertEqual("<redacted> output", HELPER.run(command, {}, "probe"))
            self.assertNotIn("private-value", (Path(directory) / "probe.log").read_text())
            self.assertNotIn(command[1], console.getvalue())
            self.assertEqual(command, run.call_args.args[0])

    def test_unmounted_classpath_entries_fail_instead_of_being_dropped(self):
        repository = Path("/reference/maven")
        value = os.pathsep.join([str(HELPER.ROOT / "opentsx-wayang/target/classes"), str(repository / "dependency.jar")])
        self.assertEqual("/workspace/opentsx-wayang/target/classes:/m2/dependency.jar",
                         HELPER.container_classpath(value, repository))
        with self.assertRaisesRegex(RuntimeError, "outside the mounted source"):
            HELPER.container_classpath("/unmounted/dependency.jar", repository)

    def test_source_changes_during_build_require_a_rebuild(self):
        with patch.object(HELPER, "source_signature", return_value="changed"):
            with self.assertRaisesRegex(RuntimeError, "sources changed during the build"):
                HELPER.verify_build_sources("captured-before-build")
        with patch.object(HELPER, "source_signature", return_value="same"):
            HELPER.verify_build_sources("same")

    def test_build_configuration_changes_invalidate_cached_artifacts(self):
        names = ["pom.xml", "opentsx-wayang/pom.xml", "opentsx-model/pom.xml",
                 "opentsx-saas-frontend/package-lock.json", "opentsx-saas-backend/requirements.txt",
                 "docker-compose.wayang-rust.yml", "deploy/compose/wayang-rust-nginx.conf",
                 "tools/verify_wayang_rust_compose.py", "tools/build_kafscale_proxy.py", "tools/kafscale_proxy_cluster.go",
                 "tools/kafscale_metadata_overlay.py", "tools/kafscale_metadata_test.go",
                 "tools/kafscale_proxy_protocol_overlay.py", "tools/kafscale_proxy_protocol_test.go",
                 "bin/opentsx", "opentsx-saas-frontend/package.json",
                 "opentsx-saas-frontend/vite.config.ts", "opentsx-saas-frontend/tsconfig.json",
                 "opentsx-saas-frontend/tsconfig.node.json", "opentsx-saas-frontend/postcss.config.js",
                 "opentsx-saas-frontend/tailwind.config.js", "opentsx-saas-frontend/index.html",
                 "opentsx-saas-frontend/src/workbench/workbench.css", "systems/catalogs/tsx-catalog-1.yaml",
                 "systems/processors/resample.yaml"]
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "ROOT", Path(directory)):
            for name in names:
                path = Path(directory) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("initial")
            initial = HELPER.source_signature()
            for name in names:
                with self.subTest(name=name):
                    path = Path(directory) / name
                    path.write_text("changed")
                    self.assertNotEqual(initial, HELPER.source_signature())
                    path.write_text("initial")

    def test_dependency_install_recreates_the_retained_environment(self):
        compose = (HELPER.ROOT / "docker-compose.wayang-rust.yml").read_text()
        service = compose.split("  python-deps:\n", 1)[1].split("\n  runner-1:", 1)[0]
        command_line = next(line for line in service.splitlines() if line.strip().startswith("command:"))
        command = json.loads(command_line[command_line.index('"'):command_line.rindex('"') + 1])
        create = shlex.split(command.split(" && ", 1)[0])
        self.assertEqual(["python", "-m", "venv"], create[:3])
        self.assertEqual("/opt/venv", create[-1])
        with tempfile.TemporaryDirectory() as directory:
            environment = Path(directory) / "venv"
            environment.mkdir()
            stale = environment / "stale-native-package.so"
            stale.write_text("unusable dependency from another interpreter")
            subprocess.run([sys.executable, *create[1:-1], str(environment)], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertFalse(stale.exists(), "the Compose installer retained stale native dependencies")

    def test_new_run_removes_only_known_public_evidence(self):
        evidence = {"result.json", "fixture.json", "submitted-dag.txt", "verified.json"}
        for phase in ["initial", "retry", "restart"]:
            evidence.update({f"deployment-{phase}.json", f"browser-{phase}.json", f"browser-{phase}.png",
                             f"browser-{phase}-failed.png", f"verified-{phase}.json"})
        retained = {"build-source.sha256", "backend-install.log", "unrelated-cache.json"}
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "DIRECTORY", Path(directory)):
            for name in evidence | retained:
                (Path(directory) / name).write_text("previous run")
            HELPER.clear_run_artifacts()
            self.assertEqual(retained, {path.name for path in Path(directory).iterdir()})
            HELPER.clear_run_artifacts()
            self.assertEqual(retained, {path.name for path in Path(directory).iterdir()})

    def test_restart_requires_a_fresh_successful_worker_capability_probe(self):
        events = []
        with patch.object(HELPER, "run", side_effect=lambda *args, **kwargs: events.append("restart")), \
                patch.object(HELPER, "wait_driver", side_effect=lambda *args: events.append("qualified")):
            HELPER.restart_runtime(["compose"], {})
        self.assertEqual(["restart", "qualified"], events)
        with patch.object(HELPER, "run"), patch.object(
                HELPER, "wait_driver", side_effect=RuntimeError("workers are incompatible")):
            with self.assertRaisesRegex(RuntimeError, "workers are incompatible"):
                HELPER.restart_runtime(["compose"], {})

    def test_all_three_phases_require_identical_clock_cut_and_whole_topic_inventory(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(HELPER, "DIRECTORY", Path(directory)):
            root = Path(directory)
            graph = b"original UI text\n"
            sources = [{"topic": f"tsx.test.input{number}.v2", "partitions": number} for number in range(1, 4)]
            value = {"id": "local-test", "specHash": hashlib.sha256(graph).hexdigest(), "planHash": "a" * 64,
                     "createdAtMicros": "1700000000000000", "state": "completed", "assignments": [
                         {"node": f"worker-{number}", "inputTopic": item["topic"], "partitionCount": str(number)}
                         for number, item in enumerate(sources, 1)]}
            (root / "submitted-dag.txt").write_bytes(graph)
            (root / "fixture.json").write_text(json.dumps({"sources": sources}))
            for phase in ["initial", "retry", "restart"]:
                (root / f"deployment-{phase}.json").write_text(json.dumps(value))
                (root / f"browser-{phase}.json").write_text(json.dumps({"phase": phase, "assignments": 3}))
                (root / f"verified-{phase}.json").write_text(json.dumps({"verifiedRecords": 12, "distinctEpisodes": 12}))
            self.assertEqual(3, HELPER.verify_views()["workers"])
            changed = dict(value, createdAtMicros="1700000000000001")
            (root / "deployment-retry.json").write_text(json.dumps(changed))
            with self.assertRaisesRegex(RuntimeError, "cut, clock or receipt"):
                HELPER.verify_views()


if __name__ == "__main__":
    unittest.main()
