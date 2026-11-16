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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Operator example segment.fixed-duration (docs/user-manual/17-operators.md): cuts fall on the full 10 minutes of the clock. */
class SegmentFixedDurationExampleTest {

    private static final long MIDNIGHT = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"), "m/s");

    @Test
    void anHourStartingAt0320IsCutAtEveryFullTenMinutes() throws IOException {
        Random r = new Random(1);
        double[] values = new double[3600];
        for (int i = 0; i < values.length; i++) {
            values[i] = 5 + 4 * i / 3600.0 + 0.6 * Math.sin(2 * Math.PI * i / 900.0) + 0.3 * r.nextGaussian();
        }
        long start = MIDNIGHT + 200 * SEC;
        List<Episode> episodes = new ArrayList<>();
        for (int s = 0; s < values.length; s += 600) {
            episodes.add(Episodes.regular(KEY, start + s * SEC, SEC, Arrays.copyOfRange(values, s, s + 600))
                    .source("example").build());
        }

        // usage:begin
        InMemorySeriesStore store = new InMemorySeriesStore();
        store.write(episodes);
        EpisodeService svc = new EpisodeService(store);
        List<Episode> segments = svc.segments(Segmenter.fixedDuration(Duration.ofMinutes(10)))
                .metric("wind.met.wind_speed")
                .fetch();
        // usage:end

        double[] startMinute = segments.stream().mapToDouble(e -> (Micros.of(e.getTStart()) - MIDNIGHT) / 60e6).toArray();
        double[] count = segments.stream().mapToDouble(Episode::getCount).toArray();
        assertArrayEquals(new double[]{200 / 60.0, 10, 20, 30, 40, 50, 60}, startMinute, 1e-9);
        assertArrayEquals(new double[]{400, 600, 600, 600, 600, 600, 200}, count);

        ExampleChart chart = new ExampleChart("segment.fixed-duration",
                "Fixed duration: cuts fall on full 10 minutes");
        double[] x = new double[600];
        double[] y = new double[600];
        for (int k = 0; k < 600; k++) {
            x[k] = (200 + 6 * k) / 60.0;
            y[k] = values[6 * k];
        }
        chart.panel("Input: 1 Hz from 00:03:20 for one hour", "minute after midnight", "m/s", "")
                .add("input", "line", x, y);
        chart.panel("Segments of PT10M, colors alternate", "minute after midnight", "m/s", "");
        for (int k = 0; k < segments.size(); k++) {
            double[] v = Episodes.values(segments.get(k));
            double[] sx = new double[v.length / 3];
            double[] sy = new double[v.length / 3];
            for (int i = 0; i < sx.length; i++) {
                sx[i] = startMinute[k] + 3 * i / 60.0;
                sy[i] = v[3 * i];
            }
            String name = k % 2 == 0 ? "odd segments" : "even segments";
            chart.add(name, "line", sx, sy).add(name, "line", new double[]{Double.NaN}, new double[]{Double.NaN});
        }
        chart.note("expected: starts 3.3, 10, 20, ..., 60")
                .note(String.format("measured: starts %.1f, %.0f, %.0f, ..., %.0f", startMinute[0], startMinute[1],
                        startMinute[2], startMinute[6]))
                .note(String.format("samples: %.0f, 5 x %.0f, %.0f", count[0], count[1], count[6]));
        chart.write();
    }
}
