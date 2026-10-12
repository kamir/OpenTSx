package org.opentsx.model;

import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Provenance;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds valid {@link Episode}s (derived fields such as count, tEnd, encoded time axis and summary are computed)
 * and reads their values and timestamps back.
 *
 * <pre>{@code
 * Episode ep = Episodes.regular(key, tStartMicros, 1_000_000L, values).source("simulator:scenario-1").build();
 * long[] ts = Episodes.timestampsMicros(ep);
 * }</pre>
 */
public final class Episodes {

    public static final String PRODUCER = "opentsx-model/3.0.0";

    private Episodes() {
    }

    public static Draft regular(SeriesKey series, long tStartMicros, long intervalMicros, double[] values) {
        if (intervalMicros <= 0) {
            throw new IllegalArgumentException("intervalMicros must be > 0");
        }
        Draft d = new Draft(series, values);
        d.encoding = TimeEncoding.REGULAR;
        d.tStart = tStartMicros;
        d.interval = intervalMicros;
        return d;
    }

    public static Draft irregular(SeriesKey series, long[] timestampsMicros, double[] values) {
        if (timestampsMicros.length != values.length) {
            throw new IllegalArgumentException("timestamps and values differ in length");
        }
        if (values.length == 0) {
            throw new IllegalArgumentException("an irregular episode needs at least one value");
        }
        Draft d = new Draft(series, values);
        d.encoding = TimeEncoding.IRREGULAR_DELTA;
        d.timestamps = timestampsMicros.clone();
        d.tStart = timestampsMicros[0];
        return d;
    }

    public static double[] values(Episode episode) {
        List<Double> v = episode.getValues();
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }

    public static long[] timestampsMicros(Episode episode) {
        return TimeAxis.timestampsMicros(episode);
    }

    /** Checks the invariants the builder guarantees; use on episodes received from outside. */
    public static void validate(Episode e) {
        SeriesKeys.verify(e.getSeries());
        int n = e.getCount();
        if (e.getValues().size() != n) {
            throw new IllegalArgumentException("count " + n + " != values " + e.getValues().size());
        }
        long[] ts = timestampsMicros(e);
        long tEnd = Micros.of(e.getTEnd());
        if (n > 0 && ts[n - 1] >= tEnd) {
            throw new IllegalArgumentException("last timestamp not before tEnd");
        }
        if (Micros.of(e.getTStart()) >= tEnd) {
            throw new IllegalArgumentException("tStart must be before tEnd");
        }
        if (e.getQuality() != null && e.getQuality().remaining() != n) {
            throw new IllegalArgumentException("quality must have one byte per value");
        }
    }

    /** Mutable draft; {@link #build()} derives the remaining fields. */
    public static final class Draft {
        private final SeriesKey series;
        private final double[] values;
        private TimeEncoding encoding;
        private long tStart;
        private long interval;
        private long[] timestamps;
        private Long tEnd;
        private String episodeId;
        private String source = "unknown";
        private String producer = PRODUCER;
        private String pipeline;
        private String parentEpisodeId;
        private Long createdAt;
        private Map<String, String> labels = new LinkedHashMap<>();
        private String bucketId;
        private Segmentation segmentation;
        private byte[] quality;
        private int revision;

        private Draft(SeriesKey series, double[] values) {
            SeriesKeys.verify(series);
            this.series = series;
            this.values = values.clone();
        }

        /** Exclusive end; defaults to the next sample (REGULAR) or last timestamp + 1 µs (IRREGULAR_DELTA). */
        public Draft tEnd(long tEndMicros) {
            this.tEnd = tEndMicros;
            return this;
        }

        public Draft episodeId(String id) {
            this.episodeId = id;
            return this;
        }

        public Draft source(String source) {
            this.source = source;
            return this;
        }

        public Draft producer(String producer) {
            this.producer = producer;
            return this;
        }

        public Draft pipeline(String pipeline) {
            this.pipeline = pipeline;
            return this;
        }

        public Draft parentEpisodeId(String id) {
            this.parentEpisodeId = id;
            return this;
        }

        public Draft createdAt(long micros) {
            this.createdAt = micros;
            return this;
        }

        public Draft label(String key, String value) {
            this.labels.put(key, value);
            return this;
        }

        public Draft bucketId(String bucketId) {
            this.bucketId = bucketId;
            return this;
        }

        public Draft segmentation(Segmentation segmentation) {
            this.segmentation = segmentation;
            return this;
        }

        public Draft quality(byte[] quality) {
            this.quality = quality.clone();
            return this;
        }

        public Draft revision(int revision) {
            this.revision = revision;
            return this;
        }

        public Episode build() {
            int n = values.length;
            long end;
            ByteBuffer deltas = null;
            Long intervalMicros = null;
            if (encoding == TimeEncoding.REGULAR) {
                intervalMicros = interval;
                long next = Math.addExact(tStart, Math.multiplyExact(interval, (long) n));
                end = tEnd != null ? tEnd : (n == 0 ? tStart + interval : next);
                if (n > 0 && next - interval >= end) {
                    throw new IllegalArgumentException("tEnd cuts off values");
                }
            } else {
                deltas = ByteBuffer.wrap(TimeAxis.encodeDeltas(timestamps));
                long last = timestamps[n - 1];
                end = tEnd != null ? tEnd : last + 1;
                if (last >= end) {
                    throw new IllegalArgumentException("tEnd must be after the last timestamp");
                }
            }
            List<Double> boxed = new ArrayList<>(n);
            for (double v : values) {
                boxed.add(v);
            }
            Provenance provenance = Provenance.newBuilder()
                    .setSource(source)
                    .setProducer(producer)
                    .setCreatedAt(Micros.toInstant(createdAt != null ? createdAt : Micros.now()))
                    .setPipeline(pipeline)
                    .setParentEpisodeId(parentEpisodeId)
                    .build();
            Episode episode = Episode.newBuilder()
                    .setEpisodeId(episodeId != null ? episodeId : Ulid.next())
                    .setSeries(series)
                    .setTStart(Micros.toInstant(tStart))
                    .setTEnd(Micros.toInstant(end))
                    .setCount(n)
                    .setTimeEncoding(encoding)
                    .setIntervalMicros(intervalMicros)
                    .setTimeDeltas(deltas)
                    .setValues(boxed)
                    .setQuality(quality == null ? null : ByteBuffer.wrap(quality))
                    .setSummary(Summaries.of(values))
                    .setSegmentation(segmentation)
                    .setLabels(SeriesKeys.sortedByUtf8(labels))
                    .setBucketId(bucketId)
                    .setRevision(revision)
                    .setProvenance(provenance)
                    .build();
            validate(episode);
            return episode;
        }
    }
}
