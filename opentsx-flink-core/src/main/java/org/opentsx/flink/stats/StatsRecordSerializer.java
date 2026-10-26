package org.opentsx.flink.stats;

import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.opentsx.kafka.TopicLayout;

import java.nio.charset.StandardCharsets;

/** Writes a {@link StatsRecord} as the KafScale processor does: key series id, timestamp window end, schema header. */
public final class StatsRecordSerializer implements KafkaRecordSerializationSchema<StatsRecord> {

    private static final long serialVersionUID = 1L;
    private static final byte[] SCHEMA = "org.opentsx.model.v2.SeriesStats".getBytes(StandardCharsets.UTF_8);

    private final String topic;

    public StatsRecordSerializer(String topic) {
        this.topic = topic;
    }

    @Override
    public ProducerRecord<byte[], byte[]> serialize(StatsRecord r, KafkaSinkContext context, Long timestamp) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(TopicLayout.HEADER_SCHEMA, SCHEMA);
        return new ProducerRecord<>(topic, null, r.timestampMs, r.seriesId.getBytes(StandardCharsets.UTF_8), r.payload,
                headers);
    }
}
