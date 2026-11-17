import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "compose_helper", Path(__file__).with_name("verify_rust_runner_compose.py")
)
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class ComposeHelperLoggingTest(unittest.TestCase):
    def test_long_classpath_stays_in_arguments_and_out_of_console(self):
        classpath = "dependency.jar:" * 8192
        command = ["/local/bin/java", "-cp", classpath, "Fixture"]
        expected_command = tuple(command)
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(HELPER, "DIRECTORY", Path(directory)), patch.object(
                HELPER.subprocess, "run", return_value=subprocess.CompletedProcess(command, 0)
            ) as execute:
                with contextlib.redirect_stdout(output):
                    self.assertEqual("", HELPER.run(command, {}, "console-probe", timeout=1))
                self.assertEqual(expected_command, tuple(execute.call_args.args[0]))
                self.assertEqual(expected_command, tuple(command))
        self.assertIn("console-probe", output.getvalue())
        self.assertIn("java", output.getvalue())
        self.assertNotIn(classpath, output.getvalue())
        self.assertLess(len(output.getvalue()), 256)


class ComposeWorkerWaitTest(unittest.TestCase):
    def run_phase(self, ids=None, wait_error=None, wait_output="0\n0\n0\n"):
        identifiers = ids if ids is not None else [str(index) * 64 for index in range(1, 4)]
        statuses = [{"Service": worker, "State": "exited", "ExitCode": 0} for worker in HELPER.WORKERS]

        def command_result(command, environment, name, timeout):
            if command == HELPER.COMPOSE + ["--profile", "workers", "wait"] + HELPER.WORKERS:
                raise RuntimeError('no containers for project "opentsx-rust-runner"')
            if name.endswith("-ids"):
                return "\n".join(identifiers) + "\n"
            if command[:3] == ["docker", "container", "wait"]:
                self.assertEqual(identifiers, command[3:])
                if wait_error is not None:
                    raise RuntimeError(wait_error)
                return wait_output
            if name.endswith("-status"):
                return json.dumps(statuses)
            if name.startswith("runner-"):
                return json.dumps({"node": name.split("-completed", 1)[0]}) + "\n"
            return ""

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(HELPER, "DIRECTORY", Path(directory)), patch.object(
                HELPER, "run", side_effect=command_result
            ) as execute, patch.object(HELPER, "verify_receipt") as verify:
                HELPER.run_workers({}, "completed-retry")
                self.assertEqual(3, verify.call_count)
                return execute.call_args_list

    def test_already_exited_workers_are_waited_by_explicit_container_ids(self):
        calls = self.run_phase()
        commands = [call.args[0] for call in calls]
        self.assertIn(["docker", "container", "wait", "1" * 64, "2" * 64, "3" * 64], commands)
        self.assertIn(HELPER.COMPOSE + ["--profile", "workers", "ps", "--all", "--quiet"] + HELPER.WORKERS, commands)
        self.assertNotIn(HELPER.COMPOSE + ["--profile", "workers", "wait"] + HELPER.WORKERS, commands)

    def test_missing_duplicate_or_invalid_container_ids_are_refused(self):
        for ids in [[], ["1" * 64, "2" * 64], ["1" * 64] * 3, ["--help", "2" * 64, "3" * 64]]:
            with self.subTest(ids=ids), self.assertRaisesRegex(RuntimeError, "three distinct worker container IDs"):
                self.run_phase(ids=ids)

    def test_container_wait_error_is_not_hidden_by_successful_statuses(self):
        with self.assertRaisesRegex(RuntimeError, "container wait failed"):
            self.run_phase(wait_error="container wait failed")

    def test_missing_or_unsuccessful_wait_results_are_refused(self):
        for output in ["", "0\n0\n", "0\n3\n0\n"]:
            with self.subTest(output=output), self.assertRaisesRegex(RuntimeError, "three successful exits"):
                self.run_phase(wait_output=output)


if __name__ == "__main__":
    unittest.main()
