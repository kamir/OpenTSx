import importlib.util
import json
from pathlib import Path
import platform
import sys
import tempfile
import unittest


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SAMPLER = load("board_resource_sampler")
IDENTITY = load("board_identity")
EVALUATION = load("wayang_board_evaluation")
LAG = load("board_lag")


class Clock:
    """A virtual monotonic clock: sleep advances it, every read costs a little time."""

    def __init__(self, cost=0.01):
        self.now = 1000.0
        self.cost = cost

    def __call__(self):
        self.now += self.cost
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def fake_process(root, pid, *, rss_kb=2048, utime=100, stime=50, name="java (main) x"):
    folder = root / str(pid)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "status").write_text(f"Name:\tjava\nVmRSS:\t   {rss_kb} kB\n", encoding="utf-8")
    fields = ["S"] + ["0"] * 10 + [str(utime), str(stime)] + ["0"] * 10
    (folder / "stat").write_text(f"{pid} ({name}) " + " ".join(fields) + "\n", encoding="utf-8")


class SamplerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        fake_process(self.root / "proc", 42)

    def tearDown(self):
        self.directory.cleanup()

    def run_sampler(self, duration, clock=None, **options):
        clock = clock or Clock()
        output = self.root / "resources.csv"
        SAMPLER.sample(42, duration, output, proc=self.root / "proc", ticks=100, clock=clock, sleep=clock.sleep, **options)
        return output

    def test_output_passes_the_board_evidence_validator(self):
        output = self.run_sampler(2100)
        last = EVALUATION.resource_samples(output.read_bytes(), 2100)
        self.assertLess(2099, last)
        rows = output.read_text().splitlines()
        self.assertEqual(",".join(SAMPLER.FIELDS), rows[0])
        self.assertEqual(2101, len(rows))
        self.assertEqual(["2097152", "1.50", "0"], rows[1].split(",")[1:])

    def test_command_names_with_spaces_and_parentheses_are_parsed(self):
        fake_process(self.root / "proc", 42, utime=250, stime=150, name="a) (b c")
        self.assertEqual((2097152, 4.0), SAMPLER.process_sample(self.root / "proc", 42, 100))

    def test_a_missed_second_stops_the_run(self):
        with self.assertRaisesRegex(RuntimeError, "cadence was missed"):
            self.run_sampler(5, clock=Clock(cost=0.6))

    def test_an_exited_process_stops_the_run(self):
        (self.root / "proc" / "42" / "status").write_text("Name:\tjava\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "no VmRSS"):
            self.run_sampler(3)

    def test_lag_command_output_is_recorded_and_checked(self):
        output = self.run_sampler(2, lag_command=["echo", "17"])
        self.assertTrue(all(row.endswith(",17") for row in output.read_text().splitlines()[1:]))
        output.unlink()
        with self.assertRaisesRegex(ValueError, "one non-negative integer"):
            self.run_sampler(2, lag_command=["echo", "-3"])

    def test_existing_output_is_never_overwritten_and_duration_is_whole_seconds(self):
        output = self.run_sampler(1)
        with self.assertRaises(FileExistsError):
            self.run_sampler(1)
        output.unlink()
        for duration in (0, 2.5):
            with self.subTest(duration=duration), self.assertRaisesRegex(ValueError, "whole number"):
                self.run_sampler(duration)


Uname = platform.uname_result


class IdentityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        (self.root / "etc").mkdir()
        (self.root / "etc/os-release").write_text('NAME="Ubuntu"\nPRETTY_NAME="Ubuntu 18.04.6 LTS"\n')
        (self.root / "proc/device-tree").mkdir(parents=True)
        (self.root / "proc/device-tree/model").write_text("NVIDIA Jetson Nano Developer Kit\x00")
        (self.root / "proc/meminfo").write_text("MemTotal:        4057012 kB\n")
        (self.root / "proc/cpuinfo").write_text("processor\t: 0\nCPU part\t: 0xd07\n")
        for cpu in range(4):
            folder = self.root / f"sys/devices/system/cpu/cpu{cpu}/cpufreq"
            folder.mkdir(parents=True)
            (folder / "scaling_governor").write_text("performance\n")
        zone = self.root / "sys/class/thermal/thermal_zone0"
        zone.mkdir(parents=True)
        (zone / "type").write_text("CPU-therm\n")
        (zone / "temp").write_text("41500\n")
        self.uname = lambda: Uname("Linux", "jetson", "4.9.337-tegra", "#1 SMP", "aarch64")

    def tearDown(self):
        self.directory.cleanup()

    def identity(self, **options):
        return IDENTITY.identity(self.root, "USB 3 SSD, ext4", "1 GbE to KafScale host", uname=self.uname,
                                 power_mode=lambda: "NV Power Mode: MAXN\n0", **options)

    def test_a_ready_jetson_matches_the_board_fields_of_the_validator(self):
        result = self.identity()
        self.assertEqual([], result["problems"])
        board = result["board"]
        self.assertEqual({"architecture": "arm64", "deviceModel": "NVIDIA Jetson Nano Developer Kit",
                          "os": "Ubuntu 18.04.6 LTS", "kernel": "4.9.337-tegra", "cpuGovernor": "performance",
                          "storage": "USB 3 SSD, ext4", "network": "1 GbE to KafScale host",
                          "executionMode": "physical", "system": "Linux", "machine": "aarch64"}, board)
        self.assertEqual(4057012 * 1024, result["detail"]["memoryBytes"])
        self.assertEqual({"CPU-therm": 41.5}, result["detail"]["thermalCelsius"])
        self.assertIn("MAXN", result["detail"]["nvpmodel"])
        self.assertEqual(EVALUATION.ARCHITECTURES, (board["architecture"],))

    def test_a_cpu_without_performance_governor_is_named(self):
        (self.root / "sys/devices/system/cpu/cpu2/cpufreq/scaling_governor").write_text("schedutil\n")
        result = self.identity()
        self.assertEqual("mixed", result["board"]["cpuGovernor"])
        self.assertIn("cpuGovernor is not performance on every CPU", result["problems"])

    def test_armv7_or_aarch32_userland_is_not_a_b4_2_board(self):
        self.uname = lambda: Uname("Linux", "pitop", "6.1.21-v7l+", "#1 SMP", "armv7l")
        self.assertIn("machine is not aarch64 (D-T14: B4.2 needs a physical ARM64 board)", self.identity()["problems"])

    def test_cli_writes_a_new_file_and_fails_when_the_board_is_not_ready(self):
        output = self.root / "identity.json"
        code = IDENTITY.main(["--storage", "SD card", "--network", "local KafScale", "--output", str(output),
                              "--root", str(self.root)])
        written = json.loads(output.read_text())
        self.assertEqual("opentsx.board-identity.v1", written["schema"])
        self.assertEqual(0 if platform.machine() == "aarch64" else 1, code)
        with self.assertRaises(FileExistsError):
            IDENTITY.main(["--storage", "x", "--network", "y", "--output", str(output), "--root", str(self.root)])


class LagTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.file = Path(self.directory.name) / "run.lag"

    def tearDown(self):
        self.directory.cleanup()

    def test_a_fresh_value_is_printed_as_the_sampler_expects(self):
        self.file.write_text("1234\n", encoding="ascii")
        self.assertEqual(1234, LAG.read(self.file))
        self.assertEqual(0, LAG.main(["--file", str(self.file)]))
        self.file.write_text("0\n", encoding="ascii")
        self.assertEqual(0, LAG.read(self.file))

    def test_a_stale_missing_or_malformed_file_fails(self):
        self.file.write_text("7\n", encoding="ascii")
        mtime = self.file.stat().st_mtime
        with self.assertRaisesRegex(ValueError, "old"):
            LAG.read(self.file, now=lambda: mtime + 3.5)
        for text in ("-1\n", "7", "7\n8\n", "\n", "x\n"):
            self.file.write_text(text, encoding="ascii")
            with self.subTest(text=text), self.assertRaises(ValueError):
                LAG.read(self.file)
        self.assertEqual(1, LAG.main(["--file", str(self.file) + ".absent"]))

    def test_the_sampler_records_the_printed_lag(self):
        self.file.write_text("42\n", encoding="ascii")
        command = [sys.executable, str(Path(__file__).with_name("board_lag.py")), "--file", str(self.file)]
        self.assertEqual(42, SAMPLER.lag(command, 5))


if __name__ == "__main__":
    unittest.main()
