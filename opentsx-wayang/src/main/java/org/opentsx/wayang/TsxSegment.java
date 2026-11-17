package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.retrieval.segment.Segmenter;

public class TsxSegment extends UnaryToUnaryOperator<SpecificRecord, SpecificRecord> {

    private final String node;
    private final Segmenter segmenter;
    private final TsxState state;
    private final boolean complete;

    public TsxSegment(String node, Segmenter segmenter, TsxState state) {
        this(node, segmenter, state, false);
    }

    private TsxSegment(String node, Segmenter segmenter, TsxState state, boolean complete) {
        super(DataSetType.createDefault(SpecificRecord.class), DataSetType.createDefault(SpecificRecord.class), false);
        if (segmenter.strategy() != SegmentationStrategy.FEATURE_THRESHOLD
                && segmenter.strategy() != SegmentationStrategy.FEATURE_EXTREMUM) {
            throw new IllegalArgumentException("segmentation operator supports threshold and extremum strategies only");
        }
        this.node = node;
        this.segmenter = segmenter;
        this.state = state;
        this.complete = complete;
    }

    public static TsxSegment bounded(String node, Segmenter segmenter, TsxState state) {
        return new TsxSegment(node, segmenter, state, true);
    }

    protected TsxSegment(TsxSegment that) {
        super(that);
        this.node = that.node;
        this.segmenter = that.segmenter;
        this.state = that.state;
        this.complete = that.complete;
    }

    public String node() {
        return node;
    }

    public Segmenter segmenter() {
        return segmenter;
    }

    public TsxState state() {
        return state;
    }

    public boolean complete() {
        return complete;
    }
}
