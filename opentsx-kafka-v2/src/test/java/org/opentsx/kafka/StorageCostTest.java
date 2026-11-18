package org.opentsx.kafka;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.Ulid;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Provenance;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Measures bytes per stored value for episode payloads as a KafScale segment holds them: Avro single-object
 * records, batched (~512 KB) and zstd-compressed (level 3, Kafka's default). The figures are published in the module
 * README, docs/user-manual/04-kafscale-storage.md and docs/devops/sizing-plan.md; the test fails when one moves.
 */
class StorageCostTest {

    /** The published figures have two decimals. */
    private static final double ROUNDING = 0.005;

    private final OpenTsxAvro avro = new OpenTsxAvro();

    private record Cost(double raw, double zstd) {
    }

    @Test
    void bytesPerValue() {
        List<String> channels = List.of("wind_speed", "power_active", "rpm");
        // 10-min episodes at 1 Hz, 4 turbines, 6 episodes per series = 43,200 values
        List<Episode> quantized = WindData.episodes(4, channels, 6, 600, 11);
        Cost q = measure("1 Hz, sensor-resolution values, REGULAR", quantized);

        Random r = new Random(5);
        List<Episode> noisy = quantized.stream().map(e -> {
            double[] v = Episodes.values(e);
            for (int i = 0; i < v.length; i++) {
                v[i] += r.nextGaussian() * 1e-6; // full-precision doubles, e.g. derived values
            }
            return Episodes.regular(e.getSeries(), org.opentsx.model.Micros.of(e.getTStart()), 1_000_000L, v).build();
        }).toList();
        Cost n = measure("1 Hz, full-precision doubles, REGULAR", noisy);

        List<Episode> irregular = quantized.stream().map(e -> {
            long[] ts = Episodes.timestampsMicros(e);
            for (int i = 1; i < ts.length; i++) {
                ts[i] += r.nextInt(2_000); // up to 2 ms jitter
            }
            return Episodes.irregular(e.getSeries(), ts, Episodes.values(e)).build();
        }).toList();
        Cost i = measure("1 Hz with jitter, sensor resolution, IRREGULAR_DELTA", irregular);

        assertAll(
                () -> assertEquals(8.40, q.raw(), ROUNDING, "sensor resolution, raw"),
                () -> assertEquals(2.18, q.zstd(), ROUNDING, "sensor resolution, zstd"),
                () -> assertEquals(8.36, n.raw(), ROUNDING, "full-precision doubles, raw"),
                () -> assertEquals(6.96, n.zstd(), ROUNDING, "full-precision doubles, zstd"),
                () -> assertEquals(10.32, i.raw(), ROUNDING, "IRREGULAR_DELTA with jitter, raw"),
                () -> assertEquals(4.07, i.zstd(), ROUNDING, "IRREGULAR_DELTA with jitter, zstd"));
    }

    /** Pins episodeId and createdAt, which Draft.build takes from the clock, so the figures depend only on the seeds. */
    private Cost measure(String label, List<Episode> generated) {
        List<Episode> episodes = new ArrayList<>();
        for (int k = 0; k < generated.size(); k++) {
            Episode e = generated.get(k);
            long t = Micros.of(e.getTStart());
            episodes.add(Episode.newBuilder(e).setEpisodeId(Ulid.deterministic(t / 1000, k))
                    .setProvenance(Provenance.newBuilder(e.getProvenance()).setCreatedAt(Micros.toInstant(t)).build())
                    .build());
        }
        long values = 0;
        long raw = 0;
        long compressed = 0;
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        for (Episode e : episodes) {
            byte[] p = avro.encode(e);
            values += e.getCount();
            raw += p.length;
            batch.writeBytes(p);
            if (batch.size() >= 512 * 1024) {
                compressed += Zstd.compress(batch.toByteArray(), 3).length;
                batch.reset();
            }
        }
        if (batch.size() > 0) {
            compressed += Zstd.compress(batch.toByteArray(), 3).length;
        }
        Cost cost = new Cost((double) raw / values, (double) compressed / values);
        System.out.printf("[storage-cost] %-52s values=%,d raw=%.4f B/value zstd=%.4f B/value%n",
                label, values, cost.raw(), cost.zstd());
        return cost;
    }
}
