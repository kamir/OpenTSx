package org.opentsx.algorithms.ris.experimental;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2Test.intervals;

class TSPropertyTesterTest {

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

    private static TSPropertyTester filled(String name) throws Exception {
        TSPropertyTester ris = new TSPropertyTester(name, 12, 10);
        ris.addDistData(intervals(1100));
        return ris;
    }
}
