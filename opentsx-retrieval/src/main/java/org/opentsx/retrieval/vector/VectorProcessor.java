package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogTail;
import org.opentsx.kafka.Patience;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.clean.SeriesStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Keeps the vector sinks of deployed DAGs up to date: it follows the episodes topic of a domain and appends the
 * measuring points to {@code tsx.<domain>.smart.<name>.vectors.v1} as they become final.
 * Rules: docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 5b.
 *
 * <ul>
 *   <li><b>What runs</b> comes from the control topic {@link TopicLayout#dags()}: the latest record per DAG name
 *   says deployed (with the DAG document) or stopped. A deployment survives a restart of the processor. A vector topic
 *   has one writer: a DAG whose vector topic a sink of another DAG writes is refused ({@link #sinkConflict}).</li>
 *   <li><b>One runner per vector sink</b> with its own consumer, positions and state. A new or changed DAG starts
 *   from the beginning of the source topic (or from now, {@code source.start: latest}) without touching the others.</li>
 *   <li><b>Checkpoint</b> per runner in the state directory: positions and {@link VectorStream} state, written only
 *   after the broker confirmed every measuring point. Waiting for that does not stop the loop: every runner has its
 *   own producer, and one whose sends are not confirmed is closed and replaced when the runner starts again. After a crash the runner repeats at most what it wrote since
 *   the last checkpoint; readers take the later record per key and tick, and the values are the same. A checkpoint
 *   that cannot be read fails the runner and stays as it is.</li>
 *   <li><b>Status</b> per runner goes to {@link TopicLayout#dagStatus()}.</li>
 *   <li><b>Log</b>: deployments and stops at INFO, a refused DAG, a control topic that cannot be read and the first
 *   undecodable record of each partition of a sink at WARN, a failed sink at ERROR with the cause chain; the stack
 *   trace of a failure at DEBUG, once per distinct failure of a sink.</li>
 * </ul>
 *
 * <p>Exactly one processor per domain: the runners assign all partitions themselves, there is no consumer group.
 */
public final class VectorProcessor implements AutoCloseable {

    public static final String CONTROL_SCHEMA = "opentsx.dag-control.v1";
    public static final String STATUS_SCHEMA = "opentsx.dag-status.v1";
    /** Name of a DAG: the {@code dag:} of its document and the key of its control records. */
    public static final Pattern DAG_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Logger LOG = LoggerFactory.getLogger(VectorProcessor.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Timing of the processor. The defaults are for a service; tests use short ones. */
    public record Timing(Duration checkpointEvery, Duration statusEvery, Duration controlEvery, Duration retryAfter, Duration poll,
                         Duration confirmWithin) {
        public static Timing standard() {
            // a send retries until the delivery timeout; a confirmation that is still missing a while later never comes
            return new Timing(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(60),
                    Duration.ofMillis(200), KafScaleClients.DELIVERY_TIMEOUT.plusSeconds(30));
        }
    }

    private final Supplier<Consumer<byte[], byte[]>> consumers;
    private final Supplier<Producer<String, byte[]>> producers;
    /** Producer of the status records. Replaced when a send fails, so reporting survives a producer that got stuck. */
    private Producer<String, byte[]> statusProducer;
    private volatile boolean statusBroken;
    /** Whether the last status record the broker answered for was refused; set and read on the producer thread. */
    private final AtomicBoolean statusFailing = new AtomicBoolean();
    /** Records on the control topic that are no control record; reported in the status of every sink. */
    private long ignoredControlRecords;
    /** The cause chain of the failure under way while the control topic cannot be read; null while it can. */
    private String controlFailure;
    private final TopicLayout layout;
    private final Path stateDir;
    private final Timing timing;
    /** Nanoseconds as {@link System#nanoTime()} gives them; the times of the {@link Timing} are measured with it. */
    private final LongSupplier nanoTime;
    private java.util.function.Consumer<String> sinkProvisioner = name -> { };
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final Map<String, Runner> runners = new TreeMap<>();
    /** Per DAG name: the hash of the document that is deployed, or "" when the DAG is stopped or was refused. */
    private final Map<String, String> deployed = new HashMap<>();
    private Consumer<byte[], byte[]> controlConsumer;
    private LogTail control;
    private long nextControl;

    /**
     * @param consumers gives a new consumer for the control topic and for each runner
     * @param producers gives a new producer: one per runner and one for the status records. They are separate so
     *                  that a sink the broker does not confirm cannot hold up the others; a producer whose send
     *                  failed is closed and replaced
     * @param stateDir  directory of the checkpoints; created when missing
     */
    public VectorProcessor(Supplier<Consumer<byte[], byte[]>> consumers, Supplier<Producer<String, byte[]>> producers,
                           TopicLayout layout, Path stateDir, Timing timing) {
        this(consumers, producers, layout, stateDir, timing, System::nanoTime);
    }

    VectorProcessor(Supplier<Consumer<byte[], byte[]>> consumers, Supplier<Producer<String, byte[]>> producers,
                    TopicLayout layout, Path stateDir, Timing timing, LongSupplier nanoTime) {
        this.consumers = consumers;
        this.producers = producers;
        this.layout = layout;
        this.stateDir = stateDir;
        this.timing = timing;
        this.nanoTime = nanoTime;
        try {
            Files.createDirectories(stateDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Called with the name of a vector before its runner writes for the first time, to create the sink topic when
     * it is missing. Without one, the topic must exist or the broker must create topics on its own.
     */
    public void sinkProvisioner(java.util.function.Consumer<String> provisioner) {
        this.sinkProvisioner = provisioner;
    }

    /** Runs until {@code stop} says so, then writes the checkpoints. */
    public void run(BooleanSupplier stop) {
        while (!stop.getAsBoolean()) {
            step();
        }
        close();
    }

    /** One round: take up changes of the control topic, let every runner read and write, report. */
    public void step() {
        long now = nanoTime.getAsLong();
        if (control == null || now - nextControl >= 0) {
            readControl();
            nextControl = nanoTime.getAsLong() + timing.controlEvery().toNanos();
        }
        for (Runner r : runners.values()) {
            r.step();
        }
        if (runners.isEmpty()) {
            sleep(timing.poll());
        }
    }

    /** The runners by "dag/vector" with their state, for tests and logs. */
    public Map<String, String> states() {
        Map<String, String> out = new TreeMap<>();
        runners.forEach((id, r) -> out.put(id, r.state));
        return out;
    }

    @Override
    public void close() {
        for (Runner r : runners.values()) {
            r.shutdown();
        }
        runners.clear();
        if (controlConsumer != null) {
            controlConsumer.close(Duration.ofSeconds(5));
            controlConsumer = null;
            control = null;
        }
        if (statusProducer != null) {
            statusProducer.close(Duration.ofSeconds(5));
            statusProducer = null;
        }
    }

    /** Sends a status record without waiting for it. A failed send makes the next one use a new producer. */
    private void status(String key, byte[] value) {
        if (statusProducer == null || statusBroken) {
            if (statusProducer != null) {
                closeNow(statusProducer);
            }
            statusProducer = producers.get();
            statusBroken = false;
        }
        statusProducer.send(new ProducerRecord<>(layout.dagStatus(), key, value), (meta, error) -> {
            if (error != null) {
                statusBroken = true;
                if (statusFailing.compareAndSet(false, true)) {
                    LOG.warn("status record not sent; the next one goes through a new producer topic={} key={} cause={}",
                            layout.dagStatus(), key, causes(error));
                }
            } else if (statusFailing.compareAndSet(true, false)) {
                LOG.info("status records are sent again topic={}", layout.dagStatus());
            }
        });
    }

    /** Closes a producer without waiting for what it has not sent: it is being given up. */
    private static void closeNow(Producer<String, byte[]> producer) {
        try {
            producer.close(Duration.ZERO);
        } catch (RuntimeException ignored) {
            // a producer of a broker that does not answer may fail to close; it is gone either way
        }
    }

    /** A control record: deploy the DAG document under its name. */
    public static byte[] deployRecord(String dag, String spec, String by) {
        ObjectNode n = JSON.createObjectNode();
        n.put("schema", CONTROL_SCHEMA).put("dag", dag).put("state", "deployed").put("spec", spec).put("by", by)
                .put("at", Micros.toInstant(Micros.now()).toString());
        return bytes(n);
    }

    /** A control record: stop the DAG of this name. */
    public static byte[] stopRecord(String dag, String by) {
        ObjectNode n = JSON.createObjectNode();
        n.put("schema", CONTROL_SCHEMA).put("dag", dag).put("state", "stopped").put("by", by)
                .put("at", Micros.toInstant(Micros.now()).toString());
        return bytes(n);
    }

    /** The name of the DAG in a document, as the key of its control records. */
    public static String dagName(String spec) {
        try {
            String name = YAML.readTree(spec).path("dag").asText("");
            if (!DAG_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("the DAG needs a name (dag: ...) of letters, digits, dots, hyphens and underscores");
            }
            return name;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the DAG is not valid YAML: " + e.getOriginalMessage(), e);
        }
    }

    /** The record as a control record; null when it is empty, no JSON, of another schema or without a dag. */
    private static JsonNode controlRecord(byte[] value) {
        if (value == null) {
            return null;
        }
        JsonNode n;
        try {
            n = JSON.readTree(value);
        } catch (IOException e) {
            return null;
        }
        return CONTROL_SCHEMA.equals(n.path("schema").asText()) && n.hasNonNull("dag") ? n : null;
    }

    /** The vector topics a DAG document writes: the topics of its sinks of {@link VectorJob#SCHEMA}. */
    static Set<String> vectorTopics(JsonNode dag) {
        Set<String> topics = new TreeSet<>();
        for (JsonNode sink : dag.path("sinks")) {
            if (VectorJob.SCHEMA.equals(sink.path("schema").asText()) && sink.hasNonNull("topic")) {
                topics.add(sink.path("topic").asText());
            }
        }
        return topics;
    }

    /**
     * The vector topics of every DAG whose latest control record says deployed, by DAG name, read from the start of the
     * control topic to its end. A document that is no YAML is left out: the processor refuses it, so it writes
     * nothing.
     */
    public static Map<String, Set<String>> deployedVectorTopics(Consumer<byte[], byte[]> consumer, TopicLayout layout) {
        Map<String, JsonNode> latest = new TreeMap<>();
        new LogTail(consumer, layout.dags(), Duration.ofSeconds(30)).poll(r -> {
            JsonNode n = controlRecord(r.value());
            if (n != null) {
                latest.put(n.path("dag").asText(), n);
            }
        });
        Map<String, Set<String>> out = new TreeMap<>();
        latest.forEach((dag, n) -> {
            if ("deployed".equals(n.path("state").asText())) {
                try {
                    out.put(dag, vectorTopics(YAML.readTree(n.path("spec").asText(""))));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // the processor refuses a document that is no YAML, so it writes nothing
                }
            }
        });
        return out;
    }

    /**
     * Each vector topic has one writer. The refusal when another DAG in {@code written} (vector topics by DAG name)
     * writes a vector topic of the document {@code dag} under the name {@code name}; empty when none does. The same
     * name deployed again is no other writer.
     */
    public static Optional<String> sinkConflict(String name, JsonNode dag, Map<String, Set<String>> written) {
        for (String topic : vectorTopics(dag)) {
            for (Map.Entry<String, Set<String>> other : written.entrySet()) {
                if (!other.getKey().equals(name) && other.getValue().contains(topic)) {
                    return Optional.of(name + ": sink topic " + topic + " is already written by DAG " + other.getKey()
                            + "; stop that DAG first or choose another sink");
                }
            }
        }
        return Optional.empty();
    }

    private void readControl() {
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        try {
            if (control == null) {
                controlConsumer = consumers.get();
                control = new LogTail(controlConsumer, layout.dags(), Duration.ofSeconds(30));
            }
            control.poll(r -> {
                JsonNode n = controlRecord(r.value());
                if (n != null) {
                    latest.put(n.path("dag").asText(), n);
                } else if (++ignoredControlRecords == 1) {
                    LOG.warn("control record ignored and counted in ignoredControlRecords; later ones are only counted"
                            + " topic={} partition={} offset={}", r.topic(), r.partition(), r.offset());
                }
            });
            if (controlFailure != null) {
                controlFailure = null;
                LOG.info("control topic readable again topic={}", layout.dags());
            }
        } catch (RuntimeException e) {
            // a refusal, or the time-out of the read, which LogTail reports as IllegalStateException
            if (!Patience.isRefusal(e) && !(e instanceof IllegalStateException)) {
                throw e;
            }
            String cause = causes(e);
            if (!cause.equals(controlFailure)) {
                controlFailure = cause;
                LOG.warn("control topic cannot be read now; the sinks that run go on topic={} cause={}", layout.dags(), cause);
                LOG.debug("stack trace of the failure topic={}", layout.dags(), e);
            }
        }
        latest.forEach(this::apply);
    }

    private void apply(String dag, JsonNode record) {
        boolean deploy = "deployed".equals(record.path("state").asText());
        String spec = record.path("spec").asText("");
        String hash = deploy ? sha256(spec) : "";
        if (hash.equals(deployed.get(dag))) {
            return;
        }
        // whatever ran under this name ends; a changed DAG starts again from its own beginning
        new ArrayList<>(runners.keySet()).stream().filter(id -> id.startsWith(dag + "/")).forEach(id -> {
            Runner old = runners.remove(id);
            old.shutdown();
            old.forget();
            if (!deploy) {
                old.state = "stopped";
                old.report();
            }
        });
        deployed.put(dag, hash);
        if (!deploy) {
            LOG.info("dag stopped dag={}", dag);
            return;
        }
        try {
            JsonNode document = YAML.readTree(spec);
            DagPlan plan = DagPlan.of(document, layout, hash);
            Map<String, Set<String>> written = new TreeMap<>();
            runners.values().stream().filter(r -> r.job.kind().equals("vector"))
                    .forEach(r -> written.computeIfAbsent(r.job.dag(), d -> new TreeSet<>()).add(r.job.topic()));
            Optional<String> conflict = sinkConflict(dag, document, written);
            if (conflict.isPresent()) {
                throw new IllegalArgumentException(conflict.get());
            }
            List<Sink> sinks = new ArrayList<>();
            plan.vectors().forEach(job -> sinks.add(new VectorSink(job, hash)));
            plan.series().forEach(job -> sinks.add(new SeriesSink(job, avro)));
            for (Sink sink : sinks) {
                runners.put(dag + "/" + sink.name(), new Runner(sink, hash));
                LOG.info("sink deployed dag={} sink={} kind={} topic={}", dag, sink.name(), sink.kind(), sink.topic());
            }
        } catch (IllegalArgumentException | IOException e) {
            deployed.put(dag, "");
            reportRefusal(dag, hash, e.getMessage());
            LOG.warn("dag refused dag={} cause={}", dag, causes(e));
        }
    }

    private void reportRefusal(String dag, String hash, String error) {
        ObjectNode n = JSON.createObjectNode();
        n.put("schema", STATUS_SCHEMA).put("dag", dag).put("state", "failed").put("specHash", hash).put("error", error)
                .put("at", Micros.toInstant(Micros.now()).toString());
        status(dag, bytes(n));
    }

    /** One sink of a deployed DAG: what it reads, where it writes, and the stream that computes it. */
    private interface Sink {
        String dag();

        String name();

        String topic();

        /** vector or series. */
        String kind();

        /** The topic the sink reads its series from. */
        String sourceTopic();

        boolean fromStart();

        /** @param saved the stream state of a checkpoint, or null to begin anew */
        Work open(JsonNode saved);
    }

    /**
     * The stream of one sink: takes the episodes of the source and hands out the records to write.
     *
     * @param add takes one episode and hands every record it gives, with its key, to the consumer
     */
    private record Work(java.util.function.BiConsumer<Episode, java.util.function.BiConsumer<String, byte[]>> add,
                        Supplier<JsonNode> state, LongSupplier watermark, Supplier<List<String>> waitingFor,
                        LongSupplier skippedBlocks) {
    }

    /** {@code align.vector} on the series of the source: measuring points as JSON ({@code opentsx.vector.v1}). */
    private record VectorSink(VectorJob job, String hash) implements Sink {
        @Override
        public String dag() {
            return job.dag();
        }

        @Override
        public String name() {
            return job.name();
        }

        @Override
        public String topic() {
            return job.topic();
        }

        @Override
        public String kind() {
            return "vector";
        }

        @Override
        public String sourceTopic() {
            return null;    // the episodes topic of the domain
        }

        @Override
        public boolean fromStart() {
            return job.fromStart();
        }

        @Override
        public Work open(JsonNode saved) {
            long lateness = job.lateness().toNanos() / 1000;
            VectorStream stream = saved == null
                    ? new VectorStream(job.inputNames(), job.params(), new LinkedHashSet<>(job.seriesIds()), lateness)
                    : VectorStream.restore(job.inputNames(), job.params(), new LinkedHashSet<>(job.seriesIds()), lateness, saved);
            return new Work((e, out) -> {
                Map<String, String> tags = new TreeMap<>();
                e.getSeries().getTags().forEach((k, v) -> tags.put(k.toString(), v.toString()));
                for (VectorAligner.Point p : stream.add(e.getSeries().getSeriesId(), e.getSeries().getMetric(), tags,
                        Episodes.timestampsMicros(e), Episodes.values(e), Micros.of(e.getTEnd()))) {
                    out.accept(VectorJson.keyText(p.key()), VectorJson.bytes(VectorJson.record(job.name(), p, job.dag(), hash)));
                }
            }, stream::state, stream::watermark, stream::waitingFor, stream::skippedBlocks);
        }
    }

    /** A chain of cleaning operators on the series of the source: v2 episodes of the clean grade of a signal. */
    private record SeriesSink(SeriesJob job, OpenTsxAvro avro) implements Sink {
        @Override
        public String dag() {
            return job.dag();
        }

        @Override
        public String name() {
            return job.name();
        }

        @Override
        public String topic() {
            return job.topic();
        }

        @Override
        public String kind() {
            return "series";
        }

        @Override
        public String sourceTopic() {
            return job.sourceTopic();
        }

        @Override
        public boolean fromStart() {
            return job.fromStart();
        }

        @Override
        public Work open(JsonNode saved) {
            SeriesStream stream = saved == null
                    ? new SeriesStream(job.chain(), job.target(), job.seriesIds())
                    : SeriesStream.restore(job.chain(), job.target(), job.seriesIds(), saved);
            return new Work((e, out) -> {
                for (Episode clean : stream.add(e)) {
                    out.accept(clean.getSeries().getSeriesId().toString(), avro.encode(clean));
                }
            }, stream::state, stream::watermark, stream::waitingFor, stream::skippedBlocks);
        }
    }

    private final class Runner {
        final Sink job;
        final String hash;
        final Path file;
        final Map<TopicPartition, Long> positions = new HashMap<>();
        final List<Future<RecordMetadata>> pending = new ArrayList<>();
        final Patience patience = Patience.standard();
        Consumer<byte[], byte[]> consumer;
        Producer<String, byte[]> producer;
        /** A checkpoint that waits for the broker to confirm what was written before it; null when none is under way. */
        Snapshot waiting;
        Work stream;
        long records;
        long undecodable;
        /** The partitions whose first undecodable record was logged; later ones are only counted. */
        final Set<TopicPartition> undecodableReported = new HashSet<>();
        /** Records that retention deleted between the checkpoint and the start of this runner; kept in the checkpoint. */
        long lostToRetention;
        /** The cause chain of the last failure whose stack trace was logged. */
        String traced;
        long lastCheckpoint = nanoTime.getAsLong();
        long lastStatus;
        long retryAt;
        boolean dirty;
        String state = "starting";
        String reported = "";
        long reportedRecords = -1;
        String error;

        Runner(Sink job, String hash) {
            this.job = job;
            this.hash = hash;
            this.file = stateDir.resolve((job.dag() + "--" + job.name()).replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        }

        void step() {
            if (state.equals("failed")) {
                if (nanoTime.getAsLong() - retryAt < 0) {
                    return;
                }
                state = "starting";         // try again from the last checkpoint
            }
            try {
                if (consumer == null) {
                    open();
                }
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, timing.poll())) {
                    handle(r);
                }
                state = stream.waitingFor().get().isEmpty() ? "running" : "waiting";
                error = null;
                long now = nanoTime.getAsLong();
                boolean saved = false;
                if (waiting == null && dirty && (now - lastCheckpoint >= timing.checkpointEvery().toNanos() || pending.size() >= 5000)) {
                    snapshot();
                }
                if (waiting != null && confirmed()) {
                    saved = !waiting.confirmations.isEmpty();
                    save();
                }
                // report a change of state at once, confirmed progress with the checkpoint, and otherwise now and then
                if (!state.equals(reported) || (saved && records != reportedRecords) || now - lastStatus >= timing.statusEvery().toNanos()) {
                    report();
                }
            } catch (RuntimeException e) {
                fail(e);
            }
        }

        private void open() {
            sinkProvisioner.accept(job.topic());
            positions.clear();
            pending.clear();
            waiting = null;
            producer = producers.get();
            records = 0;
            JsonNode saved = load();
            stream = job.open(saved == null ? null : saved.path("stream"));
            consumer = consumers.get();
            List<TopicPartition> tps = new ArrayList<>();
            String source = job.sourceTopic() == null ? layout.episodes() : job.sourceTopic();
            List<PartitionInfo> partitions = consumer.partitionsFor(source, Duration.ofSeconds(30));
            if (partitions == null || partitions.isEmpty()) {
                throw new IllegalStateException("the source topic " + source + " does not exist yet; nothing has written it");
            }
            for (PartitionInfo p : partitions) {
                tps.add(new TopicPartition(p.topic(), p.partition()));
            }
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, Duration.ofSeconds(30));
            Map<TopicPartition, Long> end = job.fromStart() ? Map.of() : consumer.endOffsets(tps, Duration.ofSeconds(30));
            consumer.assign(tps);
            records = saved == null ? 0 : saved.path("records").asLong();
            lostToRetention = saved == null ? 0 : saved.path("lostToRetention").asLong();
            for (TopicPartition tp : tps) {
                long from;
                if (saved != null) {
                    long position = saved.path("positions").path(tp.topic() + "|" + tp.partition()).asLong(0);
                    from = Math.max(begin.get(tp), position);
                    if (from > position) {
                        lostToRetention += from - position;
                        LOG.warn("retention deleted records before the sink read them; reading on from the log start dag={}"
                                        + " sink={} topic={} partition={} saved={} logStart={} lost={}", job.dag(), job.name(),
                                tp.topic(), tp.partition(), position, from, from - position);
                    }
                } else {
                    from = job.fromStart() ? begin.get(tp) : end.get(tp);
                }
                positions.put(tp, from);
                consumer.seek(tp, from);
            }
            dirty = saved == null;      // a fresh runner writes its first checkpoint soon, so "latest" stays where it began
            lastCheckpoint = nanoTime.getAsLong();
        }

        private void handle(ConsumerRecord<byte[], byte[]> r) {
            TopicPartition tp = new TopicPartition(r.topic(), r.partition());
            positions.put(tp, r.offset() + 1);
            dirty = true;
            Episode e;
            try {
                e = avro.decode(r.value(), Episode.class);
            } catch (RuntimeException ex) {
                undecodable++;
                if (undecodableReported.add(tp)) {
                    LOG.warn("undecodable record skipped and counted in undecodable; later ones of this partition are only counted"
                                    + " dag={} sink={} topic={} partition={} offset={} cause={}", job.dag(), job.name(), r.topic(),
                            r.partition(), r.offset(), causes(ex));
                }
                return;
            }
            // episodes cut out of other episodes repeat their samples
            if (e.getProvenance().getParentEpisodeId() != null
                    || (e.getSegmentation() != null && e.getSegmentation().getSourceEpisodeId() != null)) {
                return;
            }
            stream.add().accept(e, (key, value) -> {
                pending.add(producer.send(new ProducerRecord<>(job.topic(), key, value)));
                records++;
            });
        }

        /**
         * Takes down positions and state as they are now. The checkpoint is written once the broker has confirmed
         * every measuring point sent up to here ({@link #confirmed()}); meanwhile the runner keeps reading, and the
         * other runners are not held up.
         */
        void snapshot() {
            ObjectNode n = JSON.createObjectNode();
            n.put("dag", job.dag()).put("vector", job.name()).put("specHash", hash).put("records", records)
                    .put("lostToRetention", lostToRetention);
            ObjectNode pos = n.putObject("positions");
            new TreeMap<>(positionsByName()).forEach(pos::put);
            n.set("stream", stream.state().get());
            waiting = new Snapshot(n, new ArrayList<>(pending), nanoTime.getAsLong());
            pending.clear();
            dirty = false;
        }

        /**
         * True when the broker has confirmed everything the waiting checkpoint depends on.
         *
         * @throws IllegalStateException when a send failed, or no confirmation came within the time a send may take
         */
        boolean confirmed() {
            for (Future<RecordMetadata> f : waiting.confirmations) {
                if (!f.isDone()) {
                    if (nanoTime.getAsLong() - waiting.since > timing.confirmWithin().toNanos()) {
                        throw new IllegalStateException("the broker has not confirmed the measuring points for "
                                + timing.confirmWithin().toSeconds() + " s; continuing from the last checkpoint with a new producer");
                    }
                    return false;
                }
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the broker to confirm", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new IllegalStateException("the broker did not confirm every measuring point; continuing from the last"
                            + " checkpoint (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")", cause);
                }
            }
            return true;
        }

        /** Writes the waiting checkpoint. */
        void save() {
            try {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, bytes(waiting.document));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            waiting = null;
            lastCheckpoint = nanoTime.getAsLong();
        }

        private Map<String, Long> positionsByName() {
            Map<String, Long> out = new HashMap<>();
            positions.forEach((tp, offset) -> out.put(tp.topic() + "|" + tp.partition(), offset));
            return out;
        }

        /**
         * The checkpoint of this DAG document, or null when there is none (or one of another document).
         *
         * @throws IllegalStateException when the file cannot be read; the runner fails and the file stays as it is
         */
        private JsonNode load() {
            if (!Files.exists(file)) {
                return null;
            }
            JsonNode n;
            try {
                n = JSON.readTree(file.toFile());
            } catch (IOException e) {
                throw new IllegalStateException(unreadable(e instanceof com.fasterxml.jackson.core.JsonProcessingException j
                        ? j.getOriginalMessage() : e.toString()), e);
            }
            if (!n.isObject()) {
                throw new IllegalStateException(unreadable("it holds no JSON object"));
            }
            return hash.equals(n.path("specHash").asText()) ? n : null;
        }

        private String unreadable(String why) {
            return "checkpoint " + file + ": cannot be read (" + why + "); repair it, or delete it to start the sink over";
        }

        private void fail(RuntimeException e) {
            state = "failed";
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            retryAt = nanoTime.getAsLong() + timing.retryAfter().toNanos();
            String cause = causes(e);
            LOG.error("sink failed; the next try is in {} dag={} sink={} cause={}", timing.retryAfter(), job.dag(), job.name(), cause);
            if (!cause.equals(traced)) {
                traced = cause;
                LOG.debug("stack trace of the sink failure dag={} sink={}", job.dag(), job.name(), e);
            }
            release();
            try {
                report();
            } catch (RuntimeException ignored) {
                // the broker may be the reason; the status follows with the next round
            }
        }

        private void release() {
            if (producer != null) {
                closeNow(producer);     // what it has not sent belongs to work that is repeated from the checkpoint
                producer = null;
            }
            pending.clear();
            waiting = null;
            if (consumer != null) {
                try {
                    consumer.close(Duration.ofSeconds(5));
                } catch (RuntimeException ignored) {
                    // closing a consumer of a broker that does not answer may fail; it is gone either way
                }
                consumer = null;
            }
        }

        /** Ends the runner in order: a last checkpoint when the broker allows it. */
        void shutdown() {
            if (consumer != null && (dirty || waiting != null)) {
                try {
                    if (waiting == null) {
                        snapshot();
                    } else if (dirty) {
                        // a checkpoint is under way and more was read since: finish that one first, then take the last
                        awaitConfirmation();
                        save();
                        snapshot();
                    }
                    awaitConfirmation();
                    save();
                    LOG.info("final checkpoint written dag={} sink={} records={} positions={}", job.dag(), job.name(), records,
                            String.join(",", positions.entrySet().stream().map(p -> p.getKey() + ":" + p.getValue()).sorted().toList()));
                } catch (RuntimeException e) {
                    LOG.warn("no final checkpoint; a restart repeats what was read since the last one dag={} sink={} cause={}",
                            job.dag(), job.name(), causes(e));
                }
            }
            release();
        }

        /** Waits a short while for the broker, as an orderly end allows. */
        private void awaitConfirmation() {
            producer.flush();
            // this wait sleeps for real, so it ends by the real clock also when the processor runs on another one
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!confirmed()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("the broker did not confirm in time");
                }
                sleep(Duration.ofMillis(50));
            }
        }

        /** Removes the checkpoint: the DAG was stopped or replaced. */
        void forget() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warn("checkpoint not removed; delete it by hand file={} cause={}", file, causes(e));
            }
        }

        void report() {
            ObjectNode n = JSON.createObjectNode();
            n.put("schema", STATUS_SCHEMA).put("dag", job.dag()).put("vector", job.name()).put("kind", job.kind()).put("topic", job.topic())
                    .put("state", state).put("specHash", hash).put("records", records);
            if (stream != null) {
                n.put("skippedBlocks", stream.skippedBlocks().getAsLong());
                long w = stream.watermark().getAsLong();
                if (w != Long.MIN_VALUE) {
                    n.put("watermark", Micros.toInstant(w).toString());
                }
                List<String> waitingFor = stream.waitingFor().get();
                ArrayNode waiting = n.putArray("waitingFor");
                waitingFor.stream().limit(10).forEach(waiting::add);
                n.put("waitingForCount", waitingFor.size());
            }
            if (undecodable > 0) {
                n.put("undecodable", undecodable);
            }
            if (lostToRetention > 0) {
                n.put("lostToRetention", lostToRetention);
            }
            if (ignoredControlRecords > 0) {
                n.put("ignoredControlRecords", ignoredControlRecords);
            }
            if (error != null) {
                n.put("error", error);
            }
            n.put("at", Micros.toInstant(Micros.now()).toString());
            status(job.dag() + "/" + job.name(), bytes(n));
            reported = state;
            reportedRecords = records;
            lastStatus = nanoTime.getAsLong();
        }
    }

    /** Positions and state of a runner at one moment, and the sends that must be confirmed before it may be written. */
    private record Snapshot(ObjectNode document, List<Future<RecordMetadata>> confirmations, long since) {
    }

    /** The exception and its causes on one line, for a log line. */
    static String causes(Throwable t) {
        StringBuilder s = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            s.append(s.length() == 0 ? "" : "; caused by ").append(c);
        }
        return s.toString().replaceAll("\\p{Cntrl}", " ");
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(JsonNode n) {
        try {
            return JSON.writeValueAsBytes(n);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(Math.max(1, d.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
