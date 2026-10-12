package org.opentsx.dwd;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.Ulid;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.twin.Origin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One converted DWD series: a variable at a station from one product, in canonical units (see {@link Variable}),
 * time in UTC microseconds. Aggregates over an interval (hourly irradiance, precipitation sums, 10-minute means) are
 * stamped at the <b>interval start</b> and carry an {@code aggregation} tag such as {@code mean.PT1H}, as in the
 * wind-farm normalizer. Missing values are {@code NaN}.
 *
 * @param station        DWD station id (MOSMIX id such as {@code 10578}, or CDC id such as {@code 01358})
 * @param product        {@code mosmix-l}, {@code cdc-10min} or {@code cdc-hourly}
 * @param variable       canonical variable
 * @param aggregation    {@code null} for instantaneous values, else e.g. {@code mean.PT10M}, {@code sum.PT1H}
 * @param origin         {@link Origin#FORECAST} for MOSMIX, {@link Origin#MEASURED} for CDC observations
 * @param referenceTime  issue time of the forecast run, {@code null} for observations
 * @param sourceElement  the DWD element or column the values came from ({@code FF}, {@code TT_10}, ...)
 * @param intervalMicros nominal spacing of the series
 * @param timestamps     UTC microseconds, strictly increasing
 * @param values         canonical values, {@code NaN} = missing
 */
public record WeatherSeries(String station, String product, Variable variable, String aggregation, Origin origin,
                            Instant referenceTime, String sourceElement, long intervalMicros, long[] timestamps,
                            double[] values) {

    /** Quality codes as in the wind-farm catalog: 0 GOOD, 2 MISSING. */
    public static final byte QUALITY_GOOD = 0;
    public static final byte QUALITY_MISSING = 2;

    public WeatherSeries {
        if (timestamps.length != values.length) {
            throw new IllegalArgumentException("timestamps and values differ in length");
        }
        for (int i = 1; i < timestamps.length; i++) {
            if (timestamps[i] <= timestamps[i - 1]) {
                throw new IllegalArgumentException("timestamps must be strictly increasing");
            }
        }
    }

    /** Series identity: {@code weather.<var>{source=dwd,product=..,station=..[,aggregation=..]}}, unit attached. */
    public SeriesKey seriesKey() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("source", "dwd");
        tags.put("product", product);
        tags.put("station", station);
        if (aggregation != null) {
            tags.put("aggregation", aggregation);
        }
        return SeriesKeys.of(variable.metric(), tags, variable.unit());
    }

    public int size() {
        return values.length;
    }

    /** Number of non-missing values. */
    public int valid() {
        int n = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        return n;
    }

    /** Values at or after {@code from} and before {@code to}. */
    public WeatherSeries slice(Instant from, Instant to) {
        long a = Micros.of(from);
        long b = Micros.of(to);
        int i = 0;
        while (i < timestamps.length && timestamps[i] < a) {
            i++;
        }
        int j = i;
        while (j < timestamps.length && timestamps[j] < b) {
            j++;
        }
        return new WeatherSeries(station, product, variable, aggregation, origin, referenceTime, sourceElement,
                intervalMicros, Arrays.copyOfRange(timestamps, i, j), Arrays.copyOfRange(values, i, j));
    }

    /**
     * v2 episodes. A forecast run becomes <b>one</b> episode (the run is the unit of issue; label
     * {@code forecast.reference_time}); observations are cut into epoch-aligned windows of {@code window}
     * (FIXED_DURATION), like the wind-farm path. Episodes that hold only missing values are skipped. Episode ids are
     * deterministic (series, start, reference time), so re-collecting the same data gives the same ids.
     */
    public List<Episode> toEpisodes(Duration window) {
        List<Episode> out = new ArrayList<>();
        if (values.length == 0) {
            return out;
        }
        if (referenceTime != null) {
            addEpisode(out, 0, values.length, timestamps[values.length - 1] + intervalMicros,
                    Segmentation.newBuilder().setStrategy(SegmentationStrategy.MANUAL)
                            .setParams(Map.of("unit", "forecast-run")).setAnchorTs(null).setSourceEpisodeId(null).build());
            return out;
        }
        long w = window.toNanos() / 1_000L;
        if (w <= 0) {
            throw new IllegalArgumentException("window must be positive");
        }
        Segmentation seg = Segmentation.newBuilder().setStrategy(SegmentationStrategy.FIXED_DURATION)
                .setParams(Map.of("duration", window.toString())).setAnchorTs(null).setSourceEpisodeId(null).build();
        int i = 0;
        while (i < values.length) {
            long start = Math.floorDiv(timestamps[i], w) * w;
            int j = i;
            while (j < values.length && timestamps[j] < start + w) {
                j++;
            }
            addEpisode(out, i, j, start + w, seg);
            i = j;
        }
        return out;
    }

    private void addEpisode(List<Episode> out, int from, int to, long tEnd, Segmentation seg) {
        int n = to - from;
        long[] ts = Arrays.copyOfRange(timestamps, from, to);
        double[] v = Arrays.copyOfRange(values, from, to);
        byte[] q = new byte[n];
        boolean anyMissing = false;
        boolean anyValid = false;
        for (int k = 0; k < n; k++) {
            if (Double.isNaN(v[k])) {
                q[k] = QUALITY_MISSING;
                anyMissing = true;
            } else {
                anyValid = true;
            }
        }
        if (!anyValid) {
            return;
        }
        boolean regular = n >= 2;
        for (int k = 2; regular && k < n; k++) {
            regular = ts[k] - ts[k - 1] == ts[1] - ts[0];
        }
        SeriesKey key = seriesKey();
        Episodes.Draft d = regular ? Episodes.regular(key, ts[0], ts[1] - ts[0], v) : Episodes.irregular(key, ts, v);
        String seed = key.getSeriesId() + "|" + ts[0] + "|" + (referenceTime == null ? "" : referenceTime.toString());
        d.tEnd(tEnd)
                .episodeId(Ulid.deterministic(ts[0] / 1000, XxHash64.hash(seed.getBytes(StandardCharsets.UTF_8))))
                .source("dwd-opendata")
                .pipeline("dwd-collector:" + product)
                .label("dwd.element", sourceElement)
                .label("dwd.origin", origin.name())
                .segmentation(seg);
        if (referenceTime != null) {
            d.label("forecast.reference_time", referenceTime.toString());
        }
        if (anyMissing) {
            d.quality(q);
        }
        out.add(d.build());
    }
}
