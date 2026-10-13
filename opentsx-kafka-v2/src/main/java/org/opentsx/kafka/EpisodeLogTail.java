package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows the episodes topic of a domain. The first {@link #poll} reads the whole log, every later call the episodes
 * appended since the previous one. Manual assignment and seek, no consumer group (see {@link KafScaleClients}).
 *
 * <p>Not thread-safe beyond the synchronized {@code poll}: the consumer belongs to this object.
 */
public final class EpisodeLogTail {

    private final Consumer<byte[], byte[]> consumer;
    private final String topic;
    private final Duration timeout;
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final Map<TopicPartition, Long> next = new HashMap<>();
    private long undecodable;

    public EpisodeLogTail(Consumer<byte[], byte[]> consumer, TopicLayout layout, Duration timeout) {
        this.consumer = consumer;
        this.topic = layout.episodes();
        this.timeout = timeout;
    }

    public String topic() {
        return topic;
    }

    /** Records that could not be decoded as an episode and were skipped. */
    public synchronized long undecodable() {
        return undecodable;
    }

    /**
     * Hands every episode appended since the previous call to the sink.
     *
     * @return the number of episodes handed over
     */
    public synchronized int poll(java.util.function.Consumer<Episode> sink) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, timeout);
        if (infos == null || infos.isEmpty()) {
            return 0;
        }
        List<TopicPartition> tps = infos.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
        Map<TopicPartition, long[]> ranges = new HashMap<>();
        for (TopicPartition tp : tps) {
            // retention may have moved the log start past the position reached last time
            ranges.put(tp, new long[]{Math.max(next.getOrDefault(tp, 0L), begin.get(tp)), end.get(tp)});
        }
        int[] delivered = {0};
        LogReader.read(consumer, ranges, r -> {
            next.merge(new TopicPartition(r.topic(), r.partition()), r.offset() + 1, Math::max);
            Episode e;
            try {
                e = avro.decode(r.value(), Episode.class);
            } catch (RuntimeException ex) {
                undecodable++;
                return;
            }
            sink.accept(e);
            delivered[0]++;
        }, timeout);
        ranges.forEach((tp, r) -> next.merge(tp, r[1], Math::max));
        return delivered[0];
    }
}
