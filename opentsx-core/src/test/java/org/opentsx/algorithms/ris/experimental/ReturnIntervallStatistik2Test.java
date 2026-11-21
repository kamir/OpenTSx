package org.opentsx.algorithms.ris.experimental;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReturnIntervallStatistik2Test {

    @TempDir
    Path dir;

    int binning;
    int scale;

    @BeforeEach
    void saveTheStaticBinning() {
        binning = ReturnIntervallStatistik2.binning;
        scale = ReturnIntervallStatistik2.scale;
    }

    @AfterEach
    void restoreTheStaticBinning() {
        ReturnIntervallStatistik2.binning = binning;
        ReturnIntervallStatistik2.scale = scale;
    }

    @Test
    void aNaNProbabilityIsRejectedNamingTheIndexAndTheContainerIsUnchanged() throws Exception {
        ReturnIntervallStatistik2 single = filled("single");
        single.probSKALIERT[3] = Double.NaN;
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> container.add(single));

        assertEquals("add: probability 3 of single is NaN; expected a finite probability", e.getMessage());
        for (int i = 0; i < container.probSKALIERT.length; i++) {
            assertEquals(0.0, container.probSKALIERT[i]);
            assertEquals(0, container.dataSKALIERT[i]);
        }
    }

    @Test
    void addingToAResultThatIsNotAContainerFails() throws Exception {
        ReturnIntervallStatistik2 single = filled("single");
        ReturnIntervallStatistik2 other = filled("other");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> other.add(single));

        assertEquals("add: other is not a container; set isContainerInstanz to true before adding results",
                e.getMessage());
    }

    @Test
    void addingAResultWithAnotherBinningFailsNamingBoth() throws Exception {
        ReturnIntervallStatistik2 single = new ReturnIntervallStatistik2("single", 6, 10);
        single.addDistData(intervals(1100));
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> container.add(single));

        assertEquals("add: single has 7 bins and all has 13; expected the same binning and scale", e.getMessage());
    }

    @Test
    void addingIntervalsToAContainerFails() {
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> container.addDistData(intervals(10)));

        assertEquals("addDistData: all is a container; add intervals to a result that is not a container",
                e.getMessage());
    }

    @Test
    void aFailingStoreClosesItsFile() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("stored", 12, 10);
        ris.folder = dir + "/";
        ris.label = "distribution.dat";
        ris.mrVerteilung = failingSeries();

        assertThrows(IllegalStateException.class, ris::store);

        assertFalse(isOpen(dir.resolve("distribution.dat")));
    }

    @Test
    void addingAndStoringAreLoggedAtDebugWithoutTheDebugAndVerboseFlags() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("logged", 12, 10);
        ris.folder = dir + "/";
        ris.label = "distribution.dat";

        String log = stderrOf(() -> {
            ris.addDistData(intervals(10));
            ris.store();
        });

        assertTrue(log.contains("adding intervals result=logged intervals=10"), log);
        assertTrue(log.contains("stored distribution result=logged file=" + dir + "/distribution.dat"), log);
    }

    @Test
    void addingTimesAddsTheIntervalsBetweenThem() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("times", 12, 10);

        ris._addData(times(0.0, 2.0, 5.0, 9.0), 3.0);

        assertEquals(List.of(2.0, 3.0, 4.0), ris.dataRaw_InterEventTIMES);
        assertEquals(List.of(2.0, 3.0, 4.0), ris.dataRawFILTERED);
        assertEquals(3.0, ris.getRq());

        ris._addData(times(10.0, 11.0), 1.0);

        assertEquals(List.of(1.0), ris.dataRaw_InterEventTIMES);
        assertEquals(List.of(1.0), ris.dataRawFILTERED);
    }

    @Test
    void theMeanOfANaNOrInfiniteValueIsNaN() {
        assertEquals(1.7e308, ReturnIntervallStatistik2.meanOf(times(1.7e308, 1.7e308)));
        assertTrue(Double.isNaN(ReturnIntervallStatistik2.meanOf(times())));
        assertTrue(Double.isNaN(ReturnIntervallStatistik2.meanOf(times(1.0, Double.POSITIVE_INFINITY))));
        assertTrue(Double.isNaN(ReturnIntervallStatistik2.meanOf(times(Double.POSITIVE_INFINITY))));
        assertTrue(Double.isNaN(ReturnIntervallStatistik2.meanOf(times(Double.NEGATIVE_INFINITY, 1.0))));
        assertTrue(Double.isNaN(ReturnIntervallStatistik2.meanOf(times(1.0, Double.NaN))));
    }

    @Test
    void equalTimesAddNoIntervalAndTheProbabilitiesSumToOne() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("equal", 12, 10);

        ris._addData(times(0.0, 1.0, 1.0, 2.0), 1.0);

        assertEquals(List.of(1.0, 1.0), ris.dataRawFILTERED);
        assertEquals(0, ris.dataSKALIERT[0]);
        assertEquals(2, ris.dataSKALIERT[1]);
        assertEquals(1.0, Arrays.stream(ris.probSKALIERT).sum(), 1e-12);
    }

    @Test
    void anIntervalThatIsLeftOutIsNotBinned() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("intervals", 12, 10);

        ris.addDistData(new Vector<>(List.of(1.0, 0.0, 1.0)));

        assertEquals(List.of(1.0, 1.0), ris.dataRawFILTERED);
        assertEquals(0, ris.dataSKALIERT[0]);
        assertEquals(1.0, Arrays.stream(ris.probSKALIERT).sum(), 1e-12);
    }

    @Test
    void aResultThatKeepsNoIntervalHasNaNProbabilitiesAndIsSkipped() throws Exception {
        ReturnIntervallStatistik2 empty = new ReturnIntervallStatistik2("empty", 12, 10);
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;

        empty._addData(times(1.0, 1.0, 1.0), 1.0);
        container.add(empty);

        assertEquals(List.of(), empty.dataRawFILTERED);
        assertTrue(Double.isNaN(empty.getRq()));
        assertTrue(Arrays.stream(empty.probSKALIERT).allMatch(Double::isNaN));
        empty.calcMessreihen();
        assertEquals(List.of(), empty.mrVerteilungSkaliert.xValues);
        assertEquals(List.of(), container.allRqs);
        assertEquals(0.0, Arrays.stream(container.probSKALIERT).sum());
    }

    @Test
    void aSingleResultGivesTheCountsAndTheDistributionOfItsIntervals() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("single", 12, 10);
        ris.addDistData(new Vector<>(List.of(0.15, 0.25, 0.25, 0.55, 3.0)));

        ris.calcMessreihen();

        double[] counts = {0, 1, 2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1};
        assertSeries(counts, 1.0, ris.mrHaeufigkeit);
        assertSeries(counts, 5.0, ris.mrVerteilung);
    }

    @Test
    void aSingleResultAddsTheShareOfEachScaledBin() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("single", 12, 10);
        ris.addDistData(new Vector<>(List.of(0.25, 0.25, 0.5, 3.0)));

        ris.calcMessreihen();

        assertArrayEquals(new int[] {3, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, ris.dataSKALIERT);
        assertScaledSeries(new double[] {3, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 4.0, ris.mrVerteilungSkaliert);
    }

    @Test
    void aSingleResultAndAContainerHoldingItPutEachScaledBinAtTheSameX() throws Exception {
        ReturnIntervallStatistik2 single = new ReturnIntervallStatistik2("single", 12, 10);
        single.addDistData(new Vector<>(List.of(0.25, 0.25, 0.5, 3.0)));
        single.calcMessreihen();
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;
        container.add(single);

        container.calcMessreihen();

        assertEquals(List.of(List.of(0.5 * 10 / 12, 0.75), List.of(3.5 * 10 / 12, 0.25)),
                pointsWithAShare(container.mrVerteilungSkaliert));
        assertEquals(pointsWithAShare(container.mrVerteilungSkaliert), pointsWithAShare(single.mrVerteilungSkaliert));
    }

    @Test
    void aNegativeIntervalKeptWithAnOffsetBelow0IsCountedInTheFirstBin() throws Exception {
        double offset = ReturnIntervallStatistik2.offset;
        ReturnIntervallStatistik2.offset = -5;
        try {
            ReturnIntervallStatistik2 near = new ReturnIntervallStatistik2("near", 12, 10);
            ReturnIntervallStatistik2 far = new ReturnIntervallStatistik2("far", 12, 10);
            ReturnIntervallStatistik2 negativeMean = new ReturnIntervallStatistik2("negativeMean", 12, 10);

            near.addDistData(new Vector<>(List.of(-0.5, 2.0, 3.0)));
            far.addDistData(new Vector<>(List.of(-3.0, 2.0, 3.0)));
            negativeMean.addDistData(new Vector<>(List.of(-3.0, 1.0)));

            assertEquals(List.of(-0.5, 2.0, 3.0), near.dataRawFILTERED);
            assertArrayEquals(new int[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}, near.data);
            assertArrayEquals(new int[] {1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, near.dataSKALIERT);
            assertArrayEquals(new int[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}, far.data);
            assertArrayEquals(new int[] {1, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0}, far.dataSKALIERT);
            assertEquals(1.0, Arrays.stream(far.probSKALIERT).sum(), 1e-12);
            assertEquals(-1.0, negativeMean.getRq());
            assertArrayEquals(new int[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0}, negativeMean.data);
            assertArrayEquals(new int[] {1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, negativeMean.dataSKALIERT);
        } finally {
            ReturnIntervallStatistik2.offset = offset;
        }
    }

    @Test
    void intervalsWhoseSumOverflowsGiveTheirMeanAsRqAndTheirScaledBin() throws Exception {
        ReturnIntervallStatistik2 two = new ReturnIntervallStatistik2("two", 12, 10);
        ReturnIntervallStatistik2 three = new ReturnIntervallStatistik2("three", 12, 10);
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;

        two.addDistData(new Vector<>(List.of(1.7e308, 1.7e308)));
        three.addDistData(new Vector<>(List.of(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)));
        container.add(two);

        assertEquals(1.7e308, two.getRq());
        assertArrayEquals(new int[] {0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, two.dataSKALIERT);
        assertEquals(Double.MAX_VALUE, three.getRq());
        assertArrayEquals(new int[] {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, three.dataSKALIERT);
        assertEquals(List.of(1.7e308), container.allRqs);
    }

    @Test
    void aSecondCalcMessreihenGivesTheSameSeriesAsTheFirst() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("twice", 12, 10);
        ris.addDistData(new Vector<>(List.of(0.15, 0.25, 0.25, 0.55, 3.0)));
        ris.calcMessreihen();
        List<List<Object>> once = pairsOf(ris.mrHaeufigkeit, ris.mrVerteilung, ris.mrVerteilungSkaliert);

        ris.calcMessreihen();

        assertEquals(once, pairsOf(ris.mrHaeufigkeit, ris.mrVerteilung, ris.mrVerteilungSkaliert));
    }

    @Test
    void aPositiveOffsetLeavesOutTheScaledBinsThatStartBelowOffsetOverRq() throws Exception {
        double offset = ReturnIntervallStatistik2.offset;
        ReturnIntervallStatistik2.offset = 0.5;
        try {
            ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("offset", 12, 10);
            ris.addDistData(new Vector<>(List.of(0.5, 1.0, 1.5, 3.0)));

            ris.calcMessreihen();

            assertEquals(1.5, ris.getRq(), 1e-12);
            assertArrayEquals(new int[] {2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, ris.dataSKALIERT);
            assertScaledSeriesFrom(1, new double[] {1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 4.0, ris.mrVerteilungSkaliert);
        } finally {
            ReturnIntervallStatistik2.offset = offset;
        }
    }

    @Test
    void aNanOrInfiniteIntervalIsRefused() {
        ReturnIntervallStatistik2 nan = new ReturnIntervallStatistik2("nan", 12, 10);
        ReturnIntervallStatistik2 infinite = new ReturnIntervallStatistik2("infinite", 12, 10);

        IllegalArgumentException notANumber = assertThrows(IllegalArgumentException.class,
                () -> nan.addDistData(new Vector<>(List.of(1.0, 2.0, Double.NaN, 3.0))));
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> infinite.addDistData(new Vector<>(List.of(1.0, Double.POSITIVE_INFINITY))));

        assertEquals("addDistData: interval 2 of nan is NaN; expected a finite interval", notANumber.getMessage());
        assertEquals("addDistData: interval 1 of infinite is Infinity; expected a finite interval", tooLong.getMessage());
        for (ReturnIntervallStatistik2 refused : List.of(nan, infinite)) {
            assertArrayEquals(new int[13], refused.dataSKALIERT);
            assertEquals(List.of(), refused.dataRawFILTERED);
            assertEquals(0.0, refused.getRq());
        }
    }

    @Test
    void aTimeBeforeTheTimeBeforeItIsRefused() {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("unsorted", 12, 10);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ris._addData(times(0.0, 5.0, 2.0), 1.0));

        assertEquals("_addData: time 2 of unsorted is 2.0, before time 1 at 5.0; expected times that do not decrease",
                e.getMessage());
        assertEquals(List.of(), ris.dataRaw_InterEventTIMES);
    }

    @Test
    void aTimeThatIsNotFiniteIsRefused() {
        ReturnIntervallStatistik2 nan = new ReturnIntervallStatistik2("nan", 12, 10);
        ReturnIntervallStatistik2 infinite = new ReturnIntervallStatistik2("infinite", 12, 10);

        IllegalArgumentException later = assertThrows(IllegalArgumentException.class,
                () -> nan._addData(times(0.0, Double.NaN, 5.0), 1.0));
        IllegalArgumentException first = assertThrows(IllegalArgumentException.class,
                () -> infinite._addData(times(Double.POSITIVE_INFINITY, 1.0), 1.0));

        assertEquals("_addData: time 1 of nan is NaN; expected a finite time", later.getMessage());
        assertEquals("_addData: time 0 of infinite is Infinity; expected a finite time", first.getMessage());
        assertEquals(List.of(), nan.dataRaw_InterEventTIMES);
        assertEquals(List.of(), infinite.dataRaw_InterEventTIMES);
    }

    @Test
    void noTimesAddNoIntervalLikeOneTime() throws Exception {
        ReturnIntervallStatistik2 none = new ReturnIntervallStatistik2("none", 12, 10);
        ReturnIntervallStatistik2 one = new ReturnIntervallStatistik2("one", 12, 10);
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;

        none._addData(times(), 1.0);
        one._addData(times(4.0), 1.0);
        container.add(none);
        container.add(one);

        for (ReturnIntervallStatistik2 ris : List.of(none, one)) {
            assertEquals(List.of(), ris.dataRaw_InterEventTIMES);
            assertEquals(List.of(), ris.dataRawFILTERED);
            assertTrue(Double.isNaN(ris.getRq()));
            ris.calcMessreihen();
            assertEquals(List.of(), ris.mrVerteilungSkaliert.xValues);
        }
        assertEquals(List.of(), container.allRqs);
        assertEquals(0.0, Arrays.stream(container.probSKALIERT).sum());
    }

    @Test
    void noTimesAreLoggedAsNoInterval() throws Exception {
        String log = stderrOf(() -> new ReturnIntervallStatistik2("none", 12, 10)._addData(times(), 1.0));

        assertTrue(log.contains("computing intervals result=none intervals=0"), log);
    }

    @Test
    void anOffsetOf0OrBelowKeepsEveryScaledBin() throws Exception {
        double offset = ReturnIntervallStatistik2.offset;
        try {
            ReturnIntervallStatistik2.offset = 0.0;
            ReturnIntervallStatistik2 zeroMean = new ReturnIntervallStatistik2("zeroMean", 12, 10);
            zeroMean.addDistData(new Vector<>(List.of(1.0, 2.0, 3.0)));
            zeroMean.setRQ(0.0);
            zeroMean.calcMessreihen();

            ReturnIntervallStatistik2.offset = -5;
            ReturnIntervallStatistik2 negativeMean = new ReturnIntervallStatistik2("negativeMean", 12, 10);
            negativeMean.addDistData(new Vector<>(List.of(-3.0, 1.0)));
            negativeMean.calcMessreihen();
            ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
            container.isContainerInstanz = true;
            container.add(negativeMean);
            container.calcMessreihen();

            assertScaledSeries(new double[] {1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 3.0, zeroMean.mrVerteilungSkaliert);
            assertScaledSeries(new double[] {1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 2.0, negativeMean.mrVerteilungSkaliert);
            assertEquals(pointsWithAShare(container.mrVerteilungSkaliert), pointsWithAShare(negativeMean.mrVerteilungSkaliert));
        } finally {
            ReturnIntervallStatistik2.offset = offset;
        }
    }

    @Test
    void aPositiveOffsetLeavesOutNoScaledBinWhenRqIs0OrNegative() throws Exception {
        double offset = ReturnIntervallStatistik2.offset;
        ReturnIntervallStatistik2.offset = 0.5;
        try {
            ReturnIntervallStatistik2 zero = new ReturnIntervallStatistik2("zero", 12, 10);
            zero.addDistData(new Vector<>(List.of(1.0, 2.0, 3.0)));
            zero.setRQ(0.0);
            zero.calcMessreihen();
            ReturnIntervallStatistik2 negative = new ReturnIntervallStatistik2("negative", 12, 10);
            negative.addDistData(new Vector<>(List.of(1.0, 2.0, 3.0)));
            negative.setRQ(-1.0);
            negative.calcMessreihen();

            assertScaledSeries(new double[] {1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 3.0, zero.mrVerteilungSkaliert);
            assertScaledSeries(new double[] {1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 3.0, negative.mrVerteilungSkaliert);
        } finally {
            ReturnIntervallStatistik2.offset = offset;
        }
    }

    @Test
    void aResultThatKeepsNoIntervalHasAnEmptyDistribution() throws Exception {
        ReturnIntervallStatistik2 empty = new ReturnIntervallStatistik2("empty", 12, 10);
        empty._addData(times(1.0, 1.0, 1.0), 1.0);

        empty.calcMessreihen();

        assertEquals(List.of(), empty.mrVerteilung.xValues);
        assertEquals(List.of(), empty.mrVerteilung.yValues);
        assertSeries(new double[13], 1.0, empty.mrHaeufigkeit);
    }

    @Test
    void timesMoreThanDoubleMaxValueApartAreRefusedAndKeepTheIntervalsBefore() throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2("far", 12, 10);
        ris._addData(times(0.0, 2.0), 1.0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ris._addData(times(-1e308, 1e308), 1.0));

        assertEquals("_addData: time 1 of far is 1.0E308, more than Double.MAX_VALUE after time 0 at -1.0E308;"
                + " expected times whose difference is finite", e.getMessage());
        assertEquals(List.of(2.0), ris.dataRaw_InterEventTIMES);
        assertEquals(List.of(2.0), ris.dataRawFILTERED);
        assertEquals(2.0, ris.getRq());
    }

    @Test
    void timesAddedToAContainerAreRefusedAndAddNoInterval() {
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> container._addData(times(0.0, 1.0), 1.0));

        assertEquals("addDistData: all is a container; add intervals to a result that is not a container",
                e.getMessage());
        assertEquals(List.of(), container.dataRaw_InterEventTIMES);
    }

    @Test
    void addingARowLogsTheRqOfItsResultAndNotTheMeanOfTheValuesReadAsTimes() throws Exception {
        TimeSeriesObject series = new TimeSeriesObject();
        series.setLabel("row");
        for (double y : new double[] {0.0, 1.0, 2.0, 6.0}) {
            series.addValue(y);
        }
        ReturnIntervallStatistik2.mrv = new Vector<>();
        ReturnIntervallStatistik2.mrv2 = new Vector<>();
        ReturnIntervallStatistik2.mrvB = new Vector<>();
        ReturnIntervallStatistik2.mrv2B = new Vector<>();
        try {
            String log = stderrOf(() -> ReturnIntervallStatistik2.addRow(series));

            assertTrue(log.contains("mean interval series=row rq=3.0"), log);
            assertFalse(log.contains("mean interval series=row rq=2.0"), log);
            assertFalse(log.contains("mean interval series=row rq=2.25"), log);
        } finally {
            ReturnIntervallStatistik2.mrv = null;
            ReturnIntervallStatistik2.mrv2 = null;
            ReturnIntervallStatistik2.mrvB = null;
            ReturnIntervallStatistik2.mrv2B = null;
        }
    }

    @Test
    void addingARowBBinsTheIntervalsBetweenItsPeaksAndNotTheDaysOfThePeaks() throws Exception {
        TimeSeriesObject series = new TimeSeriesObject();
        series.setLabel("peaks");
        for (double y : new double[] {0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0}) {
            series.addValue(y);
        }
        ReturnIntervallStatistik2.binning = 12;
        ReturnIntervallStatistik2.scale = 10;
        ReturnIntervallStatistik2.mrvB = new Vector<>();
        ReturnIntervallStatistik2.mrv2B = new Vector<>();
        try {
            String log = stderrOf(() -> ReturnIntervallStatistik2.addRowB(series));

            assertTrue(log.contains("mean interval result=peaks rqAll=2.0 rqFiltered=2.0"), log);
            assertEquals(1, ReturnIntervallStatistik2.mrvB.size());
            assertEquals(List.of(List.of(1.25, 1.0)), pointsWithAShare(ReturnIntervallStatistik2.mrvB.get(0)));
        } finally {
            ReturnIntervallStatistik2.mrvB = null;
            ReturnIntervallStatistik2.mrv2B = null;
        }
    }

    @Test
    void aContainerAddsUpTheCountsOfItsResultsInItsCountSeries() throws Exception {
        ReturnIntervallStatistik2 first = new ReturnIntervallStatistik2("first", 12, 10);
        ReturnIntervallStatistik2 second = new ReturnIntervallStatistik2("second", 12, 10);
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;
        first.addDistData(new Vector<>(List.of(0.15, 0.25)));
        second.addDistData(new Vector<>(List.of(0.25, 0.55, 3.0)));

        container.add(first);
        container.add(second);
        container.calcMessreihen();
        container.calcMessreihen();

        assertSeries(new double[] {0, 1, 2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1}, 1.0, container.mrHaeufigkeit);
        assertEquals("all", container.mrHaeufigkeit.getLabel());
    }

    @Test
    void aContainerNamesItsScaledSeriesWithItsResolutionInParentheses() throws Exception {
        ReturnIntervallStatistik2 container = new ReturnIntervallStatistik2("all", 12, 10);
        container.isContainerInstanz = true;
        container.add(filled("first"));

        container.calcMessreihen();

        assertEquals("all (res=0.8333333333333334)", container.mrVerteilungSkaliert.getLabel());
    }

    @Test
    void addingARowChartsTheCountsOfItsIntervalsAndOfTheIntervalsBetweenItsPeaks() throws Exception {
        TimeSeriesObject series = new TimeSeriesObject();
        series.setLabel("counts");
        for (double y : new double[] {0.15, 0.25, 0.25, 0.55, 3.0}) {
            series.addValue(y);
        }
        ReturnIntervallStatistik2.binning = 12;
        ReturnIntervallStatistik2.scale = 10;
        ReturnIntervallStatistik2.mrv = new Vector<>();
        ReturnIntervallStatistik2.mrv2 = new Vector<>();
        ReturnIntervallStatistik2.mrvB = new Vector<>();
        ReturnIntervallStatistik2.mrv2B = new Vector<>();
        try {
            ReturnIntervallStatistik2.addRow(series);

            assertEquals(1, ReturnIntervallStatistik2.mrv2.size());
            assertEquals(1, ReturnIntervallStatistik2.mrv2B.size());
            assertSeries(new double[] {0, 1, 2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1}, 1.0, ReturnIntervallStatistik2.mrv2.get(0));
            assertSeries(new double[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0}, 1.0, ReturnIntervallStatistik2.mrv2B.get(0));
        } finally {
            ReturnIntervallStatistik2.mrv = null;
            ReturnIntervallStatistik2.mrv2 = null;
            ReturnIntervallStatistik2.mrvB = null;
            ReturnIntervallStatistik2.mrv2B = null;
        }
    }

    @Test
    void shiftingASeriesMovesItsMinimumTo0AndPrintsNothing() throws Exception {
        TimeSeriesObject series = new TimeSeriesObject();
        series.setLabel("gauss");
        for (double y : new double[] {1.0, -2.5, 4.0}) {
            series.addValue(y);
        }

        String[] out = new String[1];
        String log = stderrOf(() -> out[0] = stdoutOf(() -> ReturnIntervallStatistik2.shiftToZero(series,
                LoggerFactory.getLogger(ReturnIntervallStatistik2.class))));

        assertEquals("", out[0]);
        assertEquals(0.0, series.getMinY());
        assertEquals(List.of(3.5, 0.0, 6.5), series.yValues);
        assertTrue(log.contains("shifted series series=gauss min=-2.5 shiftedMin=0.0"), log);
    }

    interface Action {
        void run() throws Exception;
    }

    static String stdoutOf(Action action) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(out);
        }
        return captured.toString(UTF_8);
    }

    static String stderrOf(Action action) throws Exception {
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(err);
        }
        return captured.toString(UTF_8);
    }

    static TimeSeriesObject failingSeries() {
        return new TimeSeriesObject() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString fails");
            }
        };
    }

    static boolean isOpen(Path file) throws IOException {
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        Path real = file.toRealPath();
        try (Stream<Path> links = Files.list(fds)) {
            return links.anyMatch(fd -> real.equals(target(fd)));
        }
    }

    private static Path target(Path fd) {
        try {
            return fd.resolveSibling(Files.readSymbolicLink(fd));
        } catch (IOException gone) {
            return null;
        }
    }

    private static ReturnIntervallStatistik2 filled(String name) throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2(name, 12, 10);
        ris.addDistData(intervals(1100));
        return ris;
    }

    static void assertSeries(double[] counts, double total, TimeSeriesObject series) {
        assertEquals(counts.length, series.yValues.size(), series.toString());
        for (int i = 0; i < counts.length; i++) {
            assertEquals(i / 10.0, (Double) series.xValues.get(i), 1e-12);
            assertEquals(counts[i] / total, (Double) series.yValues.get(i), 1e-12);
        }
    }

    static void assertScaledSeries(double[] counts, double total, TimeSeriesObject series) {
        assertScaledSeriesFrom(0, counts, total, series);
    }

    static void assertScaledSeriesFrom(int firstBin, double[] counts, double total, TimeSeriesObject series) {
        assertEquals(counts.length, series.yValues.size(), series.toString());
        for (int i = 0; i < counts.length; i++) {
            assertEquals((firstBin + i + 0.5) * 10.0 / 12.0, (Double) series.xValues.get(i), 1e-12);
            assertEquals(counts[i] / total, (Double) series.yValues.get(i), 1e-12);
        }
    }

    static List<List<Object>> pointsWithAShare(TimeSeriesObject series) {
        List<List<Object>> points = new ArrayList<>();
        for (int i = 0; i < series.yValues.size(); i++) {
            if ((Double) series.yValues.get(i) != 0.0) {
                points.add(List.of(series.xValues.get(i), series.yValues.get(i)));
            }
        }
        return points;
    }

    static List<List<Object>> pairsOf(TimeSeriesObject... series) {
        List<List<Object>> pairs = new ArrayList<>();
        for (TimeSeriesObject one : series) {
            pairs.add(new ArrayList<>(one.xValues));
            pairs.add(new ArrayList<>(one.yValues));
        }
        return pairs;
    }

    static Vector<Double> times(double... values) {
        Vector<Double> times = new Vector<>();
        for (double value : values) {
            times.add(value);
        }
        return times;
    }

    static Vector<Double> intervals(int n) {
        Vector<Double> values = new Vector<>();
        for (int i = 0; i < n; i++) {
            values.add(1.0 + i % 7);
        }
        return values;
    }
}
