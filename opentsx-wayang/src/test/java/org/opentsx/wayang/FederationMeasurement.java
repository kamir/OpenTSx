package org.opentsx.wayang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.federation.AggregateRecord;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FederationMeasurement {
    private static final List<String> BYTE_COLUMNS = List.of("route", "site", "topic", "produce_frame_bytes", "produce_records",
            "produce_errors", "http_body_bytes", "http_records", "tls_client_bytes", "elapsed_ms");

    private FederationMeasurement() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("usage: FederationMeasurement <output-directory>");
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(output)) throw new IllegalArgumentException("measurement output directory must exist");
        for (String file : List.of("bytes.csv", "windows.csv", "run.json")) {
            if (Files.exists(output.resolve(file))) throw new IllegalArgumentException("measurement output already exists: " + file);
        }
        try (FederationFixture fixture = new FederationFixture(output.resolve("execution"))) {
            List<FederationFixture.ByteRow> bytes = new ArrayList<>(fixture.centralizedTransfer());
            FederationFixture.Result signal = fixture.routeS();
            FederationFixture.Result job = fixture.routeJ();
            if (!records(signal.partials()).equals(records(job.partials()))
                    || !records(signal.merged()).equals(records(job.merged()))) {
                throw new IllegalStateException("signal-cluster and job-state aggregate bytes differ");
            }
            long comparisons = compareOrders(signal);
            bytes.addAll(signal.bytes());
            bytes.addAll(job.bytes());
            if (bytes.size() != 9) throw new IllegalStateException("three routes must report all three sites");
            List<List<String>> byteValues = new ArrayList<>();
            for (FederationFixture.ByteRow row : bytes) {
                if (row.produceErrors() > 0) throw new IllegalStateException("a measured produce acknowledgement failed");
                byteValues.add(List.of(row.route(), row.site(), row.topic(), numeric(row.produceFrameBytes()),
                        numeric(row.produceRecords()), numeric(row.produceErrors()), numeric(row.httpBodyBytes()),
                        numeric(row.httpRecords()), numeric(row.tlsClientBytes()), Double.toString(row.elapsedNanos() / 1000000.0)));
            }
            csv(output.resolve("bytes.csv"), BYTE_COLUMNS, byteValues);
            if (signal.windows().isEmpty()) throw new IllegalStateException("window comparison evidence is missing");
            List<String> columns = new ArrayList<>(signal.windows().get(0).columns().keySet());
            List<List<String>> windows = new ArrayList<>();
            double maximumMean = 0;
            double maximumM2 = 0;
            for (FederationFixture.WindowRow row : signal.windows()) {
                if (!new ArrayList<>(row.columns().keySet()).equals(columns)) throw new IllegalStateException("window evidence columns changed");
                windows.add(columns.stream().map(row.columns()::get).toList());
                maximumMean = Math.max(maximumMean, Double.parseDouble(row.columns().get("mean_relative_error")));
                maximumM2 = Math.max(maximumM2, Double.parseDouble(row.columns().get("m2_relative_error")));
            }
            if (!(maximumMean <= 3e-12 && maximumM2 <= 3e-12)) throw new IllegalStateException("D-T2 numerical tolerance was exceeded");
            csv(output.resolve("windows.csv"), columns, windows);
            FederatedWindparkData data = fixture.data();
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode run = mapper.createObjectNode().put("schema", "opentsx.federation-measurement.v1")
                    .put("correctnessPassed", true).put("routeSAdaptersEqualJ", true)
                    .put("episodes", data.episodes().size())
                    .put("values", data.episodes().stream().mapToLong(episode -> Episodes.values(episode).length).sum())
                    .put("rawSeries", data.rawGroups().size()).put("windows", signal.windows().size())
                    .put("partials", signal.partials().size()).put("comparisons", comparisons)
                    .put("maxRelativeMeanError", maximumMean).put("maxRelativeM2Error", maximumM2).put("dt2Tolerance", 3e-12)
                    .put("sourceSpecHash", fixture.specHash())
                    .put("scenarioSha256", ExecutionAssignments.sha256(Files.readString(KafScaleFixture.SCENARIO, StandardCharsets.UTF_8)))
                    .put("referenceOrdering", "scenario generator episode order, reconstructed from broker-received episode ids")
                    .put("stateAbi", "tsx-java/1");
            run.putObject("topology").put("siteAgentInstances", 3).put("brokers", 4).put("jvmProcesses", 1);
            Files.write(output.resolve("run.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run), StandardOpenOption.CREATE_NEW);
            System.out.println("Federation measurement completed: " + signal.windows().size() + " windows, " + bytes.size() + " site/route samples");
        }
    }

    private static Map<String, AggregateRecord> records(List<AggregateRecord> records) {
        Map<String, AggregateRecord> output = new TreeMap<>();
        for (AggregateRecord record : records) {
            String identity = record.key() + "|" + record.revision();
            if (output.putIfAbsent(identity, record) != null) throw new IllegalStateException("duplicate measured aggregate identity");
        }
        return output;
    }

    private static String numeric(long value) {
        return value < 0 ? "" : Long.toString(value);
    }

    private static long compareOrders(FederationFixture.Result result) {
        Map<String, List<TsxStatsMerge.Partial>> partials = new TreeMap<>();
        for (AggregateRecord record : result.partials()) {
            SeriesStats statistics = record.statistics();
            String window = statistics.getSeriesId() + "|" + statistics.getWindowType() + "|" + Micros.of(statistics.getWindowStart());
            partials.computeIfAbsent(window, ignored -> new ArrayList<>())
                    .add(new TsxStatsMerge.Partial(record.header("tsx-site"), statistics));
        }
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        OpenTsxAvro avro = new OpenTsxAvro();
        long comparisons = 0;
        for (AggregateRecord record : result.merged()) {
            SeriesStats statistics = record.statistics();
            String window = statistics.getSeriesId() + "|" + statistics.getWindowType() + "|" + Micros.of(statistics.getWindowStart());
            List<TsxStatsMerge.Partial> sites = partials.get(window);
            if (sites == null || sites.size() != 3) throw new IllegalStateException("measured window does not contain all three sites");
            for (int[] order : orders) {
                SeriesStats merged = TsxStatsMerge.merge(statistics.getSeriesId(), statistics.getWindowType(),
                        Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()), 0.01,
                        List.of(sites.get(order[0]), sites.get(order[1]), sites.get(order[2])));
                if (!java.util.Arrays.equals(record.value(), avro.encode(merged))) {
                    throw new IllegalStateException("measured site order changed aggregate bytes");
                }
                comparisons++;
            }
        }
        return comparisons;
    }

    private static void csv(Path path, List<String> columns, List<List<String>> rows) throws Exception {
        StringBuilder text = new StringBuilder(String.join(",", columns)).append('\n');
        for (List<String> row : rows) {
            if (row.size() != columns.size()) throw new IllegalStateException("measurement CSV width changed");
            text.append(String.join(",", row.stream().map(FederationMeasurement::cell).toList())).append('\n');
        }
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String cell(String value) {
        if (value == null) throw new IllegalStateException("measurement cell is missing");
        return value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
