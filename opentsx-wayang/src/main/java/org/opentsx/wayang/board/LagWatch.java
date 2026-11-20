package org.opentsx.wayang.board;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;

/**
 * Writes the lag of a run to a file every {@link #PERIOD}: the records in the input topic minus the records the run
 * has processed. The file holds one decimal line and is replaced atomically, so {@code tools/board_lag.py} can answer
 * the resource sampler within its 0.5-second limit without a broker client.
 */
final class LagWatch implements AutoCloseable {

    static final Duration PERIOD = Duration.ofMillis(500);

    private final Thread thread;
    private volatile boolean stop;

    /**
     * @param processed maps the sum of the input's beginning offsets to the sum of the next offsets the run will read;
     *                  a run that has processed nothing returns its argument
     */
    LagWatch(String bootstrap, String topic, Path file, LongUnaryOperator processed, BoardEvents events) {
        thread = new Thread(() -> {
            try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
                List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                        .map(i -> new TopicPartition(topic, i.partition())).toList();
                long begin = sum(consumer.beginningOffsets(partitions, Duration.ofSeconds(30)));
                while (!stop) {
                    long end = sum(consumer.endOffsets(partitions, Duration.ofSeconds(30)));
                    write(file, lag(end, processed.applyAsLong(begin)));
                    Thread.sleep(PERIOD.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                events.emit("lagFailed", Map.of("error", String.valueOf(e.getMessage())));
            }
        }, "opentsx-board-lag");
        thread.setDaemon(true);
        thread.start();
    }

    static long lag(long end, long processed) {
        return Math.max(0, end - processed);
    }

    /** Replaces {@code file} with one line holding {@code lag}. */
    static void write(Path file, long lag) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, lag + "\n", StandardCharsets.US_ASCII);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long sum(Map<TopicPartition, Long> offsets) {
        return offsets.values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public void close() throws InterruptedException {
        stop = true;
        thread.join(5_000);
    }
}
