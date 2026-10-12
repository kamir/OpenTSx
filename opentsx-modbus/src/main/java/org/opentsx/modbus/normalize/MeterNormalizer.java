package org.opentsx.modbus.normalize;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.poll.RawReading;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns Modbus readings into canonical v2 episodes, driven by the register map. Same shape as the wind farm's
 * {@code Normalizer} (fixed windows aligned to the epoch, FIXED_DURATION segmentation, quality bytes only when a
 * value is flagged), but without the turbine inventory and IEC catalog: the register map is the catalog here.
 * <ul>
 *   <li>series: {@code metric} of the point, tags = map tags + {@code device} + point tags, unit of the point</li>
 *   <li>quality: MISSING for failed reads, IMPLAUSIBLE outside min/max, COUNTER_WRAP / COUNTER_RESET for counters
 *   (state is kept across calls, so feed batches in time order)</li>
 *   <li>labels: register map name@version, point, function, address, type, OBIS, origin, counter kind, and
 *   {@code registermap.example=true} for template maps</li>
 * </ul>
 */
public final class MeterNormalizer {

    private final RegisterMap map;
    private final Duration episodeDuration;
    private final long windowMicros;
    private final String originOverride;
    private final CounterTracker counters = new CounterTracker();

    /**
     * @param originOverride replaces the origin of every point (e.g. {@code SYNTHETIC} when polling the simulator);
     *                       null keeps the map's origin
     */
    public MeterNormalizer(RegisterMap map, Duration episodeDuration, String originOverride) {
        this.map = map;
        this.episodeDuration = episodeDuration;
        this.windowMicros = episodeDuration.toNanos() / 1_000L;
        if (windowMicros <= 0) {
            throw new IllegalArgumentException("episodeDuration must be positive");
        }
        if (originOverride != null && !RegisterMap.ORIGINS.contains(originOverride)) {
            throw new IllegalArgumentException("unknown origin " + originOverride);
        }
        this.originOverride = originOverride;
    }

    public MeterNormalizer(RegisterMap map, Duration episodeDuration) {
        this(map, episodeDuration, null);
    }

    public record Result(List<Episode> episodes, NormalizationReport report) {
    }

    /** A reading with its quality, for consumers that do not want episodes (CSV). */
    public record Assessed(RawReading reading, RegisterPoint point, byte quality) {
    }

    public SeriesKey seriesKey(RegisterPoint p) {
        Map<String, String> tags = new LinkedHashMap<>(map.tags());
        tags.put("device", map.device().id());
        tags.putAll(p.tags());
        return SeriesKeys.of(p.metric(), tags, p.unit());
    }

    public String origin(RegisterPoint p) {
        return originOverride != null ? originOverride : p.origin();
    }

    /** Quality of one reading; updates the counter state. Returns null for points not in the map. */
    public Assessed assess(RawReading r) {
        if (!map.has(r.point())) {
            return null;
        }
        RegisterPoint p = map.point(r.point());
        byte q = Quality.GOOD;
        if (Double.isNaN(r.value())) {
            q = Quality.MISSING;
        } else {
            byte c = counters.check(r.device() + "/" + p.name(), p, r.raw());
            if (c != Quality.GOOD) {
                q = c;
            } else if (!p.plausible(r.value())) {
                q = Quality.IMPLAUSIBLE;
            }
        }
        return new Assessed(r, p, q);
    }

    public Result normalize(Iterable<RawReading> readings) {
        List<RawReading> sorted = new ArrayList<>();
        readings.forEach(sorted::add);
        sorted.sort(Comparator.comparing(RawReading::timestamp));
        long n = 0, accepted = 0, unknown = 0, missing = 0, implausible = 0, wraps = 0, resets = 0, duplicates = 0;
        TreeSet<String> unknownNames = new TreeSet<>();
        Map<SeriesKey, RegisterPoint> points = new LinkedHashMap<>();
        Map<SeriesKey, TreeMap<Long, TreeMap<Long, double[]>>> buffer = new LinkedHashMap<>();
        for (RawReading r : sorted) {
            n++;
            Assessed a = assess(r);
            if (a == null) {
                unknown++;
                unknownNames.add(r.point());
                continue;
            }
            switch (a.quality()) {
                case Quality.MISSING: missing++; break;
                case Quality.IMPLAUSIBLE: implausible++; break;
                case Quality.COUNTER_WRAP: wraps++; break;
                case Quality.COUNTER_RESET: resets++; break;
                default: break;
            }
            SeriesKey key = seriesKey(a.point());
            points.putIfAbsent(key, a.point());
            long ts = Micros.of(r.timestamp());
            long window = Math.floorDiv(ts, windowMicros) * windowMicros;
            double[] prev = buffer.computeIfAbsent(key, k -> new TreeMap<>())
                    .computeIfAbsent(window, k -> new TreeMap<>())
                    .put(ts, new double[]{r.value(), a.quality()});
            if (prev != null) {
                duplicates++;
            }
            accepted++;
        }
        List<Episode> out = new ArrayList<>();
        buffer.forEach((key, windows) -> windows.forEach((start, pts) -> out.add(episode(key, points.get(key), start, pts))));
        NormalizationReport report = new NormalizationReport(map.device().id(), map.name() + "@" + map.version(), n,
                accepted, unknown, missing, implausible, wraps, resets, duplicates, out.size(), unknownNames);
        return new Result(out, report);
    }

    private Episode episode(SeriesKey key, RegisterPoint p, long windowStart, TreeMap<Long, double[]> pts) {
        int size = pts.size();
        long[] ts = new long[size];
        double[] v = new double[size];
        byte[] q = new byte[size];
        boolean anyFlag = false;
        int i = 0;
        for (Map.Entry<Long, double[]> e : pts.entrySet()) {
            ts[i] = e.getKey();
            v[i] = e.getValue()[0];
            q[i] = (byte) e.getValue()[1];
            anyFlag |= q[i] != Quality.GOOD;
            i++;
        }
        boolean regular = size >= 2;
        for (int k = 2; regular && k < size; k++) {
            regular = ts[k] - ts[k - 1] == ts[1] - ts[0];
        }
        Episodes.Draft d = regular
                ? Episodes.regular(key, ts[0], ts[1] - ts[0], v)
                : Episodes.irregular(key, ts, v);
        d.tEnd(windowStart + windowMicros)
                .source("modbus:" + map.device().id())
                .pipeline("modbus-normalizer")
                .label("registermap", map.name() + "@" + map.version())
                .label("source.point", p.name())
                .label("modbus.function", p.function().yamlName())
                .label("modbus.address", Integer.toString(p.address()))
                .label("modbus.type", p.type().yamlName())
                .label("origin", origin(p))
                .label("kind", p.kind().name().toLowerCase(java.util.Locale.ROOT))
                .segmentation(Segmentation.newBuilder()
                        .setStrategy(SegmentationStrategy.FIXED_DURATION)
                        .setParams(Map.of("duration", episodeDuration.toString()))
                        .setAnchorTs(null)
                        .setSourceEpisodeId(null)
                        .build());
        if (p.obis() != null) {
            d.label("obis", p.obis());
        }
        if (map.example()) {
            d.label("registermap.example", "true");
        }
        if (anyFlag) {
            d.quality(q);
        }
        return d.build();
    }
}
