package org.opentsx.wayang.rust;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnarySink;
import org.apache.wayang.core.types.DataSetType;

import java.util.List;
import java.util.Objects;

public class RustTopicSink extends UnarySink<SpecificRecord> {
    private final String topic;
    private final String fromNodeId;
    private final RustStageAction action;

    public RustTopicSink(String topic, String fromNodeId, RustStageAction action) {
        super(DataSetType.createDefault(SpecificRecord.class));
        if (topic == null || topic.isBlank() || fromNodeId == null || fromNodeId.isBlank()) {
            throw new IllegalArgumentException("a Rust sink needs a topic and original input node ID");
        }
        this.topic = topic;
        this.fromNodeId = fromNodeId;
        this.action = Objects.requireNonNull(action);
        setName("Rust topic sink " + topic);
    }

    protected RustTopicSink(RustTopicSink that) {
        super(that);
        this.topic = that.topic;
        this.fromNodeId = that.fromNodeId;
        this.action = that.action;
    }

    public String topic() {
        return topic;
    }

    public String fromNodeId() {
        return fromNodeId;
    }

    public List<String> originalNodeIds() {
        return List.of(fromNodeId);
    }

    public RustStageAction action() {
        return action;
    }
}
