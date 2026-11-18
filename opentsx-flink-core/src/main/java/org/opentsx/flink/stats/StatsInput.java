package org.opentsx.flink.stats;

/**
 * One record of the episodes or the observations topic, keyed by series id. The payload stays encoded until
 * {@link StatsFunction} decodes it, so Flink serializes it as a POJO and never through Kryo.
 */
public final class StatsInput {

    public String seriesId;
    /** True for an {@code Episode}, false for an {@code Observation}. */
    public boolean episode;
    /** Avro single-object encoding, as written by {@code OpenTsxAvro}. */
    public byte[] payload;
    /** Topic, partition and offset of the record, for the stderr report of an undecodable input or an invalid episode. */
    public String topic;
    public int partition;
    public long offset;

    public StatsInput() {
    }

    public StatsInput(String seriesId, boolean episode, byte[] payload, String topic, int partition, long offset) {
        this.seriesId = seriesId;
        this.episode = episode;
        this.payload = payload;
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
    }

    public String getSeriesId() {
        return seriesId;
    }
}
