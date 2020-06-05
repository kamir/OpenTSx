package org.opentsx.lg.metrics;

public interface TSGMBean {

    // How many time series have been processed since the beginning of the task?
    public int getTotalseriesCount();

    // How much time was needed to get the
    public double getAverageDurationOfRound();

}
