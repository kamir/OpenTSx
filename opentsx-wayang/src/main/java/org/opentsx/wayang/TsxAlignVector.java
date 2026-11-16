package org.opentsx.wayang;

import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.retrieval.vector.VectorAligner;

import java.util.Map;
import java.util.Set;

/**
 * {@code align.vector} on Wayang: measuring points of several inputs on one clock, computed by {@code VectorStream}
 * of {@code opentsx-retrieval} with inputs of their own (S1.7, catalog 1). The state survives in {@link TsxState}.
 *
 * @param expectedByInput input name to the ids of its series, in the order of the inputs
 * @param flush           true to hand out the open ticks at the end of the input, as at the end of the data
 */
public class TsxAlignVector extends UnaryToUnaryOperator<TsxTagged, TsxVectorRecord> {

    private final String node;
    private final String vector;
    private final String dag;
    private final String specHash;
    private final VectorAligner.Params params;
    private final Map<String, Set<String>> expectedByInput;
    private final long latenessMicros;
    private final boolean flush;
    private final TsxState state;

    public TsxAlignVector(String node, String vector, String dag, String specHash, VectorAligner.Params params,
                          Map<String, Set<String>> expectedByInput, long latenessMicros, boolean flush, TsxState state) {
        super(DataSetType.createDefault(TsxTagged.class), DataSetType.createDefault(TsxVectorRecord.class), false);
        this.node = node;
        this.vector = vector;
        this.dag = dag;
        this.specHash = specHash;
        this.params = params;
        this.expectedByInput = expectedByInput;
        this.latenessMicros = latenessMicros;
        this.flush = flush;
        this.state = state;
    }

    protected TsxAlignVector(TsxAlignVector that) {
        super(that);
        this.node = that.node;
        this.vector = that.vector;
        this.dag = that.dag;
        this.specHash = that.specHash;
        this.params = that.params;
        this.expectedByInput = that.expectedByInput;
        this.latenessMicros = that.latenessMicros;
        this.flush = that.flush;
        this.state = that.state;
    }

    public String node() {
        return node;
    }

    public String vector() {
        return vector;
    }

    public String dag() {
        return dag;
    }

    public String specHash() {
        return specHash;
    }

    public VectorAligner.Params params() {
        return params;
    }

    public Map<String, Set<String>> expectedByInput() {
        return expectedByInput;
    }

    public long latenessMicros() {
        return latenessMicros;
    }

    public boolean flush() {
        return flush;
    }

    public TsxState state() {
        return state;
    }
}
