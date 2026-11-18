package org.opentsx.wayang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.wayang.federation.AggregateRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FederationEvaluation {
    private static final List<String> BYTE_COLUMNS = List.of("route", "site", "topic", "produce_frame_bytes", "produce_records",
            "produce_errors", "http_body_bytes", "http_records", "tls_client_bytes", "elapsed_ms");
    private static final double TOLERANCE = 3e-12;

    private FederationEvaluation() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("usage: FederationEvaluation <output-directory> <sites>");
        int sites = Integer.parseInt(arguments[1]);
        FederatedWindparkData.siteNames(sites);
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(output)) throw new IllegalArgumentException("evaluation output directory must exist");
        for (String file : List.of("bytes.csv", "windows.csv", "run.json", "execution")) {
            if (Files.exists(output.resolve(file))) throw new IllegalArgumentException("evaluation output already exists: " + file);
        }
        long started = System.nanoTime();
        long cpu = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
        try (FederationFixture fixture = new FederationFixture(output.resolve("execution"), sites)) {
            List<FederationFixture.ByteRow> bytes = new ArrayList<>(fixture.centralizedTransfer());
            FederationFixture.CentralResult central = fixture.centralizedPlan();
            FederationFixture.Result signal = fixture.routeS();
            FederationFixture.Result job = fixture.routeJ();
            if (!records(signal.partials()).equals(records(job.partials()))
                    || !records(signal.merged()).equals(records(job.merged()))) {
                throw new IllegalStateException("signal-cluster and job-state aggregate records differ");
            }
            bytes.addAll(signal.bytes());
            bytes.addAll(job.bytes());
            if (bytes.size() != 3 * sites) throw new IllegalStateException("every route must report every site");
            List<List<String>> byteValues = new ArrayList<>();
            for (FederationFixture.ByteRow row : bytes) {
                if (row.produceErrors() > 0) throw new IllegalStateException("a measured produce acknowledgement failed");
                byteValues.add(List.of(row.route(), row.site(), row.topic(), numeric(row.produceFrameBytes()),
                        numeric(row.produceRecords()), numeric(row.produceErrors()), numeric(row.httpBodyBytes()),
                        numeric(row.httpRecords()), numeric(row.tlsClientBytes()), Double.toString(row.elapsedNanos() / 1_000_000.0)));
            }
            csv(output.resolve("bytes.csv"), BYTE_COLUMNS, byteValues);
            Map<FederatedWindparkData.Window, SeriesStats> centralWindows = windows(central.statistics());
            List<Map<String, String>> rows = new ArrayList<>();
            for (AggregateRecord record : signal.merged()) {
                SeriesStats actual = record.statistics();
                FederatedWindparkData.Window window = window(actual);
                Long gaps = fixture.data().gaps().get(window);
                if (gaps == null) throw new IllegalStateException("a federated window has no central gap reference");
                rows.add(comparison(window, fixture.data().centralized().get(window), gaps,
                        actual, centralWindows.remove(window), fixture.data().uncollapsedCentral().get(window)));
            }
            if (!centralWindows.isEmpty() || rows.size() != 420) throw new IllegalStateException("central and federated windows differ");
            List<String> columns = List.copyOf(rows.get(0).keySet());
            csv(output.resolve("windows.csv"), columns, rows.stream().map(row -> columns.stream().map(row::get).toList()).toList());
            FederatedWindparkData data = fixture.data();
            if (data.episodes().size() != 3600 || data.rawGroups().size() != 100
                    || data.episodes().stream().mapToLong(episode -> Episodes.values(episode).length).sum() != 2_160_000) {
                throw new IllegalStateException("evaluation requires the complete W scenario");
            }
            int orders = orders(sites).size();
            long comparisons = compareOrders(signal, sites);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode run = mapper.createObjectNode().put("schema", "opentsx.federation-evaluation.v1")
                    .put("siteCount", sites).put("correctnessPassed", true).put("routeSAdaptersEqualJ", true)
                    .put("centralizedPlanPassed", true).put("episodes", data.episodes().size()).put("values", 2_160_000)
                    .put("rawSeries", data.rawGroups().size()).put("windows", rows.size()).put("partials", signal.partials().size())
                    .put("comparisons", comparisons).put("mergeOrdersChecked", orders)
                    .put("maxRelativeMeanError", maximum(rows, "mean_relative_error"))
                    .put("maxRelativeM2Error", maximum(rows, "m2_relative_error"))
                    .put("maxCentralRelativeMeanError", maximum(rows, "central_native_mean_relative_error"))
                    .put("maxCentralRelativeM2Error", maximum(rows, "central_native_m2_relative_error"))
                    .put("maxFederatedCentralRelativeMeanError", maximum(rows, "federated_central_mean_relative_error"))
                    .put("maxFederatedCentralRelativeM2Error", maximum(rows, "federated_central_m2_relative_error"))
                    .put("dt2Tolerance", TOLERANCE).put("sourceSpecHash", fixture.specHash())
                    .put("scenarioSha256", ExecutionAssignments.sha256(Files.readString(KafScaleFixture.SCENARIO, StandardCharsets.UTF_8)))
                    .put("referenceOrdering", "scenario generator episode order, reconstructed from broker-received episode ids")
                    .put("stateAbi", "tsx-java/1")
                    .put("centralizedTransferElapsedNanos", bytes.get(0).elapsedNanos())
                    .put("centralizedPlanElapsedNanos", central.elapsedNanos())
                    .put("centralizedPlanCpuNanos", central.cpuNanos())
                    .put("centralizedFirstFetchNanos", central.firstFetchNanos())
                    .put("centralizedFirstStatisticsNanos", central.firstStatisticsNanos())
                    .put("evaluationElapsedNanos", System.nanoTime() - started)
                    .put("evaluationCpuNanos", ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos() - cpu);
            run.putObject("topology").put("siteAgentInstances", sites).put("brokers", sites + 1).put("jvmProcesses", 1)
                    .put("availableProcessors", Runtime.getRuntime().availableProcessors());
            Files.write(output.resolve("run.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run), StandardOpenOption.CREATE_NEW);
            System.out.println("Federation evaluation completed: sites=" + sites + ", windows=" + rows.size() + ", samples=" + bytes.size());
        }
    }

    static List<int[]> orders(int sites) {
        FederatedWindparkData.siteNames(sites);
        if (sites == 3) return List.of(new int[]{0, 1, 2}, new int[]{0, 2, 1}, new int[]{1, 0, 2},
                new int[]{1, 2, 0}, new int[]{2, 0, 1}, new int[]{2, 1, 0});
        int[] ascending = java.util.stream.IntStream.range(0, sites).toArray();
        if (sites == 1) return List.of(ascending);
        return List.of(ascending, java.util.stream.IntStream.range(0, sites).map(index -> sites - index - 1).toArray());
    }

    private static long compareOrders(FederationFixture.Result result, int sites) {
        Map<FederatedWindparkData.Window, List<TsxStatsMerge.Partial>> partials = new TreeMap<>();
        for (AggregateRecord record : result.partials()) partials.computeIfAbsent(window(record.statistics()), ignored -> new ArrayList<>())
                .add(new TsxStatsMerge.Partial(record.header("tsx-site"), record.statistics()));
        org.opentsx.model.OpenTsxAvro avro = new org.opentsx.model.OpenTsxAvro();
        long comparisons = 0;
        for (AggregateRecord record : result.merged()) {
            SeriesStats statistics = record.statistics();
            List<TsxStatsMerge.Partial> values = partials.get(window(statistics));
            if (values == null || values.size() != sites) throw new IllegalStateException("a measured window is missing a site");
            for (int[] order : orders(sites)) {
                SeriesStats merged = TsxStatsMerge.merge(statistics.getSeriesId(), statistics.getWindowType(),
                        Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()), 0.01,
                        Arrays.stream(order).mapToObj(values::get).toList());
                if (!Arrays.equals(record.value(), avro.encode(merged))) throw new IllegalStateException("measured input order changed aggregate bytes");
                comparisons++;
            }
        }
        return comparisons;
    }

    private static Map<String, AggregateRecord> records(List<AggregateRecord> input) {
        Map<String, AggregateRecord> output = new TreeMap<>();
        for (AggregateRecord record : input) if (output.putIfAbsent(record.key() + "|" + record.revision(), record) != null) {
            throw new IllegalStateException("duplicate aggregate identity");
        }
        return output;
    }

    private static Map<FederatedWindparkData.Window, SeriesStats> windows(List<SeriesStats> input) {
        Map<FederatedWindparkData.Window, SeriesStats> output = new TreeMap<>();
        for (SeriesStats statistics : input) if (output.putIfAbsent(window(statistics), statistics) != null) {
            throw new IllegalStateException("duplicate central statistics window");
        }
        return output;
    }

    private static FederatedWindparkData.Window window(SeriesStats statistics) {
        return new FederatedWindparkData.Window(statistics.getSeriesId(), statistics.getWindowType(),
                Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()));
    }

    static Map<String, String> comparison(FederatedWindparkData.Window window, SeriesStats reference, long gaps,
                                          SeriesStats federated, SeriesStats central, byte[] uncollapsed) {
        if (reference == null || federated == null || central == null || uncollapsed == null) {
            throw new IllegalStateException("comparison requires all central and federated windows");
        }
        for (SeriesStats actual : List.of(reference, federated, central)) {
            if (!window.equals(window(actual)) || reference.getValidCount() != actual.getValidCount()
                    || reference.getNanCount() != actual.getNanCount() || Double.compare(reference.getMin(), actual.getMin()) != 0
                    || Double.compare(reference.getMax(), actual.getMax()) != 0 || !reference.getLastSeen().equals(actual.getLastSeen())
                    || !Arrays.equals(uncollapsed, sketch(actual))) {
                throw new IllegalStateException("exact aggregate fields or uncollapsed sketch differ: " + window);
            }
        }
        if (federated.getGapCount() != gaps || central.getGapCount() != gaps) {
            throw new IllegalStateException("aggregate gap count differs: " + window);
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("route", "signal-cluster");
        row.put("group_id", window.groupId()); row.put("window_type", window.windowType());
        row.put("window_start_micros", Long.toString(window.startMicros())); row.put("window_end_micros", Long.toString(window.endMicros()));
        fields(row, "", federated); fields(row, "expected_", reference); fields(row, "central_native_", central);
        row.put("expected_gap_count", Long.toString(gaps));
        row.put("mean_relative_error", Double.toString(relative(reference.getMean(), federated.getMean())));
        row.put("m2_relative_error", Double.toString(relative(reference.getM2(), federated.getM2())));
        row.put("central_native_mean_relative_error", Double.toString(relative(reference.getMean(), central.getMean())));
        row.put("central_native_m2_relative_error", Double.toString(relative(reference.getM2(), central.getM2())));
        row.put("federated_central_mean_relative_error", Double.toString(relative(central.getMean(), federated.getMean())));
        row.put("federated_central_m2_relative_error", Double.toString(relative(central.getM2(), federated.getM2())));
        row.put("sketch_equal", "true"); row.put("exact_fields_equal", "true");
        row.put("central_native_sketch_equal", "true"); row.put("central_native_exact_fields_equal", "true");
        QuantileSketch expected = QuantileSketch.fromBytes(reference.getQuantileSketch());
        QuantileSketch actual = QuantileSketch.fromBytes(federated.getQuantileSketch());
        for (double quantile : new double[]{0.01, 0.5, 0.99}) {
            String name = quantile == 0.01 ? "q01" : quantile == 0.5 ? "q50" : "q99";
            double wanted = expected.quantile(quantile), found = actual.quantile(quantile);
            row.put("expected_" + name, Double.toString(wanted)); row.put(name, Double.toString(found));
            row.put(name + "_absolute_error", Double.toString(Math.abs(found - wanted)));
        }
        return row;
    }

    private static void fields(Map<String, String> row, String prefix, SeriesStats value) {
        row.put(prefix + "valid_count", Long.toString(value.getValidCount())); row.put(prefix + "nan_count", Long.toString(value.getNanCount()));
        row.put(prefix + "gap_count", Long.toString(value.getGapCount())); row.put(prefix + "min", Double.toString(value.getMin()));
        row.put(prefix + "max", Double.toString(value.getMax())); row.put(prefix + "mean", Double.toString(value.getMean()));
        row.put(prefix + "m2", Double.toString(value.getM2()));
    }

    private static byte[] sketch(SeriesStats value) {
        ByteBuffer buffer = value.getQuantileSketch().duplicate();
        byte[] result = new byte[buffer.remaining()]; buffer.get(result); return result;
    }

    static double relative(double expected, double actual) {
        double error = expected == 0 ? Math.abs(actual) : Math.abs((actual - expected) / expected);
        if (!Double.isFinite(error) || error > TOLERANCE) throw new IllegalStateException("D-T2 numerical tolerance was exceeded: " + error);
        return error;
    }

    private static double maximum(List<Map<String, String>> rows, String field) {
        return rows.stream().mapToDouble(row -> Double.parseDouble(row.get(field))).max().orElseThrow();
    }

    private static String numeric(long value) { return value < 0 ? "" : Long.toString(value); }

    private static void csv(Path path, List<String> columns, List<List<String>> rows) throws Exception {
        StringBuilder text = new StringBuilder(String.join(",", columns)).append('\n');
        for (List<String> row : rows) {
            if (row.size() != columns.size()) throw new IllegalStateException("evaluation CSV width changed");
            text.append(String.join(",", row.stream().map(FederationEvaluation::cell).toList())).append('\n');
        }
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String cell(String value) {
        if (value == null) throw new IllegalStateException("evaluation CSV cell is missing");
        return value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
