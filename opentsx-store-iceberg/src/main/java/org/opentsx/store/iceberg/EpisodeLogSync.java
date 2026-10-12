package org.opentsx.store.iceberg;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.WriteResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies episodes from the KafScale episodes topic into a {@link SeriesStore} (typically Iceberg).
 *
 * <p>Reads with manual assignment from the given start positions up to the end offsets seen at call time and
 * returns the next positions; the caller persists them. Store writes are idempotent per episodeId, so restarting
 * from older positions (at-least-once) is safe.
 */
public final class EpisodeLogSync {

    public record Result(long written, long skipped, long undecodable, Map<TopicPartition, Long> nextPositions) {
    }

    private EpisodeLogSync() {
    }

    public static Result sync(Consumer<byte[], byte[]> consumer, TopicLayout layout, SeriesStore store,
                              Map<TopicPartition, Long> fromPositions, int batchSize, Duration timeout) {
        List<PartitionInfo> infos = consumer.partitionsFor(layout.episodes(), timeout);
        List<TopicPartition> tps = infos.stream().map(i -> new TopicPartition(i.topic(), i.partition())).toList();
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
        Map<TopicPartition, Long> next = new HashMap<>();
        consumer.assign(tps);
        for (TopicPartition tp : tps) {
            long from = Math.max(begin.get(tp), fromPositions.getOrDefault(tp, 0L));
            next.put(tp, from);
            consumer.seek(tp, from);
        }
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Episode> batch = new ArrayList<>();
        long written = 0;
        long skipped = 0;
        long bad = 0;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (tps.stream().anyMatch(tp -> next.get(tp) < end.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timed out syncing " + layout.episodes());
                }
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    if (r.offset() >= end.get(tp)) {
                        continue;
                    }
                    try {
                        batch.add(avro.decode(r.value(), Episode.class));
                    } catch (RuntimeException e) {
                        bad++;
                    }
                    next.put(tp, r.offset() + 1);
                    if (batch.size() >= batchSize) {
                        WriteResult w = store.write(batch);
                        written += w.written();
                        skipped += w.skipped();
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                WriteResult w = store.write(batch);
                written += w.written();
                skipped += w.skipped();
            }
        } finally {
            consumer.assign(List.of());
        }
        return new Result(written, skipped, bad, next);
    }
}
