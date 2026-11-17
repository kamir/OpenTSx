package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rebuilds an episode index by scanning the episodes topic: the index is derived state, the log is the truth. A record
 * that does not decode as an episode is counted in {@code skipped}; a failure of the index stops the rebuild.
 */
public final class EpisodeIndexer {

    private EpisodeIndexer() {
    }

    public record Result(long indexed, long skipped) {
    }

    public static Result rebuild(Consumer<byte[], byte[]> consumer, TopicLayout layout, EpisodeIndex index, Duration timeout) {
        OpenTsxAvro avro = new OpenTsxAvro();
        AtomicLong indexed = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        LogReader.readAll(consumer, layout.episodes(), r -> {
            Episode e;
            try {
                e = avro.decode(r.value(), Episode.class);
            } catch (RuntimeException ex) {
                skipped.incrementAndGet();
                return;
            }
            index.add(new IndexEntry(e.getEpisodeId(), e.getSeries().getSeriesId(), Micros.of(e.getTStart()),
                    Micros.of(e.getTEnd()), e.getBucketId(), r.topic(), r.partition(), r.offset()));
            indexed.incrementAndGet();
        }, timeout);
        return new Result(indexed.get(), skipped.get());
    }
}
