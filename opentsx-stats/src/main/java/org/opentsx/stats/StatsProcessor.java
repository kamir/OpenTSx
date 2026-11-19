package org.opentsx.stats;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.kafka.Patience;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Runs a {@link StatsEngine} over the KafScale episode (and optionally observation) topic and writes
 * {@link SeriesStats} to {@code tsx.<domain>.series-stats.v2}.
 *
 * <p>Built for what KafScale supports: manual partition assignment, no consumer group, no offset commits. Input
 * positions and the complete engine state are kept in a local checkpoint file, written atomically after all
 * outputs of the covered input were acknowledged. After a crash the processor resumes from the last checkpoint and may
 * emit some statistics again (at-least-once); a re-emitted record is identical or newer, and readers keep the latest
 * record per (seriesId, windowType, windowStart), see {@link SeriesStatsLog}.
 *
 * <p>A record that does not decode is counted in {@code undecodable} and skipped. An episode that decodes but fails
 * {@link Episodes#validate} is counted in {@code invalid} and skipped. The first record of each kind per partition is
 * logged at WARN. A failure of the engine stops the processor, and the checkpoint stays before that record. Records
 * that retention deleted before they were read, after the checkpoint position or during a run, are counted in
 * {@code lostToRetention} and logged at WARN.
 *
 * <p>At INFO it logs the restored checkpoint, the assigned partitions with their start offsets, in continuous mode one
 * progress line with the counters per checkpoint, and the final checkpoint.
 */
public final class StatsProcessor {

    /** Outcome of a bounded run. */
    public record RunResult(long records, long undecodable, long invalid, long lostToRetention, long statsWritten,
                            StatsEngine.Metrics metrics) {
    }

    private static final String MAGIC = "TSXSTATS1";
    private static final Logger LOG = LoggerFactory.getLogger(StatsProcessor.class);
    private static final Comparator<TopicPartition> BY_TOPIC_AND_PARTITION =
            Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition);

    private final Consumer<byte[], byte[]> consumer;
    private final Producer<String, byte[]> producer;
    private final TopicLayout layout;
    private final StatsEngine engine;
    private final Path checkpoint;
    private final boolean observations;
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final Map<TopicPartition, Long> positions = new HashMap<>();
    private final List<Future<?>> pending = new ArrayList<>();
    private final Set<TopicPartition> undecodableReported = new HashSet<>();
    private final Set<TopicPartition> invalidReported = new HashSet<>();
    private long undecodable;
    private long invalid;
    private long lostToRetention;
    private long statsWritten;
    /** KafScale refuses reads for about a minute at a time; the processor waits instead of ending. */
    private Patience patience = Patience.standard();

    /**
     * @param checkpoint   checkpoint file; restored if it exists, null for none (always start from the beginning)
     * @param observations also consume the observations topic
     */
    public StatsProcessor(Consumer<byte[], byte[]> consumer, Producer<String, byte[]> producer, TopicLayout layout,
                          StatsEngine engine, Path checkpoint, boolean observations) {
        this.consumer = consumer;
        this.producer = producer;
        this.layout = layout;
        this.engine = engine;
        this.checkpoint = checkpoint;
        this.observations = observations;
        if (checkpoint != null && Files.exists(checkpoint)) {
            restore();
            LOG.info("checkpoint restored path={} domain={} positions={}", checkpoint, layout.domain(), format(positions));
        } else {
            LOG.info("no checkpoint; reading the topics from the log start path={}", checkpoint == null ? "none" : checkpoint);
        }
    }

    /** How long refused reads are waited out; the default is {@link Patience#standard()}. */
    public void patience(Patience patience) {
        this.patience = patience;
    }

    public StatsEngine engine() {
        return engine;
    }

    public Map<TopicPartition, Long> positions() {
        return Map.copyOf(positions);
    }

    /**
     * Processes everything up to the end offsets seen at start, then (optionally) closes all open windows, emits
     * GLOBAL and writes a checkpoint.
     */
    public RunResult runUntilCaughtUp(Duration timeout, boolean flushAtEnd) {
        List<TopicPartition> tps = assign(timeout);
        Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
        long records = 0;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (tps.stream().anyMatch(tp -> positions.get(tp) < end.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timed out computing statistics");
                }
                long before = records;
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    if (r.offset() >= end.get(tp)) {
                        long position = positions.get(tp);
                        if (position < end.get(tp)) {
                            lost(tp, position, r.offset());
                            positions.put(tp, r.offset());
                        }
                        continue;
                    }
                    handle(r);
                    records++;
                }
                if (records == before) {
                    skipDeleted(tps, end, Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                }
            }
            send(flushAtEnd ? engine.flush() : engine.emitGlobal());
            checkpoint();
        } finally {
            consumer.assign(List.of());
        }
        return new RunResult(records, undecodable, invalid, lostToRetention, statsWritten, engine.metrics());
    }

    /**
     * Continuous mode: emits GLOBAL and checkpoints every {@code checkpointEvery} until {@code stop} is true.
     *
     * @throws OpenTsxConfException when none of the input topics has a partition
     */
    public void run(Duration checkpointEvery, BooleanSupplier stop) {
        if (assign(Duration.ofSeconds(30)).isEmpty()) {
            throw new OpenTsxConfException(String.join(",", inputTopics()), "the input topics do not exist or have no"
                    + " partitions", "check opentsx.domain (--domain, OPENTSX_DOMAIN), or create the topics first");
        }
        long records = 0;
        long next = System.nanoTime() + checkpointEvery.toNanos();
        try {
            while (!stop.getAsBoolean()) {
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, Duration.ofMillis(500))) {
                    handle(r);
                    records++;
                }
                if (System.nanoTime() >= next) {
                    send(engine.emitGlobal());
                    checkpoint();
                    StatsEngine.Metrics m = engine.metrics();
                    LOG.info("progress records={} undecodable={} invalid={} lostToRetention={} statsWritten={} duplicates={}"
                                    + " lateValues={}", records, undecodable, invalid, lostToRetention, statsWritten,
                            m.duplicates(), m.lateValues());
                    next = System.nanoTime() + checkpointEvery.toNanos();
                }
            }
            send(engine.emitGlobal());
            checkpoint();
            if (checkpoint == null) {
                LOG.info("stopped without a checkpoint file positions={}", format(positions));
            } else {
                LOG.info("final checkpoint written path={} positions={}", checkpoint, format(positions));
            }
        } finally {
            consumer.assign(List.of());
        }
    }

    private List<String> inputTopics() {
        return observations ? List.of(layout.episodes(), layout.observations()) : List.of(layout.episodes());
    }

    private List<TopicPartition> assign(Duration timeout) {
        List<String> topics = inputTopics();
        List<TopicPartition> tps = new ArrayList<>();
        for (String topic : topics) {
            for (PartitionInfo p : consumer.partitionsFor(topic, timeout)) {
                tps.add(new TopicPartition(p.topic(), p.partition()));
            }
        }
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        consumer.assign(tps);
        Map<TopicPartition, Long> start = new HashMap<>();
        for (TopicPartition tp : tps) {
            long from = begin.get(tp);
            Long saved = positions.get(tp);
            if (saved != null && saved < from) {
                lost(tp, saved, from);
            } else if (saved != null) {
                from = saved;
            }
            positions.put(tp, from);
            consumer.seek(tp, from);
            start.put(tp, from);
        }
        if (tps.isEmpty()) {
            LOG.warn("no partitions assigned; the input topics do not exist or have no partitions topics={}",
                    String.join(",", topics));
        } else {
            LOG.info("partitions assigned count={} start={}", tps.size(), format(start));
        }
        return tps;
    }

    /** {@code <topic>-<partition>:<offset>} per partition, sorted by topic and partition, comma-separated. */
    private static String format(Map<TopicPartition, Long> offsets) {
        if (offsets.isEmpty()) {
            return "none";
        }
        return offsets.entrySet().stream().sorted(Map.Entry.comparingByKey(BY_TOPIC_AND_PARTITION))
                .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(","));
    }

    /** Retention can delete past a position with no record left to poll; only the log start shows that. */
    private void skipDeleted(List<TopicPartition> tps, Map<TopicPartition, Long> end, Duration timeout) {
        List<TopicPartition> behind = tps.stream().filter(tp -> positions.get(tp) < end.get(tp)).toList();
        Map<TopicPartition, Long> logStart = consumer.beginningOffsets(behind, timeout);
        for (TopicPartition tp : behind) {
            long position = positions.get(tp);
            long start = logStart.get(tp);
            if (start > position) {
                lost(tp, position, start);
                positions.put(tp, start);
            }
        }
    }

    private void lost(TopicPartition tp, long position, long logStart) {
        lostToRetention += logStart - position;
        LOG.warn("retention deleted records before they were read; reading on from the log start topic={} partition={}"
                + " position={} logStart={} lost={}", tp.topic(), tp.partition(), position, logStart, logStart - position);
    }

    private void handle(ConsumerRecord<byte[], byte[]> r) {
        TopicPartition tp = new TopicPartition(r.topic(), r.partition());
        long position = positions.get(tp);
        if (r.offset() > position) {
            lost(tp, position, r.offset());
        }
        boolean isEpisode = r.topic().equals(layout.episodes());
        Episode episode = null;
        Observation observation = null;
        try {
            if (isEpisode) {
                episode = avro.decode(r.value(), Episode.class);
            } else {
                observation = avro.decode(r.value(), Observation.class);
            }
        } catch (RuntimeException e) {
            undecodable++;
            if (undecodableReported.add(tp)) {
                LOG.warn("undecodable record skipped and counted in undecodable; later ones of this partition are only"
                                + " counted topic={} partition={} offset={} cause={}", tp.topic(), tp.partition(), r.offset(),
                        StatsProcessorApp.causes(e));
            }
            positions.put(tp, r.offset() + 1);
            return;
        }
        List<SeriesStats> closed;
        try {
            if (isEpisode && !consistent(tp, r.offset(), episode)) {
                positions.put(tp, r.offset() + 1);
                return;
            }
            closed = isEpisode ? engine.onEpisode(episode) : engine.onObservation(observation);
        } catch (RuntimeException e) {
            throw new IllegalStateException(tp + " offset " + r.offset()
                    + ": the statistics engine failed on this record; the checkpoint stays before it", e);
        }
        positions.put(tp, r.offset() + 1);
        send(closed);
    }

    private boolean consistent(TopicPartition tp, long offset, Episode episode) {
        try {
            Episodes.validate(episode);
            return true;
        } catch (IllegalArgumentException e) {
            invalid++;
            if (invalidReported.add(tp)) {
                LOG.warn("invalid episode skipped and counted in invalid; later ones of this partition are only counted"
                                + " topic={} partition={} offset={} cause={}", tp.topic(), tp.partition(), offset,
                        StatsProcessorApp.causes(e));
            }
            return false;
        }
    }

    private void send(List<SeriesStats> stats) {
        for (SeriesStats s : stats) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(layout.seriesStats(), null,
                    Math.floorDiv(Micros.of(s.getWindowEnd()), 1000L), s.getSeriesId(), avro.encode(s));
            record.headers().add(TopicLayout.HEADER_SCHEMA, "org.opentsx.model.v2.SeriesStats".getBytes(StandardCharsets.UTF_8));
            pending.add(producer.send(record));
            statsWritten++;
        }
    }

    /** Waits for all outputs, then writes positions + engine state atomically. */
    public void checkpoint() {
        producer.flush();
        for (Future<?> f : pending) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for statistics to be acknowledged", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("writing statistics failed; checkpoint not advanced", e.getCause());
            }
        }
        pending.clear();
        if (checkpoint == null) {
            return;
        }
        try {
            Path tmp = checkpoint.resolveSibling(checkpoint.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeUTF(MAGIC);
                out.writeUTF(layout.domain());
                Map<TopicPartition, Long> sorted = new TreeMap<>(BY_TOPIC_AND_PARTITION);
                sorted.putAll(positions);
                out.writeInt(sorted.size());
                for (Map.Entry<TopicPartition, Long> e : sorted.entrySet()) {
                    out.writeUTF(e.getKey().topic());
                    out.writeInt(e.getKey().partition());
                    out.writeLong(e.getValue());
                }
                engine.writeState(out);
            }
            Files.move(tmp, checkpoint, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write checkpoint " + checkpoint, e);
        }
    }

    private void restore() {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(checkpoint)))) {
            if (!MAGIC.equals(in.readUTF())) {
                throw new IOException("not a statistics checkpoint: " + checkpoint);
            }
            String domain = in.readUTF();
            if (!domain.equals(layout.domain())) {
                throw new IOException("checkpoint belongs to domain " + domain + ", not " + layout.domain());
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                positions.put(new TopicPartition(in.readUTF(), in.readInt()), in.readLong());
            }
            engine.readState(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read checkpoint " + checkpoint, e);
        }
    }
}
