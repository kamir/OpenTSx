package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.server.RetrievalServer;
import org.opentsx.retrieval.server.SeriesInventory;
import org.opentsx.store.InMemorySeriesStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The state of the signals of h2-chain against an inventory: what is there, what is missing, what to do next. */
class SystemsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Systems SYSTEMS = new Systems(List.of(SystemDoc.load(SystemDocTest.H2, SystemDocTest.ROOT)));

    /** An inventory as GET /v1/inventory gives it, reduced to what the state needs. */
    static ObjectNode inventory(String topic) {
        ObjectNode inv = JSON.createObjectNode();
        inv.putObject("source").put("kind", "kafscale").putObject("topics").put("episodes", topic);
        inv.putArray("series");
        return inv;
    }

    static ObjectNode series(ObjectNode inv, String id, String metric, double min, double max) {
        ObjectNode s = inv.withArray("series").addObject();
        s.put("seriesId", id).put("metric", metric).put("samples", 1000).put("missing", 10).put("min", min).put("max", max);
        s.put("from", "2026-10-01T00:00:00Z").put("to", "2026-10-01T06:00:00Z").put("intervalMicros", 1_000_000);
        s.putObject("tags");
        s.putObject("labels");
        return s;
    }

    static ObjectNode windFarm() {
        ObjectNode inv = inventory("tsx.wind.episodes.v2");
        ((ObjectNode) series(inv, "aaa", "wind.turbine.power_active", -20, 3000).get("tags")).put("park", "ost-01").put("turbine", "T02");
        ((ObjectNode) series(inv, "bbb", "wind.turbine.power_active", -20, 3000).get("tags")).put("park", "ost-01").put("turbine", "T03");
        // a gust the catalog does not allow: 0 to 60 m/s
        ((ObjectNode) series(inv, "ccc", "wind.met.wind_speed", 0, 70).get("tags")).put("park", "ost-01").put("turbine", "T02");
        return inv;
    }

    static JsonNode signal(JsonNode status, String id) {
        for (JsonNode s : status.path("signals")) {
            if (id.equals(s.path("id").asText())) {
                return s;
            }
        }
        throw new AssertionError("no signal " + id);
    }

    static List<String> texts(JsonNode array) {
        return JSON.convertValue(array, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    @Test
    void everySignalHasOneStateAndANextStep() {
        JsonNode st = SYSTEMS.signals("h2-chain", windFarm());
        assertEquals("opentsx.signal-status.v1", st.path("schema").asText());
        assertEquals(0, st.path("problems").size());

        // raw data is there and a flow is declared for the clean grade, which nothing has written yet
        JsonNode power = signal(st, "park/T02/WTUR.W");
        assertEquals("clean-not-running", power.path("state").asText());
        assertEquals(List.of("start"), texts(power.path("next")));
        assertEquals(List.of("aaa"), texts(power.path("grades").path("raw").path("data").path("series")));
        assertEquals("SYNTHETIC", power.path("origin").asText());
        assertEquals("topic", power.path("via").asText());
        assertEquals("T02", power.path("group").asText());
        assertEquals("WTUR.W", power.path("name").asText());
        assertFalse(power.has("outOfRange"));
        assertEquals(List.of("turbine-power-clean", "turbine-power-features", "park-power"), texts(power.path("usedBy")));

        JsonNode speed = signal(st, "park/T02/WMET.HorWdSpd");
        assertEquals("raw-only", speed.path("state").asText());
        assertEquals(List.of("prepare"), texts(speed.path("next")));
        assertEquals(70.0, speed.path("outOfRange").path("max").asDouble());

        // derived: no raw grade; its origin is the worst of what its flow reads
        JsonNode avail = signal(st, "park.p_avail_kw");
        assertEquals("clean-not-running", avail.path("state").asText());
        assertEquals("derived", avail.path("via").asText());
        assertEquals("SYNTHETIC", avail.path("origin").asText());
        assertTrue(avail.path("port").asBoolean());

        JsonNode price = signal(st, "market.price_eur_mwh");
        assertEquals("bound-no-data", price.path("state").asText());
        assertEquals("simulated", price.path("via").asText());
        assertEquals("SYNTHETIC", price.path("origin").asText());
        assertEquals(7, price.path("grades").path("raw").path("source").path("seed").asInt());
        // nothing has run the generator; that is the step, not a wrong source
        assertEquals(List.of("generate"), texts(price.path("next")));

        JsonNode demand = signal(st, "offtake.demand_kgph");
        assertEquals("open", demand.path("state").asText());
        assertEquals(List.of("bind", "simulate"), texts(demand.path("next")));
        assertFalse(demand.has("origin"));

        JsonNode setpoint = signal(st, "ely.setpoint_kw");
        assertEquals("constant", setpoint.path("state").asText());
        assertEquals("REPORTED", setpoint.path("origin").asText());
        assertEquals(5000.0, setpoint.path("grades").path("raw").path("source").path("value").asDouble());

        JsonNode counts = st.path("counts");
        assertEquals(8, counts.path("signals").asInt());
        assertEquals(2, counts.path("topic").asInt());
        assertEquals(1, counts.path("derived").asInt());
        assertEquals(1, counts.path("simulated").asInt());
        assertEquals(2, counts.path("manual").asInt());
        assertEquals(2, counts.path("open").asInt());
        assertEquals(2, st.path("states").path("open").asInt());
    }

    @Test
    void cleanDataIsFoundByTheTagsSystemAndSignal() {
        ObjectNode inv = windFarm();
        ((ObjectNode) series(inv, "ddd", "wind.turbine.power_active", 0, 2900).get("tags"))
                .put("system", "h2-chain").put("signal", "park/T02/WTUR.W").put("turbine", "T02");
        ((ObjectNode) series(inv, "eee", "wind.turbine.power_active", 0, 2900).get("tags"))
                .put("system", "h2-chain").put("signal", "park/T02/WTUR.W").put("feature", "lag_1");
        JsonNode power = signal(SYSTEMS.signals("h2-chain", inv), "park/T02/WTUR.W");
        assertEquals("clean", power.path("state").asText());
        assertEquals(0, power.path("next").size());
        assertEquals(List.of("ddd"), texts(power.path("grades").path("clean").path("data").path("series")));
        assertEquals(List.of("eee"), texts(power.path("grades").path("features").path("data").path("series")));
        // the raw grade still matches the raw series only
        assertEquals(List.of("aaa"), texts(power.path("grades").path("raw").path("data").path("series")));
    }

    @Test
    void aServerThatReadsAnotherTopicHasNoDataForTheSignal() {
        ObjectNode inv = windFarm();
        ((ObjectNode) inv.path("source").path("topics")).put("episodes", "tsx.solar.episodes.v2");
        assertEquals("bound-no-data", signal(SYSTEMS.signals("h2-chain", inv), "park/T02/WMET.HorWdSpd").path("state").asText());
    }

    @Test
    void theServerHandsOutSystemsAndSignals() throws Exception {
        InMemorySeriesStore store = new InMemorySeriesStore();
        SeriesInventory inventory = new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1));
        HttpClient http = HttpClient.newHttpClient();
        try (RetrievalServer with = new RetrievalServer(new EpisodeService(store), 0, inventory, null, false, null, SYSTEMS);
             RetrievalServer without = new RetrievalServer(new EpisodeService(store), 0, inventory)) {
            String base = "http://localhost:" + with.port() + "/v1/systems";
            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            JsonNode first = JSON.readTree(list.body()).path("systems").get(0);
            assertEquals("h2-chain", first.path("system").asText());
            assertEquals(8, first.path("counts").path("signals").asInt());
            assertEquals(0, first.path("problems").asInt());

            HttpResponse<String> signals = http.send(HttpRequest.newBuilder(URI.create(base + "/h2-chain/signals")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signals.statusCode());
            assertEquals(8, JSON.readTree(signals.body()).path("signals").size());

            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/no-such/signals")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String none = "http://localhost:" + without.port() + "/v1/systems";
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(none)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
