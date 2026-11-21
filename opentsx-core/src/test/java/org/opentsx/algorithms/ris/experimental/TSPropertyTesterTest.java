package org.opentsx.algorithms.ris.experimental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.assertScaledSeries;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.assertScaledSeriesFrom;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.assertSeries;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.failingSeries;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.intervals;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.isOpen;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.pairsOf;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.pointsWithAShare;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.stderrOf;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.times;

class TSPropertyTesterTest {

    @TempDir
    Path dir;

    @Test
    void aFailingStoreClosesItsFile() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("stored", 12, 10);
        tester.folder = dir + "/";
        tester.label = "distribution.dat";
        tester.mrVerteilung = failingSeries();

        assertThrows(IllegalStateException.class, tester::store);

        assertFalse(isOpen(dir.resolve("distribution.dat")));
    }

    @Test
    void addingAndStoringAreLoggedAtDebugWithoutTheDebugAndVerboseFlags() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("logged", 12, 10);
        tester.folder = dir + "/";
        tester.label = "distribution.dat";

        String log = stderrOf(() -> {
            tester.addDistData(intervals(10));
            tester.store();
        });

        assertTrue(log.contains("adding intervals result=logged intervals=10"), log);
        assertTrue(log.contains("stored distribution result=logged file=" + dir + "/distribution.dat"), log);
    }

    @Test
    void aNaNProbabilityIsRejectedNamingTheIndexAndTheContainerIsUnchanged() throws Exception {
        TSPropertyTester single = filled("single");
        single.probSKALIERT[3] = Double.NaN;
        TSPropertyTester container = new TSPropertyTester("all", 12, 10);
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
        TSPropertyTester single = filled("single");
        TSPropertyTester other = filled("other");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> other.add(single));

        assertEquals("add: other is not a container; set isContainerInstanz to true before adding results",
                e.getMessage());
    }

    @Test
    void addingAResultWithAnotherBinningFailsNamingBoth() throws Exception {
        TSPropertyTester single = new TSPropertyTester("single", 6, 10);
        single.addDistData(intervals(1100));
        TSPropertyTester container = new TSPropertyTester("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> container.add(single));

        assertEquals("add: single has 7 bins and all has 13; expected the same binning and scale", e.getMessage());
    }

    @Test
    void addingIntervalsToAContainerFails() {
        TSPropertyTester container = new TSPropertyTester("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> container.addDistData(intervals(10)));

        assertEquals("addDistData: all is a container; add intervals to a result that is not a container",
                e.getMessage());
    }

    @Test
    void addingTimesAddsTheIntervalsBetweenThem() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("times", 12, 10);

        tester._addData(times(0.0, 2.0, 5.0, 9.0), 3.0);

        assertEquals(List.of(2.0, 3.0, 4.0), tester.dataRaw_InterEventTIMES);
        assertEquals(List.of(2.0, 3.0, 4.0), tester.dataRawFILTERED);

        tester._addData(times(10.0, 11.0), 1.0);

        assertEquals(List.of(1.0), tester.dataRaw_InterEventTIMES);
        assertEquals(List.of(1.0), tester.dataRawFILTERED);
    }

    @Test
    void aSingleResultGivesTheCountsAndTheDistributionOfItsIntervals() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("single", 12, 10);
        tester.addDistData(new Vector<>(List.of(0.15, 0.25, 0.25, 0.55, 3.0)));

        tester.calcMessreihen();

        double[] counts = {0, 1, 2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1};
        assertSeries(counts, 1.0, tester.mrHaeufigkeit);
        assertSeries(counts, 5.0, tester.mrVerteilung);
    }

    @Test
    void aSingleResultAddsTheShareOfEachScaledBin() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("single", 12, 10);
        tester.addDistData(new Vector<>(List.of(0.25, 0.25, 0.5, 3.0)));

        tester.calcMessreihen();

        assertArrayEquals(new int[] {3, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, tester.dataSKALIERT);
        assertScaledSeries(new double[] {3, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 4.0, tester.mrVerteilungSkaliert);
    }

    @Test
    void aSingleResultAndAContainerHoldingItPutEachScaledBinAtTheSameX() throws Exception {
        TSPropertyTester single = new TSPropertyTester("single", 12, 10);
        single.addDistData(new Vector<>(List.of(0.25, 0.25, 0.5, 3.0)));
        single.calcMessreihen();
        TSPropertyTester container = new TSPropertyTester("all", 12, 10);
        container.isContainerInstanz = true;
        container.add(single);

        container.calcMessreihen();

        assertEquals(List.of(List.of(0.5 * 10 / 12, 0.75), List.of(3.5 * 10 / 12, 0.25)),
                pointsWithAShare(container.mrVerteilungSkaliert));
        assertEquals(pointsWithAShare(container.mrVerteilungSkaliert), pointsWithAShare(single.mrVerteilungSkaliert));
    }

    @Test
    void setRQAfterAddDistDataReplacesTheMeanForCalcMessreihenAndAdd() throws Exception {
        double offset = TSPropertyTester.offset;
        TSPropertyTester.offset = 2.0;
        try {
            TSPropertyTester tester = new TSPropertyTester("set after", 12, 10);
            tester.addDistData(new Vector<>(List.of(1.0, 2.0, 3.0)));
            tester.setRQ(4.0);
            TSPropertyTester container = new TSPropertyTester("all", 12, 10);
            container.isContainerInstanz = true;

            tester.calcMessreihen();
            container.add(tester);

            assertEquals(4.0, tester.getRq());
            assertArrayEquals(new int[] {1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, tester.dataSKALIERT);
            assertScaledSeriesFrom(1, new double[] {2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 3.0, tester.mrVerteilungSkaliert);
            assertEquals(List.of(4.0), container.allRqs);
        } finally {
            TSPropertyTester.offset = offset;
        }
    }

    @Test
    void aSecondCalcMessreihenGivesTheSameSeriesAsTheFirst() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("twice", 12, 10);
        tester.addDistData(new Vector<>(List.of(0.15, 0.25, 0.25, 0.55, 3.0)));
        tester.calcMessreihen();
        List<List<Object>> once = pairsOf(tester.mrHaeufigkeit, tester.mrVerteilung, tester.mrVerteilungSkaliert);

        tester.calcMessreihen();

        assertEquals(once, pairsOf(tester.mrHaeufigkeit, tester.mrVerteilung, tester.mrVerteilungSkaliert));
    }

    @Test
    void aPositiveOffsetLeavesOutTheScaledBinsThatStartBelowOffsetOverRq() throws Exception {
        double offset = TSPropertyTester.offset;
        TSPropertyTester.offset = 0.5;
        try {
            TSPropertyTester tester = new TSPropertyTester("offset", 12, 10);
            tester.addDistData(new Vector<>(List.of(0.5, 1.0, 1.5, 3.0)));

            tester.calcMessreihen();

            assertEquals(1.5, tester.getRq(), 1e-12);
            assertArrayEquals(new int[] {2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, tester.dataSKALIERT);
            assertScaledSeriesFrom(1, new double[] {1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 4.0, tester.mrVerteilungSkaliert);
        } finally {
            TSPropertyTester.offset = offset;
        }
    }

    @Test
    void theBinsAreScaledWithTheMeanOfTheIntervalsAndCountAnIntervalPastTheLastBin() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("scaled", 12, 10);
        Vector<Double> intervals = new Vector<>();
        for (int i = 0; i < 10; i++) {
            intervals.add(1.0);
        }
        intervals.add(1000.0);

        tester.addDistData(intervals);

        assertEquals(10, tester.dataSKALIERT[0]);
        assertEquals(1, tester.dataSKALIERT[12]);
        assertEquals(10.0 / 11.0, tester.probSKALIERT[0], 1e-12);
        assertEquals(1.0, Arrays.stream(tester.probSKALIERT).sum(), 1e-9);
    }

    @Test
    void intervalsWhoseSumOverflowsGiveTheirMeanAsRqAndTheirScaledBin() throws Exception {
        TSPropertyTester two = new TSPropertyTester("two", 12, 10);
        TSPropertyTester three = new TSPropertyTester("three", 12, 10);
        TSPropertyTester container = new TSPropertyTester("all", 12, 10);
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
    void negativeIntervalsWhoseSumOverflowsGiveTheirMeanAsRqAndTheirScaledBin() throws Exception {
        TSPropertyTester two = new TSPropertyTester("two", 12, 10);
        TSPropertyTester mixed = new TSPropertyTester("mixed", 12, 10);

        two.addDistData(new Vector<>(List.of(-1.7e308, -1.7e308)));
        mixed.addDistData(new Vector<>(List.of(-1.7e308, -1.7e308, 1.0)));

        assertEquals(-1.7e308, two.getRq());
        assertArrayEquals(new int[] {0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, two.dataSKALIERT);
        assertEquals(-1.1333333333333332E308, mixed.getRq());
        assertArrayEquals(new int[] {1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, mixed.dataSKALIERT);
    }

    @Test
    void setRQDoesNotChangeTheBins() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("set", 12, 10);
        tester.setRQ(4.0);

        tester.addDistData(new Vector<>(List.of(1.0, 2.0, 3.0)));

        assertArrayEquals(new int[] {1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, tester.dataSKALIERT);
    }

    @Test
    void getRqReturnsTheMeanTheBinsAreScaledWith() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("rq", 12, 10);
        tester.setRQ(4.0);

        tester.addDistData(new Vector<>(List.of(1.0, 2.0, 3.0)));

        assertEquals(2.0, tester.getRq(), 1e-12);
    }

    @Test
    void aNanOrInfiniteIntervalIsRefused() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
            TSPropertyTester tester = new TSPropertyTester("bad", 12, 10);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> tester.addDistData(new Vector<>(List.of(1.0, 2.0, bad, 3.0))));

            assertEquals("addDistData: interval 2 of bad is " + bad + "; expected a finite interval", e.getMessage());
            assertArrayEquals(new int[13], tester.dataSKALIERT);
            assertEquals(List.of(), tester.dataRawFILTERED);
        }
    }

    @Test
    void anIntervalBelowTheFirstBinIsCountedInTheFirstBin() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("negative", 12, 10);

        tester.addDistData(new Vector<>(List.of(-3.0, 1.0, 2.0, 4.0)));

        assertArrayEquals(new int[] {1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0}, tester.dataSKALIERT);
        assertEquals(1.0, Arrays.stream(tester.probSKALIERT).sum(), 1e-9);
    }

    @Test
    void aTimeBeforeTheTimeBeforeItIsRefused() {
        TSPropertyTester tester = new TSPropertyTester("unsorted", 12, 10);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tester._addData(times(0.0, 5.0, 2.0), 1.0));

        assertEquals("_addData: time 2 of unsorted is 2.0, before time 1 at 5.0; expected times that do not decrease",
                e.getMessage());
        assertEquals(List.of(), tester.dataRaw_InterEventTIMES);
    }

    @Test
    void aTimeThatIsNotFiniteIsRefused() {
        TSPropertyTester nan = new TSPropertyTester("nan", 12, 10);
        TSPropertyTester infinite = new TSPropertyTester("infinite", 12, 10);

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
        TSPropertyTester none = new TSPropertyTester("none", 12, 10);
        TSPropertyTester one = new TSPropertyTester("one", 12, 10);
        TSPropertyTester container = new TSPropertyTester("all", 12, 10);
        container.isContainerInstanz = true;

        none._addData(times(), 1.0);
        one._addData(times(4.0), 1.0);
        container.add(none);
        container.add(one);

        for (TSPropertyTester tester : List.of(none, one)) {
            assertEquals(List.of(), tester.dataRaw_InterEventTIMES);
            assertEquals(List.of(), tester.dataRawFILTERED);
            assertTrue(Double.isNaN(tester.getRq()));
            tester.calcMessreihen();
            assertEquals(List.of(), tester.mrVerteilungSkaliert.xValues);
        }
        assertEquals(List.of(), container.allRqs);
        assertEquals(0.0, Arrays.stream(container.probSKALIERT).sum());
    }

    @Test
    void noTimesAreLoggedAsNoInterval() throws Exception {
        String log = stderrOf(() -> new TSPropertyTester("none", 12, 10)._addData(times(), 1.0));

        assertTrue(log.contains("computing intervals result=none intervals=0"), log);
    }

    @Test
    void anOffsetOf0KeepsEveryScaledBinWhenTheMeanIntervalIs0() throws Exception {
        double offset = TSPropertyTester.offset;
        TSPropertyTester.offset = 0.0;
        try {
            TSPropertyTester single = new TSPropertyTester("still", 12, 10);
            single.addDistData(new Vector<>(List.of(0.0, 0.0)));
            single.calcMessreihen();
            TSPropertyTester container = new TSPropertyTester("all", 12, 10);
            container.isContainerInstanz = true;
            container.add(single);

            container.calcMessreihen();

            assertEquals(0.0, single.getRq());
            assertScaledSeries(new double[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 1.0, single.mrVerteilungSkaliert);
            assertEquals(List.of(List.of(0.5 * 10 / 12, 1.0)), pointsWithAShare(container.mrVerteilungSkaliert));
            assertEquals(pointsWithAShare(container.mrVerteilungSkaliert), pointsWithAShare(single.mrVerteilungSkaliert));
        } finally {
            TSPropertyTester.offset = offset;
        }
    }

    @Test
    void aPositiveOffsetLeavesOutNoScaledBinWhenTheMeanIntervalIs0OrNegative() throws Exception {
        double offset = TSPropertyTester.offset;
        TSPropertyTester.offset = 1.0;
        try {
            TSPropertyTester zeroMean = new TSPropertyTester("zeroMean", 12, 10);
            zeroMean.addDistData(new Vector<>(List.of(-1.0, 1.0)));
            zeroMean.calcMessreihen();
            TSPropertyTester negativeMean = new TSPropertyTester("negativeMean", 12, 10);
            negativeMean.addDistData(new Vector<>(List.of(-3.0, 1.0)));
            negativeMean.calcMessreihen();
            TSPropertyTester container = new TSPropertyTester("all", 12, 10);
            container.isContainerInstanz = true;
            container.add(zeroMean);
            container.calcMessreihen();

            assertEquals(0.0, zeroMean.getRq());
            assertEquals(-1.0, negativeMean.getRq());
            assertScaledSeries(new double[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}, 2.0, zeroMean.mrVerteilungSkaliert);
            assertScaledSeries(new double[] {1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 2.0, negativeMean.mrVerteilungSkaliert);
            assertEquals(pointsWithAShare(container.mrVerteilungSkaliert), pointsWithAShare(zeroMean.mrVerteilungSkaliert));
        } finally {
            TSPropertyTester.offset = offset;
        }
    }

    @Test
    void anOffsetBelow0KeepsEveryScaledBinWhenTheMeanIntervalIsNegative() throws Exception {
        double offset = TSPropertyTester.offset;
        TSPropertyTester.offset = -5;
        try {
            TSPropertyTester negativeMean = new TSPropertyTester("negativeMean", 12, 10);
            negativeMean.addDistData(new Vector<>(List.of(-3.0, 1.0)));
            negativeMean.calcMessreihen();
            TSPropertyTester container = new TSPropertyTester("all", 12, 10);
            container.isContainerInstanz = true;
            container.add(negativeMean);
            container.calcMessreihen();

            assertEquals(-1.0, negativeMean.getRq());
            assertScaledSeries(new double[] {1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 2.0, negativeMean.mrVerteilungSkaliert);
            assertEquals(pointsWithAShare(container.mrVerteilungSkaliert), pointsWithAShare(negativeMean.mrVerteilungSkaliert));
        } finally {
            TSPropertyTester.offset = offset;
        }
    }

    @Test
    void aResultWithoutIntervalsHasAnEmptyDistribution() throws Exception {
        TSPropertyTester none = new TSPropertyTester("none", 12, 10);
        none._addData(times(4.0), 1.0);

        none.calcMessreihen();

        assertEquals(List.of(), none.mrVerteilung.xValues);
        assertEquals(List.of(), none.mrVerteilung.yValues);
        assertSeries(new double[13], 1.0, none.mrHaeufigkeit);
    }

    @Test
    void addingIntervalsLogsHowManyItKeeps() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("kept", 12, 10);

        String log = stderrOf(() -> tester.addDistData(intervals(10)));

        assertTrue(log.contains("kept intervals result=kept intervals=10"), log);
        assertFalse(log.contains("filtered intervals"), log);
    }

    @Test
    void timesMoreThanDoubleMaxValueApartAreRefusedAndKeepTheIntervalsBefore() throws Exception {
        TSPropertyTester tester = new TSPropertyTester("far", 12, 10);
        tester._addData(times(0.0, 2.0), 1.0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tester._addData(times(-1e308, 1e308), 1.0));

        assertEquals("_addData: time 1 of far is 1.0E308, more than Double.MAX_VALUE after time 0 at -1.0E308;"
                + " expected times whose difference is finite", e.getMessage());
        assertEquals(List.of(2.0), tester.dataRaw_InterEventTIMES);
        assertEquals(List.of(2.0), tester.dataRawFILTERED);
        assertEquals(2.0, tester.getRq());
    }

    @Test
    void addingARowLogsTheRqOfItsResult() throws Exception {
        TimeSeriesObject series = new TimeSeriesObject();
        series.setLabel("row");
        for (double y : new double[] {1.0, 2.0, 6.0}) {
            series.addValue(y);
        }
        Vector<TimeSeriesObject> mrv = TSPropertyTester.mrv;
        Vector<TimeSeriesObject> mrvB = TSPropertyTester.mrvB;
        TSPropertyTester.mrv = new Vector<>();
        TSPropertyTester.mrvB = new Vector<>();
        try {
            String log = stderrOf(() -> TSPropertyTester.addRow(series));

            assertTrue(log.contains("mean interval series=row rq=3.0"), log);
            assertFalse(log.contains("mean interval series=row rq=2.0"), log);
        } finally {
            TSPropertyTester.mrv = mrv;
            TSPropertyTester.mrvB = mrvB;
        }
    }

    @Test
    void shiftingASeriesLogsOnTheLoggerOfTheCaller() throws Exception {
        TimeSeriesObject series = new TimeSeriesObject();
        series.setLabel("gauss");
        for (double y : new double[] {1.0, -2.5, 4.0}) {
            series.addValue(y);
        }

        String log = stderrOf(() -> ReturnIntervallStatistik2.shiftToZero(series, LoggerFactory.getLogger(TSPropertyTester.class)));

        assertTrue(log.contains("DEBUG " + TSPropertyTester.class.getName()
                + " - shifted series series=gauss min=-2.5 shiftedMin=0.0"), log);
    }

    @Test
    void shiftingASeriesForMainLogsOnTheTSPropertyTesterLogger() throws Exception {
        TimeSeriesObject series = new TimeSeriesObject();
        series.setLabel("gauss");
        for (double y : new double[] {1.0, -2.5, 4.0}) {
            series.addValue(y);
        }

        String log = stderrOf(() -> TSPropertyTester.shiftToZero(series));

        assertTrue(log.contains("DEBUG " + TSPropertyTester.class.getName()
                + " - shifted series series=gauss min=-2.5 shiftedMin=0.0"), log);
        assertFalse(log.contains(ReturnIntervallStatistik2.class.getName()), log);
        assertEquals(0.0, series.getMinY());
    }

    @Test
    void timesAddedToAContainerAreRefusedAndAddNoInterval() {
        TSPropertyTester container = new TSPropertyTester("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> container._addData(times(0.0, 1.0), 1.0));

        assertEquals("addDistData: all is a container; add intervals to a result that is not a container",
                e.getMessage());
        assertEquals(List.of(), container.dataRaw_InterEventTIMES);
    }

    private static TSPropertyTester filled(String name) throws Exception {
        TSPropertyTester ris = new TSPropertyTester(name, 12, 10);
        ris.addDistData(intervals(1100));
        return ris;
    }
}
