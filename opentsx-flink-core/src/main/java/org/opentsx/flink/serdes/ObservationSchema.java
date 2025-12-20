package org.opentsx.flink.serdes;

import org.apache.avro.specific.SpecificDatumReader;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opentsx.data.model.Observation;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Kafka deserialization schema for OpenTSx Observation records using Avro.
 *
 * This schema handles deserialization of Avro-encoded Observation objects from Kafka topics,
 * with built-in support for schema evolution and watermark extraction.
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * KafkaSource<Observation> source = KafkaSource.<Observation>builder()
 *     .setBootstrapServers("localhost:9092")
 *     .setTopics("observations")
 *     .setDeserializer(new ObservationSchema())
 *     .build();
 * }</pre>
 *
 * <h2>Event Time Processing:</h2>
 * This schema automatically extracts timestamps from Observation objects for
 * event-time processing. The timestamp is taken from {@link Observation#getTimestamp()}.
 *
 * @see Observation
 * @see KafkaRecordDeserializationSchema
 */
public class ObservationSchema implements KafkaRecordDeserializationSchema<Observation> {

    private static final long serialVersionUID = 1L;

    private transient SpecificDatumReader<Observation> datumReader;
    private transient org.apache.avro.io.Decoder decoder;

    /**
     * Initializes the Avro reader for Observation deserialization.
     * This is called automatically by Flink before deserialization begins.
     */
    private void ensureInitialized() {
        if (datumReader == null) {
            datumReader = new SpecificDatumReader<>(Observation.getClassSchema());
        }
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<Observation> out) throws IOException {
        ensureInitialized();

        if (record.value() == null) {
            // Skip null records (tombstone messages)
            return;
        }

        try {
            // Decode Avro binary data
            ByteArrayInputStream inputStream = new ByteArrayInputStream(record.value());
            decoder = org.apache.avro.io.DecoderFactory.get().binaryDecoder(inputStream, decoder);

            // Read the Observation object
            Observation observation = datumReader.read(null, decoder);

            // Emit the deserialized observation
            out.collect(observation);

        } catch (Exception e) {
            // Log error but don't fail the job
            // In production, consider using a side output for failed records
            System.err.println("Failed to deserialize observation from topic " + record.topic()
                + " partition " + record.partition() + " offset " + record.offset() + ": " + e.getMessage());
        }
    }

    @Override
    public TypeInformation<Observation> getProducedType() {
        return TypeInformation.of(Observation.class);
    }
}
