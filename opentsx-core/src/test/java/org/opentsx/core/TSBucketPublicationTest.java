package org.opentsx.core;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.util.Progressable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.folderStore.TSBASE;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TSBucketPublicationTest {

    @TempDir
    java.nio.file.Path directory;
    String originalBase;
    static Faults faults;

    @BeforeEach
    void configureStorage() {
        originalBase = TSBASE.BASE_PATH;
        TSBASE.BASE_PATH = directory.toString();
        faults = new Faults();
    }

    @AfterEach
    void restoreStorage() throws IOException {
        TSBASE.BASE_PATH = originalBase;
        for (OutputStream stream : faults.opened) {
            stream.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void preparationFailuresPreserveExistingDataAndChecksumAndCloseTheStream(Phase phase) throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        byte[] checksum = Files.readAllBytes(checksum());
        faults.phase = phase;
        IOException thrown = assertThrows(IOException.class,
                () -> save(configuration(FaultFileSystem.class), armedRows()));
        assertSame(faults.ioFailure, thrown);
        assertEquals(1, faults.closes);
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertArrayEquals(checksum, Files.readAllBytes(checksum()));
        assertNoStagingFiles();
        assertRows(1.0);
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void preparationFailuresDoNotPublishANewBucket(Phase phase) throws Exception {
        faults.phase = phase;
        assertSame(faults.ioFailure, assertThrows(IOException.class,
                () -> save(configuration(FaultFileSystem.class), armedRows())));
        assertEquals(1, faults.closes);
        assertFalse(Files.exists(target()));
        assertFalse(Files.exists(checksum()));
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rowFailuresRetainTheirIdentityAndSuppressCloseFailures(boolean failClose) throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        byte[] checksum = Files.readAllBytes(checksum());
        faults.failClose = failClose;
        RuntimeException failure = new IllegalStateException("Fixture row failed");
        TimeSeriesObject row = new TimeSeriesObject("Failing row") {
            @Override
            public double[] getYData() {
                throw failure;
            }
        };
        Vector<TimeSeriesObject> rows = rows(2.0);
        rows.add(row);
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> save(configuration(FaultFileSystem.class), rows)));
        assertEquals(1, faults.closes);
        assertArrayEquals(failClose ? new Throwable[]{faults.ioFailure} : new Throwable[0],
                failure.getSuppressed());
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertArrayEquals(checksum, Files.readAllBytes(checksum()));
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void flushFailuresKeepTheirIdentityWhenClosingAlsoFails(boolean sameFailure) throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        byte[] checksum = Files.readAllBytes(checksum());
        faults.phase = Phase.FLUSH;
        faults.failClose = true;
        if (!sameFailure) {
            faults.closeFailure = new IOException("Fixture close failed");
        }
        IOException thrown = assertThrows(IOException.class,
                () -> save(configuration(FaultFileSystem.class), armedRows()));
        assertSame(faults.ioFailure, thrown);
        assertArrayEquals(sameFailure ? new Throwable[0] : new Throwable[]{faults.closeFailure},
                thrown.getSuppressed());
        assertEquals(1, faults.closes);
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertArrayEquals(checksum, Files.readAllBytes(checksum()));
        assertNoStagingFiles();
    }

    @Test
    void serializerInitializationFailureClosesTheAlreadyOpenedStream() throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        byte[] checksum = Files.readAllBytes(checksum());
        Configuration configuration = configuration(FaultFileSystem.class);
        configuration.set("io.serializations", "");
        assertThrows(IOException.class, () -> save(configuration, rows(2.0)));
        assertEquals(1, faults.closes);
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertArrayEquals(checksum, Files.readAllBytes(checksum()));
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupFailuresAreSuppressedBehindTheOriginalWriteFailure(boolean throwOnDelete) throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        faults.phase = Phase.WRITE;
        faults.failDelete = true;
        faults.throwOnDelete = throwOnDelete;
        IOException thrown = assertThrows(IOException.class,
                () -> save(configuration(FaultFileSystem.class), armedRows()));
        assertSame(faults.ioFailure, thrown);
        assertEquals(1, faults.closes);
        assertEquals(1, thrown.getSuppressed().length);
        if (throwOnDelete) {
            assertSame(faults.cleanupFailure, thrown.getSuppressed()[0]);
        } else {
            assertInstanceOf(IOException.class, thrown.getSuppressed()[0]);
        }
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertEquals(1, stagingFiles().size());
    }

    @Test
    void falseFolderCreationFailsBeforeOpeningAnOutputStream() {
        faults.failMkdirs = true;
        assertThrows(IOException.class,
                () -> save(configuration(FaultFileSystem.class), rows(2.0)));
        assertEquals(0, faults.creates);
        assertFalse(Files.exists(target()));
    }

    @Test
    void unsupportedFilesystemCannotOverwriteAnExistingBucket() throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        byte[] checksum = Files.readAllBytes(checksum());
        IOException failure = assertThrows(IOException.class,
                () -> save(configuration(UnsupportedFileSystem.class), rows(2.0)));
        assertTrue(failure.getMessage().contains("not supported"));
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertArrayEquals(checksum, Files.readAllBytes(checksum()));
        assertNoStagingFiles();
    }

    @Test
    void checksumPublicationFailureIdentifiesTheSavedDataAndFailedDestination() throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] originalChecksum = Files.readAllBytes(checksum());
        faults.blockChecksum = checksum();
        IOException failure = assertThrows(IOException.class,
                () -> save(configuration(FaultFileSystem.class), rows(2.0)));
        assertTrue(failure.getMessage().contains("saved"));
        assertTrue(failure.getMessage().contains(target().toString()));
        assertTrue(failure.getMessage().contains(checksum().toString()));
        assertNotNull(failure.getCause());
        assertTrue(Files.size(target()) > 0);
        assertArrayEquals(originalChecksum, Files.readAllBytes(checksum().resolve("marker")));
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulPublicationReportsAStagingCleanupFailure(boolean throwOnDelete) throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        faults.failDelete = true;
        faults.throwOnDelete = throwOnDelete;
        IOException failure = assertThrows(IOException.class,
                () -> save(configuration(FaultFileSystem.class), rows(2.0)));
        assertTrue(failure.getMessage().contains("saved"));
        assertTrue(failure.getMessage().contains(target().toString()));
        if (throwOnDelete) {
            assertSame(faults.cleanupFailure, failure.getCause());
        }
        assertEquals(1, failure.getSuppressed().length);
        assertRows(2.0);
        assertEquals(1, stagingFiles().size());
    }

    @Test
    void replacementRetainsExistingAccessPermissions() throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        PosixFileAttributeView attributes = Files.getFileAttributeView(target(), PosixFileAttributeView.class);
        if (attributes != null) {
            var expected = PosixFilePermissions.fromString("rw-r-----");
            attributes.setPermissions(expected);
            save(configuration(FaultFileSystem.class), rows(2.0));
            assertEquals(expected, Files.getPosixFilePermissions(target()));
        } else {
            save(configuration(FaultFileSystem.class), rows(2.0));
            assertTrue(Files.isReadable(target()));
        }
        assertRows(2.0);
    }

    @Test
    void nestedLabelsKeepTheirExistingFilesystemMeaning() throws Exception {
        new TSBucket(configuration(LocalFileSystem.class))
                .createBucketFromVectorOfSeries("track", "nested/bucket", rows(2.0));
        java.nio.file.Path file = directory.resolve("track/nested/bucket.tsb.vec.seq");
        TSBucket loaded = new TSBucket(configuration(LocalFileSystem.class));
        loaded.setInMemory(true);
        loaded.loadFromSequenceFile(file.toFile());
        assertEquals(List.of(2.0, 3.0), loaded.bucketData.get(0).yValues);
        try (var paths = Files.list(file.getParent())) {
            assertEquals(List.of(".bucket.tsb.vec.seq.crc", "bucket.tsb.vec.seq"),
                    paths.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localPublicationCanCreateAndReplaceAReadableBucket(boolean existing) throws Exception {
        if (existing) {
            save(configuration(LocalFileSystem.class), rows(1.0));
        }
        save(configuration(FaultFileSystem.class), rows(2.0));
        assertRows(2.0);
        assertEquals(1, faults.closes);
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawLocalPublicationCanCreateAndReplaceAReadableBucket(boolean existing) throws Exception {
        if (existing) {
            save(configuration(RawLocalFileSystem.class), rows(1.0));
        }
        save(configuration(RawLocalFileSystem.class), rows(2.0));
        assertRows(2.0);
        assertFalse(Files.exists(checksum()));
        assertNoStagingFiles();
    }

    @Test
    void rawLocalPublicationRefusesToLeaveAnOldChecksumBesideNewData() throws Exception {
        save(configuration(LocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        byte[] checksum = Files.readAllBytes(checksum());
        assertThrows(IOException.class,
                () -> save(configuration(RawLocalFileSystem.class), rows(2.0)));
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertArrayEquals(checksum, Files.readAllBytes(checksum()));
        assertNoStagingFiles();
    }

    @Test
    void distributedPublicationUsesTheAtomicOverwriteApiWithoutDeletingTheTarget() throws Exception {
        save(configuration(RawLocalFileSystem.class), rows(1.0));
        Configuration configuration = configuration(AtomicDistributedFileSystem.class);
        configuration.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, "hdfs://fixture/");
        configuration.setClass("fs.hdfs.impl", AtomicDistributedFileSystem.class, FileSystem.class);
        configuration.setBoolean("fs.hdfs.impl.disable.cache", true);
        save(configuration, rows(2.0));
        assertEquals(List.of(Options.Rename.OVERWRITE), faults.renameOptions);
        assertEquals(0, faults.targetDeletes);
        assertRows(2.0);
        assertNoStagingFiles();
    }

    @Test
    void distributedPublicationFailurePreservesTheOldDataAndCleansStaging() throws Exception {
        save(configuration(RawLocalFileSystem.class), rows(1.0));
        byte[] original = Files.readAllBytes(target());
        faults.failRename = true;
        Configuration configuration = configuration(AtomicDistributedFileSystem.class);
        configuration.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, "hdfs://fixture/");
        configuration.setClass("fs.hdfs.impl", AtomicDistributedFileSystem.class, FileSystem.class);
        configuration.setBoolean("fs.hdfs.impl.disable.cache", true);
        assertSame(faults.ioFailure, assertThrows(IOException.class,
                () -> save(configuration, rows(2.0))));
        assertArrayEquals(original, Files.readAllBytes(target()));
        assertEquals(List.of(Options.Rename.OVERWRITE), faults.renameOptions);
        assertEquals(0, faults.targetDeletes);
        assertNoStagingFiles();
    }

    private Configuration configuration(Class<? extends FileSystem> implementation) {
        Configuration configuration = new Configuration(false);
        configuration.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, "file:///");
        configuration.setClass("fs.file.impl", implementation, FileSystem.class);
        configuration.setBoolean("fs.file.impl.disable.cache", true);
        return configuration;
    }

    private Vector<TimeSeriesObject> armedRows() {
        TimeSeriesObject row = new TimeSeriesObject("Arming row") {
            @Override
            public double[] getYData() {
                faults.armed = true;
                return new double[]{2.0, 3.0};
            }
        };
        return new Vector<>(List.of(row));
    }

    private Vector<TimeSeriesObject> rows(double first) {
        TimeSeriesObject row = new TimeSeriesObject("Series \u0394");
        row.addValue(first);
        row.addValue(first + 1.0);
        return new Vector<>(List.of(row));
    }

    private void save(Configuration configuration, Vector<TimeSeriesObject> rows) throws IOException {
        new TSBucket(configuration).createBucketFromVectorOfSeries("track", "bucket", rows);
    }

    private java.nio.file.Path target() {
        return directory.resolve("track/bucket.tsb.vec.seq");
    }

    private java.nio.file.Path checksum() {
        return directory.resolve("track/.bucket.tsb.vec.seq.crc");
    }

    private void assertRows(double first) throws IOException {
        TSBucket bucket = new TSBucket(configuration(LocalFileSystem.class));
        bucket.setInMemory(true);
        bucket.loadFromSequenceFile(target().toFile());
        assertEquals(1, bucket.bucketData.size());
        assertEquals(List.of(first, first + 1.0), bucket.bucketData.get(0).yValues);
    }

    private List<String> stagingFiles() throws IOException {
        java.nio.file.Path track = directory.resolve("track");
        if (!Files.exists(track)) {
            return List.of();
        }
        try (var paths = Files.list(track)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("bucket.tsb.vec.seq")
                            && !name.equals(".bucket.tsb.vec.seq.crc"))
                    .sorted().toList();
        }
    }

    private void assertNoStagingFiles() throws IOException {
        assertEquals(List.of(), stagingFiles());
    }

    enum Phase { HEADER, WRITE, FLUSH, CLOSE }

    static final class Faults {
        Phase phase;
        boolean armed;
        boolean failClose;
        boolean failDelete;
        boolean throwOnDelete;
        boolean failMkdirs;
        boolean failRename;
        java.nio.file.Path blockChecksum;
        int creates;
        int closes;
        int targetDeletes;
        List<Options.Rename> renameOptions;
        final List<OutputStream> opened = new ArrayList<>();
        final IOException ioFailure = new IOException("Fixture output failed");
        IOException closeFailure = ioFailure;
        final IOException cleanupFailure = new IOException("Fixture cleanup failed");
    }

    public static class FaultFileSystem extends LocalFileSystem {
        @Override
        public FSDataOutputStream create(Path path, FsPermission permission, boolean overwrite,
                                         int bufferSize, short replication, long blockSize,
                                         Progressable progress) throws IOException {
            faults.creates++;
            FSDataOutputStream output = super.create(path, permission, overwrite, bufferSize,
                    replication, blockSize, progress);
            faults.opened.add(output);
            return new FSDataOutputStream(new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    checkWrite();
                    output.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    checkWrite();
                    output.write(bytes, offset, length);
                }

                private void checkWrite() throws IOException {
                    if (faults.phase == Phase.HEADER || faults.phase == Phase.WRITE && faults.armed) {
                        throw faults.ioFailure;
                    }
                }

                @Override
                public void flush() throws IOException {
                    if (faults.phase == Phase.FLUSH && faults.armed) {
                        throw faults.ioFailure;
                    }
                    output.flush();
                }

                @Override
                public void close() throws IOException {
                    faults.closes++;
                    output.close();
                    if (faults.phase == Phase.CLOSE) {
                        throw faults.ioFailure;
                    }
                    if (faults.failClose) {
                        throw faults.closeFailure;
                    }
                    if (faults.blockChecksum != null) {
                        java.nio.file.Path savedChecksum = faults.blockChecksum.resolveSibling("saved-checksum");
                        Files.move(faults.blockChecksum, savedChecksum);
                        Files.createDirectory(faults.blockChecksum);
                        Files.move(savedChecksum, faults.blockChecksum.resolve("marker"));
                    }
                }
            }, null);
        }

        @Override
        public boolean mkdirs(Path path) throws IOException {
            return !faults.failMkdirs && super.mkdirs(path);
        }

        @Override
        public boolean delete(Path path, boolean recursive) throws IOException {
            if (faults.failDelete && path.getName().startsWith(".opentsx-bucket-")) {
                if (faults.throwOnDelete) {
                    throw faults.cleanupFailure;
                }
                return false;
            }
            return super.delete(path, recursive);
        }
    }

    public static class UnsupportedFileSystem extends FilterFileSystem {
        public UnsupportedFileSystem() {
            super(new LocalFileSystem());
        }
    }

    public static class AtomicDistributedFileSystem extends DistributedFileSystem {
        final RawLocalFileSystem local = new RawLocalFileSystem();

        @Override
        public void initialize(URI uri, Configuration configuration) throws IOException {
            setConf(configuration);
            local.initialize(URI.create("file:///"), configuration);
        }

        @Override
        public URI getUri() {
            return URI.create("hdfs://fixture/");
        }

        @Override
        public short getDefaultReplication() {
            return 1;
        }

        @Override
        public long getDefaultBlockSize() {
            return 64 * 1024 * 1024;
        }

        @Override
        public FSDataOutputStream create(Path path, FsPermission permission, boolean overwrite,
                                         int bufferSize, short replication, long blockSize,
                                         Progressable progress) throws IOException {
            return local.create(path, permission, overwrite, bufferSize, replication, blockSize, progress);
        }

        @Override
        public boolean mkdirs(Path path) throws IOException {
            return local.mkdirs(path);
        }

        @Override
        public boolean exists(Path path) throws IOException {
            return local.exists(path);
        }

        @Override
        public FileStatus getFileStatus(Path path) throws IOException {
            return local.getFileStatus(path);
        }

        @Override
        public void setPermission(Path path, FsPermission permission) throws IOException {
            local.setPermission(path, permission);
        }

        @Override
        public boolean delete(Path path, boolean recursive) throws IOException {
            if (path.getName().equals("bucket.tsb.vec.seq")) {
                faults.targetDeletes++;
            }
            return local.delete(path, recursive);
        }

        @Override
        public void rename(Path source, Path target, Options.Rename... options) throws IOException {
            faults.renameOptions = Arrays.asList(options);
            if (faults.failRename) {
                throw faults.ioFailure;
            }
            Files.move(local.pathToFile(source).toPath(), local.pathToFile(target).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
