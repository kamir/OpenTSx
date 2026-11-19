package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded reads with manual assignment (no consumer group), the only reliable way to read KafScale today. */
public final class LogReader {

    private LogReader() {
    }

    /** Reads [from, toExclusive) of every given partition, then unassigns. */
    static void read(Consumer<byte[], byte[]> consumer, Map<TopicPartition, long[]> ranges,
                     java.util.function.Consumer<ConsumerRecord<byte[], byte[]>> sink, Duration timeout) {
        Map<TopicPartition, Long> until = new HashMap<>();
        ranges.forEach((tp, r) -> {
            if (r[1] > r[0]) {
                until.put(tp, r[1]);
            }
        });
        if (until.isEmpty()) {
            return;
        }
        Set<TopicPartition> open = new HashSet<>(until.keySet());
        consumer.assign(open);
        open.forEach(tp -> consumer.seek(tp, ranges.get(tp)[0]));
        long deadline = System.nanoTime() + timeout.toNanos();
        // a refused fetch is waited out; the deadline of this read still holds
        Patience patience = new Patience(timeout, Duration.ofSeconds(2));
        try {
            while (!open.isEmpty()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timed out reading " + open);
                }
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    Long end = until.get(tp);
                    if (end != null && r.offset() < end && open.contains(tp)) {
                        sink.accept(r);
                    }
                }
                open.removeIf(tp -> consumer.position(tp) >= until.get(tp));
                consumer.pause(until.keySet().stream().filter(tp -> !open.contains(tp)).toList());
            }
        } finally {
            consumer.assign(List.of());
        }
    }

    /**
     * Reads everything currently in the topic (from log start to the end offsets at call time), then unassigns. A
     * refused fetch is waited out within {@code timeout}.
     *
     * @throws IllegalStateException when the topic does not exist or has no partitions, or when the read takes longer
     *                               than {@code timeout}
     */
    public static void readAll(Consumer<byte[], byte[]> consumer, String topic,
                               java.util.function.Consumer<ConsumerRecord<byte[], byte[]>> sink, Duration timeout) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, timeout);
        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException(topic + ": no such topic; check the domain, or create the topic first");
        }
        List<TopicPartition> tps = infos.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
        Map<TopicPartition, long[]> ranges = new HashMap<>();
        for (TopicPartition tp : tps) {
            ranges.put(tp, new long[]{begin.get(tp), end.get(tp)});
        }
        read(consumer, ranges, sink, timeout);
    }

    /** The message and its causes on one line, for a log line. */
    static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return s.toString();
    }
}
