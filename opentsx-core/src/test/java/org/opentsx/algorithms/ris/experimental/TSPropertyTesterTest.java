package org.opentsx.algorithms.ris.experimental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static TSPropertyTester filled(String name) throws Exception {
        TSPropertyTester ris = new TSPropertyTester(name, 12, 10);
        ris.addDistData(intervals(1100));
        return ris;
    }
}
