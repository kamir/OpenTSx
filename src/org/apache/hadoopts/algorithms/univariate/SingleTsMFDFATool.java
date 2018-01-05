package org.apache.hadoopts.algorithms.univariate;

/*
 *  wird auf eine einzelne Reihe angewendet ...
 * 
 *  schreibt in eine Datei ...
 * 
 */


import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.hadoopts.core.SingleRowTSO;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer explodeWriter ) throws Exception {

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
     *
     * @param resultWriter
     * @param reihe ..
     * @return ...
     * @throws Exception
     */
    public String processReihe(Writer resultWriter, TimeSeriesObject reihe) throws Exception {
        return "... (" + this.getClass().getName() + ") ";
    }
    
}
