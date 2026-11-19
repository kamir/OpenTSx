package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rebuilds an episode index by scanning the episodes topic: the index is derived state, the log is the truth. A record
 * that does not decode as an episode is counted in {@code skipped}. An episode that fails {@link Episodes#validate} is
 * counted in {@code invalid} and not indexed. The first record of each kind per partition is logged at WARN with
 * topic, partition and offset. A failure of the index stops the rebuild.
 */
public final class EpisodeIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(EpisodeIndexer.class);

    private EpisodeIndexer() {
    }

    public record Result(long indexed, long skipped, long invalid) {
    }

    public static Result rebuild(Consumer<byte[], byte[]> consumer, TopicLayout layout, EpisodeIndex index, Duration timeout) {
        OpenTsxAvro avro = new OpenTsxAvro();
        AtomicLong indexed = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        AtomicLong invalid = new AtomicLong();
        Set<TopicPartition> undecodableReported = new HashSet<>();
        Set<TopicPartition> invalidReported = new HashSet<>();
        LogReader.readAll(consumer, layout.episodes(), r -> {
            TopicPartition tp = new TopicPartition(r.topic(), r.partition());
            Episode e;
            try {
                e = avro.decode(r.value(), Episode.class);
            } catch (RuntimeException ex) {
                skipped.incrementAndGet();
                if (undecodableReported.add(tp)) {
                    LOG.warn("undecodable record skipped and counted in skipped; later ones of this partition are only"
                            + " counted topic={} partition={} offset={} cause={}", r.topic(), r.partition(), r.offset(),
                            LogReader.causes(ex));
                }
                return;
            }
            try {
                Episodes.validate(e);
            } catch (IllegalArgumentException ex) {
                invalid.incrementAndGet();
                if (invalidReported.add(tp)) {
                    LOG.warn("invalid episode skipped and counted in invalid; later ones of this partition are only counted"
                            + " topic={} partition={} offset={} cause={}", r.topic(), r.partition(), r.offset(),
                            LogReader.causes(ex));
                }
                return;
            }
            index.add(new IndexEntry(e.getEpisodeId(), e.getSeries().getSeriesId(), Micros.of(e.getTStart()),
                    Micros.of(e.getTEnd()), e.getBucketId(), r.topic(), r.partition(), r.offset()));
            indexed.incrementAndGet();
        }, timeout);
        return new Result(indexed.get(), skipped.get(), invalid.get());
    }
}
