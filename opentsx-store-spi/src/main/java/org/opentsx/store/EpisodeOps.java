package org.opentsx.store;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.Ulid;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.TimeEncoding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Operations on episodes that stores and query layers share. */
public final class EpisodeOps {

    /** Read order of {@link SeriesStore#read}. */
    public static final Comparator<Episode> READ_ORDER = Comparator.comparing(Episode::getTStart)
            .thenComparing(e -> e.getSeries().getSeriesId())
            .thenComparing(Episode::getEpisodeId);

    private EpisodeOps() {
    }

    public static boolean overlaps(Episode e, long fromMicros, long toMicros) {
        return Micros.of(e.getTStart()) < toMicros && Micros.of(e.getTEnd()) > fromMicros;
    }

    /**
     * The part of an episode inside [fromMicros, toMicros), or null if no value lies inside.
     * Returns the episode itself if it lies completely inside; otherwise a new episode with the window as its bounds,
     * parentEpisodeId = original, the createdAt of the original, and an id derived from the original id and the bounds
     * of the part, so the same clip of the same episode is equal on every read.
     */
    public static Episode clip(Episode e, long fromMicros, long toMicros) {
        long tStart = Micros.of(e.getTStart());
        long tEnd = Micros.of(e.getTEnd());
        if (tStart >= fromMicros && tEnd <= toMicros) {
            return e;
        }
        long[] ts = Episodes.timestampsMicros(e);
        double[] v = Episodes.values(e);
        int lo = 0;
        while (lo < ts.length && ts[lo] < fromMicros) {
            lo++;
        }
        int hi = lo;
        while (hi < ts.length && ts[hi] < toMicros) {
            hi++;
        }
        if (lo == hi) {
            return null;
        }
        long[] cts = Arrays.copyOfRange(ts, lo, hi);
        double[] cv = Arrays.copyOfRange(v, lo, hi);
        long clipEnd = Math.min(tEnd, toMicros);
        long createdAt = Micros.of(e.getProvenance().getCreatedAt());
        long seed = XxHash64.hash((e.getEpisodeId() + "/" + cts[0] + "/" + clipEnd).getBytes(StandardCharsets.UTF_8));
        Episodes.Draft d = e.getTimeEncoding() == TimeEncoding.REGULAR
                ? Episodes.regular(e.getSeries(), cts[0], e.getIntervalMicros(), cv)
                : Episodes.irregular(e.getSeries(), cts, cv);
        d.tEnd(clipEnd)
                .episodeId(Ulid.deterministic(Math.floorMod(Math.floorDiv(createdAt, 1000L), 1L << 48), seed))
                .createdAt(createdAt)
                .source(e.getProvenance().getSource())
                .producer(e.getProvenance().getProducer())
                .pipeline(e.getProvenance().getPipeline())
                .parentEpisodeId(e.getEpisodeId())
                .bucketId(e.getBucketId())
                .revision(e.getRevision());
        e.getLabels().forEach(d::label);
        if (e.getSegmentation() != null) {
            d.segmentation(e.getSegmentation());
        }
        if (e.getQuality() != null) {
            byte[] q = new byte[e.getQuality().remaining()];
            e.getQuality().duplicate().get(q);
            d.quality(Arrays.copyOfRange(q, lo, hi));
        }
        return d.build();
    }
}
