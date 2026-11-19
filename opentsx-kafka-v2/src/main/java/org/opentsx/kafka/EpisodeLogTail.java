package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Follows the episodes topic of a domain: a {@link LogTail} that decodes the records. The first {@link #poll} reads
 * the whole log, every later call the episodes appended since the previous one.
 *
 * <p>Not thread-safe beyond the synchronized {@code poll}: the consumer belongs to this object.
 */
public final class EpisodeLogTail {

    private static final Logger LOG = LoggerFactory.getLogger(EpisodeLogTail.class);

    private final LogTail tail;
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final Set<TopicPartition> undecodableReported = new HashSet<>();
    private long undecodable;

    public EpisodeLogTail(Consumer<byte[], byte[]> consumer, TopicLayout layout, Duration timeout) {
        this(consumer, layout.episodes(), timeout);
    }

    /** Follows another topic of v2 episodes, for example {@link TopicLayout#clean()}. */
    public EpisodeLogTail(Consumer<byte[], byte[]> consumer, String topic, Duration timeout) {
        this.tail = new LogTail(consumer, topic, timeout);
    }

    public String topic() {
        return tail.topic();
    }

    /**
     * Records that could not be decoded as an episode and were skipped. The first one of each partition is logged at
     * WARN with topic, partition and offset.
     */
    public synchronized long undecodable() {
        return undecodable;
    }

    /** Records that retention deleted before they were read, see {@link LogTail#lostToRetention()}. */
    public synchronized long lostToRetention() {
        return tail.lostToRetention();
    }

    /**
     * Hands every episode appended since the previous call to the sink.
     *
     * @return the number of episodes handed over
     */
    public synchronized int poll(java.util.function.Consumer<Episode> sink) {
        int[] delivered = {0};
        tail.poll(r -> {
            Episode e;
            try {
                e = avro.decode(r.value(), Episode.class);
            } catch (RuntimeException ex) {
                undecodable++;
                if (undecodableReported.add(new TopicPartition(r.topic(), r.partition()))) {
                    LOG.warn("undecodable record skipped and counted in undecodable; later ones of this partition are only"
                            + " counted topic={} partition={} offset={} cause={}", r.topic(), r.partition(), r.offset(),
                            LogReader.causes(ex));
                }
                return;
            }
            sink.accept(e);
            delivered[0]++;
        });
        return delivered[0];
    }
}
