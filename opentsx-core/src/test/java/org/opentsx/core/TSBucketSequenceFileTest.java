package org.opentsx.core;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.BucketLoader;
import org.opentsx.tsbucket.folderStore.TSBASE;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TSBucketSequenceFileTest {

    @TempDir
    Path directory;
    String originalBase;
    boolean originalLabels;

    @BeforeEach
    void configureLocalStorage() {
        originalBase = TSBASE.BASE_PATH;
        originalLabels = TSBucket.processSeriesLabelOnLoad;
        TSBASE.BASE_PATH = directory.toString();
        TSBucket.processSeriesLabelOnLoad = false;
    }

    @AfterEach
    void restoreStorage() {
        TSBASE.BASE_PATH = originalBase;
        TSBucket.processSeriesLabelOnLoad = originalLabels;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savedSeriesCanBeLoadedScaledSavedAndReloaded(boolean fileOverload) throws Exception {
        Vector<TimeSeriesObject> original = rows();
        Path input = save("input", original);
        TSBucket loaded = load(input, fileOverload);
        assertEquals(3, loaded.bucketData.size());
        Vector<TimeSeriesObject> scaled = new Vector<>();
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).yValues, loaded.bucketData.get(i).yValues);
            scaled.add(loaded.bucketData.get(i).scaleY_2(2.0));
        }
        TSBucket reloaded = load(save("scaled", scaled), fileOverload);
        assertEquals(3, reloaded.bucketData.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(scaled.get(i).yValues, reloaded.bucketData.get(i).yValues);
            assertEquals(scaled.get(i).xValues, reloaded.bucketData.get(i).xValues);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyBucketLoadsWithoutInventingARecord(boolean fileOverload) throws Exception {
        assertTrue(load(save("empty", new Vector<>()), fileOverload).bucketData.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void fileLoaderHonorsExactRecordLimit(int limit) throws Exception {
        TSBucket bucket = bucket();
        bucket.setLimitTo(limit);
        bucket.loadFromSequenceFile(save("limited", rows()).toFile());
        assertEquals(limit, bucket.bucketData.size());
    }

    @Test
    void operationFailureIsPropagatedWithItsCause() throws Exception {
        IOException failure = new IOException("Fixture operation failed");
        SingleRowTSO operation = new SingleRowTSO() {
            @Override
            public TimeSeriesObject processReihe(Writer output, TimeSeriesObject row, Writer exploded)
                    throws Exception {
                throw failure;
            }
        };
        IOException thrown = assertThrows(IOException.class,
                () -> bucket()._loadAndProcess(save("failure", rows()).toString(), operation, null));
        assertSame(failure, thrown.getCause());
    }

    private Vector<TimeSeriesObject> rows() {
        Vector<TimeSeriesObject> rows = new Vector<>();
        for (int i = 0; i < 3; i++) {
            TimeSeriesObject row = new TimeSeriesObject();
            row.setLabel("series_" + i);
            row.addValue(i + 1.0);
            row.addValue(i + 2.0);
            row.addValue(i + 3.0);
            rows.add(row);
        }
        return rows;
    }

    private TSBucket bucket() {
        Configuration configuration = new Configuration(false);
        configuration.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
                CommonConfigurationKeysPublic.FS_DEFAULT_NAME_DEFAULT);
        TSBucket bucket = new TSBucket(configuration);
        bucket.setInMemory(true);
        return bucket;
    }

    private Path save(String label, Vector<TimeSeriesObject> rows) throws IOException {
        bucket().createBucketFromVectorOfSeries("test", label, rows);
        return directory.resolve("test").resolve(label + ".tsb.vec.seq");
    }

    private TSBucket load(Path path, boolean fileOverload) throws IOException {
        if (fileOverload) {
            TSBucket bucket = bucket();
            bucket.loadFromSequenceFile(path.toFile());
            return bucket;
        }
        return new BucketLoader().loadBucket(path.toUri().toString());
    }
}
