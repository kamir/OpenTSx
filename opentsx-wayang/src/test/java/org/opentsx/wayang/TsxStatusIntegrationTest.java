package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.v2.Episode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TASK-020 A2.5: a running loop of increments reports opentsx.dag-status.v1 records as S5.4 requires. */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxStatusIntegrationTest {

    @Test
    void theLoopReportsRunningAfterEveryIncrementAndStoppedAtTheEnd(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        List<Episode> w = KafScaleFixture.scenarioW().subList(0, 90);
        k.write(k.layout.episodes(), w);
        TsxIncrement.Config c = k.config(dir.resolve("ckpt.json"), KafScaleFixture.EPISODE_ONLY);
        AtomicInteger increments = new AtomicInteger();
        try (TsxStatus status = new TsxStatus(k.bootstrap, k.layout.dagStatus())) {
            long read = TsxIncrement.loop(c, (source, state) -> TsxPassThroughIntegrationTest.connect(source,
                    new TsxTopicSink(k.bootstrap, k.layout.clean())), status, Duration.ZERO,
                    () -> increments.getAndIncrement() >= 3);
            assertEquals(w.size(), read);
        }

        List<JsonNode> records = assertStatus(k.readAll(k.layout.dagStatus()), c);
        assertTrue(records.size() >= 5);
        assertEquals("stopped", records.get(records.size() - 1).path("state").asText());
        assertTrue(records.subList(0, records.size() - 1).stream().allMatch(n -> n.path("state").asText().equals("running")));
        assertEquals(w.size(), records.get(records.size() - 1).path("records").asLong());
    }

    @Test
    void aFailedIncrementIsReportedWithItsError(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        k.write(k.layout.episodes(), KafScaleFixture.scenarioW().subList(0, 3));
        TsxIncrement.Config c = k.config(dir.resolve("ckpt.json"), java.util.Set.of("0000000000000000"));
        TsxIncrementException error;
        try (TsxStatus status = new TsxStatus(k.bootstrap, k.layout.dagStatus())) {
            error = org.junit.jupiter.api.Assertions.assertThrows(TsxIncrementException.class, () -> TsxIncrement.loop(c,
                    (source, state) -> TsxPassThroughIntegrationTest.connect(source,
                            new TsxTopicSink(k.bootstrap, k.layout.clean())), status, Duration.ofSeconds(1), () -> false));
        }
        List<JsonNode> written = assertStatus(k.readAll(k.layout.dagStatus()), c);
        JsonNode last = written.get(written.size() - 1);
        assertEquals("failed", last.path("state").asText());
        assertEquals(error.getMessage(), last.path("error").asText());
    }

    static List<JsonNode> assertStatus(List<ConsumerRecord<byte[], byte[]>> written, TsxIncrement.Config config) throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> records = new ArrayList<>();
        long previousCount = 0;
        Instant previousTime = null;
        boolean terminal = false;
        for (ConsumerRecord<byte[], byte[]> r : written) {
            assertEquals(config.job(), new String(r.key(), StandardCharsets.UTF_8));
            JsonNode n = json.readTree(r.value());
            assertEquals("opentsx.dag-status.v1", n.path("schema").asText());
            assertEquals("A", n.path("track").asText());
            assertEquals(config.job(), n.path("dag").asText());
            assertEquals(config.job(), n.path("job").asText());
            assertEquals(config.planVersion(), n.path("planVersion").asInt());
            assertEquals(config.specHash(), n.path("specHash").asText());
            assertTrue(n.path("records").isIntegralNumber());
            assertTrue(n.path("records").asLong() >= previousCount);
            previousCount = n.path("records").asLong();
            assertTrue(n.has("watermark"));
            if (!n.path("watermark").isNull()) Instant.parse(n.path("watermark").asText());
            assertTrue(n.path("waitingFor").isArray());
            assertTrue(n.path("waitingForCount").asInt() >= n.path("waitingFor").size());
            assertTrue(n.path("skippedBlocks").isIntegralNumber());
            assertTrue(n.path("skippedBlocks").asLong() >= 0);
            assertTrue(List.of("running", "stopped", "failed").contains(n.path("state").asText()));
            assertTrue(!terminal, "no status may follow a terminal state");
            terminal = !n.path("state").asText().equals("running");
            if (n.path("state").asText().equals("failed")) assertTrue(n.path("error").isTextual());
            else assertTrue(!n.has("error"));
            Instant at = Instant.parse(n.path("at").asText());
            if (previousTime != null) {
                Duration gap = Duration.between(previousTime, at);
                assertTrue(!gap.isNegative());
                assertTrue(gap.compareTo(Duration.ofSeconds(31)) <= 0, "gap " + gap);
            }
            previousTime = at;
            records.add(n);
        }
        assertTrue(!records.isEmpty());
        assertEquals("running", records.get(0).path("state").asText());
        assertTrue(terminal, "the loop must report its terminal state");
        return records;
    }
}
