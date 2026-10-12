package org.opentsx.windpark.normalize;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.ChannelDef;
import org.opentsx.windpark.catalog.Park;
import org.opentsx.windpark.catalog.SourceMapping;
import org.opentsx.windpark.catalog.SourceSystem;
import org.opentsx.windpark.catalog.Units;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns raw readings of one source system into canonical v2 episodes, driven by the data-point catalog:
 * <ol>
 *   <li>channel: the source system's mapping for the original name, else a canonical metric / IEC reference;
 *   anything else is reported as unmapped (never guessed)</li>
 *   <li>turbine: must exist in the {@link Park} inventory</li>
 *   <li>value: {@code * scale + offset}, sign inversion, unit conversion, quantization to the sensor resolution</li>
 *   <li>time: UTC; END-stamped aggregates are moved to the interval start; aggregates become their own series
 *   (tag {@code aggregation}, e.g. {@code mean.PT10M})</li>
 *   <li>quality per value (catalog codes): MISSING for NaN, IMPLAUSIBLE outside the plausibility band</li>
 *   <li>episodes: one per series and window of {@code episodeDuration}, aligned to the epoch; FIXED_DURATION;
 *   labels record source system, catalog version and mapping status</li>
 * </ol>
 */
public final class Normalizer {

    public static final byte QUALITY_GOOD = 0;
    public static final byte QUALITY_IMPLAUSIBLE = 1;
    public static final byte QUALITY_MISSING = 2;

    private final ChannelCatalog catalog;
    private final Park park;
    private final SourceSystem source;
    private final long windowMicros;
    private final Duration episodeDuration;

    public Normalizer(ChannelCatalog catalog, Park park, Duration episodeDuration, String sourceSystem) {
        this.catalog = catalog;
        this.park = park;
        this.source = catalog.source(sourceSystem)
                .orElseThrow(() -> new IllegalArgumentException("source system " + sourceSystem + " is not in catalog " + catalog.name()));
        this.episodeDuration = episodeDuration;
        this.windowMicros = episodeDuration.toNanos() / 1_000L;
        if (windowMicros <= 0) {
            throw new IllegalArgumentException("episodeDuration must be positive");
        }
    }

    /** Time zone of the source system's local timestamps (from the catalog). */
    public ZoneId zone() {
        return source.timezone();
    }

    /** A reading stamped in the source system's local time. */
    public RawReading local(String turbineId, String channel, LocalDateTime localTime, double value, String unit) {
        return RawReading.local(turbineId, channel, localTime, source.timezone(), value, unit);
    }

    public record Result(List<Episode> episodes, NormalizationReport report) {
    }

    private record Target(SeriesKey key, ChannelDef channel, String mappingStatus, String sourceName) {
    }

    public Result normalize(Iterable<RawReading> readings) {
        long n = 0, accepted = 0, unmapped = 0, unknownTurbine = 0, unitErrors = 0, converted = 0, provisional = 0,
                implausible = 0, missing = 0, duplicates = 0;
        TreeSet<String> unmappedNames = new TreeSet<>();
        Map<String, Long> conversions = new TreeMap<>();
        Map<SeriesKey, Target> targets = new LinkedHashMap<>();
        Map<SeriesKey, TreeMap<Long, TreeMap<Long, double[]>>> buffer = new LinkedHashMap<>();

        for (RawReading r : readings) {
            n++;
            Optional<SourceMapping> mapping = catalog.mapping(source.system(), r.channel());
            ChannelDef c;
            double value = r.value();
            String fromUnit;
            long ts = Micros.of(r.timestamp());
            String aggTag = null;
            String status = "CANONICAL";
            if (mapping.isPresent()) {
                SourceMapping m = mapping.get();
                if (m.status() == SourceMapping.MappingStatus.UNMAPPED) {
                    unmapped++;
                    unmappedNames.add(r.channel());
                    continue;
                }
                c = catalog.channel(m.channel());
                value = value * m.scale() + m.offset();
                if (m.invertSign()) {
                    value = -value;
                }
                fromUnit = r.unit() != null ? r.unit() : m.sourceUnit();
                if (m.aggregation() != null) {
                    aggTag = m.aggregation().tag();
                    if (m.timestamp() == SourceMapping.TimestampReference.END) {
                        ts -= m.aggregation().interval().toNanos() / 1_000L;
                    }
                }
                status = m.status().name();
                if (m.status() == SourceMapping.MappingStatus.PROVISIONAL) {
                    provisional++;
                }
            } else {
                Optional<ChannelDef> canonical = catalog.resolve(r.channel());
                if (canonical.isEmpty()) {
                    unmapped++;
                    unmappedNames.add(r.channel());
                    continue;
                }
                c = canonical.get();
                fromUnit = r.unit() != null ? r.unit() : c.unit();
            }
            if (park.turbines().stream().noneMatch(t -> t.id().equals(r.turbineId()))) {
                unknownTurbine++;
                continue;
            }
            try {
                String from = Units.canonical(fromUnit == null ? c.unit() : fromUnit);
                value = Units.convert(value, from, c.unit());
                if (!from.equals(Units.canonical(c.unit()))) {
                    converted++;
                    conversions.merge(from + "->" + c.unit(), 1L, Long::sum);
                }
            } catch (IllegalArgumentException e) {
                unitErrors++;
                continue;
            }
            value = c.quantize(value);
            byte q = QUALITY_GOOD;
            if (Double.isNaN(value)) {
                q = QUALITY_MISSING;
                missing++;
            } else if (!c.plausible(value)) {
                q = QUALITY_IMPLAUSIBLE;
                implausible++;
            }
            SeriesKey key = park.seriesKey(c, r.turbineId(), aggTag);
            targets.putIfAbsent(key, new Target(key, c, status, r.channel()));
            long window = Math.floorDiv(ts, windowMicros) * windowMicros;
            double[] prev = buffer.computeIfAbsent(key, k -> new TreeMap<>())
                    .computeIfAbsent(window, k -> new TreeMap<>())
                    .put(ts, new double[]{value, q});
            if (prev != null) {
                duplicates++;
            }
            accepted++;
        }

        List<Episode> out = new ArrayList<>();
        buffer.forEach((key, windows) -> windows.forEach((start, points) ->
                out.add(episode(targets.get(key), start, points))));
        NormalizationReport report = new NormalizationReport(source.system(), catalog.version(), n, accepted, unmapped,
                unknownTurbine, unitErrors, converted, provisional, implausible, missing, duplicates, out.size(),
                unmappedNames, conversions);
        return new Result(out, report);
    }

    private Episode episode(Target target, long windowStart, TreeMap<Long, double[]> points) {
        int size = points.size();
        long[] ts = new long[size];
        double[] v = new double[size];
        byte[] q = new byte[size];
        int i = 0;
        boolean anyFlag = false;
        for (Map.Entry<Long, double[]> p : points.entrySet()) {
            ts[i] = p.getKey();
            v[i] = p.getValue()[0];
            q[i] = (byte) p.getValue()[1];
            anyFlag |= q[i] != QUALITY_GOOD;
            i++;
        }
        boolean regular = size >= 2;
        for (int k = 2; regular && k < size; k++) {
            regular = ts[k] - ts[k - 1] == ts[1] - ts[0];
        }
        Episodes.Draft d = regular
                ? Episodes.regular(target.key(), ts[0], ts[1] - ts[0], v)
                : Episodes.irregular(target.key(), ts, v);
        d.tEnd(windowStart + windowMicros)
                .source(source.system())
                .pipeline("windpark-normalizer")
                .label("catalog", catalog.name() + "@" + catalog.version())
                .label("iec", target.channel().iecReference())
                .label("mapping.status", target.mappingStatus())
                .label("source.channel", target.sourceName())
                .segmentation(Segmentation.newBuilder()
                        .setStrategy(SegmentationStrategy.FIXED_DURATION)
                        .setParams(Map.of("duration", episodeDuration.toString()))
                        .setAnchorTs(null)
                        .setSourceEpisodeId(null)
                        .build());
        if (anyFlag) {
            d.quality(q);
        }
        return d.build();
    }
}
