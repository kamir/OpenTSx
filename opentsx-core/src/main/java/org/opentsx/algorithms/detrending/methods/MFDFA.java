package org.opentsx.algorithms.detrending.methods;

import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class MFDFA extends DFACore {

    private static final Logger LOG = LoggerFactory.getLogger(MFDFA.class);

    @Override
    public void calc() {
        double t1 = System.currentTimeMillis();
        initF();
        status.append("[");

        //create profile of timeline
        calcProfile();

        int zS = 0;

        // over all window sizes ...
        for (int currentS = 0; currentS < s.length; currentS++) {
            zS++;
            int valueofS = s[currentS];

            if(debug) LOG.debug("window index={} s={}", zS, valueofS);
            double FS = 0;

            int zw = 0;
            
            // move window over profile ...
            for (int pr_pos = 0;
                    pr_pos < pr.length - (valueofS - 1);
                    pr_pos = pr_pos + valueofS) {

                double FS_sum = 0;
                zw++;
                
                if(debug) LOG.debug("segment s={} start={} index={}", valueofS, pr_pos, zw);

                double[] fit = null;
                
                // size of array depends on order of dfa
                if (para.getGradeOfPolynom() == 1) {
                    fit = fit1(pr_pos, pr_pos + valueofS-1);
                }
                if (para.getGradeOfPolynom() == 2) {
                    fit = fitn(pr_pos, pr_pos + valueofS-1,
                            para.getGradeOfPolynom());
                }
                if (para.getGradeOfPolynom() > 2) {
                    fit = fitn(pr_pos, pr_pos + valueofS-1,
                            para.getGradeOfPolynom());
                }
                int z = 0;
                TimeSeriesObject mrFitN = null;
                if (currentS == 8) {
                    mrFitN = new TimeSeriesObject();
                    mrFitN.setLabel(pr_pos + " - " + (pr_pos + valueofS));
                }
                
                // sum of all (x tilde)^2
                for (int x = pr_pos; x < pr_pos + valueofS; x++) {

//                  calc y value of fit function depending on poly. degree
                    double fit_y = fx(x-pr_pos, fit);
                    if (currentS == 8) {
                        mrFitN.addValuePair( x, fit_y);
                    }
                    double x_s = (pr[x] - fit_y);  //x~ = pr_value - fit_val

                    FS_sum = FS_sum + Math.pow( x_s , 2.0 ); // x~^2;
                    
                }

                
                FS_sum = FS_sum / valueofS; // divided by the window size
                
                FS_sum = Math.pow(FS_sum, para.q * 0.5 );
                
                F[2][currentS] += FS_sum;
            }

            // the mean over the zw segments of this window size
            FS = F[2][currentS] / zw;  // / anzw:
            FS = Math.pow( FS, 1.0 / para.q ); // for MFDFA
            
            F[0][currentS] = valueofS; // s
            F[1][currentS] = zw;       // number of segments
            F[3][currentS] = FS;       // F(s)

            if(debug) LOG.debug("stored fluctuation s={} segments={} F={}", valueofS, zw, FS);
            zw = 0;

        }

        double t2 = System.currentTimeMillis();
        status.append("]");
        status.append("\n> Duration: " + ((t2 - t1) / 1000) + " s. ");
    }



    // needs the parameter object
    public void initIntervalS_version4() {

        int s_start = Math.abs( para.getGradeOfPolynom() ) + 2 ;
        int s_end = para.getN();

        para.setzSValues( s_end - s_start);
        s = new int[s_end - s_start];

        for (int i = 0; i < ( s_end-s_start ); i++) {
            s[i] = i+s_start;
        }
        if ( debug ) LOG.debug("window sizes s={}", Arrays.toString(s));
        initF();
    }

    public void initIntervalS_FULL() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
