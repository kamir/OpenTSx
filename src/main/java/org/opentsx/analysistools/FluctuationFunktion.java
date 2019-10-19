/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.opentsx.analysistools;

import org.opentsx.analysistools.utils.FitbereichLogScale;
import org.opentsx.data.series.TimeSeriesObject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author root
 */
public class FluctuationFunktion {

    TimeSeriesObject Fs = null;

    /**
     * The TimeSeriesObject object is calculated by an DFA algorithm
     * We provide a label and wrap some specific functionality around it.
     * 
     * @param mr ...
     * @param label  ....
     */
    public FluctuationFunktion(TimeSeriesObject mr, String label ) {
        this.Fs = mr;
        this.label = label;
        Fs.setLabel( label + " " + Fs.getLabel() );
    };

    public double calcAlpha( FitbereichLogScale fb ) {
        double alpha = 0.0;
            try {

                double fu = fb.getULog10();
                double fo = fb.getOLog10();

                alpha = Fs.linFit( fu, fo).getSlope();
                
                System.out.println(">>> Alpha wurde neu berechnet ["+ fu +","+fo+"] ... a=" + alpha + " beta=" + calcBeta(alpha) );
            }
            catch (Exception ex) {
                Logger.getLogger(FluctuationFunktion.class.getName()).log(Level.SEVERE, null, ex);
                alpha = Double.NaN;
            }

        return alpha;
    };

    public double calcBeta( FitbereichLogScale fb ) {
        double alpha = calcAlpha(fb);
        return 2.0 * alpha - 1.0;
    };

    public double calcBeta( double alpha ) {
        return 2.0 * alpha - 1.0;
    };

    String label = null;
    public String getLabel() {
        return label;
    }
    
    public TimeSeriesObject getFs() {
        return Fs;
    }

}
