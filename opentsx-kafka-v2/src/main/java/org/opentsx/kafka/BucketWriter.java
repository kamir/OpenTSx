package org.opentsx.kafka;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.opentsx.model.Buckets;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.Ulid;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.BucketState;
import org.opentsx.model.v2.Episode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes one bucket: an OPEN manifest, its episodes (async, key = seriesId, record timestamp = tStart), and a
 * SEALED manifest with episode count, time range and checksum once every episode is acknowledged.
 * Index entries are taken from the producer acknowledgements.
 *
 * <pre>{@code
 * try (BucketWriter w = BucketWriter.open(producer, layout, index, "exp-2026-10-ffmlrc", descriptor)) {
 *     episodes.forEach(w::append);
 * } // seals
 * }</pre>
 *
 * If any send fails the bucket is not sealed and {@link #seal()} throws; replay treats unsealed buckets as incomplete.
 */
public final class BucketWriter implements AutoCloseable {

    private final Producer<String, byte[]> producer;
    private final TopicLayout layout;
    private final EpisodeIndex index;
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final BucketManifest openManifest;
    private final List<String> episodeIds = new ArrayList<>();
    private final List<CompletableFuture<IndexEntry>> pending = new ArrayList<>();
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final AtomicLong payloadBytes = new AtomicLong();
    private long values;
    private long tMin = Long.MAX_VALUE;
    private long tMax = Long.MIN_VALUE;
    private BucketManifest sealed;

    private BucketWriter(Producer<String, byte[]> producer, TopicLayout layout, EpisodeIndex index, BucketManifest open) {
        this.producer = producer;
        this.layout = layout;
        this.index = index;
        this.openManifest = open;
    }

    public static BucketWriter open(Producer<String, byte[]> producer, TopicLayout layout, EpisodeIndex index,
                                    String name, Map<String, String> descriptor) {
        return open(producer, layout, index, Ulid.next(), name, descriptor);
    }

    public static BucketWriter open(Producer<String, byte[]> producer, TopicLayout layout, EpisodeIndex index,
                                    String bucketId, String name, Map<String, String> descriptor) {
        BucketManifest open = BucketManifest.newBuilder()
                .setBucketId(bucketId)
                .setName(name)
                .setState(BucketState.OPEN)
                .setDescriptor(new LinkedHashMap<>(descriptor == null ? Map.of() : descriptor))
                .setTMin(null).setTMax(null)
                .setEpisodeCount(0)
                .setChecksum(null)
                .setCreatedAt(Instant.now())
                .build();
        BucketWriter w = new BucketWriter(producer, layout, index, open);
        w.sendManifest(open);
        return w;
    }

    public String bucketId() {
        return openManifest.getBucketId();
    }

    /** Sends one episode of this bucket. An episode without bucketId is assigned to this bucket. */
    public synchronized CompletableFuture<IndexEntry> append(Episode episode) {
        if (sealed != null) {
            throw new IllegalStateException("bucket " + bucketId() + " is sealed");
        }
        Episode e = episode;
        if (e.getBucketId() == null) {
            e = Episode.newBuilder(episode).setBucketId(bucketId()).build();
        } else if (!e.getBucketId().equals(bucketId())) {
            throw new IllegalArgumentException("episode " + e.getEpisodeId() + " belongs to bucket " + e.getBucketId());
        }
        byte[] payload = avro.encode(e);
        payloadBytes.addAndGet(payload.length);
        values += e.getCount();
        long tStart = Micros.of(e.getTStart());
        long tEnd = Micros.of(e.getTEnd());
        tMin = Math.min(tMin, tStart);
        tMax = Math.max(tMax, tEnd);
        episodeIds.add(e.getEpisodeId());

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(layout.episodes(), null,
                Math.floorDiv(tStart, 1_000L), e.getSeries().getSeriesId(), payload);
        record.headers().add(TopicLayout.HEADER_SCHEMA, "org.opentsx.model.v2.Episode".getBytes(StandardCharsets.UTF_8));
        record.headers().add(TopicLayout.HEADER_BUCKET, bucketId().getBytes(StandardCharsets.UTF_8));

        Episode sent = e;
        CompletableFuture<IndexEntry> future = new CompletableFuture<>();
        pending.add(future);
        try {
            producer.send(record, (RecordMetadata md, Exception ex) -> {
                if (ex != null) {
                    failure.compareAndSet(null, ex);
                    future.completeExceptionally(ex);
                    return;
                }
                IndexEntry entry = new IndexEntry(sent.getEpisodeId(), sent.getSeries().getSeriesId(), tStart, tEnd,
                        bucketId(), md.topic(), md.partition(), md.offset());
                index.add(entry);
                future.complete(entry);
            });
        } catch (RuntimeException ex) {
            // e.g. RecordTooLargeException thrown synchronously: the bucket must not be sealed
            failure.compareAndSet(null, ex);
            future.completeExceptionally(ex);
        }
        return future;
    }

    /** Waits for all acknowledgements and writes the SEALED manifest. Idempotent. */
    public synchronized BucketManifest seal() {
        if (sealed != null) {
            return sealed;
        }
        producer.flush();
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            // reported below via failure
        }
        Exception ex = failure.get();
        if (ex != null) {
            throw new BucketWriteException("bucket " + bucketId() + " not sealed: an episode was not written", ex);
        }
        BucketManifest manifest = BucketManifest.newBuilder(openManifest)
                .setState(BucketState.SEALED)
                .setEpisodeCount(episodeIds.size())
                .setTMin(episodeIds.isEmpty() ? null : Micros.toInstant(tMin))
                .setTMax(episodeIds.isEmpty() ? null : Micros.toInstant(tMax))
                .setChecksum(Buckets.checksum(episodeIds))
                .build();
        sendManifest(manifest);
        sealed = manifest;
        return manifest;
    }

    @Override
    public void close() {
        seal();
    }

    /** Encoded episode bytes handed to the producer (before compression). */
    public long payloadBytes() {
        return payloadBytes.get();
    }

    public synchronized long valueCount() {
        return values;
    }

    private void sendManifest(BucketManifest manifest) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(layout.buckets(), manifest.getBucketId(), avro.encode(manifest));
        record.headers().add(TopicLayout.HEADER_SCHEMA, "org.opentsx.model.v2.BucketManifest".getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new BucketWriteException("cannot write " + manifest.getState() + " manifest of bucket " + manifest.getBucketId(), e);
        }
    }

    /** Thrown when a bucket cannot be written completely. */
    public static final class BucketWriteException extends RuntimeException {
        public BucketWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
