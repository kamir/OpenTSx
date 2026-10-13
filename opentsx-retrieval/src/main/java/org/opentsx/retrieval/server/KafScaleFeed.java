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
import org.opentsx.retrieval.vector.DagPlan;
import org.opentsx.retrieval.vector.VectorJson;
import org.opentsx.retrieval.vector.VectorProcessor;
import org.opentsx.retrieval.vector.VectorStoreBridge;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
 *   <li>Deploys and stops DAGs for the vector processor by writing to its control topic ({@link #deploy},
 *   {@link #stop}), and reads back what the processor reports ({@link #dags()}).</li>
 * </ul>
 *
 * <p>The stores live in the heap. Loading episodes stops at {@code maxEpisodes}; {@link #truncated()} then says
 * that the store does not hold the whole topic.
 */
final class KafScaleFeed implements AutoCloseable {

    /** How long reading one vector topic may take, and how long a topic that failed is left alone. */
    private static final Duration VECTOR_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration VECTOR_RETRY = Duration.ofSeconds(90);

    private final String bootstrap;
    private final TopicLayout layout;
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final EpisodeLogTail tail;
    private final InMemorySeriesStore store;
    private final int maxEpisodes;
    private final Runnable onChange;
    private final Map<String, LogTail> vectorTails = new LinkedHashMap<>();
    private final Map<String, Map<String, VectorJson.Row>> vectorRows = new TreeMap<>();
    /** Vector topics whose last read failed, with the time (nanoTime) of the next try. */
    private final Map<String, Long> unreadable = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, JsonNode> deployments = new TreeMap<>();
    private final Map<String, JsonNode> statuses = new TreeMap<>();
    private LogTail controlTail;
    private LogTail statusTail;
    /** Follows the clean series of the domain once a flow has written the topic. */
    private EpisodeLogTail cleanTail;
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
        try {
            written += pullClean();
        } catch (RuntimeException e) {
            System.err.println("kafscale feed: " + layout.clean() + " cannot be read now: " + e.getMessage());
        }
        boolean vectorsChanged = pullVectors();
        try {
            pullDags();
        } catch (RuntimeException e) {
            System.err.println("kafscale feed: the DAG topics cannot be read now: " + e.getMessage());
        }
        if (written > 0 || vectorsChanged) {
            onChange.run();
        }
        return written;
    }

    /**
     * Reads what flows appended to the clean series of the domain into the same store as the episodes: the clean
     * series carry the tags {@code system} and {@code signal}, so they stay apart from the raw ones.
     */
    private int pullClean() {
        if (cleanTail == null) {
            if (!consumer.listTopics(Duration.ofSeconds(30)).containsKey(layout.clean())) {
                return 0;
            }
            cleanTail = new EpisodeLogTail(consumer, layout.clean(), Duration.ofMinutes(2));
        }
        List<Episode> batch = new ArrayList<>();
        cleanTail.poll(batch::add);
        return batch.isEmpty() ? 0 : store.write(batch).written();
    }

    /** Reads what was appended to the vector topics of the domain; true when their series changed. */
    private boolean pullVectors() {
        List<String> topics = consumer.listTopics(Duration.ofSeconds(30)).keySet().stream()
                .filter(t -> layout.smartVectorName(t) != null).sorted().toList();
        boolean changed = !topics.equals(vectorTopics);
        long now = System.nanoTime();
        for (String topic : topics) {
            Long retryAt = unreadable.get(topic);
            if (retryAt != null && now - retryAt < 0) {
                continue;       // failed a short while ago: do not spend every round waiting for it
            }
            LogTail t = vectorTails.computeIfAbsent(topic, name -> new LogTail(consumer, name, VECTOR_READ_TIMEOUT));
            Map<String, VectorJson.Row> rows = vectorRows.computeIfAbsent(topic, name -> new LinkedHashMap<>());
            try {
                int n = t.poll(r -> {
                    VectorJson.Row row = VectorJson.parse(r.value());
                    if (row != null) {
                        rows.put(VectorJson.keyText(row.key()) + "@" + row.fromMicros(), row);
                    }
                });
                changed |= n > 0;
                changed |= unreadable.remove(topic) != null;
            } catch (RuntimeException e) {
                // one topic the broker does not hand out must not keep the others, or the episodes, from being read
                changed |= unreadable.put(topic, now + VECTOR_RETRY.toNanos()) == null;
                System.err.println("kafscale feed: " + topic + " cannot be read, next try in " + VECTOR_RETRY + ": " + e.getMessage());
            }
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
     * Deploys the DAG: checks that the vector processor can run it and appends a record to the control topic
     * {@link TopicLayout#dags()}. The processor takes it up; a DAG of the same name is replaced.
     *
     * @throws IllegalArgumentException with a message for the user when the DAG cannot be run
     */
    synchronized ObjectNode deploy(JsonNode dag, String text, String by) {
        String name = VectorProcessor.dagName(text);
        DagPlan plan = DagPlan.of(dag, layout, "");
        control(name, VectorProcessor.deployRecord(name, text, by));
        ObjectNode out = EpisodeJson.JSON.createObjectNode();
        out.put("dag", name).put("state", "deployed");
        ArrayNode sinks = out.putArray("sinks");
        plan.vectors().forEach(j -> sinks.addObject().put("vector", j.name()).put("kind", "vector").put("topic", j.topic()).put("series", j.seriesIds().size()));
        plan.series().forEach(j -> sinks.addObject().put("vector", j.name()).put("kind", "series").put("topic", j.topic())
                .put("signal", j.target().signal()).put("series", j.seriesIds().size()));
        ArrayNode skipped = out.putArray("skipped");
        plan.skipped().forEach(skipped::add);
        return out;
    }

    /** Stops the DAG of this name: appends the record to the control topic. */
    synchronized ObjectNode stop(String name, String by) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("name the DAG to stop (letters, digits, dots, hyphens and underscores)");
        }
        control(name, VectorProcessor.stopRecord(name, by));
        return EpisodeJson.JSON.createObjectNode().put("dag", name).put("state", "stopped");
    }

    private void control(String name, byte[] record) {
        try (Admin admin = KafScaleClients.admin(bootstrap);
             KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            layout.provisionDagTopics(admin, (short) 1);
            producer.send(new ProducerRecord<>(layout.dags(), name, record))
                    .get(KafScaleClients.DELIVERY_TIMEOUT.toSeconds() + 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing to " + layout.dags(), e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("the broker did not confirm the record for " + layout.dags() + " ("
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "); try again", cause);
        }
        try {
            pullDags();     // so the caller sees its own record at once
        } catch (RuntimeException e) {
            // the record is written; the next round reads it
        }
    }

    /** Reads what was appended to the control and the status topic of the vector processor. */
    private void pullDags() {
        if (controlTail == null) {
            controlTail = new LogTail(consumer, layout.dags(), VECTOR_READ_TIMEOUT);
            statusTail = new LogTail(consumer, layout.dagStatus(), VECTOR_READ_TIMEOUT);
        }
        controlTail.poll(r -> {
            JsonNode n = json(r.value());
            if (n != null && VectorProcessor.CONTROL_SCHEMA.equals(n.path("schema").asText()) && n.hasNonNull("dag")) {
                ObjectNode slim = ((ObjectNode) n).deepCopy();
                slim.remove("spec");        // the document can be large and is not needed to show the state
                deployments.put(n.path("dag").asText(), slim);
            }
        });
        statusTail.poll(r -> {
            JsonNode n = json(r.value());
            if (n != null && VectorProcessor.STATUS_SCHEMA.equals(n.path("schema").asText()) && r.key() != null) {
                statuses.put(new String(r.key(), StandardCharsets.UTF_8), n);
            }
        });
    }

    private static JsonNode json(byte[] value) {
        try {
            return EpisodeJson.JSON.readTree(value);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * The DAGs of the domain as the control topic and the vector processor see them: per DAG what was asked for
     * (deployed or stopped, by whom, when) and what the processor reports per sink. {@code now} lets a reader judge
     * how old a report is: a deployed DAG without a recent report has no processor behind it.
     */
    synchronized ObjectNode dags() {
        return dagView(deployments, statuses, org.opentsx.model.Micros.toInstant(org.opentsx.model.Micros.now()).toString());
    }

    /**
     * Puts the latest control record per DAG together with the reports of its sinks. A report older than the control
     * record belongs to what ran before: under a stopped DAG it is shown as stopped, under a DAG deployed again it is
     * left out until the processor reports for the new deployment.
     */
    static ObjectNode dagView(Map<String, JsonNode> deployments, Map<String, JsonNode> statuses, String now) {
        ObjectNode out = EpisodeJson.JSON.createObjectNode();
        out.put("now", now);
        ArrayNode list = out.putArray("dags");
        for (Map.Entry<String, JsonNode> d : deployments.entrySet()) {
            String asked = d.getValue().path("at").asText();
            boolean stopped = "stopped".equals(d.getValue().path("state").asText());
            ObjectNode o = list.addObject();
            o.put("dag", d.getKey());
            o.put("desired", d.getValue().path("state").asText());
            o.put("by", d.getValue().path("by").asText());
            o.put("at", asked);
            ArrayNode sinks = o.putArray("sinks");
            for (Map.Entry<String, JsonNode> st : statuses.entrySet()) {
                boolean before = st.getValue().path("at").asText().compareTo(asked) < 0;
                if (st.getKey().startsWith(d.getKey() + "/")) {
                    if (stopped) {
                        sinks.add(((ObjectNode) st.getValue().deepCopy()).put("state", "stopped"));
                    } else if (!before) {
                        sinks.add(st.getValue());
                    }
                } else if (st.getKey().equals(d.getKey()) && !before && !stopped) {
                    o.put("refused", st.getValue().path("error").asText());     // the processor could not run this document
                }
            }
        }
        return out;
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

    /** Vector topics the broker did not hand out at the last try; their series may be out of date. */
    List<String> unreadableVectorTopics() {
        return unreadable.keySet().stream().sorted().toList();
    }

    /** Topic names by role, as shown in the inventory. */
    static Map<String, String> topics(String domain) {
        TopicLayout layout = new TopicLayout(domain);
        Map<String, String> t = new LinkedHashMap<>();
        t.put("episodes", layout.episodes());
        t.put("clean", layout.clean());
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
