package org.opentsx.wayang;

import org.apache.wayang.core.optimizer.cardinality.FixedSizeCardinalityEstimator;
import org.apache.wayang.core.plan.wayangplan.UnarySource;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.model.v2.SeriesStats;

import java.util.List;
import java.util.Objects;

public class TsxSiteStage extends UnarySource<SeriesStats> {
    @FunctionalInterface
    public interface Action {
        List<SeriesStats> execute();
    }

    private final Action action;

    public TsxSiteStage(long expectedRecords, Action action) {
        super(DataSetType.createDefault(SeriesStats.class));
        if (expectedRecords < 0) throw new IllegalArgumentException("expected aggregate count must be nonnegative");
        this.action = Objects.requireNonNull(action);
        setCardinalityEstimator(0, new FixedSizeCardinalityEstimator(expectedRecords));
        setName("Bounded JVM site stage");
    }

    protected TsxSiteStage(TsxSiteStage that) {
        super(that);
        action = that.action;
    }

    public Action action() {
        return action;
    }
}
