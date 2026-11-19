/*
 *  Operates on a single time series
 *  and writes data into a single file.
 */
package org.opentsx.core;

import org.opentsx.data.series.TimeSeriesObject;

import java.io.Writer;

/**
 *
 * @author kamir
 */
public class SingleRowTSO extends TSOperation {
    
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer exploder ) throws Exception {
        
        fw.write( reihe.getLabel() + "\t" + reihe.summeY() + "\n" );
        fw.flush();
        
        return reihe;
        
    }
   
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Object para, Writer exploder ) throws Exception {
        
        fw.write( reihe.getLabel() + " " + reihe.summeY() + "\n" );
        fw.flush();
        
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

        resultWriter.write( r );
        resultWriter.flush();

        return r;

    }

}
