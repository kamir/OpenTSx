package org.opentsx.windpark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.iceberg.expressions.Expressions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.Summaries;
import org.opentsx.model.Ulid;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.iceberg.IcebergSeriesStore;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindparkDemoTest {

    private static final long START = 1_790_812_800_000_000L;
    private static final long FAULT_FROM = START + 600_000_000L;
    private static final long FAULT_TO = START + 1_200_000_000L;
    private final ObjectMapper json = new ObjectMapper();
    private Method entrypoint;

    @TempDir
    Path dir;

    @BeforeEach
    void findEntrypoint() throws Exception {
        entrypoint = Class.forName("org.opentsx.windpark.WindparkDemo").getMethod("main", String[].class);
    }

    @Test
    void simulationDetectsTheInjectedFaultAndCurtailmentAgainstIndependentEnergyValues() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        JsonNode report = json.readTree(output.resolve("report.json").toFile());
        assertEquals(1, report.path("schemaVersion").asInt());
        assertEquals("windpark-replay", report.path("scenario").asText());
        assertEquals(31, report.path("seed").asLong());
        assertEquals(START, report.path("fromMicros").asLong());
        assertEquals(START + 1_800_000_000L, report.path("toMicros").asLong());
        assertEquals(90, report.path("episodes").asInt());
        assertEquals(900, report.path("values").asInt());
        JsonNode inventory = report.path("episodeSha256");
        assertTrue(inventory.isObject());
        assertEquals(90, inventory.size());
        inventory.fields().forEachRemaining(entry -> {
            assertTrue(entry.getKey().matches("[0-9A-HJKMNP-TV-Z]{26}"), entry.getKey());
            assertTrue(entry.getValue().asText().matches("[0-9a-f]{64}"), entry.getValue().asText());
        });
        assertEquals(2, report.path("groundTruth").size());
        Set<String> types = new HashSet<>();
        for (JsonNode truth : report.path("groundTruth")) {
            types.add(truth.path("type").asText());
            assertEquals("operation", truth.path("category").asText());
            assertEquals(FAULT_FROM, truth.path("fromMicros").asLong());
            assertEquals(FAULT_TO, truth.path("toMicros").asLong());
            assertEquals(truth.path("type").asText().equals("FAULT") ? "T02" : "T03",
                    truth.path("turbine").asText());
        }
        assertEquals(Set.of("FAULT", "CURTAILMENT"), types);
        JsonNode analysis = report.path("analysis");
        assertEquals(3, analysis.size());
        assertAnalysis(analysis.path("T01"), 1, 1500, 0, 0);
        assertAnalysis(analysis.path("T02"), 2.0 / 3, 1000, 0, 500);
        assertAnalysis(analysis.path("T03"), 5.0 / 6, 1250, 0.5, 250);
    }

    @Test
    void separateRunsProduceIdenticalReportsIncludingEpisodePayloadHashes() throws Exception {
        Path first = dir.resolve("first");
        Path second = dir.resolve("second");
        invoke("run", "--output", first.toString());
        invoke("run", "--output", second.toString());
        assertArrayEquals(Files.readAllBytes(first.resolve("report.json")),
                Files.readAllBytes(second.resolve("report.json")));
    }

    @Test
    void replayReopensThePersistedEpisodesAndProducesTheOriginalReportBytes() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        byte[] original = Files.readAllBytes(output.resolve("report.json"));
        assertFalse(Files.exists(output.resolve("replay.json")));
        invoke("replay", "--output", output.toString());
        assertArrayEquals(original, Files.readAllBytes(output.resolve("replay.json")));
        assertArrayEquals(original, Files.readAllBytes(output.resolve("report.json")));
        invoke("replay", "--output", output.toString());
        assertArrayEquals(original, Files.readAllBytes(output.resolve("replay.json")));
        assertArrayEquals(original, Files.readAllBytes(output.resolve("report.json")));
    }

    @Test
    void replayRejectsDeletedPersistedEpisodesWithoutOverwritingAnExistingReplay() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        invoke("replay", "--output", output.toString());
        byte[] replay = Files.readAllBytes(output.resolve("replay.json"));
        try (IcebergSeriesStore store = IcebergSeriesStore.open(storeProperties(output), "windpark_demo")) {
            try (var files = store.episodesTable().newScan().planFiles(); var iterator = files.iterator()) {
                assertTrue(iterator.hasNext());
                store.episodesTable().newDelete().deleteFile(iterator.next().file()).commit();
            }
            assertTrue(store.read(EpisodeQuery.of(SeriesSelector.all(), START, START + 1_800_000_000L)).size() < 90);
        }
        IOException error = assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertEquals("replay does not match report.json", error.getMessage());
        assertArrayEquals(replay, Files.readAllBytes(output.resolve("replay.json")));
    }

    @Test
    void replayRejectsAnExtraTemperatureEpisodeEvenWhenAllAnalysisInputsAreUnchanged() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        byte[] report = Files.readAllBytes(output.resolve("report.json"));
        EpisodeQuery windQuery = EpisodeQuery.of(SeriesSelector.metric("wind.met.wind_speed"),
                START, START + 1_800_000_000L);
        EpisodeQuery powerQuery = EpisodeQuery.of(SeriesSelector.metric("wind.turbine.power_active"),
                START, START + 1_800_000_000L);
        try (IcebergSeriesStore store = IcebergSeriesStore.open(storeProperties(output), "windpark_demo")) {
            List<Episode> wind = store.read(windQuery);
            List<Episode> power = store.read(powerQuery);
            Episode original = store.read(EpisodeQuery.of(SeriesSelector.metric("wind.generator.temperature"),
                    START, START + 1_800_000_000L)).get(0);
            double[] values = Episodes.values(original);
            values[0] += 1;
            Episode extra = Episode.newBuilder(original)
                    .setEpisodeId(Ulid.deterministic(START / 1000, 99))
                    .setValues(Arrays.stream(values).boxed().toList())
                    .setSummary(Summaries.of(values)).build();
            Episodes.validate(extra);
            assertFalse(json.readTree(report).path("episodeSha256").has(extra.getEpisodeId()));
            assertEquals(1, store.write(List.of(extra)).written());
            assertEquals(wind, store.read(windQuery));
            assertEquals(power, store.read(powerQuery));
        }
        IOException error = assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertEquals("replay does not match report.json", error.getMessage());
        assertArrayEquals(report, Files.readAllBytes(output.resolve("report.json")));
        assertFalse(Files.exists(output.resolve("replay.json")));
    }

    @Test
    void replayRejectsChangedTemperaturePayloadWithTheSameEpisodeInventoryAndAnalysisInputs() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        byte[] report = Files.readAllBytes(output.resolve("report.json"));
        EpisodeQuery query = EpisodeQuery.of(SeriesSelector.all(), START, START + 1_800_000_000L);
        EpisodeQuery windQuery = EpisodeQuery.of(SeriesSelector.metric("wind.met.wind_speed"),
                START, START + 1_800_000_000L);
        EpisodeQuery powerQuery = EpisodeQuery.of(SeriesSelector.metric("wind.turbine.power_active"),
                START, START + 1_800_000_000L);
        try (IcebergSeriesStore store = IcebergSeriesStore.open(storeProperties(output), "windpark_demo")) {
            List<Episode> original = store.read(query);
            List<Episode> wind = store.read(windQuery);
            List<Episode> power = store.read(powerQuery);
            Episode temperature = original.stream()
                    .filter(e -> e.getSeries().getMetric().equals("wind.generator.temperature"))
                    .findFirst().orElseThrow();
            double[] values = Episodes.values(temperature);
            values[0] += 1;
            Episode changed = Episode.newBuilder(temperature)
                    .setValues(Arrays.stream(values).boxed().toList())
                    .setSummary(Summaries.of(values)).build();
            Episodes.validate(changed);
            List<Episode> replaced = new ArrayList<>(original);
            replaced.set(original.indexOf(temperature), changed);
            store.episodesTable().newDelete().deleteFromRowFilter(Expressions.alwaysTrue()).commit();
            assertEquals(90, store.write(replaced).written());
            List<Episode> persisted = store.read(query);
            assertEquals(90, persisted.size());
            assertEquals(900, persisted.stream().mapToInt(Episode::getCount).sum());
            assertEquals(original.stream().map(Episode::getEpisodeId).toList(),
                    persisted.stream().map(Episode::getEpisodeId).toList());
            assertEquals(values[0], Episodes.values(store.episode(temperature.getEpisodeId()).orElseThrow())[0], 1e-12);
            assertEquals(wind, store.read(windQuery));
            assertEquals(power, store.read(powerQuery));
        }
        IOException error = assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertEquals("replay does not match report.json", error.getMessage());
        assertArrayEquals(report, Files.readAllBytes(output.resolve("report.json")));
        assertFalse(Files.exists(output.resolve("replay.json")));
    }

    @Test
    void replayRejectsAnAlteredReportWithoutPublishingOrOverwritingAReplay() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        ObjectNode report = (ObjectNode) json.readTree(output.resolve("report.json").toFile());
        report.put("values", 901);
        json.writeValue(output.resolve("report.json").toFile(), report);
        assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertFalse(Files.exists(output.resolve("replay.json")));
        Files.writeString(output.resolve("replay.json"), "existing replay\n");
        assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertEquals("existing replay\n", Files.readString(output.resolve("replay.json")));
    }

    @Test
    void replayRejectsMissingOrMalformedStateWithoutCreatingAReplacementDatabase() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        Path report = output.resolve("report.json");
        byte[] original = Files.readAllBytes(report);
        Files.delete(report);
        assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertFalse(Files.exists(report));
        Files.writeString(report, "{");
        assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        Files.write(report, original);
        Files.delete(output.resolve("catalog.db"));
        assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertFalse(Files.exists(output.resolve("catalog.db")));
        assertFalse(Files.exists(output.resolve("replay.json")));
        Path missing = dir.resolve("missing");
        assertThrows(IOException.class, () -> invoke("replay", "--output", missing.toString()));
        assertFalse(Files.exists(missing));
    }

    @Test
    void replayRejectsMissingWarehouseAndInvalidCatalogWithoutRecreatingState() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        Path warehouse = output.resolve("warehouse");
        Path saved = output.resolve("saved-warehouse");
        Files.move(warehouse, saved);
        assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertFalse(Files.exists(warehouse));
        Files.move(saved, warehouse);
        Path catalog = output.resolve("catalog.db");
        byte[] invalid = "invalid existing catalog\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(catalog, invalid);
        assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertArrayEquals(invalid, Files.readAllBytes(catalog));
        assertFalse(Files.exists(output.resolve("replay.json")));
    }

    @Test
    void replayRejectsCorruptReceiptsAndSymlinksWithoutOverwritingThem() throws Exception {
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        Path receipt = output.resolve("replay.json");
        Files.writeString(receipt, "existing corrupt receipt\n");
        IOException corrupt = assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertEquals("existing replay.json does not match verified replay", corrupt.getMessage());
        assertEquals("existing corrupt receipt\n", Files.readString(receipt));
        Files.delete(receipt);
        Path protectedFile = dir.resolve("protected.json");
        Files.writeString(protectedFile, "protected file\n");
        Files.createSymbolicLink(receipt, protectedFile);
        IOException symlink = assertThrows(IOException.class, () -> invoke("replay", "--output", output.toString()));
        assertEquals("existing replay.json does not match verified replay", symlink.getMessage());
        assertTrue(Files.isSymbolicLink(receipt));
        assertEquals("protected file\n", Files.readString(protectedFile));
    }

    @Test
    void runRejectsExistingDirectoriesAndPreservesTheirContents() throws Exception {
        Path empty = Files.createDirectory(dir.resolve("empty"));
        assertThrows(IllegalArgumentException.class, () -> invoke("run", "--output", empty.toString()));
        Path output = dir.resolve("run");
        invoke("run", "--output", output.toString());
        byte[] report = Files.readAllBytes(output.resolve("report.json"));
        Files.writeString(output.resolve("keep.txt"), "existing file\n");
        assertThrows(IllegalArgumentException.class, () -> invoke("run", "--output", output.toString()));
        assertArrayEquals(report, Files.readAllBytes(output.resolve("report.json")));
        assertEquals("existing file\n", Files.readString(output.resolve("keep.txt")));
    }

    @Test
    void cliRejectsUnknownVerbsOptionsAndIncompleteArgumentsBeforeCreatingOutput() throws Exception {
        Path output = dir.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> invoke());
        assertThrows(IllegalArgumentException.class, () -> invoke("run"));
        assertThrows(IllegalArgumentException.class, () -> invoke("run", "--output"));
        assertThrows(IllegalArgumentException.class, () -> invoke("unknown", "--output", output.toString()));
        assertThrows(IllegalArgumentException.class, () -> invoke("run", "--unknown", output.toString()));
        assertThrows(IllegalArgumentException.class, () -> invoke("run", "--output", ""));
        assertThrows(IllegalArgumentException.class, () -> invoke("run", "--output", output.toString(), "extra"));
        assertFalse(Files.exists(output));
    }

    private void assertAnalysis(JsonNode analysis, double pi, double produced, double ratio, double lost) {
        assertEquals(3, analysis.path("intervals").asInt());
        assertEquals(pi, analysis.path("performanceIndex").asDouble(), 1e-12);
        assertEquals(produced, analysis.path("producedKwh").asDouble(), 1e-9);
        assertEquals(1500, analysis.path("expectedKwh").asDouble(), 1e-9);
        JsonNode periods = analysis.path("underperformance");
        assertEquals(lost == 0 ? 0 : 1, periods.size());
        if (lost != 0) {
            JsonNode period = periods.get(0);
            assertEquals(FAULT_FROM, period.path("fromMicros").asLong());
            assertEquals(FAULT_TO, period.path("toMicros").asLong());
            assertEquals(ratio, period.path("meanRatio").asDouble(), 1e-12);
            assertEquals(lost, period.path("lostEnergyKwh").asDouble(), 1e-9);
        }
    }

    private Map<String, String> storeProperties(Path output) {
        return Map.of("catalog-impl", "org.apache.iceberg.jdbc.JdbcCatalog",
                "uri", "jdbc:sqlite:" + output.resolve("catalog.db").toAbsolutePath(),
                "jdbc.schema-version", "V1", "warehouse", output.resolve("warehouse").toUri().toString());
    }

    private void invoke(String... args) throws Exception {
        try {
            entrypoint.invoke(null, (Object) args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        }
    }
}
