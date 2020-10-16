/*
 * JCuda - Java bindings for NVIDIA CUDA
 *
 * Copyright 2008-2016 Marco Hutter - http://www.jcuda.org
 */
package org.opentsx.tsa.rng;

import org.opentsx.chart.simple.MultiChart;

/**
 * This application reads a time series bucket from a TSBucketStore.<br/>
 */
public class RNGExperiments3
{

    public static void main(String args[])
    {

        //TSBucket tsb = TSBucketStore.getReader().loadBucket_Cassandra("ks1", "tsb");

        String title = "RNGComparison TSB";
        String tx = "t";
        String ty = "random";

        MultiChart.setSmallFont();

        //MultiChart.open(tsb.getBucketData(), title, tx, ty, false);


    }

}
