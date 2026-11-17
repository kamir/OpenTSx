package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.wayang.basic.operators.MapOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.vector.VectorAligner;
import org.opentsx.retrieval.vector.VectorJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TASK-020 A3.1 on the broker: align.vector between the episode topic and a vector topic, three increments with the
 * state in the checkpoint. A tick is written once, when every series has passed it, and never again.
 */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxVectorIntegrationTest {

    private static final SeriesKey WIND = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p", "turbine", "T01"));
    private static final SeriesKey POWER = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "p", "turbine", "T01"));

    private static List<Episode> minutes(int from, int to) {
        List<Episode> out = new ArrayList<>();
        for (int m = from; m < to; m++) {
            long t = m * 60_000_000L;
            out.add(Episodes.irregular(WIND, new long[]{t}, new double[]{m}).build());
            out.add(Episodes.irregular(POWER, new long[]{t}, new double[]{100.0 * m}).build());
        }
        return out;
    }

    private static TsxVectorSink plan(TsxTopicSource source, TsxState state, String bootstrap, String topic) {
        Set<String> ids = new LinkedHashSet<>(List.of(WIND.getSeriesId(), POWER.getSeriesId()));
        TsxAlignVector align = new TsxAlignVector("v", "turbines", "dag", "a".repeat(64),
                new VectorAligner.Params(Duration.ofSeconds(60), "turbine", VectorAligner.Agg.MEAN, false,
                        Duration.ofSeconds(60), false), Map.of("raw", ids), 0, false, state);
        MapOperator<SpecificRecord, TsxTagged> tag = new MapOperator<>(r -> new TsxTagged("raw", (Episode) r),
                SpecificRecord.class, TsxTagged.class);
        TsxVectorSink sink = new TsxVectorSink(bootstrap, topic);
        source.connectTo(0, tag, 0);
        tag.connectTo(0, align, 0);
        align.connectTo(0, sink, 0);
        return sink;
    }

    @Test
    void everyTickIsWrittenOnceWhenEverySeriesHasPassedIt(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        String topic = k.layout.smartVectors("turbines");
        try (Admin admin = KafScaleClients.admin(k.bootstrap)) {
            k.layout.provisionSmartVectors(admin, "turbines", (short) 1);
        }
        Path ckpt = dir.resolve("ckpt.json");
        TsxIncrement.Plan p = (source, state) -> plan(source, state, k.bootstrap, topic);

        k.write(k.layout.episodes(), minutes(0, 10));
        TsxIncrement.run(k.config(ckpt, KafScaleFixture.EPISODE_ONLY), p);
        assertEquals(9, k.readAll(topic).size(), "minute 9 stays open: no series has passed it");

        TsxIncrement.run(k.config(ckpt, KafScaleFixture.EPISODE_ONLY), p);
        assertEquals(9, k.readAll(topic).size(), "an increment without data writes nothing");

        k.write(k.layout.episodes(), minutes(10, 11));
        TsxIncrement.run(k.config(ckpt, KafScaleFixture.EPISODE_ONLY), p);

        Set<Long> from = new TreeSet<>();
        for (ConsumerRecord<byte[], byte[]> r : k.readAll(topic)) {
            VectorJson.Row row = VectorJson.parse(r.value());
            assertNotNull(row);
            assertEquals(VectorJson.keyText(Map.of("turbine", "T01")), new String(r.key(), StandardCharsets.UTF_8));
            assertEquals(List.of("power_active", "wind_speed"), row.components());
            long minute = row.fromMicros() / 60_000_000L;
            assertEquals(100.0 * minute, row.values()[0]);
            assertEquals(minute, row.values()[1]);
            from.add(minute);
        }
        assertEquals(10, k.readAll(topic).size());
        assertEquals(new TreeSet<>(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)), from);
    }
}
