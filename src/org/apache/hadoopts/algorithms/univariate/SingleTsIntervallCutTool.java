package org.apache.hadoopts.algorithms.univariate;

/**
 *  SingleTsFilterTool 
 *  arbeitet mit einer Zeitreihe und schreibt ggf. in zwei
 *  verschiedene FileWriter.
 * 
 *  
 */


import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.hadoopts.core.SingleRowTSO;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class SingleTsIntervallCutTool extends SingleRowTSO {
    
    Vector<Integer> ids;
    Hashtable<Integer, Integer> von;
    Hashtable<Integer, Integer> bis;
    
    @Override
    public TimeSeriesObject processReihe(FileWriter fw, TimeSeriesObject reihe, FileWriter explodeWriter ) throws Exception {

        int l = reihe.getLabel().length()-1;
        Integer id = Integer.parseInt( reihe.getLabel().substring(0, l)  );
        
        if ( ids.contains(id) ) {
            
            int von1 = von.get(id);
            int bis1 = bis.get(id);

            TimeSeriesObject binned = reihe.setBinningX_sum(24);
            double sumAll = StatUtils.sum( binned.getYData() );

            int n = 60;
            TimeSeriesObject a = binned.cutOut( von1, von1 + n );
            TimeSeriesObject b = binned.cutOut( von1 + n, von1 + n + n );


            StandardDeviation stdev = new StandardDeviation();

            double sum1 = StatUtils.sum( a.getYData() );
            double sum2 = StatUtils.sum( b.getYData() );
            double std1 = stdev.evaluate( a.getYData() );
            double std2 = stdev.evaluate( b.getYData() );
            
            
            String line = sumAll + "\t";
            line = line.concat( von1 + "\t" + bis1 + "\t"  + sum1 + "\t" + std1 + "\t"  + sum2 + "\t" + std2 );

            try {
                fw.write( "" + id + "\t" + line + "\n" );
                fw.flush();
            } 
            catch (IOException ex) {
                Logger.getLogger(SingleTsIntervallCutTool.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        return reihe;
        
    }
    
}
