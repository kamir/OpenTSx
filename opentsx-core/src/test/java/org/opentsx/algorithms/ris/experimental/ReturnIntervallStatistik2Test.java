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
        assertTrue(hasPair(ris.mrVerteilungSkaliert, 0.0, 0.75), ris.mrVerteilungSkaliert.toString());
        assertTrue(hasPair(ris.mrVerteilungSkaliert, 0.3, 0.25), ris.mrVerteilungSkaliert.toString());
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

    static void assertSeries(double[] counts, double total, TimeSeriesObject series) {
        assertEquals(counts.length, series.yValues.size(), series.toString());
        for (int i = 0; i < counts.length; i++) {
            assertEquals(i / 10.0, (Double) series.xValues.get(i), 1e-12);
            assertEquals(counts[i] / total, (Double) series.yValues.get(i), 1e-12);
        }
    }

    static boolean hasPair(TimeSeriesObject series, double x, double y) {
        for (int i = 0; i < series.xValues.size(); i++) {
            if (Math.abs((Double) series.xValues.get(i) - x) < 1e-12
                    && Math.abs((Double) series.yValues.get(i) - y) < 1e-12) {
                return true;
            }
        }
        return false;
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
