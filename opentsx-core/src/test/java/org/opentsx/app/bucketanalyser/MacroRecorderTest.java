package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void aDefaultLookAndFeelThatCannotLoadIsIgnoredWithAWarningAndTheLookAndFeelIsSet(@TempDir Path dir)
            throws Exception {
        Path output = dir.resolve("output.txt");
        Path errors = dir.resolve("errors.txt");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dswing.defaultlaf=does.not.Exist",
                "-cp", System.getProperty("surefire.test.class.path"),
                SetMetalWithADefaultThatCannotLoad.class.getName()))
                .directory(dir.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        int status = builder.start().waitFor();
        String printed = Files.readString(output);
        String log = Files.readString(errors);

        assertEquals(0, status, printed + log);
        assertEquals(MetalLookAndFeel.class.getName() + "\n", printed, log);
        assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - swing.defaultlaf: the default look and"
                + " feel cannot load and is ignored; name a look and feel class on the class path or remove"
                + " swing.defaultlaf lookAndFeel=" + MetalLookAndFeel.class.getName()
                + " cause=Cannot load does.not.Exist"), log);
        assertEquals(1, log.lines().count(), log);
        assertFalse(log.contains("java.lang.Error"), log);
    }

    @Test
    void aDefaultLookAndFeelThatIsNotOnTheClassPathIsNotLoadedSoTheKeyBindingsOfAWindowWork(@TempDir Path dir)
            throws Exception {
        Path output = dir.resolve("output.txt");
        Path errors = dir.resolve("errors.txt");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dswing.defaultlaf=does.not.Exist",
                "-cp", System.getProperty("surefire.test.class.path"),
                FireAWindowKeyBindingWithADefaultThatCannotLoad.class.getName()))
                .directory(dir.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        int status = builder.start().waitFor();
        String printed = Files.readString(output);
        String log = Files.readString(errors);

        assertEquals(0, status, printed + log);
        assertEquals("fired for the window 1, for a disabled button 1\n", printed, log);
        assertTrue(log.contains("cause=Cannot load does.not.Exist; caused by java.lang.ClassNotFoundException:"
                + " does.not.Exist"), log);
        assertEquals(1, log.lines().count(), log);
    }

    @Test
    void aDefaultLookAndFeelWhoseClassCannotInitializeIsIgnoredWithAWarningAndTheLookAndFeelIsSet(@TempDir Path dir)
            throws Exception {
        Forked run = fork(dir, List.of("-Dswing.defaultlaf=" + CannotInitialize.class.getName()),
                SetMetalWithADefaultThatCannotLoad.class);

        assertEquals(0, run.status(), run.printed() + run.log());
        assertEquals(MetalLookAndFeel.class.getName() + "\n", run.printed(), run.log());
        assertTrue(run.log().contains("WARN " + MacroRecorder2.class.getName() + " - swing.defaultlaf: the default look"
                + " and feel cannot load and is ignored; name a look and feel class on the class path or remove"
                + " swing.defaultlaf lookAndFeel=" + MetalLookAndFeel.class.getName() + " cause=Cannot load "
                + CannotInitialize.class.getName() + "; caused by java.lang.ExceptionInInitializerError; caused by"
                + " java.lang.NumberFormatException: For input string: \"broken\""), run.log());
        assertEquals(1, run.log().lines().count(), run.log());
    }

    @Test
    void aDefaultLookAndFeelThatFailsToLinkWhenItInitializesIsIgnoredWithAWarningAndTheLookAndFeelIsSet(
            @TempDir Path dir) throws Exception {
        Forked run = fork(dir, List.of("-Dswing.defaultlaf=" + InitializeCannotLink.class.getName()),
                SetMetalWithADefaultThatCannotLoad.class);

        assertEquals(0, run.status(), run.printed() + run.log());
        assertEquals(MetalLookAndFeel.class.getName() + "\n", run.printed(), run.log());
        assertTrue(run.log().contains("WARN " + MacroRecorder2.class.getName() + " - swing.defaultlaf: the default look"
                + " and feel cannot load and is ignored; name a look and feel class on the class path or remove"
                + " swing.defaultlaf lookAndFeel=" + MetalLookAndFeel.class.getName() + " cause=Cannot load "
                + InitializeCannotLink.class.getName() + "; caused by java.lang.NoClassDefFoundError:"
                + " a/missing/Dependency"), run.log());
        assertEquals(1, run.log().lines().count(), run.log());
    }

    @Test
    void aCrossPlatformDefaultThatCannotLoadIsNamedInTheWarningAndTheLookAndFeelIsSet(@TempDir Path dir)
            throws Exception {
        Path javaHome = Files.createDirectory(dir.resolve("java-home"));

        Forked run = fork(dir, List.of("-Dswing.crossplatformlaf=does.not.Exist"),
                SetMetalWithADefaultThatCannotLoad.class, javaHome.toString());

        assertEquals(0, run.status(), run.printed() + run.log());
        assertEquals(MetalLookAndFeel.class.getName() + "\n", run.printed(), run.log());
        assertTrue(run.log().contains("WARN " + MacroRecorder2.class.getName() + " - swing.crossplatformlaf: the"
                + " default look and feel cannot load and is ignored; name a look and feel class on the class path or"
                + " remove swing.crossplatformlaf lookAndFeel=" + MetalLookAndFeel.class.getName()
                + " cause=Cannot load does.not.Exist; caused by java.lang.ClassNotFoundException: does.not.Exist"),
                run.log());
        assertEquals(1, run.log().lines().count(), run.log());
    }

    @Test
    void onMacOsTheDefaultIsTheSystemLookAndFeelThatSwingSystemlafNames(@TempDir Path dir) throws Exception {
        Path javaHome = Files.createDirectory(dir.resolve("java-home"));

        Forked system = fork(dir, List.of("-Djava.awt.headless=true", "-Dswing.systemlaf=does.not.Exist",
                "-Dswing.crossplatformlaf=" + CannotInitialize.class.getName()),
                PrintTheDefaultOnMacOsAndElsewhere.class, javaHome.toString());
        Forked crossPlatform = fork(dir, List.of("-Djava.awt.headless=true", "-Dswing.crossplatformlaf=does.not.Exist"),
                PrintTheDefaultOnMacOsAndElsewhere.class, javaHome.toString());

        assertEquals(0, system.status(), system.printed() + system.log());
        assertEquals("does.not.Exist swing.systemlaf\n" + CannotInitialize.class.getName() + " swing.crossplatformlaf\n",
                system.printed(), system.log());
        assertEquals(0, crossPlatform.status(), crossPlatform.printed() + crossPlatform.log());
        assertEquals("does.not.Exist swing.crossplatformlaf\ndoes.not.Exist swing.crossplatformlaf\n",
                crossPlatform.printed(), crossPlatform.log());
    }

    @Test
    void onMacOsABrokenSystemLookAndFeelIsNamedInTheWarningAndTheLookAndFeelIsSet(@TempDir Path dir)
            throws Exception {
        Path javaHome = Files.createDirectory(dir.resolve("java-home"));

        Forked run = fork(dir, List.of("-Djava.awt.headless=true", "-Dos.name=Mac OS X",
                "-Dswing.systemlaf=does.not.Exist"), SetMetalWithADefaultThatCannotLoad.class, javaHome.toString());

        assertEquals(0, run.status(), run.printed() + run.log());
        assertEquals(MetalLookAndFeel.class.getName() + "\n", run.printed(), run.log());
        assertTrue(run.log().contains("WARN " + MacroRecorder2.class.getName() + " - swing.systemlaf: the default"
                + " look and feel cannot load and is ignored; name a look and feel class on the class path or remove"
                + " swing.systemlaf lookAndFeel=" + MetalLookAndFeel.class.getName()
                + " cause=Cannot load does.not.Exist; caused by java.lang.ClassNotFoundException: does.not.Exist"),
                run.log());
        assertEquals(1, run.log().lines().count(), run.log());
    }

    public static class PrintTheDefaultOnMacOsAndElsewhere {
        public static void main(String[] args) throws Exception {
            Class.forName(MacroRecorder2.class.getName());
            System.setProperty("java.home", args[0]);
            for (boolean macOS : new boolean[] {true, false}) {
                MacroRecorder2.Default defaultLaf = MacroRecorder2.defaultLookAndFeel(macOS);
                System.out.println(defaultLaf.className() + " " + defaultLaf.source());
            }
        }
    }

    @Test
    void aDefaultThatOnlySwingPropertiesNamesIsNamedWithItsFileAndNotLoadedSoTheKeyBindingsOfAWindowWork(
            @TempDir Path dir) throws Exception {
        Path system = Path.of(System.getProperty("java.home"), "conf", "swing.properties");
        byte[] systemBefore = Files.exists(system) ? Files.readAllBytes(system) : null;
        Path javaHome = dir.resolve("java-home");
        Path swingProperties = Files.createDirectories(javaHome.resolve("conf")).resolve("swing.properties");
        Files.writeString(swingProperties, "swing.defaultlaf=does.not.Exist\n");

        Forked run = fork(dir, List.of(), FireAWindowKeyBindingWithADefaultThatCannotLoad.class, javaHome.toString());

        assertEquals(0, run.status(), run.printed() + run.log());
        assertEquals("fired for the window 1, for a disabled button 1\n", run.printed(), run.log());
        assertTrue(run.log().contains("WARN " + MacroRecorder2.class.getName() + " - swing.defaultlaf in "
                + swingProperties + ": the default look and feel cannot load and is ignored; name a look and feel"
                + " class on the class path or remove swing.defaultlaf in " + swingProperties + " lookAndFeel="
                + MetalLookAndFeel.class.getName() + " cause=Cannot load does.not.Exist; caused by"
                + " java.lang.ClassNotFoundException: does.not.Exist"), run.log());
        assertEquals(1, run.log().lines().count(), run.log());
        assertArrayEquals(systemBefore, Files.exists(system) ? Files.readAllBytes(system) : null);
    }

    @Test
    void aDefaultOnTheClassPathThatIsNotALookAndFeelIsNotLoadedSoTheKeyBindingsOfAWindowWork(@TempDir Path dir)
            throws Exception {
        Forked run = fork(dir, List.of("-Dswing.defaultlaf=java.lang.String"),
                FireAWindowKeyBindingWithADefaultThatCannotLoad.class);

        assertEquals(0, run.status(), run.printed() + run.log());
        assertEquals("fired for the window 1, for a disabled button 1\n", run.printed(), run.log());
        assertTrue(run.log().contains("cause=Cannot load java.lang.String; caused by java.lang.ClassCastException:"
                + " Cannot cast java.lang.String to javax.swing.LookAndFeel"), run.log());
        assertEquals(1, run.log().lines().count(), run.log());
    }

    @Test
    void aDefaultLookAndFeelOfTheJdkThatJavaDesktopDoesNotExportIsLeftToSwingWithoutAWarning(@TempDir Path dir)
            throws Exception {
        Forked run = fork(dir, List.of("-Dswing.defaultlaf=com.sun.java.swing.plaf.motif.MotifLookAndFeel"),
                FireAWindowKeyBindingWithADefaultThatCannotLoad.class);

        assertEquals(0, run.status(), run.printed() + run.log());
        assertEquals("fired for the window 1, for a disabled button 1\n", run.printed(), run.log());
        assertEquals("", run.log());
    }

    private record Forked(int status, String printed, String log) {
    }

    private static Forked fork(Path dir, List<String> options, Class<?> main, String... args) throws Exception {
        Path output = dir.resolve("output.txt");
        Path errors = dir.resolve("errors.txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path"), main.getName()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(dir.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        int status = builder.start().waitFor();
        return new Forked(status, Files.readString(output), Files.readString(errors));
    }

    static void setMetal(String[] javaHome) throws ClassNotFoundException {
        // the JDK opens its module image below java.home on first use, which has to happen before java.home moves
        Class.forName(MacroRecorder2.class.getName());
        String before = System.getProperty("java.home");
        if (javaHome.length > 0) {
            System.setProperty("java.home", javaHome[0]);
        }
        try {
            MacroRecorder2.setLookAndFeel(new MetalLookAndFeel());
        } finally {
            System.setProperty("java.home", before);
        }
    }

    public static class CannotInitialize extends MetalLookAndFeel {
        static final int BROKEN = Integer.parseInt("broken");
    }

    public static class InitializeCannotLink extends MetalLookAndFeel {
        @Override
        public void initialize() {
            throw new NoClassDefFoundError("a/missing/Dependency");
        }
    }

    public static class FireAWindowKeyBindingWithADefaultThatCannotLoad {
        public static void main(String[] args) throws Exception {
            setMetal(args);
            AtomicInteger fired = new AtomicInteger();
            String[] printed = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("bindings");
                JButton button = new JButton("disabled");
                button.setEnabled(false);
                frame.getContentPane().add(button);
                KeyStroke save = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
                frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(save, "save");
                frame.getRootPane().getActionMap().put("save", new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        fired.incrementAndGet();
                    }
                });
                frame.pack();
                frame.setVisible(true);
                KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                focus.dispatchKeyEvent(new KeyEvent(frame, KeyEvent.KEY_PRESSED, 0, InputEvent.CTRL_DOWN_MASK,
                        KeyEvent.VK_S, KeyEvent.CHAR_UNDEFINED));
                int forWindow = fired.get();
                focus.dispatchKeyEvent(new KeyEvent(button, KeyEvent.KEY_PRESSED, 0, InputEvent.CTRL_DOWN_MASK,
                        KeyEvent.VK_S, KeyEvent.CHAR_UNDEFINED));
                printed[0] = "fired for the window " + forWindow + ", for a disabled button " + (fired.get() - forWindow);
                frame.dispose();
            });
            System.out.println(printed[0]);
            System.exit(0);
        }
    }

    @Test
    void aDefaultLookAndFeelOnTheClassPathThatCannotBeSetIsIgnoredWithAWarningAndTheLookAndFeelIsSet(@TempDir Path dir)
            throws Exception {
        Path output = dir.resolve("output.txt");
        Path errors = dir.resolve("errors.txt");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dswing.defaultlaf=java.lang.String",
                "-cp", System.getProperty("surefire.test.class.path"),
                SetMetalWithADefaultThatCannotLoad.class.getName()))
                .directory(dir.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        int status = builder.start().waitFor();
        String printed = Files.readString(output);
        String log = Files.readString(errors);

        assertEquals(0, status, printed + log);
        assertEquals(MetalLookAndFeel.class.getName() + "\n", printed, log);
        assertTrue(log.contains("WARN " + MacroRecorder2.class.getName() + " - swing.defaultlaf: the default look and"
                + " feel cannot load and is ignored; name a look and feel class on the class path or remove"
                + " swing.defaultlaf lookAndFeel=" + MetalLookAndFeel.class.getName()
                + " cause=Cannot load java.lang.String"), log);
        assertEquals(1, log.lines().count(), log);
    }

    public static class SetMetalWithADefaultThatCannotLoad {
        public static void main(String[] args) throws Exception {
            setMetal(args);
            System.out.println(UIManager.getLookAndFeel().getClass().getName());
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
