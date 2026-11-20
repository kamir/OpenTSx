from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
VERSION = "3.1.0"
PRODUCERS = {
    "opentsx-model/src/main/java/org/opentsx/model/Episodes.java": "opentsx-model",
    "opentsx-legacy-bridge/src/main/java/org/opentsx/bridge/LegacyBridge.java": "opentsx-legacy-bridge",
    "opentsx-windpark/src/main/java/org/opentsx/windpark/sim/WindparkSimulator.java": "opentsx-windpark",
    "opentsx-market/src/main/java/org/opentsx/market/MarketCli.java": "opentsx-market",
    "opentsx-market/src/main/java/org/opentsx/market/archive/BrokerPublish.java": "opentsx-market",
    "opentsx-store-opentsdb-v2/src/main/java/org/opentsx/store/opentsdb/OpenTsdbSeriesStore.java": "opentsx-store-opentsdb-v2",
    "rust/tsx-runner/src/pairs.rs": "opentsx-model",
}
JAR_LAUNCHERS = (
    "opentsx-lg/bin/run_KSAPP_01.sh",
    "opentsx-lg/bin/run_KTAPP_01.sh",
    "opentsx-lg/bin/run_TSG_01.sh",
    "opentsx-lg/src/main/docker-compose/bin/executor-script.sh",
    "opentsx-lg/src/main/docker-compose/bin/run_LatencyProbe_CHECK.sh",
    "opentsx-lg/src/main/docker-compose/bin/run_LatencyProbe_CONS.sh",
    "opentsx-lg/src/main/docker-compose/bin/run_LatencyProbe_PROD.sh",
    "opentsx-lg/src/main/docker-compose/bin/run_LatencyProbe_SETUP.sh",
    "opentsx-lg/src/main/docker/core-tool-image/Dockerfile",
)


class RuntimeVersionTests(unittest.TestCase):
    def test_built_jar_launchers_use_the_current_release(self):
        for name in JAR_LAUNCHERS:
            with self.subTest(source=name):
                versions = re.findall(
                    r"opentsx-lg-([0-9]+\.[0-9]+\.[0-9]+)(?:-jar-with-dependencies)?\.jar",
                    (ROOT / name).read_text(encoding="utf-8"),
                )
                self.assertTrue(versions)
                self.assertEqual({VERSION}, set(versions))

    def test_runtime_producers_identify_the_current_release(self):
        for name, producer in PRODUCERS.items():
            with self.subTest(source=name):
                source = (ROOT / name).read_text(encoding="utf-8")
                versions = re.findall(
                    r'"' + re.escape(producer) + r'/([^"\s]+)"', source
                )
                self.assertEqual([VERSION], versions)

    def test_desktop_label_identifies_the_current_release(self):
        source = (
            ROOT
            / "opentsx-core/src/main/java/org/opentsx/app/bucketanalyser/ModernMacroTrackerFrame.java"
        ).read_text(encoding="utf-8")
        versions = re.findall(r'"OpenTSx v([^"\s]+) \| Task-005"', source)
        self.assertEqual([VERSION], versions)


if __name__ == "__main__":
    unittest.main()
