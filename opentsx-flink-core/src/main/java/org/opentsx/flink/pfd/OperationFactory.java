package org.opentsx.flink.pfd;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.flink.serdes.TimeSeriesObjectTypeInfo;

public class OperationFactory {

    public static DataStream<TimeSeriesObject> apply(
            DataStream<TimeSeriesObject> stream,
            PFDStep step) {

        switch (step.operation) {
            case "NORMALIZE":
                return applyNormalize(stream, step);
            case "DFA":
                return applyDFA(stream, step);
            // Add other operations (FILTER, DECOMPOSE, etc.) here
            default:
                throw new IllegalArgumentException("Unknown operation: " + step.operation);
        }
    }

    private static DataStream<TimeSeriesObject> applyNormalize(
            DataStream<TimeSeriesObject> stream,
            PFDStep step) {

        return stream.map(ts -> {
            // Simple normalization example
            // In a real implementation, use parameters to choose method (z-score, min-max)
            // ts.normalize(); // Assuming this method exists or implementing manual z-score
            ts.calcAverage();
            // Manual z-score for now if normalize() isn't available on TSO directly without
            // args
            // Implementing a simple zero-mean shift
            for (int i = 0; i < ts.yValues.size(); i++) {
                ts.yValues.set(i, ((Double) ts.yValues.get(i)) - ts.getAvarage());
            }
            return ts;
        }).returns(new TimeSeriesObjectTypeInfo()).name(step.id + ": Normalize");
    }

    private static DataStream<TimeSeriesObject> applyDFA(
            DataStream<TimeSeriesObject> stream,
            PFDStep step) {

        return stream.map(ts -> {
            // Placeholder for DFA analysis
            // Real implementation would invoke OpenTSx DFA algorithms
            ts.setAddinfo("dfa_processed=true");
            return ts;
        }).returns(new TimeSeriesObjectTypeInfo()).name(step.id + ": DFA");
    }
}
