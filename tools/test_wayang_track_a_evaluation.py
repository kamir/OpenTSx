import csv
from contextlib import ExitStack, nullcontext, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


class TrackAEvaluationTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("track_a_evaluation", Path(__file__).with_name("wayang_track_a_evaluation.py"))
        self.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tool)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def csv(self, name, rows):
        with (self.root / name).open("w", newline="") as destination:
            writer = csv.DictWriter(destination, fieldnames=rows[0].keys())
            writer.writeheader()
            writer.writerows(rows)

    def federation(self, sites=3):
        run = {"schema": "opentsx.federation-evaluation.v1", "siteCount": sites, "correctnessPassed": True,
               "centralizedPlanPassed": True,
               "routeSAdaptersEqualJ": True, "topology": {"siteAgentInstances": sites, "brokers": sites + 1, "jvmProcesses": 1},
               "episodes": 3600, "values": 2160000, "rawSeries": 100, "windows": 420, "partials": 420 * sites,
               "dt2Tolerance": 3e-12, "maxRelativeMeanError": 1e-14, "maxRelativeM2Error": 2e-12,
               "sourceSpecHash": "a" * 64, "scenarioSha256": "b" * 64,
               "centralizedTransferElapsedNanos": 100, "centralizedPlanElapsedNanos": 200, "centralizedPlanCpuNanos": 150}
        for field, name in self.tool.ERRORS.items():
            run[name] = 1e-14 if "mean" in field else 2e-12
        (self.root / "run.json").write_text(json.dumps(run))
        windows = [{"group_id": str(index), "window_type": "PT10M" if index < 360 else "PT1H",
                    "window_start_micros": "0", "window_end_micros": "600000000",
                    "mean_relative_error": "1e-14", "m2_relative_error": "2e-12",
                    "central_native_mean_relative_error": "1e-14", "central_native_m2_relative_error": "2e-12",
                    "federated_central_mean_relative_error": "1e-14", "federated_central_m2_relative_error": "2e-12",
                    "sketch_equal": "true", "exact_fields_equal": "true", "central_native_sketch_equal": "true",
                    "central_native_exact_fields_equal": "true"} for index in range(420)]
        self.csv("windows.csv", windows)
        rows = []
        for route in self.tool.FEDERATION.ROUTES:
            for site in range(1, sites + 1):
                http = route == "job-state"
                rows.append(dict(zip(self.tool.FEDERATION.FIELDS,
                                     (route, f"s{site}", "tsx.wind.episodes.v2" if route == "centralized" else "tsx.wind.aggregates.v2", "" if http else "100",
                                      "" if http else str(360 * sum((i * sites + 9) // 10 == site for i in range(1, 11)) if route == "centralized" else 420), "" if http else "0",
                                      "20" if http else "", "420" if http else "", "30" if http else "", "1.5"))))
        self.csv("bytes.csv", rows)
        return run, windows, rows

    def streaming(self):
        run = {"schema": "opentsx.streaming-evaluation.v1", "chain": "clean", "runtime": "java", "partitions": 4,
               "inputTopic": "tsx.wind.episodes.v2", "outputTopic": "tsx.wind.clean.v2",
               "nativeImplementation": "VectorProcessor", "correctnessPassed": True, "checkpointVerified": True,
               "inputRecords": 3600, "inputValues": 2160000, "outputRecords": 1, "elapsedNanos": 10000000,
               "cpuNanos": 1000000, "availableProcessors": 4, "firstFetchNanos": 100000, "firstOutputNanos": 1000000,
               "startedNanos": 2000000, "endedNanos": 12000000, "lastOutputAckNanos": 1100000}
        (self.root / "case.json").write_text(json.dumps(run))
        row = {"output_id": "output", "input_ids": "in-0", "input_request_ns": "1000000", "input_ack_ns": "1100000",
               "output_request_ns": "3000000", "output_ack_ns": "3100000", "latency_ms": "2.0",
               "output_partition": "0", "output_offset": "0", "output_type": "Episode"}
        self.csv("records.csv", [row])
        attempts = [{"topic": "tsx.wind.episodes.v2", "partition": "0", "offset": str(i), "identity": f"in-{i}",
                     "type": "Episode", "request_ns": "1000000", "ack_ns": "1100000", "error_code": "0"} for i in range(3600)]
        attempts.append({"topic": "tsx.wind.clean.v2", "partition": "0", "offset": "0", "identity": "output", "type": "Episode",
                         "request_ns": "3000000", "ack_ns": "3100000", "error_code": "0"})
        self.csv("produce-attempts.csv", attempts)
        measurement = {"wall_ms": "10", "episodes_per_second": "360000", "values_per_second": "216000000",
                       "episodes_per_second_per_core": "90000", "values_per_second_per_core": "54000000",
                       "p50_ms": "2", "p95_ms": "2", "p99_ms": "2"}
        self.csv("measurement.csv", [measurement])
        return run, row, attempts

    def test_complete_matrix_contains_nine_federation_and_fifty_four_streaming_conditions(self):
        rows = self.tool.cases(3)
        self.assertEqual(63, len(rows))
        self.assertEqual(63, len({row[0] for row in rows}))
        self.assertEqual(9, sum(row[1] == "federation" for row in rows))
        self.assertEqual(54, sum(row[1] == "streaming" for row in rows))

    def test_matrix_rejects_unsupported_duplicate_or_empty_axes(self):
        for args in ((0,), (True,), (1, [2]), (1, [1, 1]), (1, [1], [])):
            with self.subTest(args=args), self.assertRaises(ValueError):
                self.tool.cases(*args)

    def test_ps_cpu_clocks_cover_linux_and_macos_without_wall_time(self):
        self.assertEqual(1.25, self.tool.cpu_seconds("0:01.25"))
        self.assertEqual(3723, self.tool.cpu_seconds("01:02:03"))
        self.assertEqual(93784, self.tool.cpu_seconds("1-02:03:04"))
        for value in ("", "NaN", "1:99", "1:70:00", "-1:01"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.tool.cpu_seconds(value)

    def test_process_samples_include_only_owned_trees(self):
        text = "10 1 20 0:01.00 /jdk/bin/java\n11 10 30 0:02.00 /tmp/kafscale-broker\n12 1 999 0:09.00 /other\n"
        rows = self.tool.process_rows(text, {10: "driver"}, 5)
        self.assertEqual([(5, 10, 1, "driver", 20480, 1), (5, 11, 10, "broker", 30720, 2)], rows)
        with self.assertRaises(ValueError):
            self.tool.process_rows("10 1 20", {10: "driver"}, 0)

    def test_resource_summary_sums_only_simultaneous_owned_brokers(self):
        rows = [{"elapsed_ms": t, "pid": pid, "ppid": "1", "role": role, "rss_bytes": rss, "cpu_seconds": cpu}
                for t, pid, role, rss, cpu in ((0, 10, "driver", 1024, 0), (0, 11, "broker", 2000, 1),
                                               (0, 12, "broker", 3000, 2), (1, 10, "driver", 2048, 1),
                                               (1, 11, "broker", 4000, 2), (1, 13, "subprocess", 9999, 2))]
        self.csv("resources.csv", rows)
        self.assertEqual((2048, 5000, 1), self.tool.resource_result(self.root))

    def test_measurement_builds_guarded_transport_and_uses_only_proxy_endpoints(self):
        output = self.root / "results"
        transport_sources = ("tools/build_kafscale_proxy.py", "tools/kafscale_proxy_cluster.go", "tools/kafscale_proxy_cluster_test.go",
                             "tools/kafscale_metadata_overlay.py", "tools/kafscale_metadata_test.go", "tools/kafscale_proxy_protocol_overlay.py",
                             "tools/kafscale_proxy_protocol_test.go")
        snapshot = {"commit": "a" * 40, "files": {name: "b" * 64 for name in (*self.tool.SOURCES, *transport_sources)}}
        commands, measured = [], []

        def execute(command, **kwargs):
            commands.append(command)
            if command[0] == "git" and command[1] == "clone":
                Path(command[-1]).mkdir()
            elif command[:3] == ["git", "rev-parse", "HEAD"]:
                return self.tool.COMMON.KAFSCALE_COMMIT
            elif command[0] == "go" and command[1] == "version":
                return "go version go1.25.2 synthetic"
            elif command[0] == "go" and command[1] == "build":
                Path(command[3]).write_bytes(Path(command[3]).name.encode())
            elif command[-1] == "-version":
                return 'openjdk version "21.0.8"'
            elif command[0] == "mvn":
                for option in command:
                    if option.startswith("-Dmdep.outputFile="):
                        Path(option.split("=", 1)[1]).write_text("dependency.jar")
            return "synthetic"

        def measure(command, directory, env, roots, record):
            measured.append((command, env, roots))
            record(command)
            names = ("bytes.csv", "windows.csv", "run.json", "resources.csv") if "org.opentsx.wayang.FederationEvaluation" in command else \
                ("case.json", "measurement.csv", "records.csv", "produce-attempts.csv", "resources.csv")
            for name in names:
                (directory / name).write_text("synthetic")

        process = Mock(pid=101)
        process.poll.return_value = None
        overlay = self.root / "transport-overlay.json"
        argv = ["wayang_track_a_evaluation.py", "--output", str(output), "--java-home", str(self.root / "jdk"),
                "--pilot", "--repetitions", "1", "--sites", "1", "--partitions", "1", "--chains", "clean", "--runtimes", "java"]
        with ExitStack() as stack:
            stack.enter_context(patch.object(self.tool.sys, "argv", argv))
            stack.enter_context(patch.object(self.tool.platform, "system", return_value="Darwin"))
            stack.enter_context(patch.object(self.tool.platform, "platform", return_value="synthetic"))
            stack.enter_context(patch.object(self.tool.FEDERATION, "source_snapshot", return_value=snapshot))
            stack.enter_context(patch.object(self.tool.FEDERATION, "require_same_source"))
            stack.enter_context(patch.object(self.tool.COMMON, "run", side_effect=execute))
            transport_overlay = stack.enter_context(patch.object(self.tool.COMMON.build_kafscale_proxy, "transport_overlay", return_value=overlay))
            stack.enter_context(patch.object(self.tool.COMMON, "freeze_classpath", return_value=("frozen-classpath", [])))
            stack.enter_context(patch.object(self.tool.COMMON, "power_assertion", return_value=nullcontext(None)))
            stack.enter_context(patch.object(self.tool.COMMON, "require_power_assertion"))
            stack.enter_context(patch.object(self.tool.COMMON, "free_port", return_value=39092))
            stack.enter_context(patch.object(self.tool, "measured_command", side_effect=measure))
            stack.enter_context(patch.object(self.tool, "federation_result"))
            stack.enter_context(patch.object(self.tool, "streaming_result"))
            stack.enter_context(patch.object(self.tool, "report"))
            launch = stack.enter_context(patch.object(self.tool.subprocess, "Popen", return_value=process))
            stack.enter_context(patch.object(self.tool.socket, "create_connection", return_value=nullcontext(None)))
            stop = stack.enter_context(patch.object(self.tool, "stop_group"))
            with redirect_stdout(io.StringIO()):
                self.tool.main()

        transport_overlay.assert_called_once()
        builds = [command for command in commands if command[:2] == ["go", "build"]]
        self.assertEqual({"kafscale-broker", "kafscale-proxy", "kafscale-cluster"}, {Path(command[3]).name for command in builds})
        self.assertTrue(all(f"-overlay={overlay}" in command for command in builds))
        self.assertIn(["go", "test", f"-overlay={overlay}", "-run", "^TestOpenTSx(Proxy|Metadata)", "-count=1", "./cmd/proxy", "./pkg/metadata"], commands)
        self.assertEqual(2, len(measured))
        for name in ("BROKER", "PROXY", "CLUSTER"):
            self.assertEqual(f"kafscale-{name.lower()}", Path(measured[0][1][f"KAFSCALE_{name}_BINARY"]).name)
        launched = launch.call_args.args[0]
        self.assertEqual("kafscale-cluster", Path(launched[0]).name)
        self.assertEqual("127.0.0.1:39092", launched[launched.index("--listen") + 1])
        self.assertEqual("39092", launched[launched.index("--advertised-port") + 1])
        self.assertEqual("kafscale-proxy", Path(launched[launched.index("--proxy-binary") + 1]).name)
        self.assertEqual(str(output.resolve() / "streaming/clean-p1-java-r1/metadata"), launched[launched.index("--data-dir") + 1])
        self.assertNotIn("KAFSCALE_BROKER_ADDR", launch.call_args.kwargs["env"])
        self.assertEqual({101: "subprocess"}, measured[1][2])
        stop.assert_called_once_with(process)
        metadata = json.loads((output / "metadata.json").read_text())
        self.assertEqual("complete", metadata["status"])
        self.assertEqual("kafscale-proxy", metadata["transport"])
        self.assertEqual({name: "b" * 64 for name in transport_sources}, metadata["transportSourcesSha256"])
        for name in ("broker", "proxy", "cluster"):
            self.assertEqual(64, len(metadata[f"{name}BinarySha256"]))
            self.assertEqual(len(f"kafscale-{name}"), metadata[f"{name}BinaryBytes"])

    def test_federation_reads_all_three_site_counts(self):
        for sites in (1, 3, 10):
            with self.subTest(sites=sites):
                self.federation(sites)
                run, rows = self.tool.federation_result(self.root, sites)
                self.assertEqual(420 * sites, run["partials"])
                self.assertEqual(3 * sites, len(rows))

    def test_federation_rejects_missing_or_duplicate_sites(self):
        _, _, rows = self.federation()
        rows[-1] = rows[0]
        self.csv("bytes.csv", rows)
        with self.assertRaises(ValueError):
            self.tool.federation_result(self.root, 3)

    def test_federation_rejects_inapplicable_layers_and_failed_produce(self):
        for field, value in (("http_body_bytes", "20"), ("produce_errors", "1"), ("produce_frame_bytes", "NaN")):
            with self.subTest(field=field):
                _, _, rows = self.federation()
                rows[0][field] = value
                self.csv("bytes.csv", rows)
                with self.assertRaises(ValueError):
                    self.tool.federation_result(self.root, 3)

    def test_federation_rejects_false_exactness_or_out_of_bound_window(self):
        for field, value in (("central_native_sketch_equal", "false"), ("mean_relative_error", "4e-12")):
            with self.subTest(field=field):
                _, windows, _ = self.federation()
                windows[0][field] = value
                self.csv("windows.csv", windows)
                with self.assertRaises(ValueError):
                    self.tool.federation_result(self.root, 3)

    def test_streaming_recomputes_latency_and_checks_native_binding(self):
        run, row, _ = self.streaming()
        self.assertEqual(run, self.tool.streaming_result(self.root, ("clean", "java", 4))[0])
        row["latency_ms"] = "1"
        self.csv("records.csv", [row])
        with self.assertRaises(ValueError):
            self.tool.streaming_result(self.root, ("clean", "java", 4))
        self.streaming()
        run["nativeImplementation"] = "java-decode"
        (self.root / "case.json").write_text(json.dumps(run))
        with self.assertRaises(ValueError):
            self.tool.streaming_result(self.root, ("clean", "java", 4))

    def test_streaming_rejects_missing_or_failed_wire_observations(self):
        for change in ("missing", "error", "clock"):
            with self.subTest(change=change):
                _, _, attempts = self.streaming()
                if change == "missing":
                    attempts.pop(0)
                elif change == "error":
                    attempts[0]["error_code"] = "7"
                else:
                    attempts[-1]["request_ns"] = "2500000"
                self.csv("produce-attempts.csv", attempts)
                with self.assertRaises(ValueError):
                    self.tool.streaming_result(self.root, ("clean", "java", 4))

    def test_streaming_rejects_stale_summary_rates(self):
        self.streaming()
        with (self.root / "measurement.csv").open() as source:
            measurement = list(csv.DictReader(source))
        measurement[0]["p99_ms"] = "999"
        self.csv("measurement.csv", measurement)
        with self.assertRaises(ValueError):
            self.tool.streaming_result(self.root, ("clean", "java", 4))

    def test_streaming_rejects_outside_activation_clocks_and_unverified_checkpoint(self):
        for field, value in (("firstFetchNanos", 20000000), ("checkpointVerified", False), ("cpuNanos", float("nan"))):
            with self.subTest(field=field):
                run, _, _ = self.streaming()
                run[field] = value
                (self.root / "case.json").write_text(json.dumps(run))
                with self.assertRaises(ValueError):
                    self.tool.streaming_result(self.root, ("clean", "java", 4))

    def test_streaming_rejects_inconsistent_job_duration_and_first_output(self):
        for field, value in (("endedNanos", 13000000), ("firstOutputNanos", 5000000), ("lastOutputAckNanos", 1000000)):
            with self.subTest(field=field):
                run, _, _ = self.streaming()
                run[field] = value
                (self.root / "case.json").write_text(json.dumps(run))
                with self.assertRaises(ValueError):
                    self.tool.streaming_result(self.root, ("clean", "java", 4))

    def test_streaming_rejects_duplicate_wire_positions_and_output_ids(self):
        _, _, attempts = self.streaming()
        attempts[1]["offset"] = "0"
        self.csv("produce-attempts.csv", attempts)
        with self.assertRaises(ValueError):
            self.tool.streaming_result(self.root, ("clean", "java", 4))
        run, row, _ = self.streaming()
        run["outputRecords"] = 2
        (self.root / "case.json").write_text(json.dumps(run))
        self.csv("records.csv", [row, row])
        with self.assertRaises(ValueError):
            self.tool.streaming_result(self.root, ("clean", "java", 4))

    def test_streaming_partition_count_is_an_integer_not_boolean(self):
        run, _, _ = self.streaming()
        run["partitions"] = True
        (self.root / "case.json").write_text(json.dumps(run))
        with self.assertRaises(ValueError):
            self.tool.streaming_result(self.root, ("clean", "java", 1))

    def test_report_keeps_metrics_in_readable_tables_without_clock_claim_changes(self):
        run, _, _ = self.federation(1)
        run.update(centralizedFirstFetchNanos=20, centralizedFirstStatisticsNanos=100)
        (self.root / "run.json").write_text(json.dumps(run))
        self.streaming()
        self.csv("resources.csv", [{"elapsed_ms": "0", "pid": "1", "ppid": "0", "role": "driver", "rss_bytes": "1024", "cpu_seconds": "0"}])
        metadata = {"openTsxCommit": "a" * 40, "startedAt": "2026-11-18T00:00:00Z", "profile": "synthetic test",
                    "hardware": "synthetic", "platform": "synthetic", "javaVersion": 'version "21.0.8"',
                    "goVersion": "go1.25.2", "runtimeBytes": 100, "brokerBinaryBytes": 100,
                    "transport": "kafscale-proxy",
                    "matrix": [(".", "federation", 1, 1), (".", "streaming", ("clean", "java", 4), 1)]}
        self.tool.report(self.root, metadata)
        text = (self.root / "REPORT.md").read_text()
        self.assertLessEqual(max(map(len, text.splitlines())), 80)
        self.assertIn("not a speed comparison", text)
        self.assertIn("C4J-r1", text)
        self.assertIn("Kafka event/envelope timestamps are not latency clocks", text)
        self.assertIn("Every Kafka client uses a KafScale Proxy", text)
        self.assertIn("outside the broker RSS column", text)


if __name__ == "__main__":
    unittest.main()
