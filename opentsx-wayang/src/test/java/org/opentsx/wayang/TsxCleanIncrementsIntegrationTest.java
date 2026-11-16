package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-020 A2.4: a clean chain with an open resampling window runs over W in three increments on KafScale, its state
 * carried in the checkpoint, and writes the same episode ids and values as {@link SeriesStream} of
 * {@code opentsx-retrieval} over all of W in one pass.
 */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxCleanIncrementsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static List<JsonNode> nodes() throws Exception {
        return List.of(
                JSON.readTree("{\"id\":\"range\",\"op\":\"dq.checks\",\"params\":{\"checks\":[\"PLAUSIBLE_RANGE\"],\"min\":-50,\"max\":5000,\"mode\":\"gap\"}}"),
                JSON.readTree("{\"id\":\"minute\",\"op\":\"resample\",\"params\":{\"every\":\"PT7M\",\"agg\":\"MEAN\"}}"));
    }

    @Test
    void threeIncrementsWriteWhatOnePassWrites(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        List<Episode> w = KafScaleFixture.scenarioW();
        Path ckpt = dir.resolve("ckpt.json");
        List<JsonNode> nodes = nodes();
        int third = w.size() / 3;
        for (List<Episode> part : List.of(w.subList(0, third), w.subList(third, 2 * third), w.subList(2 * third, w.size()))) {
            k.write(k.layout.episodes(), part);
            TsxIncrement.run(k.config(ckpt, KafScaleFixture.EPISODE_ONLY), (source, state) -> {
                TsxSeriesChain chain = new TsxSeriesChain("minute", nodes, TsxCleanVectorsTest.TARGET, List.of(), state);
                TsxTopicSink sink = new TsxTopicSink(k.bootstrap, k.layout.clean());
                source.connectTo(0, chain, 0);
                chain.connectTo(0, sink, 0);
                return sink;
            });
        }

        SeriesStream once = new SeriesStream(CleanChain.of(nodes), TsxCleanVectorsTest.TARGET, List.of());
        Map<String, double[]> expected = new TreeMap<>();
        for (Episode e : w) {
            once.add(e).forEach(c -> expected.put(c.getEpisodeId(), Episodes.values(c)));
        }

        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, double[]> actual = new TreeMap<>();
        List<ConsumerRecord<byte[], byte[]>> written = k.readAll(k.layout.clean());
        for (ConsumerRecord<byte[], byte[]> r : written) {
            Episode c = avro.decode(r.value(), Episode.class);
            actual.put(c.getEpisodeId(), Episodes.values(c));
        }
        assertTrue(expected.size() > 100, "W gives clean episodes: " + expected.size());
        assertEquals(expected.keySet(), actual.keySet());
        assertEquals(expected.size(), written.size(), "no duplicates without a failure");
        List<String> differ = new ArrayList<>();
        expected.forEach((id, v) -> {
            if (!Arrays.equals(v, actual.get(id))) {
                differ.add(id);
            }
        });
        assertEquals(List.of(), differ);
    }
}
