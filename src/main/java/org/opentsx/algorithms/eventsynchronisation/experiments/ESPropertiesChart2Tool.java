/**
 * This program runs the second experiment to study the properties of the Event
 * Synchronisation algorithm. We generate pairs of random event time series
 * (ETS).
 *
 *
 *
 * Look for label: \label{sssec:ES_properties}
 */
package org.opentsx.algorithms.eventsynchronisation.experiments;

import org.semanpix.chart.simple.MyXYPlot;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.exporter.OriginProject;
import java.io.IOException;

import org.opentsx.algorithms.eventsynchronisation.ESCalc2;
import java.util.Vector;

/**
 * @author Mirko Kämpf
 */
public class ESPropertiesChart2Tool {

    /**
     * Simulation parameters ...
     *
     * Scale : 1, 10 iterations : k_MAX total length of series : n1 * scale nr
     * of events s1 : 3 ... 300, 1 nr of events s2 : z
     */
    public static void main(String[] args) throws IOException {

        OriginProject op = new OriginProject();
        op.initBaseFolder(null);

        // log the results later
        StringBuffer sb = new StringBuffer();
        Vector<TimeSeriesObject> vmr = null;

        ESCalc2.debug = false;

        int scale = 1;  // 1,5,10

        int k_MAX = 10; // number of iterations

        // we use a series length of one year on hourly resolution
        int n1 = 365 * 24 * 2;

//         int z[] = {3, 100, 300};
        int z[] = {30};

        int z1 = 0;
        double ratioRho = 1;

        op.initSubFolder("experiment_ES2.M3___length_" + n1);

        op.createLogFile("global_experiment_ES2.log");


        for (int zi2 = 0; zi2 < z.length; zi2 = zi2 + 1) {

            int z2 = z[zi2];

            // for each run we collect some logs.
            // op.createLogFile( runLabel + ".log");
            int N = 10;
            int N2 = 250;
            double F = 1;

            for (int i = 10; i < 1100; i = (int) (i * F) + N2) {
        vmr = new Vector<TimeSeriesObject>();

                                    // Here we collect the sereis
//            op.logLine(runLabel + ".log", ">>> init new vector ... ");
                // for this experiment we create a series which is shown later in a plot
                TimeSeriesObject mr_Q_SHIFT_fixed_rho = new TimeSeriesObject();
                mr_Q_SHIFT_fixed_rho.setLabel("Q_" + i);

                TimeSeriesObject mr_q_SHIFT_fixed_rho = new TimeSeriesObject();
                mr_q_SHIFT_fixed_rho.setLabel("q_" + i);

                TimeSeriesObject mr_q_t = new TimeSeriesObject();
                mr_q_t.setLabel("q_t_" + n1);

                TimeSeriesObject mr_q_t2 = new TimeSeriesObject();
                mr_q_t2.setLabel("q/Q_" + n1);

                for (int j = 10; j < 1000; j = (int) (j * F) + N) {

                    String runLabel = "z2_" + j;

                    ratioRho = (double) j / (double) i;

                    // op.logLine(runLabel + ".log", "rho: " + ratioRho );
                    double sumQ = 0;
                    double sumq = 0;

                    /**
                     * We repeat this experiment several times.
                     */
                    int k = 0;

                    for (k = 0; k < k_MAX; k++) {

                        int r1[] = ESCalc2.createEventTS_SINGLE(n1, (int) i * scale);
                        int[] r1_IETS = ESCalc2.getEventIndexSeries(r1);

                        int r2[] = ESCalc2.createEventTS_SINGLE(n1, (int) j * scale);
                        int[] r2_IETS = ESCalc2.getEventIndexSeries(r2);

                        double[] esTEST3 = ESCalc2.calcES(r1_IETS, r2_IETS);
                        // System.out.println("TEST3: " + esTEST3[0] + " => " + esTEST3[1] + " => " + ratioRho);

                        double Q = esTEST3[0];
                        double q = esTEST3[1];

//                    mr_Q_SHIFT_fixed_rho.addValuePair(x, Q);
//                    mr_q_SHIFT_fixed_rho.addValuePair(x, q);
//
//                    
//                    mr_q_t.addValuePair(x, (0.7 / (Math.pow(5.0 + maxX, 0.6))));
//                    mr_q_t2.addValuePair(x, q / Q);
                        sumQ = sumQ + Q;
                        sumq = sumq + q;

                    }

                    if (ratioRho > 1.0) {
                        ratioRho = 1.0 / ratioRho;
                    }
                    System.out.println("ratioRho= " + ratioRho);

                    mr_Q_SHIFT_fixed_rho.addValuePair(ratioRho, sumQ / (1.0 * k_MAX));
                    mr_q_SHIFT_fixed_rho.addValuePair(ratioRho, sumq / (1.0 * k_MAX));

//                double maxX = Math.max(sumX / t, 1.0 / (sumX / t));
//                mr_q_t.addValuePair( x , (0.7 / (Math.pow(5.0 + maxX, 0.6))));
//                mr_q_t2.addValuePair( x, sumq / sumQ);
                }
//                vmr.add(mr_Q_SHIFT_fixed_rho);//
                vmr.add(mr_q_SHIFT_fixed_rho);

                //         op.logLine( "global_experiment_ES2.log", "z2: " + z2 );
                MyXYPlot.xRangDEFAULT_MAX = 1;
                MyXYPlot.xRangDEFAULT_MIN = 0;
                MyXYPlot.yRangDEFAULT_MIN = 0;
                MyXYPlot.yRangDEFAULT_MAX = 0.35;

                String label = "n_" + n1 + "_scale_" + scale + "_i_" + i;

                MyXYPlot plot = MyXYPlot.openAndGet(vmr, label, "rho1 / rho2", "Q, q, and Q/q", true);
                op.storePlotMyXYPlot(plot, label + "_sketch");

                MeasurementTable mwt = new MeasurementTable(
                        "ESPropertiesChart2Tool_" + label + ".csv",
                        "ESPropertiesChart2Tool_" + label + "_data_table.csv");

                mwt.setMessReihen(vmr);

                op.storeMeasurementTable(mwt);

                //  vmr.add(mr_q_SHIFT_fixed_rho);
            }

//            vmr.add(mr_q_t);
//            vmr.add(mr_q_t2);
        }

        op.closeAllWriters();

    }

}
