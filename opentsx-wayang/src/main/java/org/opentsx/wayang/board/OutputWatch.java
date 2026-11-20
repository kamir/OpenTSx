package org.opentsx.wayang.board;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Polls the end offsets of the output topic every 100 ms and reports {@code firstOutput} when they first grow. */
final class OutputWatch implements AutoCloseable {

    private final Thread thread;
    private volatile boolean stop;

    OutputWatch(String bootstrap, String topic, BoardEvents events) {
        thread = new Thread(() -> {
            try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
                List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(30));
                List<TopicPartition> partitions = infos.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
                long baseline = total(consumer.endOffsets(partitions, Duration.ofSeconds(30)));
                while (!stop && !events.hasOutput()) {
                    if (total(consumer.endOffsets(partitions, Duration.ofSeconds(30))) > baseline) events.firstOutput();
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                events.emit("watchFailed", Map.of("error", String.valueOf(e.getMessage())));
            }
        }, "opentsx-board-output-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private static long total(Map<TopicPartition, Long> offsets) {
        return offsets.values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public void close() throws InterruptedException {
        stop = true;
        thread.join(5_000);
    }
}
