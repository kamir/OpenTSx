package org.opentsx.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Client settings that work on KafScale (and on Apache Kafka).
 *
 * <ul>
 *   <li>Producer: {@code enable.idempotence=false} (KafScale has no InitProducerId; clients >= 3.0 default to
 *   true), {@code acks=all}, zstd, batching for cheap S3 segments. Duplicates are removed by episodeId.
 *   No fixed number of retries: KafScale refuses every produce for about a minute while it rates its object
 *   store as degraded, so a send retries with pauses until the delivery timeout of three minutes.</li>
 *   <li>Consumer: no group id - readers use manual assignment and seek (KafScale cannot seek by time, rejects
 *   commits without membership and does not let restarted group members rejoin, see kamir/kafscale#16-#18).
 *   Read with {@link Patience#poll}: a refused fetch otherwise ends the reader with an exception.</li>
 * </ul>
 */
public final class KafScaleClients {

    /** How long a send keeps retrying before it fails. Longer than the minute KafScale refuses writes at a time. */
    public static final java.time.Duration DELIVERY_TIMEOUT = java.time.Duration.ofMinutes(3);

    private KafScaleClients() {
    }

    public static Properties producerProps(String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        p.put(ProducerConfig.LINGER_MS_CONFIG, 50);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 512 * 1024);
        p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 4 * 1024 * 1024);
        // retries stays at the client default (unbounded): the delivery timeout decides when a send has failed
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) DELIVERY_TIMEOUT.toMillis());
        p.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000L);
        p.put(ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG, 10_000L);
        // without idempotence, more than one in-flight request can reorder episodes of a series on retry
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        return p;
    }

    public static Properties consumerProps(String bootstrap) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 8 * 1024 * 1024);
        p.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 32 * 1024 * 1024);
        return p;
    }

    public static KafkaProducer<String, byte[]> producer(String bootstrap) {
        return new KafkaProducer<>(producerProps(bootstrap), new StringSerializer(), new ByteArraySerializer());
    }

    public static KafkaConsumer<byte[], byte[]> consumer(String bootstrap) {
        return new KafkaConsumer<>(consumerProps(bootstrap), new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    public static Admin admin(String bootstrap) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        return Admin.create(p);
    }
}
