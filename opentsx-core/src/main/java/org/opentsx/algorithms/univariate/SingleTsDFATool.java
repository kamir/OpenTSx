package org.opentsx.algorithms.univariate;

import org.opentsx.data.series.MRT;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;
import org.opentsx.algorithms.detrending.SingleDFATool;

import java.io.Writer;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class SingleTsDFATool extends SingleRowTSO {

    Boolean doDFA_on_raw_data = false;
    Boolean doDFA_on_normalized_data = false;
                
    public SingleTsDFATool(String[] args) {
        
        doDFA_on_raw_data = Boolean.parseBoolean( args[2] );  
        doDFA_on_normalized_data = Boolean.parseBoolean( args[3] );  
        
    }
    
    /**
     *   OUTPUT is not collected, it is written directly to the FW 
     */
    @Override
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer explodeWriter ) throws Exception {

        String line = "\t";
   
        // normalize
        TimeSeriesObject normalized1 = MRT.normalizeByPeriodeTrend(reihe, 24*7 );
        
        Vector<TimeSeriesObject> vmr_raw = new Vector<TimeSeriesObject>();
        Vector<TimeSeriesObject> vmr_norm = new Vector<TimeSeriesObject>();
        
        int[] orders = {2};
        
        vmr_raw.add( reihe );
        vmr_norm.add( normalized1 );
        
        
        for ( int ord : orders) {
            if ( doDFA_on_normalized_data ) {
                SingleDFATool tool1 = new SingleDFATool();
                tool1.logLogResults = true;

                tool1.runDFA(vmr_norm, ord);
                String trends1 = tool1.getAlphas();
                line = line.concat(  "\t t1 :" + trends1 );

            }

            if( doDFA_on_raw_data ) {
                SingleDFATool tool2 = new SingleDFATool();
                tool2.logLogResults = true;

                tool2.runDFA(vmr_raw, ord);
                String trends2 = tool2.getAlphas();

                line = line.concat(  "\t t2: " + trends2 );
            }
        }

        fw.write( reihe.getLabel() + " " + reihe.summeY() + line + "\n" );
        fw.flush();
        
        return reihe;
        
    }
    
}
