package org.opentsx.stats;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.Patience;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Runs a {@link StatsEngine} over the KafScale episode (and optionally observation) topic and writes
 * {@link SeriesStats} to {@code tsx.<domain>.series-stats.v2}.
 *
 * <p>Built for what KafScale supports: manual partition assignment, no consumer group, no offset commits. Input
 * positions and the complete engine state are kept in a local checkpoint file, written atomically after all
 * outputs of the covered input were acknowledged. After a crash the processor resumes from the last checkpoint and may
 * emit some statistics again (at-least-once); a re-emitted record is identical or newer, and readers keep the latest
 * record per (seriesId, windowType, windowStart) - see {@link SeriesStatsLog}.
 */
public final class StatsProcessor {

    /** Outcome of a bounded run. */
    public record RunResult(long records, long undecodable, long statsWritten, StatsEngine.Metrics metrics) {
    }

    private static final String MAGIC = "TSXSTATS1";

    private final Consumer<byte[], byte[]> consumer;
    private final Producer<String, byte[]> producer;
    private final TopicLayout layout;
    private final StatsEngine engine;
    private final Path checkpoint;
    private final boolean observations;
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final Map<TopicPartition, Long> positions = new HashMap<>();
    private final List<Future<?>> pending = new ArrayList<>();
    private long undecodable;
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
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, Duration.ofMillis(500))) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    if (r.offset() >= end.get(tp)) {
                        continue;
                    }
                    handle(r);
                    records++;
                }
            }
            send(flushAtEnd ? engine.flush() : engine.emitGlobal());
            checkpoint();
        } finally {
            consumer.assign(List.of());
        }
        return new RunResult(records, undecodable, statsWritten, engine.metrics());
    }

    /** Continuous mode: emits GLOBAL and checkpoints every {@code checkpointEvery} until {@code stop} is true. */
    public void run(Duration checkpointEvery, BooleanSupplier stop) {
        assign(Duration.ofSeconds(30));
        long next = System.nanoTime() + checkpointEvery.toNanos();
        try {
            while (!stop.getAsBoolean()) {
                for (ConsumerRecord<byte[], byte[]> r : patience.poll(consumer, Duration.ofMillis(500))) {
                    handle(r);
                }
                if (System.nanoTime() >= next) {
                    send(engine.emitGlobal());
                    checkpoint();
                    next = System.nanoTime() + checkpointEvery.toNanos();
                }
            }
            send(engine.emitGlobal());
            checkpoint();
        } finally {
            consumer.assign(List.of());
        }
    }

    private List<TopicPartition> assign(Duration timeout) {
        List<String> topics = observations ? List.of(layout.episodes(), layout.observations()) : List.of(layout.episodes());
        List<TopicPartition> tps = new ArrayList<>();
        for (String topic : topics) {
            for (PartitionInfo p : consumer.partitionsFor(topic, timeout)) {
                tps.add(new TopicPartition(p.topic(), p.partition()));
            }
        }
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
        consumer.assign(tps);
        for (TopicPartition tp : tps) {
            long from = Math.max(begin.get(tp), positions.getOrDefault(tp, 0L));
            positions.put(tp, from);
            consumer.seek(tp, from);
        }
        return tps;
    }

    private void handle(ConsumerRecord<byte[], byte[]> r) {
        List<SeriesStats> closed = List.of();
        try {
            if (r.topic().equals(layout.episodes())) {
                closed = engine.onEpisode(avro.decode(r.value(), Episode.class));
            } else {
                closed = engine.onObservation(avro.decode(r.value(), Observation.class));
            }
        } catch (RuntimeException e) {
            undecodable++;
        }
        positions.put(new TopicPartition(r.topic(), r.partition()), r.offset() + 1);
        send(closed);
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
                Map<TopicPartition, Long> sorted = new TreeMap<>((a, b) -> a.topic().equals(b.topic())
                        ? Integer.compare(a.partition(), b.partition()) : a.topic().compareTo(b.topic()));
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
            throw new UncheckedIOException(e);
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
            throw new UncheckedIOException(e);
        }
    }
}
