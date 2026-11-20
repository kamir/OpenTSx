package org.opentsx.twin.live;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.Patience;
import org.opentsx.kafka.TopicLayout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Topics on KafScale or Apache Kafka, with the client settings of {@link KafScaleClients}. Event topics have one
 * partition. Readers assign that partition and seek; they use no consumer group, because KafScale neither commits
 * without membership nor lets a restarted member rejoin. A cursor waits out the reads KafScale refuses ({@link Patience}).
 * The readiness lookups of {@link #exists} share one Admin client, opened at the first lookup and closed by
 * {@link #close}; a lookup that fails closes it, and the next lookup opens a new one. {@link #open} looks its topic up on
 * an Admin of its own, because closing the shared one ends every lookup that waits on it.
 */
public final class KafkaTopics implements Topics {

    private final String bootstrap;
    private final Function<String, Admin> admins;
    private final Function<String, Producer<String, byte[]>> producers;
    private final Function<String, Consumer<byte[], byte[]>> consumers;
    private final Supplier<Patience> patience;
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private Producer<String, byte[]> producer;
    private volatile boolean closed;
    private final Object adminLock = new Object();
    private Admin admin;

    public KafkaTopics(String bootstrap) {
        this(bootstrap, KafScaleClients::admin, KafScaleClients::producer, KafScaleClients::consumer, Patience::standard);
    }

    /** {@code patience} gives one {@link Patience} per cursor. */
    KafkaTopics(String bootstrap, Function<String, Admin> admins, Function<String, Producer<String, byte[]>> producers,
                Function<String, Consumer<byte[], byte[]>> consumers, Supplier<Patience> patience) {
        this.bootstrap = bootstrap;
        this.admins = admins;
        this.producers = producers;
        this.consumers = consumers;
        this.patience = patience;
    }

    @Override
    public synchronized void append(String topic, String key, byte[] value, String schema) {
        if (closed) {
            throw new IllegalStateException("topics: closed; create a new KafkaTopics to append again");
        }
        if (producer == null) {
            producer = producers.apply(bootstrap);
        }
        create(topic);
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

    /** A topic counts as known only once it was created, so a failed create is tried again by the next call. */
    @Override
    public void create(String topic) {
        if (known.contains(topic)) {
            return;
        }
        try (Admin admin = admins.apply(bootstrap)) {
            TopicLayout.provisionTwinEvents(admin, topic, (short) 1);
        }
        known.add(topic);
    }

    @Override
    public boolean exists(String topic, Duration timeout) {
        Admin lookup = admin();
        try {
            return listed(lookup, topic, timeout);
        } catch (RuntimeException e) {
            drop(lookup);
            throw e;
        }
    }

    /** The broker gets at most {@code timeout} to answer the lookup; the admin request ends at the same deadline. */
    private boolean listed(Admin lookup, String topic, Duration timeout) {
        long ms = timeout.toMillis();
        try {
            return lookup.listTopics(new ListTopicsOptions().timeoutMs((int) ms)).names().get(ms, TimeUnit.MILLISECONDS)
                    .contains(topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while looking up " + topic + " at " + bootstrap, e);
        } catch (Exception e) {
            throw new IllegalStateException("cannot reach the broker at " + bootstrap, e);
        }
    }

    /** The Admin of the lookups, opened when there is none. */
    private Admin admin() {
        synchronized (adminLock) {
            if (closed) {
                throw new IllegalStateException("topics: closed; create a new KafkaTopics to look up a topic");
            }
            if (admin == null) {
                admin = newAdmin();
            }
            return admin;
        }
    }

    private Admin newAdmin() {
        try {
            return admins.apply(bootstrap);
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot reach the broker at " + bootstrap, e);
        }
    }

    /** Closes an Admin whose lookup failed, so that the next lookup opens a new one. */
    private void drop(Admin failed) {
        synchronized (adminLock) {
            if (admin == failed) {
                admin = null;
            }
        }
        failed.close(Duration.ZERO);
    }

    @Override
    public Cursor open(String topic, long offset) throws NoSuchTopicException {
        try (Admin own = newAdmin()) {
            if (!listed(own, topic, Duration.ofSeconds(30))) {
                throw new NoSuchTopicException(topic);
            }
        }
        Consumer<byte[], byte[]> consumer = consumers.apply(bootstrap);
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
        Patience refusals = patience.get();
        return new Cursor() {
            @Override
            public List<Rec> poll(Duration timeout) {
                List<Rec> out = new ArrayList<>();
                for (ConsumerRecord<byte[], byte[]> r : refusals.poll(consumer, timeout)) {
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
        closed = true;
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
            producer = null;
        }
        synchronized (adminLock) {
            if (admin != null) {
                admin.close(Duration.ofSeconds(5));
                admin = null;
            }
        }
    }
}
