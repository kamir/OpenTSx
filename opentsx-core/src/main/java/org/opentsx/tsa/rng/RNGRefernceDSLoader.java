/*
 * JCuda - Java bindings for NVIDIA CUDA
 *
 * Copyright 2008-2016 Marco Hutter - http://www.jcuda.org
 */
package org.opentsx.tsa.rng;

import org.opentsx.core.TSBucket;
import org.opentsx.tsbucket.TSBucketStore;
import org.semanpix.chart.simple.MultiChart;

/**
 * This application reads a time series bucket from a TSBucketStore.<br/>
 */
public class RNGRefernceDSLoader {

    public static void main(String args[]) {

        TSBucket tsb1 = TSBucketStore.getReader().loadBucket_Cassandra( ReferenceDataset.CASSANDRA_KS, ReferenceDataset.CASSANDRA_TN );

        TSBucket tsb2 = TSBucketStore.getReader().loadBucket_Kafka( "TSO-collection-" + ReferenceDataset.EXPERIMENT__TAG, 20 );

        String title = "Referenc Dataset for OpenSTx Demos ";
        String tx = "t";
        String ty = "random";

        MultiChart.setSmallFont();

        MultiChart.open(tsb1, title + " 1", tx, ty, false);
        MultiChart.open(tsb2, title + " 2", tx, ty, false);

    }

}


