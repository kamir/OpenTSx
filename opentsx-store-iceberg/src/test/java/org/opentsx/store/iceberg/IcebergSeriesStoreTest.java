package org.opentsx.store.iceberg;

import org.apache.iceberg.CombinedScanTask;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.CloseableIterator;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.IndexEntry;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.SeriesStoreContract;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A JDBC catalog that counts how often it is closed. */
    public static class CountingCatalog extends JdbcCatalog {
        static int closed;

        public CountingCatalog() {
        }

        @Override
        public void close() {
            closed++;
            super.close();
        }
    }

    @Test
    void aStoreBorrowsTheCatalogItIsGivenAndClosesTheOneItOpens() throws Exception {
        Files.createDirectories(dir.resolve("own"));
        Map<String, String> props = new HashMap<>(catalogProps(dir.resolve("own")));
        props.put("catalog-impl", CountingCatalog.class.getName());
        CountingCatalog.closed = 0;
        Catalog catalog = IcebergSeriesStore.catalog(props);
        try {
            new IcebergSeriesStore(catalog, Namespace.of("tsx")).close();
            IcebergSeriesStore.openExisting(catalog, Namespace.of("tsx")).close();
            assertEquals(0, CountingCatalog.closed, "the caller's catalog stays open");
            assertTrue(catalog.tableExists(TableIdentifier.of("tsx", Tables.EPISODES)));
        } finally {
            ((Closeable) catalog).close();
        }

        IcebergSeriesStore.open(props, "tsx").close();
        IcebergSeriesStore.openExisting(props, "tsx").close();
        assertEquals(3, CountingCatalog.closed, "each properties overload closes the catalog it opened");
    }

    /** The scan, and every scan derived from it, fails to close its file tasks, as a broken object store stream does. */
    static TableScan failingToClose(TableScan scan) {
        return (TableScan) Proxy.newProxyInstance(TableScan.class.getClassLoader(), new Class<?>[]{TableScan.class},
                (p, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(scan, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (result instanceof TableScan next) {
                        return failingToClose(next);
                    }
                    if (method.getName().equals("planTasks")) {
                        @SuppressWarnings("unchecked")
                        CloseableIterable<CombinedScanTask> tasks = (CloseableIterable<CombinedScanTask>) result;
                        return new CloseableIterable<CombinedScanTask>() {
                            @Override
                            public CloseableIterator<CombinedScanTask> iterator() {
                                return tasks.iterator();
                            }

                            @Override
                            public void close() throws IOException {
                                tasks.close();
                                throw new IOException("stream closed by the object store");
                            }
                        };
                    }
                    return result;
                });
    }

    static Table scansFailToClose(Table table) {
        return IcebergIOTest.proxy(Table.class, table, (name, args) -> name.equals("newScan") ? failingToClose(table.newScan()) : null);
    }

    @Test
    void aFailingReadNamesTheTable() throws Exception {
        store.write(fixture().subList(0, 1));
        Catalog catalog = IcebergSeriesStore.catalog(catalogProps(dir));
        Catalog failing = IcebergIOTest.proxy(Catalog.class, catalog, (name, args) -> name.equals("loadTable")
                ? scansFailToClose(catalog.loadTable((TableIdentifier) args[0])) : null);
        try (IcebergSeriesStore reader = IcebergSeriesStore.openExisting(failing, Namespace.of("tsx"));
             IcebergSeriesStore writer = new IcebergSeriesStore(failing, Namespace.of("tsx"))) {
            UncheckedIOException read = assertThrows(UncheckedIOException.class,
                    () -> reader.read(EpisodeQuery.of(SeriesSelector.all(), T0, T0 + 3600 * SEC)));
            assertEquals("cannot read table opentsx.tsx.episodes", read.getMessage());
            assertEquals("stream closed by the object store", read.getCause().getMessage());
            UncheckedIOException write = assertThrows(UncheckedIOException.class, () -> writer.write(fixture().subList(1, 2)));
            assertEquals("cannot read table opentsx.tsx.episodes", write.getMessage());
        } finally {
            ((Closeable) catalog).close();
        }
    }
}
