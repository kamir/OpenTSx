import contextlib
import importlib.util
import io
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


if __name__ == "__main__":
    unittest.main()
