package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.model.Buckets;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.BucketState;
import org.opentsx.model.v2.Episode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Index-driven replay: looks up log positions in the {@link EpisodeIndex}, reads them with manual assignment and
 * seek, and delivers episodes in event-time order (tStart, episodeId), optionally paced.
 *
 * <p>Episodes are processed in chunks of {@code maxEpisodesInMemory} consecutive index entries, so memory stays
 * bounded; each chunk reads only the offset ranges it needs.
 */
public final class ReplayService {

    private final Consumer<byte[], byte[]> consumer;
    private final TopicLayout layout;
    private final EpisodeIndex index;
    private final Duration timeout;
    private final OpenTsxAvro avro = new OpenTsxAvro();

    public ReplayService(Consumer<byte[], byte[]> consumer, TopicLayout layout, EpisodeIndex index) {
        this(consumer, layout, index, Duration.ofMinutes(2));
    }

    public ReplayService(Consumer<byte[], byte[]> consumer, TopicLayout layout, EpisodeIndex index, Duration timeout) {
        this.consumer = consumer;
        this.layout = layout;
        this.index = index;
        this.timeout = timeout;
    }

    public ReplayResult replay(ReplayRequest request, java.util.function.Consumer<Episode> sink) {
        List<IndexEntry> entries = request.bucketId() != null
                ? index.bucket(request.bucketId())
                : index.find(request.seriesIds(), request.fromMicros(), request.toMicros());

        List<String> deliveredIds = new ArrayList<>();
        long missing = 0;
        Pacer pacer = new Pacer(request.speed());
        int chunk = request.maxEpisodesInMemory();
        for (int start = 0; start < entries.size(); start += chunk) {
            List<IndexEntry> part = entries.subList(start, Math.min(entries.size(), start + chunk));
            List<Episode> episodes = fetch(part);
            missing += part.size() - episodes.size();
            episodes.sort(Comparator.comparing(Episode::getTStart).thenComparing(Episode::getEpisodeId));
            for (Episode e : episodes) {
                pacer.await(Micros.of(e.getTStart()));
                sink.accept(e);
                deliveredIds.add(e.getEpisodeId());
            }
        }

        String checksum = Buckets.checksum(deliveredIds);
        boolean verified = false;
        if (request.bucketId() != null) {
            BucketManifest manifest = BucketCatalog.load(consumer, layout, timeout).get(request.bucketId());
            verified = manifest != null
                    && manifest.getState() == BucketState.SEALED
                    && manifest.getEpisodeCount() == deliveredIds.size()
                    && checksum.equals(manifest.getChecksum());
        }
        return new ReplayResult(entries.size(), deliveredIds.size(), missing, checksum, verified);
    }

    private List<Episode> fetch(List<IndexEntry> part) {
        Map<TopicPartition, long[]> ranges = new HashMap<>();
        Map<TopicPartition, Map<Long, IndexEntry>> wanted = new HashMap<>();
        for (IndexEntry e : part) {
            TopicPartition tp = e.topicPartition();
            ranges.merge(tp, new long[]{e.offset(), e.offset() + 1},
                    (a, b) -> new long[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            wanted.computeIfAbsent(tp, k -> new HashMap<>()).put(e.offset(), e);
        }
        List<Episode> out = new ArrayList<>(part.size());
        Set<String> seen = new HashSet<>();
        LogReader.read(consumer, ranges, r -> {
            IndexEntry entry = wanted.getOrDefault(new TopicPartition(r.topic(), r.partition()), Map.of()).get(r.offset());
            if (entry == null) {
                return;
            }
            Episode e = avro.decode(r.value(), Episode.class);
            if (e.getEpisodeId().equals(entry.episodeId()) && seen.add(e.getEpisodeId())) {
                out.add(e);
            }
        }, timeout);
        return out;
    }

    /** Sleeps so that event time advances {@code speed} times faster than wall-clock time. */
    private static final class Pacer {
        private final double speed;
        private long firstEventMicros = Long.MIN_VALUE;
        private long wallStartNanos;

        Pacer(double speed) {
            this.speed = speed;
        }

        void await(long eventMicros) {
            if (speed <= 0) {
                return;
            }
            if (firstEventMicros == Long.MIN_VALUE) {
                firstEventMicros = eventMicros;
                wallStartNanos = System.nanoTime();
                return;
            }
            long dueNanos = wallStartNanos + (long) ((eventMicros - firstEventMicros) * 1_000.0 / speed);
            long sleep = dueNanos - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("replay interrupted", e);
                }
            }
        }
    }
}
