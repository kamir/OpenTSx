package org.opentsx.stats;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.model.v2.TimeEncoding;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Running and windowed statistics over the episode and observation streams, independent of any runtime
 * (the KafScale {@link StatsProcessor} and tests drive it; a Flink operator can wrap it the same way).
 *
 * <ul>
 *   <li><b>Windows:</b> epoch-aligned tumbling windows (e.g. PT10M, PT1H, P1D) per series, plus a GLOBAL
 *       ("since start") accumulator emitted on demand ({@link #emitGlobal()}).</li>
 *   <li><b>Event time per series:</b> a window closes when the series has progressed to {@code windowEnd + allowedLateness}
 *       (an episode advances it to its {@code tEnd}, an observation to its timestamp).
 *       Series are keyed by {@code seriesId}, so they stay in order within a partition; values that arrive for an
 *       already closed window are counted as {@link Metrics#lateValues()} and only enter GLOBAL.</li>
 *   <li><b>Gaps:</b> a gap is a step between consecutive timestamps of more than {@code gapFactor} times the expected
 *       interval (configured per series, otherwise learned from REGULAR episodes). Missing values inside a regular
 *       episode are NaN and counted as {@code nanCount}, not as gaps.</li>
 *   <li><b>Duplicates:</b> KafScale has no idempotent producer, so episodes are deduplicated by {@code episodeId}
 *       (bounded memory of recent ids).</li>
 * </ul>
 */
public final class StatsEngine {

    public static final String GLOBAL = "GLOBAL";
    private static final int STATE_VERSION = 1;

    /** Engine configuration. */
    public record Config(List<Duration> windows, double sketchAlpha, Duration allowedLateness, double gapFactor,
                         Function<String, OptionalLong> expectedIntervalMicros, int dedupCapacity) {

        public Config {
            windows = List.copyOf(windows);
            for (Duration w : windows) {
                if (w.isNegative() || w.isZero() || w.toNanos() % 1000 != 0) {
                    throw new IllegalArgumentException("window must be a positive whole number of microseconds: " + w);
                }
            }
            Objects.requireNonNull(allowedLateness);
            Objects.requireNonNull(expectedIntervalMicros);
            if (gapFactor <= 1) {
                throw new IllegalArgumentException("gapFactor must be > 1");
            }
        }

        /** PT10M and PT1H windows, sketches with 1 % relative accuracy, no lateness, gap at 1.5 x interval. */
        public static Config defaults() {
            return new Config(List.of(Duration.ofMinutes(10), Duration.ofHours(1)), QuantileSketch.DEFAULT_ALPHA,
                    Duration.ZERO, 1.5, id -> OptionalLong.empty(), 100_000);
        }

        public Config withWindows(Duration... w) {
            return new Config(List.of(w), sketchAlpha, allowedLateness, gapFactor, expectedIntervalMicros, dedupCapacity);
        }

        public Config withSketchAlpha(double alpha) {
            return new Config(windows, alpha, allowedLateness, gapFactor, expectedIntervalMicros, dedupCapacity);
        }

        public Config withAllowedLateness(Duration lateness) {
            return new Config(windows, sketchAlpha, lateness, gapFactor, expectedIntervalMicros, dedupCapacity);
        }

        public Config withExpectedInterval(Function<String, OptionalLong> intervalMicros) {
            return new Config(windows, sketchAlpha, allowedLateness, gapFactor, intervalMicros, dedupCapacity);
        }
    }

    /** Counters for monitoring. */
    public record Metrics(long episodes, long observations, long duplicates, long values, long lateValues, long emitted) {
    }

    private static final class SeriesState {
        long lastTs = Long.MIN_VALUE;
        long progress = Long.MIN_VALUE;
        long learnedInterval;
        StatsAccumulator global;
        boolean globalDirty;
        final Map<Long, TreeMap<Long, StatsAccumulator>> open = new HashMap<>();
        final Map<Long, Long> closedUpTo = new HashMap<>();
    }

    private final Config config;
    private final long[] windowMicros;
    private final long latenessMicros;
    private final Map<String, SeriesState> series = new HashMap<>();
    private final LinkedHashMap<String, Boolean> recentEpisodes;
    private long episodes;
    private long observations;
    private long duplicates;
    private long values;
    private long lateValues;
    private long emitted;

    public StatsEngine(Config config) {
        this.config = config;
        this.windowMicros = config.windows().stream().mapToLong(d -> d.toNanos() / 1000).toArray();
        this.latenessMicros = config.allowedLateness().toNanos() / 1000;
        int capacity = config.dedupCapacity();
        this.recentEpisodes = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    public Config config() {
        return config;
    }

    /** Adds an episode; returns the windows it closed. Duplicates (same episodeId) are ignored. */
    public List<SeriesStats> onEpisode(Episode e) {
        if (recentEpisodes.put(e.getEpisodeId(), Boolean.TRUE) != null) {
            duplicates++;
            return List.of();
        }
        episodes++;
        String id = e.getSeries().getSeriesId();
        SeriesState s = state(id);
        if (e.getTimeEncoding() == TimeEncoding.REGULAR && e.getIntervalMicros() != null) {
            s.learnedInterval = e.getIntervalMicros();
        }
        long[] ts = Episodes.timestampsMicros(e);
        double[] vs = Episodes.values(e);
        for (int i = 0; i < vs.length; i++) {
            add(id, s, ts[i], vs[i]);
        }
        s.progress = Math.max(s.progress, Micros.of(e.getTEnd()));
        return close(id, s);
    }

    /** Adds a single live observation; returns the windows it closed. */
    public List<SeriesStats> onObservation(Observation o) {
        observations++;
        SeriesState s = state(o.getSeriesId());
        long t = Micros.of(o.getTs());
        add(o.getSeriesId(), s, t, o.getValue());
        s.progress = Math.max(s.progress, t);
        return close(o.getSeriesId(), s);
    }

    /** GLOBAL statistics of every series that changed since the last call. */
    public List<SeriesStats> emitGlobal() {
        List<SeriesStats> out = new ArrayList<>();
        series.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> {
            SeriesState s = en.getValue();
            if (s.globalDirty && !s.global.isEmpty()) {
                out.add(s.global.toSeriesStats(en.getKey(), GLOBAL, s.global.firstMicros(), s.global.lastMicros() + 1));
                s.globalDirty = false;
            }
        });
        emitted += out.size();
        return out;
    }

    /** Closes and emits all open windows (end of a bounded run), followed by GLOBAL. */
    public List<SeriesStats> flush() {
        List<SeriesStats> out = new ArrayList<>();
        series.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> {
            for (int w = 0; w < windowMicros.length; w++) {
                long size = windowMicros[w];
                TreeMap<Long, StatsAccumulator> open = en.getValue().open.getOrDefault(size, new TreeMap<>());
                while (!open.isEmpty()) {
                    Map.Entry<Long, StatsAccumulator> first = open.pollFirstEntry();
                    out.add(first.getValue().toSeriesStats(en.getKey(), config.windows().get(w).toString(),
                            first.getKey(), first.getKey() + size));
                    en.getValue().closedUpTo.merge(size, first.getKey() + size, Math::max);
                }
            }
        });
        emitted += out.size();
        out.addAll(emitGlobal());
        return out;
    }

    /** Current GLOBAL accumulator of a series (read-only use). */
    public StatsAccumulator global(String seriesId) {
        SeriesState s = series.get(seriesId);
        return s == null ? null : s.global;
    }

    public Metrics metrics() {
        return new Metrics(episodes, observations, duplicates, values, lateValues, emitted);
    }

    private SeriesState state(String id) {
        return series.computeIfAbsent(id, k -> {
            SeriesState s = new SeriesState();
            s.global = new StatsAccumulator(config.sketchAlpha());
            return s;
        });
    }

    private void add(String id, SeriesState s, long t, double v) {
        values++;
        boolean gap = false;
        if (s.lastTs != Long.MIN_VALUE && t > s.lastTs) {
            long expected = config.expectedIntervalMicros().apply(id).orElse(s.learnedInterval);
            gap = expected > 0 && t - s.lastTs > config.gapFactor() * expected;
        }
        s.lastTs = Math.max(s.lastTs, t);
        s.global.add(t, v);
        s.globalDirty = true;
        if (gap) {
            s.global.addGap();
        }
        for (long size : windowMicros) {
            long start = Math.floorDiv(t, size) * size;
            if (start + size <= s.closedUpTo.getOrDefault(size, Long.MIN_VALUE)) {
                lateValues++;
                continue;
            }
            StatsAccumulator acc = s.open.computeIfAbsent(size, k -> new TreeMap<>())
                    .computeIfAbsent(start, k -> new StatsAccumulator(config.sketchAlpha()));
            acc.add(t, v);
            if (gap) {
                acc.addGap();
            }
        }
    }

    private List<SeriesStats> close(String id, SeriesState s) {
        List<SeriesStats> out = null;
        for (int w = 0; w < windowMicros.length; w++) {
            long size = windowMicros[w];
            TreeMap<Long, StatsAccumulator> open = s.open.get(size);
            while (open != null && !open.isEmpty() && open.firstKey() + size + latenessMicros <= s.progress) {
                Map.Entry<Long, StatsAccumulator> first = open.pollFirstEntry();
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(first.getValue().toSeriesStats(id, config.windows().get(w).toString(), first.getKey(), first.getKey() + size));
                s.closedUpTo.merge(size, first.getKey() + size, Math::max);
            }
        }
        if (out == null) {
            return List.of();
        }
        emitted += out.size();
        return out;
    }

    // ---- checkpointing -------------------------------------------------------------------------------------------

    /** Writes the complete engine state (open windows, GLOBAL, gap tracking, recent episode ids, counters). */
    public void writeState(DataOutputStream out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeInt(windowMicros.length);
        for (long w : windowMicros) {
            out.writeLong(w);
        }
        out.writeLong(episodes);
        out.writeLong(observations);
        out.writeLong(duplicates);
        out.writeLong(values);
        out.writeLong(lateValues);
        out.writeLong(emitted);
        out.writeInt(recentEpisodes.size());
        for (String id : recentEpisodes.keySet()) {
            out.writeUTF(id);
        }
        out.writeInt(series.size());
        for (Map.Entry<String, SeriesState> en : new TreeMap<>(series).entrySet()) {
            SeriesState s = en.getValue();
            out.writeUTF(en.getKey());
            out.writeLong(s.lastTs);
            out.writeLong(s.progress);
            out.writeLong(s.learnedInterval);
            out.writeBoolean(s.globalDirty);
            s.global.writeTo(out);
            for (long size : windowMicros) {
                out.writeLong(s.closedUpTo.getOrDefault(size, Long.MIN_VALUE));
                TreeMap<Long, StatsAccumulator> open = s.open.getOrDefault(size, new TreeMap<>());
                out.writeInt(open.size());
                for (Map.Entry<Long, StatsAccumulator> w : open.entrySet()) {
                    out.writeLong(w.getKey());
                    w.getValue().writeTo(out);
                }
            }
        }
    }

    /** Restores a state written by {@link #writeState}; the window configuration must be the same. */
    public void readState(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != STATE_VERSION) {
            throw new IOException("unsupported stats state version " + version);
        }
        int n = in.readInt();
        long[] w = new long[n];
        for (int i = 0; i < n; i++) {
            w[i] = in.readLong();
        }
        if (!java.util.Arrays.equals(w, windowMicros)) {
            throw new IOException("checkpoint was written with other windows; start from scratch or keep the windows");
        }
        series.clear();
        recentEpisodes.clear();
        episodes = in.readLong();
        observations = in.readLong();
        duplicates = in.readLong();
        values = in.readLong();
        lateValues = in.readLong();
        emitted = in.readLong();
        int recent = in.readInt();
        for (int i = 0; i < recent; i++) {
            recentEpisodes.put(in.readUTF(), Boolean.TRUE);
        }
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String id = in.readUTF();
            SeriesState s = new SeriesState();
            s.lastTs = in.readLong();
            s.progress = in.readLong();
            s.learnedInterval = in.readLong();
            s.globalDirty = in.readBoolean();
            s.global = StatsAccumulator.readFrom(in);
            for (long size : windowMicros) {
                long closed = in.readLong();
                if (closed != Long.MIN_VALUE) {
                    s.closedUpTo.put(size, closed);
                }
                int windows = in.readInt();
                TreeMap<Long, StatsAccumulator> open = new TreeMap<>();
                for (int j = 0; j < windows; j++) {
                    long start = in.readLong();
                    open.put(start, StatsAccumulator.readFrom(in));
                }
                if (!open.isEmpty()) {
                    s.open.put(size, open);
                }
            }
            series.put(id, s);
        }
    }
}
