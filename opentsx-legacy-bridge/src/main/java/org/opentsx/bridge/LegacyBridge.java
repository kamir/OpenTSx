package org.opentsx.bridge;

import org.opentsx.data.model.EpisodesRecord;
import org.opentsx.data.model.Observation;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * Converts between the legacy model and v2 episodes.
 *
 * <ul>
 *   <li>{@link TimeSeriesObject} x-values are interpreted as epoch time in a given unit; equally spaced series become
 *   REGULAR episodes, others IRREGULAR_DELTA.</li>
 *   <li>{@code EpisodesRecord} (v1) uses the per-observation timestamps (epoch millis), not the redundant header.</li>
 *   <li>Legacy labels of the form {@code "metric k1=v1,k2=v2"} (the OpenTSDB convention) become {@link SeriesKey}s.</li>
 * </ul>
 */
public final class LegacyBridge {

    public static final String PRODUCER = "opentsx-legacy-bridge/3.0.0";

    private LegacyBridge() {
    }

    public static Episode fromTimeSeriesObject(TimeSeriesObject tso, SeriesKey key, TimeUnitOfX unit, String source) {
        Vector<?> xs = tso.getXValues();
        double[] values = tso.getYData();
        long[] ts = new long[values.length];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = unit.toMicros(((Number) xs.get(i)).doubleValue());
        }
        return draft(key, ts, values).source(source).producer(PRODUCER).build();
    }

    /** Same, with the series identity parsed from the TSO label (see {@link #seriesKeyFromLabel}). */
    public static Episode fromTimeSeriesObject(TimeSeriesObject tso, TimeUnitOfX unit, String source) {
        return fromTimeSeriesObject(tso, seriesKeyFromLabel(tso.getLabel(), null), unit, source);
    }

    public static TimeSeriesObject toTimeSeriesObject(Episode episode, TimeUnitOfX unit) {
        TimeSeriesObject tso = new TimeSeriesObject(legacyLabel(episode.getSeries()));
        long[] ts = Episodes.timestampsMicros(episode);
        double[] values = Episodes.values(episode);
        for (int i = 0; i < ts.length; i++) {
            tso.addValuePair(unit.fromMicros(ts[i]), values[i]);
        }
        return tso;
    }

    public static Episode fromEpisodesRecord(EpisodesRecord v1, SeriesKey key) {
        List<Observation> obs = v1.getObservationArray();
        long[] ts = new long[obs.size()];
        double[] values = new double[obs.size()];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = Math.multiplyExact(obs.get(i).getTimestamp(), 1_000L);
            values[i] = obs.get(i).getValue();
        }
        SeriesKey k = key != null ? key
                : seriesKeyFromLabel(v1.getLabel() == null ? "legacy.episode" : v1.getLabel().toString(), null);
        String source = v1.getUri() == null ? "legacy:episodes-record" : "legacy:" + v1.getUri();
        return draft(k, ts, values).source(source).producer(PRODUCER).build();
    }

    /** {@code "metric k1=v1,k2=v2"} -> SeriesKey; a label without tags becomes the metric. */
    public static SeriesKey seriesKeyFromLabel(String label, String unit) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty label");
        }
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return SeriesKeys.of(trimmed, Map.of(), unit);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (String pair : trimmed.substring(space + 1).trim().split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("malformed tag '" + pair + "' in label: " + label);
            }
            tags.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return SeriesKeys.of(trimmed.substring(0, space), tags, unit);
    }

    /** Inverse of {@link #seriesKeyFromLabel}: the label format the OpenTSDB connector expects. */
    public static String legacyLabel(SeriesKey key) {
        if (key.getTags().isEmpty()) {
            return key.getMetric();
        }
        StringBuilder sb = new StringBuilder(key.getMetric()).append(' ');
        boolean first = true;
        for (Map.Entry<String, String> e : SeriesKeys.sortedByUtf8(key.getTags()).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static Episodes.Draft draft(SeriesKey key, long[] ts, double[] values) {
        if (ts.length >= 2) {
            long step = ts[1] - ts[0];
            boolean regular = step > 0;
            for (int i = 2; regular && i < ts.length; i++) {
                regular = ts[i] - ts[i - 1] == step;
            }
            if (regular) {
                return Episodes.regular(key, ts[0], step, values);
            }
        }
        if (ts.length == 1) {
            return Episodes.irregular(key, ts, values);
        }
        if (ts.length == 0) {
            throw new IllegalArgumentException("cannot convert an empty series");
        }
        return Episodes.irregular(key, ts, values);
    }
}
