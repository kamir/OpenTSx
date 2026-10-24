package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.nio.file.Files;
import org.opentsx.core.TSBucket;
import org.opentsx.tsbucket.BucketLoader;
import org.opentsx.experimental.SimpleFilteredBucketTool;

import static org.junit.jupiter.api.Assertions.*;

class BucketAnalyserToolTest {

    @TempDir
    Path directory;
    int loaderLimit;
    int bucketLimit;
    int processingMode;
    boolean hdfs;
    String extension;
    String parserFactory;

    @BeforeEach
    void saveConfiguration() {
        loaderLimit = BucketLoader.default_LIMIT;
        bucketLimit = TSBucket.default_LIMIT;
        processingMode = SimpleFilteredBucketTool.procm;
        hdfs = TSBucket.useHDFS;
        extension = TSBucket.FN_EXT;
        parserFactory = System.getProperty("javax.xml.parsers.DocumentBuilderFactory");
    }

    @AfterEach
    void restoreConfiguration() {
        BucketLoader.default_LIMIT = loaderLimit;
        TSBucket.default_LIMIT = bucketLimit;
        SimpleFilteredBucketTool.procm = processingMode;
        TSBucket.useHDFS = hdfs;
        TSBucket.FN_EXT = extension;
        if (parserFactory == null) System.clearProperty("javax.xml.parsers.DocumentBuilderFactory");
        else System.setProperty("javax.xml.parsers.DocumentBuilderFactory", parserFactory);
    }

    @Test
    void usesTheSuppliedInputFolder() {
        assertDoesNotThrow(() -> BucketAnalyserTool.main(arguments()));
    }

    @Test
    void suppliedFilterExcludesOtherBuckets() throws Exception {
        Files.writeString(directory.resolve("excluded.tsb.vec.seq"), "not a sequence file");
        String[] args = {directory.toString(), "test", "false", "false", "wanted", "0"};
        assertDoesNotThrow(() -> BucketAnalyserTool.main(args));
    }

    @Test
    void missingArgumentsHaveAUsageError() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BucketAnalyserTool.main(new String[0]));
        assertTrue(failure.getMessage().startsWith("Usage:"));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void invalidBooleanIsRejected(int index) {
        String[] args = arguments();
        args[index] = "not-a-boolean";
        assertThrows(IllegalArgumentException.class, () -> BucketAnalyserTool.main(args));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "not-a-number"})
    void invalidRecordLimitIsRejected(String limit) {
        String[] args = {directory.toString(), "test", "false", "false", "", limit};
        assertThrows(IllegalArgumentException.class, () -> BucketAnalyserTool.main(args));
    }

    @Test
    void missingInputFolderIsReported() {
        String[] args = arguments();
        args[0] = directory.resolve("missing").toString();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BucketAnalyserTool.main(args));
        assertTrue(failure.getMessage().contains(args[0]));
    }

    private String[] arguments() {
        return new String[]{directory.toString(), "test", "false", "false"};
    }
}
