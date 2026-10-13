package org.opentsx.twin.live;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Topics on KafScale or Apache Kafka, with the client settings of {@link KafScaleClients}. Event topics have one
 * partition. Readers assign that partition and seek; they use no consumer group, because KafScale neither commits
 * without membership nor lets a restarted member rejoin.
 */
public final class KafkaTopics implements Topics {

    private final String bootstrap;
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private KafkaProducer<String, byte[]> producer;

    public KafkaTopics(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public synchronized void append(String topic, String key, byte[] value, String schema) {
        if (producer == null) {
            producer = KafScaleClients.producer(bootstrap);
        }
        if (known.add(topic)) {
            create(topic);
        }
        ProducerRecord<String, byte[]> rec = new ProducerRecord<>(topic, 0, key, value);
        rec.headers().add(TopicLayout.HEADER_SCHEMA, schema.getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(rec).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing to " + topic, e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("publishing to " + topic + " failed", e);
        }
    }

    private void create(String topic) {
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            if (!admin.listTopics().names().get(30, TimeUnit.SECONDS).contains(topic)) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)
                        .configs(Map.of("cleanup.policy", "delete")))).all().get(30, TimeUnit.SECONDS);
            }
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("creating topic " + topic + " failed", e.getCause());
            }
        } catch (Exception e) {
            throw new IllegalStateException("creating topic " + topic + " failed", e);
        }
    }

    @Override
    public Cursor open(String topic, long offset) throws NoSuchTopicException {
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            if (!admin.listTopics().names().get(30, TimeUnit.SECONDS).contains(topic)) {
                throw new NoSuchTopicException(topic);
            }
        } catch (NoSuchTopicException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot reach the broker at " + bootstrap, e);
        }
        KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
        TopicPartition tp = new TopicPartition(topic, 0);
        consumer.assign(List.of(tp));
        if (offset == EARLIEST) {
            consumer.seekToBeginning(List.of(tp));
        } else if (offset == LATEST) {
            consumer.seekToEnd(List.of(tp));
            consumer.position(tp);
        } else {
            consumer.seek(tp, Math.max(0, offset));
        }
        return new Cursor() {
            @Override
            public List<Rec> poll(Duration timeout) {
                List<Rec> out = new ArrayList<>();
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(timeout)) {
                    out.add(new Rec(r.offset(), r.timestamp(),
                            r.key() == null ? null : new String(r.key(), StandardCharsets.UTF_8), r.value()));
                }
                return out;
            }

            @Override
            public void close() {
                consumer.close(Duration.ofSeconds(2));
            }
        };
    }

    @Override
    public synchronized void close() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
            producer = null;
        }
    }
}
