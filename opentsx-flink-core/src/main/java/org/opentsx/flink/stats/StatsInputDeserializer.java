package org.opentsx.flink.stats;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Turns a record of the episodes or observations topic into a {@link StatsInput}. The series id is the record key,
 * as {@code BucketWriter} writes it; a record without key is decoded to find it. Records that cannot be decoded are
 * counted in the metric {@code undecodable} and dropped; the first one per subtask and partition is logged at WARN.
 */
public final class StatsInputDeserializer implements KafkaRecordDeserializationSchema<StatsInput> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(StatsInputDeserializer.class);

    private final String episodesTopic;

    private transient OpenTsxAvro avro;
    private transient Counter undecodable;
    private transient Set<TopicPartition> undecodableReported;

    public StatsInputDeserializer(String episodesTopic) {
        this.episodesTopic = episodesTopic;
    }

    @Override
    public void open(DeserializationSchema.InitializationContext context) {
        avro = new OpenTsxAvro();
        undecodable = context.getMetricGroup().counter("undecodable");
        undecodableReported = new HashSet<>();
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<StatsInput> out) {
        if (record.value() == null) {
            drop(record, "no value");
            return;
        }
        boolean episode = record.topic().equals(episodesTopic);
        String seriesId = record.key() == null ? "" : new String(record.key(), StandardCharsets.UTF_8);
        if (seriesId.isEmpty()) {
            try {
                seriesId = episode ? avro.decode(record.value(), Episode.class).getSeries().getSeriesId()
                        : avro.decode(record.value(), Observation.class).getSeriesId();
            } catch (RuntimeException e) {
                drop(record, e.getMessage());
                return;
            }
        }
        out.collect(new StatsInput(seriesId, episode, record.value(), record.topic(), record.partition(), record.offset()));
    }

    private void drop(ConsumerRecord<byte[], byte[]> record, String reason) {
        undecodable.inc();
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        if (undecodableReported.add(tp)) {
            LOG.warn("undecodable record skipped and counted in undecodable; later ones of this partition are only counted"
                    + " by this subtask topic={} partition={} offset={} cause={}", record.topic(), record.partition(),
                    record.offset(), reason);
        }
    }

    @Override
    public TypeInformation<StatsInput> getProducedType() {
        return TypeInformation.of(StatsInput.class);
    }
}
