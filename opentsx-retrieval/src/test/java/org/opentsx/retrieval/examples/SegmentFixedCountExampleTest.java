package org.opentsx.retrieval.examples;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.store.InMemorySeriesStore;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Operator example segment.fixed-count (docs/user-manual/17-operators.md): blocks of 256 samples with 50 % overlap. */
class SegmentFixedCountExampleTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.generator.speed", Map.of("turbine", "T01"), "rpm");

    @Test
    void blocksStartEvery128SamplesAndTheIncompleteTailIsDropped() throws IOException {
        Random r = new Random(2);
        double[] values = new double[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = 1200 + 30 * Math.sin(2 * Math.PI * i / 100.0) + 5 * r.nextGaussian();
        }
        List<Episode> episodes = new ArrayList<>();
        for (int s = 0; s < values.length; s += 300) {
            episodes.add(Episodes.regular(KEY, T0 + s * SEC, SEC, Arrays.copyOfRange(values, s, Math.min(values.length, s + 300)))
                    .source("example").build());
        }

        // usage:begin
        InMemorySeriesStore store = new InMemorySeriesStore();
        store.write(episodes);
        EpisodeService svc = new EpisodeService(store);
        List<Episode> blocks = svc.segments(Segmenter.fixedCount(256, 128))
                .metric("wind.generator.speed")
                .fetch();
        // usage:end

        double[] first = blocks.stream().mapToDouble(e -> (Micros.of(e.getTStart()) - T0) / (double) SEC).toArray();
        assertArrayEquals(new double[]{0, 128, 256, 384, 512, 640}, first);
        assertEquals(6, blocks.stream().filter(e -> e.getCount() == 256).count());
        int covered = (int) first[first.length - 1] + 256;
        assertEquals(896, covered);
        assertArrayEquals(Arrays.copyOfRange(values, 640, 896), Episodes.values(blocks.get(5)));

        ExampleChart chart = new ExampleChart("segment.fixed-count", "Fixed count: 256 samples, 50 % overlap");
        double[] x = new double[448];
        double[] y = new double[448];
        for (int k = 0; k < 448; k++) {
            x[k] = 2 * k;
            y[k] = values[2 * k];
        }
        double[] tx = new double[52];
        double[] ty = new double[52];
        for (int k = 0; k < 52; k++) {
            tx[k] = 896 + 2 * k;
            ty[k] = values[896 + 2 * k];
        }
        chart.panel("Input: 1000 samples, the last " + (1000 - covered) + " in no block", "sample", "rpm", "")
                .add("in a block", "line", x, y)
                .add("dropped tail", "line", tx, ty);
        chart.panel("Blocks", "sample", "block", "");
        for (int k = 0; k < blocks.size(); k++) {
            chart.add("in a block", "line", new double[]{first[k], first[k] + 255, Double.NaN},
                    new double[]{k + 1, k + 1, Double.NaN});
        }
        chart.note("expected: starts 0, 128, ..., 640")
                .note(String.format("measured: starts %.0f, %.0f, ..., %.0f", first[0], first[1], first[5]));
        chart.write();
    }
}
