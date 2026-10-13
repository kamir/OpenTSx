package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.RetriableException;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import java.util.HashMap;
import java.util.Map;

/**
 * Waits out the times KafScale refuses to answer.
 *
 * <p>While KafScale rates its object store as degraded (about a minute after one slow write), it refuses every
 * produce and fetch with REQUEST_TIMED_OUT. A producer handles that with its delivery timeout (see
 * {@link KafScaleClients#producerProps}). A consumer does not: its {@code poll} throws
 * {@code IllegalStateException: Unexpected error code 7 while fetching ...}, and a reader that lets the exception
 * through dies although the broker is fine a minute later. {@link #poll} pauses instead and tries again; the
 * position of the consumer is untouched by the refusal.
 *
 * <p>One instance per reading loop; not thread-safe.
 */
public final class Patience {

    private final long limitNanos;
    private final long pauseMillis;
    private long refusedSince = -1;

    /**
     * @param limit how long refusals may last in a row before {@link #poll} gives up and throws
     * @param pause how long to wait after a refusal before the next try
     */
    public Patience(Duration limit, Duration pause) {
        this.limitNanos = limit.toNanos();
        this.pauseMillis = Math.max(1, pause.toMillis());
    }

    /** Ten minutes of refusals, a try every five seconds: for readers that run for good. */
    public static Patience standard() {
        return new Patience(Duration.ofMinutes(10), Duration.ofSeconds(5));
    }

    /**
     * True for the errors that mean "not now" and nothing about the data: the refused fetch (error code 7 reaches
     * the caller as an {@code IllegalStateException}) and the retriable errors of the client.
     */
    public static boolean isRefusal(Throwable t) {
        if (t instanceof RetriableException) {
            return true;
        }
        return t instanceof IllegalStateException && t.getMessage() != null
                && t.getMessage().startsWith("Unexpected error code 7 while fetching");
    }

    /** Where the consumer stands on every partition it is assigned. A partition without a position is left out. */
    private static <K, V> Map<TopicPartition, Long> positions(Consumer<K, V> consumer) {
        Map<TopicPartition, Long> out = new HashMap<>();
        for (TopicPartition tp : consumer.assignment()) {
            try {
                out.put(tp, consumer.position(tp));
            } catch (RuntimeException e) {
                // no position yet: the client finds one with the poll itself
            }
        }
        return out;
    }

    /**
     * Polls. A refusal gives no records after a pause; the caller's loop simply comes round again. The consumer then
     * stands where it stood before the poll, so no record is skipped.
     *
     * @throws IllegalStateException when the broker has refused for longer than the limit, with the refusal as cause
     */
    public <K, V> ConsumerRecords<K, V> poll(Consumer<K, V> consumer, Duration timeout) {
        Map<TopicPartition, Long> before = positions(consumer);
        try {
            ConsumerRecords<K, V> records = consumer.poll(timeout);
            if (refusedSince >= 0) {
                System.err.println("kafscale: the broker answers again after "
                        + Duration.ofNanos(System.nanoTime() - refusedSince).toSeconds() + " s");
                refusedSince = -1;
            }
            return records;
        } catch (RuntimeException e) {
            if (!isRefusal(e)) {
                throw e;
            }
            // The client throws the refusal of one partition out of the middle of a poll: records it had already taken
            // from other partitions are gone, and their positions have moved on. Nothing of this poll reached the
            // caller, so every partition goes back to where it was.
            before.forEach((tp, position) -> {
                try {
                    consumer.seek(tp, position);
                } catch (RuntimeException ignored) {
                    // no longer assigned: nothing to put back
                }
            });
            long now = System.nanoTime();
            if (refusedSince < 0) {
                refusedSince = now;
                System.err.println("kafscale: the broker refuses reads (" + e.getMessage() + "); waiting");
            } else if (now - refusedSince > limitNanos) {
                throw new IllegalStateException("the broker has refused reads for more than "
                        + Duration.ofNanos(limitNanos).toSeconds() + " s", e);
            }
            try {
                Thread.sleep(pauseMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the broker", ie);
            }
            return ConsumerRecords.empty();
        }
    }
}
