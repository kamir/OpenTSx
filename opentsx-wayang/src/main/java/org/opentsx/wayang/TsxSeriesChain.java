package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.retrieval.clean.SeriesStream;

import java.util.List;

/**
 * A run of consecutive per-series operators of the clean family ({@code dq.checks}, {@code convert.unit},
 * {@code resample}, {@code model.wind-shear}, {@code model.power-curve}, {@code feature.lag}, {@code feature.rolling};
 * {@code CleanChain.OPS}) as one Wayang operator. The steps share one state per series and the written series gets
 * its key and episode ids once, after the last step, as the processor in {@code opentsx-retrieval} does (S2.3 of the
 * specification).
 *
 * @param node     id of the last DAG node of the run; key of its state in {@link TsxState}
 * @param nodes    the DAG nodes of the run, in order
 * @param expected ids of the input series to read; empty for all
 */
public class TsxSeriesChain extends UnaryToUnaryOperator<SpecificRecord, SpecificRecord> {

    private final String node;
    private final List<JsonNode> nodes;
    private final SeriesStream.Target target;
    private final List<String> expected;
    private final TsxState state;

    public TsxSeriesChain(String node, List<JsonNode> nodes, SeriesStream.Target target, List<String> expected,
                          TsxState state) {
        super(DataSetType.createDefault(SpecificRecord.class), DataSetType.createDefault(SpecificRecord.class), false);
        this.node = node;
        this.nodes = List.copyOf(nodes);
        this.target = target;
        this.expected = List.copyOf(expected);
        this.state = state;
    }

    protected TsxSeriesChain(TsxSeriesChain that) {
        super(that);
        this.node = that.node;
        this.nodes = that.nodes;
        this.target = that.target;
        this.expected = that.expected;
        this.state = that.state;
    }

    public String node() {
        return node;
    }

    public List<JsonNode> nodes() {
        return nodes;
    }

    public SeriesStream.Target target() {
        return target;
    }

    public List<String> expected() {
        return expected;
    }

    public TsxState state() {
        return state;
    }
}
