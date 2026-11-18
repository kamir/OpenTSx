package org.opentsx.flink.serdes;

import org.apache.avro.specific.SpecificDatumReader;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opentsx.data.model.Observation;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decodes raw legacy Avro Observation records from Kafka.
 * Callers assign event timestamps and watermarks.
 */
public class ObservationSchema implements KafkaRecordDeserializationSchema<Observation> {

    private static final long serialVersionUID = 1L;

    private transient SpecificDatumReader<Observation> datumReader;
    private transient org.apache.avro.io.BinaryDecoder decoder;

    private void ensureInitialized() {
        if (datumReader == null) {
            datumReader = new SpecificDatumReader<>(Observation.getClassSchema());
        }
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<Observation> out) throws IOException {
        ensureInitialized();

        if (record.value() == null) {
            return;
        }

        Observation observation;
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(record.value());
            decoder = org.apache.avro.io.DecoderFactory.get().binaryDecoder(inputStream, decoder);
            observation = datumReader.read(null, decoder);
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Failed to deserialize observation from topic " + record.topic()
                    + " partition " + record.partition() + " offset " + record.offset(), failure);
        }
        out.collect(observation);
    }

    @Override
    public TypeInformation<Observation> getProducedType() {
        return TypeInformation.of(Observation.class);
    }
}
