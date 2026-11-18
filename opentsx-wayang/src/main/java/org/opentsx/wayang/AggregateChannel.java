package org.opentsx.wayang;

import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.executionplan.Channel;
import org.apache.wayang.core.plan.wayangplan.OutputSlot;
import org.apache.wayang.core.platform.AbstractChannelInstance;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.Executor;
import org.opentsx.model.v2.SeriesStats;

import java.util.List;

public final class AggregateChannel extends Channel {
    public static final ChannelDescriptor DESCRIPTOR = new ChannelDescriptor(AggregateChannel.class, true, false);

    public AggregateChannel(ChannelDescriptor descriptor, OutputSlot<?> output) {
        super(descriptor, output);
        if (!DESCRIPTOR.equals(descriptor)) throw new IllegalArgumentException("unsupported aggregate channel descriptor");
    }

    private AggregateChannel(AggregateChannel that) {
        super(that);
    }

    @Override
    public AggregateChannel copy() {
        return new AggregateChannel(this);
    }

    @Override
    public ChannelInstance createInstance(Executor executor, OptimizationContext.OperatorContext context, int outputIndex) {
        return new Instance(executor, context, outputIndex);
    }

    public final class Instance extends AbstractChannelInstance {
        private List<SeriesStats> records;

        private Instance(Executor executor, OptimizationContext.OperatorContext context, int outputIndex) {
            super(executor, context, outputIndex);
        }

        public void accept(List<SeriesStats> records) {
            if (this.records != null) throw new IllegalStateException("aggregate channel was already produced");
            this.records = List.copyOf(records);
            setMeasuredCardinality(records.size());
        }

        public List<SeriesStats> records() {
            if (records == null) throw new IllegalStateException("aggregate channel has not been produced");
            return records;
        }

        @Override
        public AggregateChannel getChannel() {
            return AggregateChannel.this;
        }

        @Override
        protected void doDispose() {
            records = null;
        }
    }
}
