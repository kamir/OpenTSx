package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodeLogTailTest {

    private final TopicLayout layout = new TopicLayout("unit");
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    private final Map<TopicPartition, Long> end = new HashMap<>();

    /** Appends records to the mock log; the consumer sees them on its next poll, once the partitions are assigned. */
    private void append(int partition, byte[]... values) {
        TopicPartition tp = new TopicPartition(layout.episodes(), partition);
        long first = end.getOrDefault(tp, 0L);
        end.put(tp, first + values.length);
        consumer.updateEndOffsets(end);
        consumer.schedulePollTask(() -> {
            for (int i = 0; i < values.length; i++) {
                consumer.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), first + i, new byte[0], values[i]));
            }
        });
    }

    private EpisodeLogTail tail() {
        Node node = new Node(0, "localhost", 9092);
        List<PartitionInfo> infos = new ArrayList<>();
        Map<TopicPartition, Long> begin = new HashMap<>();
        for (int p = 0; p < 2; p++) {
            infos.add(new PartitionInfo(layout.episodes(), p, node, new Node[]{node}, new Node[]{node}));
            begin.put(new TopicPartition(layout.episodes(), p), 0L);
            end.put(new TopicPartition(layout.episodes(), p), 0L);
        }
        consumer.updatePartitions(layout.episodes(), infos);
        consumer.updateBeginningOffsets(begin);
        consumer.updateEndOffsets(end);
        return new EpisodeLogTail(consumer, layout, Duration.ofSeconds(5));
    }

    @Test
    void firstPollReadsTheLogLaterPollsOnlyWhatWasAppended() {
        List<Episode> written = WindData.episodes(2, List.of("wind_speed"), 3, 30, 7);
        assertEquals(6, written.size());
        EpisodeLogTail tail = tail();
        List<String> seen = new ArrayList<>();

        assertEquals(0, tail.poll(e -> seen.add(e.getEpisodeId())), "an empty log delivers nothing");

        append(0, avro.encode(written.get(0)), avro.encode(written.get(1)));
        append(1, avro.encode(written.get(2)));
        assertEquals(3, tail.poll(e -> seen.add(e.getEpisodeId())));

        assertEquals(0, tail.poll(e -> seen.add(e.getEpisodeId())), "nothing new, nothing delivered twice");

        append(1, avro.encode(written.get(3)), "not avro".getBytes(StandardCharsets.UTF_8), avro.encode(written.get(4)));
        assertEquals(2, tail.poll(e -> seen.add(e.getEpisodeId())));
        assertEquals(1, tail.undecodable(), "a record that is no episode is skipped, not fatal");

        assertEquals(written.subList(0, 5).stream().map(Episode::getEpisodeId).sorted().toList(),
                seen.stream().sorted().toList());
    }
}
