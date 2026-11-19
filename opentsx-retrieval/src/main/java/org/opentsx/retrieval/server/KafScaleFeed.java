package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.EpisodeLogTail;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogTail;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.vector.DagPlan;
import org.opentsx.retrieval.vector.VectorJob;
import org.opentsx.retrieval.vector.VectorJson;
import org.opentsx.retrieval.vector.VectorProcessor;
import org.opentsx.retrieval.vector.VectorStoreBridge;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
 *
 * <p>Records it cannot use are skipped and counted: episodes that do not decode ({@code undecodable}), records that
 * retention deleted before they were read ({@code lostToRetention}), vector rows that are no {@code opentsx.vector.v1}
 * record ({@code droppedVectorRows}), and records of the control and status topics of another kind
 * ({@code droppedControlRecords}, {@code droppedStatusRecords}). The counts go to the refresh line of the log and to
 * the inventory ({@link #details}).
 */
final class KafScaleFeed implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafScaleFeed.class);
    /** The answer to a write the broker did not take; the cause, with the broker address, goes to the log only. */
    static final String BROKER_UNAVAILABLE = "cannot reach the broker; try again";

    /** How long reading one vector topic may take, and how long a topic that failed is left alone. */
    private static final Duration VECTOR_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration VECTOR_RETRY = Duration.ofSeconds(90);

    private final String bootstrap;
    private final TopicLayout layout;
    private final Consumer<byte[], byte[]> consumer;
    private final Function<String, Admin> admins;
    private final Function<String, Producer<String, byte[]>> producers;
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
    /** Follow the clean and the generated series of the domain, each once something has written its topic. */
    private final Map<String, EpisodeLogTail> signalTails = new LinkedHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kafscale-feed");
        t.setDaemon(true);
        return t;
    });
    private long droppedVectorRows;
    private long droppedControlRecords;
    private long droppedStatusRecords;
    /** Partitions whose first dropped record was logged; later ones are only counted. */
    private final Set<TopicPartition> dropLogged = new HashSet<>();
    /** The counts as of the end of the last read, for readers that must not wait for one under way. */
    private volatile Map<String, Long> counts = Map.of();
    /** The counts in the last refresh line. */
    private Map<String, Long> countsLogged = Map.of();
    /** Per source of trouble, the cause chain of the failure under way. */
    private final Map<String, String> failing = new ConcurrentHashMap<>();
    /** Refreshes that failed since the last one that worked. */
    private volatile int refreshFailures;
    private int loaded;
    private volatile boolean truncated;
    private volatile SeriesStore vectors = new InMemorySeriesStore();
    private volatile List<String> vectorTopics = List.of();

    /**
     * @param consumer  reads every topic of the domain; the feed closes it
     * @param admins    give an Admin for the bootstrap address, one per write
     * @param producers give a producer for the bootstrap address, one per write
     */
    KafScaleFeed(String bootstrap, String domain, Consumer<byte[], byte[]> consumer, Function<String, Admin> admins,
                 Function<String, Producer<String, byte[]>> producers, InMemorySeriesStore store, int maxEpisodes,
                 Runnable onChange) {
        this.bootstrap = bootstrap;
        this.layout = new TopicLayout(domain);
        this.consumer = consumer;
        this.admins = admins;
        this.producers = producers;
        this.tail = new EpisodeLogTail(consumer, layout, Duration.ofMinutes(2));
        this.store = store;
        this.maxEpisodes = maxEpisodes;
        this.onChange = onChange;
    }

    /** Reads the logs once (blocking; fails when the broker cannot be reached), then follows them in the background. */
    static KafScaleFeed start(String bootstrap, String domain, Duration refresh, InMemorySeriesStore store,
                              int maxEpisodes, Runnable onChange) {
        KafScaleFeed feed = new KafScaleFeed(bootstrap, domain, KafScaleClients.consumer(bootstrap), KafScaleClients::admin,
                KafScaleClients::producer, store, maxEpisodes, onChange);
        feed.load();
        long ms = Math.max(1000, refresh.toMillis());
        feed.timer.scheduleWithFixedDelay(feed::refresh, ms, ms, TimeUnit.MILLISECONDS);
        return feed;
    }

    /** The first read of the logs; fails when the broker cannot be reached. */
    void load() {
        int n = pull();
        Map<String, Long> c = counts;
        LOG.info("feed loaded topic={} episodes={} truncated={} vectorTopics={} {}", tail.topic(), n, truncated,
                String.join(",", vectorTopics), text(c));
        countsLogged = c;
    }

    /**
     * One round of the schedule: reads what was appended since the round before. It never throws, because an
     * exception, an Error included, would cancel the schedule for good. A round that fails is logged and counted in
     * {@code refreshFailures} of the inventory; the next round tries again.
     */
    void refresh() {
        try {
            int added = pull();
            if (refreshFailures > 0) {
                refreshFailures = 0;
                onChange.run();
            }
            recovered("refresh", "feed refresh works again");
            Map<String, Long> c = counts;
            if (added > 0 || !c.equals(countsLogged)) {
                LOG.info("feed refreshed episodes={} {}", added, text(c));
                countsLogged = c;
            }
        } catch (Throwable t) {
            refreshFailures++;
            failed("refresh", "feed refresh failed; the next refresh tries again", t);
            onChange.run();
        }
    }

    /** {@code key=value} pairs of the counts, for a log line. */
    private static String text(Map<String, Long> counts) {
        StringBuilder s = new StringBuilder();
        counts.forEach((k, v) -> s.append(s.length() == 0 ? "" : " ").append(k).append('=').append(v));
        return s.toString();
    }

    /**
     * Logs a failure of one source when it starts or its cause changes: WARN, ERROR for an Error, with the cause chain;
     * the stack trace at DEBUG. The same failure in the next round is not logged again.
     */
    private void failed(String source, String event, Throwable t) {
        String cause = RetrievalServer.causes(t);
        if (cause.equals(failing.put(source, cause))) {
            return;
        }
        if (t instanceof Error) {
            LOG.error("{} source={} cause={}", event, source, cause);
        } else {
            LOG.warn("{} source={} cause={}", event, source, cause);
        }
        LOG.debug("stack trace of the failure source={}", source, t);
    }

    /** Logs at INFO that a source works again after a failure. */
    private void recovered(String source, String event) {
        if (failing.remove(source) != null) {
            LOG.info("{} source={}", event, source);
        }
    }

    /** Counts a record that was read and not used; the first one of its partition is logged. */
    private void dropped(ConsumerRecord<byte[], byte[]> r, String counter, String reason) {
        if (dropLogged.add(new TopicPartition(r.topic(), r.partition()))) {
            LOG.warn("record dropped and counted in {}; later ones of this partition are only counted topic={} partition={}"
                    + " offset={} reason={}", counter, r.topic(), r.partition(), r.offset(), reason);
        }
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
        for (String topic : List.of(layout.clean(), layout.synthetic())) {
            try {
                written += pullSignals(topic);
                recovered(topic, "topic readable again");
            } catch (RuntimeException e) {
                failed(topic, "topic cannot be read now; the next refresh tries again", e);
            }
        }
        boolean vectorsChanged = pullVectors();
        try {
            pullDags();
            recovered(dagTopics(), "topic readable again");
        } catch (RuntimeException e) {
            failed(dagTopics(), "topic cannot be read now; the next refresh tries again", e);
        }
        Map<String, Long> before = counts;
        counts = count();
        if (written > 0 || vectorsChanged || !counts.equals(before)) {
            onChange.run();
        }
        return written;
    }

    /** The control and the status topic, as the source of a failure in the log. */
    private String dagTopics() {
        return layout.dags() + "," + layout.dagStatus();
    }

    /** What was skipped so far, summed over every topic the feed reads. */
    private Map<String, Long> count() {
        long undecodable = tail.undecodable();
        long lost = tail.lostToRetention();
        for (EpisodeLogTail t : signalTails.values()) {
            undecodable += t.undecodable();
            lost += t.lostToRetention();
        }
        for (LogTail t : vectorTails.values()) {
            lost += t.lostToRetention();
        }
        if (controlTail != null) {
            lost += controlTail.lostToRetention() + statusTail.lostToRetention();
        }
        Map<String, Long> c = new LinkedHashMap<>();
        c.put("undecodable", undecodable);
        c.put("lostToRetention", lost);
        c.put("droppedVectorRows", droppedVectorRows);
        c.put("droppedControlRecords", droppedControlRecords);
        c.put("droppedStatusRecords", droppedStatusRecords);
        return c;
    }

    /**
     * Reads what flows or generators appended to a topic of series written for signals into the same store as the
     * episodes: those series carry the tags {@code system} and {@code signal}, so they stay apart from the raw ones.
     */
    private int pullSignals(String topic) {
        EpisodeLogTail tail = signalTails.get(topic);
        if (tail == null) {
            if (!consumer.listTopics(Duration.ofSeconds(30)).containsKey(topic)) {
                return 0;
            }
            tail = new EpisodeLogTail(consumer, topic, Duration.ofMinutes(2));
            signalTails.put(topic, tail);
        }
        List<Episode> batch = new ArrayList<>();
        tail.poll(batch::add);
        return batch.isEmpty() ? 0 : store.write(batch).written();
    }

    /**
     * Writes generated episodes to the topic of the generated series of the domain and to nowhere else: a generator
     * must never write among measurements.
     *
     * @throws IllegalArgumentException   when asked to write to another topic
     * @throws RetrievalServer.Unavailable when the broker does not take the episodes
     */
    synchronized void writeGenerated(String topic, List<Episode> episodes) {
        if (!topic.equals(layout.synthetic())) {
            throw new IllegalArgumentException("generated series go to " + layout.synthetic() + ", not to " + topic);
        }
        org.opentsx.model.OpenTsxAvro avro = new org.opentsx.model.OpenTsxAvro();
        try (Admin admin = admins.apply(bootstrap);
             Producer<String, byte[]> producer = producers.apply(bootstrap)) {
            layout.provisionTopic(admin, topic, (short) 1);
            List<java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata>> sent = new ArrayList<>();
            for (Episode e : episodes) {
                sent.add(producer.send(new ProducerRecord<>(topic, e.getSeries().getSeriesId(), avro.encode(e))));
            }
            for (var f : sent) {
                f.get(KafScaleClients.DELIVERY_TIMEOUT.toSeconds() + 30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing to " + topic, e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | KafkaException
                 | IllegalStateException e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new RetrievalServer.Unavailable(BROKER_UNAVAILABLE,
                    new IllegalStateException("the broker did not take every generated episode for " + topic, cause));
        }
        // so the caller sees the series at once
        store.write(episodes);
        onChange.run();
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
                    } else {
                        droppedVectorRows++;
                        dropped(r, "droppedVectorRows", "not an " + VectorJob.SCHEMA + " record");
                    }
                });
                changed |= n > 0;
                changed |= unreadable.remove(topic) != null;
                recovered(topic, "vector topic readable again");
            } catch (RuntimeException e) {
                // one topic the broker does not hand out must not keep the others, or the episodes, from being read
                changed |= unreadable.put(topic, now + VECTOR_RETRY.toNanos()) == null;
                failed(topic, "vector topic cannot be read; the next try is in " + VECTOR_RETRY, e);
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
        return out;
    }

    /** Stops the DAG of this name: appends the record to the control topic. */
    synchronized ObjectNode stop(String name, String by) {
        if (name == null || !VectorProcessor.DAG_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name the DAG to stop (letters, digits, dots, hyphens and underscores)");
        }
        control(name, VectorProcessor.stopRecord(name, by));
        return EpisodeJson.JSON.createObjectNode().put("dag", name).put("state", "stopped");
    }

    /** @throws RetrievalServer.Unavailable when the broker does not take the record */
    private void control(String name, byte[] record) {
        try (Admin admin = admins.apply(bootstrap);
             Producer<String, byte[]> producer = producers.apply(bootstrap)) {
            layout.provisionDagTopics(admin, (short) 1);
            producer.send(new ProducerRecord<>(layout.dags(), name, record))
                    .get(KafScaleClients.DELIVERY_TIMEOUT.toSeconds() + 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing to " + layout.dags(), e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | KafkaException
                 | IllegalStateException e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new RetrievalServer.Unavailable(BROKER_UNAVAILABLE,
                    new IllegalStateException("the broker did not take the record for " + layout.dags(), cause));
        }
        try {
            pullDags();     // so the caller sees its own record at once
        } catch (RuntimeException e) {
            // the record is written; the next round reads it
            failed(dagTopics(), "topic cannot be read now; the next refresh tries again", e);
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
            } else {
                droppedControlRecords++;
                dropped(r, "droppedControlRecords", "not an " + VectorProcessor.CONTROL_SCHEMA + " record with a dag");
            }
        });
        statusTail.poll(r -> {
            JsonNode n = json(r.value());
            if (n != null && VectorProcessor.STATUS_SCHEMA.equals(n.path("schema").asText()) && r.key() != null) {
                statuses.put(new String(r.key(), StandardCharsets.UTF_8), n);
            } else {
                droppedStatusRecords++;
                dropped(r, "droppedStatusRecords", "not an " + VectorProcessor.STATUS_SCHEMA + " record with a key");
            }
        });
    }

    /** The record as JSON; null when it is empty or no JSON. */
    private static JsonNode json(byte[] value) {
        if (value == null) {
            return null;
        }
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

    /**
     * What the feed adds to the {@code source} object of the inventory: the vector topics, those that could not be
     * read, the counts of skipped records and {@code refreshFailures}. It does not wait for a read under way.
     */
    void details(ObjectNode src) {
        ArrayNode vt = src.putArray("vectorTopics");
        vectorTopics().forEach(vt::add);
        if (!unreadableVectorTopics().isEmpty()) {
            ArrayNode bad = src.putArray("unreadableVectorTopics");
            unreadableVectorTopics().forEach(bad::add);
        }
        counts.forEach(src::put);
        src.put("refreshFailures", refreshFailures);
    }

    /** Topic names by role, as shown in the inventory. */
    static Map<String, String> topics(String domain) {
        TopicLayout layout = new TopicLayout(domain);
        Map<String, String> t = new LinkedHashMap<>();
        t.put("episodes", layout.episodes());
        t.put("clean", layout.clean());
        t.put("synthetic", layout.synthetic());
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
