package org.opentsx.wayang;

import org.apache.wayang.core.plan.wayangplan.UnarySink;
import org.apache.wayang.core.types.DataSetType;

/** Writes {@code opentsx.vector.v1} measuring points to a vector topic, key = canonical text of the vector key. */
public class TsxVectorSink extends UnarySink<TsxVectorRecord> {

    private final String bootstrap;
    private final String topic;

    public TsxVectorSink(String bootstrap, String topic) {
        super(DataSetType.createDefault(TsxVectorRecord.class));
        this.bootstrap = bootstrap;
        this.topic = topic;
    }

    protected TsxVectorSink(TsxVectorSink that) {
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
