package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.apache.kafka.common.TopicPartition;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Waits out the times KafScale refuses to answer.
 *
 * <p>While KafScale rates its object store as degraded (about a minute after one slow write), it refuses every
 * produce and fetch with REQUEST_TIMED_OUT. A producer handles that with its delivery timeout (see
 * {@link KafScaleClients#producerProps}). A consumer does not: its {@code poll} throws
 * {@code IllegalStateException: Unexpected error code 7 while fetching ...}, and a reader that lets the exception
 * through dies although the broker is fine a minute later. {@link #poll} pauses instead and tries again; the
 * position of the consumer is untouched by the refusal. The first refusal of a row is logged at WARN, the next answer
 * at INFO.
 *
 * <p>One instance per reading loop; not thread-safe.
 */
public final class Patience {

    private static final Logger LOG = LoggerFactory.getLogger(Patience.class);

    private final long limitNanos;
    private final long pauseMillis;
    private final LongSupplier nanoClock;
    private final LongConsumer sleeper;
    private long refusedSince = -1;
    private boolean refused;

    /**
     * @param limit how long refusals may last in a row before {@link #poll} gives up and throws
     * @param pause how long to wait after a refusal before the next try
     */
    public Patience(Duration limit, Duration pause) {
        this(limit, pause, System::nanoTime, Patience::sleep);
    }

    /**
     * With the clock and the pause given, for tests that move a clock instead of sleeping: {@code nanoClock} is read
     * like {@link System#nanoTime}, {@code sleeper} receives the pause in milliseconds.
     */
    public Patience(Duration limit, Duration pause, LongSupplier nanoClock, LongConsumer sleeper) {
        this.limitNanos = limit.toNanos();
        this.pauseMillis = Math.max(1, pause.toMillis());
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the broker", ie);
        }
    }

    /** Whether the broker refused the last {@link #poll}; false before the first. */
    public boolean refusing() {
        return refused;
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
     * Polls. A refusal gives no records after a pause, and the caller's loop comes round again. The consumer then
     * stands where it stood before the poll, so no record is skipped.
     *
     * @throws IllegalStateException when the broker has refused for longer than the limit, with the refusal as cause
     */
    public <K, V> ConsumerRecords<K, V> poll(Consumer<K, V> consumer, Duration timeout) {
        Map<TopicPartition, Long> before = positions(consumer);
        try {
            ConsumerRecords<K, V> records = consumer.poll(timeout);
            refused = false;
            if (refusedSince >= 0) {
                LOG.info("the broker answers reads again refusedFor={}",
                        Duration.ofNanos(nanoClock.getAsLong() - refusedSince).truncatedTo(ChronoUnit.MILLIS));
                refusedSince = -1;
            }
            return records;
        } catch (RuntimeException e) {
            if (!isRefusal(e)) {
                throw e;
            }
            refused = true;
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
            long now = nanoClock.getAsLong();
            if (refusedSince < 0) {
                refusedSince = now;
                LOG.warn("the broker refuses reads; waiting pause={} limit={} cause={}", Duration.ofMillis(pauseMillis),
                        Duration.ofNanos(limitNanos), LogReader.causes(e));
            } else if (now - refusedSince > limitNanos) {
                throw new IllegalStateException("the broker has refused reads for more than "
                        + Duration.ofNanos(limitNanos), e);
            }
            sleeper.accept(pauseMillis);
            return ConsumerRecords.empty();
        }
    }
}
