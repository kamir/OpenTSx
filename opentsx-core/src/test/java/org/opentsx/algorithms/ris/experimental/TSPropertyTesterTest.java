package org.opentsx.algorithms.ris.experimental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.failingSeries;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.intervals;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.isOpen;
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
