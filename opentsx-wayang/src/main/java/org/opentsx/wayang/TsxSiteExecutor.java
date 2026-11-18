package org.opentsx.wayang;

import org.apache.wayang.core.api.Job;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.executionplan.ExecutionStage;
import org.apache.wayang.core.plan.executionplan.ExecutionTask;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.ExecutionState;
import org.apache.wayang.core.platform.ExecutorTemplate;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class TsxSiteExecutor extends ExecutorTemplate {
    private final Set<TsxSiteStage.Action> invoked = Collections.newSetFromMap(new IdentityHashMap<>());

    public TsxSiteExecutor(Job job) {
        super(job.getCrossPlatformExecutor());
    }

    @Override
    public TsxSitePlatform getPlatform() {
        return TsxSitePlatform.getInstance();
    }

    @Override
    public synchronized void execute(ExecutionStage stage, OptimizationContext context, ExecutionState state) {
        if (stage.getPlatformExecution().getPlatform() != getPlatform() || stage.getLoop() != null
                || !stage.getInboundChannels().isEmpty() || stage.getAllTasks().size() != 1) {
            throw new IllegalArgumentException("site execution requires one complete bounded stage");
        }
        ExecutionTask task = stage.getAllTasks().iterator().next();
        if (!(task.getOperator() instanceof TsxSiteExecution operator)
                || task.getNumInputChannels() != 0 || task.getNumOuputChannels() != 1
                || !(task.getOutputChannel(0) instanceof AggregateChannel channel)) {
            throw new IllegalArgumentException("site stage must produce one aggregate channel");
        }
        if (!invoked.add(operator.action())) throw new IllegalStateException("a site stage cannot execute twice in one Wayang job");
        long started = System.nanoTime();
        var records = operator.action().execute();
        if (records == null) throw new IllegalStateException("site stage returned no aggregate result");
        var operatorContext = context.getOperatorContext(operator);
        AggregateChannel.Instance output = (AggregateChannel.Instance) channel.createInstance(this, operatorContext, 0);
        output.accept(records);
        var lineage = ExecutionOperator.modelEagerExecution(new ChannelInstance[0], new ChannelInstance[]{output}, operatorContext);
        state.register(output);
        var partial = createPartialExecution(lineage.getField0(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        if (partial != null) state.add(partial);
        lineage.getField1().forEach(state::addCardinalityMeasurement);
    }
}
