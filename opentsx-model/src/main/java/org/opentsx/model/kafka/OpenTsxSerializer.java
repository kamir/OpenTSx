package org.opentsx.model.kafka;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serializer;
import org.opentsx.model.OpenTsxAvro;

/** Kafka value serializer writing v2 records as Avro single-object encoding. */
public class OpenTsxSerializer implements Serializer<SpecificRecord> {

    private final OpenTsxAvro avro = new OpenTsxAvro();

    @Override
    public byte[] serialize(String topic, SpecificRecord data) {
        return data == null ? null : avro.encode(data);
    }
}
