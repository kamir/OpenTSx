package org.opentsx.retrieval.pattern;

import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.PatternMatch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Exact top-k subsequence search over runs (series), returning non-overlapping matches.
 *
 * <ul>
 *   <li>z-normalized Euclidean: MASS distance profile per run, O(n log n).</li>
 *   <li>DTW: UCR-suite cascade per window - running z-normalization, LB_Kim, LB_Keogh, early-abandoning DTW against
 *       the current k-th best distance.</li>
 * </ul>
 * Matches of one series may not overlap by more than half the query length (exclusion zone); a better match
 * replaces overlapping worse ones. Windows containing NaN are skipped.
 */
public final class PatternSearch {

    /** Search statistics (for benchmarks). */
    public record Stats(long windows, long prunedKim, long prunedKeogh, long dtwComputed) {
    }

    private record Hit(Run run, int pos, double distance) {
    }

    private final double[] query;
    private final Distance distance;
    private final int k;
    private final String queryId;
    private final List<Hit> best = new ArrayList<>();
    private long windows;
    private long prunedKim;
    private long prunedKeogh;
    private long dtw;

    public PatternSearch(double[] query, Distance distance, int topK) {
        if (query.length < 4) {
            throw new IllegalArgumentException("query must have at least 4 points");
        }
        for (double v : query) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("query must be finite");
            }
        }
        this.query = query.clone();
        this.distance = distance;
        this.k = topK;
        ByteBuffer b = ByteBuffer.allocate(query.length * 8);
        for (double v : query) {
            b.putDouble(v);
        }
        this.queryId = XxHash64.hex(XxHash64.hash(b.array()));
    }

    public String queryId() {
        return queryId;
    }

    public List<PatternMatch> search(List<Run> runs) {
        for (Run r : runs) {
            if (r.length() < query.length) {
                continue;
            }
            if (distance.kind() == Distance.Kind.Z_EUCLIDEAN) {
                double[] profile = Mass.distanceProfile(query, r.values);
                for (int i = 0; i < profile.length; i++) {
                    windows++;
                    offer(r, i, profile[i]);
                }
            } else {
                searchDtw(r);
            }
        }
        List<PatternMatch> out = new ArrayList<>();
        for (Hit h : best) {
            out.add(PatternMatch.newBuilder()
                    .setQueryId(queryId)
                    .setEpisodeId(h.run().episodeIds[h.pos()])
                    .setSeriesId(h.run().seriesId)
                    .setOffset(h.run().offsets[h.pos()])
                    .setLength(query.length)
                    .setDistance(h.distance())
                    .setMetric(distance.label())
                    .build());
        }
        return out;
    }

    public Stats stats() {
        return new Stats(windows, prunedKim, prunedKeogh, dtw);
    }

    private double threshold() {
        return best.size() < k ? Double.POSITIVE_INFINITY : best.get(best.size() - 1).distance();
    }

    private void searchDtw(Run r) {
        int m = query.length;
        int band = (int) Math.round(distance.band() * m);
        double[] zq = ZNorm.of(query);
        double[][] env = Dtw.envelope(zq, band);
        double[] x = r.values;
        double[] window = new double[m];
        for (int i = 0; i + m <= x.length; i++) {
            windows++;
            boolean nan = false;
            for (int j = 0; j < m; j++) {
                window[j] = x[i + j];
                nan |= Double.isNaN(window[j]);
            }
            if (nan) {
                continue;
            }
            double[] zc = ZNorm.of(window);
            double cutoff = threshold();
            if (Dtw.lbKim(zq, zc) > cutoff) {
                prunedKim++;
                continue;
            }
            if (Dtw.lbKeogh(env, zc, cutoff) > cutoff) {
                prunedKeogh++;
                continue;
            }
            dtw++;
            double d = Dtw.distance(zq, zc, band, cutoff);
            if (d < Double.POSITIVE_INFINITY) {
                offer(r, i, d);
            }
        }
    }

    /** Keeps the k best non-overlapping hits. */
    private void offer(Run r, int pos, double d) {
        if (!(d < threshold())) {
            return;
        }
        int exclusion = query.length / 2;
        List<Hit> overlapping = new ArrayList<>();
        for (Hit h : best) {
            if (h.run() == r && Math.abs(h.pos() - pos) < Math.max(1, exclusion)) {
                if (h.distance() <= d) {
                    return;
                }
                overlapping.add(h);
            }
        }
        best.removeAll(overlapping);
        best.add(new Hit(r, pos, d));
        best.sort(Comparator.comparingDouble(Hit::distance));
        while (best.size() > k) {
            best.remove(best.size() - 1);
        }
    }
}
