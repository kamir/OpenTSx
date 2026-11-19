package org.opentsx.stats;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeriesStatsLogTest {

    private final TopicLayout layout = new TopicLayout("unit");

    private static SeriesStats global(String seriesId, long validCount) {
        return SeriesStats.newBuilder()
                .setSeriesId(seriesId).setWindowType(StatsEngine.GLOBAL)
                .setWindowStart(Instant.EPOCH).setWindowEnd(Instant.parse("2026-10-01T00:00:00Z"))
                .setValidCount(validCount).setNanCount(0).setMin(1).setMax(2).setMean(1.5).setM2(0.5)
                .setGapCount(0).setLastSeen(Instant.parse("2026-10-01T00:00:00Z"))
                .build();
    }

    @Test
    void readsBothPartitionsAndKeepsTheRecordWithTheMostValues() {
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        String topic = layout.seriesStats();
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, node, new Node[]{node}, new Node[]{node}),
                new PartitionInfo(topic, 1, node, new Node[]{node}, new Node[]{node})));
        TopicPartition p0 = new TopicPartition(topic, 0);
        TopicPartition p1 = new TopicPartition(topic, 1);
        c.updateBeginningOffsets(Map.of(p0, 0L, p1, 3L));
        c.updateEndOffsets(Map.of(p0, 2L, p1, 4L));
        OpenTsxAvro avro = new OpenTsxAvro();
        c.schedulePollTask(() -> {
            c.addRecord(new ConsumerRecord<>(topic, 0, 0, null, avro.encode(global("a", 10))));
            c.addRecord(new ConsumerRecord<>(topic, 0, 1, null, avro.encode(global("a", 20))));
            c.addRecord(new ConsumerRecord<>(topic, 1, 3, null, avro.encode(global("b", 5))));
        });

        List<SeriesStats> latest = SeriesStatsLog.readLatest(c, layout, Duration.ofSeconds(5));

        assertEquals(List.of(global("a", 20), global("b", 5)), latest);
        assertEquals(Set.of(), c.assignment(), "the consumer is unassigned after the read");
    }

    @Test
    void aMissingTopicIsAnErrorThatNamesIt() {
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SeriesStatsLog.readLatest(c, layout, Duration.ofSeconds(5)));

        assertEquals("tsx.unit.series-stats.v2: no such topic; check the domain, or create the topic first", e.getMessage());
    }
}
