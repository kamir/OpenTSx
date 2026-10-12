package org.opentsx.kafka;

import org.apache.kafka.common.TopicPartition;

/** Where an episode lives in the log. The index replaces timestamp seek, which KafScale does not support. */
public record IndexEntry(String episodeId, String seriesId, long tStartMicros, long tEndMicros, String bucketId,
                         String topic, int partition, long offset) {

    public TopicPartition topicPartition() {
        return new TopicPartition(topic, partition);
    }

    /** True if [tStart, tEnd) overlaps [fromMicros, toMicros). */
    public boolean overlaps(long fromMicros, long toMicros) {
        return tStartMicros < toMicros && tEndMicros > fromMicros;
    }
}
