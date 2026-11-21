package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Buckets;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.Episode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayServiceTest {

    private final TopicLayout layout = new TopicLayout("unit");
    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
    private final List<Episode> episodes = WindData.episodes(2, List.of("wind_speed"), 2, 30, 5);
    private List<byte[]> episodeLog;
    private List<byte[]> bucketLog;

    @BeforeEach
    void writeBucket() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        try (BucketWriter w = BucketWriter.open(producer, layout, index, "b-1", "exp", Map.of())) {
            episodes.forEach(w::append);
        }
        episodeLog = producer.history().stream().filter(r -> r.topic().equals(layout.episodes())).map(r -> r.value()).toList();
        bucketLog = producer.history().stream().filter(r -> r.topic().equals(layout.buckets())).map(r -> r.value()).toList();
        assertEquals(4, episodeLog.size());
        assertEquals(2, bucketLog.size());
    }

    @Test
    void aSealedBucketVerifiesWithItsContentChecksum() {
        ReplayResult r = replay(episodeLog, bucketLog);
        assertTrue(r.verified(), r.toString());
        assertEquals(avro.decode(bucketLog.get(1), BucketManifest.class).getChecksum(), r.checksum());
        assertTrue(r.checksum().startsWith("sha256:"), r.checksum());
    }

    @Test
    void changedContentUnderTheSameEpisodeIdDoesNotVerify() {
        Episode original = avro.decode(episodeLog.get(1), Episode.class);
        List<Double> values = new ArrayList<>(original.getValues());
        values.set(0, values.get(0) + 1.0);
        List<byte[]> log = new ArrayList<>(episodeLog);
        log.set(1, avro.encode(Episode.newBuilder(original).setValues(values).setRevision(1).build()));

        ReplayResult r = replay(log, bucketLog);
        assertEquals(episodes.size(), r.delivered());
        assertFalse(r.verified(), r.toString());
    }

    @Test
    void aManifestWithAnIdOnlyChecksumVerifiesTheIds() {
        String ids = Buckets.checksum(episodes.stream().map(Episode::getEpisodeId).toList());
        BucketManifest sealed = BucketManifest.newBuilder(avro.decode(bucketLog.get(1), BucketManifest.class))
                .setChecksum(ids).build();

        ReplayResult r = replay(episodeLog, List.of(bucketLog.get(0), avro.encode(sealed)));
        assertTrue(r.verified(), r.toString());
        assertEquals(ids, r.checksum());
    }

    private ReplayResult replay(List<byte[]> episodeRecords, List<byte[]> manifestRecords) {
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition ep = new TopicPartition(layout.episodes(), 0);
        TopicPartition bk = new TopicPartition(layout.buckets(), 0);
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(layout.buckets(),
                List.of(new PartitionInfo(bk.topic(), 0, node, new Node[]{node}, new Node[]{node})));
        consumer.updateBeginningOffsets(Map.of(ep, 0L, bk, 0L));
        consumer.updateEndOffsets(Map.of(ep, (long) episodeRecords.size(), bk, (long) manifestRecords.size()));
        // one poll task per read: the episode read polls first, the manifest read of BucketCatalog second
        consumer.schedulePollTask(() -> add(consumer, ep, episodeRecords));
        consumer.schedulePollTask(() -> add(consumer, bk, manifestRecords));
        return new ReplayService(consumer, layout, index, Duration.ofSeconds(5)).replay(ReplayRequest.bucket("b-1"), e -> { });
    }

    private static void add(MockConsumer<byte[], byte[]> consumer, TopicPartition tp, List<byte[]> values) {
        for (int i = 0; i < values.size(); i++) {
            consumer.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), i, new byte[0], values.get(i)));
        }
    }
}
