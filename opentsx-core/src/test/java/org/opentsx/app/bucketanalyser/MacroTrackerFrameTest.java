package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MacroTrackerFrameTest {

    @Test
    void recordingATransformationPrintsNothing() {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
        } finally {
            System.setOut(stdout);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void recordingATransformationInTheOpenTrackerPrintsNothing() throws Exception {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.init("probe");
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
                MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("b", "c", "NORMALIZE"));
            });
        } finally {
            System.setOut(stdout);
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.frame.dispose();
                MacroTrackerFrame.frame = null;
            });
            PFDRecorder.getInstance().reset("New Flow");
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void addingASourcePrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> MacroTrackerFrame.addSource(TSBucketSource.getSource("probe")));

        assertEquals("", printed);
    }

    @Test
    void selectingANodePrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> {
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
            JTree tree = (JTree) field("jTree1");
            tree.setSelectionRow(tree.getRowCount() - 1);
        });

        assertEquals("", printed);
    }

    @Test
    void exportingJavaCodeCopiesItAndPrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> {
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
            click("jButton2ActionPerformed");
        });

        assertEquals("", printed);
        assertEquals("TSBucket tsbb = tsba.processBucket( \"DFA\", null );\n", clipboard());
    }

    @Test
    void exportingScalaCodeCopiesTheScalaCodeAndPrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> {
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
            click("jButton1ActionPerformed");
        });

        assertEquals("", printed);
        assertEquals("val rddb = rdda.processBucket( \"DFA\", null );\n", clipboard());
    }

    @Test
    void showingAChartPrintsNothing() throws Exception {
        Track track = new Track();
        JDialog[] dialog = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> dialog[0] = new JDialog());
        track._registerDialogToNode("probe", dialog[0]);
        boolean shown;
        String printed;
        try {
            printed = captured(() -> SwingUtilities.invokeAndWait(() -> track.showDialog("probe")));
            shown = dialog[0].isVisible();
        } finally {
            SwingUtilities.invokeAndWait(dialog[0]::dispose);
        }

        assertTrue(shown, "the chart was not shown");
        assertEquals("", printed);
    }

    @Test
    void aPfdStepThatCannotBeRecordedReachesTheCaller() throws Exception {
        RuntimeException[] thrown = new RuntimeException[1];
        String printed = inOpenTracker(() -> thrown[0] = assertThrows(IllegalStateException.class,
                () -> MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "x("))));

        assertEquals("x(: cannot record the PFD step (" + thrown[0].getCause() + ")", thrown[0].getMessage());
        assertEquals("", printed);
    }

    interface Step {
        void run() throws Exception;
    }

    private static String inOpenTracker(Step step) throws Exception {
        SwingUtilities.invokeAndWait(() -> MacroTrackerFrame.init("probe"));
        try {
            return captured(() -> SwingUtilities.invokeAndWait(() -> {
                try {
                    step.run();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.frame.dispose();
                MacroTrackerFrame.frame = null;
            });
            PFDRecorder.getInstance().reset("New Flow");
        }
    }

    private static String captured(Step step) throws Exception {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(sink);
            System.setErr(sink);
            step.run();
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static Object field(String name) throws ReflectiveOperationException {
        var declared = MacroTrackerFrame.class.getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(MacroTrackerFrame.frame);
    }

    private static void click(String action) throws ReflectiveOperationException {
        var method = MacroTrackerFrame.class.getDeclaredMethod(action, java.awt.event.ActionEvent.class);
        method.setAccessible(true);
        try {
            method.invoke(MacroTrackerFrame.frame, new Object[]{null});
        } catch (InvocationTargetException e) {
            fail(action + " threw", e.getCause());
        }
    }

    private static String clipboard() throws Exception {
        return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
    }
}
