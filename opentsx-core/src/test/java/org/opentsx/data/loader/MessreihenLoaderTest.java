package org.opentsx.data.loader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessreihenLoaderTest {

    @TempDir
    Path dir;

    private String delimiter;

    @BeforeEach
    void keepTheDelimiter() {
        delimiter = MessreihenLoader.delim;
    }

    @AfterEach
    void restoreTheDelimiter() {
        MessreihenLoader.delim = delimiter;
    }

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

    private File write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file.toFile();
    }
}
