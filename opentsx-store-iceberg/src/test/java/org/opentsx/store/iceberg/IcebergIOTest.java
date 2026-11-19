package org.opentsx.store.iceberg;

import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.jdbc.UncheckedSQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IcebergIOTest {

    @TempDir
    Path dir;

    /** Delegates every call to {@code target}; {@code override} answers the calls it knows and returns null otherwise. */
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, T target, java.util.function.BiFunction<String, Object[], Object> override) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            Object answer = override.apply(method.getName(), args);
            if (answer != null) {
                return answer;
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    /** The table with a commit that throws {@code commitFailure}; deletes are recorded, and fail with {@code deleteFailure}. */
    static Table failingCommit(Table table, RuntimeException commitFailure, RuntimeException deleteFailure, List<String> deleted) {
        FileIO io = proxy(FileIO.class, table.io(), (name, args) -> {
            if (name.equals("deleteFile") && args[0] instanceof String path) {
                deleted.add(path);
                if (deleteFailure != null) {
                    throw deleteFailure;
                }
            }
            return null;
        });
        return proxy(Table.class, table, (name, args) -> switch (name) {
            case "io" -> io;
            case "newAppend" -> proxy(AppendFiles.class, table.newAppend(), (n, a) -> {
                if (n.equals("commit")) {
                    throw commitFailure;
                }
                return null;
            });
            default -> null;
        });
    }

    /** The table with a commit that lands and then throws {@code failure}, as a catalog that loses its answer does. */
    static Table landedThenFailed(Table table, RuntimeException failure) {
        return proxy(Table.class, table, (name, args) -> {
            if (!name.equals("newAppend")) {
                return null;
            }
            AppendFiles append = table.newAppend();
            return proxy(AppendFiles.class, append, (n, a) -> {
                if (n.equals("commit")) {
                    append.commit();
                    throw failure;
                }
                return null;
            });
        });
    }

    private List<Path> dataFiles() throws IOException {
        try (Stream<Path> files = Files.walk(dir.resolve("warehouse"))) {
            return files.filter(f -> f.toString().endsWith(".parquet")).toList();
        }
    }

    private void append(Table table) {
        var episode = Episodes.regular(SeriesKeys.of("synthetic.temperature", Map.of(), "Cel"),
                0, 1_000_000, new double[]{1, 2, 3}).source("synthetic").build();
        IcebergIO.append(table, List.of(EpisodeRows.toRow(episode)));
    }

    @Test
    void aFailedCommitAbortsTheWriterAndRemovesItsDataFiles() throws IOException {
        try (IcebergSeriesStore store = IcebergSeriesStore.open(IcebergSeriesStoreTest.catalogProps(dir), "tsx")) {
            List<String> deleted = new CopyOnWriteArrayList<>();
            CommitFailedException commitFailure = new CommitFailedException("commit refused");
            Table table = failingCommit(store.episodesTable(), commitFailure, null, deleted);

            assertSame(commitFailure, assertThrows(CommitFailedException.class, () -> append(table)));
            assertEquals(1, deleted.size(), "the writer was aborted");
            assertEquals(List.of(), dataFiles());
        }
    }

    @Test
    void aFailedAbortIsKeptAsSuppressedOnTheCommitFailure() {
        try (IcebergSeriesStore store = IcebergSeriesStore.open(IcebergSeriesStoreTest.catalogProps(dir), "tsx")) {
            UncheckedIOException deleteFailure = new UncheckedIOException(new IOException("delete refused"));
            CommitFailedException commitFailure = new CommitFailedException("commit refused");
            Table table = failingCommit(store.episodesTable(), commitFailure, deleteFailure, new CopyOnWriteArrayList<>());

            CommitFailedException thrown = assertThrows(CommitFailedException.class, () -> append(table));
            assertSame(commitFailure, thrown);
            assertArrayEquals(new Throwable[]{deleteFailure}, thrown.getSuppressed());
        }
    }

    @Test
    void aCommitInAnUnknownStateKeepsTheDataFiles() throws IOException {
        try (IcebergSeriesStore store = IcebergSeriesStore.open(IcebergSeriesStoreTest.catalogProps(dir), "tsx")) {
            List<String> deleted = new CopyOnWriteArrayList<>();
            CommitStateUnknownException unknown = new CommitStateUnknownException(new IllegalStateException("no answer"));
            Table table = failingCommit(store.episodesTable(), unknown, null, deleted);

            assertSame(unknown, assertThrows(CommitStateUnknownException.class, () -> append(table)));
            assertEquals(List.of(), deleted, "a commit that may have succeeded must keep its files");
            assertFalse(dataFiles().isEmpty());
        }
    }

    @Test
    void aCommitFailureThatIcebergDoesNotCleanUpKeepsTheDataFiles() throws IOException {
        try (IcebergSeriesStore store = IcebergSeriesStore.open(IcebergSeriesStoreTest.catalogProps(dir), "tsx")) {
            List<String> deleted = new CopyOnWriteArrayList<>();
            IllegalStateException lost = new IllegalStateException("connection lost after the update");
            Table table = failingCommit(store.episodesTable(), lost, null, deleted);

            assertSame(lost, assertThrows(IllegalStateException.class, () -> append(table)));
            assertEquals(List.of(), deleted, "a commit failure that is no CleanableFailure may have committed the files");
            assertFalse(dataFiles().isEmpty());
        }
    }

    @Test
    void aCommitThatLandedBeforeItFailedKeepsTheTableReadable() {
        try (IcebergSeriesStore store = IcebergSeriesStore.open(IcebergSeriesStoreTest.catalogProps(dir), "tsx")) {
            UncheckedSQLException lostAnswer = new UncheckedSQLException(new SQLTimeoutException("no answer"),
                    "Database Connection timeout");
            Table table = landedThenFailed(store.episodesTable(), lostAnswer);

            assertSame(lostAnswer, assertThrows(UncheckedSQLException.class, () -> append(table)));
            assertEquals(1, store.read(EpisodeQuery.of(SeriesSelector.all(), 0, 3_000_000)).size());
        }
    }
}
