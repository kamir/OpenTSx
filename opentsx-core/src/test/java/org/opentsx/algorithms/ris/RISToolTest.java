package org.opentsx.algorithms.ris;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehlerDouble;
import org.opentsx.data.exporter.OriginProject;
import org.opentsx.data.series.TimeSeriesObject;

import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Vector;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RISToolTest {

    @TempDir
    Path dir;

    OriginProject projekt;
    String[] logFileNames;
    double[] rqMIN;
    HaeufigkeitsZaehlerDouble[] debug1;
    double offset;

    @BeforeEach
    void saveTheStaticSetup() {
        projekt = RISTool.projekt;
        logFileNames = RISTool.logFileNames;
        rqMIN = RISTool.rqMIN;
        debug1 = RISTool.debug1;
        offset = RISTool.offset;
    }

    @AfterEach
    void restoreTheStaticSetup() {
        RISTool.projekt = projekt;
        RISTool.logFileNames = logFileNames;
        RISTool.rqMIN = rqMIN;
        RISTool.debug1 = debug1;
        RISTool.offset = offset;
    }

    @Test
    void addingAResultWithFewIntervalsWithoutAProjectFailsNamingTheSetup() throws Exception {
        RISTool.projekt = null;
        RISTool single = new RISTool("single", 12, 10);
        single.addDistData(intervals(10));
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> container.add(single));

        assertEquals("add: RISTool.projekt is not set; set RISTool.projekt and RISTool.logFileNames and call"
                + " RISTool.initRqMIN() before adding single, which has 10 intervals", e.getMessage());
        for (int i = 0; i < container.probSKALIERT.length; i++) {
            assertEquals(0.0, container.probSKALIERT[i]);
        }
    }

    @Test
    void addingAResultWithFewIntervalsWithoutRqMinFailsNamingTheSetup() throws Exception {
        RISTool.projekt = project("ris");
        RISTool.logFileNames = names(8);
        RISTool.rqMIN = null;
        RISTool single = new RISTool("single", 12, 10);
        single.addDistData(intervals(10));
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> container.add(single));

        assertEquals("add: RISTool.rqMIN is not set; call RISTool.initRqMIN() before adding single, which has 10"
                + " intervals", e.getMessage());
    }

    @Test
    void addingAResultWithFewIntervalsWithTheSetupWritesOneLogLinePerInterval() throws Exception {
        RISTool.projekt = project("ris");
        RISTool.logFileNames = names(8);
        RISTool.initRqMIN();
        RISTool single = new RISTool("single", 12, 10);
        single.addDistData(intervals(10));
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;

        container.add(single);
        RISTool.projekt.closeAllWriters();

        assertEquals(10, java.nio.file.Files.readAllLines(dir.resolve("ris").resolve("log_1")).stream()
                .filter(line -> line.contains("r=")).count());
    }

    @Test
    void aFailingLogWriteOfAnAddedResultSurfaces() throws Exception {
        RISTool.projekt = project("added");
        RISTool.logFileNames = names(8);
        RISTool.initRqMIN();
        RISTool single = new RISTool("single", 12, 10);
        single.addDistData(intervals(10));
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;
        RISTool.projekt.closeAllWriters();

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> container.add(single));

        assertEquals("log_1: cannot write the log line (" + e.getCause() + ")", e.getMessage());
    }

    @Test
    void aFailingLogWriteOfFilteredIntervalsSurfaces() throws Exception {
        RISTool.offset = 2.0;
        RISTool.projekt = project("filtered");
        RISTool.projekt.closeAllWriters();
        RISTool ris = new RISTool("filtered", 12, 10);
        ris.setLofFileNameKey_1("log_0");

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> ris.addDistData(intervals(10)));

        assertEquals("log_0: cannot write the log line (" + e.getCause() + ")", e.getMessage());
    }

    @Test
    void aFailingLogWriteOfFilteredIntervalsKeepsTheResultOfTheCallBefore() throws Exception {
        RISTool.offset = 2.0;
        RISTool.projekt = project("kept");
        RISTool ris = new RISTool("kept", 12, 10);
        ris.setLofFileNameKey_1("log_0");
        ris.addDistData(new Vector<>(List.of(2.0, 3.0, 4.0)));
        int[] scaled = ris.dataSKALIERT.clone();
        int raw = RISTool.anzRowRAW.dists.size();
        int filtered = RISTool.anzRowsFILTERED.dists.size();
        RISTool.projekt.closeAllWriters();

        assertThrows(UncheckedIOException.class, () -> ris.addDistData(intervals(10)));

        assertEquals(List.of(2.0, 3.0, 4.0), ris.dataRawFILTERED);
        assertEquals(0, ris.cutOff);
        assertEquals(3, ris.anzFiltered);
        assertEquals(3.0, ris.getRq());
        assertArrayEquals(scaled, ris.dataSKALIERT);
        assertEquals(raw, RISTool.anzRowRAW.dists.size());
        assertEquals(filtered, RISTool.anzRowsFILTERED.dists.size());
    }

    @Test
    void aFailingLogWriteOfASeriesThatIsTooLongSurfaces() throws Exception {
        RISTool.projekt = project("long");
        RISTool.projekt.createLogFile("LENGTH");
        RISTool.projekt.closeAllWriters();
        RISTool ris = new RISTool("long", 12, 10);
        Vector<Double> times = new Vector<>();
        times.add(0.0);
        times.add(8000.0 * 60 * 60 * 1000);

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> ris._addData(times));

        assertEquals("LENGTH: cannot write the log line (" + e.getCause() + ")", e.getMessage());
    }

    private OriginProject project(String folder) throws Exception {
        OriginProject project = new OriginProject();
        project.initBaseFolder(dir.resolve(folder).toString());
        for (String name : names(8)) {
            project.createLogFile(name);
        }
        return project;
    }

    private static String[] names(int n) {
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = "log_" + i;
        }
        return names;
    }

    @Test
    void addingIntervalsIsLoggedAtDebugWithoutADebugFlag() throws Exception {
        RISTool ris = new RISTool("logged", 12, 10);
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, UTF_8));
        try {
            ris.addDistData(intervals(10));
        } finally {
            System.setErr(err);
        }
        assertTrue(captured.toString(UTF_8).contains("adding intervals result=logged intervals=10"),
                captured.toString(UTF_8));
    }

    @Test
    void storingIsLoggedAtDebugWithoutAVerboseFlag() throws Exception {
        RISTool ris = new RISTool("stored", 12, 10);
        ris.folder = dir + "/";
        ris.label = "distribution.dat";
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, UTF_8));
        try {
            ris.store();
        } finally {
            System.setErr(err);
        }
        String log = captured.toString(UTF_8);
        assertTrue(log.contains("stored distribution result=stored file=" + dir + "/distribution.dat"), log);
    }

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
    void noTimesAddNoIntervalLikeOneTime() throws Exception {
        RISTool none = new RISTool("none", 12, 10);
        RISTool one = new RISTool("one", 12, 10);
        RISTool container = new RISTool("all", 12, 10);
        container.isContainerInstanz = true;

        none._addData(new Vector<>());
        one._addData(new Vector<>(List.of(4.0)));
        container.add(none);
        container.add(one);

        for (RISTool ris : List.of(none, one)) {
            assertEquals(List.of(), ris.dataRawTIMES);
            assertEquals(List.of(), ris.dataRawFILTERED);
            assertTrue(Double.isNaN(ris.getRq()));
        }
        for (int i = 0; i < container.probSKALIERT.length; i++) {
            assertEquals(0.0, container.probSKALIERT[i]);
            assertEquals(0, container.dataSKALIERT[i]);
        }
    }

    @Test
    void noTimesAreLoggedAsNoInterval() throws Exception {
        RISTool ris = new RISTool("none", 12, 10);
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, UTF_8));
        try {
            ris._addData(new Vector<>());
        } finally {
            System.setErr(err);
        }
        String log = captured.toString(UTF_8);
        assertTrue(log.contains("computing intervals result=none intervals=0"), log);
    }

    @Test
    void aTimeBeforeTheTimeBeforeItIsRefused() {
        RISTool ris = new RISTool("unsorted", 12, 10);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ris._addData(times(0.0, 5.0, 2.0)));

        assertEquals("_addData: time 2 of unsorted is 2.0, before time 1 at 5.0; expected times that do not decrease",
                e.getMessage());
        assertEquals(List.of(), ris.dataRawTIMES);
        assertEquals(List.of(), ris.dataRawFILTERED);
    }

    @Test
    void aTimeThatIsNotFiniteIsRefused() {
        RISTool nan = new RISTool("nan", 12, 10);
        RISTool infinite = new RISTool("infinite", 12, 10);

        IllegalArgumentException later = assertThrows(IllegalArgumentException.class,
                () -> nan._addData(times(0.0, Double.NaN, 5.0)));
        IllegalArgumentException first = assertThrows(IllegalArgumentException.class,
                () -> infinite._addData(times(Double.POSITIVE_INFINITY, 1.0)));

        assertEquals("_addData: time 1 of nan is NaN; expected a finite time", later.getMessage());
        assertEquals("_addData: time 0 of infinite is Infinity; expected a finite time", first.getMessage());
        assertEquals(List.of(), nan.dataRawTIMES);
        assertEquals(List.of(), infinite.dataRawTIMES);
    }

    @Test
    void timesMoreThanDoubleMaxValueApartAreRefusedAndKeepTheIntervalsBefore() throws Exception {
        RISTool ris = new RISTool("far", 12, 10);
        ris._addData(times(0.0, 2.0 * 60 * 60 * 1000));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ris._addData(times(-1e308, 1e308)));

        assertEquals("_addData: time 1 of far is 1.0E308, more than Double.MAX_VALUE after time 0 at -1.0E308;"
                + " expected times whose difference is finite", e.getMessage());
        assertEquals(List.of(2.0), ris.dataRawTIMES);
        assertEquals(List.of(2.0), ris.dataRawFILTERED);
        assertEquals(2.0, ris.getRq());
    }

    @Test
    void equalTimesAddAnIntervalOf0ThatIsRefusedWhileOffsetIs0AndKeepTheIntervalsBefore() throws Exception {
        RISTool ris = new RISTool("equal", 12, 10);
        ris._addData(times(0.0, 2.0 * 60 * 60 * 1000));
        int rows = RISTool.anzRowRAW.dists.size();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ris._addData(times(0.0, 0.0, 60 * 60 * 1000)));

        assertEquals("addDistData: 1 intervals of equal are 0 or negative; expected positive intervals while offset"
                + " is 0", e.getMessage());
        assertEquals(List.of(2.0), ris.dataRawTIMES);
        assertEquals(List.of(2.0), ris.dataRawFILTERED);
        assertEquals(2.0, ris.getRq());
        assertEquals(1, ris.anzFiltered);
        assertEquals(0, ris.cutOff);
        assertEquals(rows, RISTool.anzRowRAW.dists.size());
    }

    @Test
    void aRefusedIntervalOf0IsNotCountedInTheNextCall() throws Exception {
        RISTool ris = new RISTool("again", 12, 10);
        assertThrows(IllegalStateException.class, () -> ris.addDistData(new Vector<>(List.of(1.0, 0.0))));

        ris._addData(times(0.0, 60 * 60 * 1000, 3 * 60 * 60 * 1000));

        assertEquals(List.of(1.0, 2.0), ris.dataRawTIMES);
        assertEquals(List.of(1.0, 2.0), ris.dataRawFILTERED);
        assertEquals(1.5, ris.getRq());
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
        assertEquals("addDistData: 1 intervals of zero are 0 or negative; expected positive intervals while offset"
                + " is 0", e.getMessage());
    }

    @Test
    void intervalsWhoseSumOverflowsGiveTheirMeanAsRqAndTheirScaledBin() throws Exception {
        RISTool ris = new RISTool("two", 12, 10);

        ris.addDistData(new Vector<>(List.of(1.7e308, 1.7e308)));

        assertEquals(1.7e308, ris.getRq());
        assertEquals(2, ris.dataSKALIERT[5]);
        assertEquals(1.0, ris.probSKALIERT[5]);
    }

    @Test
    void aNanOrInfiniteIntervalIsRefusedAndKeepsTheIntervalsBefore() throws Exception {
        RISTool ris = new RISTool("bad", 12, 10);
        ris.addDistData(new Vector<>(List.of(2.0, 4.0)));
        int[] scaled = ris.dataSKALIERT.clone();
        int rows = RISTool.anzRowRAW.dists.size();

        IllegalArgumentException infinite = assertThrows(IllegalArgumentException.class,
                () -> ris.addDistData(new Vector<>(List.of(1.0, Double.POSITIVE_INFINITY))));
        IllegalArgumentException nan = assertThrows(IllegalArgumentException.class,
                () -> ris.addDistData(new Vector<>(List.of(1.0, 2.0, Double.NaN, 3.0))));

        assertEquals("addDistData: interval 1 of bad is Infinity; expected a finite interval", infinite.getMessage());
        assertEquals("addDistData: interval 2 of bad is NaN; expected a finite interval", nan.getMessage());
        assertEquals(List.of(2.0, 4.0), ris.dataRawFILTERED);
        assertEquals(3.0, ris.getRq());
        assertArrayEquals(scaled, ris.dataSKALIERT);
        assertEquals(rows, RISTool.anzRowRAW.dists.size());
    }

    @Test
    void aFailingStoreClosesItsFile() throws Exception {
        RISTool ris = new RISTool("stored", 12, 10);
        ris.folder = dir + "/";
        ris.label = "distribution.dat";
        ris.mrVerteilung = new TimeSeriesObject() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString fails");
            }
        };

        assertThrows(IllegalStateException.class, ris::store);

        assertFalse(isOpen(dir.resolve("distribution.dat")));
    }

    private static boolean isOpen(Path file) throws IOException {
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

    private static RISTool filled(String name) throws Exception {
        RISTool ris = new RISTool(name, 12, 10);
        ris.addDistData(intervals(1100));
        return ris;
    }

    private static Vector<Double> times(double... values) {
        Vector<Double> times = new Vector<>();
        for (double value : values) {
            times.add(value);
        }
        return times;
    }

    private static Vector<Double> intervals(int n) {
        Vector<Double> values = new Vector<>();
        for (int i = 0; i < n; i++) {
            values.add(1.0 + i % 7);
        }
        return values;
    }
}
