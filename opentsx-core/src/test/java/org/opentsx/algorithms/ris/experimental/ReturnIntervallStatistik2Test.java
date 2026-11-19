package org.opentsx.algorithms.ris.experimental;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class ReturnIntervallStatistik2Test {

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

    private static ReturnIntervallStatistik2 filled(String name) throws Exception {
        ReturnIntervallStatistik2 ris = new ReturnIntervallStatistik2(name, 12, 10);
        ris.addDistData(intervals(1100));
        return ris;
    }

    static Vector<Double> intervals(int n) {
        Vector<Double> values = new Vector<>();
        for (int i = 0; i < n; i++) {
            values.add(1.0 + i % 7);
        }
        return values;
    }
}
