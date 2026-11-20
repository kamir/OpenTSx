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

    private static Vector<Double> intervals(int n) {
        Vector<Double> values = new Vector<>();
        for (int i = 0; i < n; i++) {
            values.add(1.0 + i % 7);
        }
        return values;
    }
}
