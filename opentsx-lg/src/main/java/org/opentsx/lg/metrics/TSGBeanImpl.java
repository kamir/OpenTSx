package org.opentsx.lg.metrics;


import javax.management.StandardMBean;

public class TSGBeanImpl extends StandardMBean implements TSGMBean {

    double avgDurationOfRound = 0.0;

    int zRounds = 0;
    double totalTimeInRounds = 0;

    int zSeries = 0;


    public TSGBeanImpl() {
        super(TSGMBean.class, false);
    }

    @Override
    public int getTotalseriesCount() {
        return zSeries;
    }

    @Override
    public double getAverageDurationOfRound() {
        return avgDurationOfRound;
    }

    public void trackNumberOfTS( int z ){
        zSeries = zSeries + z;
    }

    public void trackRoundDuration( double t ) {
        zRounds = zRounds + 1;
        totalTimeInRounds = totalTimeInRounds + t;
        avgDurationOfRound = (double)totalTimeInRounds / (double)zRounds;
    }

}
