package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Map;
import java.util.Set;

/**
 * A pair method ({@code pair.xcorr}, S1.7) on Wayang: compares every series of input {@code a} with every series of
 * input {@code b}, episode by episode, following {@code pairs.ts} of the workbench. Each pair range gives one result
 * episode per field, series {@code pair.<method>.<field>} with the tags a, b, window and step. Episodes wait in
 * {@link TsxState} until every series of the other input has passed their end.
 *
 * @param params the parameters of the DAG node: pair settings and those of the method
 */
public class TsxPair extends UnaryToUnaryOperator<TsxTagged, SpecificRecord> {

    public static final String A = "a";
    public static final String B = "b";

    private final String node;
    private final String method;
    private final Map<String, Object> params;
    private final Set<String> aSeries;
    private final Set<String> bSeries;
    private final String specHash;
    private final TsxState state;

    public TsxPair(String node, String method, Map<String, Object> params, Set<String> aSeries, Set<String> bSeries,
                   String specHash, TsxState state) {
        super(DataSetType.createDefault(TsxTagged.class), DataSetType.createDefault(SpecificRecord.class), false);
        this.node = node;
        this.method = method;
        this.params = params;
        this.aSeries = aSeries;
        this.bSeries = bSeries;
        this.specHash = specHash;
        this.state = state;
    }

    protected TsxPair(TsxPair that) {
        super(that);
        this.node = that.node;
        this.method = that.method;
        this.params = that.params;
        this.aSeries = that.aSeries;
        this.bSeries = that.bSeries;
        this.specHash = that.specHash;
        this.state = that.state;
    }

    public String node() {
        return node;
    }

    public String method() {
        return method;
    }

    public Map<String, Object> params() {
        return params;
    }

    public Set<String> aSeries() {
        return aSeries;
    }

    public Set<String> bSeries() {
        return bSeries;
    }

    public String specHash() {
        return specHash;
    }

    public TsxState state() {
        return state;
    }
}
