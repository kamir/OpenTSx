package org.opentsx.flink.serdes;

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opentsx.data.model.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Decodes the raw Avro binary of a 2.x {@link Observation} (no Confluent framing) from a Kafka record, with the schema
 * of this build only. The event time is not set here; the job takes it from {@link Observation#getTimestamp()} in its
 * watermark strategy.
 *
 * <p>A record that does not decode, or has no value, is counted in the metric {@code undecodable} and skipped. An
 * observation with an empty or blank {@code uri} is counted in the metric {@code invalid} and skipped: the job keys
 * the series by it. Each subtask logs the first skipped record of each kind at WARN with topic, partition, offset and
 * cause, and after that at most one line per {@link #REPORT} with the count and the position of the latest one.
 */
public class ObservationSchema implements KafkaRecordDeserializationSchema<Observation> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ObservationSchema.class);
    static final Duration REPORT = Duration.ofMinutes(1);

    /** Nanoseconds of a monotonic clock; serializable, because Flink ships the schema to its tasks. */
    interface Ticker extends Serializable {
        long nanos();
    }

    private final Ticker ticker;

    private transient SpecificDatumReader<Observation> datumReader;
    private transient BinaryDecoder decoder;
    private transient Skips undecodable;
    private transient Skips invalid;

    public ObservationSchema() {
        this(System::nanoTime);
    }

    ObservationSchema(Ticker ticker) {
        this.ticker = ticker;
    }

    @Override
    public void open(DeserializationSchema.InitializationContext context) {
        datumReader = new SpecificDatumReader<>(Observation.getClassSchema());
        undecodable = new Skips("undecodable", context.getMetricGroup().counter("undecodable"));
        invalid = new Skips("invalid", context.getMetricGroup().counter("invalid"));
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<Observation> out) {
        Observation observation = null;
        if (record.value() == null) {
            undecodable.skip(record, "no value");
        } else {
            try {
                decoder = DecoderFactory.get().binaryDecoder(record.value(), decoder);
                observation = datumReader.read(null, decoder);
            } catch (IOException | RuntimeException e) {
                undecodable.skip(record, causes(e));
            }
        }
        if (observation != null && observation.getUri().toString().isBlank()) {
            invalid.skip(record, "uri: empty; expected the URI of the series");
            observation = null;
        }
        undecodable.countIfDue();
        invalid.countIfDue();
        if (observation != null) {
            out.collect(observation);
        }
    }

    @Override
    public TypeInformation<Observation> getProducedType() {
        return TypeInformation.of(Observation.class);
    }

    private static String causes(Throwable t) {
        StringBuilder s = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            s.append(s.length() == 0 ? "" : "; caused by ").append(c);
        }
        return s.toString();
    }

    /** The skipped records of one kind in this subtask: the metric, and what was logged. */
    private final class Skips {
        private final String kind;
        private final Counter counter;
        private boolean logged;
        private long unreported;
        private long reportedAt;
        private String lastTopic;
        private int lastPartition;
        private long lastOffset;

        Skips(String kind, Counter counter) {
            this.kind = kind;
            this.counter = counter;
        }

        void skip(ConsumerRecord<byte[], byte[]> record, String cause) {
            counter.inc();
            lastTopic = record.topic();
            lastPartition = record.partition();
            lastOffset = record.offset();
            if (logged) {
                unreported++;
                return;
            }
            logged = true;
            reportedAt = ticker.nanos();
            LOG.warn("{} observation skipped topic={} partition={} offset={} cause={}", kind, record.topic(),
                    record.partition(), record.offset(), cause);
        }

        void countIfDue() {
            if (unreported == 0) {
                return;
            }
            long now = ticker.nanos();
            if (now - reportedAt >= REPORT.toNanos()) {
                LOG.warn("{} observations skipped count={} total={} topic={} partition={} offset={}", kind, unreported,
                        counter.getCount(), lastTopic, lastPartition, lastOffset);
                unreported = 0;
                reportedAt = now;
            }
        }
    }
}
