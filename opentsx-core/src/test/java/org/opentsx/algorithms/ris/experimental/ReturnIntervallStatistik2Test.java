package org.opentsx.algorithms.ris.experimental;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    interface Action {
        void run() throws Exception;
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

    static Vector<Double> intervals(int n) {
        Vector<Double> values = new Vector<>();
        for (int i = 0; i < n; i++) {
            values.add(1.0 + i % 7);
        }
        return values;
    }
}
