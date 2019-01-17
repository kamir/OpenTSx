package org.apache.hadoopts.app.experimental;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

/**
 * Created by kamir on 27.09.18.
 */
public class EventGenerationExperiment {

    public static void main(String[] args) {

        int SUM = 0;
        SummaryStatistics sampleStats = new SummaryStatistics();

        for (int i = 0; i < 100; i++ ) {
            System.out.print( i + " : " );
//            int v =  experiment1();
//            int v =  experiment2();
            int v =  experiment3();
            SUM =+ v;
            sampleStats.addValue(v);
        }

        System.out.println( "Max  : " + sampleStats.getMax() );
        System.out.println( "Min  : " + sampleStats.getMax() );
        System.out.println( "BAND : " + (sampleStats.getMax() - sampleStats.getMin()) );
        System.out.println( "STD  : " + sampleStats.getStandardDeviation() );
        System.out.println( "Mean : " + sampleStats.getMean() );

        System.out.println( SUM / 100.0 );

    }

    /**
     * Increment an Integer value as often as possible within 1 second.
     *
     * Overhead: Time lookup in each round.
     *
     * Result: approx 22 * 10^6 operations possible.
     *
     * @return
     */

    public static int experiment1() {

        long start = System.currentTimeMillis();
        long end = start + 1001;

        int i = 0;
        while( System.currentTimeMillis() < end ) {
            i++;
        }

        System.out.println( i );

        return i;
    }

    /**
     * Increment N times, where N is the average of possible increments including the time lookup.
     *
     * Question: Will a time lookup slow down the operation?
     *
     * @return
     */
    public static int experiment2() {

        long start = System.currentTimeMillis();

        int max = Integer.MAX_VALUE;

        int i = 0;

        for( int j = 0; j < max; j++ ) {
            while (i < max) {
                i++;
            }
        }

        long end = System.currentTimeMillis();

        long duration = end - start;

        System.out.println( duration + " : " + i);

        return (int)duration;
    }

    public static int experiment3() {

        int max = Integer.MAX_VALUE;

        int z = 0;
        long step = 0;
        long start = System.currentTimeMillis();
        for( int i = 0; i < max; i++ ) {
            step = System.currentTimeMillis();
            z++;
        }

        double avg_t = (step-start)/1000.00/(double)max;

        System.out.println( avg_t + "  #   " + step + " * " + z );
        return (int) avg_t;
    }
}
