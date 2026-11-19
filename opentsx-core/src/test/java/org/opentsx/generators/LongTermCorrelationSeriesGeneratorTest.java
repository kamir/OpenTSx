package org.opentsx.generators;

import org.apache.commons.math3.stat.StatUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LongTermCorrelationSeriesGeneratorTest {

    @TempDir
    Path dir;

    @Test
    void aSeriesWithoutASeedHasVarianceOne() throws Exception {
        TimeSeriesObject row = LongTermCorrelationSeriesGenerator.getRandomRow(4096, 0.5, false, false);
        assertEquals(4096, row.yValues.size());
        assertEquals(1.0, StatUtils.variance(row.getYData()), 0.05);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.4, 0.0, 0.5, 1.0})
    void theSameSeedGivesTheSameSeriesWithVarianceOne(double beta) throws Exception {
        TimeSeriesObject first = LongTermCorrelationSeriesGenerator.getRandomRow(1000, beta, new Random(7), false, false);
        TimeSeriesObject second = LongTermCorrelationSeriesGenerator.getRandomRow(1000, beta, new Random(7), false, false);
        assertEquals(1000, first.yValues.size());
        assertEquals(1.0, StatUtils.variance(first.getYData()), 0.05);
        assertEquals(first.yValues, second.yValues);
        assertInstanceOf(TimeSeriesObjectFFT.class, first);
        assertTrue(first.getLabel().startsWith("FourierFilterd (beta=" + beta + ")"), first.getLabel());
    }

    @Test
    void callsWithoutASeedGiveDifferentSeries() throws Exception {
        TimeSeriesObject first = LongTermCorrelationSeriesGenerator.getRandomRow(256, 0.5, false, false);
        TimeSeriesObject second = LongTermCorrelationSeriesGenerator.getRandomRow(256, 0.5, false, false);
        assertNotEquals(first.yValues, second.yValues);
    }

    @Test
    void anotherSeedGivesAnotherSeries() throws Exception {
        TimeSeriesObject first = LongTermCorrelationSeriesGenerator.getRandomRow(256, 0.5, new Random(7), false, false);
        TimeSeriesObject other = LongTermCorrelationSeriesGenerator.getRandomRow(256, 0.5, new Random(8), false, false);
        assertNotEquals(first.yValues, other.yValues);
    }

    @Test
    void aSingleValueCannotBeNormalized() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LongTermCorrelationSeriesGenerator.getRandomRow(1, 0.5, new Random(7), false, false));
        assertEquals("getRandomRow: the filtered series of 1 values has standard deviation 0.0; expected at least 2"
                + " values that are not all equal", e.getMessage());
    }

    @Test
    void generatingAndFilteringWriteNothingToStandardOutput() throws Exception {
        TimeSeriesObject noise = new TimeSeriesObject();
        Random random = new Random(3);
        for (int i = 0; i < 100; i++) {
            noise.addValuePair(i, random.nextGaussian());
        }
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            LongTermCorrelationSeriesGenerator.getRandomRow(100, 0.5, new Random(3), false, false);
            LongTermCorrelationSeriesGenerator.getRandomRow(1024, 0.5, new Random(3), false, true);
            LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(noise, false, true);
            TimeSeriesObjectFFT.convertToMessreiheFFT(noise).getModifiedTimeSeries_FourierFiltered(0.5);
        } finally {
            System.setOut(stdout);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theTablesAreWrittenUnderTheGivenDirectory() throws Exception {
        Path out = dir.resolve("tables");
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            LongTermCorrelationSeriesGenerator.writeTables(out.toFile(), new Random(1));
        } finally {
            System.setOut(stdout);
        }
        assertTrue(Files.size(out.resolve("random_numbers_CHECK.csv")) > 0);
        assertTrue(Files.size(out.resolve("random_numbers_FS.csv")) > 0);
    }
}
