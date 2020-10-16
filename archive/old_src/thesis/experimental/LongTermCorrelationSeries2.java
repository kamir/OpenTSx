package org.opentsx.thesis.experimental;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;
import org.opentsx.algorithms.detrending.DetrendingMethodFactory;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;

import java.util.Vector;

public class LongTermCorrelationSeries2 {

    public static void main(String args[]) throws Exception {

        RNGWrapper.init();

        TimeSeriesObject mr1 = getRandomRow( 64000, 0.9, true );
        TimeSeriesObject mr2 = getRandomRow( 64000, 1.2, false );
        
    }
    
    public static TimeSeriesObject getRandomRow(int length, double i, boolean showTest ) throws Exception {

        // Durch 4 TEILBAR !!!
        int N = length;

        /**
         * Vorbedingungen:
         *
         * Eine Zeitreihe mit bereinigten Werten, ohne Lücken, ausser
         * es hat einen fahlichen Sinn, Lücken zu nutzen.
         */
        double[] zr = new double[N];

        TimeSeriesObjectFFT d4 = (TimeSeriesObjectFFT) TimeSeriesObjectFFT.getGaussianDistribution(N);
        
        Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();
        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
        vr.add( d4 );
        
        zr = d4.getData()[1];

        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
        int order = dfa.getPara().getGradeOfPolynom();
        dfa.getPara().setzSValues( 500 );

        // Anzahl der Werte in der Zeitreihe
        dfa.setNrOfValues(N);

        // die Werte für die Fensterbreiten sind zu wählen ...
        dfa.initIntervalS();
        dfa.showS();

        // nun wird das Array mit den Daten der ZR übergeben
        TimeSeriesObjectFFT mr4_NEW = (TimeSeriesObjectFFT)d4;
        TimeSeriesObjectFFT temp = mr4_NEW.getModifiedTimeSeries_FourierFiltered( i );
        
        dfa.setZR(temp.getData()[1]);
        
        dfa.calc();
        
        TimeSeriesObject mr4 = dfa.getResultsMRLogLog();
        mr4.setLabel( d4.getLabel() + " (" + i + ")" );
        v.add(mr4);

        String status = dfa.getStatus();
        
        SimpleRegression alpha = mr4.linFit(1.2, 3.5);

        System.out.println( alpha );
        
        System.out.println( " alpha=" + alpha.getSlope() );
        System.out.println( "     i=" + i );

        if ( showTest ) MultiChart.open(v, "fluctuation function F(s) [order:" + order + "] ", "log(s)", "log(F(s))", false, "?", null);
        
        return temp;
    }
    

}
