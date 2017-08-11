/*
 * This program runs the first experiment to study the properties of the Event
 * Synchronisation algorithm. We generate pairs of random event time series
 * (ETS).
 *
 *
 *
 * Look for label: \label{sssec:ES_properties}
 */
package org.apache.hadoopts.statphys.eventsynchronisation.experiments;

import org.apache.hadoopts.chart.simple.MyXYPlot;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.export.OriginProject;
import java.io.IOException;
import org.apache.hadoopts.statphys.eventsynchronisation.ESCalc2;
import java.util.Vector;

/**
 * @author Mirko Kämpf
 */
public class ESPropertiesChart1Tool {

    /**
     * Simulation parameters ...
     *
     * relative shift : rs 
     * time shift : dt
     * <inter event times> :  <t_be>
     *
     * rs = dt / < t_be >
     *
     * Scale : 1, 10 
     * iterations : k_MAX
     *
     * total length of series : n1 * scale
     *
     * nr of events s1 : 3 ... 300, 1 
     * nr of events s2 : z
     */
    public static void main(String[] args) throws IOException {
        
        OriginProject op = new OriginProject();
        op.initBaseFolder(null);

        ESCalc2.debug = false;

        // log the results later
        StringBuffer sb = new StringBuffer();

        int k_MAX = 5; // number of iterations
        boolean aggregate = true;

        int scale = 1;  // 1,5,10

        // we use a series length of one year on hourly resolution
        int n1 = 365 * 24 * scale;

        // this is the max density
        int rhoMAX = 550;

        // this is the max distance between two cosequent events
        int dt_MAX = 100;

        // Here we collect the sereis
        Vector<Messreihe> vmr = new Vector<Messreihe>();

        int rho = 0;

        // for this experiment we create a series which is shown later in a plot
        Messreihe mr_Q_SHIFT_fixed_rho = new Messreihe();
        mr_Q_SHIFT_fixed_rho.setLabel("Q_" + n1 + "_" + dt_MAX + "_" + rho + "_" + k_MAX);

        Messreihe mr_q_SHIFT_fixed_rho = new Messreihe();
        mr_q_SHIFT_fixed_rho.setLabel("q_" + n1 + "_" + dt_MAX + "_" + rho + "_" + k_MAX);

        Messreihe mr_Q_t = new Messreihe();
        mr_Q_t.setLabel("Q_t");

        Messreihe mr_q_t = new Messreihe();
        mr_q_t.setLabel("q_t");
        
        op.initFolder( "experiment_ES1___length_" + n1 );

        op.createLogFile("global_experiment_ES1.log");


        /**
         * Repeat the experiment for several densities rho starting with 100 in
         * increments of 50
         *
         * For each experiment we create a pair of time series to show the
         * results Q and q as a function of the shift parameter t.
         */
        for (rho = 100; rho < rhoMAX; rho = rho + 100) {

//            // for this experiment we create a series which is shown later in a plot
//            Messreihe mr_Q_SHIFT_fixed_rho = new Messreihe();
//            mr_Q_SHIFT_fixed_rho.setLabel("Q_" + n1 + "_" + dt_MAX + "_" + rho + "_" + k_MAX);
//
//            Messreihe mr_q_SHIFT_fixed_rho = new Messreihe();
//            mr_q_SHIFT_fixed_rho.setLabel("q_" + n1 + "_" + dt_MAX + "_" + rho + "_" + k_MAX);
            /**
             * Now we iterate through time. The second series is shifted against
             * the first one by dt.
             */
            for (int dt = 1; dt < dt_MAX; dt = dt + 1) {

                double sumX = 0;
                double sumY = 0;
                double sumY2 = 0;

                /**
                 * We repeat this experiment several times.
                 */
                for (int k = 0; k < k_MAX; k++) {

                    int r1[] = ESCalc2.createEventTS_INCREMENT(n1, rho);

                    int[] r1_IETS = ESCalc2.getEventIndexSeries(r1);

                    double aviet = ESCalc2.getAVIET(r1);
 
                    int r2[] = ESCalc2.moveEventRows(r1, dt);     // negatives q

//                    ESCalc2.checkRows(r1, r2);
                    
                    int[] r2_IETS = ESCalc2.getEventIndexSeries(r2);

                    double[] esTEST1 = ESCalc2.calcES(r1_IETS, r1_IETS);
                    double[] esTEST2 = ESCalc2.calcES(r2_IETS, r2_IETS);

                    double[] esTEST3 = ESCalc2.calcES(r1_IETS, r2_IETS);
 
                    double x = (double) dt / ((double) aviet);

                    sumX = sumX + x;
                    sumY = sumY + esTEST3[0];
                    sumY2 = sumY2 + esTEST3[1];
                    
                    if ( !aggregate ) {
                    
                        mr_Q_SHIFT_fixed_rho.addValuePair(x, esTEST3[0] - 0.25);
                        mr_q_SHIFT_fixed_rho.addValuePair(x, esTEST3[1]);

                        mr_Q_t.addValuePair(x, 0.25 + 0.75 * Math.exp(-4.0 * x ));
                        mr_q_t.addValuePair(x, -1.0 * Math.exp(-5 * x));

                    }
                    

                }

                if ( aggregate ) {
                    double x = sumX / k_MAX;
                        mr_Q_SHIFT_fixed_rho.addValuePair(x, sumY / k_MAX );
                        mr_q_SHIFT_fixed_rho.addValuePair(x, sumY2 / k_MAX);

                        mr_Q_t.addValuePair(x, 0.25 + 0.75 * Math.exp(-4.0 * x));
                        mr_q_t.addValuePair(x, -1.0 * Math.exp(-5 * x));
                }

                System.out.println(sumY / (double) k_MAX + " " + sumX / (double) k_MAX + " " + rho + " " + dt);
            }
 

        
 

            vmr.add(mr_Q_SHIFT_fixed_rho);
            vmr.add(mr_q_SHIFT_fixed_rho);

            vmr.add(mr_Q_t);
            vmr.add(mr_q_t);

            MyXYPlot.xRangDEFAULT_MAX = 5;
            MyXYPlot.xRangDEFAULT_MIN = 0;
            MyXYPlot.yRangDEFAULT_MIN = -1.5;
            MyXYPlot.yRangDEFAULT_MAX = 1.5;

            String label = "rho_" + rho + "_n_" + n1 + "_scale_" + scale;

            MyXYPlot.open(vmr, label, "dt / <iet>", "Q and q", true);

            MyXYPlot plot = MyXYPlot.openAndGet(vmr, label, "dt / <iet>", "Q and q", true);
            op.storePlotMyXYPlot(plot, label + "_sketch" );

            MesswertTabelle mwt = new MesswertTabelle(
                        "ESPropertiesChart1Tool_" + label + ".csv", 
                        "ESPropertiesChart1Tool_" + label + "_data_table.csv");
            mwt.setMessReihen(vmr);

            op.storeMesswertTabelle(mwt);
            
            vmr.clear();

        }
            
        op.closeAllWriter();

    }

}
