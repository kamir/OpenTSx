package org.opentsx.wayang;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** TASK-020 A1.4: a 1:1 pass-through plan on Wayang copies every episode of W, byte for byte. */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxPassThroughIntegrationTest {

    @Test
    void everyEpisodeArrivesOnceWithItsBytesAndASecondIncrementFindsNothing(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        List<Episode> w = KafScaleFixture.scenarioW();
        Map<String, byte[]> in = k.write(k.layout.episodes(), w);
        Path ckpt = dir.resolve("ckpt.json");

        TsxIncrement.Result first = TsxIncrement.run(k.config(ckpt, KafScaleFixture.EPISODE_ONLY),
                source -> connect(source, new TsxTopicSink(k.bootstrap, k.layout.clean())));
        assertEquals(w.size(), first.records());

        Map<String, byte[]> out = new HashMap<>();
        OpenTsxAvro avro = new OpenTsxAvro();
        for (ConsumerRecord<byte[], byte[]> r : k.readAll(k.layout.clean())) {
            out.put(avro.decode(r.value(), Episode.class).getEpisodeId(), r.value());
        }
        assertEquals(in.keySet(), out.keySet());
        assertEquals(w.size(), k.readAll(k.layout.clean()).size());
        in.forEach((id, bytes) -> assertArrayEquals(bytes, out.get(id), id));

        TsxIncrement.Result second = TsxIncrement.run(k.config(ckpt, KafScaleFixture.EPISODE_ONLY),
                source -> connect(source, new TsxTopicSink(k.bootstrap, k.layout.clean())));
        assertEquals(0, second.records());
        assertEquals(w.size(), k.readAll(k.layout.clean()).size());
    }

    static TsxTopicSink connect(TsxTopicSource source, TsxTopicSink sink) {
        source.connectTo(0, sink, 0);
        return sink;
    }
}
