package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows one topic. The first {@link #poll} reads the whole log, every later call the records appended since the
 * previous one. Manual assignment and seek, no consumer group (see {@link KafScaleClients}).
 *
 * <p>Several tails may share one consumer as long as they are polled one after the other.
 */
public final class LogTail {

    private final Consumer<byte[], byte[]> consumer;
    private final String topic;
    private final Duration timeout;
    private final Map<TopicPartition, Long> next = new HashMap<>();
    private long lostToRetention;

    public LogTail(Consumer<byte[], byte[]> consumer, String topic, Duration timeout) {
        this.consumer = consumer;
        this.topic = topic;
        this.timeout = timeout;
    }

    public String topic() {
        return topic;
    }

    /** Records that retention deleted after an earlier poll and before they were read; reported on stderr. */
    public synchronized long lostToRetention() {
        return lostToRetention;
    }

    /**
     * Hands every record appended since the previous call to the sink.
     *
     * @return the number of records handed over
     */
    public synchronized int poll(java.util.function.Consumer<ConsumerRecord<byte[], byte[]>> sink) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, timeout);
        if (infos == null || infos.isEmpty()) {
            return 0;
        }
        List<TopicPartition> tps = infos.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
        Map<TopicPartition, long[]> ranges = new HashMap<>();
        for (TopicPartition tp : tps) {
            long from = begin.get(tp);
            Long reached = next.get(tp);
            if (reached != null && reached < from) {
                lostToRetention += from - reached;
                System.err.println(tp + ": retention deleted " + (from - reached) + " records before they were read"
                        + " (position " + reached + ", log start " + from + "); reading on from the log start");
            } else if (reached != null) {
                from = reached;
            }
            ranges.put(tp, new long[]{from, end.get(tp)});
        }
        int[] delivered = {0};
        LogReader.read(consumer, ranges, r -> {
            next.merge(new TopicPartition(r.topic(), r.partition()), r.offset() + 1, Math::max);
            sink.accept(r);
            delivered[0]++;
        }, timeout);
        ranges.forEach((tp, r) -> next.merge(tp, r[1], Math::max));
        return delivered[0];
    }
}
