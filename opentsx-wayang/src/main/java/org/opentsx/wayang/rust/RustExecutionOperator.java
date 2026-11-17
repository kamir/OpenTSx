package org.opentsx.wayang.rust;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.optimizer.costs.ConstantLoadProfileEstimator;
import org.apache.wayang.core.optimizer.costs.LoadEstimate;
import org.apache.wayang.core.optimizer.costs.LoadProfile;
import org.apache.wayang.core.optimizer.costs.LoadProfileEstimator;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;

import java.util.List;
import java.util.Optional;

public interface RustExecutionOperator extends ExecutionOperator {
    enum Role { SOURCE, UNION, CHAIN, SINK }

    Role role();

    RustStageAction action();

    List<String> originalNodeIds();

    @Override
    default RustPlatform getPlatform() {
        return RustPlatform.getInstance();
    }

    @Override
    default List<ChannelDescriptor> getSupportedInputChannels(int index) {
        if (index < 0 || index >= getNumInputs()) throw new IllegalArgumentException("invalid Rust input index");
        return List.of(RustPlanChannel.DESCRIPTOR);
    }

    @Override
    default List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        if (index < 0 || index >= getNumOutputs()) throw new IllegalArgumentException("invalid Rust output index");
        return List.of(RustPlanChannel.DESCRIPTOR);
    }

    @Override
    default Optional<LoadProfileEstimator> createLoadProfileEstimator(Configuration configuration) {
        return Optional.of(new ConstantLoadProfileEstimator(new LoadProfile(new LoadEstimate(1), new LoadEstimate(0))));
    }
}
