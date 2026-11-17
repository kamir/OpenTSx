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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EpisodeIndexerTest {

    private final TopicLayout layout = new TopicLayout("unit");
    private final OpenTsxAvro avro = new OpenTsxAvro();

    /** One partition holding the given record values at offsets 0, 1, ... */
    private MockConsumer<byte[], byte[]> log(List<byte[]> values) {
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition(layout.episodes(), 0);
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(layout.episodes(),
                List.of(new PartitionInfo(tp.topic(), 0, node, new Node[]{node}, new Node[]{node})));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.updateEndOffsets(Map.of(tp, (long) values.size()));
        consumer.schedulePollTask(() -> {
            for (int i = 0; i < values.size(); i++) {
                consumer.addRecord(new ConsumerRecord<>(tp.topic(), 0, i, new byte[0], values.get(i)));
            }
        });
        return consumer;
    }

    @Test
    void aRecordThatIsNoEpisodeIsSkippedAndCounted() {
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 2, 30, 11);
        List<byte[]> values = new ArrayList<>();
        values.add(avro.encode(written.get(0)));
        values.add("not avro".getBytes(StandardCharsets.UTF_8));
        values.add(avro.encode(written.get(1)));
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        EpisodeIndexer.Result r = EpisodeIndexer.rebuild(log(values), layout, index, Duration.ofSeconds(5));
        assertEquals(new EpisodeIndexer.Result(2, 1), r);
        assertEquals(2, index.size());
    }

    @Test
    void anIndexFailureStopsTheRebuild() {
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 2, 30, 12);
        UncheckedIOException full = new UncheckedIOException(new IOException("No space left on device"));
        EpisodeIndex failing = new InMemoryEpisodeIndex() {
            @Override
            public synchronized void add(IndexEntry entry) {
                throw full;
            }
        };
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> EpisodeIndexer.rebuild(
                log(written.stream().map(avro::encode).toList()), layout, failing, Duration.ofSeconds(5)));
        assertSame(full, e);
    }
}
