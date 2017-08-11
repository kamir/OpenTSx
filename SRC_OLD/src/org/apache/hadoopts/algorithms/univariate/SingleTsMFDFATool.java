package org.apache.hadoopts.algorithms.univariate;

/*
 *  wird auf eine einzelne Reihe angewendet ...
 * 
 *  schreibt in eine Datei ...
 * 
 */


import org.apache.hadoopts.algorithms.univariate.SingleTsRISTool;
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
public class SingleTsMFDFATool extends SingleRowTSO {
                
    public SingleTsMFDFATool(String[] args) {
        
    }
    
       /**
     *   OUTPUT is not collected, it is written directly to the FW 
     */
    @Override
    public Messreihe processReihe( FileWriter fw, Messreihe reihe, FileWriter explodeWriter ) throws Exception {

        String line = "\t";
        
        try {
            fw.write( reihe.getLabel() + "\t" + line + "\n" );
            fw.flush();
        } 
        catch (IOException ex) {
            Logger.getLogger(SingleTsRISTool.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        return reihe;
        
    }
    
    /**
     * 
     * @param reihe
     * @return
     * @throws Exception 
     */
    public String processReihe(Messreihe reihe) throws Exception {
        return "... (" + this.getClass().getName() + ") ";
    }
    
}
