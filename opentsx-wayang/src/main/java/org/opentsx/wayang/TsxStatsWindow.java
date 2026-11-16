package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.stats.StatsEngine;

/**
 * {@code stats.welford-ddsketch}: windowed statistics per series ({@code SeriesStats}) from episodes, computed by
 * {@link StatsEngine} of {@code opentsx-stats}.
 *
 * @param node id of the DAG node; key of its state in {@link TsxState}
 */
public class TsxStatsWindow extends UnaryToUnaryOperator<SpecificRecord, SpecificRecord> {

    private final String node;
    private final StatsEngine.Config config;
    private final TsxState state;

    public TsxStatsWindow(String node, StatsEngine.Config config, TsxState state) {
        super(DataSetType.createDefault(SpecificRecord.class), DataSetType.createDefault(SpecificRecord.class), false);
        this.node = node;
        this.config = config;
        this.state = state;
    }

    protected TsxStatsWindow(TsxStatsWindow that) {
        super(that);
        this.node = that.node;
        this.config = that.config;
        this.state = that.state;
    }

    public String node() {
        return node;
    }

    public StatsEngine.Config config() {
        return config;
    }

    public TsxState state() {
        return state;
    }
}
