package org.opentsx.retrieval;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.Ulid;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Downsampling of an episode into epoch-aligned buckets (a derived REGULAR episode; empty buckets are NaN). */
public final class Resampling {

    public enum Agg { MEAN, MIN, MAX, FIRST, LAST, SUM, COUNT }

    private Resampling() {
    }

    public static Episode resample(Episode e, Duration every, Agg agg) {
        long d = every.toNanos() / 1000;
        if (d <= 0) {
            throw new IllegalArgumentException("resampling interval must be positive");
        }
        long[] ts = Episodes.timestampsMicros(e);
        double[] vs = Episodes.values(e);
        long first = Math.floorDiv(Micros.of(e.getTStart()), d) * d;
        long last = Math.floorDiv(Micros.of(e.getTEnd()) - 1, d) * d;
        int n = (int) ((last - first) / d) + 1;
        double[] acc = new double[n];
        long[] cnt = new long[n];
        java.util.Arrays.fill(acc, Double.NaN);
        for (int i = 0; i < ts.length; i++) {
            double v = vs[i];
            int b = (int) ((Math.floorDiv(ts[i], d) * d - first) / d);
            if (agg == Agg.COUNT) {
                if (!Double.isNaN(v)) {
                    acc[b] = Double.isNaN(acc[b]) ? 1 : acc[b] + 1;
                }
                continue;
            }
            if (Double.isNaN(v)) {
                continue;
            }
            cnt[b]++;
            double a = acc[b];
            acc[b] = switch (agg) {
                case MEAN -> Double.isNaN(a) ? v : a + (v - a) / cnt[b];
                case MIN -> Double.isNaN(a) ? v : Math.min(a, v);
                case MAX -> Double.isNaN(a) ? v : Math.max(a, v);
                case FIRST -> Double.isNaN(a) ? v : a;
                case LAST -> v;
                case SUM -> Double.isNaN(a) ? v : a + v;
                case COUNT -> throw new IllegalStateException();
            };
        }
        if (agg == Agg.COUNT) {
            for (int b = 0; b < n; b++) {
                if (Double.isNaN(acc[b])) {
                    acc[b] = 0;
                }
            }
        }
        String tag = every + "/" + agg;
        long seed = XxHash64.hash((e.getEpisodeId() + "|resample|" + tag).getBytes(StandardCharsets.UTF_8));
        Episodes.Draft draft = Episodes.regular(e.getSeries(), first, d, acc)
                .episodeId(Ulid.deterministic(Math.floorDiv(first, 1000L), seed))
                .source(e.getProvenance().getSource())
                .producer("opentsx-retrieval/resampling")
                .parentEpisodeId(e.getEpisodeId())
                .createdAt(first + (long) n * d)
                .label("resample", tag);
        e.getLabels().forEach((k, v) -> draft.label(k.toString(), v.toString()));
        draft.label("resample", tag);
        return draft.build();
    }
}
