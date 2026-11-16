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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operator example pattern.similar-episodes (docs/user-manual/17-operators.md): the nearest episodes have the query's shape. */
class SimilarEpisodesExampleTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.turbine.power_active", Map.of("turbine", "T01"), "kW");
    private static final String[] SHAPES = {"ramp", "sine", "gust"};

    private static double shape(int kind, double t) {
        return switch (kind) {
            case 0 -> t;
            case 1 -> Math.sin(4 * Math.PI * t);
            default -> Math.exp(-0.5 * Math.pow((t - 0.5) / 0.08, 2));
        };
    }

    @Test
    void theNineNearestEpisodesToARampAreTheOtherNineRamps() throws IOException {
        Random r = new Random(1);
        List<Episode> episodes = new ArrayList<>();
        Map<String, Integer> kindOf = new HashMap<>();
        for (int k = 0; k < 30; k++) {
            double scale = 400 + 400 * r.nextDouble();
            double level = 1500 + 500 * r.nextDouble();
            double[] v = new double[600];
            for (int i = 0; i < v.length; i++) {
                v[i] = level + scale * shape(k % 3, i / 599.0) + 0.2 * scale * r.nextGaussian();
            }
            Episode e = Episodes.regular(KEY, T0 + k * 600 * SEC, SEC, v).source("example").build();
            episodes.add(e);
            kindOf.put(e.getEpisodeId(), k % 3);
        }
        Episode query = episodes.get(0);

        // usage:begin
        InMemorySeriesStore store = new InMemorySeriesStore();
        store.write(episodes);
        EpisodeService svc = new EpisodeService(store);
        List<PatternMatch> like = svc.similarEpisodes(query.getEpisodeId())
                .distance(Distance.zNormalizedEuclidean())
                .topK(9)
                .fetch();
        // usage:end

        assertEquals(9, like.size());
        assertTrue(like.stream().allMatch(m -> kindOf.get(m.getEpisodeId()) == 0), "the nine nearest are ramps");
        List<PatternMatch> all = svc.similarEpisodes(query.getEpisodeId()).topK(29).fetch();
        assertEquals(29, all.size());
        double farthestRamp = all.stream().filter(m -> kindOf.get(m.getEpisodeId()) == 0)
                .mapToDouble(PatternMatch::getDistance).max().orElseThrow();
        double nearestOther = all.stream().filter(m -> kindOf.get(m.getEpisodeId()) != 0)
                .mapToDouble(PatternMatch::getDistance).min().orElseThrow();
        assertTrue(farthestRamp < nearestOther / 2, "ramps up to " + farthestRamp + ", others from " + nearestOther);

        ExampleChart chart = new ExampleChart("pattern.similar-episodes", "Similar episodes: a ramp finds the ramps");
        chart.panel("Input: one of 10 episodes per shape", "second", "kW", "");
        for (int kind = 0; kind < 3; kind++) {
            double[] v = Episodes.values(episodes.get(kind));
            double[] x = new double[150];
            double[] y = new double[150];
            for (int i = 0; i < 150; i++) {
                x[i] = 4 * i;
                y[i] = v[4 * i];
            }
            chart.add(SHAPES[kind], "line", x, y);
        }
        chart.panel("Distance to the first ramp, by rank", "rank", "distance", "");
        for (int k = 0; k < all.size(); k++) {
            chart.add(SHAPES[kindOf.get(all.get(k).getEpisodeId())], "point", new double[]{k + 1},
                    new double[]{all.get(k).getDistance()});
        }
        chart.note("expected: ranks 1 to 9 are ramps")
                .note(String.format("measured: ramps at d %.1f or less, others %.1f or more", farthestRamp, nearestOther));
        chart.write();
    }
}
