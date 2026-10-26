package org.opentsx.flink.stats;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;

import java.nio.charset.StandardCharsets;

/**
 * Turns a record of the episodes or observations topic into a {@link StatsInput}. The series id is the record key,
 * as {@code BucketWriter} writes it; a record without key is decoded to find it. Records that cannot be decoded are
 * counted in the metric {@code undecodable} and dropped.
 */
public final class StatsInputDeserializer implements KafkaRecordDeserializationSchema<StatsInput> {

    private static final long serialVersionUID = 1L;

    private final String episodesTopic;

    private transient OpenTsxAvro avro;
    private transient Counter undecodable;

    public StatsInputDeserializer(String episodesTopic) {
        this.episodesTopic = episodesTopic;
    }

    @Override
    public void open(DeserializationSchema.InitializationContext context) {
        avro = new OpenTsxAvro();
        undecodable = context.getMetricGroup().counter("undecodable");
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<StatsInput> out) {
        if (record.value() == null) {
            undecodable.inc();
            return;
        }
        boolean episode = record.topic().equals(episodesTopic);
        String seriesId = record.key() == null ? "" : new String(record.key(), StandardCharsets.UTF_8);
        if (seriesId.isEmpty()) {
            try {
                seriesId = episode ? avro.decode(record.value(), Episode.class).getSeries().getSeriesId()
                        : avro.decode(record.value(), Observation.class).getSeriesId();
            } catch (RuntimeException e) {
                undecodable.inc();
                return;
            }
        }
        out.collect(new StatsInput(seriesId, episode, record.value()));
    }

    @Override
    public TypeInformation<StatsInput> getProducedType() {
        return TypeInformation.of(StatsInput.class);
    }
}
