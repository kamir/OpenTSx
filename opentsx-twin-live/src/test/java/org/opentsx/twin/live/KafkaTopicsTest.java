package org.opentsx.twin.live;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.opentsx.kafka.Patience;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaTopicsTest {

    private static final String TOPIC = "twin.demo.run-1.events";
    private static final Node BROKER = new Node(0, "localhost", 9092);

    /** A MockAdminClient whose first createTopics fails; the later ones create the topics. */
    static final class FailsFirstCreate extends MockAdminClient {
        int creates;

        FailsFirstCreate() {
            super(List.of(BROKER), BROKER);
        }

        @Override
        public synchronized CreateTopicsResult createTopics(Collection<NewTopic> topics, CreateTopicsOptions options) {
            if (++creates > 1) {
                return super.createTopics(topics, options);
            }
            Map<String, KafkaFuture<CreateTopicsResult.TopicMetadataAndConfig>> failed = new HashMap<>();
            for (NewTopic t : topics) {
                KafkaFutureImpl<CreateTopicsResult.TopicMetadataAndConfig> f = new KafkaFutureImpl<>();
                f.completeExceptionally(new TimeoutException("the broker did not answer"));
                failed.put(t.name(), f);
            }
            return new CreateTopicsResult(failed) {
            };
        }
    }

    /** A MockAdminClient whose listTopics and createTopics never answer. */
    static final class NeverAnswers extends MockAdminClient {

        @Override
        public synchronized ListTopicsResult listTopics(ListTopicsOptions options) {
            try {
                // the constructor of ListTopicsResult is package-private
                Constructor<ListTopicsResult> c = ListTopicsResult.class.getDeclaredConstructor(KafkaFuture.class);
                c.setAccessible(true);
                return c.newInstance(new KafkaFutureImpl<>());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized CreateTopicsResult createTopics(Collection<NewTopic> topics, CreateTopicsOptions options) {
            Map<String, KafkaFuture<CreateTopicsResult.TopicMetadataAndConfig>> unanswered = new HashMap<>();
            topics.forEach(t -> unanswered.put(t.name(), new KafkaFutureImpl<>()));
            return new CreateTopicsResult(unanswered) {
            };
        }
    }

    private static MockProducer<String, byte[]> producer() {
        return new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    }

    private static KafkaTopics topics(Admin admin, MockProducer<String, byte[]> producer, MockConsumer<byte[], byte[]> consumer) {
        return topics(admin, producer, consumer, new ArrayList<>());
    }

    /** The cursors wait out refusals with a sleeper that records each pause in {@code pauses} instead of sleeping. */
    private static KafkaTopics topics(Admin admin, MockProducer<String, byte[]> producer, MockConsumer<byte[], byte[]> consumer,
                                      List<Long> pauses) {
        return new KafkaTopics("mock:9092", b -> admin, b -> producer, b -> consumer,
                () -> new Patience(Duration.ofMinutes(10), Duration.ofSeconds(5), () -> 0L, pauses::add));
    }

    @Test
    void aFailedCreateIsRetriedByTheNextAppend() throws Exception {
        FailsFirstCreate admin = new FailsFirstCreate();
        MockProducer<String, byte[]> producer = producer();
        try (KafkaTopics topics = topics(admin, producer, null)) {
            IllegalStateException first = assertThrows(IllegalStateException.class,
                    () -> topics.append(TOPIC, "line", new byte[]{1}, EventTopic.SCHEMA));
            assertInstanceOf(TimeoutException.class, first.getCause());
            assertTrue(first.getMessage().contains(TOPIC), first.getMessage());
            assertEquals(List.of(), producer.history(), "nothing is sent to a topic that does not exist");

            topics.append(TOPIC, "line", new byte[]{2}, EventTopic.SCHEMA);
            assertEquals(2, admin.creates, "the next append creates the topic again");
            assertTrue(admin.listTopics().names().get().contains(TOPIC));
            assertEquals(1, producer.history().size());

            topics.append(TOPIC, "line", new byte[]{3}, EventTopic.SCHEMA);
            assertEquals(2, admin.creates, "a created topic is not created again");
            assertEquals(2, producer.history().size());
        }
    }

    @Test
    void anAppendAfterCloseIsRefusedAndOpensNoProducer() {
        MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER);
        List<MockProducer<String, byte[]>> made = new ArrayList<>();
        KafkaTopics topics = new KafkaTopics("mock:9092", b -> admin, b -> {
            MockProducer<String, byte[]> p = producer();
            made.add(p);
            return p;
        }, b -> null, () -> new Patience(Duration.ofMinutes(10), Duration.ofSeconds(5), () -> 0L, pause -> {
        }));
        topics.append(TOPIC, "line", new byte[]{1}, EventTopic.SCHEMA);
        topics.close();
        IllegalStateException closed = assertThrows(IllegalStateException.class,
                () -> topics.append(TOPIC, "line", new byte[]{2}, EventTopic.SCHEMA));
        assertEquals("topics: closed; create a new KafkaTopics to append again", closed.getMessage());
        assertEquals(1, made.size(), "no producer is opened after close");
        assertTrue(made.get(0).closed());
        assertEquals(1, made.get(0).history().size());
    }

    @Test
    void anInterruptedCreateOrOpenThrowsAndKeepsTheInterrupt() {
        try (KafkaTopics topics = topics(new NeverAnswers(), producer(), null)) {
            Thread.currentThread().interrupt();
            IllegalStateException create = assertThrows(IllegalStateException.class, () -> topics.create(TOPIC));
            assertTrue(Thread.interrupted(), "create keeps the interrupt for the caller: " + create.getMessage());
            assertInstanceOf(InterruptedException.class, create.getCause());

            Thread.currentThread().interrupt();
            IllegalStateException open = assertThrows(IllegalStateException.class, () -> topics.open(TOPIC, Topics.EARLIEST));
            assertTrue(Thread.interrupted(), "open keeps the interrupt for the caller: " + open.getMessage());
            assertInstanceOf(InterruptedException.class, open.getCause());
        }
    }

    @Test
    void aLookupThatTheBrokerDoesNotAnswerEndsAtItsTimeout() {
        try (KafkaTopics topics = topics(new NeverAnswers(), producer(), null)) {
            IllegalStateException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertThrows(IllegalStateException.class, () -> topics.exists(TOPIC, Duration.ofMillis(50))),
                    "a lookup the broker does not answer ends at its timeout, not after 30 s");
            assertEquals("cannot reach the broker at mock:9092", e.getMessage());
            assertInstanceOf(java.util.concurrent.TimeoutException.class, e.getCause());
        }
    }

    @Test
    void aRefusedPollOfTheCursorGivesNoRecordsAndTheNextPollReads() throws Exception {
        MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER);
        admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        int[] polls = {0};
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
            @Override
            public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
                if (++polls[0] == 1) {
                    throw new IllegalStateException("Unexpected error code 7 while fetching at offset 0 from topic-partition "
                            + TOPIC + "-0");
                }
                return super.poll(timeout);
            }
        };
        List<Long> pauses = new ArrayList<>();
        try (KafkaTopics topics = topics(admin, producer(), consumer, pauses);
             Topics.Cursor cursor = topics.open(TOPIC, 0)) {
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0L, "line".getBytes(StandardCharsets.UTF_8),
                    "{\"type\":\"a\"}".getBytes(StandardCharsets.UTF_8)));

            assertEquals(List.of(), cursor.poll(Duration.ofMillis(10)), "a refused fetch gives no records");
            assertEquals(List.of(5000L), pauses, "the refusal pauses once, for the pause of the Patience");
            List<Topics.Rec> recs = cursor.poll(Duration.ofMillis(10));
            assertEquals(2, polls[0], "one refused and one answered poll");
            assertEquals(List.of(5000L), pauses, "an answered poll does not pause");
            assertEquals(1, recs.size());
            assertEquals(0, recs.get(0).offset());
            assertEquals("line", recs.get(0).key());
            assertEquals(new TopicPartition(TOPIC, 0), consumer.assignment().iterator().next());
        }
    }
}
