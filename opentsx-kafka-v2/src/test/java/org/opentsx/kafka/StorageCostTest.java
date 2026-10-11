package org.opentsx.kafka;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures bytes per stored value for episode payloads as a KafScale segment holds them: Avro single-object
 * records, batched (~512 KB) and zstd-compressed (level 3, Kafka's default). Prints a table; see 03-GAP-ANALYSE.
 */
class StorageCostTest {

    private final OpenTsxAvro avro = new OpenTsxAvro();

    @Test
    void bytesPerValue() {
        List<String> channels = List.of("wind_speed", "power_active", "rpm");
        // 10-min episodes at 1 Hz, 4 turbines, 6 episodes per series = 43,200 values
        List<Episode> quantized = WindData.episodes(4, channels, 6, 600, 11);
        double q = measure("1 Hz, sensor-resolution values, REGULAR", quantized);

        Random r = new Random(5);
        List<Episode> noisy = quantized.stream().map(e -> {
            double[] v = Episodes.values(e);
            for (int i = 0; i < v.length; i++) {
                v[i] += r.nextGaussian() * 1e-6; // full-precision doubles, e.g. derived values
            }
            return Episodes.regular(e.getSeries(), org.opentsx.model.Micros.of(e.getTStart()), 1_000_000L, v).build();
        }).toList();
        double n = measure("1 Hz, full-precision doubles, REGULAR", noisy);

        List<Episode> irregular = quantized.stream().map(e -> {
            long[] ts = Episodes.timestampsMicros(e);
            for (int i = 1; i < ts.length; i++) {
                ts[i] += r.nextInt(2_000); // up to 2 ms jitter
            }
            return Episodes.irregular(e.getSeries(), ts, Episodes.values(e)).build();
        }).toList();
        double i = measure("1 Hz with jitter, sensor resolution, IRREGULAR_DELTA", irregular);

        assertTrue(q < n, "quantized values must compress better than full-precision doubles");
        assertTrue(i < 8.5 && q < 8.5 && n < 9.5, "payload overhead must stay small");
    }

    private double measure(String label, List<Episode> episodes) {
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
        double perValue = (double) compressed / values;
        System.out.printf("[storage-cost] %-52s values=%,d raw=%.2f B/value zstd=%.2f B/value%n",
                label, values, (double) raw / values, perValue);
        return perValue;
    }
}
