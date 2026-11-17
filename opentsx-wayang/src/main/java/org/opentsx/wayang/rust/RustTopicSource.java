package org.opentsx.wayang.rust;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnarySource;
import org.apache.wayang.core.types.DataSetType;

import java.util.List;
import java.util.Objects;

public class RustTopicSource extends UnarySource<SpecificRecord> {
    private final String topic;
    private final List<String> originalNodeIds;
    private final RustStageAction action;

    public RustTopicSource(String topic, List<String> originalNodeIds, RustStageAction action) {
        super(DataSetType.createDefault(SpecificRecord.class));
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("a Rust source needs a topic");
        if (originalNodeIds.isEmpty() || originalNodeIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("a Rust source needs original node IDs");
        }
        this.topic = topic;
        this.originalNodeIds = List.copyOf(originalNodeIds);
        this.action = Objects.requireNonNull(action);
        setName("Rust topic source " + topic);
    }

    protected RustTopicSource(RustTopicSource that) {
        super(that);
        this.topic = that.topic;
        this.originalNodeIds = that.originalNodeIds;
        this.action = that.action;
    }

    public String topic() {
        return topic;
    }

    public List<String> originalNodeIds() {
        return originalNodeIds;
    }

    public RustStageAction action() {
        return action;
    }
}
