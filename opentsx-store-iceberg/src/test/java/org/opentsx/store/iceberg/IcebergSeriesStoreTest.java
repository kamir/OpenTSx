package org.opentsx.store.iceberg;

import org.apache.iceberg.Snapshot;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.IndexEntry;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.SeriesStoreContract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the store contract on Iceberg with a JDBC (SQLite) catalog and a local warehouse. */
class IcebergSeriesStoreTest extends SeriesStoreContract {

    @TempDir
    Path dir;

    static Map<String, String> catalogProps(Path dir) {
        return Map.of(
                "catalog-impl", "org.apache.iceberg.jdbc.JdbcCatalog",
                "uri", "jdbc:sqlite:" + dir.resolve("catalog.db"),
                "jdbc.schema-version", "V1",
                "warehouse", dir.resolve("warehouse").toUri().toString());
    }

    @Override
    protected SeriesStore newStore() {
        return IcebergSeriesStore.open(catalogProps(dir), "tsx");
    }

    @Test
    void reopenedStoreSeesCommittedDataAndSupportsTimeTravel() throws Exception {
        List<Episode> fixture = fixture();
        store.write(fixture.subList(0, 3));
        IcebergSeriesStore first = (IcebergSeriesStore) store;
        Snapshot afterFirst = first.episodesTable().currentSnapshot();
        store.write(fixture.subList(3, fixture.size()));

        try (IcebergSeriesStore reopened = IcebergSeriesStore.open(catalogProps(dir), "tsx")) {
            assertEquals(fixture.size(), reopened.read(EpisodeQuery.of(SeriesSelector.all(), T0, T0 + 3600 * SEC)).size());
            try (CloseableIterable<Record> old = IcebergGenerics.read(reopened.episodesTable()).useSnapshot(afterFirst.snapshotId()).build()) {
                int n = 0;
                for (Record ignored : old) {
                    n++;
                }
                assertEquals(3, n);
            }
        }
    }

    @Test
    void episodeIndexPersistsAcrossRestarts() throws Exception {
        Map<String, String> props = catalogProps(dir.resolve("index-catalog"));
        java.nio.file.Files.createDirectories(dir.resolve("index-catalog"));
        Catalog catalog = IcebergSeriesStore.catalog(props);
        try (IcebergEpisodeIndex index = new IcebergEpisodeIndex(catalog, Namespace.of("tsx"))) {
            index.add(new IndexEntry("e1", "s1", 0, 10, "b", "tsx.wind.episodes.v2", 0, 5));
            index.add(new IndexEntry("e2", "s2", 10, 20, "b", "tsx.wind.episodes.v2", 1, 3));
            assertEquals(2, index.bucket("b").size()); // buffered entries are visible before flush
        }
        ((AutoCloseable) catalog).close();

        Catalog again = IcebergSeriesStore.catalog(props);
        try (IcebergEpisodeIndex reopened = new IcebergEpisodeIndex(again, Namespace.of("tsx"))) {
            reopened.add(new IndexEntry("e1", "s1", 0, 10, "b", "tsx.wind.episodes.v2", 0, 9)); // duplicate, later offset
            assertEquals(2, reopened.size());
            assertEquals(5, reopened.bucket("b").get(0).offset());
            assertEquals(List.of("e2"), reopened.find(Set.of("s2"), 0, 100).stream().map(IndexEntry::episodeId).toList());
        } finally {
            ((AutoCloseable) again).close();
        }
    }
}
