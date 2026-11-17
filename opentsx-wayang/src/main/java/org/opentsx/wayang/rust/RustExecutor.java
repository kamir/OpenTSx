package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.wayang.core.api.Job;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.executionplan.Channel;
import org.apache.wayang.core.plan.executionplan.ExecutionStage;
import org.apache.wayang.core.plan.executionplan.ExecutionTask;
import org.apache.wayang.core.platform.ExecutionState;
import org.apache.wayang.core.platform.ExecutorTemplate;
import org.apache.wayang.core.platform.PartialExecution;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class RustExecutor extends ExecutorTemplate {
    private final Set<RustStageAction> invoked = Collections.newSetFromMap(new IdentityHashMap<>());

    public RustExecutor(Job job) {
        super(job.getCrossPlatformExecutor());
    }

    @Override
    public RustPlatform getPlatform() {
        return RustPlatform.getInstance();
    }

    @Override
    public synchronized void execute(ExecutionStage stage, OptimizationContext context, ExecutionState state) {
        RustStageAction action = validate(stage);
        if (!invoked.add(action)) throw new IllegalStateException("a Rust stage action cannot execute twice in one Wayang job");
        long started = System.nanoTime();
        JsonNode result = action.execute();
        if (result == null || !result.isObject()) throw new IllegalStateException("a Rust stage must return a verified result object");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        List<ExecutionLineageNode> lineage = new ArrayList<>();
        for (ExecutionTask task : stage.getAllTasks()) {
            ExecutionLineageNode node = new ExecutionLineageNode(context.getOperatorContext(task.getOperator()));
            node.addAtomicExecutionFromOperatorContext().collectAndMark(lineage, new ArrayList<>());
        }
        PartialExecution partial = createPartialExecution(lineage, elapsed);
        if (partial != null) state.add(partial);
    }

    private RustStageAction validate(ExecutionStage stage) {
        if (stage.getPlatformExecution().getPlatform() != getPlatform() || stage.getLoop() != null
                || !stage.getInboundChannels().isEmpty() || !stage.getOutboundChannels().isEmpty()) {
            throw new IllegalArgumentException("Rust execution requires one complete bounded stage without external channels");
        }
        Set<ExecutionTask> tasks = stage.getAllTasks();
        EnumMap<RustExecutionOperator.Role, Integer> counts = new EnumMap<>(RustExecutionOperator.Role.class);
        Set<String> topics = new HashSet<>();
        RustStageAction action = null;
        ExecutionTask sinkTask = null;
        ExecutionTask chainTask = null;
        for (ExecutionTask task : tasks) {
            if (!(task.getOperator() instanceof RustExecutionOperator operator)) {
                throw new IllegalArgumentException("the Rust stage contains an unsupported physical operator");
            }
            counts.merge(operator.role(), 1, Math::addExact);
            if (operator.role() != RustExecutionOperator.Role.UNION) {
                if (action == null) action = operator.action();
                if (action == null || operator.action() != action) {
                    throw new IllegalArgumentException("all Rust stage operators must share one execution action");
                }
            }
            for (Channel channel : task.getInputChannels()) {
                if (channel == null || channel.getProducer() == null || channel.getProducer().getStage() != stage
                        || !RustPlanChannel.DESCRIPTOR.equals(channel.getDescriptor())) {
                    throw new IllegalArgumentException("the Rust stage contains an invalid internal input channel");
                }
            }
            for (Channel channel : task.getOutputChannels()) {
                if (channel == null || channel.getConsumers().size() != 1
                        || channel.getConsumers().get(0).getStage() != stage
                        || !RustPlanChannel.DESCRIPTOR.equals(channel.getDescriptor())) {
                    throw new IllegalArgumentException("the Rust stage must not fork or materialize an internal channel");
                }
            }
            switch (operator.role()) {
                case SOURCE -> {
                    if (!(operator instanceof RustTopicSource source) || !topics.add(source.topic())) {
                        throw new IllegalArgumentException("a whole input topic must have exactly one Rust source");
                    }
                }
                case CHAIN -> chainTask = task;
                case SINK -> sinkTask = task;
                case UNION -> { }
            }
        }
        int sources = counts.getOrDefault(RustExecutionOperator.Role.SOURCE, 0);
        int chains = counts.getOrDefault(RustExecutionOperator.Role.CHAIN, 0);
        if (sources < 1 || sources > 3 || chains > 1
                || counts.getOrDefault(RustExecutionOperator.Role.SINK, 0) != 1
                || counts.getOrDefault(RustExecutionOperator.Role.UNION, 0) != sources - 1
                || stage.getStartTasks().size() != sources || stage.getTerminalTasks().size() != 1) {
            throw new IllegalArgumentException("a Rust stage needs one to three whole-topic sources, their union, an optional chain and one sink");
        }
        if (!(sinkTask.getOperator() instanceof RustTopicSink sink) || topics.contains(sink.topic())) {
            throw new IllegalArgumentException("the Rust sink must publish to a distinct topic");
        }
        ExecutionTask sourceTreeRoot = sinkTask.getInputChannel(0).getProducer();
        if (chains == 1) {
            if (!(chainTask.getOperator() instanceof RustSeriesChain chain) || sourceTreeRoot != chainTask
                    || !sink.fromNodeId().equals(chain.originalNodeIds().get(chain.originalNodeIds().size() - 1))) {
                throw new IllegalArgumentException("the Rust sink must consume the complete cleaning chain");
            }
            sourceTreeRoot = chainTask.getInputChannel(0).getProducer();
        } else {
            for (ExecutionTask task : tasks) {
                if (task.getOperator() instanceof RustTopicSource source && !source.originalNodeIds().contains(sink.fromNodeId())) {
                    throw new IllegalArgumentException("a Rust pass-through sink must retain the original source node ID");
                }
            }
        }
        Set<ExecutionTask> sourceTree = new HashSet<>();
        validateSourceTree(sourceTreeRoot, sourceTree);
        if (sourceTree.size() + chains + 1 != tasks.size()) {
            throw new IllegalArgumentException("the Rust stage contains tasks outside its complete source tree");
        }
        return action;
    }

    private void validateSourceTree(ExecutionTask task, Set<ExecutionTask> visited) {
        if (!visited.add(task)) throw new IllegalArgumentException("the Rust source tree contains a cycle or repeated source");
        RustExecutionOperator operator = (RustExecutionOperator) task.getOperator();
        if (operator.role() == RustExecutionOperator.Role.SOURCE) return;
        if (operator.role() != RustExecutionOperator.Role.UNION || task.getNumInputChannels() != 2) {
            throw new IllegalArgumentException("the Rust cleaning chain must consume only whole-topic sources and their union");
        }
        for (Channel channel : task.getInputChannels()) validateSourceTree(channel.getProducer(), visited);
    }
}
