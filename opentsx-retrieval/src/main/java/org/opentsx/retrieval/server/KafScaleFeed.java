package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opentsx.kafka.EpisodeLogTail;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogTail;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.vector.VectorAligner;
import org.opentsx.retrieval.vector.VectorJob;
import org.opentsx.retrieval.vector.VectorJson;
import org.opentsx.retrieval.vector.VectorStoreBridge;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The KafScale side of the retrieval server for one domain.
 *
 * <ul>
 *   <li>Keeps an in-memory store in step with the episodes topic ({@code tsx.<domain>.episodes.v2}): the whole log
 *   once, then whatever was appended, every {@code refresh}.</li>
 *   <li>Reads the vector topics of the domain ({@code tsx.<domain>.smart.<name>.vectors.v1}) and offers their
 *   components as series ({@link #vectorStore()}).</li>
 *   <li>Runs the vector sinks of a DAG over the episodes it holds and writes the measuring points to their topic
 *   ({@link #run}). This is one pass over the data held now, not a processor that keeps running.</li>
 * </ul>
 *
 * <p>The stores live in the heap. Loading episodes stops at {@code maxEpisodes}; {@link #truncated()} then says
 * that the store does not hold the whole topic.
 */
final class KafScaleFeed implements AutoCloseable {

    /** A run that would write more measuring points than this is refused. */
    static final int MAX_POINTS_PER_RUN = 500_000;

    private final String bootstrap;
    private final TopicLayout layout;
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final EpisodeLogTail tail;
    private final InMemorySeriesStore store;
    private final int maxEpisodes;
    private final Runnable onChange;
    private final Map<String, LogTail> vectorTails = new LinkedHashMap<>();
    private final Map<String, Map<String, VectorJson.Row>> vectorRows = new TreeMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kafscale-feed");
        t.setDaemon(true);
        return t;
    });
    private int loaded;
    private volatile boolean truncated;
    private volatile SeriesStore vectors = new InMemorySeriesStore();
    private volatile List<String> vectorTopics = List.of();

    private KafScaleFeed(String bootstrap, String domain, InMemorySeriesStore store, int maxEpisodes, Runnable onChange) {
        this.bootstrap = bootstrap;
        this.layout = new TopicLayout(domain);
        this.consumer = KafScaleClients.consumer(bootstrap);
        this.tail = new EpisodeLogTail(consumer, layout, Duration.ofMinutes(2));
        this.store = store;
        this.maxEpisodes = maxEpisodes;
        this.onChange = onChange;
    }

    /** Reads the logs once (blocking; fails when the broker cannot be reached), then follows them in the background. */
    static KafScaleFeed start(String bootstrap, String domain, Duration refresh, InMemorySeriesStore store,
                              int maxEpisodes, Runnable onChange) {
        KafScaleFeed feed = new KafScaleFeed(bootstrap, domain, store, maxEpisodes, onChange);
        int n = feed.pull();
        System.out.println("kafscale feed: " + n + " episodes from " + feed.tail.topic()
                + (feed.truncated ? " (stopped at --max-episodes " + maxEpisodes + ")" : "")
                + ", vector topics " + feed.vectorTopics);
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
        int written = 0;
        if (!truncated) {
            List<Episode> batch = new ArrayList<>();
            tail.poll(batch::add);
            int room = maxEpisodes - loaded;
            if (batch.size() > room) {
                batch = batch.subList(0, Math.max(0, room));
                truncated = true;
            }
            written = store.write(batch).written();
            loaded += written;
        }
        boolean vectorsChanged = pullVectors();
        if (written > 0 || vectorsChanged) {
            onChange.run();
        }
        return written;
    }

    /** Reads what was appended to the vector topics of the domain; true when their series changed. */
    private boolean pullVectors() {
        List<String> topics = consumer.listTopics(Duration.ofSeconds(30)).keySet().stream()
                .filter(t -> layout.smartVectorName(t) != null).sorted().toList();
        boolean changed = !topics.equals(vectorTopics);
        for (String topic : topics) {
            LogTail t = vectorTails.computeIfAbsent(topic, name -> new LogTail(consumer, name, Duration.ofMinutes(2)));
            Map<String, VectorJson.Row> rows = vectorRows.computeIfAbsent(topic, name -> new LinkedHashMap<>());
            int n = t.poll(r -> {
                VectorJson.Row row = VectorJson.parse(r.value());
                if (row != null) {
                    rows.put(VectorJson.keyText(row.key()) + "@" + row.fromMicros(), row);
                }
            });
            changed |= n > 0;
        }
        if (changed) {
            InMemorySeriesStore next = new InMemorySeriesStore();
            for (String topic : topics) {
                Map<String, VectorJson.Row> rows = vectorRows.getOrDefault(topic, Map.of());
                if (!rows.isEmpty()) {
                    next.write(VectorStoreBridge.episodes(topic, rows.values()));
                }
            }
            vectors = next;
            vectorTopics = topics;
        }
        return changed;
    }

    /**
     * Runs the vector sinks of the DAG over the episodes held now and appends the measuring points to their topics.
     *
     * @return what was written, for the caller of {@code POST /v1/dags/run}
     * @throws IllegalArgumentException with a message for the user when the DAG cannot be run here
     */
    synchronized ObjectNode run(JsonNode dag, String text) {
        VectorJob.Plan plan = VectorJob.of(dag, layout.domain(), layout.episodes());
        String hash = sha256(text);
        ObjectNode out = EpisodeJson.JSON.createObjectNode();
        out.put("dag", plan.jobs().get(0).dag());
        out.put("specHash", hash);
        ArrayNode sinks = out.putArray("sinks");
        try (Admin admin = KafScaleClients.admin(bootstrap);
             KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            for (VectorJob job : plan.jobs()) {
                List<VectorAligner.Input> inputs = VectorStoreBridge.inputs(store, job);
                List<VectorAligner.Point> points = VectorAligner.align(inputs, job.params());
                if (points.size() > MAX_POINTS_PER_RUN) {
                    throw new IllegalArgumentException(job.topic() + ": the run would write " + points.size()
                            + " measuring points, more than " + MAX_POINTS_PER_RUN + ". Use a coarser clock or fewer series.");
                }
                layout.provisionSmartVectors(admin, job.name(), (short) 1);
                long missing = 0;
                java.util.Set<String> keys = new java.util.TreeSet<>();
                for (VectorAligner.Point p : points) {
                    String key = VectorJson.keyText(p.key());
                    keys.add(key);
                    missing += p.missing();
                    producer.send(new ProducerRecord<>(job.topic(), key, VectorJson.bytes(VectorJson.record(job.name(), p, job.dag(), hash))));
                }
                producer.flush();
                ObjectNode s = sinks.addObject();
                s.put("topic", job.topic());
                s.put("vector", job.name());
                s.put("records", points.size());
                s.put("keys", keys.size());
                s.put("series", inputs.get(0).series().size());
                s.put("missingValues", missing);
                ArrayNode components = s.putArray("components");
                if (!points.isEmpty()) {
                    points.get(0).components().forEach(components::add);
                    s.put("from", org.opentsx.model.Micros.toInstant(points.stream().mapToLong(VectorAligner.Point::fromMicros).min().getAsLong()).toString());
                    s.put("to", org.opentsx.model.Micros.toInstant(points.stream().mapToLong(VectorAligner.Point::toMicros).max().getAsLong()).toString());
                }
            }
        }
        ArrayNode skipped = out.putArray("skipped");
        plan.skipped().forEach(skipped::add);
        // read the new records back at once, so the inventory shows the topic without waiting for the next round
        if (pullVectors()) {
            onChange.run();
        }
        return out;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean truncated() {
        return truncated;
    }

    /** The series of the vector topics, rebuilt whenever a topic changed. */
    SeriesStore vectorStore() {
        return vectors;
    }

    /** The vector topics of the domain found on the broker. */
    List<String> vectorTopics() {
        return vectorTopics;
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
