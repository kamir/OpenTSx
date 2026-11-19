package org.opentsx.store.iceberg;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.opentsx.kafka.Patience;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Copies episodes from the KafScale episodes topic into a {@link SeriesStore} (typically Iceberg).
 *
 * <p>Reads with manual assignment from the given start positions up to the end offsets seen at call time and
 * returns the next positions; the caller persists them. Store writes are idempotent per episodeId, so restarting
 * from older positions (at-least-once) is safe. A refused fetch is waited out within the timeout. A record that does
 * not decode is counted in {@code undecodable}, an episode that fails {@link Episodes#validate} is counted in
 * {@code invalid} and not written; the first of each kind per partition is logged at WARN with topic, partition and
 * offset. Records that retention deleted after a given position or during the run are counted in
 * {@code lostToRetention} and logged at WARN. A failure of the store stops the sync.
 */
public final class EpisodeLogSync {

    private static final Logger LOG = LoggerFactory.getLogger(EpisodeLogSync.class);

    public record Result(long written, long skipped, long undecodable, long invalid, long lostToRetention,
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
        return sync(consumer, layout, store, fromPositions, batchSize, timeout, new Patience(timeout, Duration.ofSeconds(2)),
                System::nanoTime);
    }

    static Result sync(Consumer<byte[], byte[]> consumer, TopicLayout layout, SeriesStore store,
                       Map<TopicPartition, Long> fromPositions, int batchSize, Duration timeout, Patience patience,
                       LongSupplier nanoClock) {
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
                lost += lost(tp, saved, from);
            } else if (saved != null) {
                from = saved;
            }
            next.put(tp, from);
            consumer.seek(tp, from);
        }
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Episode> batch = new ArrayList<>();
        Set<TopicPartition> undecodableReported = new HashSet<>();
        Set<TopicPartition> invalidReported = new HashSet<>();
        long written = 0;
        long skipped = 0;
        long bad = 0;
        long invalid = 0;
        long deadline = nanoClock.getAsLong() + timeout.toNanos();
        try {
            while (tps.stream().anyMatch(tp -> next.get(tp) < end.get(tp))) {
                if (nanoClock.getAsLong() > deadline) {
                    throw new IllegalStateException("timed out syncing " + layout.episodes());
                }
                boolean read = false;
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    long position = next.get(tp);
                    if (r.offset() >= end.get(tp)) {
                        if (position < end.get(tp)) {
                            lost += lost(tp, position, r.offset());
                            next.put(tp, r.offset());
                        }
                        continue;
                    }
                    if (r.offset() > position) {
                        lost += lost(tp, position, r.offset());
                    }
                    read = true;
                    next.put(tp, r.offset() + 1);
                    Episode e;
                    try {
                        e = avro.decode(r.value(), Episode.class);
                    } catch (RuntimeException ex) {
                        bad++;
                        if (undecodableReported.add(tp)) {
                            LOG.warn("undecodable record skipped and counted in undecodable; later ones of this partition"
                                    + " are only counted topic={} partition={} offset={} cause={}", tp.topic(),
                                    tp.partition(), r.offset(), causes(ex));
                        }
                        continue;
                    }
                    try {
                        Episodes.validate(e);
                    } catch (IllegalArgumentException ex) {
                        invalid++;
                        if (invalidReported.add(tp)) {
                            LOG.warn("invalid episode skipped and counted in invalid; later ones of this partition are"
                                    + " only counted topic={} partition={} offset={} cause={}", tp.topic(), tp.partition(),
                                    r.offset(), causes(ex));
                        }
                        continue;
                    }
                    batch.add(e);
                    if (batch.size() >= batchSize) {
                        WriteResult w = store.write(batch);
                        written += w.written();
                        skipped += w.skipped();
                        batch.clear();
                    }
                }
                if (!read) {
                    long left = TimeUnit.NANOSECONDS.toMillis(deadline - nanoClock.getAsLong());
                    if (left <= 0) {
                        throw new IllegalStateException("timed out syncing " + topic);
                    }
                    try {
                        lost += skipDeleted(consumer, tps, next, end, Duration.ofMillis(left));
                    } catch (TimeoutException e) {
                        throw new IllegalStateException("timed out syncing " + topic, e);
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
        return new Result(written, skipped, bad, invalid, lost, next);
    }

    /** Retention can delete past a position with no record left to poll; only the log start shows that. */
    private static long skipDeleted(Consumer<byte[], byte[]> consumer, List<TopicPartition> tps,
                                    Map<TopicPartition, Long> next, Map<TopicPartition, Long> end, Duration timeout) {
        List<TopicPartition> behind = tps.stream().filter(tp -> next.get(tp) < end.get(tp)).toList();
        Map<TopicPartition, Long> logStart = consumer.beginningOffsets(behind, timeout);
        long lost = 0;
        for (TopicPartition tp : behind) {
            long position = next.get(tp);
            long start = logStart.get(tp);
            if (start > position) {
                lost += lost(tp, position, start);
                next.put(tp, start);
                consumer.seek(tp, start);
            }
        }
        return lost;
    }

    private static long lost(TopicPartition tp, long position, long logStart) {
        LOG.warn("retention deleted records before they were read; reading on from the log start topic={} partition={}"
                + " position={} logStart={} lost={}", tp.topic(), tp.partition(), position, logStart, logStart - position);
        return logStart - position;
    }

    /** The message and its causes on one line, for a log line. */
    private static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return s.toString();
    }
}
