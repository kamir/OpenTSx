package org.opentsx.model.kafka;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.opentsx.model.OpenTsxAvro;

/** Kafka value deserializer for v2 records (single-object encoding; detects the framing per record). */
public class OpenTsxDeserializer implements Deserializer<SpecificRecord> {

    private final OpenTsxAvro avro = new OpenTsxAvro();

    @Override
    public SpecificRecord deserialize(String topic, byte[] data) {
        return data == null ? null : avro.decode(data);
    }
}
