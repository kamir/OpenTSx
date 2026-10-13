package org.opentsx.retrieval.vector;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** From the vector topics to the episode stores: measuring points as the episodes of one series per component. */
public final class VectorStoreBridge {

    /** Metric prefix of the series a vector topic becomes in the inventory: {@code smart.<vector>.<component>}. */
    public static final String METRIC_PREFIX = "smart.";

    private VectorStoreBridge() {
    }

    /**
     * The rows of one vector topic as episodes: one series per key and component, metric
     * {@code smart.<vector>.<component>}, tags = key of the vector plus {@code vector=<name>}. A later row for the
     * same key and tick replaces the earlier one (a rerun appends, the log is not compacted).
     */
    public static List<Episode> episodes(String topic, Collection<VectorJson.Row> rows) {
        Map<String, TreeMap<Long, VectorJson.Row>> byKey = new LinkedHashMap<>();
        for (VectorJson.Row r : rows) {
            byKey.computeIfAbsent(VectorJson.keyText(r.key()), k -> new TreeMap<>()).put(r.fromMicros(), r);
        }
        List<Episode> out = new ArrayList<>();
        for (TreeMap<Long, VectorJson.Row> ticks : byKey.values()) {
            VectorJson.Row last = ticks.lastEntry().getValue();
            long clock = last.clockMicros();
            // rows of an earlier run with another clock or other components are not part of this series
            ticks.values().removeIf(r -> r.clockMicros() != clock || !r.components().equals(last.components())
                    || Math.floorMod(r.fromMicros() - last.fromMicros(), clock) != 0);
            long lo = ticks.firstKey();
            int n = Math.toIntExact((ticks.lastKey() - lo) / clock + 1);
            Map<String, String> tags = new TreeMap<>(last.key());
            tags.put("vector", last.vector());
            for (int c = 0; c < last.components().size(); c++) {
                double[] values = new double[n];
                Arrays.fill(values, Double.NaN);
                for (VectorJson.Row r : ticks.values()) {
                    values[(int) ((r.fromMicros() - lo) / clock)] = r.values()[c];
                }
                String component = last.components().get(c);
                SeriesKey key = SeriesKeys.of(METRIC_PREFIX + last.vector() + "." + component, tags);
                out.add(Episodes.regular(key, lo, clock, values).source("smart-topic").createdAt(Micros.now())
                        .label("smartTopic", topic).label("schema", VectorJob.SCHEMA).label("component", component).build());
            }
        }
        return out;
    }
}
