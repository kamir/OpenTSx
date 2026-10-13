package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.store.InMemorySeriesStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesInventoryTest {

    static final long T0 = 1_790_000_000_000_000L;
    static final long SECOND = 1_000_000L;

    /** One 60 s episode at 1 Hz, number {@code i} of its series. */
    static Episodes.Draft episode(SeriesKey key, int i, double level) {
        double[] v = new double[60];
        java.util.Arrays.fill(v, level);
        return Episodes.regular(key, T0 + i * 60 * SECOND, SECOND, v).source("test").createdAt(T0)
                .label("scenario", "unit-demo").label("iec", "T01/WMET.HorWdSpd");
    }

    static InMemorySeriesStore store() {
        SeriesKey speed = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p", "turbine", "T01"), "m/s");
        SeriesKey power = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "p", "turbine", "T01"), "kW");
        List<Episode> eps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Episodes.Draft d = episode(speed, i, i);
            if (i == 1 || i == 2) {
                d.label("gt.sensor_fault", "STUCK");          // two adjacent episodes: one mark
            }
            if (i == 2 || i == 5) {
                d.label("gt.operation", "CURTAILMENT,FAULT");  // two separate ranges, two types each
            }
            eps.add(d.build());
        }
        eps.add(episode(power, 0, 1500).build());
        // a segment cut out of the first speed episode: listed as segment, not counted again
        eps.add(episode(speed, 0, 9).parentEpisodeId(eps.get(0).getEpisodeId()).build());
        InMemorySeriesStore s = new InMemorySeriesStore();
        s.write(eps);
        return s;
    }

    static JsonNode series(JsonNode inventory, String metric) {
        for (JsonNode s : inventory.path("series")) {
            if (s.path("metric").asText().equals(metric)) {
                return s;
            }
        }
        throw new AssertionError("no series " + metric);
    }

    @Test
    void listsEverySeriesWithExtentLabelsAndMarks() {
        ObjectNode catalog = EpisodeJson.JSON.createObjectNode();
        catalog.putArray("channels").addObject().put("metric", "wind.met.wind_speed").put("description", "wind speed");
        ObjectNode scenario = EpisodeJson.JSON.createObjectNode().put("name", "unit-demo");
        JsonNode inv = new SeriesInventory(store(), new SeriesInventory.Source("kafscale", "wind",
                Map.of("episodes", "tsx.wind.episodes.v2")), scenario, catalog, Duration.ofMinutes(1)).get();

        assertEquals(SeriesInventory.SCHEMA, inv.path("schema").asText());
        assertEquals("tsx.wind.episodes.v2", inv.path("source").path("topics").path("episodes").asText());
        assertEquals("wind", inv.path("source").path("domain").asText());
        assertEquals(2, inv.path("totals").path("series").asInt());
        assertEquals(7, inv.path("totals").path("episodes").asInt());
        assertEquals(420, inv.path("totals").path("samples").asInt());
        assertEquals("unit-demo", inv.path("totals").path("scenarios").get(0).asText());
        assertEquals("unit-demo", inv.path("scenario").path("name").asText());
        assertEquals("wind speed", inv.path("channels").get(0).path("description").asText());

        JsonNode speed = series(inv, "wind.met.wind_speed");
        assertEquals("T01", speed.path("tags").path("turbine").asText());
        assertEquals("m/s", speed.path("unit").asText());
        assertEquals(6, speed.path("episodes").asInt());
        assertEquals(1, speed.path("segments").asInt());
        assertEquals(360, speed.path("samples").asInt());
        assertEquals(0, speed.path("missing").asInt());
        assertEquals(SECOND, speed.path("intervalMicros").asLong());
        assertEquals(0.0, speed.path("min").asDouble());
        assertEquals(5.0, speed.path("max").asDouble());
        assertEquals(2.5, speed.path("mean").asDouble(), 1e-12);
        assertEquals(Instant.ofEpochSecond(1_790_000_000L).toString(), speed.path("from").asText());
        assertEquals("T01/WMET.HorWdSpd", speed.path("labels").path("iec").get(0).asText());
        assertFalse(speed.path("labels").has("gt.sensor_fault"), "ground truth is reported as marks, not as labels");

        List<String> marks = new ArrayList<>();
        for (JsonNode m : speed.path("marks")) {
            long from = (Instant.parse(m.path("from").asText()).getEpochSecond() - 1_790_000_000L) / 60;
            long to = (Instant.parse(m.path("to").asText()).getEpochSecond() - 1_790_000_000L + 1) / 60;
            marks.add(m.path("kind").asText() + ":" + m.path("type").asText() + "@" + from + "-" + to);
        }
        assertEquals(List.of("sensor_fault:STUCK@1-3", "operation:CURTAILMENT@2-3", "operation:FAULT@2-3",
                "operation:CURTAILMENT@5-6", "operation:FAULT@5-6"), marks);

        assertEquals(0, series(inv, "wind.turbine.power_active").path("marks").size());
    }

    @Test
    void isCachedUntilInvalidated() {
        InMemorySeriesStore store = store();
        SeriesInventory inventory = new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null,
                Duration.ofMinutes(10));
        assertEquals(7, inventory.get().path("totals").path("episodes").asInt());
        SeriesKey extra = SeriesKeys.of("wind.rotor.speed", Map.of("turbine", "T02"), "rpm");
        store.write(List.of(episode(extra, 0, 12).build()));
        assertEquals(7, inventory.get().path("totals").path("episodes").asInt(), "served from the cache");
        inventory.invalidate();
        assertEquals(8, inventory.get().path("totals").path("episodes").asInt());
        assertFalse(inventory.get().path("source").has("truncated"));
        inventory.truncatedWhen(() -> true);
        inventory.invalidate();
        assertTrue(inventory.get().path("source").path("truncated").asBoolean());
    }

    @Test
    void isServedOverHttp() throws Exception {
        try (RetrievalServer server = new RetrievalServer(new EpisodeService(RetrievalServer.demoStore()), 0)) {
            HttpResponse<byte[]> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/v1/inventory")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, r.statusCode());
            JsonNode inv = EpisodeJson.JSON.readTree(r.body());
            assertEquals(3, inv.path("totals").path("series").asInt());
            assertEquals(108, inv.path("totals").path("episodes").asInt());
            assertEquals(3 * 6 * 3600, inv.path("totals").path("samples").asInt());
            assertEquals("demo.signal", inv.path("series").get(0).path("metric").asText());
        }
    }
}
