package org.apache.hadoopts.algorithms.univariate;

/**
 *  SingleTsFilterTool 
 * 
 *  arbeitet mit einer Zeitreihe und schreibt ggf. in zwei
 *  verschiedene FileWriter.
 *  
 */


import org.apache.hadoopts.data.series.MRT;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.hadoopts.core.SingleRowTSO;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.hadoopts.statphys.detrending.DetrendingMethodFactory;
import org.apache.hadoopts.statphys.detrending.MultiDFATool4;
import org.apache.hadoopts.statphys.detrending.SingleDFATool;
import org.apache.hadoopts.statphys.detrending.methods.IDetrendingMethod;
import org.apache.hadoopts.app.bucketanalyser.BucketAnalyserTool;

/**
 *
 * @author kamir
 */
public class SingleTsPeakFilterTool extends SingleRowTSO {
    
    @Override
    public Messreihe processReihe( FileWriter fw, Messreihe reihe, FileWriter explodeWriter ) throws Exception {

        String line = "\t";
   
        // Detrending
        Messreihe normalized1 = MRT.normalizeByPeriodeTrend(reihe, 24*7);
        
        Messreihe peaks = normalized1.setBinningX_sum(24);
        peaks.add_to_Y( -24.0 );
        
        int[] tss = { 2, 4, 6, 8, 10 };
        line = line.concat( "Peaks:\t"  );
        for( int ts : tss ) {
            Vector<Integer> p = MRT.getPeaksDaysOverTS( ts , peaks );
            int nr = p.size();
            line = line.concat( nr + "\t" );
            for( int i : p ) {
                explodeWriter.write( reihe.getLabel() + "\t" + ts + "\t" + i + "\n" );
            }
        }

        try {
            fw.write( reihe.getLabel() + " " + reihe.summeY() + line + "\n" );
            fw.flush();
        } 
        catch (IOException ex) {
            Logger.getLogger(SingleTsPeakFilterTool.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        return reihe;
        
    }
    
}
