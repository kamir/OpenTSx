package org.opentsx.data.series;

import org.opentsx.chart.panels.MessreihePanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesObjectTextExportTest {
    @TempDir
    Path directory;
    private boolean originalNoStatus;

    @BeforeEach
    void configureStatus() {
        originalNoStatus = TimeSeriesObject.noStatus;
        TimeSeriesObject.noStatus = false;
    }

    @AfterEach
    void restoreStatus() {
        TimeSeriesObject.noStatus = originalNoStatus;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 4, 5, 8})
    void previewIsBoundedAndSafeForShortSeries(int size) {
        TimeSeriesObject row = series(size);
        String text = row.toString();
        assertEquals(expectedRows(size, '.', 5), dataRows(text));
        assertEquals(size > 5, text.contains("..."));
        assertEquals(size, row.xValues.size());
        assertEquals(size, row.yValues.size());
        assertTrue(text.contains("Measured, series"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 4, 5, 8})
    void defaultExportWritesEveryPointAndMetadata(int size) throws Exception {
        TimeSeriesObject row = series(size);
        Path file = directory.resolve("nested/series.txt");
        row.writeToFile(file.toFile());
        assertExport(row, file, size, '.');
    }

    @ParameterizedTest
    @CsvSource({"0,.", "1,.", "2,.", "4,.", "5,.", "8,.",
            "0,COMMA", "1,COMMA", "2,COMMA", "4,COMMA", "5,COMMA", "8,COMMA"})
    void separatorExportChangesOnlyNumericColumns(int size, String separatorName) throws Exception {
        char separator = separatorName.equals("COMMA") ? ',' : '.';
        TimeSeriesObject row = series(size);
        row.decimalFormat_X.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.GERMANY));
        row.decimalFormat_Y.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.GERMANY));
        Path file = directory.resolve("series.txt");
        row.writeToFile(file.toFile(), separator);
        assertExport(row, file, size, separator);
        assertEquals(',', row.decimalFormat_X.getDecimalFormatSymbols().getDecimalSeparator());
        assertEquals(',', row.decimalFormat_Y.getDecimalFormatSymbols().getDecimalSeparator());
    }

    @Test
    void disabledStatusDoesNotBreakPreviewOrExport() throws Exception {
        TimeSeriesObject.noStatus = true;
        TimeSeriesObject row = series(5);
        assertEquals(5, dataRows(row.toString()).size());
        SwingUtilities.invokeAndWait(() -> assertDoesNotThrow(() -> new MessreihePanel().setMessreihe(row)));
        Path file = directory.resolve("series.txt");
        row.writeToFile(file.toFile());
        assertEquals(5, dataRows(Files.readString(file)).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-value", "null-x", "null-y", "null-value"})
    void invalidSeriesPreservesExistingDestination(String kind) throws Exception {
        TimeSeriesObject row = series(8);
        switch (kind) {
            case "missing-value" -> row.yValues.remove(7);
            case "null-x" -> row.xValues = null;
            case "null-y" -> row.yValues = null;
            case "null-value" -> row.yValues.set(7, null);
            default -> throw new IllegalArgumentException(kind);
        }
        Path file = directory.resolve("existing.txt");
        for (int mode = 0; mode < 3; mode++) {
            Files.writeString(file, "Existing export, unchanged\n");
            int selectedMode = mode;
            assertThrows(IllegalArgumentException.class, () -> write(row, file, selectedMode));
            assertEquals("Existing export, unchanged\n", Files.readString(file));
        }
        assertEquals(List.of("existing.txt"), childNames(directory));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void parentFileFailureIsReportedAndPreservesParent(int mode) throws Exception {
        Path parent = directory.resolve("parent");
        Files.writeString(parent, "Parent marker");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> write(series(8), parent.resolve("series.txt"), mode));
        assertNotNull(failure.getCause());
        assertEquals("Parent marker", Files.readString(parent));
        assertEquals(List.of("parent"), childNames(directory));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void failedReplacementPreservesDirectoryAndRemovesTemporaryFile(int mode) throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.writeString(target.resolve("marker"), "Keep this marker");
        assertThrows(UncheckedIOException.class, () -> write(series(8), target, mode));
        assertEquals("Keep this marker", Files.readString(target.resolve("marker")));
        assertEquals(List.of("target"), childNames(directory));
    }

    @Test
    void nullSeparatorPreservesExistingFile() throws Exception {
        Path file = directory.resolve("existing.txt");
        Files.writeString(file, "Keep this export");
        assertThrows(IllegalArgumentException.class, () -> series(8).writeToFile(file.toFile(), null));
        assertEquals("Keep this export", Files.readString(file));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void fftExportPreservesRawPrecision(int mode) throws Exception {
        TimeSeriesObjectFFT row = new TimeSeriesObjectFFT("FFT, result");
        for (int i = 0; i < 8; i++) row.addValuePair(i + 0.1234567890123, Math.PI + i);
        Path file = directory.resolve("fft.txt");
        write(row, file, mode);
        char separator = mode == 2 ? ',' : '.';
        List<String> actual = dataRows(Files.readString(file));
        assertEquals(8, actual.size());
        for (int i = 0; i < 8; i++) {
            assertEquals((Double.toString(i + 0.1234567890123) + " " + Double.toString(Math.PI + i))
                    .replace('.', separator), actual.get(i));
        }
        assertTrue(Files.readString(file).contains("FFT, result"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void relativeDestinationHasNoParentRequirement(int mode) throws Exception {
        Path file = Path.of("legacy-export-" + directory.getFileName() + ".txt");
        assertFalse(Files.exists(file));
        try {
            write(series(8), file, mode);
            assertEquals(8, dataRows(Files.readString(file)).size());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void symbolicLinkDestinationIsRejectedWithoutChangingReferent(int mode) throws Exception {
        Path original = directory.resolve("original.txt");
        Files.writeString(original, "Keep original bytes");
        Path link = Files.createSymbolicLink(directory.resolve("link.txt"), original);
        assertThrows(UncheckedIOException.class, () -> write(series(8), link, mode));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("Keep original bytes", Files.readString(original));
        assertEquals(List.of("link.txt", "original.txt"), childNames(directory));
    }

    @Test
    void replacementRetainsExistingAccessPermissions() throws Exception {
        Path file = directory.resolve("existing.txt");
        Files.writeString(file, "Old export");
        var permissions = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
        if (permissions != null) {
            var expected = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");
            permissions.setPermissions(expected);
            series(8).writeToFile(file.toFile());
            assertEquals(expected, Files.getPosixFilePermissions(file));
        } else {
            series(8).writeToFile(file.toFile());
            assertTrue(Files.isReadable(file));
        }
        assertEquals(8, dataRows(Files.readString(file)).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 4, 5, 8})
    void nativePreviewAcceptsShortSeriesOnEventDispatchThread(int size) throws Exception {
        TimeSeriesObject row = series(size);
        SwingUtilities.invokeAndWait(() -> {
            MessreihePanel panel = new MessreihePanel();
            assertDoesNotThrow(() -> panel.setMessreihe(row));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void encodingFailurePreservesExistingFileAndRemovesTemporaryFile(int mode) throws Exception {
        Path file = directory.resolve("existing.txt");
        Files.writeString(file, "Original export");
        TimeSeriesObject row = series(8);
        row.setLabel("Invalid \uD800");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> write(row, file, mode));
        assertInstanceOf(java.nio.charset.CharacterCodingException.class, failure.getCause());
        assertEquals("Original export", Files.readString(file));
        assertEquals(List.of("existing.txt"), childNames(directory));
    }

    @Test
    void parentSymlinkAndParentTraversalKeepFilesystemMeaning() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("folder"));
        Path nested = Files.createDirectory(folder.resolve("nested"));
        Path link = Files.createSymbolicLink(directory.resolve("shortcut"), nested);
        Path expected = folder.resolve("series.txt");
        Path other = directory.resolve("series.txt");
        Files.writeString(expected, "Old target");
        Files.writeString(other, "Do not change this file");
        series(8).writeToFile(link.resolve("../series.txt").toFile());
        assertEquals(8, dataRows(Files.readString(expected)).size());
        assertEquals("Do not change this file", Files.readString(other));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void filesystemRootIsReportedAsAnInvalidDestination(int mode) {
        assertThrows(UncheckedIOException.class, () -> write(series(8), directory.toAbsolutePath().getRoot(), mode));
    }

    private TimeSeriesObject series(int size) {
        TimeSeriesObject row = new TimeSeriesObject("Measured, series");
        row.addinfo = "Metadata, preserved";
        row.addComment("Comment, preserved");
        row.decimalFormat_X = new DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.US));
        row.decimalFormat_Y = new DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.US));
        for (int i = 0; i < size; i++) row.addValuePair(i + 0.125, i + 10.75);
        return row;
    }

    private void assertExport(TimeSeriesObject row, Path file, int size, char separator) throws Exception {
        String text = Files.readString(file);
        assertEquals(expectedRows(size, separator, size), dataRows(text));
        assertFalse(text.contains("..."));
        assertTrue(text.contains("Measured, series"));
        assertTrue(text.contains("Metadata, preserved"));
        assertTrue(text.contains("Comment, preserved"));
        assertEquals(size, row.xValues.size());
        assertEquals(size, row.yValues.size());
    }

    private List<String> expectedRows(int size, char separator, int limit) {
        return java.util.stream.IntStream.range(0, Math.min(size, limit))
                .mapToObj(i -> String.format(Locale.ROOT, "%.3f %.3f", i + 0.125, i + 10.75)
                        .replace('.', separator)).toList();
    }

    private List<String> dataRows(String text) {
        return text.lines().filter(line -> line.matches("^[0-9].*")).toList();
    }

    private List<String> childNames(Path path) throws Exception {
        try (var children = Files.list(path)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void write(TimeSeriesObject row, Path file, int mode) {
        switch (mode) {
            case 0 -> row.writeToFile(file.toFile());
            case 1 -> row.writeToFile(file.toFile(), '.');
            case 2 -> row.writeToFile(file.toFile(), ',');
            default -> throw new IllegalArgumentException(Integer.toString(mode));
        }
    }
}
