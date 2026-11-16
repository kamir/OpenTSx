package org.opentsx.wayang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.model.Micros;

import java.io.UncheckedIOException;
import java.util.concurrent.ExecutionException;

/**
 * Writes {@code opentsx.dag-status.v1} records for a Track A job to {@code tsx.<domain>.dag-status.v1}, with the fields
 * of the processor in {@code opentsx-retrieval} plus {@code job}, {@code planVersion} and {@code track} (S5.4 of the
 * specification). Key: the job id; the latest record of a key counts.
 */
public final class TsxStatus implements AutoCloseable {

    static final String SCHEMA = "opentsx.dag-status.v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String topic;
    private final KafkaProducer<String, byte[]> producer;

    public TsxStatus(String bootstrap, String statusTopic) {
        this.topic = statusTopic;
        this.producer = KafScaleClients.producer(bootstrap);
    }

    static ObjectNode record(TsxIncrement.Config c, String state, long records, String error) {
        ObjectNode n = JSON.createObjectNode().put("schema", SCHEMA).put("dag", c.job()).put("job", c.job())
                .put("planVersion", c.planVersion()).put("track", "A").put("topic", c.inputTopic()).put("state", state)
                .put("specHash", c.specHash()).put("records", records);
        if (error != null) {
            n.put("error", error);
        }
        n.put("at", Micros.toInstant(Micros.now()).toString());
        return n;
    }

    /** Sends the record and waits for the broker; a status that is not confirmed is an error of the job. */
    public void report(TsxIncrement.Config c, String state, long records, String error) {
        try {
            producer.send(new ProducerRecord<>(topic, c.job(), JSON.writeValueAsBytes(record(c, state, records, error)))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reporting the status of " + c.job(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("status of " + c.job() + " was not acknowledged", e.getCause());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
