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
    static final Systems SYSTEMS = Systems.load(SystemDocTest.ROOT.resolve("systems"), SystemDocTest.ROOT);

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
        assertEquals("clean-not-running", speed.path("state").asText());
        assertEquals(70.0, speed.path("outOfRange").path("max").asDouble());
        assertEquals("exogenous", speed.path("role").asText());
        assertEquals(25.0, speed.path("expect").path("operating").path("max").asDouble());

        // bound to the topic, but the server holds no series for it
        JsonNode turbineState = signal(st, "park/T02/WTUR.TurSt");
        assertEquals("bound-no-data", turbineState.path("state").asText());
        assertEquals(List.of("check-source"), texts(turbineState.path("next")));

        // what was generated is an observation; what the weather would give is an estimate (15, sections 9 and 13.2)
        assertEquals("observation", signal(st, "park.p_gen_kw").path("role").asText());
        assertEquals("estimate", signal(st, "park.p_potential_kw").path("role").asText());

        // derived: no raw grade; its origin is the worst of what its flow reads
        JsonNode avail = signal(st, "park.p_potential_kw");
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
        assertEquals(13, counts.path("signals").asInt());
        assertEquals(3, counts.path("topic").asInt());
        assertEquals(2, counts.path("derived").asInt());
        assertEquals(1, counts.path("simulated").asInt());
        assertEquals(4, counts.path("manual").asInt());
        assertEquals(3, counts.path("open").asInt());
        assertEquals(3, st.path("states").path("open").asInt());
    }

    @Test
    void thePlanSaysWhatFeedsEveryInput() {
        JsonNode plan = SYSTEMS.plan("h2-chain", windFarm());
        assertEquals(12, plan.path("atoms").size());
        assertEquals(12, plan.path("counts").path("atoms").asInt());
        JsonNode park = null;
        JsonNode grid = null;
        for (JsonNode a : plan.path("atoms")) {
            park = a.path("id").asText().equals("park") ? a : park;
            grid = a.path("id").asText().equals("grid") ? a : grid;
        }
        assertEquals("wind-park", park.path("type").asText());
        // the phases are the columns of the picture; the assumptions stand next to it
        assertEquals(List.of("SOURCES", "ASSETS", "BALANCE", "FLOWS"), texts(plan.path("phases")));
        assertEquals("ASSETS", park.path("phase").asText());
        assertEquals(3, plan.path("assumptions").size());
        assertTrue(plan.path("assumptions").get(0).asText().contains("assumed"));
        // a wire inside one phase, and a feedback, deliver the value of the step before
        JsonNode ely = null;
        for (JsonNode a : plan.path("atoms")) {
            ely = a.path("id").asText().equals("ely") ? a : ely;
        }
        for (JsonNode in : ely.path("inputs")) {
            if (in.path("name").asText().equals("p_downstream_bar")) {
                assertTrue(in.path("delayed").asBoolean());
            }
        }
        for (JsonNode in : grid.path("inputs")) {
            assertFalse(in.path("delayed").asBoolean(false), in.toString());
        }
        assertEquals(3, park.path("channels").asInt());
        JsonNode avail = null;
        JsonNode limit = null;
        for (JsonNode in : park.path("inputs")) {
            avail = in.path("name").asText().equals("p_potential_kw") ? in : avail;
            limit = in.path("name").asText().equals("limit_kw") ? in : limit;
        }
        // an input from outside with a signal declared for it, and one wired inside the plan
        assertEquals("park.p_potential_kw", avail.path("signal").asText());
        assertEquals("clean-not-running", avail.path("state").asText());
        assertEquals("kW", avail.path("unit").asText());
        assertFalse(avail.has("from"));
        assertEquals("rules.limit_kw", limit.path("from").asText());
        assertFalse(limit.has("signal"));
        boolean wired = false;
        for (JsonNode in : grid.path("inputs")) {
            wired |= "park.p_gen_kw".equals(in.path("from").asText());
        }
        assertTrue(wired);
        // every input from outside has a signal; three of the signals are still open
        assertEquals(9, plan.path("counts").path("inputsFromOutside").asInt());
        assertEquals(0, plan.path("counts").path("inputsWithoutSignal").asInt());
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
    void aCleanSeriesAFlowWroteCountsAlsoWhenTheDocumentDoesNotDeclareIt() {
        ObjectNode inv = windFarm();
        // the workbench published the clean grade of the turbine state; the document only binds its raw grade
        ObjectNode clean = series(inv, "fff", "wind.turbine.state", 1, 4);
        ((ObjectNode) clean.get("tags")).put("system", "h2-chain").put("signal", "park/T02/WTUR.TurSt").put("turbine", "T02");
        ((ObjectNode) clean.get("labels")).putArray("grade").add("clean");
        ((ObjectNode) clean.get("labels")).putArray("flow").add("wind-flow-19");
        JsonNode speed = signal(SYSTEMS.signals("h2-chain", inv), "park/T02/WTUR.TurSt");
        assertEquals("clean", speed.path("state").asText());
        assertEquals(0, speed.path("next").size());
        JsonNode grade = speed.path("grades").path("clean");
        assertEquals(List.of("fff"), texts(grade.path("data").path("series")));
        assertEquals("wind-flow-19", grade.path("source").path("flow").asText());
        assertFalse(grade.path("source").path("declared").asBoolean(true));
        assertFalse(speed.has("outOfRange"));
    }

    @Test
    void aServerThatReadsAnotherTopicHasNoDataForTheSignal() {
        ObjectNode inv = windFarm();
        ((ObjectNode) inv.path("source").path("topics")).put("episodes", "tsx.solar.episodes.v2");
        // the wind speed has a flow declared for its clean grade; its raw data is not on this server
        JsonNode speed = signal(SYSTEMS.signals("h2-chain", inv), "park/T02/WMET.HorWdSpd");
        assertFalse(speed.path("grades").path("raw").has("data"));
        assertEquals("clean-not-running", speed.path("state").asText());
    }

    // ---------------------------------------------------------------- working copy and demo generators (stage L4)

    static JsonNode body(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void aBindingOfAWorkingCopyLiesOverTheDocument() throws Exception {
        ObjectNode inv = windFarm();
        ((ObjectNode) series(inv, "ggg", "h2.demand", 0, 80).get("tags")).put("customer", "c1");
        // the demand has no source in the document; a person binds it to a series in the web application
        JsonNode overlay = body("""
                {"bindings":[{"signal":"offtake.demand_kgph","grade":"raw",
                  "source":{"kind":"topic","topic":"tsx.wind.episodes.v2","origin":"REPORTED","select":{"metric":"h2.demand","tags":{"customer":"c1"}}}}]}""");
        JsonNode demand = signal(SYSTEMS.signals("h2-chain", inv, Systems.bindings(overlay)), "offtake.demand_kgph");
        assertEquals("raw-only", demand.path("state").asText());
        assertEquals("REPORTED", demand.path("origin").asText());
        assertTrue(demand.path("grades").path("raw").path("source").path("workingCopy").asBoolean());
        assertEquals(List.of("ggg"), texts(demand.path("grades").path("raw").path("data").path("series")));
        // the document itself is untouched
        assertEquals("open", signal(SYSTEMS.signals("h2-chain", inv), "offtake.demand_kgph").path("state").asText());
        // a binding for something the system does not have is named
        JsonNode wrong = SYSTEMS.signals("h2-chain", inv, Systems.bindings(body(
                "{\"bindings\":[{\"signal\":\"offtake.no_such\",\"grade\":\"raw\",\"source\":{\"kind\":\"manual\",\"value\":1}}]}")));
        assertTrue(wrong.path("problems").get(0).asText().contains("offtake.no_such@raw is no grade of a signal"));
    }

    @Test
    void aDemoGeneratorWritesTheSeriesOfItsSignalAndTheStateFollows() throws Exception {
        List<org.opentsx.model.v2.Episode> written = new java.util.ArrayList<>();
        List<String> topics = new java.util.ArrayList<>();
        Systems.Writer writer = (topic, episodes) -> {
            topics.add(topic);
            written.addAll(episodes);
        };
        JsonNode answer = SYSTEMS.generate("h2-chain", body(
                "{\"signal\":\"market.price_eur_mwh\",\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-01T06:00:00Z\"}"),
                "tsx.wind.synthetic.v2", writer);
        assertEquals(List.of("tsx.wind.synthetic.v2"), topics);
        assertEquals(24, answer.path("samples").asInt());
        assertEquals("demo", answer.path("fidelity").asText());
        assertEquals("gen.ou", answer.path("generator").asText());
        org.opentsx.model.v2.Episode first = written.get(0);
        assertEquals("market.price_eur_mwh", first.getSeries().getTags().get("signal"));
        assertEquals("raw", first.getSeries().getTags().get("grade"));

        // the inventory then holds the series, and the signal is no longer "not generated yet"
        ObjectNode inv = windFarm();
        ObjectNode gen = series(inv, first.getSeries().getSeriesId(), first.getSeries().getMetric(), 40, 80);
        first.getSeries().getTags().forEach(((ObjectNode) gen.get("tags"))::put);
        JsonNode price = signal(SYSTEMS.signals("h2-chain", inv), "market.price_eur_mwh");
        assertEquals("raw-only", price.path("state").asText());
        assertEquals("simulated", price.path("via").asText());
        assertEquals("SYNTHETIC", price.path("origin").asText());
        // generated raw data is not mistaken for the clean grade of the same signal
        assertFalse(price.path("grades").has("clean"));

        // only a signal bound to a generator can be generated, and only with a period
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> SYSTEMS.generate("h2-chain",
                body("{\"signal\":\"ely.setpoint_kw\",\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-01T06:00:00Z\"}"), "t", writer))
                .getMessage().contains("is not bound to a generator"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> SYSTEMS.generate("h2-chain",
                body("{\"signal\":\"market.price_eur_mwh\"}"), "t", writer)).getMessage().contains("from and to must be instants"));
        // a signal bound to a generator in the working copy can be generated too
        JsonNode viaOverlay = SYSTEMS.generate("h2-chain", body("""
                {"signal":"offtake.demand_kgph","from":"2026-10-01T00:00:00Z","to":"2026-10-01T01:00:00Z",
                 "bindings":[{"signal":"offtake.demand_kgph","grade":"raw","source":{"kind":"generator","processor":"gen.sine","seed":1,
                   "params":{"mean":60,"amplitude":30,"period":"PT6H"}}}]}"""), "tsx.wind.synthetic.v2", writer);
        assertEquals(60, viaOverlay.path("samples").asInt());
    }

    @Test
    void generatingIsAWriteAndNeedsTheRunToken() throws Exception {
        InMemorySeriesStore store = new InMemorySeriesStore();
        SeriesInventory inventory = new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1));
        HttpClient http = HttpClient.newHttpClient();
        List<String> topics = new java.util.ArrayList<>();
        try (RetrievalServer server = new RetrievalServer(new EpisodeService(store), 0, inventory, null, false, "secret", SYSTEMS)) {
            String base = "http://localhost:" + server.port() + "/v1/systems/h2-chain";
            String gen = "{\"signal\":\"market.price_eur_mwh\",\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-01T06:00:00Z\"}";
            // a server without a broker cannot write
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/generate")).POST(HttpRequest.BodyPublishers.ofString(gen)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            server.generatedSeries("tsx.wind.synthetic.v2", (topic, episodes) -> topics.add(topic + ":" + episodes.size()));
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/generate")).POST(HttpRequest.BodyPublishers.ofString(gen)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create(base + "/generate")).header(RetrievalServer.RUN_TOKEN_HEADER, "secret")
                    .POST(HttpRequest.BodyPublishers.ofString(gen)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals(1, topics.size());
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/generate")).header(RetrievalServer.RUN_TOKEN_HEADER, "secret")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"signal\":\"ely.setpoint_kw\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());
            // the state with a working copy is a read and needs no token
            HttpResponse<String> overlay = http.send(HttpRequest.newBuilder(URI.create(base + "/signals")).POST(HttpRequest.BodyPublishers.ofString(
                    "{\"bindings\":[{\"signal\":\"offtake.demand_kgph\",\"grade\":\"raw\",\"source\":{\"kind\":\"manual\",\"value\":40,\"origin\":\"REPORTED\"}}]}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, overlay.statusCode());
            assertEquals("constant", signal(JSON.readTree(overlay.body()), "offtake.demand_kgph").path("state").asText());
        }
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
            assertEquals(13, first.path("counts").path("signals").asInt());
            assertEquals(0, first.path("problems").asInt());

            HttpResponse<String> signals = http.send(HttpRequest.newBuilder(URI.create(base + "/h2-chain/signals")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signals.statusCode());
            assertEquals(13, JSON.readTree(signals.body()).path("signals").size());

            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/h2-chain/plan")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            // the plan with a working copy laid over it: the open demand is bound, and the input says so
            String body = "{\"bindings\":[{\"signal\":\"offtake.demand_kgph\",\"grade\":\"raw\",\"source\":{\"kind\":\"manual\",\"value\":80,\"origin\":\"REPORTED\"}}]}";
            HttpResponse<String> overlaid = http.send(HttpRequest.newBuilder(URI.create(base + "/h2-chain/plan"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, overlaid.statusCode());
            String demand = "";
            for (JsonNode a : JSON.readTree(overlaid.body()).path("atoms")) {
                for (JsonNode in : a.path("inputs")) {
                    demand = in.path("signal").asText().equals("offtake.demand_kgph") ? in.path("state").asText() : demand;
                }
            }
            assertEquals("constant", demand);
            HttpResponse<String> decisions = http.send(HttpRequest.newBuilder(URI.create(base + "/h2-chain/decisions")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, decisions.statusCode());
            assertEquals("h2-dispatch", JSON.readTree(decisions.body()).path("decisions").get(0).path("id").asText());
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/no-such/signals")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String none = "http://localhost:" + without.port() + "/v1/systems";
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(none)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
