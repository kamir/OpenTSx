package org.apache.hadoopts.statphys.ris.experimental.agh;

import org.apache.hadoopts.data.RNGWrapper;
import org.apache.hadoopts.data.series.TimeSeriesObject;

import org.apache.commons.math3.stat.regression.SimpleRegression;

public class StatistikTest {

    public static void main( String[] args ) throws Exception {

        RNGWrapper.init();

        TimeSeriesObject mr = TimeSeriesObject.getGaussianDistribution(25);
        TimeSeriesObject test1 = new TimeSeriesObject();
        TimeSeriesObject test2 = new TimeSeriesObject();

        for ( int i = 1; i < 20; i ++ ) {

            TimeSeriesObject mr2 = mr.scaleY_2((double) i );
            
            SimpleRegression reg1 = mr.linFit(5, 20);
            SimpleRegression reg2 = mr2.linFit(5, 20);
            
            test1.addValuePair(i,reg1.getRSquare()-reg2.getRSquare());
            test2.addValuePair(i,reg1.getRSquare()/reg2.getRSquare());
        }        

        System.out.println( test1 );
        System.out.println( test2 );

    };

}
