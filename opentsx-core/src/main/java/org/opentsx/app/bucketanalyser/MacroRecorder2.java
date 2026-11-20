package org.opentsx.app.bucketanalyser;

import com.formdev.flatlaf.FlatDarkLaf;
import org.opentsx.generators.TSGeneratorFINAL;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import java.util.Collections;
import java.util.IdentityHashMap;
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

    static void setLookAndFeel(LookAndFeel lookAndFeel) {
        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (Exception ex) {
            LOG.warn("look and feel not set; the workbench keeps the current one lookAndFeel={} cause={}",
                    lookAndFeel.getClass().getName(), causes(ex));
        }
    }

    private static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return s.toString();
    }

}
