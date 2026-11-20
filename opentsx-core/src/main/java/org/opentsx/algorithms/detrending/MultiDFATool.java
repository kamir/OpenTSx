package org.opentsx.algorithms.detrending;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.generator.TestDataFactory;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.Vector;

/**
 * Runs the DFA of every series of a group, for example a class of series.
 *
 * For every window size s it also sums (x tilde)^2 and counts the segments,
 * so the end result is one F(s) for all contributions of the series of
 * different length.
 *
 * @author kamir
 */
public class MultiDFATool {

    private static final Logger LOG = LoggerFactory.getLogger(MultiDFATool.class);

    TimeSeriesObject finalFS = null;

    double alpha = Double.NaN;

    /**
     * The folder the F(s) file of every series is written to, ending with a separator; by default opentsx/dfa/
     * below user.home.
     */
    public String savePath = Paths.get(System.getProperty("user.home"), "opentsx", "dfa") + File.separator;

    public static void main(String[] main) {

        MultiDFATool tool = new MultiDFATool();

        RNGWrapper.init();

        int[] length = {500, 1500, 2500, 1000, 5000,
            2500, 3500, 1450, 2500, 7000,
            3000, 10000, 6000, 2000, 1000};

        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();
        for (int i = 0; i < 15; i++) {
            TimeSeriesObject mr = TestDataFactory.getDataSeriesRandomValues_RW(length[i]);
            mr.setLabel("R" + i + " [" + length[i] + "]");
            vmr.add(mr);
        }

        tool.runDFA(vmr, 2);
        System.out.println("alpha=" + tool.alpha);
    }


    IDetrendingMethod dfa;
    // one fluctuation function over all series for every s
    Vector<TimeSeriesObject> Fs = new Vector<TimeSeriesObject>();
    Vector<Integer> s = new Vector<Integer>();

    // the DFA of one order for a class of series
    public void runDFA(TimeSeriesObject[] mr, int order) {
        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();
        for( TimeSeriesObject m : mr ) {
            vmr.add(m);
        }
        runDFA(vmr, order);
    }    
    // the DFA of one order for a class of series
    public void runDFA(Vector<TimeSeriesObject> vmr, int order) {

        dfa = DetrendingMethodFactory.getDetrendingMethod(order);

        // the longest series sets the window sizes s
        TimeSeriesObject lang = null;
        int longest = 0;
        for (int z = 0; z < vmr.size(); z++) {

            TimeSeriesObject mr = vmr.elementAt(z);
            if ( mr.xValues.size() > longest ) {
                longest = mr.xValues.size();
                lang = mr;
            }
            
        }
        int zahlS = calcNrOf_s(lang, order);
        S = dfa.getS().clone();
        LOG.debug("window sizes series={} longest={} windows={}", vmr.size(), longest, zahlS);

        // the sum of xs^2 and the number of segments anzw for every s
        double[] anzw_ = new double[ zahlS ];
        double[] sum_xs_xs_ = new double[ zahlS ];

        for (int z = 0; z < vmr.size(); z++) {

            TimeSeriesObject mr = null;
            double[][] dfa_results = null;

            mr = vmr.elementAt(z);
            if (mr != null) {

                // set number of values
                double[] zr = mr.getData()[1];
                dfa.setNrOfValues(zr.length);
                // set values for windowsizes (s) ...
                dfa.initIntervalSlog();
                
                // set input data
                dfa.setZR(zr);
                // start dfa calculation
                dfa.calc();
                // get results ...
                dfa_results = dfa.getResults();  // F(s)

                /*
                F[0][s_pos] = s[s_pos];
                F[1][s_pos] = anzw;
                F[2][s_pos] = FS;
                F[3][s_pos] = FSUM ==> sum of xs^2;
                 */

                TimeSeriesObject res_mr = new TimeSeriesObject();
                String labelstr = mr.getLabel();
                res_mr.setLabel(labelstr);
                res_mr.setAddinfo(
                        " alpha_fit_min:    " + dfa.getAlphaFitMin()
                        + "\n# alpha_fit_max: " + dfa.getAlphaFitMax()
                        + "#\n DFA order:   " + dfa.getPara().getGradeOfPolynom());

                for (int i = 0; i < dfa_results[1].length; i++) {
                    // the result of this series
                    res_mr.addValuePair(Math.log10(dfa_results[0][i]), Math.log( dfa_results[2][i] ));

                    // the contribution to the result of all series
                    anzw_[i] = anzw_[i] + dfa_results[1][i];
                    sum_xs_xs_[i] = sum_xs_xs_[i] + dfa_results[3][i];
                }

                String dfaFilesPath = savePath + (z + 1) + "_dfa" + dfa.getPara().getGradeOfPolynom() + ".txt";
                File dfaFile = new File(dfaFilesPath);
                res_mr.writeToFile(dfaFile);

                LOG.debug("wrote DFA file series={} file={}", labelstr, dfaFilesPath);
                Fs.add(res_mr);
            }
        }

        // collect the values into one F(s)
        finalFS = new TimeSeriesObject();
        TimeSeriesObject finalZ = new TimeSeriesObject();
        for (int i = 0; i < zahlS; i++) {
            finalFS.addValuePair( Math.log10( S[i] ) , Math.log10( Math.sqrt( sum_xs_xs_[i] / anzw_[i] )));
            finalZ.addValuePair( S[i] , anzw_[i] );
        }

        MultiChart.open(Fs, "F(s) over s [order:" + order + "]", "log(s)", "log(F(s))", true, "?", null);

        Vector<TimeSeriesObject> fsF = new Vector<TimeSeriesObject>();
        fsF.add(finalFS);
        MultiChart.open(fsF, "F(s) over s [order:" + order + "] (all rows)", "log(s)", "log(F(s))", true, "?", null);

        Vector<TimeSeriesObject> fsF2 = new Vector<TimeSeriesObject>();
        fsF2.add(finalZ);
        MultiChart.open(fsF2, "Segments per s [order:" + order + "] (all rows)", "s", "z", true, "?", null);

        SimpleRegression reg;
        try {
            reg = finalFS.linFit(1.0, 3.0);
        }
        catch (Exception ex) {
            throw new IllegalStateException("runDFA: cannot fit alpha to log F(s) for log s from 1 to 3 (" + ex + ")", ex);
        }
        alpha = reg.getSlope();
        LOG.debug("fitted alpha series={} order={} alpha={}", vmr.size(), order, alpha);
    }

    /**
     * Returns the number of window sizes of the DFA of the series and leaves them in the detrending method.
     */
    public int calcNrOf_s(TimeSeriesObject mr, int order) {
        dfa.setNrOfValues(mr.xValues.size());
        dfa.initIntervalSlog();
        return dfa.getS().length;
    };

    int[] S = null;
}
