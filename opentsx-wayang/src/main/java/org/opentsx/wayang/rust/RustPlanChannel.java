package org.opentsx.wayang.rust;

import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.executionplan.Channel;
import org.apache.wayang.core.plan.wayangplan.OutputSlot;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.Executor;

public final class RustPlanChannel extends Channel {
    public static final ChannelDescriptor DESCRIPTOR = new ChannelDescriptor(RustPlanChannel.class, true, false);

    public RustPlanChannel(ChannelDescriptor descriptor, OutputSlot<?> output) {
        super(descriptor, output);
        if (!DESCRIPTOR.equals(descriptor)) throw new IllegalArgumentException("unsupported Rust plan channel descriptor");
    }

    private RustPlanChannel(RustPlanChannel that) {
        super(that);
    }

    @Override
    public RustPlanChannel copy() {
        return new RustPlanChannel(this);
    }

    @Override
    public ChannelInstance createInstance(Executor executor, OptimizationContext.OperatorContext context, int outputIndex) {
        throw new UnsupportedOperationException("Rust plan channels carry topology only within one complete stage");
    }
}
