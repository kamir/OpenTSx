package org.opentsx.store.iceberg;

import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.opentsx.kafka.EpisodeIndex;
import org.opentsx.kafka.IndexEntry;
import org.opentsx.kafka.InMemoryEpisodeIndex;
import org.opentsx.model.Micros;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Durable episode index for KafScale replay, stored in {@code <namespace>.episode_index}.
 *
 * <p>{@link #add} buffers entries; {@link #flush()} (also called by {@link #close()}) commits them in one
 * Iceberg snapshot. Queries see committed and buffered entries; duplicates keep the lowest offset.
 */
public final class IcebergEpisodeIndex implements EpisodeIndex, AutoCloseable {

    private final Table table;
    private final List<IndexEntry> buffer = new ArrayList<>();

    public IcebergEpisodeIndex(Catalog catalog, Namespace namespace) {
        IcebergSeriesStore.createNamespace(catalog, namespace);
        TableIdentifier id = TableIdentifier.of(namespace, Tables.EPISODE_INDEX);
        this.table = IcebergSeriesStore.loadOrCreate(catalog, id, () -> catalog
                .buildTable(id, Tables.INDEX_SCHEMA)
                .withPartitionSpec(Tables.INDEX_SPEC)
                .withProperty("format-version", "2")
                .create());
    }

    @Override
    public synchronized void add(IndexEntry entry) {
        buffer.add(entry);
    }

    public synchronized void flush() {
        List<Record> rows = new ArrayList<>(buffer.size());
        for (IndexEntry e : buffer) {
            GenericRecord r = GenericRecord.create(Tables.INDEX_SCHEMA);
            r.setField("episode_id", e.episodeId());
            r.setField("series_id", e.seriesId());
            r.setField("t_start", IcebergIO.ts(Micros.toInstant(e.tStartMicros())));
            r.setField("t_end", IcebergIO.ts(Micros.toInstant(e.tEndMicros())));
            r.setField("bucket_id", e.bucketId());
            r.setField("topic", e.topic());
            r.setField("kafka_partition", e.partition());
            r.setField("kafka_offset", e.offset());
            rows.add(r);
        }
        IcebergIO.append(table, rows);
        buffer.clear();
    }

    @Override
    public synchronized List<IndexEntry> bucket(String bucketId) {
        return query(Expressions.equal("bucket_id", bucketId)).bucket(bucketId);
    }

    @Override
    public synchronized List<IndexEntry> find(Set<String> seriesIds, long fromMicros, long toMicros) {
        Expression f = Expressions.and(Expressions.lessThan("t_start", toMicros), Expressions.greaterThan("t_end", fromMicros));
        if (seriesIds != null) {
            f = Expressions.and(f, Expressions.in("series_id", seriesIds.toArray()));
        }
        return query(f).find(seriesIds, fromMicros, toMicros);
    }

    @Override
    public synchronized int size() {
        return query(Expressions.alwaysTrue()).size();
    }

    @Override
    public void close() {
        flush();
    }

    private InMemoryEpisodeIndex query(Expression filter) {
        table.refresh();
        InMemoryEpisodeIndex result = new InMemoryEpisodeIndex();
        IcebergSeriesStore.scan(table, filter, r -> result.add(new IndexEntry(
                (String) r.getField("episode_id"), (String) r.getField("series_id"),
                Micros.of(IcebergIO.instant(r.getField("t_start"))), Micros.of(IcebergIO.instant(r.getField("t_end"))),
                (String) r.getField("bucket_id"), (String) r.getField("topic"),
                (Integer) r.getField("kafka_partition"), (Long) r.getField("kafka_offset"))));
        buffer.forEach(result::add);
        return result;
    }
}
