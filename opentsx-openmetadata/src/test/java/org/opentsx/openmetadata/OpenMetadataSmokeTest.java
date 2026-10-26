package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentsx.kafka.TopicLayout;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Publishes the simulated park to a real OpenMetadata 2.0.5 and reads it back through the REST API. It runs in the
 * workflow {@code .github/workflows/openmetadata-smoke.yml}, which starts the server and sets {@code OM_URL}
 * (for example {@code http://localhost:8585/api}) and {@code OPENMETADATA_JWT} (a token of the ingestion bot).
 */
@EnabledIfEnvironmentVariable(named = "OM_URL", matches = ".+")
class OpenMetadataSmokeTest {

    /** The server indexes some writes asynchronously; a read-back is retried for this long. */
    private static final Duration READ_BACK = Duration.ofSeconds(90);

    private final OmApi api = new HttpOmApi(URI.create(System.getenv("OM_URL")), System.getenv("OPENMETADATA_JWT"));

    @Test
    void publishedMetadataIsReadableFromTheServer() {
        SimulatedPark sim = SimulatedPark.simulate();
        WindparkMetadataPublisher p = sim.publisher(api);
        p.publishAll();
        assertEquals(3, p.publishProfiles(sim.stats, "PT1H"), "one profile per hour of the simulation");
        assertTrue(p.publishDataQuality(sim.stats, "PT10M", WindparkExpectations.of(SimulatedPark.CATALOG)) > 0);

        TopicLayout layout = new TopicLayout("wind");
        String episodesTopic = p.topicFqn(layout.episodes());
        String episodesTable = Fqn.child(p.schemaFqn(), "episodes");
        String signals = p.signalsTableFqn();

        // topics and tables
        JsonNode topic = await("topic " + episodesTopic, "/v1/topics/name/" + enc(episodesTopic),
                t -> t.path("messageSchema").path("schemaText").asText().contains("Episode"));
        assertEquals("delete", topic.path("cleanupPolicies").path(0).asText().toLowerCase(), topic::toPrettyString);
        await("table " + episodesTable, "/v1/tables/name/" + enc(episodesTable), t -> true);
        JsonNode view = await("table " + signals, "/v1/tables/name/" + enc(signals) + "?fields=columns,tags",
                t -> t.path("columns").size() == SimulatedPark.CATALOG.channels().size() + 2);
        assertTrue(view.path("columns").findValues("tagFQN").stream()
                        .anyMatch(tag -> tag.asText().startsWith("IEC61400-25.")),
                "columns carry their IEC logical node");

        // lineage: episodes topic -> Iceberg episodes table and -> series-stats topic
        String episodesTableId = api.get("/v1/tables/name/" + enc(episodesTable)).orElseThrow().path("id").asText();
        String statsTopicId = api.get("/v1/topics/name/" + enc(p.topicFqn(layout.seriesStats()))).orElseThrow()
                .path("id").asText();
        await("lineage of " + episodesTopic,
                "/v1/lineage/topic/name/" + enc(episodesTopic) + "?upstreamDepth=0&downstreamDepth=1",
                l -> targets(l).containsAll(List.of(episodesTableId, statsTopicId)));

        // profile: the latest hourly profile of the signals view, wind speed over 3 turbines x 3600 values
        JsonNode profiled = await("profile of " + signals,
                "/v1/tables/" + enc(signals) + "/tableProfile/latest?includeColumnProfile=true",
                t -> t.has("profile") && t.path("columns").findValues("valuesCount").size() > 0);
        JsonNode windSpeed = column(profiled, "wind_met_wind_speed");
        assertEquals(3 * 3600, windSpeed.path("profile").path("valuesCount").asDouble(), windSpeed::toPrettyString);

        // data quality: the stuck anemometer of T02 fails the flatline check
        String flatline = signals + ".wind_met_wind_speed.wind_met_wind_speed_flatline";
        long from = SimulatedPark.START.toEpochMilli();
        long to = SimulatedPark.START.plus(SimulatedPark.DURATION).plusSeconds(60).toEpochMilli();
        JsonNode results = await("results of " + flatline, "/v1/dataQuality/testCases/testCaseResults/" + enc(flatline)
                + "?startTs=" + from + "&endTs=" + to, r -> r.path("data").size() == 18);
        long failed = 0;
        for (JsonNode r : results.path("data")) {
            failed += r.path("testCaseStatus").asText().equals("Failed") ? 1 : 0;
        }
        assertEquals(3, failed, "the windows ending at minutes 70, 80 and 90");

        // publishing again is accepted: every write is create-or-update
        sim.publisher(api).publishAll();
    }

    private JsonNode await(String what, String path, Predicate<JsonNode> ready) {
        long deadline = System.nanoTime() + READ_BACK.toNanos();
        Optional<JsonNode> last = Optional.empty();
        while (System.nanoTime() < deadline) {
            last = api.get(path);
            if (last.isPresent() && ready.test(last.get())) {
                return last.get();
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fail(what + " not readable from " + path + ": " + last.map(JsonNode::toPrettyString).orElse("404"));
    }

    private static Set<String> targets(JsonNode lineage) {
        Set<String> ids = new HashSet<>();
        lineage.path("downstreamEdges").forEach(e -> ids.add(e.path("toEntity").asText()));
        return ids;
    }

    private static JsonNode column(JsonNode table, String name) {
        for (JsonNode c : table.path("columns")) {
            if (c.path("name").asText().equals(name)) {
                return c;
            }
        }
        return fail("column " + name + " missing: " + table.toPrettyString());
    }

    private static String enc(String fqn) {
        return URLEncoder.encode(fqn, StandardCharsets.UTF_8);
    }
}
