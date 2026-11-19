package org.opentsx.data.exporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MeasurementTableTest {

    @TempDir
    Path dir;

    @Test
    void aWriteThatFailsThrowsNamingTheFile() throws Exception {
        File target = Files.createDirectory(dir.resolve("table.dat")).toFile();
        MeasurementTable table = table(series("a", 1.0, 2.0));

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> table.writeToFile(target));

        assertEquals(target + ": cannot write the file (" + e.getCause() + ")", e.getMessage());
    }

    @Test
    void aParentThatIsAFileFailsNamingTheFile() throws Exception {
        Path blocker = Files.writeString(dir.resolve("blocker"), "x");
        File target = blocker.resolve("table.dat").toFile();
        MeasurementTable table = table(series("a", 1.0, 2.0));

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> table.writeToFile(target));

        assertEquals(target + ": cannot create the parent directory (" + e.getCause() + ")", e.getMessage());
    }

    @Test
    void aFileNameWithoutParentNeedsNoDirectory() {
        assertDoesNotThrow(() -> new MeasurementTable().createParrentFile(new File("table.dat")));
    }

    @Test
    void aWriteCreatesTheParentDirectoryAndWritesTheTable() throws Exception {
        File target = dir.resolve("out").resolve("table.dat").toFile();
        table(series("a", 1.0, 2.0)).writeToFile(target);
        assertTrue(Files.readString(target.toPath()).contains("2.00000"));
    }

    @Test
    void aShorterSeriesIsFilledUpWithTheFillValue() {
        MeasurementTable table = table(series("long", 1.0, 2.0, 3.0), series("short", 4.0));
        table.singleX = false;
        String[] lines = table.toString().split("\n");
        String last = lines[lines.length - 1];
        assertEquals("3.00000\t3.00000\t3.00000\t-5.00000\t", last);
    }

    @Test
    void aValueThatCannotBeReadIsNotReplacedByTheFillValue() {
        TimeSeriesObject broken = series("broken", 1.0, 2.0);
        broken.yValues.set(1, null);
        MeasurementTable table = table(series("long", 1.0, 2.0), broken);
        assertThrows(NullPointerException.class, table::toString);
    }

    @Test
    void writingATableWritesNothingToStandardOutputOrError() {
        MeasurementTable table = table(series("1", 1.0, 2.0), series("2", 3.0));
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(sink);
        System.setErr(sink);
        try {
            table.writeToFile(dir.resolve("table.dat").toFile());
            table.toSignificanzString();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    private static MeasurementTable table(TimeSeriesObject... rows) {
        MeasurementTable table = new MeasurementTable();
        table.setMessReihen(rows);
        return table;
    }

    private static TimeSeriesObject series(String label, double... y) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel(label);
        for (int i = 0; i < y.length; i++) {
            row.addValuePair(i + 1.0, y[i]);
        }
        return row;
    }
}
