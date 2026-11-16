package org.opentsx.wayang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.model.Micros;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes {@code opentsx.dag-status.v1} records for a Track A job to {@code tsx.<domain>.dag-status.v1}, with the fields
 * of the processor in {@code opentsx-retrieval} plus {@code job}, {@code planVersion} and {@code track} (S5.4 of the
 * specification). Key: the job id; the latest record of a key counts.
 */
public final class TsxStatus implements AutoCloseable {

    static final String SCHEMA = "opentsx.dag-status.v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String topic;
    private final Producer<String, byte[]> producer;
    private final Clock clock;
    private final ScheduledExecutorService timer;

    public TsxStatus(String bootstrap, String statusTopic) {
        this(statusTopic, KafScaleClients.producer(bootstrap), Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "opentsx-wayang-status");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    TsxStatus(String topic, Producer<String, byte[]> producer, Clock clock, ScheduledExecutorService timer) {
        this.topic = topic;
        this.producer = producer;
        this.clock = clock;
        this.timer = timer;
    }

    static ObjectNode record(TsxIncrement.Config c, String state, long records, String error) {
        return record(c, state, records, error, new TsxState.Progress(Long.MIN_VALUE, List.of(), 0), Instant.now());
    }

    static ObjectNode record(TsxIncrement.Config c, String state, long records, String error,
                             TsxState.Progress progress, Instant at) {
        ObjectNode n = JSON.createObjectNode().put("schema", SCHEMA).put("dag", c.job()).put("job", c.job())
                .put("planVersion", c.planVersion()).put("track", "A").put("topic", c.inputTopic()).put("state", state)
                .put("specHash", c.specHash()).put("records", records).put("skippedBlocks", progress.skippedBlocks())
                .put("waitingForCount", progress.waitingFor().size());
        if (progress.watermark() == Long.MIN_VALUE) n.putNull("watermark");
        else n.put("watermark", Micros.toInstant(progress.watermark()).toString());
        var waiting = n.putArray("waitingFor");
        progress.waitingFor().stream().limit(10).forEach(waiting::add);
        if (error != null) {
            n.put("error", error);
        }
        n.put("at", at.toString());
        return n;
    }

    /** Sends the record and waits for the broker; a status that is not confirmed is an error of the job. */
    public void report(TsxIncrement.Config c, String state, long records, String error) {
        report(c, state, records, error, new TsxState.Progress(Long.MIN_VALUE, List.of(), 0));
    }

    synchronized void report(TsxIncrement.Config c, String state, long records, String error, TsxState.Progress progress) {
        try {
            producer.send(new ProducerRecord<>(topic, c.job(),
                    JSON.writeValueAsBytes(record(c, state, records, error, progress, clock.instant())))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reporting the status of " + c.job(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("status of " + c.job() + " was not acknowledged", e.getCause());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    ScheduledFuture<?> heartbeat(Runnable report) {
        return timer.scheduleAtFixedRate(report, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        timer.shutdownNow();
        producer.close();
    }
}
