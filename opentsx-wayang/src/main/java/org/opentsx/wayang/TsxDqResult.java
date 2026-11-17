package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.stats.DataQuality;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

public class TsxDqResult extends UnaryToUnaryOperator<SpecificRecord, JsonNode> {

    private final String job;
    private final String site;
    private final String specHash;
    private final DataQuality.Expectation expectation;
    private final Set<DataQuality.Check> checks;
    private final OptionalLong nowMicros;

    public TsxDqResult(String job, String site, String specHash, DataQuality.Expectation expectation,
                       Set<DataQuality.Check> checks, OptionalLong nowMicros) {
        super(DataSetType.createDefault(SpecificRecord.class), DataSetType.createDefault(JsonNode.class), false);
        if (job == null || job.isBlank() || site == null || site.isBlank()) {
            throw new IllegalArgumentException("dq.result needs a non-empty job and site");
        }
        if (specHash == null || !specHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("dq.result needs a 64-character lower-case hex specHash");
        }
        this.job = job;
        this.site = site;
        this.specHash = specHash;
        this.expectation = Objects.requireNonNull(expectation);
        this.checks = Set.copyOf(checks);
        this.nowMicros = Objects.requireNonNull(nowMicros);
    }

    protected TsxDqResult(TsxDqResult that) {
        super(that);
        this.job = that.job;
        this.site = that.site;
        this.specHash = that.specHash;
        this.expectation = that.expectation;
        this.checks = that.checks;
        this.nowMicros = that.nowMicros;
    }

    public String job() { return job; }
    public String site() { return site; }
    public String specHash() { return specHash; }
    public DataQuality.Expectation expectation() { return expectation; }
    public Set<DataQuality.Check> checks() { return checks; }
    public OptionalLong nowMicros() { return nowMicros; }
}
