/*
 *  Operates on a singel time serie ...
 * 
 *  and writes data into a single file.
 * 
 */
package org.apache.hadoopts.hadoopts.core;

import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.io.IOException;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class SingleRowTSO extends TSOperation {
    
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer exploder ) throws Exception {
        
        try {
            fw.write( reihe.getLabel() + "\t" + reihe.summeY() + "\n" );
            fw.flush();
        } 
        catch (IOException ex) {
            Logger.getLogger(SingleRowTSO.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        return reihe;
        
    }
   
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Object para, Writer exploder ) throws Exception {
        
        try {
            fw.write( reihe.getLabel() + " " + reihe.summeY() + "\n" );
            fw.flush();
        } 
        catch (IOException ex) {
            Logger.getLogger(SingleRowTSO.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        return reihe;
        
    }

    @Override
    public void init() {

    }

    @Override
    public void finish() {

    }

    @Override
    public String getSymbol() {
        return "F_" + this.getClass().getSimpleName();
    }

    @Override
    public String processReihe(Writer resultWriter, TimeSeriesObject reihe) throws Exception {

        String r = reihe.getLabel() + "___" + this.getClass().getName() + " " + reihe.summeY() + "\n";

        try {

            resultWriter.write( r );
            resultWriter.flush();

        }
        catch (IOException ex) {
            Logger.getLogger(SingleRowTSO.class.getName()).log(Level.SEVERE, null, ex);
        }

        return r;

    }

}
