package org.opentsx.data.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.loader.util.ColumnValueCalculator;
import org.opentsx.data.loader.util.LineFilter;
import org.opentsx.data.loader.util.TSValueCalculator;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessreihenLoaderTest {

    @TempDir
    Path dir;

    @Test
    void aCommaSeparatedFileWithDecimalPointsLoadsEveryRow() throws IOException {
        File file = write("data.csv", "# time,value\n0.5,1.25\n1.5,-2.75\n2.5,3.0\n");
        TimeSeriesObject row = MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2, ",");
        assertEquals(List.of(0.5, 1.5, 2.5), row.xValues);
        assertEquals(List.of(1.25, -2.75, 3.0), row.yValues);
    }

    @Test
    void aSemicolonSeparatedFileWithDecimalCommasLoadsEveryRow() throws IOException {
        File file = write("data.csv", "0,5;1,25\n1,5;-2,75\n");
        TimeSeriesObject row = MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2, ";");
        assertEquals(List.of(0.5, 1.5), row.xValues);
        assertEquals(List.of(1.25, -2.75), row.yValues);
    }

    @Test
    void anExportedSeriesLoadsBackWithEveryPoint() {
        TimeSeriesObject exported = new TimeSeriesObject();
        for (int i = 0; i < 1000; i++) {
            exported.addValuePair(i * 0.1, Math.sin(i));
        }
        File file = dir.resolve("export.csv").toFile();
        exported.writeToFile(file, ',');

        TimeSeriesObject loaded = MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2, ",");

        assertEquals(exported.xValues, loaded.xValues);
        assertEquals(exported.yValues, loaded.yValues);
    }

    @Test
    void aValueThatIsNotANumberFailsNamingTheFileAndTheLine() throws IOException {
        File file = write("bad.csv", "# time,value\n0.5,1.0\n1.5,abc\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2, ","));
        assertEquals(file + ":3: \"abc\" is not a number; expected a decimal number such as 1.5", e.getMessage());
        assertInstanceOf(NumberFormatException.class, e.getCause());
    }

    @Test
    void aLineWithTooFewColumnsFailsNamingTheFileAndTheLine() throws IOException {
        File file = write("short.csv", "0.5,1.0\n1.5\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2, ","));
        assertEquals(file + ":2: the line has 1 columns; expected at least 2 columns separated by \",\"", e.getMessage());
    }

    @Test
    void aMissingFileFailsNamingThePath() {
        File file = dir.resolve("missing.csv").toFile();
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2, ","));
        assertTrue(e.getMessage().startsWith(file + ": cannot read the file"), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void twoLoadersWithDifferentDelimitersReadTheirOwnFiles() throws IOException {
        File comma = write("comma.csv", "0.5,1.25\n1.5,2.5\n");
        File semicolon = write("semicolon.csv", "0,5;1,25\n1,5;2,5\n");
        MessreihenLoader first = MessreihenLoader.getLoader();
        MessreihenLoader second = MessreihenLoader.getLoader();

        TimeSeriesObject before = first.loadMessreihe_2(comma, 1, 2);
        TimeSeriesObject other = second.loadMessreihe_2(semicolon, 1, 2, ";");
        TimeSeriesObject after = first.loadMessreihe_2(comma, 1, 2);

        assertEquals(List.of(1.25, 2.5), before.yValues);
        assertEquals(List.of(1.25, 2.5), other.yValues);
        assertEquals(List.of(1.25, 2.5), after.yValues);
    }

    @Test
    void aRowLimitAppliesOnlyToItsOwnCall() throws IOException {
        File file = write("rows.csv", "1,10\n2,20\n3,30\n");

        TimeSeriesObject limited = MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2, 1);
        TimeSeriesObject full = MessreihenLoader.getLoader().loadMessreihe_2(file, 1, 2);

        assertEquals(List.of(10.0), limited.yValues);
        assertEquals(List.of(10.0, 20.0, 30.0), full.yValues);
    }

    @ParameterizedTest
    @ValueSource(strings = {"loadMessreihe", "loadMessreihe_1", "loadMessreihe_2", "loadMessreihe_zr_SC",
            "linkStrengthColumn", "linkStrengthMapper", "fd", "logBinned", "logBinnedByWidth", "logBinnedByWidthFiltered"})
    void everyLoaderFailsOnAMissingFileNamingThePath(String method) {
        File file = dir.resolve("missing.txt").toFile();
        MessreihenLoader loader = MessreihenLoader.getLoader();
        Executable load = switch (method) {
            case "loadMessreihe" -> () -> loader.loadMessreihe(file);
            case "loadMessreihe_1" -> () -> loader.loadMessreihe_1(file);
            case "loadMessreihe_2" -> () -> loader.loadMessreihe_2(file, 1, 2);
            case "loadMessreihe_zr_SC" -> () -> loader.loadMessreihe_zr_SC(file, 2, ",");
            case "linkStrengthColumn" -> () -> loader.loadMessreihe_For_LinkStrength(file, 1, 2, 3, ",");
            case "linkStrengthMapper" -> () -> loader.loadMessreihe_For_LinkStrength(file, 1, 2, columnMapper(), ",");
            case "fd" -> () -> loader._loadMessreihe_For_FD(file, valueMapper(), ",", false);
            case "logBinned" -> () -> loader._loadLogBinnedMessreihe(file, 1, 2, 2.0, 10.0);
            case "logBinnedByWidth" -> () -> loader._loadLogBinnedMessreihe_DIV_BY_BINWIDTH(file, 1, 2, 2.0, 10.0);
            case "logBinnedByWidthFiltered" -> () -> loader._loadLogBinnedMessreihe_DIV_BY_BINWIDTH(file, 1, 2, 2.0,
                    10.0, " ", new LineFilter());
            default -> throw new IllegalArgumentException(method);
        };
        UncheckedIOException e = assertThrows(UncheckedIOException.class, load);
        assertEquals(file + ": cannot read the file (" + e.getCause() + ")", e.getMessage());
        assertInstanceOf(FileNotFoundException.class, e.getCause());
    }

    @Test
    void aValueThatIsNotANumberFailsInEveryLoaderNamingTheFileAndTheLine() throws IOException {
        File file = write("bad.txt", "# x y\n1 2\n3 abc\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader().loadMessreihe(file));
        assertEquals(file + ":3: \"abc\" is not a number; expected a decimal number such as 1.5", e.getMessage());
    }

    @Test
    void aMapperFailureNamesTheFileAndTheLine() throws IOException {
        File file = write("links.csv", "a,b,1\nc,d,2\n");
        ColumnValueCalculator failing = columnMapper();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader().loadMessreihe_For_LinkStrength(file, 1, 2, failing, ","));
        assertEquals(file + ":2: test mapper cannot read the line (java.lang.IllegalStateException: c); expected a line"
                + " the mapper accepts", e.getMessage());
    }

    @Test
    void aMapperDateFailureNamesTheFileAndTheLine() throws IOException {
        File file = write("dates.csv", "a,1\nb,2\n");
        TSValueCalculator failing = dateMapper(new IllegalStateException("b"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader()._loadMessreihe_For_FD(file, failing, ",", false));
        assertEquals(file + ":2: test mapper cannot read the line (java.lang.IllegalStateException: b); expected a line"
                + " the mapper accepts", e.getMessage());
    }

    @Test
    void aMapperWithoutADateFailsNamingTheFileAndTheLine() throws IOException {
        File file = write("dates.csv", "a,1\nb,2\n");
        TSValueCalculator noDate = dateMapper(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader()._loadMessreihe_For_FD(file, noDate, ",", false));
        assertEquals(file + ":2: test mapper returned no date; expected a date for every line", e.getMessage());
    }

    @Test
    void aLogBinnedFileWithoutDataLinesFailsNamingTheFile() throws IOException {
        File file = write("comment.csv", "# only a comment\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader()._loadLogBinnedMessreihe(file, 1, 2, 2.0, 10.0));
        assertEquals(file + ": the file has no data lines; expected at least one line with 2 columns separated by \",\"",
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"logBinnedByWidth", "logBinnedByWidthFiltered"})
    void aLogBinnedByWidthFileWithoutDataLinesFailsNamingTheFile(String method) throws IOException {
        File file = write("comment.csv", "# only a comment\n");
        MessreihenLoader loader = MessreihenLoader.getLoader();
        Executable load = method.equals("logBinnedByWidth")
                ? () -> loader._loadLogBinnedMessreihe_DIV_BY_BINWIDTH(file, 1, 2, 2.0, 10.0)
                : () -> loader._loadLogBinnedMessreihe_DIV_BY_BINWIDTH(file, 1, 2, 2.0, 10.0, ";", null);
        String delimiter = method.equals("logBinnedByWidth") ? "," : ";";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, load);
        assertEquals(file + ": the file has no data lines; expected at least one line with 2 columns separated by \""
                + delimiter + "\"", e.getMessage());
    }

    @Test
    void theSkippedShareOfALogBinnedLoadIsLoggedAsAPercentage() throws IOException {
        File file = write("binned.csv", "1.5,1\n2.5,1\n3.5,1\n100,1\n");
        String err = stderrOf(() -> MessreihenLoader.getLoader()._loadLogBinnedMessreihe(file, 1, 2, 2.0, 10.0));
        assertTrue(err.contains("used=3 skipped=1 skippedPercent=25.0"), err);
    }

    @Test
    void everyReadLineIsLoggedAtDebugWithoutADebugFlag() throws IOException {
        File labels = write("labels.csv", "# c\n\na_b,1.5,2\n");
        String err = stderrOf(() -> MessreihenLoader.getLoader().loadMessreihe_zr_SC(labels, 2, ","));
        assertTrue(err.contains("read line file=" + labels + " line=3 text=a_b,1.5,2"), err);
    }

    @Test
    void loadingWritesNothingToStandardOutput() throws IOException {
        File binned = write("binned.csv", "1.5,1\n2.5,1\n100,1\n");
        File labels = write("labels.csv", "# c\n\na_b,1.5,2\n");
        MessreihenLoader loader = MessreihenLoader.getLoader();
        String out = stdoutOf(() -> {
            loader._loadLogBinnedMessreihe(binned, 1, 2, 2.0, 10.0);
            loader._loadLogBinnedMessreihe_DIV_BY_BINWIDTH(binned, 1, 2, 2.0, 10.0);
            loader._loadLogBinnedMessreihe_DIV_BY_BINWIDTH(binned, 1, 2, 2.0, 10.0, ",", null);
            loader.loadMessreihe_zr_SC(labels, 2, ",");
            loader.loadMessreihe_For_LinkStrength(labels, 1, 2, 3, ",");
            loader.loadMessreihe_For_LinkStrength(labels, 1, 2, columnMapper(), ",");
            loader._loadMessreihe_For_FD(labels, valueMapper(), ",", false);
        });
        assertEquals("", out);
    }

    @Test
    void aLoaderReadsAFileOfAnyName() throws IOException {
        File file = write("data.csv", "1 2\n3 4\n");
        assertEquals(List.of(2.0, 4.0), MessreihenLoader.getLoader().loadMessreihe(file).yValues);
        assertEquals(List.of(1.0, 3.0), MessreihenLoader.getLoader().loadMessreihe_1(file).yValues);
    }

    @Test
    void aLabelColumnWithoutUnderscoreFailsNamingTheFileAndTheLine() throws IOException {
        File file = write("labels.csv", "a_b,1.5\nab,2.5\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessreihenLoader.getLoader().loadMessreihe_zr_SC(file, 2, ","));
        assertEquals(file + ":2: \"ab\" has no \"_\"; expected two labels joined by \"_\" in column 1", e.getMessage());
    }

    private static ColumnValueCalculator columnMapper() {
        return new ColumnValueCalculator() {
            public double getValue(int mode, String[] line) {
                return 0.0;
            }

            public double calcLinkStrength(String[] line) {
                if (line[0].equals("c")) throw new IllegalStateException("c");
                return Double.parseDouble(line[2]);
            }

            public String getName() {
                return "test mapper";
            }
        };
    }

    private static TSValueCalculator valueMapper() {
        return new TSValueCalculator() {
            public void setTimeIntervall(Date begin, Date end) {
            }

            public Date getDate(String[] line) {
                return new Date(0);
            }

            public double getValue(String[] line) {
                return 0.0;
            }

            public String getName() {
                return "test mapper";
            }
        };
    }

    private static TSValueCalculator dateMapper(RuntimeException onLineB) {
        return new TSValueCalculator() {
            public void setTimeIntervall(Date begin, Date end) {
            }

            public Date getDate(String[] line) {
                if (!line[0].equals("b")) return new Date(0);
                if (onLineB != null) throw onLineB;
                return null;
            }

            public double getValue(String[] line) {
                return Double.parseDouble(line[1]);
            }

            public String getName() {
                return "test mapper";
            }
        };
    }

    private static String stdoutOf(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private File write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file.toFile();
    }
}
