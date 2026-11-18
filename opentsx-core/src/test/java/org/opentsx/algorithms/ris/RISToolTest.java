package org.opentsx.algorithms.ris;

import org.junit.jupiter.api.Test;

import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Vector;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RISToolTest {

    @Test
    void addingIntervalsOpensNoWindowAndPrintsNothing() throws Exception {
        RISTool ris = new RISTool("intervals", 12, 10);
        int windows = Window.getWindows().length;
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, UTF_8));
        try {
            ris.addDistData(intervals(600));
        } finally {
            System.setOut(out);
        }
        assertEquals(windows, Window.getWindows().length);
        assertEquals("", captured.toString(UTF_8));
        assertEquals(600, ris.anzFiltered);
    }

    @Test
    void aNaNProbabilityIsRejectedNamingTheIndexAndTheContainerIsUnchanged() throws Exception {
        RISTool single = filled("single");
        single.probSKALIERT[3] = Double.NaN;
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> container.add(single));

        assertEquals("add: probability 3 of single is NaN; expected a finite probability", e.getMessage());
        for (int i = 0; i < container.probSKALIERT.length; i++) {
            assertEquals(0.0, container.probSKALIERT[i]);
            assertEquals(0, container.dataSKALIERT[i]);
        }
    }

    @Test
    void aNaNProbabilityOfAResultWithFewIntervalsIsRejectedBeforeAnyLogIsWritten() throws Exception {
        RISTool single = new RISTool("single", 12, 10);
        single.addDistData(intervals(600));
        single.probSKALIERT[3] = Double.NaN;
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> container.add(single));

        assertEquals("add: probability 3 of single is NaN; expected a finite probability", e.getMessage());
        for (int i = 0; i < container.probSKALIERT.length; i++) {
            assertEquals(0.0, container.probSKALIERT[i]);
        }
    }

    @Test
    void aResultWithoutIntervalsIsSkipped() throws Exception {
        RISTool empty = new RISTool("empty", 12, 10);
        empty.addDistData(new Vector<Double>());
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;

        container.add(empty);

        for (int i = 0; i < container.probSKALIERT.length; i++) {
            assertEquals(0.0, container.probSKALIERT[i]);
            assertEquals(0, container.dataSKALIERT[i]);
        }
    }

    @Test
    void addingToAResultThatIsNotAContainerFailsInEnglish() throws Exception {
        RISTool single = filled("single");
        RISTool other = filled("other");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> other.add(single));
        assertEquals("add: other is not a container; set isContainerInstanz to true before adding results",
                e.getMessage());
    }

    @Test
    void anIntervalOfZeroWithoutAnOffsetFailsInEnglish() {
        RISTool ris = new RISTool("zero", 12, 10);
        Vector<Double> values = intervals(3);
        values.add(0.0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ris.addDistData(values));
        assertEquals("addDistData: 1 intervals are 0 or negative; expected positive intervals while offset is 0",
                e.getMessage());
    }

    private static RISTool filled(String name) throws Exception {
        RISTool ris = new RISTool(name, 12, 10);
        ris.addDistData(intervals(1100));
        return ris;
    }

    private static Vector<Double> intervals(int n) {
        Vector<Double> values = new Vector<>();
        for (int i = 0; i < n; i++) {
            values.add(1.0 + i % 7);
        }
        return values;
    }
}
