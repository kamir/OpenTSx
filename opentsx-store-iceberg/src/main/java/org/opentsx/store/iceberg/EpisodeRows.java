package org.opentsx.store.iceberg;

import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.EpisodeSummary;
import org.opentsx.model.v2.Provenance;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.opentsx.store.iceberg.IcebergIO.instant;
import static org.opentsx.store.iceberg.IcebergIO.ts;

/** Lossless mapping between v2 {@link Episode}s and rows of the {@code episodes} table. */
final class EpisodeRows {

    private EpisodeRows() {
    }

    static Record toRow(Episode e) {
        GenericRecord r = GenericRecord.create(Tables.EPISODE_SCHEMA);
        SeriesKey k = e.getSeries();
        r.setField("episode_id", e.getEpisodeId());
        r.setField("series_id", k.getSeriesId());
        r.setField("metric", k.getMetric());
        r.setField("tags", new LinkedHashMap<>(k.getTags()));
        r.setField("unit", k.getUnit());
        r.setField("t_start", ts(e.getTStart()));
        r.setField("t_end", ts(e.getTEnd()));
        r.setField("count", e.getCount());
        r.setField("time_encoding", e.getTimeEncoding().name());
        r.setField("interval_us", e.getIntervalMicros());
        r.setField("time_deltas", copy(e.getTimeDeltas()));
        r.setField("values", new ArrayList<>(e.getValues()));
        r.setField("quality", copy(e.getQuality()));
        EpisodeSummary s = e.getSummary();
        r.setField("s_valid_count", s.getValidCount());
        r.setField("s_nan_count", s.getNanCount());
        r.setField("s_min", s.getMin());
        r.setField("s_max", s.getMax());
        r.setField("s_mean", s.getMean());
        r.setField("s_m2", s.getM2());
        r.setField("s_sax", s.getSax());
        Segmentation seg = e.getSegmentation();
        if (seg != null) {
            r.setField("seg_strategy", seg.getStrategy().name());
            r.setField("seg_params", new LinkedHashMap<>(seg.getParams()));
            r.setField("seg_anchor_ts", ts(seg.getAnchorTs()));
            r.setField("seg_source_episode_id", seg.getSourceEpisodeId());
        }
        r.setField("labels", new LinkedHashMap<>(e.getLabels()));
        r.setField("bucket_id", e.getBucketId());
        r.setField("revision", e.getRevision());
        Provenance p = e.getProvenance();
        r.setField("source", p.getSource());
        r.setField("producer", p.getProducer());
        r.setField("created_at", ts(p.getCreatedAt()));
        r.setField("pipeline", p.getPipeline());
        r.setField("parent_episode_id", p.getParentEpisodeId());
        return r;
    }

    @SuppressWarnings("unchecked")
    static Episode fromRow(Record r) {
        SeriesKey key = SeriesKey.newBuilder()
                .setMetric((String) r.getField("metric"))
                .setTags(SeriesKeys.sortedByUtf8((Map<String, String>) r.getField("tags")))
                .setUnit((String) r.getField("unit"))
                .setSeriesId((String) r.getField("series_id"))
                .build();
        Segmentation seg = null;
        if (r.getField("seg_strategy") != null) {
            seg = Segmentation.newBuilder()
                    .setStrategy(SegmentationStrategy.valueOf((String) r.getField("seg_strategy")))
                    .setParams(SeriesKeys.sortedByUtf8((Map<String, String>) r.getField("seg_params")))
                    .setAnchorTs(instant(r.getField("seg_anchor_ts")))
                    .setSourceEpisodeId((String) r.getField("seg_source_episode_id"))
                    .build();
        }
        return Episode.newBuilder()
                .setEpisodeId((String) r.getField("episode_id"))
                .setSeries(key)
                .setTStart(instant(r.getField("t_start")))
                .setTEnd(instant(r.getField("t_end")))
                .setCount((Integer) r.getField("count"))
                .setTimeEncoding(TimeEncoding.valueOf((String) r.getField("time_encoding")))
                .setIntervalMicros((Long) r.getField("interval_us"))
                .setTimeDeltas(copy((ByteBuffer) r.getField("time_deltas")))
                .setValues(new ArrayList<>((List<Double>) r.getField("values")))
                .setQuality(copy((ByteBuffer) r.getField("quality")))
                .setSummary(EpisodeSummary.newBuilder()
                        .setValidCount((Long) r.getField("s_valid_count"))
                        .setNanCount((Long) r.getField("s_nan_count"))
                        .setMin((Double) r.getField("s_min"))
                        .setMax((Double) r.getField("s_max"))
                        .setMean((Double) r.getField("s_mean"))
                        .setM2((Double) r.getField("s_m2"))
                        .setSax((String) r.getField("s_sax"))
                        .build())
                .setSegmentation(seg)
                .setLabels(SeriesKeys.sortedByUtf8((Map<String, String>) r.getField("labels")))
                .setBucketId((String) r.getField("bucket_id"))
                .setRevision((Integer) r.getField("revision"))
                .setProvenance(Provenance.newBuilder()
                        .setSource((String) r.getField("source"))
                        .setProducer((String) r.getField("producer"))
                        .setCreatedAt(instant(r.getField("created_at")))
                        .setPipeline((String) r.getField("pipeline"))
                        .setParentEpisodeId((String) r.getField("parent_episode_id"))
                        .build())
                .build();
    }

    static Record seriesRow(SeriesKey k) {
        GenericRecord r = GenericRecord.create(Tables.SERIES_SCHEMA);
        r.setField("series_id", k.getSeriesId());
        r.setField("metric", k.getMetric());
        r.setField("tags", new LinkedHashMap<>(k.getTags()));
        r.setField("unit", k.getUnit());
        return r;
    }

    @SuppressWarnings("unchecked")
    static SeriesKey seriesFromRow(Record r) {
        return SeriesKey.newBuilder()
                .setSeriesId((String) r.getField("series_id"))
                .setMetric((String) r.getField("metric"))
                .setTags(SeriesKeys.sortedByUtf8((Map<String, String>) r.getField("tags")))
                .setUnit((String) r.getField("unit"))
                .build();
    }

    private static ByteBuffer copy(ByteBuffer b) {
        if (b == null) {
            return null;
        }
        ByteBuffer d = b.duplicate();
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return ByteBuffer.wrap(out);
    }
}
