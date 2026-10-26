package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.windpark.catalog.ChannelCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindparkMetadataPublisherTest {

    private static final Instant START = SimulatedPark.START;
    private static final ChannelCatalog CATALOG = SimulatedPark.CATALOG;
    private static SimulatedPark sim;
    private static List<SeriesStats> stats;

    @BeforeAll
    static void simulateAndComputeStatistics() {
        sim = SimulatedPark.simulate();
        stats = sim.stats;
    }

    private static WindparkMetadataPublisher publisher(OmApi api) {
        return sim.publisher(api);
    }

    @Test
    void everythingValidatesAgainstTheOpenMetadataSchemasAndReferencesResolve() throws Exception {
        try (FakeOpenMetadata om = new FakeOpenMetadata()) {
            WindparkMetadataPublisher p = publisher(new HttpOmApi(om.uri(), "test-token"));
            p.publishAll();
            int profiles = p.publishProfiles(stats, "PT1H");
            int results = p.publishDataQuality(stats, "PT10M", WindparkExpectations.of(CATALOG));
            assertEquals(List.of(), om.violations);

            // vocabulary
            long terms = om.byFqn.values().stream().filter(e -> e.path("entityType").asText().equals("glossaryTerm")).count();
            assertEquals(CATALOG.channels().size(), terms);
            JsonNode windSpeed = om.byFqn.get("opentsx-windpark-datapoints.WMET_HorWdSpd");
            assertTrue(windSpeed.path("description").asText().contains("WindSpeed_Avg"), "source mapping documented");
            assertTrue(windSpeed.path("description").asText().contains("Europe/Berlin"));
            assertTrue(om.byFqn.containsKey("IEC61400-25.WROT"));
            // assets
            assertTrue(om.byFqn.containsKey("kafscale.\"tsx.wind.episodes.v2\""));
            JsonNode view = om.byFqn.get(p.signalsTableFqn());
            assertEquals("opentsx-iceberg.iceberg.tsx.windpark_ostsee-1_signals", p.signalsTableFqn());
            assertEquals(CATALOG.channels().size() + 2, view.path("columns").size());
            assertEquals(5, om.lineage.size());
            assertEquals(Map.of("table", 4, "topic", 4).keySet(), om.customProperties.keySet());

            // profiles: 3 hourly windows, wind speed over 3 turbines x 3600 values
            assertEquals(3, profiles);
            List<JsonNode> prof = om.profiles.get(p.signalsTableFqn());
            assertEquals(3, prof.size());
            JsonNode ws = Stream.of(prof.get(0).path("columnProfile")).flatMap(n -> {
                List<JsonNode> l = new ArrayList<>();
                n.forEach(l::add);
                return l.stream();
            }).filter(c -> c.path("name").asText().equals("wind_met_wind_speed")).findFirst().orElseThrow();
            assertEquals(3 * 3600, ws.path("valuesCount").asDouble());
            assertTrue(ws.path("firstQuartile").asDouble() <= ws.path("median").asDouble());
            assertTrue(ws.path("median").asDouble() <= ws.path("thirdQuartile").asDouble());

            // data quality: the stuck anemometer and the power outage show up as failed results
            assertTrue(results > 0);
            String flatline = p.signalsTableFqn() + ".wind_met_wind_speed.wind_met_wind_speed_flatline";
            List<String> failedFlat = failedWindowEnds(om.results.get(flatline));
            assertEquals(List.of("70", "80", "90"), failedFlat);
            assertTrue(om.results.get(flatline).get(6).path("result").asText().startsWith("1 of 3 turbines failed: T02"));
            String completeness = p.signalsTableFqn() + ".wind_turbine_power_active.wind_turbine_power_active_completeness";
            assertEquals(List.of("130", "140"), failedWindowEnds(om.results.get(completeness)));

            // publishing again is idempotent
            int entities = om.byFqn.size();
            publisher(new HttpOmApi(om.uri(), "test-token")).publishAll();
            assertEquals(entities, om.byFqn.size());
            assertEquals(List.of(), om.violations);
        }
    }

    private static List<String> failedWindowEnds(List<JsonNode> results) {
        long start = START.toEpochMilli();
        return results.stream().filter(r -> r.path("testCaseStatus").asText().equals("Failed"))
                .map(r -> Long.toString((r.path("timestamp").asLong() - start) / 60_000)).toList();
    }

    @Test
    void theValidatorRejectsPayloadsTheServerWouldReject() throws Exception {
        try (FakeOpenMetadata om = new FakeOpenMetadata()) {
            HttpOmApi api = new HttpOmApi(om.uri(), "test-token", 1);
            ObjectNode bad = new ObjectMapper().createObjectNode();
            bad.put("name", "x");
            bad.put("serviceType", "NoSuchService");
            OmException e = assertThrows(OmException.class, () -> api.put("/v1/services/databaseServices", bad));
            assertEquals(400, e.status());
            ObjectNode table = new ObjectMapper().createObjectNode();
            table.put("name", "t");
            table.put("databaseSchema", "missing.db.schema");
            table.putArray("columns").addObject().put("name", "c").put("dataType", "DOUBLE");
            assertThrows(OmException.class, () -> api.put("/v1/tables", table));
            assertEquals(2, om.violations.size(), om.violations::toString);
            assertEquals(401, assertThrows(OmException.class,
                    () -> new HttpOmApi(om.uri(), "wrong", 1).put("/v1/classifications", bad)).status());
        }
    }

    @Test
    void dryRunWritesEveryRequest(@TempDir Path dir) throws IOException {
        DryRunOmApi api = new DryRunOmApi(dir);
        WindparkMetadataPublisher p = publisher(api);
        p.publishAll();
        p.publishDataQuality(stats, "PT10M", WindparkExpectations.of(CATALOG));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(api.requests(), files.count());
        }
        assertTrue(Files.readString(dir.resolve("0001-PUT_v1_classifications.json")).contains("IEC61400-25"));
    }
}
