package org.opentsx.kafscale;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Kafka-protocol features that OpenTSx episode storage and replay depend on.
 *
 * <p>REQUIRED features are asserted. OPTIONAL features (headers, CreateTime, timestamp seek,
 * idempotent producer, consumer-group rejoin, compaction config) are probed and written to {@code target/kafscale-capabilities.properties}
 * because KafScale does not guarantee them; OpenTSx must work without them.
 *
 * <p>Run against a broker with {@code KAFSCALE_BOOTSTRAP=host:port mvn -pl opentsx-kafscale-conformance test}.
 */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafScaleConformanceTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration GROUP_TIMEOUT = Duration.ofSeconds(20);
    private static final int SHORT_SESSION_MS = 6_000;

    private final String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private Admin admin;

    @BeforeAll
    void connect() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000);
        admin = Admin.create(p);
    }

    @AfterAll
    void close() {
        if (admin != null) {
            admin.close();
        }
        Capabilities.write();
    }

    // ---------------------------------------------------------------- REQUIRED

    @Test
    void createsTopicsViaAdminApi() throws Exception {
        String topic = createTopic("admin", 3);
        assertTrue(admin.listTopics().names().get(15, TimeUnit.SECONDS).contains(topic));
        assertEquals(3, admin.describeTopics(List.of(topic)).allTopicNames().get(15, TimeUnit.SECONDS)
                .get(topic).partitions().size());
    }

    @Test
    void producesAndConsumesInOrderPerKey() throws Exception {
        String topic = createTopic("order", 3);
        int n = 200;
        try (KafkaProducer<String, String> producer = stringProducer("none")) {
            for (int i = 0; i < n; i++) {
                producer.send(new ProducerRecord<>(topic, "series-" + (i % 4), Integer.toString(i)));
            }
            producer.flush();
        }

        List<ConsumerRecord<String, String>> records = consumeAll(topic, n, stringConsumer(group("order")));
        assertEquals(n, records.size());
        for (int k = 0; k < 4; k++) {
            String key = "series-" + k;
            int[] values = records.stream().filter(r -> r.key().equals(key))
                    .mapToInt(r -> Integer.parseInt(r.value())).toArray();
            int[] sorted = values.clone();
            Arrays.sort(sorted);
            assertArrayEquals(sorted, values, "per-key order must be preserved for " + key);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "snappy", "lz4", "zstd"})
    void roundTripsEveryCompressionCodec(String codec) throws Exception {
        String topic = createTopic("codec-" + codec, 1);
        byte[] payload = episodeLikePayload(64 * 1024);
        try (KafkaProducer<String, byte[]> producer = bytesProducer(codec)) {
            producer.send(new ProducerRecord<>(topic, "k", payload)).get(30, TimeUnit.SECONDS);
        }
        assertArrayEquals(payload, readFirstAssigned(topic).value());
    }

    @Test
    void roundTripsOneMegabyteEpisode() throws Exception {
        String topic = createTopic("large", 1);
        byte[] payload = episodeLikePayload(1024 * 1024 - 4096);
        try (KafkaProducer<String, byte[]> producer = bytesProducer("zstd")) {
            producer.send(new ProducerRecord<>(topic, "k", payload)).get(30, TimeUnit.SECONDS);
        }
        assertArrayEquals(payload, readFirstAssigned(topic).value());
    }

    /**
     * Offsets committed by a group member must be readable after a restart, also by a consumer that only
     * assigns partitions (OffsetFetch without membership) - this is how a replay resumes.
     */
    @Test
    void committedOffsetsAreReadableAfterRestart() throws Exception {
        String topic = createTopic("commit", 1);
        String group = group("commit");
        produceValues(topic, 10);
        TopicPartition tp = new TopicPartition(topic, 0);
        try (KafkaConsumer<String, String> c = stringConsumer(group)) {
            c.subscribe(List.of(topic));
            pollFirst(c);
            c.commitSync(Map.of(tp, new OffsetAndMetadata(4)));
        }
        try (KafkaConsumer<String, String> c = stringConsumer(group)) {
            c.assign(List.of(tp));
            OffsetAndMetadata committed = c.committed(Set.of(tp)).get(tp);
            assertNotNull(committed, "committed offset must be readable after restart");
            assertEquals(4L, committed.offset());
            c.seek(tp, committed.offset());
            assertEquals("v4", pollFirst(c).value());
        }
    }

    @Test
    void seeksToExplicitOffsetForIndexBasedReplay() throws Exception {
        String topic = createTopic("seek", 1);
        produceValues(topic, 20);
        TopicPartition tp = new TopicPartition(topic, 0);
        try (KafkaConsumer<String, String> c = stringConsumer(null)) {
            c.assign(List.of(tp));
            assertEquals(0L, c.beginningOffsets(List.of(tp)).get(tp));
            assertEquals(20L, c.endOffsets(List.of(tp)).get(tp));
            c.seek(tp, 13);
            ConsumerRecord<String, String> first = pollFirst(c);
            assertEquals(13L, first.offset());
            assertEquals("v13", first.value());
        }
    }

    // ---------------------------------------------------------------- OPTIONAL (probed)

    @Test
    void probeRecordHeaders() throws Exception {
        String topic = createTopic("headers", 1);
        try (KafkaProducer<String, String> producer = stringProducer("zstd")) {
            ProducerRecord<String, String> rec = new ProducerRecord<>(topic, "k", "v");
            rec.headers().add("tsx-schema", "org.opentsx.model.v2.Episode".getBytes(StandardCharsets.UTF_8));
            rec.headers().add("tsx-bucket", "bucket-42".getBytes(StandardCharsets.UTF_8));
            producer.send(rec).get(30, TimeUnit.SECONDS);
        }
        try (KafkaConsumer<String, String> c = stringConsumer(null)) {
            TopicPartition tp = new TopicPartition(topic, 0);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            ConsumerRecord<String, String> r = pollFirst(c);
            Header bucket = r.headers().lastHeader("tsx-bucket");
            boolean preserved = r.headers().toArray().length == 2 && bucket != null
                    && "bucket-42".equals(new String(bucket.value(), StandardCharsets.UTF_8));
            Capabilities.record("record.headers.preserved", preserved);
        }
    }

    @Test
    void probeCreateTimePreserved() throws Exception {
        String topic = createTopic("createtime", 1);
        long eventTime = 1_700_000_000_000L; // 2023-11-14, clearly not "now"
        try (KafkaProducer<String, String> producer = stringProducer("zstd")) {
            producer.send(new ProducerRecord<>(topic, 0, eventTime, "k", "v")).get(30, TimeUnit.SECONDS);
        }
        try (KafkaConsumer<String, String> c = stringConsumer(null)) {
            TopicPartition tp = new TopicPartition(topic, 0);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            ConsumerRecord<String, String> r = pollFirst(c);
            Capabilities.record("record.timestamp.type", r.timestampType().name);
            Capabilities.record("record.createtime.preserved",
                    r.timestampType() == TimestampType.CREATE_TIME && r.timestamp() == eventTime);
        }
    }

    @Test
    void probeOffsetsForTimes() throws Exception {
        String topic = createTopic("fortimes", 1);
        long base = 1_700_000_000_000L;
        try (KafkaProducer<String, String> producer = stringProducer("zstd")) {
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>(topic, 0, base + i * 60_000L, "k", "v" + i));
            }
            producer.flush();
        }
        TopicPartition tp = new TopicPartition(topic, 0);
        try (KafkaConsumer<String, String> c = stringConsumer(null)) {
            Map<TopicPartition, OffsetAndTimestamp> found =
                    c.offsetsForTimes(Map.of(tp, base + 5 * 60_000L), Duration.ofSeconds(15));
            OffsetAndTimestamp oat = found.get(tp);
            Capabilities.record("listoffsets.timestamp.result", oat == null ? "null" : Long.toString(oat.offset()));
            Capabilities.record("listoffsets.timestamp.supported", oat != null && oat.offset() == 5L);
        }
    }

    @Test
    void probeIdempotentProducer() throws Exception {
        String topic = createTopic("idempotent", 1);
        Properties p = producerProps("zstd");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        boolean supported;
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            RecordMetadata md = producer.send(new ProducerRecord<>(topic, "k", "v")).get(20, TimeUnit.SECONDS);
            supported = md.offset() >= 0;
        } catch (Exception e) {
            supported = false;
            Capabilities.record("producer.idempotence.error", e.getClass().getSimpleName());
        }
        Capabilities.record("producer.idempotence.supported", supported);
    }

    /** Flink and manual-assignment consumers commit with generation -1 and no member id. */
    @Test
    void probeStandaloneOffsetCommit() throws Exception {
        String topic = createTopic("standalone", 1);
        produceValues(topic, 3);
        TopicPartition tp = new TopicPartition(topic, 0);
        boolean supported;
        try (KafkaConsumer<String, String> c = stringConsumer(group("standalone"))) {
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            pollFirst(c);
            c.commitSync(Map.of(tp, new OffsetAndMetadata(2)));
            supported = Long.valueOf(2L).equals(c.committed(Set.of(tp)).get(tp).offset());
        } catch (Exception e) {
            supported = false;
            Capabilities.record("offsetcommit.standalone.error", e.getClass().getSimpleName());
        }
        Capabilities.record("offsetcommit.standalone.supported", supported);
    }

    @Test
    void probeGroupRejoinLatency() throws Exception {
        String topic = createTopic("rejoin", 1);
        String group = group("rejoin");
        try (KafkaProducer<String, String> producer = stringProducer("none")) {
            producer.send(new ProducerRecord<>(topic, "k", "v")).get(30, TimeUnit.SECONDS);
        }
        try (KafkaConsumer<String, String> c = shortSessionConsumer(group)) {
            c.subscribe(List.of(topic));
            pollFirst(c);
        } // close() sends LeaveGroup
        long start = System.currentTimeMillis();
        try (KafkaConsumer<String, String> c = shortSessionConsumer(group)) {
            c.subscribe(List.of(topic));
            long deadline = start + GROUP_TIMEOUT.toMillis();
            while (c.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
                c.poll(Duration.ofMillis(200));
            }
            long latency = System.currentTimeMillis() - start;
            Capabilities.record("group.rejoin.latency.ms", latency);
            // Kafka reassigns within about a second. KafScale v1.6.0 ignores the LeaveGroup v4 member list and
            // answers new members with REBALANCE_IN_PROGRESS instead of MEMBER_ID_REQUIRED, so the Java client
            // rejoins without a member id in a tight loop and the group never stabilises (no assignment here).
            Capabilities.record("group.rejoin.assigned", !c.assignment().isEmpty());
        }
    }

    @Test
    void probeLogCompactionConfig() {
        String name = "tsx-conf-" + run + "-compact";
        NewTopic t = new NewTopic(name, 1, (short) 1).configs(Map.of("cleanup.policy", "compact"));
        boolean accepted;
        try {
            admin.createTopics(List.of(t)).all().get(15, TimeUnit.SECONDS);
            accepted = true;
        } catch (Exception e) {
            accepted = false;
        }
        // Accepting the config does not mean the broker compacts; KafScale documents that it does not.
        Capabilities.record("topic.cleanup.policy.compact.accepted", accepted);
    }

    // ---------------------------------------------------------------- helpers

    private String createTopic(String suffix, int partitions) throws Exception {
        String name = "tsx-conf-" + run + "-" + suffix;
        admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get(15, TimeUnit.SECONDS);
        return name;
    }

    private void produceValues(String topic, int n) {
        try (KafkaProducer<String, String> producer = stringProducer("zstd")) {
            for (int i = 0; i < n; i++) {
                producer.send(new ProducerRecord<>(topic, "k", "v" + i));
            }
            producer.flush();
        }
    }

    private String group(String suffix) {
        return "tsx-conf-" + run + "-" + suffix;
    }

    private Properties producerProps(String codec) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        // KafScale has no InitProducerId; Kafka clients >= 3.0 default to idempotence=true.
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, codec);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 2 * 1024 * 1024);
        return p;
    }

    private KafkaProducer<String, String> stringProducer(String codec) {
        return new KafkaProducer<>(producerProps(codec), new StringSerializer(), new StringSerializer());
    }

    private KafkaProducer<String, byte[]> bytesProducer(String codec) {
        return new KafkaProducer<>(producerProps(codec), new StringSerializer(), new ByteArraySerializer());
    }

    private Properties consumerProps(String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        if (groupId != null) {
            p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        }
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 4 * 1024 * 1024);
        return p;
    }

    private KafkaConsumer<String, String> stringConsumer(String groupId) {
        return new KafkaConsumer<>(consumerProps(groupId), new StringDeserializer(), new StringDeserializer());
    }

    private KafkaConsumer<String, String> shortSessionConsumer(String groupId) {
        Properties p = consumerProps(groupId);
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SHORT_SESSION_MS);
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000);
        return new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
    }

    private ConsumerRecord<String, byte[]> readFirstAssigned(String topic) {
        TopicPartition tp = new TopicPartition(topic, 0);
        try (KafkaConsumer<String, byte[]> c =
                     new KafkaConsumer<>(consumerProps(null), new StringDeserializer(), new ByteArrayDeserializer())) {
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            return pollFirst(c);
        }
    }

    private static <K, V> List<ConsumerRecord<K, V>> consumeAll(String topic, int expected, KafkaConsumer<K, V> c) {
        try (c) {
            c.subscribe(List.of(topic));
            List<ConsumerRecord<K, V>> out = new ArrayList<>();
            long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
            while (out.size() < expected && System.currentTimeMillis() < deadline) {
                c.poll(Duration.ofMillis(500)).forEach(out::add);
            }
            return out;
        }
    }

    private static <K, V> ConsumerRecord<K, V> pollFirst(KafkaConsumer<K, V> c) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<K, V> r : c.poll(Duration.ofMillis(500))) {
                return r;
            }
        }
        assertNotNull(null, "no record received within " + POLL_TIMEOUT);
        return null;
    }

    /** Doubles with a smooth signal plus noise: compresses like real sensor episodes, not like zeros. */
    private static byte[] episodeLikePayload(int bytes) {
        Random random = new Random(7);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(bytes);
        int i = 0;
        while (buf.remaining() >= Double.BYTES) {
            buf.putDouble(10 + 3 * Math.sin(i++ / 50.0) + random.nextGaussian() * 0.2);
        }
        return buf.array();
    }
}
