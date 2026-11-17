package org.opentsx.app.bucketanalyser;

import org.jfree.chart.ChartRenderingInfo;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TSOperationControlerPanelSaveTest {

    @TempDir
    Path home;

    TSOperationControlerPanel panel;
    Path base;
    Path table;
    Path image;
    Path caption;
    String previousHome;
    Locale previousLocale;
    List<Window> previousWindows;
    final List<String> errors = new ArrayList<>();
    final List<String> titles = new ArrayList<>();
    final List<Integer> messageTypes = new ArrayList<>();
    final AWTEventListener dialogs = this::closeErrorDialog;

    @BeforeEach
    void createPanel() throws Exception {
        previousHome = System.getProperty("user.home");
        previousLocale = Locale.getDefault(Locale.Category.FORMAT);
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        base = home.resolve("exports");
        table = base.resolve("result/result.mwt.tsv");
        image = base.resolve("result.png");
        caption = base.resolve("caption_result.tex");
        SwingUtilities.invokeAndWait(() -> {
            previousWindows = Arrays.asList(Window.getWindows());
            panel = new TSOperationControlerPanel();
            panel.chart = new JFreeChart("Measured chart", new XYPlot());
            panel.rows = new Vector<>();
            TimeSeriesObject row = new TimeSeriesObject("Measured series");
            row.addValuePair(1.25, 4.5);
            row.addValuePair(2.5, 9.0);
            row.addValuePair(3.75, 13.5);
            panel.rows.add(row);
            panel.statsAndComments = new JTextArea("Caption \u03bb\n");
            field("jTextField2", JTextField.class).setText("result");
            field("jTextField3", JTextField.class).setText(base.toString() + "/");
            Toolkit.getDefaultToolkit().addAWTEventListener(dialogs, AWTEvent.WINDOW_EVENT_MASK);
        });
    }

    @AfterEach
    void restoreDesktopState() throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                Toolkit.getDefaultToolkit().removeAWTEventListener(dialogs);
                for (Window window : Window.getWindows()) {
                    if (!previousWindows.contains(window)) window.dispose();
                }
            });
        } finally {
            System.setProperty("user.home", previousHome);
            Locale.setDefault(Locale.Category.FORMAT, previousLocale);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesTablePngAndOptionalUtf8CaptionWithoutChangingInput(boolean includeCaption) throws Exception {
        if (!includeCaption) panel.statsAndComments = null;
        List<?> coordinates = new ArrayList<>(panel.rows.firstElement().xValues);
        List<?> values = new ArrayList<>(panel.rows.firstElement().yValues);
        save();
        assertTable();
        assertPng(image);
        if (includeCaption) {
            assertArrayEquals("Caption \u03bb\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(caption));
        } else {
            assertFalse(Files.exists(caption));
        }
        assertEquals(coordinates, panel.rows.firstElement().xValues);
        assertEquals(values, panel.rows.firstElement().yValues);
        assertTrue(errors.isEmpty());
        assertNoTemporaryFiles();
    }

    @Test
    void preservesTheConfiguredFolderWithHyphensSpacesAndUnicode() throws Exception {
        base = home.resolve("chart-files with spaces \u03bb");
        table = base.resolve("result/result.mwt.tsv");
        image = base.resolve("result.png");
        caption = base.resolve("caption_result.tex");
        SwingUtilities.invokeAndWait(() -> field("jTextField3", JTextField.class).setText(base.toString() + "/"));
        save();
        assertTable();
        assertPng(image);
        assertEquals("Caption \u03bb\n", Files.readString(caption));
        assertNoTemporaryFiles();
    }

    @Test
    void savesRelativeOutputsWhenTheBaseTextboxIsEmpty() throws Exception {
        String name = "nativesave" + home.getFileName().toString().replaceAll("[^a-zA-Z0-9]", "");
        table = Path.of(name, name + ".mwt.tsv");
        image = Path.of(name + ".png");
        caption = Path.of("caption_" + name + ".tex");
        SwingUtilities.invokeAndWait(() -> {
            field("jTextField2", JTextField.class).setText(name);
            field("jTextField3", JTextField.class).setText("");
        });
        try {
            save();
            assertTable();
            assertPng(image);
            assertEquals("Caption \u03bb\n", Files.readString(caption));
            assertTrue(errors.isEmpty());
            try (var paths = Files.list(table.getParent())) {
                assertEquals(List.of(table.getFileName()), paths.map(Path::getFileName).toList());
            }
        } finally {
            Files.deleteIfExists(table);
            Files.deleteIfExists(image);
            Files.deleteIfExists(caption);
            Files.deleteIfExists(table.getParent());
        }
    }

    @Test
    void tableWriteFailureIsReportedWithoutAttemptingLaterOutputs() throws Exception {
        Files.writeString(base, "Blocked parent");
        IOException failure = saveFailure("table", table, List.of());
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("Blocked parent", Files.readString(base));
        assertFalse(Files.exists(image));
        assertFalse(Files.exists(caption));
        assertNoTemporaryFiles();
    }

    @Test
    void tablePreparationFailurePreservesExistingExport() throws Exception {
        Files.createDirectories(table.getParent());
        Files.writeString(table, "Previous table");
        Files.writeString(image, "Previous image");
        Files.writeString(caption, "Previous caption");
        panel.rows.add(null);
        IOException failure = saveFailure("table", table, List.of());
        assertNotNull(failure.getCause());
        assertEquals("Previous table", Files.readString(table));
        assertEquals("Previous image", Files.readString(image));
        assertEquals("Previous caption", Files.readString(caption));
        assertNoTemporaryFiles();
    }

    @Test
    void chartWriteFailureReportsTheCompletedTableAndPreservesCaption() throws Exception {
        block(image);
        Files.writeString(caption, "Previous caption");
        IOException failure = saveFailure("chart", image, List.of(table));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTable();
        assertEquals("Keep marker", Files.readString(image.resolve("marker")));
        assertEquals("Previous caption", Files.readString(caption));
        assertNoTemporaryFiles();
    }

    @Test
    void captionWriteFailureReportsTheCompletedTableAndChart() throws Exception {
        block(caption);
        IOException failure = saveFailure("caption", caption, List.of(table, image));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTable();
        assertPng(image);
        assertEquals("Keep marker", Files.readString(caption.resolve("marker")));
        assertNoTemporaryFiles();
    }

    @Test
    void chartRenderingFailurePreservesThePreviousImageAndCaption() throws Exception {
        Files.createDirectories(base);
        Files.writeString(image, "Previous image");
        Files.writeString(caption, "Previous caption");
        IllegalStateException renderingFailure = new IllegalStateException("Chart rendering failed");
        panel.chart = failingChart(renderingFailure);
        IOException failure = saveFailure("chart", image, List.of(table));
        assertSame(renderingFailure, failure.getCause());
        assertTable();
        assertEquals("Previous image", Files.readString(image));
        assertEquals("Previous caption", Files.readString(caption));
        assertNoTemporaryFiles();
    }

    @Test
    void malformedCaptionReportsPartialOutputAndPreservesThePreviousCaption() throws Exception {
        Files.createDirectories(base);
        Files.writeString(caption, "Previous caption");
        panel.statsAndComments.setText("Malformed \uD800 caption");
        IOException failure = saveFailure("caption", caption, List.of(table, image));
        assertInstanceOf(CharacterCodingException.class, failure.getCause());
        assertTable();
        assertPng(image);
        assertEquals("Previous caption", Files.readString(caption));
        assertNoTemporaryFiles();
    }

    @Test
    void pngHelperReportsParentFailures() throws Exception {
        Files.writeString(base, "Blocked parent");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> panel.storeChartImageAsPNG(panel.chart, base.toFile(), "result"));
        assertNotNull(failure.getCause());
        assertEquals("Blocked parent", Files.readString(base));
        assertNoTemporaryFiles();
    }

    @Test
    void pngHelperCreatesMissingParentsAndKeepsTheImageDimensions() throws Exception {
        panel.storeChartImageAsPNG(panel.chart, base.toFile(), "result");
        assertPng(image);
        assertNoTemporaryFiles();
    }

    @Test
    void pngHelperReportsReplacementFailureAndCleansUp() throws Exception {
        block(image);
        assertThrows(UncheckedIOException.class,
                () -> panel.storeChartImageAsPNG(panel.chart, base.toFile(), "result"));
        assertEquals("Keep marker", Files.readString(image.resolve("marker")));
        assertNoTemporaryFiles();
    }

    @Test
    void pngHelperPropagatesRenderingFailureWithoutReplacingExistingData() throws Exception {
        Files.createDirectories(base);
        Files.writeString(image, "Previous image");
        IllegalStateException renderingFailure = new IllegalStateException("Chart rendering failed");
        assertSame(renderingFailure, assertThrows(IllegalStateException.class,
                () -> panel.storeChartImageAsPNG(failingChart(renderingFailure), base.toFile(), "result")));
        assertEquals("Previous image", Files.readString(image));
        assertNoTemporaryFiles();
    }

    @Test
    void pngHelperRejectsDestinationSymlinksAndPreservesTheReferent() throws Exception {
        Files.createDirectories(base);
        Path original = base.resolve("original.png");
        Files.writeString(original, "Previous image");
        Files.createSymbolicLink(image, original.getFileName());
        assertThrows(UncheckedIOException.class,
                () -> panel.storeChartImageAsPNG(panel.chart, base.toFile(), "result"));
        assertTrue(Files.isSymbolicLink(image));
        assertEquals("Previous image", Files.readString(original));
        assertNoTemporaryFiles();
    }

    @Test
    void pngHelperRetainsExistingAccessPermissions() throws Exception {
        Files.createDirectories(base);
        Files.writeString(image, "Previous image");
        var view = Files.getFileAttributeView(image, PosixFileAttributeView.class);
        if (view != null) {
            var permissions = PosixFilePermissions.fromString("rw-r-----");
            Files.setPosixFilePermissions(image, permissions);
            panel.storeChartImageAsPNG(panel.chart, base.toFile(), "result");
            assertEquals(permissions, Files.getPosixFilePermissions(image));
        } else {
            panel.storeChartImageAsPNG(panel.chart, base.toFile(), "result");
        }
        assertPng(image);
        assertNoTemporaryFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"table", "chart", "caption"})
    void saveButtonShowsTheFailureAndCompletedOutputs(String stage) throws Exception {
        Path target = switch (stage) {
            case "table" -> table;
            case "chart" -> image;
            case "caption" -> caption;
            default -> throw new IllegalArgumentException(stage);
        };
        block(target);
        SwingUtilities.invokeAndWait(() -> field("jButton1", JButton.class).doClick(0));
        assertEquals(List.of("Save failed"), titles);
        assertEquals(List.of(JOptionPane.ERROR_MESSAGE), messageTypes);
        assertEquals(1, errors.size());
        List<Path> completed = switch (stage) {
            case "table" -> List.of();
            case "chart" -> List.of(table);
            case "caption" -> List.of(table, image);
            default -> throw new IllegalArgumentException(stage);
        };
        assertFailureMessage(errors.get(0), stage, target, completed);
        assertEquals("Keep marker", Files.readString(target.resolve("marker")));
        if (stage.equals("table")) assertFalse(Files.exists(image));
        if (!stage.equals("caption")) assertFalse(Files.exists(caption));
        assertNoTemporaryFiles();
    }

    @Test
    void saveButtonShowsSuppressedFailuresAlongsideThePrimaryFailureAndCompletedTable() throws Exception {
        Files.createDirectories(base);
        Files.writeString(image, "Previous image");
        Files.writeString(caption, "Previous caption");
        IllegalStateException renderingFailure = new IllegalStateException("Chart rendering failed");
        IOException cleanupFailure = new IOException("Temporary export could not be removed: "
                + home.resolve(".opentsx-export-left.tmp"));
        renderingFailure.addSuppressed(cleanupFailure);
        panel.chart = failingChart(renderingFailure);
        IOException failure = saveFailure("chart", image, List.of(table));
        assertSame(renderingFailure, failure.getCause());
        assertArrayEquals(new Throwable[]{cleanupFailure}, failure.getCause().getSuppressed());
        SwingUtilities.invokeAndWait(() -> field("jButton1", JButton.class).doClick(0));
        assertEquals(List.of("Save failed"), titles);
        assertEquals(List.of(JOptionPane.ERROR_MESSAGE), messageTypes);
        assertEquals(1, errors.size());
        String message = errors.get(0);
        assertFailureMessage(message, "chart", image, List.of(table));
        assertTrue(message.contains("Chart rendering failed"), message);
        assertTrue(message.contains("Additional failure: " + cleanupFailure), message);
        assertTable();
        assertEquals("Previous image", Files.readString(image));
        assertEquals("Previous caption", Files.readString(caption));
        assertNoTemporaryFiles();
    }

    private void save() throws Exception {
        AtomicReference<IOException> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                panel.save();
            } catch (IOException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    private IOException saveFailure(String stage, Path target, List<Path> completed) {
        IOException failure = assertThrows(IOException.class, this::save);
        assertFailureMessage(failure.getMessage(), stage, target, completed);
        return failure;
    }

    private void assertFailureMessage(String message, String stage, Path target, List<Path> completed) {
        assertTrue(message.contains("Failed to save " + stage + " to " + target.toAbsolutePath()), message);
        if (completed.isEmpty()) {
            assertTrue(message.contains("\n\nNo files were saved."), message);
        } else {
            String paths = String.join("\n", completed.stream().map(path -> path.toAbsolutePath().toString()).toList());
            assertTrue(message.contains("\n\nSaved in this attempt:\n" + paths), message);
        }
    }

    private void assertTable() throws Exception {
        List<String> values = Files.readAllLines(table).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(List.of("1.25000\t4.50000\t", "2.50000\t9.00000\t", "3.75000\t13.50000\t"), values);
    }

    private void assertPng(Path path) throws Exception {
        BufferedImage png = ImageIO.read(path.toFile());
        assertNotNull(png);
        assertEquals(800, png.getWidth());
        assertEquals(600, png.getHeight());
        int first = png.getRGB(0, 0);
        assertTrue(IntStream.range(0, png.getWidth() * png.getHeight())
                .anyMatch(pixel -> png.getRGB(pixel % png.getWidth(), pixel / png.getWidth()) != first));
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var paths = Files.walk(home)) {
            assertEquals(List.of(), paths.filter(path -> path.getFileName().toString().startsWith(".opentsx-export-"))
                    .toList());
        }
    }

    private void block(Path path) throws Exception {
        Files.createDirectories(path);
        Files.writeString(path.resolve("marker"), "Keep marker");
    }

    private JFreeChart failingChart(IllegalStateException failure) {
        return new JFreeChart("Failed chart", new XYPlot()) {
            @Override
            public BufferedImage createBufferedImage(int width, int height, ChartRenderingInfo info) {
                throw failure;
            }
        };
    }

    private void closeErrorDialog(AWTEvent event) {
        if (event.getID() != WindowEvent.WINDOW_OPENED) return;
        JOptionPane pane = findOptionPane((Container) event.getSource());
        if (pane == null) return;
        errors.add(String.valueOf(pane.getMessage()));
        titles.add(((JDialog) event.getSource()).getTitle());
        messageTypes.add(pane.getMessageType());
        pane.setValue(JOptionPane.OK_OPTION);
    }

    private JOptionPane findOptionPane(Container parent) {
        if (parent instanceof JOptionPane pane) return pane;
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) {
                JOptionPane pane = findOptionPane(container);
                if (pane != null) return pane;
            }
        }
        return null;
    }

    private <T> T field(String name, Class<T> type) {
        try {
            var field = TSOperationControlerPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(panel));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
