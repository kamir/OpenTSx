package org.opentsx.flink.pfd;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.opentsx.core.pfd.PFDValidation;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.flink.serdes.TimeSeriesObjectTypeInfo;

public class OperationFactory {

    public static DataStream<TimeSeriesObject> apply(
            DataStream<TimeSeriesObject> stream,
            PFDStep step) {
        if (step == null) {
            throw new IllegalArgumentException("PFD step is required");
        }
        PFDValidation.validateOperation(step.operation, step.params);
        return stream.map(ts -> {
            ts.calcAverage();
            double mean = ts.getAvarage();
            for (int i = 0; i < ts.yValues.size(); i++) {
                ts.yValues.set(i, ((Double) ts.yValues.get(i)) - mean);
            }
            ts.calcAverage();
            return ts;
        }).returns(new TimeSeriesObjectTypeInfo()).name(step.id + ": Center");
    }
}
