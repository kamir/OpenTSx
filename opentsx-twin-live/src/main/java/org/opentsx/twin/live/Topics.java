package org.opentsx.twin.live;

import java.time.Duration;
import java.util.List;

/**
 * The little of a log that the publisher and the bridge need: append to a topic, and read a topic from an offset on.
 * {@link KafkaTopics} talks to KafScale or Apache Kafka; {@link MemoryTopics} keeps the log in the process.
 */
public interface Topics extends AutoCloseable {

    /** Start at the first record of the topic. */
    long EARLIEST = -2;
    /** Start behind the last record: only what arrives from now on. */
    long LATEST = -1;

    void append(String topic, String key, byte[] value, String schema);

    /** Opens a cursor at {@code offset}, {@link #EARLIEST} or {@link #LATEST}. */
    Cursor open(String topic, long offset) throws NoSuchTopicException;

    @Override
    void close();

    interface Cursor extends AutoCloseable {

        /** The next records in offset order; empty if none arrived within {@code timeout}. */
        List<Rec> poll(Duration timeout);

        @Override
        void close();
    }

    record Rec(long offset, long timestampMs, String key, byte[] value) {
    }

    final class NoSuchTopicException extends Exception {
        public NoSuchTopicException(String topic) {
            super("no such topic: " + topic);
        }
    }
}
