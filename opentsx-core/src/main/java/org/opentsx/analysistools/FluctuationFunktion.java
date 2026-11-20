package org.opentsx.analysistools;

import org.opentsx.analysistools.utils.FitbereichLogScale;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author root
 */
public class FluctuationFunktion {

    private static final Logger LOG = LoggerFactory.getLogger(FluctuationFunktion.class);

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

    /**
     * Fits alpha, the slope of F(s), in the range of fb.
     *
     * @return the slope; NaN when the values of F(s) in the range have fewer than two different x values (none,
     *         one, or all with the same x), as SimpleRegression.getSlope returns it
     * @throws IllegalStateException naming the series, with the cause, when F(s) has no values or cannot be fitted
     */
    public double calcAlpha( FitbereichLogScale fb ) {
        double fu = fb.getULog10();
        double fo = fb.getOLog10();
        double alpha;
        try {
            alpha = Fs.linFit( fu, fo).getSlope();
        }
        catch (Exception ex) {
            throw new IllegalStateException( label + ": alpha cannot be fitted in [" + fu + ", " + fo
                    + "]; expected a fluctuation function with values", ex );
        }
        LOG.debug("alpha recomputed series={} fitMin={} fitMax={} alpha={} beta={}", label, fu, fo, alpha,
                calcBeta(alpha));
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
