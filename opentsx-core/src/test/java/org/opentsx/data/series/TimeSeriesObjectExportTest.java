package org.opentsx.data.series;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesObjectExportTest {

    @TempDir
    Path dir;

    @Test
    void exportWritesEveryPairWithTheSeparator() throws IOException {
        TimeSeriesObject row = series(3);
        File file = dir.resolve("out/three.csv").toFile();

        row.writeToFile(file, ',');

        List<String> data = dataLines(file);
        assertEquals(List.of("1.0,0.5", "2.0,1.5", "3.0,2.5"), data);
    }

    @Test
    void exportOfAThousandPointsWritesAThousandLines() throws IOException {
        TimeSeriesObject row = series(1000);
        File tab = dir.resolve("thousand.tsv").toFile();
        File space = dir.resolve("thousand.txt").toFile();

        row.writeToFile(tab, '\t');
        row.writeToFile(space);

        List<String> tabLines = dataLines(tab);
        assertEquals(1000, tabLines.size());
        assertEquals("1000.0\t999.5", tabLines.get(999));
        List<String> spaceLines = dataLines(space);
        assertEquals(1000, spaceLines.size());
        assertEquals("1000.0 999.5", spaceLines.get(999));
    }

    @Test
    void aFailedWriteOfAnFftSeriesThrowsNamingTheFile() throws IOException {
        Path blocker = Files.writeString(dir.resolve("blocker"), "x");
        TimeSeriesObjectFFT row = TimeSeriesObjectFFT.convertToMessreiheFFT(series(3));
        File file = blocker.resolve("out.txt").toFile();

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> row.writeToFile(file));

        assertTrue(e.getMessage().startsWith(file + ": cannot write the file"), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void anFftSeriesExportsEveryPair() throws IOException {
        TimeSeriesObjectFFT row = TimeSeriesObjectFFT.convertToMessreiheFFT(series(3));
        row.addComment("a comment line");
        File file = dir.resolve("fft.txt").toFile();

        row.writeToFile(file);

        assertEquals(List.of("1.0 0.5", "2.0 1.5", "3.0 2.5"), dataLines(file));
    }

    @Test
    void textOfAShortSeriesListsItsPoints() {
        String text = series(3).toString();
        assertTrue(text.contains("# Nr of data points => 3"));
        assertEquals(List.of("1.00000 0.50000", "2.00000 1.50000", "3.00000 2.50000"),
                text.lines().filter(line -> !line.startsWith("#") && line.matches("\\S+ \\S+")).toList());
    }

    private static List<String> dataLines(File file) throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private static TimeSeriesObject series(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("export check");
        for (int i = 1; i <= n; i++) {
            row.addValuePair(i, i - 0.5);
        }
        return row;
    }
}
