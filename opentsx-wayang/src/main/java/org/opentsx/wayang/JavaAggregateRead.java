package org.opentsx.wayang;

import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.opentsx.model.v2.SeriesStats;

import java.util.Collection;
import java.util.List;

final class JavaAggregateRead extends UnaryToUnaryOperator<SeriesStats, SeriesStats> implements JavaExecutionOperator {
    JavaAggregateRead() {
        super(DataSetType.createDefault(SeriesStats.class), DataSetType.createDefault(SeriesStats.class), false);
    }

    private JavaAggregateRead(JavaAggregateRead that) {
        super(that);
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(ChannelInstance[] inputs,
            ChannelInstance[] outputs, JavaExecutor executor, OptimizationContext.OperatorContext context) {
        ((CollectionChannel.Instance) outputs[0]).accept(((AggregateChannel.Instance) inputs[0]).records());
        return ExecutionOperator.modelEagerExecution(inputs, outputs, context);
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaAggregateRead(this);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        if (index != 0) throw new IllegalArgumentException("invalid aggregate input index");
        return List.of(AggregateChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        if (index != 0) throw new IllegalArgumentException("invalid aggregate output index");
        return List.of(CollectionChannel.DESCRIPTOR);
    }
}
