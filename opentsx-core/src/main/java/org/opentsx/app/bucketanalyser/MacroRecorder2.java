package org.opentsx.app.bucketanalyser;

import com.formdev.flatlaf.FlatDarkLaf;
import org.opentsx.generators.TSGeneratorFINAL;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

/**
 * Produce Images for chapter 7.
 * 
 * @author kamir
 */
public class MacroRecorder2 {

    private static final Logger LOG = LoggerFactory.getLogger(MacroRecorder2.class);

    public static String label_of_TRACK = "EXP4";

    public static String loadOp = "CACHE";

    /**
     * NO ARGUMENTS
     * 
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {

        // To build a time series with N components we need a container to hold
        // components.
        Vector<TimeSeriesObject> components = new Vector<TimeSeriesObject>();

        components = TSGeneratorFINAL.getSampleA();

        setLookAndFeel(new FlatDarkLaf());

        MacroTrackerFrame.init(label_of_TRACK);
        MultiChart.open(components, true, "Components");
        MacroTrackerFrame.addTransformation(
                TSBucketTransformation.getTransformation("Collection", "Components", loadOp), "Components");

        // Now we shuffle the values and calc the Entropy per row again.
        // shuffeling should not change the Entropy
        Vector<TimeSeriesObject> shuffledComponents = new Vector<TimeSeriesObject>();

        int shuffles = 1;

        for (TimeSeriesObject comp : components) {

            TimeSeriesObject mm = comp.copy();

            mm.shuffleYValues(shuffles);
            mm.labelWithEntropy();

            shuffledComponents.add(mm);

        }

        MultiChart.open(shuffledComponents, true, "Shuffled");
        MacroTrackerFrame.addTransformation(
                TSBucketTransformation.getTransformation("Components", "Shuffled", "shuffleYValues(" + shuffles + ")"));


    }

    /**
     * @deprecated Has no effect; MacroRecorder2 runs no DFA.
     */
    @Deprecated
    public static double fitMIN = 1.2;

    /**
     * @deprecated Has no effect; MacroRecorder2 runs no DFA.
     */
    @Deprecated
    public static double fitMAX = 3.5;

    record Default(String className, String source) {
    }

    static void setLookAndFeel(LookAndFeel lookAndFeel) {
        LookAndFeel before;
        Default defaultLaf = defaultLookAndFeel(System.getProperty("os.name", "").contains("OS X"));
        Throwable notSettable = cannotBeSet(defaultLaf.className());
        if (notSettable != null) {
            // a default that UIManager cannot set ends UIManager.initialize() early, so UIManager must never try it
            ignored(defaultLaf, lookAndFeel, new Error("Cannot load " + defaultLaf.className(), notSettable));
            before = null;
        } else {
            try {
                before = UIManager.getLookAndFeel();
            } catch (Error failed) {
                // UIManager turns an exception of the default into Error("Cannot load <class>")
                // and lets a LinkageError of the default through
                if (failed.getClass() != Error.class && !(failed instanceof LinkageError)) {
                    throw failed;
                }
                ignored(defaultLaf, lookAndFeel, failed.getClass() == Error.class ? failed
                        : new Error("Cannot load " + defaultLaf.className(), failed));
                before = null;
            }
        }
        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (Exception ex) {
            // UIManager installs the new look and feel before its initialize() and getDefaults() run
            String crossPlatform = UIManager.getCrossPlatformLookAndFeelClassName();
            try {
                if (before == null) {
                    UIManager.setLookAndFeel(crossPlatform);
                } else if (UIManager.getLookAndFeel() != before) {
                    UIManager.setLookAndFeel(before);
                }
            } catch (Exception restore) {
                ex.addSuppressed(restore);
                if (before == null) {
                    LOG.warn("look and feel not set, and installing the cross-platform one failed lookAndFeel={}"
                            + " crossPlatform={} cause={}", lookAndFeel.getClass().getName(), crossPlatform,
                            causes(ex));
                } else {
                    LOG.warn("look and feel not set, and setting back the one before failed lookAndFeel={}"
                            + " before={} cause={}", lookAndFeel.getClass().getName(), before.getClass().getName(),
                            causes(ex));
                }
                return;
            }
            if (before == null) {
                LOG.warn("look and feel not set; the workbench uses the cross-platform one lookAndFeel={}"
                        + " crossPlatform={} cause={}", lookAndFeel.getClass().getName(), crossPlatform, causes(ex));
            } else {
                LOG.warn("look and feel not set; the workbench keeps the current one lookAndFeel={} cause={}",
                        lookAndFeel.getClass().getName(), causes(ex));
            }
        }
    }

    private static void ignored(Default defaultLaf, LookAndFeel lookAndFeel, Error cause) {
        LOG.warn("{}: the default look and feel cannot load and is ignored; name a look and feel class on the class"
                + " path or remove {} lookAndFeel={} cause={}", defaultLaf.source(), defaultLaf.source(),
                lookAndFeel.getClass().getName(), causes(cause));
    }

    /**
     * Returns the default look and feel UIManager.initialize() sets and where it is named: the system property
     * swing.defaultlaf, else swing.defaultlaf in java.home/conf/swing.properties, else on macOS the system one, else
     * the cross-platform one.
     */
    static Default defaultLookAndFeel(boolean macOS) {
        String property = System.getProperty("swing.defaultlaf");
        if (property != null) {
            return new Default(property, "swing.defaultlaf");
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path file = Path.of(javaHome, "conf", "swing.properties");
            String named = swingProperties(file).getProperty("swing.defaultlaf");
            if (named != null) {
                return new Default(named, "swing.defaultlaf in " + file);
            }
        }
        if (macOS) {
            String system = System.getProperty("swing.systemlaf");
            if (system != null) {
                return new Default(system, "swing.systemlaf");
            }
            String systemDefault = UIManager.getSystemLookAndFeelClassName();
            if (!systemDefault.equals(UIManager.getCrossPlatformLookAndFeelClassName())) {
                return new Default(systemDefault, "swing.systemlaf");
            }
        }
        return new Default(UIManager.getCrossPlatformLookAndFeelClassName(), "swing.crossplatformlaf");
    }

    private static Properties swingProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.exists(file)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            // UIManager ignores a swing.properties it cannot read as well
            LOG.debug("swing.properties not read file={} cause={}", file, unreadable.toString());
        }
        return properties;
    }

    private static Throwable cannotBeSet(String className) {
        try {
            Class<?> type = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            Module desktop = LookAndFeel.class.getModule();
            if (type.getModule() == desktop && LookAndFeel.class.isAssignableFrom(type)
                    && !desktop.isExported(type.getPackageName(), MacroRecorder2.class.getModule())) {
                // java.desktop does not export look and feels such as GTK and Motif, which UIManager creates itself
                return null;
            }
            Object instance = type.getConstructor().newInstance();
            LookAndFeel defaultLaf = LookAndFeel.class.cast(instance);
            if (!defaultLaf.isSupportedLookAndFeel()) {
                return new UnsupportedLookAndFeelException(defaultLaf + " not supported on this platform");
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError notSettable) {
            return notSettable;
        }
    }

    private static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        for (Throwable suppressed : t.getSuppressed()) {
            s.append("; suppressed ").append(suppressed);
        }
        return s.toString();
    }

}
