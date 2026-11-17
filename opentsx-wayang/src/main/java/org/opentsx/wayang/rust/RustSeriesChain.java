package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class RustSeriesChain extends UnaryToUnaryOperator<SpecificRecord, SpecificRecord> {
    private final List<String> originalNodeIds;
    private final List<String> operations;
    private final RustStageAction action;

    public RustSeriesChain(List<JsonNode> nodes, RustStageAction action) {
        super(DataSetType.createDefault(SpecificRecord.class), DataSetType.createDefault(SpecificRecord.class), false);
        if (nodes.isEmpty()) throw new IllegalArgumentException("a Rust cleaning chain must not be empty");
        List<String> ids = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            if (node == null || !node.isObject() || !node.path("id").isTextual()
                    || node.path("id").asText().isBlank() || !seen.add(node.path("id").asText())) {
                throw new IllegalArgumentException("a Rust cleaning chain needs unique original node IDs");
            }
            String op = node.path("op").asText();
            if (!Set.of("dq.checks", "convert.unit", "resample").contains(op)) {
                throw new IllegalArgumentException("unsupported Rust cleaning operator " + op);
            }
            if (op.equals("resample") && index + 1 != nodes.size()) {
                throw new IllegalArgumentException("Rust resample must be the trailing operator");
            }
            ids.add(node.path("id").asText());
            ops.add(op);
        }
        this.originalNodeIds = List.copyOf(ids);
        this.operations = List.copyOf(ops);
        this.action = Objects.requireNonNull(action);
        setName("Rust series chain " + String.join(",", ids));
    }

    protected RustSeriesChain(RustSeriesChain that) {
        super(that);
        this.originalNodeIds = that.originalNodeIds;
        this.operations = that.operations;
        this.action = that.action;
    }

    public List<String> originalNodeIds() {
        return originalNodeIds;
    }

    public List<String> operations() {
        return operations;
    }

    public RustStageAction action() {
        return action;
    }
}
