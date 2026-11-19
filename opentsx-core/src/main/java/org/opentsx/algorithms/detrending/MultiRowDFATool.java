/*
 *
 *   Computes a FluctuationFunktion for every MultiRowContainer; alpha can
 *   then be fitted in the default fit range.
 *
 */

package org.opentsx.algorithms.detrending;

import org.opentsx.analysistools.utils.FitbereichLogScale;
import org.opentsx.analysistools.FluctuationFunktion;
import org.opentsx.data.series.MultiRowContainer;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.generator.TestDataFactory;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Vector;

/**
 *
 * @author root
 */
public class MultiRowDFATool {

    private static final Logger LOG = LoggerFactory.getLogger(MultiRowDFATool.class);

    static FitbereichLogScale defaultFit = new FitbereichLogScale( 1.25, 2.0 );

    public static Vector<TimeSeriesObject> convertToMessreihe(Vector<FluctuationFunktion> vFs ) {
        Vector<TimeSeriesObject> mr = new Vector<TimeSeriesObject>();
        for( FluctuationFunktion m : vFs ) {

            mr.add( m.getFs() );
            LOG.debug("fluctuation function label={} alpha={}", m.getLabel(), m.calcAlpha(defaultFit));

        };
        return mr;
    }

    MultiRowContainer cont = null;

    public static Vector<FluctuationFunktion> _calcMultiRowsDFA( MultiRowContainer cont, int order ) {

        Vector<FluctuationFunktion> v = new Vector<FluctuationFunktion>();


        for( String l : cont.getLabels() ) {

            // without the limit of N/4
            MultiDFATool2 tool = new MultiDFATool2();

            Vector<TimeSeriesObject> r = cont.getRowSets().get(l);
            LOG.debug("running DFA container={} first={} series={}", l, r.elementAt(0).getLabel(), r.size());
            tool.runDFA( r, 2 );

            FluctuationFunktion fs = new FluctuationFunktion( tool.finalFS, l );

            v.add( fs );
        }

        return v;
    }

    public static void main( String[] args ) {

        RNGWrapper.init();

        Vector<TimeSeriesObject> mr1 = new Vector<TimeSeriesObject>();
        for ( int a = 0; a < 10; a++ ) {
            TimeSeriesObject m = TestDataFactory.getDataSeriesRandomValues_RW(500);
            mr1.add(m);
        }

        Vector<TimeSeriesObject> mr2 = new Vector<TimeSeriesObject>();
        for ( int a = 0; a < 10; a++ ) {
            TimeSeriesObject m = TestDataFactory.getDataSeriesRandomValues2(500);
            mr2.add(m);
        }

        Vector<TimeSeriesObject> mr3 = new Vector<TimeSeriesObject>();
        for ( int a = 0; a < 10; a++ ) {
            TimeSeriesObject m = TestDataFactory.getDataSeriesRandomValues3(500);
            mr3.add(m);
        }


        Vector<TimeSeriesObject> mr4 = new Vector<TimeSeriesObject>();
        for ( int a = 0; a < 10; a++ ) {
            TimeSeriesObject m = TestDataFactory.getDataSeriesRandomValues_JAVA_CORE(500);
            mr4.add(m);
        }

        MultiRowContainer rmc = new MultiRowContainer();
        rmc.addNewRows(mr1, "Sorte 1");
        rmc.addNewRows(mr2, "Sorte 2");
        rmc.addNewRows(mr3, "Sorte 3");
        rmc.addNewRows(mr4, "Sorte 4");


        

    };

}
