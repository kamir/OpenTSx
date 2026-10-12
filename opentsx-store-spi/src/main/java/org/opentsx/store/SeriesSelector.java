package org.opentsx.store;

import org.opentsx.model.v2.SeriesKey;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which series: optional metric, required tag values (all must match) and optional explicit series ids.
 * {@link #all()} selects everything.
 */
public record SeriesSelector(String metric, Map<String, String> tags, Set<String> seriesIds) {

    public SeriesSelector {
        tags = Map.copyOf(tags == null ? Map.of() : tags);
        seriesIds = seriesIds == null ? null : Set.copyOf(seriesIds);
    }

    public static SeriesSelector all() {
        return new SeriesSelector(null, Map.of(), null);
    }

    public static SeriesSelector metric(String metric) {
        return new SeriesSelector(metric, Map.of(), null);
    }

    public static SeriesSelector ids(String... seriesIds) {
        return new SeriesSelector(null, Map.of(), new LinkedHashSet<>(java.util.List.of(seriesIds)));
    }

    public SeriesSelector tag(String key, String value) {
        Map<String, String> t = new LinkedHashMap<>(tags);
        t.put(key, value);
        return new SeriesSelector(metric, t, seriesIds);
    }

    public boolean matches(SeriesKey key) {
        if (metric != null && !metric.equals(key.getMetric())) {
            return false;
        }
        if (seriesIds != null && !seriesIds.contains(key.getSeriesId())) {
            return false;
        }
        for (Map.Entry<String, String> t : tags.entrySet()) {
            if (!t.getValue().equals(key.getTags().get(t.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
