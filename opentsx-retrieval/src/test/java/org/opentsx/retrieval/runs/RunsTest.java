package org.opentsx.retrieval.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.server.RetrievalServer;
import org.opentsx.retrieval.server.SeriesInventory;
import org.opentsx.retrieval.system.Systems;
import org.opentsx.store.InMemorySeriesStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs of the twin of h2-chain on request: what a person may ask, what comes back, and what is kept. */
class RunsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode body(String json) throws Exception {
        return JSON.readTree(json);
    }

    static JsonNode kpi(JsonNode compare, String key) {
        for (JsonNode k : compare.path("kpis")) {
            if (key.equals(k.path("key").asText())) {
                return k;
            }
        }
        throw new AssertionError("no kpi " + key);
    }

    static List<String> events(JsonNode run) {
        List<String> out = new ArrayList<>();
        run.path("events").forEach(e -> out.add(e.path("clock").asText() + " " + e.path("atom").asText() + " " + e.path("type").asText()));
        return out;
    }

    @Test
    void theScenarioSaysWhatCanBeAsked() {
        Runs runs = Runs.standard();
        assertTrue(runs.has("h2-chain"));
        assertFalse(runs.has("no-such"));
        JsonNode s = runs.scenario("h2-chain");
        assertEquals(360, s.path("steps").asInt());
        assertEquals(60, s.path("stepSeconds").asInt());
        List<String> controls = new ArrayList<>();
        s.path("controls").forEach(c -> controls.add(c.path("atom").asText() + "." + c.path("control").asText()));
        assertTrue(controls.containsAll(List.of("handover.valve", "ely.setpoint_kw", "park.dispatch_kw", "park.limit_kw", "battery.power_kw")), controls.toString());
        assertEquals("handover", s.path("referenceDecisions").get(0).path("atom").asText());
        assertEquals("01:00:00", s.path("referenceDecisions").get(0).path("clock").asText());
        assertEquals(2, s.path("policies").size());
        assertEquals(s.path("reference").asText(), runs.reference("h2-chain"));
    }

    @Test
    void aRunHasAnIdentityAndSaysWhereItsNumbersComeFrom() throws Exception {
        Runs runs = Runs.standard();
        JsonNode run = runs.run("h2-chain", body("""
                {"interventions": [{"at": "02:00", "atom": "handover", "control": "valve", "value": 1, "reason": "valve reopened"}]}"""));
        assertEquals("opentsx.run.v1", run.path("schema").asText());
        assertEquals("what-if", run.path("kind").asText());
        assertEquals(runs.reference("h2-chain"), run.path("baseline").asText());
        assertEquals("handover.valve = 1 at 02:00:00", run.path("label").asText());
        // the decisions of the reference stay in force, and the one that was asked for comes on top
        assertEquals(4, run.path("decisions").size());
        assertTrue(events(run).contains("02:00:00 handover intervention"), events(run).toString());
        assertFalse(events(run).stream().anyMatch(e -> e.endsWith("protective_shutdown")));
        assertEquals(0.0, run.path("kpis").path("ely.trips").asDouble());
        // every run says that its inputs are invented
        assertEquals("restricted", run.path("status").path("usability").asText());
        assertTrue(run.path("status").path("reasons").get(0).asText().contains("invented"));
        assertEquals("MEASURED", run.path("inputs").get(run.path("inputs").size() - 1).path("origin").asText());
        assertTrue(run.path("inputs").toString().contains("park.p_potential_kw"));

        // the same question gives the same run; another question another one
        JsonNode again = runs.run("h2-chain", body("""
                {"interventions": [{"at": 120, "atom": "handover", "control": "valve", "value": 1, "reason": "valve reopened"}]}"""));
        assertEquals(run.path("run").asText(), again.path("run").asText());
        assertEquals(run.path("hash").asText(), again.path("hash").asText());
        JsonNode later = runs.run("h2-chain", body("""
                {"interventions": [{"at": "02:40", "atom": "handover", "control": "valve", "value": 1}]}"""));
        assertNotEquals(run.path("run").asText(), later.path("run").asText());
        assertEquals(1.0, later.path("kpis").path("ely.trips").asDouble(), "too late: the buffer was full at 02:29");

        // the register: reference, and the two runs, newest first
        JsonNode list = runs.list("h2-chain");
        assertEquals(3, list.path("runs").size());
        assertEquals(later.path("run").asText(), list.path("runs").get(0).path("run").asText());
        assertEquals("reference", list.path("runs").get(2).path("kind").asText());
        assertEquals(run.path("run").asText(), runs.doc("h2-chain", run.path("run").asText()).path("run").asText());
    }

    @Test
    void twoRunsAreComparedByTheKpisOfTheScenario() throws Exception {
        Runs runs = Runs.standard();
        String run = runs.run("h2-chain", body("""
                {"label": "reopen at two", "interventions": [{"at": "02:00", "atom": "handover", "control": "valve", "value": 1}]}""")).path("run").asText();
        JsonNode c = runs.compare("h2-chain", run, runs.reference("h2-chain"));
        assertEquals("reopen at two", c.path("run").path("label").asText());
        assertEquals(-1.0, kpi(c, "ely.trips").path("delta").asDouble());
        assertEquals("better", kpi(c, "ely.trips").path("verdict").asText());
        assertEquals("better", kpi(c, "handover.delivered_kg").path("verdict").asText());
        assertEquals("same", kpi(c, "park.curtailed_rules_kwh").path("verdict").asText());
        // the series the scenario compares, over the whole horizon, with its limits
        JsonNode p = c.path("series").path("buffer.p_bar");
        assertEquals(360, p.path("base").size());
        assertEquals(360, p.path("run").size());
        assertEquals(p.path("base").get(100).asDouble(), p.path("run").get(100).asDouble(), 1e-12, "the same until the valve reopens");
        assertTrue(p.path("run").get(300).asDouble() < p.path("base").get(300).asDouble());
        assertEquals(200.0, p.path("limits").get(0).path("value").asDouble());
        // what is left in the stores at the end stands next to the KPIs
        assertEquals("buffer.state.mass_kg", c.path("endStocks").get(0).path("key").asText());

        JsonNode series = runs.series("h2-chain", run, List.of("buffer.p_bar", "ely.p_el_kw"));
        assertEquals(360, series.path("series").path("ely.p_el_kw").path("values").size());
        assertThrows(IllegalArgumentException.class, () -> runs.series("h2-chain", run, List.of("no.such")));
    }

    @Test
    void aForecastContinuesTheReferenceAndMayChangeThePolicy() throws Exception {
        Runs runs = Runs.standard();
        JsonNode aware = runs.run("h2-chain", body("""
                {"from": 70, "policies": {"ely": "pressure-aware"}}"""));
        assertEquals("forecast", aware.path("kind").asText());
        assertEquals("FORECAST", aware.path("mode").asText());
        assertEquals(70, aware.path("firstStep").asInt());
        assertEquals(290, aware.path("steps").asInt());
        assertEquals("forecast from minute 70; ely runs pressure-aware", aware.path("label").asText());
        assertEquals(0.0, aware.path("kpis").path("ely.trips").asDouble());
        assertEquals("FORECAST", aware.path("inputs").get(0).path("origin").asText());
        // in the comparison the minutes before the forecast are empty for the run, not zero
        JsonNode p = runs.compare("h2-chain", aware.path("run").asText(), runs.reference("h2-chain")).path("series").path("buffer.p_bar");
        assertTrue(p.path("run").get(10).isNull());
        assertFalse(p.path("run").get(70).isNull());
        // a forecast cannot change the past
        assertThrows(IllegalArgumentException.class, () -> runs.run("h2-chain", body("""
                {"from": 70, "interventions": [{"at": "00:30", "atom": "handover", "control": "valve", "value": 1}]}""")));
    }

    @Test
    void whatCannotBeAskedIsRefusedWithAReason() {
        Runs runs = Runs.standard();
        for (String bad : List.of(
                "{\"interventions\": [{\"at\": \"02:00\", \"atom\": \"handover\", \"control\": \"nope\", \"value\": 1}]}",
                "{\"interventions\": [{\"at\": \"02:00\", \"atom\": \"nobody\", \"control\": \"valve\", \"value\": 1}]}",
                "{\"interventions\": [{\"at\": \"09:00\", \"atom\": \"handover\", \"control\": \"valve\", \"value\": 1}]}",
                "{\"interventions\": [{\"at\": \"soon\", \"atom\": \"handover\", \"control\": \"valve\", \"value\": 1}]}",
                "{\"interventions\": [{\"at\": \"02:00\", \"atom\": \"handover\", \"control\": \"valve\", \"value\": \"open\"}]}",
                "{\"policies\": {\"ely\": \"magic\"}}",
                "{\"from\": 0}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> runs.run("h2-chain", body(bad)), bad);
            assertFalse(e.getMessage().isBlank(), bad);
        }
        assertThrows(NoSuchElementException.class, () -> runs.doc("h2-chain", "h2-chain-0000"));
    }

    @Test
    void theServerOffersRunsOfASystem() throws Exception {
        InMemorySeriesStore store = new InMemorySeriesStore();
        SeriesInventory inventory = new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1));
        java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        Systems systems = Systems.load(root.resolve("systems"), root);
        HttpClient http = HttpClient.newHttpClient();
        try (RetrievalServer server = new RetrievalServer(new EpisodeService(store), 0, inventory, null, false, null, systems)) {
            String base = "http://localhost:" + server.port() + "/v1/systems/h2-chain";
            HttpResponse<String> scenario = http.send(HttpRequest.newBuilder(URI.create(base + "/scenario")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, scenario.statusCode());
            String ref = JSON.readTree(scenario.body()).path("reference").asText();
            HttpResponse<String> made = http.send(HttpRequest.newBuilder(URI.create(base + "/runs"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"interventions\":[{\"at\":\"02:00\",\"atom\":\"handover\",\"control\":\"valve\",\"value\":1}]}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, made.statusCode(), made.body());
            String run = JSON.readTree(made.body()).path("run").asText();
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/runs")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/runs/" + run)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/runs/" + run + "/series?keys=buffer.p_bar")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> cmp = http.send(HttpRequest.newBuilder(URI.create(base + "/runs/" + run + "/compare")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cmp.statusCode());
            assertEquals(ref, JSON.readTree(cmp.body()).path("base").path("run").asText(), "compared with the reference unless another base is named");
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/runs/nope")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(base + "/runs/" + run + "/series")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(base + "/runs"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"policies\":{\"ely\":\"magic\"}}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
