package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.InMemorySeriesStore;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafScaleFeedTest {

    private static final long T0 = 1_790_000_000_000_000L;
    private static final TopicLayout LAYOUT = new TopicLayout("wind");
    private static final OpenTsxAvro AVRO = new OpenTsxAvro();

    /** A broker with one partition per topic that holds the one episode given for the topic. */
    private static MockConsumer<byte[], byte[]> broker(Map<String, Episode> episodes) {
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
            @Override
            public synchronized void assign(Collection<TopicPartition> partitions) {
                super.assign(partitions);
                for (TopicPartition tp : partitions) {
                    addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), 0L, new byte[0], AVRO.encode(episodes.get(tp.topic()))));
                }
            }
        };
        Node node = new Node(0, "localhost", 9092);
        Map<TopicPartition, Long> begin = new HashMap<>();
        Map<TopicPartition, Long> end = new HashMap<>();
        for (String topic : episodes.keySet()) {
            consumer.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, node, new Node[]{node}, new Node[]{node})));
            begin.put(new TopicPartition(topic, 0), 0L);
            end.put(new TopicPartition(topic, 0), 1L);
        }
        consumer.updateBeginningOffsets(begin);
        consumer.updateEndOffsets(end);
        return consumer;
    }

    private static Episode episode(Map<String, String> tags) {
        return Episodes.regular(SeriesKeys.of("wind.turbine.power_active", tags, "kW"), T0, 60_000_000L, new double[]{1500, 1510})
                .source("opentsx-processor").createdAt(T0).build();
    }

    @Test
    void servesTheFeaturesTopicLikeTheCleanTopic() {
        Episode clean = episode(Map.of("system", "h2-chain", "signal", "park/T02/WTUR.W", "grade", "clean"));
        Episode features = episode(Map.of("system", "h2-chain", "signal", "park/T02/WTUR.W", "grade", "features", "feature", "lag_1"));
        InMemorySeriesStore store = new InMemorySeriesStore();
        try (KafScaleFeed feed = new KafScaleFeed("localhost:9092", broker(Map.of(LAYOUT.clean(), clean, LAYOUT.features(), features)),
                LAYOUT.domain(), store, 100, () -> { })) {
            assertEquals(2, feed.pull());
        }

        JsonNode inventory = new SeriesInventory(store, new SeriesInventory.Source("kafscale", LAYOUT.domain(),
                KafScaleFeed.topics(LAYOUT.domain())), null, null, Duration.ZERO).get();
        assertEquals(LAYOUT.features(), inventory.path("source").path("topics").path("features").asText());
        Map<String, String> feature = new TreeMap<>();
        for (JsonNode s : inventory.path("series")) {
            feature.put(s.path("tags").path("grade").asText(), s.path("tags").path("feature").asText("none"));
        }
        assertEquals(Map.of("clean", "none", "features", "lag_1"), feature);
    }
}
