package org.opentsx.algorithms;



import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.opentsx.app.ContextRecorder;
import org.opentsx.app.bucketanalyser.MacroTrackerFrame;

import org.opentsx.generators.FFTPhaseRandomizer;
import org.opentsx.generators.TSGenerator;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.chart.simple.MyXYPlot;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.DetrendingMethodFactory;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.Vector;

/**
 * Correlation of two sine waves as a function of the series length. It computes no transfer entropy and no mutual
 * information.
 *
 * @author kamir
 */
public class EntropieTest {

    private static final Logger LOG = LoggerFactory.getLogger(EntropieTest.class);

    public static String label_of_EXPERIMENT = "EXP0";
    public static double samplingRate = 1000;

    /**
     * Builds sine waves of 2 Hz and 3 Hz over 10 s at 2000 values per second and their sum, labels each with its
     * entropy and charts them. For each length from 5 to 5999 values it prints the length and the Pearson correlation
     * of the two waves cut to that length, separated by a tab and with a decimal comma, after the header
     * {@code length<TAB>cc}, and charts the correlation over the length. It then builds a shuffled and two
     * phase-randomized copies of the sum and labels them with their entropy.
     *
     * @param args not used
     */
    public static void main(String[] args) throws Exception {

        // Paramters for test data generation ...
        double PI = Math.PI;

        MacroTrackerFrame.init("Entropy test");

        // EXP 1
        label_of_EXPERIMENT = "NONLINEARDEP";
        String subLabel = "EXP4b";

        double[] f = {2, 3};  // frequency
        double[] a = {1, 1};                  // amplitude 
        double[] phase = {0.0, 0.0};  // phase
        double[] noise = {0.0, 0.0};  // noise level
        boolean doShuffle = false;

        ContextRecorder.setSubLabel(subLabel);

        ContextRecorder.recordParameters("f", f);
        ContextRecorder.recordParameters("a", a);
        ContextRecorder.recordParameters("phase", phase);
        ContextRecorder.recordParameters("noise", noise);
        ContextRecorder.resetSubLabel();

        doDFA = false;
        SCATTERPLOT = true;
        SCATTERPLOT_AUTO_SAVE = false;

        double time = 10;
        samplingRate = 2000;

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

        MultiChart.open(components, true, "Components");

        int windowSize = 20;
        int shuffles = 1;

        // DFA ...
        applyDFA(components, "RAW components  ");

        System.out.println("length\tcc");

        TimeSeriesObject mrPROBE2 = new TimeSeriesObject();
        mrPROBE2.setLabel("CC as a function ot length");
        Vector<TimeSeriesObject> prob = new Vector<TimeSeriesObject>();
        prob.add(mrPROBE2);

        for (int i = 5; i < 6000; i = i + 1) {

            Vector<TimeSeriesObject> comp = new Vector<TimeSeriesObject>();

            TimeSeriesObject mm = components.elementAt(0).cutOut(0, i);
            TimeSeriesObject mmm = components.elementAt(1).cutOut(0, i);

            if (doShuffle) {
                mm.shuffleYValues(1);
                mmm.shuffleYValues(1);
            }
            comp.add(mm);
            comp.add(mmm);

            if (i == samplingRate) {
                SCATTERPLOT = true;
            } else {
                SCATTERPLOT = false;
            }

            System.out.println(i + "\t" + calcTEandMI(comp.elementAt(0), comp.elementAt(1), "r0", "r1", mrPROBE2, i));

        }

        MultiChart.open(prob, subLabel, "length", "CC", true, ContextRecorder.sb.toString(), null);

        Vector<TimeSeriesObject> mrv2 = new Vector<TimeSeriesObject>();

        int i = 1;

        // simple randomisation
        TimeSeriesObject m2 = mix.copy();
        m2.shuffleYValues(i);

        // phase manipulation
        TimeSeriesObject m3 = FFTPhaseRandomizer.getPhaseRandomizedRow(mix.copy(), false, false, 0, FFTPhaseRandomizer.MODE_multiply_phase_with_random_value);
        TimeSeriesObject m4 = FFTPhaseRandomizer.getPhaseRandomizedRow(mix.copy(), false, false, 0, FFTPhaseRandomizer.MODE_shuffle_phase);

        m2.setLabel("shuffled_SIMPLE(" + i + ")");
        m3.setLabel("shuffled_PHASERANDOM_MULTIPLY(" + i + ")");
        m4.setLabel("shuffled_PHASERANDOM_SHUFFLE(" + i + ")");

        m2.labelWithEntropy("m2");
        m3.labelWithEntropy("m3");
        m4.labelWithEntropy("m4");

        mrv2.add(mix);
        mrv2.add(m2);
        mrv2.add(m3);
        mrv2.add(m4);

        applyDFA(mrv2, "<<< RAW, Shuffled, PHRand.MULTIPLY, PHRand.SHUFFLE >>> ");

        LOG.debug("compared surrogates experiment={} series={}", label_of_EXPERIMENT, mrv2.size());

    }

    static boolean doDFA = false;
    static boolean SCATTERPLOT = false;
    static boolean SCATTERPLOT_AUTO_SAVE = false;

    public static double fitMIN = 1.2;
    public static double fitMAX = 3.5;

    private static void applyDFA(Vector<TimeSeriesObject> mrv, String label) throws Exception {

        if (!doDFA) {
            return;
        }

        int nrOfSValues = 250;
        int order = 0;

        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();

        for (TimeSeriesObject d4 : mrv) {

            int N = d4.yValues.size();
            double[] zr = new double[N];

            Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();

            vr.add(d4);

            zr = d4.getData()[1];

            IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
            order = dfa.getPara().getGradeOfPolynom();
            dfa.getPara().setzSValues(nrOfSValues);

            // number of values in the series
            dfa.setNrOfValues(N);

            // choose the window sizes
            dfa.initIntervalSlog();

            dfa.showS();

            // http://stackoverflow.com/questions/12049407/build-sample-data-for-apache-commons-fast-fourier-transform-algorithm
            dfa.setZR(d4.getData()[1]);

            dfa.calc();

            TimeSeriesObject mr4 = dfa.getResultsMRLogLog();
            mr4.setLabel(d4.getLabel());
            v.add(mr4);

            String status = dfa.getStatus();

            SimpleRegression alphaSR = mr4.linFit(fitMIN, fitMAX);

            double alpha = alphaSR.getSlope();

            LOG.debug("computed DFA series={} alpha={} status={}", d4.getLabel(), alpha, status.strip().replace('\n', ' '));
        }

        if (true) {
            DecimalFormat df = new DecimalFormat("0.000");
            MultiChart.open(v, label + " fluctuation function F(s) [order:" + order + "] ", "log(s)", "log(F(s))", true, "???", null);

        }
    }




    static boolean singleDebug = true;

    /**
     * Normalizes both series and returns their Pearson correlation with a decimal comma. It adds the point (x, cc)
     * to row2 and computes no transfer entropy and no mutual information.
     */
    private static String calcTEandMI(TimeSeriesObject mr1, TimeSeriesObject mr2, String l1, String l2, TimeSeriesObject row2, int x) throws Exception {

        mr1.normalize();
        mr2.normalize();

        Vector<TimeSeriesObject> vv = new Vector<TimeSeriesObject>();
        vv.add(mr1);
        vv.add(mr2);
        if (singleDebug) {
            MultiChart.open(vv);
            singleDebug = false;
        }

        PearsonsCorrelation pc = new PearsonsCorrelation();
        double cc = pc.correlation(mr1.getYData(), mr2.getYData());   // no timelag
        String line = String.valueOf(cc);

        row2.addValuePair(x, cc);

        if (SCATTERPLOT) {

            Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
            v.add(mr1.zip(mr2));

            // SET SCALES to MIN and MAX from TimeSeriesObject ...
            MyXYPlot.setXRange(mr1);
            MyXYPlot.setYRange(mr2);

            MyXYPlot plot = MyXYPlot.openAndGet(v, l1 + "_vs_" + l2, mr1.getLabel(), mr2.getLabel(), true);
        }

        return line.replace('.', ',');

    }



}
