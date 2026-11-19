package org.opentsx.algorithms.detrending.methods;

/**
 *
 * DFAmulti computes one F(s) curve for many time series, for example the
 * series of one phase, weighted by their length.
 *
 * A plain mean of the single F(s) curves would give the short series too
 * much weight.
 *
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Vector;

public class DFAmulti extends DFACore {

    private static final Logger LOG = LoggerFactory.getLogger(DFAmulti.class);

    public double[][] FSMW = null;

    int indexMaxS = 0;


    @Override
    public void calc() {
    
        _initFelder();

        //create profile of timeline
        calcProfile();

        int zS = 0;

        indexMaxS = this.getMaxIndex( this.para.getN() );

        if ( debug ) LOG.debug("window sizes values={} windows={} maxIndex={} maxS={}", this.para.getN(),
                this.para.getzSValues(), indexMaxS, s[indexMaxS]);

        // over all window sizes ...
        for (int currentS = 0; currentS < indexMaxS ; currentS++) {
            zS++;
            int valueofS = s[currentS];

            if(debug) LOG.debug("window index={} s={}", zS, valueofS);
            double FS = 0;

            // reset the segment counter
            int zw = 0;
            
            // move window over profile ...
            for (int pr_pos = 0;
                    pr_pos < ( pr.length - valueofS) ;
                    pr_pos = pr_pos + valueofS) { 

                double FS_sum = 0;
                zw++;
                if(debug) LOG.debug("segment s={} start={} index={} limit={}", valueofS, pr_pos, zw, pr.length - valueofS);

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

                // sum of all (x tilde)^2
                for (int x = pr_pos; x < pr_pos + valueofS; x++) {

                    // calc y value of fit function depending on poly. degree
                    double fit_y = fx(x-pr_pos, fit);

                    if ( Double.isNaN( pr[x] ) )
                        z = 1;

                    double x_s = (pr[x] - fit_y);  //x~ = pr_value - fit_val


                    FS_sum += x_s * x_s; // x~^2;

                }

                FS_sum = FS_sum / valueofS; // divided by the window size

                F[2][currentS] = F[2][currentS] + FS_sum;
            }
    
            // the mean over the zw segments of this window size
            FS = Math.sqrt(F[2][currentS] / zw);  // / anzw:

            F[0][currentS] = valueofS; // s
            F[1][currentS] = zw;       // number of segments
            F[3][currentS] = FS;       // F(s)

            FSMW[0][currentS] = F[2][currentS];  // sum of xs^2 / s
            FSMW[1][currentS] = F[1][currentS];  // number of segments

            double a = FSMW[0][currentS];
            double b = FSMW[1][currentS];


            if(debug) LOG.debug("stored fluctuation s={} segments={}", valueofS, zw);
            
            zw = 0;
        }

        double t2 = System.currentTimeMillis();

    }

    /**
     * Chooses the window sizes from s_min to s_max.
     */
    public void initIntervalS_FULL( int s_min, int s_max ) {

        rangeS = s_max-s_min;
        LOG.debug("linear window sizes min={} max={} count={}", s_min, s_max, rangeS);

        sMax = rangeS;

        para.setzSValues(rangeS);

        s = new int[rangeS];

        s[0] = s_min;
        s[para.getzSValues()-1] = s_max;

        int step = 1;

        for ( int i = 1; i < rangeS; i++ ) {
            s[i] = s_min + step * i;
        }
    }

    public void initIntervalS_FULL2( int s_min, int s_max ) {

        Vector<Double> lin = new Vector<Double>();
        Vector<Double> log = new Vector<Double>();
        
        for ( int sv = s_min; sv < s_min + 200; sv++ ) {
            lin.add(sv * 1.0);
        }
        
        double offset = s_min + 200;
        double sv = offset;
        
        double f = DFACore.S_SCALE_FACTOR;
        while( sv < s_max) {
            f = f * DFACore.S_SCALE_FACTOR;
            sv = offset + f;
            
            if ( !log.contains( sv ) ) log.add( sv );
        }
        
        
        rangeS = lin.size() + log.size();

        LOG.debug("window sizes min={} max={} count={}", s_min, s_max, rangeS);
        
        para.setzSValues(rangeS);
        sMax = rangeS;

        s = new int[rangeS];

        int i = 0;
        for( double svec : lin ) {
            s[i] = (int)svec;
            i++;
        }
        
        for( double svec : log ) {
            s[i] = (int)svec;
            i++;
        }
    }

    
        /**
     * Chooses the window sizes from s_min to s_max.
     */
    
    public void initIntervalS_FULL_LOG( int s_min, int s_max ) {

        Vector<Integer> sss = new Vector<Integer>();
        int sx = 0;
        int i = 0;
        int j = 0;
        
        double bbb = S_SCALE_FACTOR;
        while ( sx < s_max ) {
            sx = (int)(Math.pow( bbb, i));
            if ( !sss.contains(sx) ) {
                sss.add(sx);
                j++;
            }
            i = i + 1;
        } 
        
        rangeS = j;

        para.setzSValues(rangeS);

        s = new int[rangeS];

        s[0] = s_min;
        
        int ix = 0;
        for( int x : sss ) {
           s[ix] = s_min + x;   
           ix++;
        }
        
        
    }


    int rangeS=0;
    public int sMax = 0;

    public void initIntervalS_version4() {
        this.initIntervalSlog();
    }

    public void initIntervalS_FULL() {
      throw new UnsupportedOperationException("Not supported any more.");
    };

    private int getMaxIndex(int laenge) {
        int max_ind = 0;
        for ( int vs = 0; vs < s.length; vs++ )  {
            if ( s[vs] < laenge ) max_ind = vs;
            else break;
        }
        return max_ind;
    }

    private void _initFelder() {
        initF();
        FSMW = new double[2][rangeS];
    }

}
