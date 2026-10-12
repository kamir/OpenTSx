package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Rebuilds an episode index by scanning the episodes topic - the index is derived state, the log is the truth. */
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
            try {
                Episode e = avro.decode(r.value(), Episode.class);
                index.add(new IndexEntry(e.getEpisodeId(), e.getSeries().getSeriesId(), Micros.of(e.getTStart()),
                        Micros.of(e.getTEnd()), e.getBucketId(), r.topic(), r.partition(), r.offset()));
                indexed.incrementAndGet();
            } catch (RuntimeException ex) {
                skipped.incrementAndGet();
            }
        }, timeout);
        return new Result(indexed.get(), skipped.get());
    }
}
