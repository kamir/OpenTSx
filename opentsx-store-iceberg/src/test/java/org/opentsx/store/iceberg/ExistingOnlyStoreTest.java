package org.opentsx.store.iceberg;

import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExistingOnlyStoreTest {
    @TempDir Path dir;

    @BeforeEach
    void reset() {
        TrackingCatalog.calls.clear();
        TrackingCatalog.missing = "";
        TrackingCatalog.closeFails = false;
    }

    static IcebergSeriesStore existing(Class<?> type, Object argument) throws Exception {
        try {
            return (IcebergSeriesStore) IcebergSeriesStore.class
                    .getMethod("openExisting", type, type == Catalog.class ? Namespace.class : String.class)
                    .invoke(null, argument, type == Catalog.class ? Namespace.of("tsx") : "tsx");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw e;
        }
    }

    static Map<String, String> props() {
        return Map.of("catalog-impl", TrackingCatalog.class.getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"episodes", "series", "namespace"})
    void missingObjectsNeverCreateAndCloseOwnedCatalog(String missing) {
        TrackingCatalog.missing = missing;
        assertThrows(NoSuchTableException.class, () -> existing(Map.class, props()));
        assertEquals(missing.equals("series") ? List.of("load:episodes", "load:series", "close")
                : List.of("load:episodes", "close"), TrackingCatalog.calls);
    }

    @Test
    void failedBorrowedCatalogRemainsOwnedByCaller() {
        TrackingCatalog.missing = "series";
        assertThrows(NoSuchTableException.class, () -> existing(Catalog.class, new TrackingCatalog()));
        assertEquals(List.of("load:episodes", "load:series"), TrackingCatalog.calls);
    }

    @Test
    void failedOwnedInitializationRetainsCloseFailureAsSuppressed() {
        TrackingCatalog.missing = "episodes";
        TrackingCatalog.closeFails = true;
        NoSuchTableException failure = assertThrows(NoSuchTableException.class, () -> existing(Map.class, props()));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("close failed", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void readOnlyRejectsEmptyAndNonemptyWritesAndMutableTableAccessBeforeTouch() throws Exception {
        try (IcebergSeriesStore store = existing(Catalog.class, new TrackingCatalog())) {
            TrackingCatalog.calls.clear();
            var episode = Episodes.regular(SeriesKeys.of("synthetic.temperature", Map.of(), "Cel"),
                    0, 1_000_000, new double[]{1}).source("synthetic").build();
            assertThrows(UnsupportedOperationException.class, () -> store.write(List.of()));
            assertThrows(UnsupportedOperationException.class, () -> store.write(List.of(episode)));
            assertThrows(UnsupportedOperationException.class, store::episodesTable);
            assertEquals(List.of(), TrackingCatalog.calls);
        }
        assertEquals(List.of(), TrackingCatalog.calls);
    }

    @Test
    void successfulOwnedStoreClosesItsCatalog() throws Exception {
        try (IcebergSeriesStore ignored = existing(Map.class, props())) {
            assertEquals(List.of("load:episodes", "load:series"), TrackingCatalog.calls);
        }
        assertEquals(List.of("load:episodes", "load:series", "close"), TrackingCatalog.calls);
    }

    @Test
    void existingReaderReturnsWriterPayloadWithoutNewSnapshots() throws Exception {
        Map<String, String> props = IcebergSeriesStoreTest.catalogProps(dir);
        var episode = Episodes.regular(SeriesKeys.of("synthetic.temperature", Map.of(), "Cel"),
                0, 1_000_000, new double[]{1, 2, 3}).source("synthetic").build();
        try (IcebergSeriesStore writer = IcebergSeriesStore.open(props, "tsx")) {
            writer.write(List.of(episode));
            long snapshot = writer.episodesTable().currentSnapshot().snapshotId();
            try (IcebergSeriesStore reader = existing(Map.class, props)) {
                assertEquals(List.of(episode), reader.read(EpisodeQuery.of(SeriesSelector.all(), 0, 3_000_000)));
                assertEquals(List.of(episode.getSeries()), reader.listSeries(SeriesSelector.all()));
                assertEquals(episode, reader.episode(episode.getEpisodeId()).orElseThrow());
            }
            writer.episodesTable().refresh();
            assertEquals(snapshot, writer.episodesTable().currentSnapshot().snapshotId());
        }
    }

    public static class TrackingCatalog implements Catalog, SupportsNamespaces, Closeable {
        static final List<String> calls = new ArrayList<>();
        static String missing = "";
        static boolean closeFails;

        public TrackingCatalog() { }

        @Override
        public Table loadTable(TableIdentifier id) {
            calls.add("load:" + id.name());
            if (missing.equals(id.name()) || missing.equals("namespace")) throw new NoSuchTableException("missing");
            return (Table) Proxy.newProxyInstance(Table.class.getClassLoader(), new Class<?>[]{Table.class},
                    (proxy, method, arguments) -> { throw new AssertionError("table touched: " + method.getName()); });
        }

        @Override
        public List<TableIdentifier> listTables(Namespace namespace) { throw new AssertionError("listTables"); }

        @Override
        public boolean dropTable(TableIdentifier id, boolean purge) { throw new AssertionError("dropTable"); }

        @Override
        public void renameTable(TableIdentifier from, TableIdentifier to) { throw new AssertionError("renameTable"); }

        @Override
        public TableBuilder buildTable(TableIdentifier id, org.apache.iceberg.Schema schema) {
            throw new AssertionError("buildTable");
        }

        @Override
        public boolean tableExists(TableIdentifier id) { throw new AssertionError("tableExists"); }

        @Override
        public boolean namespaceExists(Namespace namespace) { throw new AssertionError("namespaceExists"); }

        @Override
        public void createNamespace(Namespace namespace, Map<String, String> metadata) {
            throw new AssertionError("createNamespace");
        }

        @Override
        public List<Namespace> listNamespaces(Namespace namespace) { throw new AssertionError("listNamespaces"); }

        @Override
        public Map<String, String> loadNamespaceMetadata(Namespace namespace) {
            throw new AssertionError("loadNamespaceMetadata");
        }

        @Override
        public boolean dropNamespace(Namespace namespace) { throw new AssertionError("dropNamespace"); }

        @Override
        public boolean setProperties(Namespace namespace, Map<String, String> properties) {
            throw new AssertionError("setProperties");
        }

        @Override
        public boolean removeProperties(Namespace namespace, Set<String> properties) {
            throw new AssertionError("removeProperties");
        }

        @Override
        public void close() throws IOException {
            calls.add("close");
            if (closeFails) throw new IOException("close failed");
        }
    }
}
