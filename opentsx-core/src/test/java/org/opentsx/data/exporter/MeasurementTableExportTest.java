package org.opentsx.data.exporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class MeasurementTableExportTest {

    @TempDir
    Path directory;

    private Locale originalLocale;

    @BeforeEach
    void useFixedNumericFormatting() {
        originalLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }

    @AfterEach
    void restoreNumericFormatting() {
        Locale.setDefault(Locale.Category.FORMAT, originalLocale);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exportsAllRowsWithTheExistingHeadersAndFillValue(boolean singleX) throws Exception {
        MeasurementTable table = table();
        table.singleX = singleX;
        Path file = directory.resolve("nested/table.tsv");
        table.writeToFile(file.toFile());
        String text = Files.readString(file);
        String prefix = "# Measurement export\n#\n# M e s s w e r t t a b e l l e  der Reihen:\n#"
                + "\n# First [3,3] Werte\n# Second [2,2] Werte\n#\n#\n#";
        String expected = singleX
                ? prefix + "Sample\tFirst values\tSecond values\t\n"
                + "1.50000\t10.25000\t20.50000\t\n"
                + "2.50000\t11.25000\t21.50000\t\n"
                + "3.50000\t12.25000\t-5.00000\t\n"
                : prefix + "Sample\tFirst values\tOther sample\tSecond values\t\n"
                + "1.50000\t10.25000\t1.50000\t20.50000\t\n"
                + "2.50000\t11.25000\t2.50000\t21.50000\t\n"
                + "3.50000\t12.25000\t3.50000\t-5.00000\t\n";
        assertEquals(expected, text);
        assertEquals(List.of(1.5, 2.5, 3.5), table.getMessReihen().get(0).xValues);
        assertEquals(List.of(20.5, 21.5), table.getMessReihen().get(1).yValues);
        assertEquals(List.of("table.tsv"), childNames(file.getParent()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void relativeFilenamesWorkWithBothWriterEntryPoints(int mode) throws Exception {
        Path file = Path.of("measurement-export-" + directory.getFileName() + ".tsv");
        try {
            write(table(), file, mode);
            assertTrue(Files.readString(file).startsWith("# Measurement export\n"));
            assertTrue(Files.readString(file).endsWith("12.25000\t-5.00000\t\n"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void parentPreparationAcceptsAFileWithoutAParentComponent() {
        assertDoesNotThrow(() -> table().createParrentFile(Path.of("table.tsv").toFile()));
    }

    @Test
    void parentPreparationReportsAnExistingFileInsteadOfIgnoringTheFailure() throws Exception {
        Path parent = directory.resolve("parent");
        Files.writeString(parent, "Keep parent bytes");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> table().createParrentFile(parent.resolve("table.tsv").toFile()));
        assertNotNull(failure.getCause());
        assertEquals("Keep parent bytes", Files.readString(parent));
    }

    @Test
    void filesystemRootIsReportedAsAnInvalidDestination() {
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> table().writeToFile(directory.toAbsolutePath().getRoot().toFile()));
        assertNotNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void parentCreationFailuresKeepTheirIoCauseAndExistingBytes(int mode) throws Exception {
        Path parent = directory.resolve("parent");
        Files.writeString(parent, "Keep parent bytes");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> write(table(), parent.resolve("table.tsv"), mode));
        assertNotNull(failure.getCause());
        assertEquals("Keep parent bytes", Files.readString(parent));
        assertEquals(List.of("parent"), childNames(directory));
    }

    @Test
    void failedReplacementPreservesTheDestinationAndRemovesTemporaryFiles() throws Exception {
        Path target = Files.createDirectory(directory.resolve("table.tsv"));
        Files.writeString(target.resolve("marker"), "Keep destination bytes");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> table().writeToFile(target.toFile()));
        assertNotNull(failure.getCause());
        assertEquals("Keep destination bytes", Files.readString(target.resolve("marker")));
        assertEquals(List.of("table.tsv"), childNames(directory));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void renderingFailuresPreserveAnExistingDestination(int mode) throws Exception {
        Path target = directory.resolve("table.tsv");
        Files.writeString(target, "Keep original table");
        MeasurementTable invalid = table();
        invalid.getMessReihen().get(0).xValues = null;
        assertThrows(RuntimeException.class, () -> write(invalid, target, mode));
        assertEquals("Keep original table", Files.readString(target));
        assertEquals(List.of("table.tsv"), childNames(directory));
    }

    @Test
    void renderingFailuresDoNotCreateTheOutputFolder() {
        Path parent = directory.resolve("missing");
        MeasurementTable invalid = table();
        invalid.getMessReihen().get(0).xValues = null;
        assertThrows(RuntimeException.class, () -> invalid.writeToFile(parent.resolve("table.tsv").toFile()));
        assertFalse(Files.exists(parent));
    }

    @Test
    void exportsUnicodeAsUtf8() throws Exception {
        MeasurementTable table = table();
        table.setHeader("# Temperature \u00b0C, change \u0394\n");
        Path target = directory.resolve("table.tsv");
        table.writeToFile(target.toFile());
        byte[] prefix = "# Temperature \u00b0C, change \u0394\n".getBytes(StandardCharsets.UTF_8);
        byte[] actual = Files.readAllBytes(target);
        assertArrayEquals(prefix, java.util.Arrays.copyOf(actual, prefix.length));
    }

    @Test
    void encodingFailuresPreserveAnExistingDestinationAndKeepTheCause() throws Exception {
        MeasurementTable table = table();
        table.setHeader("# Invalid \uD800\n");
        Path target = directory.resolve("table.tsv");
        Files.writeString(target, "Keep original table");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> table.writeToFile(target.toFile()));
        assertInstanceOf(CharacterCodingException.class, failure.getCause());
        assertEquals("Keep original table", Files.readString(target));
        assertEquals(List.of("table.tsv"), childNames(directory));
    }

    @Test
    void symbolicLinkDestinationsAreRejectedWithoutChangingTheirTargets() throws Exception {
        Path original = directory.resolve("original.tsv");
        Files.writeString(original, "Keep original table");
        Path link = Files.createSymbolicLink(directory.resolve("table.tsv"), original);
        assertThrows(UncheckedIOException.class, () -> table().writeToFile(link.toFile()));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("Keep original table", Files.readString(original));
        assertEquals(List.of("original.tsv", "table.tsv"), childNames(directory));
    }

    @Test
    void replacementRetainsExistingAccessPermissions() throws Exception {
        Path target = directory.resolve("table.tsv");
        Files.writeString(target, "Old table");
        var permissions = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (permissions != null) {
            var expected = PosixFilePermissions.fromString("rw-r-----");
            permissions.setPermissions(expected);
            table().writeToFile(target.toFile());
            assertEquals(expected, Files.getPosixFilePermissions(target));
        } else {
            table().writeToFile(target.toFile());
            assertTrue(Files.isReadable(target));
        }
        assertTrue(Files.readString(target).startsWith("# Measurement export\n"));
    }

    @Test
    void parentSymlinksAndTraversalRetainTheirFilesystemMeaning() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("folder"));
        Path nested = Files.createDirectory(folder.resolve("nested"));
        Path link = Files.createSymbolicLink(directory.resolve("shortcut"), nested);
        Path target = folder.resolve("table.tsv");
        Path other = directory.resolve("table.tsv");
        Files.writeString(target, "Old table");
        Files.writeString(other, "Keep other table");
        table().writeToFile(link.resolve("../table.tsv").toFile());
        assertTrue(Files.readString(target).startsWith("# Measurement export\n"));
        assertEquals("Keep other table", Files.readString(other));
    }

    private MeasurementTable table() {
        TimeSeriesObject first = new TimeSeriesObject("First");
        first.setLabel_X("Sample");
        first.setLabel_Y("First values");
        first.addValuePair(1.5, 10.25);
        first.addValuePair(2.5, 11.25);
        first.addValuePair(3.5, 12.25);
        TimeSeriesObject second = new TimeSeriesObject("Second");
        second.setLabel_X("Other sample");
        second.setLabel_Y("Second values");
        second.addValuePair(10.5, 20.5);
        second.addValuePair(11.5, 21.5);
        MeasurementTable table = new MeasurementTable();
        table.setHeader("# Measurement export\n");
        table.setMessReihen(new TimeSeriesObject[]{first, second});
        return table;
    }

    private void write(MeasurementTable table, Path file, int mode) {
        if (mode == 0) {
            table.writeToFile(file.toFile());
        } else {
            table.setLabel(file.toString());
            table.writeToFile();
        }
    }

    private List<String> childNames(Path parent) throws Exception {
        try (var paths = Files.list(parent)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
