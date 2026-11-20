package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.federation.AggregateRecord;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class FederatedStatsIntegrationTest {
    @TempDir(factory = FederationLogDirectory.class, cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test void everyActualW3WindowMatchesCentralizedAndAllSixMergeOrders() throws Exception {
        try (var fixture = new FederationFixture(directory)) {
            var result = fixture.routeS();
            assertEquals(3600, fixture.data().episodes().size());
            assertEquals(2_160_000, fixture.data().episodes().stream().mapToLong(org.opentsx.model.v2.Episode::getCount).sum());
            assertEquals(100, fixture.data().rawGroups().size());
            assertEquals(420, result.windows().size());
            assertEquals(3, result.reports().size());
            for (var report : result.reports()) {
                assertEquals(10, report.path("checks").size());
                for (var check : report.path("checks")) assertEquals("pass", check.path("result").asText(), check.path("id").asText());
            }
            Map<FederatedWindparkData.Window, List<TsxStatsMerge.Partial>> partials = new TreeMap<>();
            for (var record : result.partials()) {
                SeriesStats statistics = record.statistics();
                var window = new FederatedWindparkData.Window(statistics.getSeriesId(), statistics.getWindowType(),
                        Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()));
                partials.computeIfAbsent(window, ignored -> new ArrayList<>()).add(new TsxStatsMerge.Partial(record.header("tsx-site"), statistics));
            }
            int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
            Map<FederatedWindparkData.Window, byte[]> published = new TreeMap<>();
            for (var record : result.merged()) {
                SeriesStats statistics = record.statistics();
                assertNull(published.put(new FederatedWindparkData.Window(statistics.getSeriesId(), statistics.getWindowType(),
                        Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd())), record.value()));
            }
            int compared = 0;
            var avro = new OpenTsxAvro();
            for (var entry : partials.entrySet()) {
                var window = entry.getKey();
                assertEquals(3, entry.getValue().size());
                byte[] baseline = null;
                for (int[] order : orders) {
                    byte[] merged = avro.encode(TsxStatsMerge.merge(window.groupId(), window.windowType(), window.startMicros(),
                            window.endMicros(), 0.01, Arrays.stream(order).mapToObj(entry.getValue()::get).toList()));
                    assertArrayEquals(published.get(window), merged, window.toString());
                    if (baseline == null) baseline = merged;
                    else assertArrayEquals(baseline, merged, window.toString());
                    compared++;
                }
            }
            assertEquals(2520, compared);
            List<AggregateRecord> sorted = result.merged().stream().sorted(Comparator.comparing(AggregateRecord::key)).toList();
            List<SeriesStats> driver = result.driverOutput().stream().sorted(Comparator.comparing(s ->
                    "w3|" + s.getSeriesId() + "|" + s.getWindowType() + "|" + Micros.of(s.getWindowStart()))).toList();
            for (int index = 0; index < sorted.size(); index++) assertArrayEquals(sorted.get(index).value(), avro.encode(driver.get(index)));
        }
    }

    @Test void everyActualMixedW3WindowMatchesCentralizedAndAllSixMergeOrders() throws Exception {
        Path output = Path.of("target", "rust-b4-mixed");
        for (String file : List.of("result.json", "execution.json", "windows.csv", "partials.csv")) Files.deleteIfExists(output.resolve(file));
        try (var fixture = FederationFixture.mixed(directory)) {
            var result = fixture.routeS();
            Map<String, JsonNode> reports = new TreeMap<>();
            result.reports().forEach(report -> assertNull(reports.put(report.path("site").asText(), report)));
            assertEquals("B", reports.get("s3").path("track").asText());
            assertEquals("A", reports.get("s1").path("track").asText());
            assertEquals("A", reports.get("s2").path("track").asText());
            assertEquals(3, reports.size());
            int conformanceChecks = 0;
            for (var report : reports.values()) {
                assertEquals(10, report.path("checks").size());
                for (var check : report.path("checks")) {
                    assertEquals("pass", check.path("result").asText(), check.path("id").asText());
                    conformanceChecks++;
                }
            }
            assertEquals(3600, fixture.data().episodes().size());
            long sampleCount = fixture.data().episodes().stream().mapToLong(org.opentsx.model.v2.Episode::getCount).sum();
            assertEquals(2_160_000, sampleCount);
            assertEquals(100, fixture.data().rawGroups().size());
            assertEquals(1260, result.partials().size());
            assertEquals(420, result.merged().size());
            assertEquals(420, result.windows().size());
            assertEquals(420, result.driverOutput().size());

            Map<FederatedWindparkData.Window, List<TsxStatsMerge.Partial>> partials = new TreeMap<>();
            List<Map<String, String>> partialRows = new ArrayList<>();
            for (var record : result.partials()) {
                SeriesStats statistics = record.statistics();
                var window = window(statistics);
                String site = record.header("tsx-site");
                partials.computeIfAbsent(window, ignored -> new ArrayList<>()).add(new TsxStatsMerge.Partial(site, statistics));
                SeriesStats reference = fixture.data().partials().get(window).stream().filter(p -> p.site().equals(site))
                        .findFirst().orElseThrow().statistics();
                assertEquals(reference.getValidCount(), statistics.getValidCount(), record.key());
                assertEquals(reference.getNanCount(), statistics.getNanCount(), record.key());
                assertEquals(reference.getGapCount(), statistics.getGapCount(), record.key());
                assertEquals(reference.getMin(), statistics.getMin(), record.key());
                assertEquals(reference.getMax(), statistics.getMax(), record.key());
                assertEquals(reference.getLastSeen(), statistics.getLastSeen(), record.key());
                Map<String, String> row = new LinkedHashMap<>();
                row.put("key", record.key()); row.put("site", site); row.put("track", reports.get(site).path("track").asText());
                row.put("group_id", window.groupId()); row.put("window_type", window.windowType());
                row.put("window_start_micros", Long.toString(window.startMicros())); row.put("window_end_micros", Long.toString(window.endMicros()));
                row.put("revision", Long.toString(record.revision())); row.put("generation", record.header("tsx-generation"));
                row.put("assignment", record.header("tsx-assignment")); row.put("fence", record.header("tsx-fence"));
                row.put("spec_hash", record.header("tsx-spec-hash")); row.put("fragment_hash", record.header("tsx-fragment-hash"));
                row.put("payload_sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(record.value())));
                row.put("valid_count", Long.toString(statistics.getValidCount())); row.put("nan_count", Long.toString(statistics.getNanCount()));
                row.put("gap_count", Long.toString(statistics.getGapCount())); row.put("min", Double.toString(statistics.getMin()));
                row.put("max", Double.toString(statistics.getMax())); row.put("mean", Double.toString(statistics.getMean()));
                row.put("m2", Double.toString(statistics.getM2())); row.put("last_seen_micros", Long.toString(Micros.of(statistics.getLastSeen())));
                row.put("expected_mean", Double.toString(reference.getMean())); row.put("expected_m2", Double.toString(reference.getM2()));
                row.put("mean_relative_error", Double.toString(relative(reference.getMean(), statistics.getMean())));
                row.put("m2_relative_error", Double.toString(relative(reference.getM2(), statistics.getM2())));
                row.put("sketch_hex", HexFormat.of().formatHex(bytes(statistics.getQuantileSketch())));
                row.put("expected_sketch_hex", HexFormat.of().formatHex(fixture.data().uncollapsedSites().get(site).get(window)));
                shape(row, "actual_", statistics.getQuantileSketch());
                shape(row, "expected_", ByteBuffer.wrap(fixture.data().uncollapsedSites().get(site).get(window)));
                partialRows.add(row);
            }
            assertEquals(420, partials.size());
            Map<FederatedWindparkData.Window, byte[]> published = new TreeMap<>();
            for (var record : result.merged()) {
                assertEquals("true", record.header("tsx-final"));
                assertEquals("", record.header("tsx-missing-sites"));
                assertEquals(0, record.revision());
                assertNull(published.put(window(record.statistics()), record.value()));
            }
            int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
            int compared = 0;
            var avro = new OpenTsxAvro();
            for (var entry : partials.entrySet()) {
                assertEquals(3, entry.getValue().size());
                for (int[] order : orders) {
                    var window = entry.getKey();
                    byte[] merged = avro.encode(TsxStatsMerge.merge(window.groupId(), window.windowType(), window.startMicros(),
                            window.endMicros(), 0.01, Arrays.stream(order).mapToObj(entry.getValue()::get).toList()));
                    assertArrayEquals(published.get(window), merged, window.toString());
                    compared++;
                }
            }
            assertEquals(2520, compared);
            Map<FederatedWindparkData.Window, byte[]> driver = new TreeMap<>();
            result.driverOutput().forEach(statistics -> assertNull(driver.put(window(statistics), avro.encode(statistics))));
            published.forEach((window, value) -> assertArrayEquals(value, driver.get(window), window.toString()));

            Files.createDirectories(output);
            ObjectMapper json = new ObjectMapper();
            JsonNode execution = json.readTree(output.resolve("execution.json").toFile());
            assertEquals("opentsx.rust-b4-mixed-execution.v1", execution.path("schema").asText());
            for (String site : List.of("s1", "s2", "s3")) {
                JsonNode actual = execution.path("sites").path(site);
                JsonNode statuses = actual.path("statuses");
                assertFalse(statuses.isEmpty());
                assertTrue(java.util.stream.StreamSupport.stream(statuses.spliterator(), false)
                        .anyMatch(status -> status.path("state").asText().equals("running")));
                JsonNode terminal = statuses.get(statuses.size() - 1);
                assertEquals("stopped", terminal.path("state").asText());
                assertEquals(reports.get(site).path("track"), terminal.path("track"));
                assertEquals(fixture.data().sites().get(site).size(), terminal.path("records").asLong());
                assertEquals(actual.path("inputRecords").asLong(), terminal.path("records").asLong());
                assertEquals(reports.get(site).path("artifact").path("digest"), terminal.path("artifact").path("digest"));
                assertEquals(reports.get(site).path("artifact").path("version"), terminal.path("artifact").path("version"));
                assertEquals(reports.get(site).path("artifact").path("stateAbi"), actual.path("stateAbi"));
                assertEquals(actual.path("digest"), terminal.path("artifact").path("digest"));
                assertTrue(actual.path("commandHash").asText().matches("[0-9a-f]{64}"));
            }
            assertEquals(execution.path("rustOutcomes").path("run"), execution.path("rustOutcomes").path("replay"));
            assertEquals("completed", execution.path("rustOutcomes").path("run").path("state").asText());
            assertTrue(execution.path("rustOutcomes").path("run").path("completed").asBoolean());
            assertEquals(420, execution.path("rustOutcomes").path("run").path("forwardedRecords").asInt());
            List<Map<String, String>> windows = result.windows().stream().map(FederationFixture.WindowRow::columns).toList();
            writeCsv(output.resolve("windows.csv"), windows);
            writeCsv(output.resolve("partials.csv"), partialRows);
            ObjectNode evidence = json.createObjectNode().put("schema", "opentsx.rust-b4-mixed-acceptance.v1")
                    .put("sourceCommit", System.getProperty("opentsx.sourceCommit"))
                    .put("route", result.route()).put("episodes", fixture.data().episodes().size()).put("samples", sampleCount)
                    .put("series", fixture.data().rawGroups().size()).put("partialCount", result.partials().size())
                    .put("finalWindowCount", result.merged().size()).put("uniqueFinalWindowCount", published.size())
                    .put("driverWindowCount", driver.size()).put("mergeOrders", orders.length).put("mergeByteComparisons", compared)
                    .put("conformanceChecks", conformanceChecks).put("meanM2RelativeTolerance", 3e-12).put("alpha", 0.01)
                    .put("elapsedNanos", result.elapsedNanos())
                    .put("maxMeanRelativeError", maximum(windows, "mean_relative_error"))
                    .put("maxM2RelativeError", maximum(windows, "m2_relative_error"))
                    .put("maxPartialMeanRelativeError", maximum(partialRows, "mean_relative_error"))
                    .put("maxPartialM2RelativeError", maximum(partialRows, "m2_relative_error"))
                    .put("centralSketchByteDifferences", windows.stream().filter(row -> row.get("sketch_equal").equals("false")).count());
            evidence.set("execution", execution);
            evidence.set("conformance", json.valueToTree(reports));
            evidence.set("traffic", json.valueToTree(result.bytes()));
            System.out.println("Mixed W3 moments: mean=" + evidence.path("maxMeanRelativeError") + ", m2=" + evidence.path("maxM2RelativeError"));
            for (var rows : List.of(windows, partialRows)) {
                for (var row : rows) {
                    assertTrue(Double.parseDouble(row.get("mean_relative_error")) <= 3e-12, row.toString());
                    assertTrue(Double.parseDouble(row.get("m2_relative_error")) <= 3e-12, row.toString());
                    for (String prefix : List.of("actual_", "expected_")) {
                        assertTrue(Integer.parseInt(row.get(prefix + "positive_bins")) < 4096, row.toString());
                        assertTrue(Integer.parseInt(row.get(prefix + "negative_bins")) < 4096, row.toString());
                    }
                }
            }
            for (var row : windows) {
                for (String q : List.of("q01", "q50", "q99")) {
                    assertEquals("true", row.get(q + "_actual_within_alpha"), row.toString());
                    assertEquals("true", row.get(q + "_expected_within_alpha"), row.toString());
                }
            }
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), evidence);
        }
    }

    private static FederatedWindparkData.Window window(SeriesStats statistics) {
        return new FederatedWindparkData.Window(statistics.getSeriesId(), statistics.getWindowType(),
                Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    private static void shape(Map<String, String> row, String prefix, ByteBuffer bytes) {
        var stores = MixedSketchQualificationTest.inspect(bytes);
        row.put(prefix + "zero_count", Long.toString(stores.zeros()));
        for (String sign : List.of("positive", "negative")) {
            Map<Integer, Long> bins = sign.equals("positive") ? stores.positive() : stores.negative();
            row.put(prefix + sign + "_bins", Integer.toString(bins.size()));
            long span = bins.isEmpty() ? 0 : (long) java.util.Collections.max(bins.keySet()) - java.util.Collections.min(bins.keySet()) + 1;
            row.put(prefix + sign + "_span", Long.toString(span));
        }
    }

    private static double relative(double expected, double actual) {
        return expected == 0 ? Math.abs(actual) : Math.abs((actual - expected) / expected);
    }

    private static double maximum(List<Map<String, String>> rows, String name) {
        return rows.stream().mapToDouble(row -> Double.parseDouble(row.get(name))).max().orElseThrow();
    }

    private static void writeCsv(Path path, List<Map<String, String>> rows) throws Exception {
        assertFalse(rows.isEmpty());
        List<String> fields = new ArrayList<>(rows.get(0).keySet());
        StringBuilder csv = new StringBuilder(String.join(",", fields)).append('\n');
        for (var row : rows) {
            assertEquals(fields, new ArrayList<>(row.keySet()));
            csv.append(fields.stream().map(name -> "\"" + row.get(name).replace("\"", "\"\"") + "\"")
                    .collect(java.util.stream.Collectors.joining(","))).append('\n');
        }
        Files.writeString(path, csv);
    }
}
