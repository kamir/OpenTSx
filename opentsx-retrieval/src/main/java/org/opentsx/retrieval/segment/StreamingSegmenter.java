package org.opentsx.retrieval.segment;

import org.opentsx.model.Episodes;
import org.opentsx.model.Ulid;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Segmentation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Segments a stream of episodes (any number of series, each in time order) with one {@link Segmenter}.
 * Memory per series is bounded by what the detector still needs (pre/post/window), and the result equals the batch
 * result ({@link Segmentations#segment}) once {@link #flush()} is called.
 *
 * <p>Segment episodes are deterministic: the id is derived from series, strategy, parameters and start, so
 * re-running a segmentation writes the same episodes (stores deduplicate by id). They carry {@code segmentation}
 * (strategy, params, anchor, source episode), the source as parent, and the source labels (except ground truth).
 */
public final class StreamingSegmenter {

    public static final String PRODUCER = "opentsx-retrieval/segmenter";

    private final Segmenter segmenter;
    private final Map<String, State> series = new HashMap<>();
    private final String paramKey;
    private final boolean trim;

    private static final class State {
        final SampleBuffer buf = new SampleBuffer();
        final Detector detector;

        State(Detector detector) {
            this.detector = detector;
        }
    }

    public StreamingSegmenter(Segmenter segmenter) {
        this(segmenter, true);
    }

    /** @param trim drop samples the detector no longer needs (false only for tests) */
    StreamingSegmenter(Segmenter segmenter, boolean trim) {
        this.trim = trim;
        this.segmenter = segmenter;
        this.paramKey = segmenter.strategy() + new TreeMap<>(segmenter.params()).toString();
    }

    /** Adds an episode; returns the segments that became final. */
    public List<Episode> onEpisode(Episode e) {
        State s = series.computeIfAbsent(e.getSeries().getSeriesId(), k -> new State(segmenter.newDetector()));
        long before = s.buf.end();
        s.buf.append(e);
        List<Episode> out = new ArrayList<>();
        for (long i = before; i < s.buf.end(); i++) {
            for (Cut c : s.detector.onSample(s.buf, i)) {
                out.add(materialize(s.buf, c));
            }
        }
        if (trim) {
            s.buf.trimBefore(s.detector.earliestNeeded(s.buf));
        }
        return out;
    }

    /** End of input: decides all open segments. */
    public List<Episode> flush() {
        List<Episode> out = new ArrayList<>();
        for (State s : new TreeMap<>(series).values()) {
            for (Cut c : s.detector.flush(s.buf)) {
                out.add(materialize(s.buf, c));
            }
        }
        series.clear();
        return out;
    }

    private Episode materialize(SampleBuffer b, Cut c) {
        int n = (int) (c.to() - c.from());
        double[] values = new double[n];
        long[] ts = new long[n];
        byte[] quality = new byte[n];
        boolean regular = n > 1;
        for (int k = 0; k < n; k++) {
            long i = c.from() + k;
            ts[k] = b.ts(i);
            values[k] = b.v(i);
            quality[k] = b.quality(i);
            if (k > 1 && ts[k] - ts[k - 1] != ts[1] - ts[0]) {
                regular = false;
            }
        }
        Episode anchorSource = b.source(c.anchor() >= 0 ? c.anchor() : c.from());
        var key = anchorSource.getSeries();
        Episodes.Draft d = regular ? Episodes.regular(key, ts[0], ts[1] - ts[0], values) : Episodes.irregular(key, ts, values);
        long seed = XxHash64.hash((key.getSeriesId() + "|" + paramKey + "|" + ts[0]).getBytes(StandardCharsets.UTF_8));
        d.episodeId(Ulid.deterministic(Math.floorDiv(ts[0], 1000L), seed))
                .source(anchorSource.getProvenance().getSource())
                .producer(PRODUCER)
                .parentEpisodeId(anchorSource.getEpisodeId())
                .createdAt(ts[n - 1] + 1)
                .segmentation(Segmentation.newBuilder()
                        .setStrategy(segmenter.strategy())
                        .setParams(new java.util.LinkedHashMap<>(segmenter.params()))
                        .setAnchorTs(c.anchor() >= 0 ? org.opentsx.model.Micros.toInstant(b.ts(c.anchor())) : null)
                        .setSourceEpisodeId(anchorSource.getEpisodeId())
                        .build());
        anchorSource.getLabels().forEach((k, v) -> {
            if (!k.toString().startsWith("gt.")) {
                d.label(k.toString(), v.toString());
            }
        });
        d.label("segment.strategy", segmenter.strategy().name());
        if (b.anyQuality()) {
            d.quality(quality);
        }
        return d.build();
    }
}
