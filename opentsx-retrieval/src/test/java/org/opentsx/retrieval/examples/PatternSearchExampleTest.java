package org.opentsx.retrieval.examples;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.PatternMatch;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.pattern.Distance;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operator example pattern.search (docs/user-manual/17-operators.md): planted copies are found, DTW also finds a slower copy. */
class PatternSearchExampleTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"), "m/s");

    private static double shape(double t) {
        return Math.sin(3 * Math.PI * t) * Math.exp(-3 * t) + 0.5 * t;
    }

    @Test
    void bothDistancesFindThePlantedCopiesAndDtwAlsoTheSlowerOne() throws IOException {
        Random r = new Random(1);
        double[] values = new double[4000];
        double walk = 0;
        for (int i = 0; i < values.length; i++) {
            walk += 0.1 * r.nextGaussian();
            values[i] = 8 + walk;
        }
        int[] planted = {600, 1500, 2400};
        for (int p : planted) {
            double level = values[p];
            for (int i = 0; i < 100; i++) {
                values[p + i] = level + 2 * shape(i / 99.0) + 0.05 * r.nextGaussian();
            }
        }
        double level = values[3200];
        for (int i = 0; i < 130; i++) {
            values[3200 + i] = level + 2 * shape(i / 129.0) + 0.05 * r.nextGaussian();
        }
        double[] pattern = new double[100];
        for (int i = 0; i < 100; i++) {
            pattern[i] = shape(i / 99.0);
        }
        List<Episode> episodes = new ArrayList<>();
        for (int s = 0; s < values.length; s += 600) {
            episodes.add(Episodes.regular(KEY, T0 + s * SEC, SEC, Arrays.copyOfRange(values, s, Math.min(values.length, s + 600)))
                    .source("example").build());
        }

        // usage:begin
        InMemorySeriesStore store = new InMemorySeriesStore();
        store.write(episodes);
        EpisodeService svc = new EpisodeService(store);
        List<PatternMatch> euclidean = svc.similarTo(pattern)
                .metric("wind.met.wind_speed")
                .topK(4)
                .fetch();
        List<PatternMatch> dtw = svc.similarTo(pattern)
                .metric("wind.met.wind_speed")
                .distance(Distance.dtw(0.2))
                .topK(4)
                .fetch();
        // usage:end

        double[] ed = sortedStarts(store, euclidean, 3);
        double[] dt = sortedStarts(store, dtw, 4);
        assertArrayEquals(new double[]{600, 1500, 2400}, ed, 2);
        assertArrayEquals(new double[]{600, 1500, 2400, 3200}, dt, 2);
        for (int k = 0; k < 3; k++) {
            assertTrue(euclidean.get(k).getDistance() < 1.5, "zED distance of a planted copy " + euclidean.get(k).getDistance());
        }
        double edFourth = euclidean.get(3).getDistance();
        assertTrue(edFourth > 3, "zED distance of the fourth match " + edFourth);
        assertTrue(Math.abs(start(store, euclidean.get(3)) - 3200) > 50, "the fourth zED match lies elsewhere");
        double dtwSlower = dtw.stream().filter(m -> Math.abs(start(store, m) - 3200) <= 2).findFirst().orElseThrow().getDistance();
        assertTrue(dtwSlower < 1.5, "DTW distance of the slower copy " + dtwSlower);
        assertEquals(4, dtw.size());

        double[] x = new double[400];
        double[] y = new double[400];
        for (int k = 0; k < 400; k++) {
            x[k] = 10 * k;
            y[k] = values[10 * k];
        }
        ExampleChart chart = new ExampleChart("pattern.search", "Pattern search: DTW also finds the slower copy");
        chart.panel("zED, top 4", "second", "m/s", "").add("input", "line", x, y);
        matches(chart, store, values, euclidean);
        chart.note("planted at 600, 1500, 2400, 30 % slower at 3200")
                .note(String.format("found 3 at d %.2f to %.2f", euclidean.get(0).getDistance(), euclidean.get(2).getDistance()))
                .note(String.format("4th at %.0f, d %.2f", start(store, euclidean.get(3)), edFourth));
        chart.panel("DTW band 0.2, top 4", "second", "m/s", "").add("input", "line", x, y);
        matches(chart, store, values, dtw);
        chart.note(String.format("found all 4 at d %.2f to %.2f", dtw.get(0).getDistance(), dtw.get(3).getDistance()))
                .note(String.format("slower copy at d %.2f", dtwSlower));
        chart.write();
    }

    private static double start(InMemorySeriesStore store, PatternMatch m) {
        Episode e = store.episode(m.getEpisodeId()).orElseThrow();
        return (Micros.of(e.getTStart()) - T0) / (double) SEC + m.getOffset();
    }

    private static double[] sortedStarts(InMemorySeriesStore store, List<PatternMatch> matches, int n) {
        return matches.stream().limit(n).mapToDouble(m -> start(store, m)).sorted().toArray();
    }

    private static void matches(ExampleChart chart, InMemorySeriesStore store, double[] values, List<PatternMatch> matches) {
        for (PatternMatch m : matches) {
            int s = (int) start(store, m);
            double[] mx = new double[m.getLength() + 1];
            double[] my = new double[m.getLength() + 1];
            for (int i = 0; i < m.getLength(); i++) {
                mx[i] = s + i;
                my[i] = values[s + i];
            }
            mx[m.getLength()] = Double.NaN;
            my[m.getLength()] = Double.NaN;
            chart.add("match", "line", mx, my);
        }
    }
}
