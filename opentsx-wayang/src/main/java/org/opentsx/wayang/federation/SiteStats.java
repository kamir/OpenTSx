package org.opentsx.wayang.federation;

import org.apache.avro.specific.SpecificData;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsEngine;
import org.opentsx.wayang.TsxStatsMerge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SiteStats {

    private SiteStats() { }

    public static List<SeriesStats> group(Collection<SeriesStats> rawStatistics, Map<String, String> rawSeriesToGroup, double alpha) {
        if (rawStatistics == null || rawSeriesToGroup == null) throw new IllegalArgumentException("raw statistics and grouping are required");
        Map<String, List<TsxStatsMerge.Partial>> windows = new TreeMap<>();
        for (SeriesStats raw : rawStatistics) {
            if (raw == null) throw new IllegalArgumentException("raw statistics must not be null");
            if (StatsEngine.GLOBAL.equals(raw.getWindowType())) continue;
            String group = rawSeriesToGroup.get(raw.getSeriesId());
            if (group == null) throw new IllegalArgumentException("raw series has no admitted aggregate group");
            SeriesStats copy = SpecificData.get().deepCopy(SeriesStats.getClassSchema(), raw);
            copy.setSeriesId(group);
            String key = group + "|" + copy.getWindowType() + "|" + Micros.of(copy.getWindowStart()) + "|" + Micros.of(copy.getWindowEnd());
            windows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new TsxStatsMerge.Partial(raw.getSeriesId(), copy));
        }
        List<SeriesStats> output = new ArrayList<>();
        for (List<TsxStatsMerge.Partial> partials : windows.values()) {
            SeriesStats first = partials.get(0).statistics();
            output.add(TsxStatsMerge.merge(first.getSeriesId(), first.getWindowType(), Micros.of(first.getWindowStart()),
                    Micros.of(first.getWindowEnd()), alpha, partials));
        }
        output.sort(Comparator.comparing(SeriesStats::getSeriesId).thenComparing(SeriesStats::getWindowType).thenComparing(SeriesStats::getWindowStart));
        return List.copyOf(output);
    }
}
