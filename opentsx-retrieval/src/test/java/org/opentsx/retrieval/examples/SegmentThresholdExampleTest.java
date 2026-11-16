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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operator example segment.threshold (docs/user-manual/17-operators.md): hysteresis turns three noisy phases into three segments. */
class SegmentThresholdExampleTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"), "m/s");

    @Test
    void hysteresisGivesOneSegmentPerPhaseWhereASingleLevelChatters() throws IOException {
        Random r = new Random(1);
        int[] from = {600, 1500, 2700};
        int[] to = {900, 2100, 3000};
        double[] values = new double[3600];
        for (int i = 0; i < values.length; i++) {
            boolean storm = (i >= 600 && i < 900) || (i >= 1500 && i < 2100) || (i >= 2700 && i < 3000);
            values[i] = (storm ? 21.5 : 15) + 0.7 * r.nextGaussian();
        }
        List<Episode> episodes = new ArrayList<>();
        for (int s = 0; s < values.length; s += 600) {
            episodes.add(Episodes.regular(KEY, T0 + s * SEC, SEC, Arrays.copyOfRange(values, s, s + 600))
                    .source("example").build());
        }

        // usage:begin
        InMemorySeriesStore store = new InMemorySeriesStore();
        store.write(episodes);
        EpisodeService svc = new EpisodeService(store);
        Segmenter hysteresis = Segmenter.threshold(Segmenter.Direction.ABOVE, 20, 18,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        Segmenter oneLevel = Segmenter.threshold(Segmenter.Direction.ABOVE, 20, 20,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        List<Episode> phases = svc.segments(hysteresis).metric("wind.met.wind_speed").fetch();
        List<Episode> chatter = svc.segments(oneLevel).metric("wind.met.wind_speed").fetch();
        // usage:end

        assertEquals(3, phases.size());
        long[] start = new long[3];
        for (int k = 0; k < 3; k++) {
            start[k] = (Micros.of(phases.get(k).getTStart()) - T0) / SEC;
            assertTrue(Math.abs(start[k] - from[k]) <= 2, "phase " + k + " starts at " + from[k] + ", segment at " + start[k]);
            assertTrue(Math.abs(phases.get(k).getCount() - (to[k] - from[k])) <= 2, "phase " + k + ": " + phases.get(k).getCount());
        }
        assertTrue(chatter.size() > 10, "segments without hysteresis: " + chatter.size());

        double[] x = new double[600];
        double[] y = new double[600];
        for (int k = 0; k < 600; k++) {
            x[k] = 6 * k / 60.0;
            y[k] = values[6 * k];
        }
        ExampleChart chart = new ExampleChart("segment.threshold", "Threshold: hysteresis stops the chatter");
        chart.panel("Input: 3 storm phases with noise", "minute", "m/s", "")
                .add("input", "line", x, y)
                .add("enter 20", "line", new double[]{0, 60}, new double[]{20, 20})
                .add("exit 18", "line", new double[]{0, 60}, new double[]{18, 18});
        double[][] a = steps(phases);
        double[][] b = steps(chatter);
        chart.panel("enter 20, exit 18", "minute", "segments so far", "").add("segments so far", "step", a[0], a[1]);
        chart.note("expected: 3 segments")
                .note(String.format("measured: %d, of %d, %d, %d s", phases.size(), phases.get(0).getCount(),
                        phases.get(1).getCount(), phases.get(2).getCount()));
        chart.panel("enter 20, exit 20", "minute", "segments so far", "").add("segments so far", "step", b[0], b[1]);
        chart.note("measured: " + chatter.size() + " segments");
        chart.write();
    }

    private static double[][] steps(List<Episode> segments) {
        double[] x = new double[segments.size() + 2];
        double[] y = new double[segments.size() + 2];
        for (int k = 0; k < segments.size(); k++) {
            x[k + 1] = (Micros.of(segments.get(k).getTStart()) - T0) / 60e6;
            y[k + 1] = k + 1;
        }
        x[segments.size() + 1] = 60;
        y[segments.size() + 1] = segments.size();
        return new double[][]{x, y};
    }
}
