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

/** Operator example segment.extremum (docs/user-manual/17-operators.md): exactly the three planted gusts are found. */
class SegmentExtremumExampleTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"), "m/s");

    @Test
    void prominenceTwoKeepsTheThreePlantedGustsAndDropsTheNoise() throws IOException {
        Random r = new Random(1);
        int[] planted = {600, 1500, 2400};
        double[] heights = {4, 5, 6};
        double[] values = new double[3000];
        for (int i = 0; i < values.length; i++) {
            values[i] = 8 + 0.25 * r.nextGaussian();
            for (int p = 0; p < planted.length; p++) {
                values[i] += heights[p] * Math.exp(-0.5 * Math.pow((i - planted[p]) / 10.0, 2));
            }
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
        Segmenter gusts = Segmenter.extremum(Segmenter.Feature.MAX, 2.0, Duration.ofMinutes(5), Duration.ofMinutes(2),
                Duration.ofSeconds(60), Duration.ofSeconds(90));
        Segmenter everyMaximum = Segmenter.extremum(Segmenter.Feature.MAX, 0.0, Duration.ofMinutes(5),
                Duration.ofMinutes(2), Duration.ofSeconds(60), Duration.ofSeconds(90));
        List<Episode> found = svc.segments(gusts).metric("wind.met.wind_speed").fetch();
        List<Episode> unfiltered = svc.segments(everyMaximum).metric("wind.met.wind_speed").fetch();
        // usage:end

        assertEquals(3, found.size());
        long[] anchor = found.stream().mapToLong(e -> (Micros.of(e.getSegmentation().getAnchorTs()) - T0) / SEC).toArray();
        for (int k = 0; k < 3; k++) {
            assertTrue(Math.abs(anchor[k] - planted[k]) <= 5, "gust " + k + " planted at " + planted[k] + ", found at " + anchor[k]);
            assertEquals(151, found.get(k).getCount(), "60 s before to 90 s after the peak");
        }
        assertTrue(unfiltered.size() >= 10, "noise maxima without prominence: " + unfiltered.size());
        long[] all = unfiltered.stream().mapToLong(e -> (Micros.of(e.getSegmentation().getAnchorTs()) - T0) / SEC).toArray();
        for (long a : anchor) {
            assertTrue(Arrays.stream(all).anyMatch(b -> b == a), "prominence 0 keeps the gust at " + a);
        }

        double[] x = new double[600];
        double[] y = new double[600];
        for (int k = 0; k < 600; k++) {
            x[k] = 5 * k / 60.0;
            y[k] = values[5 * k];
        }
        ExampleChart chart = new ExampleChart("segment.extremum", "Extremum: prominence 2 keeps the 3 gusts");
        chart.panel("Prominence 0: " + unfiltered.size() + " segments", "minute", "m/s", "").add("input", "line", x, y);
        segments(chart, unfiltered);
        chart.panel("Prominence 2: " + found.size() + " segments", "minute", "m/s", "").add("input", "line", x, y);
        segments(chart, found);
        chart.note("expected: peaks at 600, 1500, 2400 s")
                .note(String.format("measured: %d, %d, %d s", anchor[0], anchor[1], anchor[2]));
        chart.write();
    }

    private static void segments(ExampleChart chart, List<Episode> segments) {
        for (Episode e : segments) {
            long from = (Micros.of(e.getTStart()) - T0) / SEC;
            double[] v = Episodes.values(e);
            double[] sx = new double[v.length + 1];
            double[] sy = new double[v.length + 1];
            for (int i = 0; i < v.length; i++) {
                sx[i] = (from + i) / 60.0;
                sy[i] = v[i];
            }
            sx[v.length] = Double.NaN;
            sy[v.length] = Double.NaN;
            long peak = (Micros.of(e.getSegmentation().getAnchorTs()) - T0) / SEC;
            chart.add("segment", "line", sx, sy)
                    .add("peak", "point", new double[]{peak / 60.0}, new double[]{v[(int) (peak - from)]});
        }
    }
}
