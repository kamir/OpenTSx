package org.opentsx.model;

import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Provenance;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

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

    private static final Pattern MODULE = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern VERSION_FORMAT = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.]+)?$");
    private static final String VERSION = version();

    /** The producer of episodes built here: {@code opentsx-model/<version>}. */
    public static final String PRODUCER = producer("opentsx-model");

    private Episodes() {
    }

    /** {@code <module>/<version>} with the version of this build, for {@link Draft#producer(String)}. */
    public static String producer(String module) {
        if (module == null || !MODULE.matcher(module).matches()) {
            throw new IllegalArgumentException("module: \"" + module
                    + "\" is not allowed; expected a Maven artifact id such as opentsx-market");
        }
        return module + "/" + VERSION;
    }

    private static String version() {
        try (InputStream in = Episodes.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                throw new IllegalStateException("org/opentsx/model/version.properties: missing from the class path;"
                        + " build opentsx-model with Maven");
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            if (v == null || !VERSION_FORMAT.matcher(v).matches()) {
                throw new IllegalStateException("org/opentsx/model/version.properties: version \"" + v
                        + "\" is not stamped; build opentsx-model with Maven");
            }
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "org/opentsx/model/version.properties: cannot read; build opentsx-model with Maven", e);
        }
    }

    public static Draft regular(SeriesKey series, long tStartMicros, long intervalMicros, double[] values) {
        if (intervalMicros <= 0) {
            throw new IllegalArgumentException("intervalMicros: \"" + intervalMicros
                    + "\" is not allowed; expected a positive number of microseconds");
        }
        Draft d = new Draft(series, values);
        d.encoding = TimeEncoding.REGULAR;
        d.tStart = tStartMicros;
        d.interval = intervalMicros;
        return d;
    }

    public static Draft irregular(SeriesKey series, long[] timestampsMicros, double[] values) {
        if (timestampsMicros.length != values.length) {
            throw new IllegalArgumentException("timestamps: length " + timestampsMicros.length + " differs from the length "
                    + values.length + " of values; expected one timestamp per value");
        }
        if (values.length == 0) {
            throw new IllegalArgumentException("values: empty; expected at least one value");
        }
        Draft d = new Draft(series, values);
        d.encoding = TimeEncoding.IRREGULAR_DELTA;
        d.timestamps = timestampsMicros.clone();
        d.tStart = timestampsMicros[0];
        return d;
    }

    /**
     * REGULAR with the common step if there are at least two timestamps with one positive step, IRREGULAR_DELTA
     * otherwise. Refuses what {@link #irregular} refuses: an empty series, and timestamps and values of different length.
     */
    public static Draft of(SeriesKey series, long[] timestampsMicros, double[] values) {
        int n = timestampsMicros.length;
        if (n >= 2 && n == values.length) {
            long step = timestampsMicros[1] - timestampsMicros[0];
            boolean equal = step > 0;
            for (int i = 2; equal && i < n; i++) {
                equal = timestampsMicros[i] - timestampsMicros[i - 1] == step;
            }
            if (equal) {
                return regular(series, timestampsMicros[0], step, values);
            }
        }
        return irregular(series, timestampsMicros, values);
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
            throw new IllegalArgumentException("count: \"" + n + "\" differs from the length " + e.getValues().size()
                    + " of values; expected the number of values");
        }
        String axis;
        if (e.getTimeEncoding() == TimeEncoding.REGULAR) {
            axis = "intervalMicros";
            if (e.getIntervalMicros() == null) {
                throw new IllegalArgumentException(
                        "intervalMicros: missing in a REGULAR episode; expected a positive number of microseconds");
            }
            if (e.getIntervalMicros() <= 0) {
                throw new IllegalArgumentException("intervalMicros: \"" + e.getIntervalMicros()
                        + "\" is not allowed; expected a positive number of microseconds");
            }
        } else {
            axis = "timeDeltas";
            if (n > 0 && e.getTimeDeltas() == null) {
                throw new IllegalArgumentException("timeDeltas: missing in an IRREGULAR_DELTA episode of " + n
                        + " values; expected the encoded deltas of its timestamps");
            }
        }
        long[] ts = timestampsMicros(e);
        for (int i = 1; i < n; i++) {
            if (ts[i] <= ts[i - 1]) {
                throw new IllegalArgumentException(axis + ": timestamp " + i + " is not after timestamp " + (i - 1)
                        + "; expected strictly increasing timestamps");
            }
        }
        long tEnd = Micros.of(e.getTEnd());
        if (n > 0 && ts[n - 1] >= tEnd) {
            throw endNotAfterLast(tEnd, ts[n - 1]);
        }
        long tStart = Micros.of(e.getTStart());
        if (tStart >= tEnd) {
            throw new IllegalArgumentException("tEnd: \"" + tEnd + "\" is not after tStart " + tStart
                    + "; expected a time after tStart");
        }
        if (e.getQuality() != null && e.getQuality().remaining() != n) {
            throw new IllegalArgumentException("quality: length " + e.getQuality().remaining()
                    + " differs from the count " + n + "; expected one byte per value");
        }
    }

    private static IllegalArgumentException endNotAfterLast(long tEnd, long last) {
        return new IllegalArgumentException("tEnd: \"" + tEnd + "\" is not after the last timestamp " + last
                + "; expected a time after the last timestamp");
    }

    /**
     * Mutable draft; {@link #build()} derives the remaining fields. A draft without {@link #source(String)} builds an
     * episode with the source {@code unknown}, so every producer sets the source.
     */
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

        /** Where the values come from, for example {@code scada:ost-01}; {@code unknown} when not set. */
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
                    throw endNotAfterLast(end, next - interval);
                }
            } else {
                deltas = ByteBuffer.wrap(TimeAxis.encodeDeltas(timestamps));
                long last = timestamps[n - 1];
                end = tEnd != null ? tEnd : last + 1;
                if (last >= end) {
                    throw endNotAfterLast(end, last);
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
