package org.opentsx.core;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.BucketLoader;
import org.opentsx.tsbucket.folderStore.TSBASE;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TSBucketSelectionTest {

    private static final List<String> LABELS = List.of("3", "alpha", "3", "2", "omega");
    private static final List<Integer> ALL = List.of(1, 2, 3, 4, 5);

    @TempDir
    Path directory;

    String originalBase;
    String originalMode;
    String originalExtension;
    boolean originalLabels;
    boolean originalInMemory;
    boolean originalHdfs;
    int originalCounter;
    int originalLoaderLimit;
    int originalBucketLimit;
    Path input;
    Path other;

    @BeforeEach
    void createLocalBucketsAndConfigureSelection() throws Exception {
        originalBase = TSBASE.BASE_PATH;
        originalMode = TSBucket.recoderIdMode;
        originalExtension = TSBucket.FN_EXT;
        originalLabels = TSBucket.processSeriesLabelOnLoad;
        originalInMemory = BucketLoader.default_INMEM;
        originalHdfs = TSBucket.useHDFS;
        originalCounter = TSBucket.counter;
        originalLoaderLimit = BucketLoader.default_LIMIT;
        originalBucketLimit = TSBucket.default_LIMIT;
        TSBASE.BASE_PATH = directory.toString();
        TSBucket.recoderIdMode = "page-id";
        TSBucket.counter = 100;
        TSBucket.FN_EXT = "selection";
        TSBucket.processSeriesLabelOnLoad = false;
        TSBucket.useHDFS = false;
        BucketLoader.default_INMEM = true;
        BucketLoader.default_LIMIT = 5;
        TSBucket.default_LIMIT = 5;
        input = save("input", 10);
        other = save("other", 100);
    }

    @AfterEach
    void restoreStorageAndLoaderSettings() {
        TSBASE.BASE_PATH = originalBase;
        TSBucket.recoderIdMode = originalMode;
        TSBucket.FN_EXT = originalExtension;
        TSBucket.processSeriesLabelOnLoad = originalLabels;
        BucketLoader.default_INMEM = originalInMemory;
        TSBucket.useHDFS = originalHdfs;
        TSBucket.counter = originalCounter;
        BucketLoader.default_LIMIT = originalLoaderLimit;
        TSBucket.default_LIMIT = originalBucketLimit;
    }

    @ParameterizedTest
    @CsvSource({"false,all", "true,all", "false,empty", "true,empty", "false,reordered", "true,reordered",
            "false,duplicates", "true,duplicates", "false,unmatched", "true,unmatched", "false,single", "true,single"})
    void selectsOneBasedPhysicalPositionsIndependentlyOfLabels(boolean numberedLabels, String selection) throws Exception {
        TSBucket.processSeriesLabelOnLoad = numberedLabels;
        Vector<Integer> ids = switch (selection) {
            case "all" -> null;
            case "empty" -> ids();
            case "reordered" -> ids(5, 2, 4);
            case "duplicates" -> ids(3, 1, 3);
            case "unmatched" -> ids(99);
            case "single" -> ids(4);
            default -> throw new IllegalArgumentException(selection);
        };
        List<Integer> expected = switch (selection) {
            case "all" -> ALL;
            case "empty", "unmatched" -> List.of();
            case "reordered" -> List.of(2, 4, 5);
            case "duplicates" -> List.of(1, 3);
            case "single" -> List.of(4);
            default -> throw new IllegalArgumentException(selection);
        };
        TSBucket bucket = bucket(true);
        bucket.loadFromSequenceFile(input.toString(), ids, null);
        assertRows(bucket.bucketData, expected, numberedLabels, 10);
        assertEquals("counter", TSBucket.recoderIdMode);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void recordLimitCapsScannedPositionsRatherThanSelectedMatches(int limit) throws Exception {
        TSBucket.processSeriesLabelOnLoad = true;
        TSBucket bucket = bucket(true);
        bucket.setLimitTo(limit);
        bucket.loadFromSequenceFile(input.toString(), ids(2, 4, 5), null);
        List<Integer> expected = switch (limit) {
            case 0, 1 -> List.of();
            case 2, 3 -> List.of(2);
            case 4 -> List.of(2, 4);
            case 5 -> List.of(2, 4, 5);
            default -> throw new IllegalArgumentException(Integer.toString(limit));
        };
        assertRows(bucket.bucketData, expected, true, 10);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlySelectedRowsReachTheCallbackWithOrWithoutMemoryRetention(boolean inMemory) throws Exception {
        TSBucket.processSeriesLabelOnLoad = true;
        List<TimeSeriesObject> seen = new ArrayList<>();
        TSBucket bucket = bucket(inMemory);
        bucket.loadFromSequenceFile(input.toString(), ids(4, 2), observer(seen));
        assertRows(seen, List.of(2, 4), true, 10);
        assertRows(bucket.bucketData, inMemory ? List.of(2, 4) : List.of(), true, 10);
        assertTrue(bucket.isProcessed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processingWritesOnlySelectedRowsToTheRealCsvOutput(boolean numberedLabels) throws Exception {
        TSBucket.processSeriesLabelOnLoad = numberedLabels;
        TSBucket bucket = bucket(false);
        bucket._loadAndProcess(input.toString(), new SingleRowTSO(), ids(4, 2));
        assertEquals(numberedLabels ? List.of("2\t41.0", "4\t81.0") : List.of("alpha\t41.0", "2\t81.0"),
                Files.readAllLines(csv(input)));
        assertEquals("", Files.readString(exploded(input)));
        assertTrue(bucket.bucketData.isEmpty());
    }

    @Test
    void anEmptySelectionProducesEmptyCsvFiles() throws Exception {
        TSBucket bucket = bucket(false);
        bucket._loadAndProcess(input.toString(), new SingleRowTSO(), ids());
        assertEquals("", Files.readString(csv(input)));
        assertEquals("", Files.readString(exploded(input)));
        assertTrue(bucket.bucketData.isEmpty());
    }

    @Test
    void aLoaderReusesItsStoredSelectionForAnotherLoad() throws Exception {
        BucketLoader loader = new BucketLoader();
        loader.loadBucket(input.toString(), ids(3));
        assertRows(loader.getBucketData(), List.of(3), false, 10);
        TSBucket next = loader.loadBucket(other.toString());
        assertRows(next.bucketData, List.of(3), false, 100);
        assertEquals(ids(3), loader.getIds());
    }

    @Test
    void loadBucketDataBypassesFilteringAndPreservesTheStoredSelection() throws Exception {
        BucketLoader loader = new BucketLoader();
        loader.loadBucket(input.toString(), ids(4));
        assertRows(loader.getBucketData(), List.of(4), false, 10);
        loader.loadBucketData(other.toString());
        assertRows(loader.getBucketData(), ALL, false, 100);
        assertEquals(ids(4), loader.getIds());
    }

    @Test
    void anExplicitNullLoadSelectionClearsThePreviousSelection() throws Exception {
        BucketLoader loader = new BucketLoader();
        loader.loadBucket(input.toString(), ids(4));
        loader.loadBucket(other.toString(), null);
        assertNull(loader.getIds());
        assertRows(loader.getBucketData(), ALL, false, 100);
    }

    @Test
    void processingReusesItsStoredSelectionForAnotherBucket() throws Exception {
        BucketLoader loader = new BucketLoader();
        loader._processBucket(input.toString(), new SingleRowTSO(), ids(2, 5));
        List<TimeSeriesObject> seen = new ArrayList<>();
        TSBucket result = loader._processBucket(other.toString(), observer(seen));
        assertRows(seen, List.of(2, 5), false, 100);
        assertEquals(ids(2, 5), loader.getIds());
        assertTrue(result.isProcessed);
    }

    @Test
    void anExplicitNullProcessingSelectionClearsThePreviousSelection() throws Exception {
        BucketLoader loader = new BucketLoader();
        loader._processBucket(input.toString(), new SingleRowTSO(), ids(2));
        List<TimeSeriesObject> seen = new ArrayList<>();
        loader._processBucket(other.toString(), observer(seen), null);
        assertNull(loader.getIds());
        assertRows(seen, ALL, false, 100);
    }

    @Test
    void explicitNullProcessingAlsoClearsASelectionStoredByLoading() throws Exception {
        BucketLoader loader = new BucketLoader();
        loader.loadBucket(input.toString(), ids(1));
        List<TimeSeriesObject> seen = new ArrayList<>();
        loader._processBucket(other.toString(), observer(seen), null);
        assertNull(loader.getIds());
        assertRows(seen, ALL, false, 100);
    }

    @Test
    void explicitNullLoadingAlsoClearsASelectionStoredByProcessing() throws Exception {
        BucketLoader loader = new BucketLoader();
        loader._processBucket(input.toString(), new SingleRowTSO(), ids(5));
        loader.loadBucket(other.toString(), null);
        assertNull(loader.getIds());
        assertRows(loader.getBucketData(), ALL, false, 100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"load", "data", "process"})
    void everyLoaderEntryPointKeepsItsSelectionPolicyWithinTheScanLimit(String entryPoint) throws Exception {
        BucketLoader loader = new BucketLoader();
        loader.loadBucket(input.toString(), ids(4, 5));
        loader.setLimit(3);
        List<TimeSeriesObject> seen = new ArrayList<>();
        switch (entryPoint) {
            case "load" -> loader.loadBucket(other.toString());
            case "data" -> loader.loadBucketData(other.toString());
            case "process" -> loader._processBucket(other.toString(), observer(seen));
            default -> throw new IllegalArgumentException(entryPoint);
        }
        assertEquals(3, loader.getTSBucket().LIMIT);
        if (entryPoint.equals("process")) {
            assertTrue(seen.isEmpty());
            assertEquals("", Files.readString(csv(other)));
        } else if (entryPoint.equals("data")) {
            assertRows(loader.getBucketData(), List.of(1, 2, 3), false, 100);
        } else {
            assertTrue(loader.getBucketData().isEmpty());
        }
        assertEquals(ids(4, 5), loader.getIds());
    }

    private TSBucket bucket(boolean inMemory) {
        Configuration configuration = new Configuration(false);
        configuration.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
                CommonConfigurationKeysPublic.FS_DEFAULT_NAME_DEFAULT);
        TSBucket bucket = new TSBucket(configuration);
        bucket.setInMemory(inMemory);
        return bucket;
    }

    private Path save(String name, int scale) throws IOException {
        Vector<TimeSeriesObject> rows = new Vector<>();
        for (int position = 1; position <= LABELS.size(); position++) {
            TimeSeriesObject row = new TimeSeriesObject(LABELS.get(position - 1));
            row.addValue(position * (double) scale);
            row.addValue(position * (double) scale + 1);
            rows.add(row);
        }
        bucket(false).createBucketFromVectorOfSeries("selection", name, rows);
        return directory.resolve("selection").resolve(name + ".tsb.vec.seq");
    }

    private void assertRows(List<TimeSeriesObject> actual, List<Integer> expected, boolean numberedLabels, int scale) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            int position = expected.get(index);
            TimeSeriesObject row = actual.get(index);
            assertEquals(numberedLabels ? Integer.toString(position) : LABELS.get(position - 1), row.getLabel());
            assertEquals(List.of(position * (double) scale, position * (double) scale + 1), row.yValues);
            assertEquals(List.of(1.0, 2.0), row.xValues);
        }
    }

    private SingleRowTSO observer(List<TimeSeriesObject> seen) {
        return new SingleRowTSO() {
            @Override
            public TimeSeriesObject processReihe(Writer output, TimeSeriesObject row, Writer exploded) {
                seen.add(row);
                return row;
            }
        };
    }

    private Vector<Integer> ids(Integer... values) {
        return new Vector<>(Arrays.asList(values));
    }

    private Path csv(Path path) {
        return Path.of(path.toString() + ".proc." + TSBucket.FN_EXT + ".csv");
    }

    private Path exploded(Path path) {
        return Path.of(path.toString() + ".proc.explode." + TSBucket.FN_EXT + ".csv");
    }
}
