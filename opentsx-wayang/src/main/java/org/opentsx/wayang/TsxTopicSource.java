package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnarySource;
import org.apache.wayang.core.types.DataSetType;

/** Reads the OpenTSx records of one topic in fixed offset ranges; never joins a consumer group. */
public class TsxTopicSource extends UnarySource<SpecificRecord> {

    private final TsxRead read;

    public TsxTopicSource(TsxRead read) {
        super(DataSetType.createDefault(SpecificRecord.class));
        this.read = read;
    }

    protected TsxTopicSource(TsxTopicSource that) {
        super(that);
        this.read = that.read;
    }

    public TsxRead read() {
        return read;
    }
}
