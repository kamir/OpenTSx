package org.opentsx.stats;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
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
 * re-emission after a restart); this keeps the latest record per (seriesId, windowType, windowStart) - for GLOBAL the
 * one with the most values.
 */
public final class SeriesStatsLog {

    private SeriesStatsLog() {
    }

    public static List<SeriesStats> readLatest(Consumer<byte[], byte[]> consumer, TopicLayout layout, Duration timeout) {
        List<TopicPartition> tps = new ArrayList<>();
        for (PartitionInfo p : consumer.partitionsFor(layout.seriesStats(), timeout)) {
            tps.add(new TopicPartition(p.topic(), p.partition()));
        }
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
        Map<TopicPartition, Long> next = new HashMap<>(begin);
        consumer.assign(tps);
        tps.forEach(tp -> consumer.seek(tp, begin.get(tp)));
        OpenTsxAvro avro = new OpenTsxAvro();
        List<SeriesStats> all = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (tps.stream().anyMatch(tp -> next.get(tp) < end.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timed out reading " + layout.seriesStats());
                }
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    if (r.offset() < end.get(tp)) {
                        all.add(avro.decode(r.value(), SeriesStats.class));
                        next.put(tp, r.offset() + 1);
                    }
                }
            }
        } finally {
            consumer.assign(List.of());
        }
        return latest(all);
    }

    /** Keeps the latest record per (seriesId, windowType, windowStart), sorted by series, window type and start. */
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
