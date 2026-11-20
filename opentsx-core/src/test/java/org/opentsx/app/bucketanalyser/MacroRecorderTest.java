package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JTree;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class MacroRecorderTest {

    interface Main {
        void run() throws Exception;
    }

    @Test
    void showChartOnTheComponentsAndShuffledNodesOfMacroRecorderShowsTheirOwnCharts() throws Exception {
        String track = MacroRecorder.label_of_TRACK;
        double samplingRate = MacroRecorder.samplingRate;
        try {
            assertEquals(List.of("Components", "Shuffled"), nodesShowingTheirOwnChart(() -> MacroRecorder.main(new String[0])));
        } finally {
            MacroRecorder.label_of_TRACK = track;
            MacroRecorder.samplingRate = samplingRate;
        }
    }

    @Test
    void showChartOnTheComponentsAndShuffledNodesOfMacroRecorder2ShowsTheirOwnCharts() throws Exception {
        LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
        try {
            assertEquals(List.of("Components", "Shuffled"), nodesShowingTheirOwnChart(() -> MacroRecorder2.main(new String[0])));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(lookAndFeel);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Test
    void aLookAndFeelThatCannotBeSetIsLoggedAtWarnWithItsCauseAndTheCurrentOneStays() throws Exception {
        LookAndFeel current = UIManager.getLookAndFeel();
        LookAndFeel unsupported = new MetalLookAndFeel() {
            @Override
            public boolean isSupportedLookAndFeel() {
                return false;
            }
        };
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, UTF_8));
        try {
            MacroRecorder2.setLookAndFeel(unsupported);
        } finally {
            System.setErr(err);
        }

        String log = captured.toString(UTF_8);
        assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - look and feel not set; the workbench keeps"
                + " the current one lookAndFeel=" + unsupported.getClass().getName() + " cause=" + unsupported
                + " not supported on this platform"), log);
        assertEquals(1, log.lines().count(), log);
        assertSame(current, UIManager.getLookAndFeel());
    }

    private static List<String> nodesShowingTheirOwnChart(Main main) throws Exception {
        List<String> shown = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    main.run();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                for (String node : List.of("Components", "Shuffled")) {
                    JDialog chart = MacroTrackerFrame.frame.track.dialogs.get(node);
                    chart.setVisible(false);
                    JDialog later = new JDialog();
                    MacroTrackerFrame._registerDialogToNode(node, later);
                    select(MacroTrackerFrame.frame.track.nodes.get(node));
                    showChart();
                    if (chart.isVisible() && !later.isVisible()) {
                        shown.add(node);
                    }
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
                MacroTrackerFrame.frame = null;
            });
            PFDRecorder.getInstance().reset("New Flow");
        }
        return shown;
    }

    private static void select(DefaultMutableTreeNode node) {
        try {
            var declared = MacroTrackerFrame.class.getDeclaredField("jTree1");
            declared.setAccessible(true);
            ((JTree) declared.get(MacroTrackerFrame.frame)).setSelectionPath(new TreePath(node.getPath()));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void showChart() {
        try {
            var method = MacroTrackerFrame.class.getDeclaredMethod("jButton3ActionPerformed", java.awt.event.ActionEvent.class);
            method.setAccessible(true);
            method.invoke(MacroTrackerFrame.frame, new Object[]{null});
        } catch (InvocationTargetException e) {
            fail("show Chart threw", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
