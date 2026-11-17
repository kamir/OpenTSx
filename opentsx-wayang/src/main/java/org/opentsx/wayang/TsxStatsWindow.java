package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.stats.StatsEngine;

import java.time.Duration;

/**
 * {@code stats.welford-ddsketch}: windowed statistics per series ({@code SeriesStats}) from episodes and observations,
 * computed by {@link StatsEngine} of {@code opentsx-stats}.
 *
 * @param node id of the DAG node; key of its state in {@link TsxState}
 */
public class TsxStatsWindow extends UnaryToUnaryOperator<SpecificRecord, SpecificRecord> {

    private final String node;
    private final StatsEngine.Config config;
    private final TsxState state;
    private final boolean complete;
    private final String windowSeriesId;
    private final long windowStartMicros;

    public TsxStatsWindow(String node, StatsEngine.Config config, TsxState state) {
        this(node, config, state, false, null, 0);
    }

    private TsxStatsWindow(String node, StatsEngine.Config config, TsxState state, boolean complete,
                           String windowSeriesId, long windowStartMicros) {
        super(DataSetType.createDefault(SpecificRecord.class), DataSetType.createDefault(SpecificRecord.class), false);
        this.node = node;
        this.config = config;
        this.state = state;
        this.complete = complete;
        this.windowSeriesId = windowSeriesId;
        this.windowStartMicros = windowStartMicros;
    }

    public static TsxStatsWindow bounded(String node, StatsEngine.Config config, TsxState state) {
        return new TsxStatsWindow(node, config, state, true, null, 0);
    }

    public static TsxStatsWindow window(String node, StatsEngine.Config config, TsxState state,
                                       String seriesId, long startMicros) {
        if (seriesId == null || seriesId.isBlank()) {
            throw new IllegalArgumentException("statistics window needs a non-empty series id");
        }
        if (config.windows().isEmpty()) {
            throw new IllegalArgumentException("statistics window needs at least one duration");
        }
        for (Duration window : config.windows()) {
            long size = window.toNanos() / 1000;
            if (Math.floorMod(startMicros, size) != 0) {
                throw new IllegalArgumentException("statistics window start must be aligned to " + window);
            }
            Math.addExact(startMicros, size);
        }
        return new TsxStatsWindow(node, config, state, true, seriesId, startMicros);
    }

    protected TsxStatsWindow(TsxStatsWindow that) {
        super(that);
        this.node = that.node;
        this.config = that.config;
        this.state = that.state;
        this.complete = that.complete;
        this.windowSeriesId = that.windowSeriesId;
        this.windowStartMicros = that.windowStartMicros;
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

    public boolean complete() {
        return complete;
    }

    public String windowSeriesId() {
        return windowSeriesId;
    }

    public long windowStartMicros() {
        return windowStartMicros;
    }
}
