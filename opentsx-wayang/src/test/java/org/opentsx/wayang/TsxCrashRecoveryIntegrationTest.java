package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.wayang.basic.operators.MapOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-020 A1.5: an increment that fails after part of its output was written leaves the checkpoint where it was;
 * the next increment writes again, and every duplicate carries the same id and the same bytes.
 */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxCrashRecoveryIntegrationTest {

    @Test
    void aFailureAtThreePointsRepeatsOnlyIdenticalRecords(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        List<Episode> w = KafScaleFixture.scenarioW().subList(0, 300);
        Map<String, byte[]> in = k.write(k.layout.episodes(), w);
        Path ckpt = dir.resolve("ckpt.json");

        for (int failAfter : new int[]{1, w.size() / 2, w.size() - 1}) {
            AtomicInteger seen = new AtomicInteger();
            TsxIncrementException e = assertThrows(TsxIncrementException.class, () -> TsxIncrement.run(
                    k.config(ckpt, KafScaleFixture.EPISODE_ONLY), source -> {
                        MapOperator<SpecificRecord, SpecificRecord> crash = new MapOperator<>(r -> {
                            if (seen.incrementAndGet() > failAfter) {
                                throw new IllegalStateException("crash after " + failAfter);
                            }
                            return r;
                        }, SpecificRecord.class, SpecificRecord.class);
                        TsxTopicSink sink = new TsxTopicSink(k.bootstrap, k.layout.clean());
                        source.connectTo(0, crash, 0);
                        crash.connectTo(0, sink, 0);
                        return sink;
                    }));
            assertEquals("crash after " + failAfter, e.getMessage());
            assertFalse(Files.exists(ckpt), "no checkpoint after a failed increment");
        }

        TsxIncrement.run(k.config(ckpt, KafScaleFixture.EPISODE_ONLY),
                source -> TsxPassThroughIntegrationTest.connect(source, new TsxTopicSink(k.bootstrap, k.layout.clean())));

        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, byte[]> out = new HashMap<>();
        int records = 0;
        for (ConsumerRecord<byte[], byte[]> r : k.readAll(k.layout.clean())) {
            String id = avro.decode(r.value(), Episode.class).getEpisodeId();
            byte[] earlier = out.putIfAbsent(id, r.value());
            if (earlier != null) {
                assertArrayEquals(earlier, r.value(), "duplicate " + id + " differs");
            }
            records++;
        }
        assertEquals(in.keySet(), out.keySet());
        in.forEach((id, bytes) -> assertArrayEquals(bytes, out.get(id), id));
        assertTrue(records > w.size(), "the failed increments wrote records before they failed: " + records);
    }
}
