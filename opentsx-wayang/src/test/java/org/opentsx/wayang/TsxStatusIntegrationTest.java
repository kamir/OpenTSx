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
                    new TsxTopicSink(k.bootstrap, k.layout.clean())), status, Duration.ofSeconds(2),
                    () -> increments.getAndIncrement() >= 3);
            assertEquals(w.size(), read);
        }

        ObjectMapper json = new ObjectMapper();
        List<JsonNode> records = new ArrayList<>();
        for (ConsumerRecord<byte[], byte[]> r : k.readAll(k.layout.dagStatus())) {
            assertEquals(c.job(), new String(r.key(), StandardCharsets.UTF_8));
            records.add(json.readTree(r.value()));
        }
        records.sort((a, b) -> Instant.parse(a.path("at").asText()).compareTo(Instant.parse(b.path("at").asText())));
        assertEquals(List.of("running", "running", "running", "running", "stopped"),
                records.stream().map(n -> n.path("state").asText()).toList());
        for (JsonNode n : records) {
            assertEquals("opentsx.dag-status.v1", n.path("schema").asText());
            assertEquals("A", n.path("track").asText());
            assertEquals(c.job(), n.path("job").asText());
            assertEquals(c.planVersion(), n.path("planVersion").asInt());
            assertEquals(c.specHash(), n.path("specHash").asText());
        }
        assertEquals(w.size(), records.get(records.size() - 1).path("records").asLong());
        for (int i = 1; i < records.size(); i++) {
            Duration gap = Duration.between(Instant.parse(records.get(i - 1).path("at").asText()),
                    Instant.parse(records.get(i).path("at").asText()));
            assertTrue(gap.compareTo(Duration.ofSeconds(31)) <= 0, "gap " + gap);
        }
    }

    @Test
    void aFailedIncrementIsReportedWithItsError(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        k.write(k.layout.episodes(), KafScaleFixture.scenarioW().subList(0, 3));
        TsxIncrement.Config c = k.config(dir.resolve("ckpt.json"), java.util.Set.of("0000000000000000"));
        try (TsxStatus status = new TsxStatus(k.bootstrap, k.layout.dagStatus())) {
            org.junit.jupiter.api.Assertions.assertThrows(TsxIncrementException.class, () -> TsxIncrement.loop(c,
                    (source, state) -> TsxPassThroughIntegrationTest.connect(source,
                            new TsxTopicSink(k.bootstrap, k.layout.clean())), status, Duration.ofSeconds(1), () -> false));
        }
        List<ConsumerRecord<byte[], byte[]>> written = k.readAll(k.layout.dagStatus());
        JsonNode last = new ObjectMapper().readTree(written.get(written.size() - 1).value());
        assertEquals("failed", last.path("state").asText());
        assertTrue(last.path("error").asText().contains("has schema fingerprint 8985f02b48c77195, not in plan it version 1"),
                last.toString());
    }
}
