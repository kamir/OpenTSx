/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.hadoopts.app.bucketanalyser;

import java.util.Vector;
import org.apache.hadoopts.data.series.TimeSeriesObject;

/**
 *
 * @author kamir
 */
public interface ICorrelator extends TSTool {
    
    public void calcSingleBucketCorrelations(Vector<TimeSeriesObject> series, String label );

    public void setTSOperationControlerPanel(TSOperationControlerPanel fcp);
    
}
