package org.opentsx.app.bucketanalyser;


import org.opentsx.generators.TSGenerator;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;

import java.util.Vector;

/**
 * Produce Images for chapter 7. 
 * 
 * @author kamir
 */
public class MacroRecorder {

    public static String label_of_TRACK = "EXP4";
    public static String loadOp = "CACHE";
    
    public static double samplingRate = 1000;
        
    /**
     * NO ARGUMENTS
     * 
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {
        
        // Paramters for test data generation ...
        double PI = Math.PI;
        

        // EXP 2
        label_of_TRACK = "EXP2";
        double[] f = {2, 2, 2, 6, 12, 24};  // frequency
        double[] a = {5, 3, 8, 2, 1, 5};                  // amplitude 
        double[] phase = {0.0, 0.5, 0.25, 0.0, 0.0, 0.0};   // phase
        double[] noise = {1.0, 0.5, 20.0, 0.0, 0.0, 0.0};  // noise level

        double time = 5;
        samplingRate = 1000;

        // To build a time series with N components we need a container to hold 
        // components.
        Vector<TimeSeriesObject> components = new Vector<TimeSeriesObject>();
        
        // final series - additive superposition of components
        TimeSeriesObject mix = new TimeSeriesObject();
        mix.setLabel("mix");
        
        for (int i = 0; i < f.length; i++) {
            TimeSeriesObject m = TSGenerator.getSinusWave(f[i], time, samplingRate, a[i], phase[i] * PI, noise[i]);
            m.labelWithEntropy();
            mix = mix.add(m);

            components.add(m.copy());
        }
        mix.labelWithEntropy();
        components.add(mix);

        
        
        MacroTrackerFrame.init(label_of_TRACK);
        MultiChart.open(components, true, "Components");
        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Collection", "Components", loadOp), "Components");
        
        // Now we shuffle the values and calc the Entropy per row again.        
        // shuffeling should not change the Entropy
        Vector<TimeSeriesObject> shuffledComponents = new Vector<TimeSeriesObject>();
        
        int shuffles = 1;
        
        for( TimeSeriesObject comp : components ) {
              
            TimeSeriesObject mm = comp.copy();
            
            mm.shuffleYValues(shuffles);
            mm.labelWithEntropy();
            
            shuffledComponents.add(mm);
            
        }
        
        MultiChart.open(shuffledComponents, true, "Shuffled");
        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Components", "Shuffled", "shuffleYValues(" + shuffles + ")"));

    }

    /**
     * @deprecated Has no effect; MacroRecorder runs no DFA.
     */
    @Deprecated
    public static double fitMIN = 1.2;

    /**
     * @deprecated Has no effect; MacroRecorder runs no DFA.
     */
    @Deprecated
    public static double fitMAX = 3.5;

}
