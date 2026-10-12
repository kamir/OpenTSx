package org.opentsx.retrieval.pattern;

import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.EpisodeSummary;
import org.opentsx.model.v2.PatternMatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Whole-episode similarity ("which 10-minute blocks look like this one?"). Every episode is represented by its
 * z-normalized PAA with {@code points} values, so episodes of different lengths are comparable; the distance is the
 * Euclidean distance of these representations (or DTW on them).
 *
 * <p>Candidates are pruned exactly with SAX: MINDIST of the SAX words lower-bounds the Euclidean distance of the
 * representations, so candidates are verified best-first until the bound exceeds the k-th best distance. Words are
 * taken from {@code EpisodeSummary.sax} when they were stored with the same parameters ({@link #annotate}),
 * otherwise computed.
 */
public final class EpisodeSimilarity {

    /** Search statistics (for benchmarks). */
    public record Stats(int candidates, int verified) {
    }

    private final int points;
    private final int saxLength;
    private final int alphabet;
    private Stats stats = new Stats(0, 0);

    /** @param points representation length (e.g. 64); @param saxLength SAX word length (divides into points); @param alphabet SAX alphabet */
    public EpisodeSimilarity(int points, int saxLength, int alphabet) {
        if (saxLength > points) {
            throw new IllegalArgumentException("SAX word cannot be longer than the representation");
        }
        this.points = points;
        this.saxLength = saxLength;
        this.alphabet = alphabet;
    }

    public static EpisodeSimilarity defaults() {
        return new EpisodeSimilarity(64, 16, 8);
    }

    /** The z-normalized PAA representation of an episode. */
    public double[] representation(double[] values) {
        return ZNorm.of(values.length < points ? values : Paa.of(values, points));
    }

    public double[] representation(Episode e) {
        return representation(Episodes.values(e));
    }

    /** The SAX word stored in {@code EpisodeSummary.sax}. */
    public String sax(Episode e) {
        double[] rep = representation(e);
        return rep.length == points ? Sax.word(rep, saxLength, alphabet) : null;
    }

    /** Copy of the episode with {@code summary.sax} filled (write-time indexing). */
    public Episode annotate(Episode e) {
        String word = sax(e);
        if (word == null) {
            return e;
        }
        EpisodeSummary s = EpisodeSummary.newBuilder(e.getSummary()).setSax(word).build();
        return Episode.newBuilder(e).setSummary(s).build();
    }

    public List<PatternMatch> search(Episode query, Collection<Episode> candidates, Distance distance, int k) {
        return search(Episodes.values(query), query.getEpisodeId(), candidates, distance, k);
    }

    public List<PatternMatch> search(double[] queryValues, String excludeEpisodeId, Collection<Episode> candidates,
                                     Distance distance, int k) {
        double[] q = representation(queryValues);
        if (q.length != points) {
            throw new IllegalArgumentException("query needs at least " + points + " values");
        }
        String qWord = Sax.word(q, saxLength, alphabet);
        String prefix = saxLength + "x" + alphabet + ":";
        record Cand(Episode e, double lb) {
        }
        List<Cand> cands = new ArrayList<>();
        for (Episode e : candidates) {
            if (e.getEpisodeId().equals(excludeEpisodeId) || e.getCount() < points) {
                continue;
            }
            String w = e.getSummary().getSax() != null && e.getSummary().getSax().startsWith(prefix)
                    ? e.getSummary().getSax() : sax(e);
            // DTW can be smaller than the Euclidean distance, so the SAX bound only prunes for Euclidean
            double lb = distance.kind() == Distance.Kind.Z_EUCLIDEAN ? Sax.mindist(qWord, w, points) : 0;
            cands.add(new Cand(e, lb));
        }
        cands.sort(Comparator.comparingDouble(Cand::lb));
        List<PatternMatch> best = new ArrayList<>();
        int band = (int) Math.round(distance.band() * points);
        int verified = 0;
        for (Cand c : cands) {
            double kth = best.size() < k ? Double.POSITIVE_INFINITY : best.get(best.size() - 1).getDistance();
            if (c.lb() > kth) {
                break;
            }
            verified++;
            double[] r = representation(c.e());
            double d = distance.kind() == Distance.Kind.Z_EUCLIDEAN ? euclid(q, r) : Dtw.distance(q, r, band, kth);
            if (d < kth) {
                best.add(PatternMatch.newBuilder().setQueryId(qWord).setEpisodeId(c.e().getEpisodeId())
                        .setSeriesId(c.e().getSeries().getSeriesId()).setOffset(0).setLength(c.e().getCount())
                        .setDistance(d).setMetric(distance.label() + "@paa" + points).build());
                best.sort(Comparator.comparingDouble(PatternMatch::getDistance));
                if (best.size() > k) {
                    best.remove(best.size() - 1);
                }
            }
        }
        stats = new Stats(cands.size(), verified);
        return best;
    }

    public Stats stats() {
        return stats;
    }

    private static double euclid(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }
}
