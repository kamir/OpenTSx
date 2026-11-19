package org.opentsx.stats;

import org.apache.kafka.clients.consumer.Consumer;
import org.opentsx.kafka.LogReader;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the statistics topic. KafScale has no compaction, so a key can occur several times (GLOBAL updates,
 * re-emission after a restart). This keeps one record per (seriesId, windowType, windowStart), for GLOBAL per
 * (seriesId, windowType): the one with the most values, and of records with as many values the one read last.
 */
public final class SeriesStatsLog {

    private SeriesStatsLog() {
    }

    /**
     * Reads the whole statistics topic of the domain with {@link LogReader#readAll} and keeps one record per key, as
     * {@link #latest} does.
     *
     * @throws IllegalStateException when the topic does not exist or the read takes longer than {@code timeout}
     */
    public static List<SeriesStats> readLatest(Consumer<byte[], byte[]> consumer, TopicLayout layout, Duration timeout) {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<SeriesStats> all = new ArrayList<>();
        LogReader.readAll(consumer, layout.seriesStats(), r -> all.add(avro.decode(r.value(), SeriesStats.class)), timeout);
        return latest(all);
    }

    /**
     * Keeps the record with the most values per (seriesId, windowType, windowStart), for GLOBAL per
     * (seriesId, windowType), and of records with as many values the later one; sorted by series, window type and start.
     */
    public static List<SeriesStats> latest(Collection<SeriesStats> records) {
        Map<String, SeriesStats> latest = new HashMap<>();
        for (SeriesStats s : records) {
            String key = s.getSeriesId() + "|" + s.getWindowType() + "|"
                    + (StatsEngine.GLOBAL.equals(s.getWindowType()) ? "" : Micros.of(s.getWindowStart()));
            latest.merge(key, s, (a, b) -> b.getValidCount() + b.getNanCount() >= a.getValidCount() + a.getNanCount() ? b : a);
        }
        List<SeriesStats> out = new ArrayList<>(latest.values());
        out.sort(Comparator.comparing((SeriesStats s) -> s.getSeriesId().toString())
                .thenComparing(s -> s.getWindowType().toString())
                .thenComparing(SeriesStats::getWindowStart));
        return out;
    }
}
