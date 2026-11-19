package org.opentsx.store.iceberg;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.Patience;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * from older positions (at-least-once) is safe. A refused fetch is waited out within the timeout. Records that
 * retention deleted after a given position are counted in {@code lostToRetention} and logged at WARN.
 */
public final class EpisodeLogSync {

    private static final Logger LOG = LoggerFactory.getLogger(EpisodeLogSync.class);

    public record Result(long written, long skipped, long undecodable, long lostToRetention,
                         Map<TopicPartition, Long> nextPositions) {
    }

    private EpisodeLogSync() {
    }

    /**
     * @throws IllegalStateException when the episodes topic does not exist or has no partitions, when the broker
     *                               refuses reads for longer than {@code timeout}, or when the copy takes longer
     */
    public static Result sync(Consumer<byte[], byte[]> consumer, TopicLayout layout, SeriesStore store,
                              Map<TopicPartition, Long> fromPositions, int batchSize, Duration timeout) {
        return sync(consumer, layout, store, fromPositions, batchSize, timeout, new Patience(timeout, Duration.ofSeconds(2)));
    }

    static Result sync(Consumer<byte[], byte[]> consumer, TopicLayout layout, SeriesStore store,
                       Map<TopicPartition, Long> fromPositions, int batchSize, Duration timeout, Patience patience) {
        String topic = layout.episodes();
        List<PartitionInfo> infos = consumer.partitionsFor(topic, timeout);
        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException(topic + ": no such topic; check the domain, or create the topic first");
        }
        List<TopicPartition> tps = infos.stream().map(i -> new TopicPartition(i.topic(), i.partition())).toList();
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
        Map<TopicPartition, Long> next = new HashMap<>();
        long lost = 0;
        consumer.assign(tps);
        for (TopicPartition tp : tps) {
            long from = begin.get(tp);
            Long saved = fromPositions.get(tp);
            if (saved != null && saved < from) {
                lost += from - saved;
                LOG.warn("retention deleted records before they were read; reading on from the log start topic={}"
                        + " partition={} position={} logStart={} lost={}", tp.topic(), tp.partition(), saved, from, from - saved);
            } else if (saved != null) {
                from = saved;
            }
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
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, Duration.ofMillis(500))) {
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
        return new Result(written, skipped, bad, lost, next);
    }
}
