package org.opentsx.core;

import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TSBucketUnsupportedOperationsTest {

    @TempDir
    Path directory;

    @Test
    void existingInputDoesNotProduceAnEmptySuccess() throws Exception {
        Path file = directory.resolve("input.tsb.vec.seq");
        Files.writeString(file, "Input must remain untouched");

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> TSBucket.loadViaContext(new Object(), file.toString()));

        assertEquals("Context-based bucket loading is not implemented", error.getMessage());
        assertEquals("Input must remain untouched", Files.readString(file));
    }

    @Test
    void missingInputDoesNotProduceAnEmptySuccess() {
        Path file = directory.resolve("missing.tsb.vec.seq");

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> TSBucket.loadViaContext(null, file.toString()));

        assertEquals("Context-based bucket loading is not implemented", error.getMessage());
        assertFalse(Files.exists(file));
    }

    @Test
    void namedProcessingFailsWithoutChangingTheSourceBucket() {
        TSBucket bucket = new TSBucket(new Configuration(false));
        TimeSeriesObject row = new TimeSeriesObject("Measured signal");
        row.addValuePair(0.25, 2.0);
        row.addValuePair(0.5, 4.0);
        bucket.bucketData.add(row);

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> bucket.processBucket("CACHE", null));

        assertEquals("Named bucket processing is not implemented", error.getMessage());
        assertEquals(1, bucket.bucketData.size());
        assertSame(row, bucket.bucketData.firstElement());
        assertEquals(2.0, ((Number) row.yValues.get(0)).doubleValue());
        assertEquals(4.0, ((Number) row.yValues.get(1)).doubleValue());
        assertFalse(bucket.isProcessed);
    }

    @Test
    void unknownOperationsFailWithoutInspectingTheirParameters() {
        TSBucket bucket = new TSBucket(new Configuration(false));
        Object parameter = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("Unsupported processing must not inspect the parameter");
            }
        };

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> bucket.processBucket("UNKNOWN_OPERATION", parameter));

        assertEquals("Named bucket processing is not implemented", error.getMessage());
    }
}
