package org.opentsx.app.bucketanalyser;

import java.util.Vector;
import org.opentsx.data.series.TimeSeriesObject;

/**
 *
 * @author kamir
 */
public interface ICorrelator extends TSTool {
    
    public void calcSingleBucketCorrelations(Vector<TimeSeriesObject> series, String label );

    public void setTSOperationControlerPanel(TSOperationControlerPanel fcp);
    
}
