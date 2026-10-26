package org.opentsx.flink.stats;

/** One encoded {@code SeriesStats} with the record key and timestamp the KafScale processor writes. */
public final class StatsRecord {

    public String seriesId;
    /** Window end in epoch milliseconds, the Kafka record timestamp. */
    public long timestampMs;
    /** Avro single-object encoding of the {@code SeriesStats}. */
    public byte[] payload;

    public StatsRecord() {
    }

    public StatsRecord(String seriesId, long timestampMs, byte[] payload) {
        this.seriesId = seriesId;
        this.timestampMs = timestampMs;
        this.payload = payload;
    }
}
