package org.opentsx.retrieval.pattern;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.PatternMatch;
import org.opentsx.model.v2.SeriesKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternEngineTest {

    static final long T0 = 1_790_000_000_000_000L;
    static final long SEC = 1_000_000L;
    static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p1", "turbine", "T01"), "m/s");

    static double zed(double[] a, double[] b) {
        double[] x = ZNorm.of(a);
        double[] y = ZNorm.of(b);
        double s = 0;
        for (int i = 0; i < x.length; i++) {
            s += (x[i] - y[i]) * (x[i] - y[i]);
        }
        return Math.sqrt(s);
    }

    static double[] randomWalk(Random r, int n) {
        double[] x = new double[n];
        double v = 0;
        for (int i = 0; i < n; i++) {
            v += r.nextGaussian();
            x[i] = v;
        }
        return x;
    }

    @Test
    void massEqualsBruteForce() {
        Random r = new Random(1);
        double[] series = randomWalk(r, 3000);
        series[1234] = Double.NaN;
        for (int m : new int[]{8, 64, 333}) {
            double[] q = randomWalk(r, m);
            double[] p = Mass.distanceProfile(q, series);
            assertEquals(series.length - m + 1, p.length);
            for (int i = 0; i < p.length; i += 7) {
                double[] w = Arrays.copyOfRange(series, i, i + m);
                boolean nan = Arrays.stream(w).anyMatch(Double::isNaN);
                double want = nan ? Double.POSITIVE_INFINITY : zed(q, w);
                assertEquals(want, p[i], 1e-6, "m=" + m + " i=" + i);
            }
        }
        double[] flat = new double[100];
        Arrays.fill(flat, 3.0);
        double[] p = Mass.distanceProfile(new double[]{1, 2, 3, 4}, flat);
        assertEquals(2.0, p[0], 1e-12, "constant window vs varying query = sqrt(m)");
    }

    @Test
    void lowerBoundsHold() {
        Random r = new Random(2);
        for (int t = 0; t < 300; t++) {
            int n = 64;
            double[] a = ZNorm.of(randomWalk(r, n));
            double[] b = ZNorm.of(randomWalk(r, n));
            double ed = Dtw.distance(a, b, 0, Double.POSITIVE_INFINITY);
            assertEquals(zed(a, b), ed, 1e-9, "band 0 is Euclidean");
            int band = 6;
            double dtw = Dtw.distance(a, b, band, Double.POSITIVE_INFINITY);
            assertTrue(dtw <= ed + 1e-9);
            assertTrue(Dtw.lbKim(a, b) <= dtw + 1e-9);
            assertTrue(Dtw.lbKeogh(Dtw.envelope(a, band), b, Double.POSITIVE_INFINITY) <= dtw + 1e-9);
            assertTrue(Sax.mindist(Sax.word(a, 16, 8), Sax.word(b, 16, 8), n) <= ed + 1e-9);
            assertEquals(Double.POSITIVE_INFINITY, Dtw.distance(a, b, band, dtw * 0.5), "early abandon");
        }
        assertEquals(0.0, Sax.inverseNormal(0.5), 1e-12);
        assertEquals(1.959963985, Sax.inverseNormal(0.975), 1e-8);
    }

    /** Random walk with a planted shape (optionally time-warped, scaled, offset, noisy) at known positions. */
    static double[] planted(Random r, int n, double[] shape, int[] at, double warp, double noise) {
        double[] x = randomWalk(r, n);
        for (int p : at) {
            int len = (int) Math.round(shape.length * (1 + warp));
            double scale = 2 + r.nextDouble() * 3;
            double offset = x[p];
            for (int i = 0; i < len && p + i < n; i++) {
                double src = (double) i * (shape.length - 1) / (len - 1);
                int lo = (int) Math.floor(src);
                int hi = Math.min(shape.length - 1, lo + 1);
                double v = shape[lo] + (shape[hi] - shape[lo]) * (src - lo);
                x[p + i] = offset + scale * v + r.nextGaussian() * noise;
            }
        }
        return x;
    }

    static double[] gust(int m) {
        double[] s = new double[m];
        for (int i = 0; i < m; i++) {
            double t = (double) i / (m - 1);
            s[i] = Math.sin(3 * Math.PI * t) * Math.exp(-3 * t) + 0.5 * t;
        }
        return s;
    }

    static List<Episode> episodes(double[] x, int chunk) {
        List<Episode> out = new ArrayList<>();
        for (int s = 0; s < x.length; s += chunk) {
            out.add(Episodes.regular(KEY, T0 + s * SEC, SEC, Arrays.copyOfRange(x, s, Math.min(x.length, s + chunk)))
                    .source("test").build());
        }
        return out;
    }

    static int absolute(List<Episode> eps, PatternMatch m) {
        int base = 0;
        for (Episode e : eps) {
            if (e.getEpisodeId().equals(m.getEpisodeId())) {
                return base + m.getOffset();
            }
            base += e.getCount();
        }
        throw new AssertionError("episode not found");
    }

    @Test
    void plantedPatternsAreFoundAcrossEpisodeBoundaries() {
        Random r = new Random(3);
        double[] shape = gust(120);
        int[] at = {1500, 5990, 9000, 14_350, 18_000};   // 5990 crosses an episode boundary (600)
        double[] x = planted(r, 20_000, shape, at, 0, 0.05);
        List<Episode> eps = episodes(x, 600);
        PatternSearch s = new PatternSearch(shape, Distance.zNormalizedEuclidean(), 5);
        List<PatternMatch> hits = s.search(Run.of(eps));
        assertEquals(5, hits.size());
        int[] found = hits.stream().mapToInt(m -> absolute(eps, m)).sorted().toArray();
        for (int k = 0; k < at.length; k++) {
            assertTrue(Math.abs(found[k] - at[k]) <= 2, Arrays.toString(found));
        }
        assertTrue(hits.get(0).getDistance() < 1.0);
        assertEquals("zED", hits.get(0).getMetric());
    }

    @Test
    void dtwFindsTimeWarpedPatternsAndPrunes() {
        Random r = new Random(4);
        double[] shape = gust(100);
        int[] at = {2000, 7000, 12_000};
        double[] x = planted(r, 15_000, shape, at, 0.12, 0.05);   // 12 % slower
        List<Episode> eps = episodes(x, 600);
        List<Run> runs = Run.of(eps);
        PatternSearch ed = new PatternSearch(shape, Distance.zNormalizedEuclidean(), 3);
        PatternSearch dtw = new PatternSearch(shape, Distance.dtw(0.15), 3);
        List<PatternMatch> e = ed.search(runs);
        List<PatternMatch> d = dtw.search(runs);
        for (PatternMatch m : d) {
            assertTrue(m.getDistance() < e.get(e.size() - 1).getDistance() + 1e-9);
        }
        int[] found = d.stream().mapToInt(m -> absolute(eps, m)).sorted().toArray();
        for (int k = 0; k < at.length; k++) {
            assertTrue(Math.abs(found[k] - at[k]) <= 8, Arrays.toString(found));
        }
        PatternSearch.Stats st = dtw.stats();
        assertTrue(st.prunedKim() + st.prunedKeogh() > st.windows() / 2, st.toString());
    }

    @Test
    void episodeSimilarityWithSaxPruningIsExact() {
        Random r = new Random(5);
        EpisodeSimilarity sim = EpisodeSimilarity.defaults();
        List<Episode> eps = new ArrayList<>();
        for (int k = 0; k < 400; k++) {
            Episode e = Episodes.regular(KEY, T0 + k * 600 * SEC, SEC, randomWalk(r, 600)).source("t").build();
            eps.add(k % 2 == 0 ? sim.annotate(e) : e);    // half with stored SAX words
        }
        Episode query = eps.get(17);
        List<PatternMatch> hits = sim.search(query, eps, Distance.zNormalizedEuclidean(), 5);
        // brute force
        double[] q = sim.representation(query);
        List<double[]> all = new ArrayList<>();
        for (Episode e : eps) {
            if (e != query) {
                double[] c = sim.representation(e);
                double s = 0;
                for (int i = 0; i < q.length; i++) {
                    s += (q[i] - c[i]) * (q[i] - c[i]);
                }
                all.add(new double[]{Math.sqrt(s)});
            }
        }
        all.sort(Comparator.comparingDouble(a -> a[0]));
        for (int i = 0; i < 5; i++) {
            assertEquals(all.get(i)[0], hits.get(i).getDistance(), 1e-9);
        }
        assertTrue(sim.stats().verified() < sim.stats().candidates() / 2, sim.stats().toString());
        assertTrue(eps.get(0).getSummary().getSax().startsWith("16x8:"));
    }
}
