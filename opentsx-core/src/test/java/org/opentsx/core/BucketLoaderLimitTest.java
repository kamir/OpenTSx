package org.opentsx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.BucketLoader;
import org.opentsx.tsbucket.folderStore.TSBASE;

import java.io.Writer;
import java.nio.file.Path;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BucketLoaderLimitTest {

    @TempDir
    Path directory;
    String originalBase;
    int originalLoaderLimit;
    int originalBucketLimit;

    @BeforeEach
    void configureStorage() {
        originalBase = TSBASE.BASE_PATH;
        originalLoaderLimit = BucketLoader.default_LIMIT;
        originalBucketLimit = TSBucket.default_LIMIT;
        TSBASE.BASE_PATH = directory.toString();
    }

    @AfterEach
    void restoreStorage() {
        TSBASE.BASE_PATH = originalBase;
        BucketLoader.default_LIMIT = originalLoaderLimit;
        TSBucket.default_LIMIT = originalBucketLimit;
    }

    @ParameterizedTest
    @CsvSource({"load,0", "load,1", "data,0", "data,1", "process,0", "process,1"})
    void configuredLimitAppliesToEveryLoadEntryPoint(String entryPoint, int limit) throws Exception {
        Vector<TimeSeriesObject> rows = new Vector<>();
        for (int i = 0; i < 3; i++) {
            TimeSeriesObject row = new TimeSeriesObject();
            row.addValue(i);
            rows.add(row);
        }
        new TSBucket().createBucketFromVectorOfSeries("test", "input", rows);
        String path = directory.resolve("test/input.tsb.vec.seq").toString();
        BucketLoader loader = new BucketLoader();
        loader.setLimit(limit);
        AtomicInteger processed = new AtomicInteger();
        switch (entryPoint) {
            case "load" -> loader.loadBucket(path);
            case "data" -> loader.loadBucketData(path);
            case "process" -> loader._processBucket(path, new SingleRowTSO() {
                @Override
                public TimeSeriesObject processReihe(Writer output, TimeSeriesObject row, Writer exploded) {
                    processed.incrementAndGet();
                    return row;
                }
            });
            default -> throw new IllegalArgumentException(entryPoint);
        }
        assertEquals(limit, entryPoint.equals("process") ? processed.get() : loader.getBucketData().size());
        assertEquals(limit, loader.getTSBucket().LIMIT);
    }
}
