package org.opentsx.retrieval.pattern;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.PatternMatch;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic benchmark: a gust shape is planted (scaled, offset, optionally time-warped, with noise) into simulated
 * 1 Hz wind speed of several turbines; the search must find the planted positions. Writes
 * {@code target/pattern-benchmark.md} (recall@k and latency).
 */
class PatternBenchmarkTest {

    private record Planted(String seriesId, int index) {
    }

    /** Wind speed of 4 turbines, 8 h at 1 Hz. */
    private static List<Episode> wind() {
        Scenario scenario = new Scenario("bench", 21, Instant.parse("2026-10-01T00:00:00Z"), Duration.ofHours(8),
                Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", "bench", 54, 13, "GENERIC-3MW", 1, 4, 5, 7, 0.05),
                Scenario.WindSpec.DEFAULT, List.of(), List.of());
        List<Episode> wind = new ArrayList<>();
        new WindparkSimulator(scenario, ChannelCatalog.standard()).run(e -> {
            if (e.getSeries().getMetric().equals("wind.met.wind_speed")) {
                wind.add(e);
            }
        });
        return wind;
    }

    /** Success criterion 7 of TASK-011 (D87): 10 planted gusts, the top 10 of similarTo with z-ED, recall at least 0.95. */
    @Test
    void recallAtTenMeetsTheSuccessCriterion() {
        double[] shape = PatternEngineTest.gust(90);
        List<Planted> planted = new ArrayList<>();
        List<Episode> data = plant(wind(), shape, 0, 0.05, new Random(87), planted, 10);
        assertEquals(10, planted.size());
        List<PatternMatch> hits = new PatternSearch(shape, Distance.zNormalizedEuclidean(), 10).search(Run.of(data));
        assertEquals(10, hits.size());
        double recall = recall(planted, hits, data, 10);
        assertTrue(recall >= 0.95, "recall@10 = " + recall);
    }

    @Test
    void recallAndLatencyOnSimulatedWindData() throws IOException {
        List<Episode> wind = wind();
        double[] shape = PatternEngineTest.gust(90);
        StringBuilder report = new StringBuilder("# Pattern search benchmark\n\n");
        report.append("4 turbines x 8 h at 1 Hz (115,200 samples), gust shape of 90 s planted 12 times, scale 1.5-3 m/s.\n\n");
        report.append("| distance | warp | noise (m/s) | recall@12 | latency ms | windows | pruned |\n|---|---|---|---|---|---|---|\n");
        Map<String, Double> recalls = new LinkedHashMap<>();
        for (double warp : new double[]{0, 0.25}) {
            for (double noise : new double[]{0.05, 0.2, 0.4}) {
                Random r = new Random(Double.hashCode(warp) * 31 + Double.hashCode(noise));
                List<Planted> planted = new ArrayList<>();
                List<Episode> data = plant(wind, shape, warp, noise, r, planted, 12);
                List<Run> runs = Run.of(data);
                for (Distance d : List.of(Distance.zNormalizedEuclidean(), Distance.dtw(0.25))) {
                    PatternSearch s = new PatternSearch(shape, d, planted.size());
                    long t0 = System.nanoTime();
                    List<PatternMatch> hits = s.search(runs);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    double recall = recall(planted, hits, data, 10);
                    recalls.put(d.label() + "/" + warp + "/" + noise, recall);
                    PatternSearch.Stats st = s.stats();
                    report.append(String.format(Locale.ROOT, "| %s | %.0f %% | %.2f | %.2f | %.0f | %d | %d |%n",
                            d.label(), warp * 100, noise, recall, ms, st.windows(), st.prunedKim() + st.prunedKeogh()));
                }
            }
        }
        Path out = Path.of("target", "pattern-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report);

        // low noise: both find every planted gust; with strong time warping DTW keeps up, z-ED drops
        assertEquals(1.0, recalls.get("zED/0.0/0.05"), report::toString);
        assertEquals(1.0, recalls.get("DTW(w=0.25)/0.0/0.05"), report::toString);
        assertEquals(1.0, recalls.get("DTW(w=0.25)/0.25/0.05"), report::toString);
        assertTrue(recalls.get("DTW(w=0.25)/0.25/0.05") >= recalls.get("zED/0.25/0.05"), report::toString);
        // with noise, recall degrades (documented in target/pattern-benchmark.md); z-ED stays usable at 0.2 m/s
        assertTrue(recalls.get("zED/0.0/0.2") >= 0.75, report::toString);
    }

    /** Plants up to 3 gusts per series, {@code count} in all. */
    private static List<Episode> plant(List<Episode> wind, double[] shape, double warp, double noise, Random r,
                                       List<Planted> planted, int count) {
        Map<String, List<Episode>> bySeries = new LinkedHashMap<>();
        wind.forEach(e -> bySeries.computeIfAbsent(e.getSeries().getSeriesId(), k -> new ArrayList<>()).add(e));
        List<Episode> out = new ArrayList<>();
        for (Map.Entry<String, List<Episode>> en : bySeries.entrySet()) {
            double[] x = en.getValue().stream().flatMapToDouble(e -> Arrays.stream(Episodes.values(e))).toArray();
            for (int p = 0; p < 3 && planted.size() < count; p++) {
                int at = 2000 + p * 9000 + r.nextInt(5000);
                int len = (int) Math.round(shape.length * (1 + warp));
                double scale = 1.5 + r.nextDouble() * 1.5;
                double base = x[at];
                for (int i = 0; i < len; i++) {
                    double src = (double) i * (shape.length - 1) / (len - 1);
                    int lo = (int) src;
                    int hi = Math.min(shape.length - 1, lo + 1);
                    x[at + i] = base + scale * (shape[lo] + (shape[hi] - shape[lo]) * (src - lo)) + r.nextGaussian() * noise;
                }
                planted.add(new Planted(en.getKey(), at));
            }
            int k = 0;
            for (Episode e : en.getValue()) {
                double[] v = Arrays.copyOfRange(x, k, k + e.getCount());
                out.add(Episodes.regular(e.getSeries(), Micros.of(e.getTStart()), e.getIntervalMicros(), v)
                        .episodeId(e.getEpisodeId()).source("bench").build());
                k += e.getCount();
            }
        }
        return out;
    }

    private static double recall(List<Planted> planted, List<PatternMatch> hits, List<Episode> data, int tolerance) {
        Map<String, Integer> startOf = new HashMap<>();
        Map<String, Integer> pos = new HashMap<>();
        for (Episode e : data) {
            String s = e.getSeries().getSeriesId();
            int base = pos.getOrDefault(s, 0);
            startOf.put(e.getEpisodeId(), base);
            pos.put(s, base + e.getCount());
        }
        int found = 0;
        for (Planted p : planted) {
            for (PatternMatch m : hits) {
                if (m.getSeriesId().equals(p.seriesId())
                        && Math.abs(startOf.get(m.getEpisodeId()) + m.getOffset() - p.index()) <= tolerance) {
                    found++;
                    break;
                }
            }
        }
        return (double) found / planted.size();
    }
}
