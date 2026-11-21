package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JTree;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
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

    @Test
    void aLookAndFeelWhoseDefaultsFailIsLoggedAtWarnAndTheCurrentOneIsSetBack() throws Exception {
        LookAndFeel current = UIManager.getLookAndFeel();
        try {
            String log = stderrOf(() -> MacroRecorder2.setLookAndFeel(new FailingDefaults()));

            assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - look and feel not set; the workbench"
                    + " keeps the current one lookAndFeel=" + FailingDefaults.class.getName()
                    + " cause=no defaults"), log);
            assertEquals(1, log.lines().count(), log);
            assertSame(current, UIManager.getLookAndFeel());
        } finally {
            UIManager.setLookAndFeel(current);
        }
    }

    @Test
    void aLookAndFeelThatFailsToInitializeIsLoggedAtWarnAndTheCurrentOneIsSetBack() throws Exception {
        LookAndFeel current = UIManager.getLookAndFeel();
        try {
            String log = stderrOf(() -> MacroRecorder2.setLookAndFeel(new FailingInitialize()));

            assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - look and feel not set; the workbench"
                    + " keeps the current one lookAndFeel=" + FailingInitialize.class.getName()
                    + " cause=cannot initialize"), log);
            assertEquals(1, log.lines().count(), log);
            assertSame(current, UIManager.getLookAndFeel());
        } finally {
            UIManager.setLookAndFeel(current);
        }
    }

    @Test
    void aFailingLookAndFeelWithoutOneBeforeInstallsTheCrossPlatformOne() throws Exception {
        LookAndFeel current = UIManager.getLookAndFeel();
        try {
            UIManager.setLookAndFeel((LookAndFeel) null);

            String log = stderrOf(() -> MacroRecorder2.setLookAndFeel(new FailingDefaults()));

            assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - look and feel not set; the workbench"
                    + " uses the cross-platform one lookAndFeel=" + FailingDefaults.class.getName() + " crossPlatform="
                    + UIManager.getCrossPlatformLookAndFeelClassName() + " cause=no defaults"), log);
            assertEquals(1, log.lines().count(), log);
            assertEquals(UIManager.getCrossPlatformLookAndFeelClassName(),
                    UIManager.getLookAndFeel().getClass().getName());
        } finally {
            UIManager.setLookAndFeel(current);
        }
    }

    @Test
    void aCurrentLookAndFeelThatCannotBeSetBackIsNamedWithTheFailureAsSuppressed() throws Exception {
        LookAndFeel current = UIManager.getLookAndFeel();
        InitializesOnce before = new InitializesOnce();
        try {
            UIManager.setLookAndFeel(before);

            String log = stderrOf(() -> MacroRecorder2.setLookAndFeel(new FailingDefaults()));

            assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - look and feel not set, and setting"
                    + " back the one before failed lookAndFeel=" + FailingDefaults.class.getName() + " before="
                    + InitializesOnce.class.getName() + " cause=no defaults; suppressed"
                    + " java.lang.IllegalStateException: cannot initialize again"), log);
            assertEquals(1, log.lines().count(), log);
        } finally {
            UIManager.setLookAndFeel(current);
        }
    }

    @Test
    void aCrossPlatformLookAndFeelThatCannotBeInstalledIsNamedWithTheFailureAsSuppressed() throws Exception {
        LookAndFeel current = UIManager.getLookAndFeel();
        String crossPlatform = System.getProperty("swing.crossplatformlaf");
        try {
            UIManager.setLookAndFeel((LookAndFeel) null);
            System.setProperty("swing.crossplatformlaf", FailingCrossPlatform.class.getName());

            String log = stderrOf(() -> MacroRecorder2.setLookAndFeel(new FailingDefaults()));

            assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - look and feel not set, and"
                    + " installing the cross-platform one failed lookAndFeel=" + FailingDefaults.class.getName()
                    + " crossPlatform=" + FailingCrossPlatform.class.getName() + " cause=no defaults; suppressed"
                    + " java.lang.IllegalStateException: no cross-platform defaults"), log);
            assertEquals(1, log.lines().count(), log);
        } finally {
            if (crossPlatform == null) {
                System.clearProperty("swing.crossplatformlaf");
            } else {
                System.setProperty("swing.crossplatformlaf", crossPlatform);
            }
            UIManager.setLookAndFeel(current);
        }
    }

    public static class FailingCrossPlatform extends MetalLookAndFeel {
        @Override
        public UIDefaults getDefaults() {
            throw new IllegalStateException("no cross-platform defaults");
        }
    }

    static class FailingDefaults extends MetalLookAndFeel {
        @Override
        public UIDefaults getDefaults() {
            throw new IllegalStateException("no defaults");
        }
    }

    static class FailingInitialize extends MetalLookAndFeel {
        @Override
        public void initialize() {
            throw new IllegalStateException("cannot initialize");
        }
    }

    static class InitializesOnce extends MetalLookAndFeel {
        private boolean initialized;

        @Override
        public void initialize() {
            if (initialized) {
                throw new IllegalStateException("cannot initialize again");
            }
            initialized = true;
            super.initialize();
        }
    }

    private static String stderrOf(Main action) throws Exception {
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(err);
        }
        return captured.toString(UTF_8);
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
