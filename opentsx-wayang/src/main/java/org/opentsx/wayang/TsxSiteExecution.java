package org.opentsx.wayang;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.optimizer.costs.ConstantLoadProfileEstimator;
import org.apache.wayang.core.optimizer.costs.LoadEstimate;
import org.apache.wayang.core.optimizer.costs.LoadProfile;
import org.apache.wayang.core.optimizer.costs.LoadProfileEstimator;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;

import java.util.List;
import java.util.Optional;

final class TsxSiteExecution extends TsxSiteStage implements ExecutionOperator {
    TsxSiteExecution(TsxSiteStage that) {
        super(that);
    }

    @Override
    public TsxSitePlatform getPlatform() {
        return TsxSitePlatform.getInstance();
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new TsxSiteExecution(this);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        throw new IllegalArgumentException("site stage has no input channels");
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        if (index != 0) throw new IllegalArgumentException("invalid aggregate output index");
        return List.of(AggregateChannel.DESCRIPTOR);
    }

    @Override
    public Optional<LoadProfileEstimator> createLoadProfileEstimator(Configuration configuration) {
        return Optional.of(new ConstantLoadProfileEstimator(new LoadProfile(new LoadEstimate(1), new LoadEstimate(0))));
    }
}
