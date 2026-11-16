package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnarySink;
import org.apache.wayang.core.types.DataSetType;

/** Writes OpenTSx records to one topic with the KafScale client settings. */
public class TsxTopicSink extends UnarySink<SpecificRecord> {

    private final String bootstrap;
    private final String topic;

    public TsxTopicSink(String bootstrap, String topic) {
        super(DataSetType.createDefault(SpecificRecord.class));
        this.bootstrap = bootstrap;
        this.topic = topic;
    }

    protected TsxTopicSink(TsxTopicSink that) {
        super(that);
        this.bootstrap = that.bootstrap;
        this.topic = that.topic;
    }

    public String bootstrap() {
        return bootstrap;
    }

    public String topic() {
        return topic;
    }
}
