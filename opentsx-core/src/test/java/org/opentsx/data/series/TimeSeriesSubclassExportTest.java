package org.opentsx.data.series;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesSubclassExportTest {
    private static final int SIZE = 7;
    private static final String LABEL = "Signal, kept";
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "DOT", "COMMA"})
    void fftExportPreservesRawPrecisionAndEnglishHeaders(String mode) throws Exception {
        TimeSeriesObjectFFT row = new TimeSeriesObjectFFT();
        metadata(row);
        row.getDecimalFormat_X().applyPattern("0.0");
        row.getDecimalFormat_Y().applyPattern("0.0");
        for (int i = 0; i < SIZE; i++) row.addValuePair(x(i), y(i));
        String output = write(row, mode);
        assertMetadata(output);
        assertTrue(output.contains("# Time series"));
        assertTrue(output.contains("# Class : class org.opentsx.data.series.TimeSeriesObjectFFT"));
        assertTrue(output.contains("# Number of value pairs = 7"));
        List<String> lines = output.lines().filter(line -> line.matches("^[-0-9].*")).toList();
        assertEquals(SIZE, lines.size());
        for (int i = 0; i < SIZE; i++) {
            assertEquals(raw(x(i), mode) + " " + raw(y(i), mode), lines.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "DOT", "COMMA"})
    void markedExportPreservesMarkersAndNumericPatterns(String mode) throws Exception {
        MarkableTimeSeriesObject row = new MarkableTimeSeriesObject();
        metadata(row);
        row.getDecimalFormat_X().applyPattern("0.000000000000");
        row.getDecimalFormat_Y().applyPattern("0.000000000000");
        row.getDecimalFormat_X().setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.GERMANY));
        row.getDecimalFormat_Y().setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.GERMANY));
        for (int i = 0; i < SIZE; i++) row.addValuePair(x(i), y(i), "marker-" + i + ", kept");
        String output = write(row, mode);
        assertMetadata(output);
        List<String> lines = output.lines().filter(line -> line.matches("^[-0-9].*")).toList();
        assertEquals(SIZE, lines.size());
        DecimalFormat expected = new DecimalFormat("0.000000000000", symbols(mode));
        for (int i = 0; i < SIZE; i++) {
            assertEquals(expected.format(x(i)) + "\t" + expected.format(y(i))
                    + "\tmarker-" + i + ", kept", lines.get(i));
        }
        assertEquals(',', row.getDecimalFormat_X().getDecimalFormatSymbols().getDecimalSeparator());
        assertEquals(',', row.getDecimalFormat_Y().getDecimalFormatSymbols().getDecimalSeparator());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "DOT", "COMMA"})
    void qualifiedExportPreservesQualityFlagsAndRawValues(String mode) throws Exception {
        QualifiedTimeSeriesObject row = new QualifiedTimeSeriesObject();
        metadata(row);
        for (int i = 0; i < SIZE; i++) {
            row.addValuePair(x(i), y(i));
            row.correct.set(i, i % 2 == 0);
        }
        String output = write(row, mode);
        assertMetadata(output);
        List<String> lines = output.lines().filter(line -> line.startsWith("[+]") || line.startsWith("[-]")).toList();
        assertEquals(SIZE, lines.size());
        for (int i = 0; i < SIZE; i++) {
            assertEquals("[" + (i % 2 == 0 ? "+" : "-") + "]  \t" + raw(x(i), mode)
                    + "\t " + raw(y(i), mode), lines.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "DOT", "COMMA"})
    void datedExportPreservesDateAndTimestampColumns(String mode) throws Exception {
        Zeitreihe row = new Zeitreihe();
        row.setLabel(LABEL);
        for (int i = 0; i < SIZE; i++) row.addValuePair(1577836800000.125 + i * 1000, y(i));
        String output = write(row, mode);
        assertTrue(output.contains(LABEL));
        List<String> lines = output.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(SIZE, lines.size());
        DateFormat dates = DateFormat.getDateTimeInstance();
        for (int i = 0; i < SIZE; i++) {
            double timestamp = (Double) row.xValues.get(i);
            assertEquals(dates.format(new Date((long) timestamp)) + "\t" + raw(y(i), mode)
                    + "\t (" + raw(timestamp, mode) + ")", lines.get(i));
        }
    }

    private String write(TimeSeriesObject row, String mode) throws Exception {
        Path file = directory.resolve("series.txt");
        if (mode.equals("DEFAULT")) row.writeToFile(file.toFile());
        else row.writeToFile(file.toFile(), mode.equals("COMMA") ? ',' : '.');
        return Files.readString(file);
    }

    private static void metadata(TimeSeriesObject row) {
        row.setLabel(LABEL);
        row.setAddinfo("Details, kept");
        row.addComment("Comment, kept");
    }

    private static void assertMetadata(String output) {
        assertTrue(output.contains(LABEL));
        assertTrue(output.contains("Details, kept"));
        assertTrue(output.contains("Comment, kept"));
    }

    private static DecimalFormatSymbols symbols(String mode) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.GERMANY);
        if (!mode.equals("DEFAULT")) symbols.setDecimalSeparator(mode.equals("COMMA") ? ',' : '.');
        return symbols;
    }

    private static String raw(double value, String mode) {
        String text = Double.toString(value);
        return mode.equals("COMMA") ? text.replace('.', ',') : text;
    }

    private static double x(int index) {
        return 0.12345678912345678 + index;
    }

    private static double y(int index) {
        return -0.9876543219876543 - index;
    }
}
