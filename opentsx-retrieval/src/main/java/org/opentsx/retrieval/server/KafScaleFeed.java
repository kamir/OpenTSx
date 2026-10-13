package org.opentsx.retrieval.server;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opentsx.kafka.EpisodeLogTail;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.InMemorySeriesStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps an in-memory store in step with the episodes topic of one domain ({@code tsx.<domain>.episodes.v2}): the
 * whole log once, then whatever was appended, every {@code refresh}.
 *
 * <p>The store lives in the heap. Loading stops at {@code maxEpisodes}; {@link #truncated()} then says that the
 * store does not hold the whole topic. For more data use the Iceberg store.
 */
final class KafScaleFeed implements AutoCloseable {

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final EpisodeLogTail tail;
    private final InMemorySeriesStore store;
    private final int maxEpisodes;
    private final Runnable onChange;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kafscale-feed");
        t.setDaemon(true);
        return t;
    });
    private int loaded;
    private volatile boolean truncated;

    private KafScaleFeed(String bootstrap, String domain, InMemorySeriesStore store, int maxEpisodes, Runnable onChange) {
        this.consumer = KafScaleClients.consumer(bootstrap);
        this.tail = new EpisodeLogTail(consumer, new TopicLayout(domain), Duration.ofMinutes(2));
        this.store = store;
        this.maxEpisodes = maxEpisodes;
        this.onChange = onChange;
    }

    /** Reads the log once (blocking; fails when the broker cannot be reached), then follows it in the background. */
    static KafScaleFeed start(String bootstrap, String domain, Duration refresh, InMemorySeriesStore store,
                              int maxEpisodes, Runnable onChange) {
        KafScaleFeed feed = new KafScaleFeed(bootstrap, domain, store, maxEpisodes, onChange);
        int n = feed.pull();
        System.out.println("kafscale feed: " + n + " episodes from " + feed.tail.topic()
                + (feed.truncated ? " (stopped at --max-episodes " + maxEpisodes + ")" : ""));
        long ms = Math.max(1000, refresh.toMillis());
        feed.timer.scheduleWithFixedDelay(() -> {
            try {
                int added = feed.pull();
                if (added > 0) {
                    System.out.println("kafscale feed: +" + added + " episodes");
                }
            } catch (RuntimeException e) {
                System.err.println("kafscale feed: " + e);     // keep following; the next round retries
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
        return feed;
    }

    private synchronized int pull() {
        if (truncated) {
            return 0;
        }
        List<Episode> batch = new ArrayList<>();
        tail.poll(batch::add);
        int room = maxEpisodes - loaded;
        if (batch.size() > room) {
            batch = batch.subList(0, Math.max(0, room));
            truncated = true;
        }
        int written = store.write(batch).written();
        loaded += written;
        if (written > 0) {
            onChange.run();
        }
        return written;
    }

    boolean truncated() {
        return truncated;
    }

    /** Topic names by role, as shown in the inventory. */
    static Map<String, String> topics(String domain) {
        TopicLayout layout = new TopicLayout(domain);
        Map<String, String> t = new LinkedHashMap<>();
        t.put("episodes", layout.episodes());
        t.put("observations", layout.observations());
        t.put("seriesStats", layout.seriesStats());
        t.put("buckets", layout.buckets());
        t.put("series", layout.series());
        return t;
    }

    @Override
    public void close() {
        timer.shutdownNow();
        synchronized (this) {
            consumer.close(Duration.ofSeconds(5));
        }
    }
}
