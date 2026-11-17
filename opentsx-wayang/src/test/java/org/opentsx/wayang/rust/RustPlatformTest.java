package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.MapOperator;
import org.apache.wayang.basic.operators.UnionAllOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.Job;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.executionplan.ExecutionStage;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.types.DataSetType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustPlatformTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<JsonNode> nodes() {
        return List.of(JSON.createObjectNode().put("id", "range").put("op", "dq.checks"),
                JSON.createObjectNode().put("id", "convert").put("op", "convert.unit"),
                JSON.createObjectNode().put("id", "sample").put("op", "resample"));
    }

    private static RustTopicSink graph(List<String> topics, RustStageAction action) {
        return graph(topics, action, nodes());
    }

    private static RustTopicSink graph(List<String> topics, RustStageAction action, List<JsonNode> chainNodes) {
        RustTopicSource first = new RustTopicSource(topics.get(0), List.of("decode"), action);
        org.apache.wayang.core.plan.wayangplan.OperatorBase root = first;
        for (String topic : topics.subList(1, topics.size())) {
            RustTopicSource source = new RustTopicSource(topic, List.of("decode"), action);
            UnionAllOperator<SpecificRecord> union = new UnionAllOperator<>(DataSetType.createDefault(SpecificRecord.class));
            root.connectTo(0, union, 0);
            source.connectTo(0, union, 1);
            root = union;
        }
        if (!chainNodes.isEmpty()) {
            RustSeriesChain chain = new RustSeriesChain(chainNodes, action);
            root.connectTo(0, chain, 0);
            root = chain;
        }
        RustTopicSink sink = new RustTopicSink("tsx.test.clean.v2", chainNodes.isEmpty() ? "decode" : "sample", action);
        root.connectTo(0, sink, 0);
        return sink;
    }

    private static Job job(RustTopicSink sink) {
        Configuration configuration = new Configuration();
        configuration.setProperty("wayang.core.log.enabled", "false");
        return new WayangContext(configuration).with(new RustPlugin()).createJob("bounded-rust", new WayangPlan(sink));
    }

    @Test
    void threeLogicalSourcesCompileToOneRustStageAndOneCompleteAction() {
        AtomicInteger calls = new AtomicInteger();
        RustStageAction action = () -> {
            calls.incrementAndGet();
            return JSON.createObjectNode().put("state", "completed");
        };
        Job job = job(graph(List.of("tsx.test.first.v2", "tsx.test.second.v2", "tsx.test.third.v2"), action));
        job.execute();
        assertEquals(1, calls.get());
        Set<ExecutionStage> stages = job.getCrossPlatformExecutor().getCompletedStages();
        assertEquals(1, stages.size());
        ExecutionStage stage = stages.iterator().next();
        assertEquals(RustPlatform.getInstance(), stage.getPlatformExecution().getPlatform());
        assertEquals(7, stage.getAllTasks().size());
        List<RustExecutionOperator> operators = stage.getAllTasks().stream()
                .map(task -> assertInstanceOf(RustExecutionOperator.class, task.getOperator())).toList();
        assertEquals(3, operators.stream().filter(operator -> operator.role() == RustExecutionOperator.Role.SOURCE).count());
        assertEquals(2, operators.stream().filter(operator -> operator.role() == RustExecutionOperator.Role.UNION).count());
        assertEquals(Set.of("decode", "range", "convert", "sample"), operators.stream()
                .flatMap(operator -> operator.originalNodeIds().stream()).collect(Collectors.toSet()));
        assertTrue(stage.getInboundChannels().isEmpty());
        assertTrue(stage.getOutboundChannels().isEmpty());
    }

    @Test
    void aSingleWholeTopicUsesTheSamePlatform() {
        AtomicInteger calls = new AtomicInteger();
        job(graph(List.of("tsx.test.first.v2"), () -> {
            calls.incrementAndGet();
            return JSON.createObjectNode();
        })).execute();
        assertEquals(1, calls.get());
    }

    @Test
    void passThroughRetainsARealSourceUnionSinkStage() {
        AtomicInteger calls = new AtomicInteger();
        Job job = job(graph(List.of("tsx.test.first.v2", "tsx.test.second.v2", "tsx.test.third.v2"), () -> {
            calls.incrementAndGet();
            return JSON.createObjectNode();
        }, List.of()));
        job.execute();
        assertEquals(1, calls.get());
        Set<ExecutionStage> stages = job.getCrossPlatformExecutor().getCompletedStages();
        assertEquals(1, stages.size());
        ExecutionStage stage = stages.iterator().next();
        assertEquals(RustPlatform.getInstance(), stage.getPlatformExecution().getPlatform());
        assertEquals(6, stage.getAllTasks().size());
        assertEquals(0, stage.getAllTasks().stream().map(task -> (RustExecutionOperator) task.getOperator())
                .filter(operator -> operator.role() == RustExecutionOperator.Role.CHAIN).count());
    }

    @Test
    void duplicateTopicSourcesAreRefusedBeforeExecution() {
        AtomicInteger calls = new AtomicInteger();
        Job job = job(graph(List.of("tsx.test.first.v2", "tsx.test.first.v2"), () -> {
            calls.incrementAndGet();
            return JSON.createObjectNode();
        }));
        assertThrows(RuntimeException.class, job::execute);
        assertEquals(0, calls.get());
    }

    @Test
    void unsupportedLogicalOperatorsCannotFallBackToJava() {
        AtomicInteger calls = new AtomicInteger();
        RustStageAction action = () -> {
            calls.incrementAndGet();
            return JSON.createObjectNode();
        };
        RustTopicSource source = new RustTopicSource("tsx.test.first.v2", List.of("decode"), action);
        MapOperator<SpecificRecord, SpecificRecord> unsupported = new MapOperator<>(value -> value,
                SpecificRecord.class, SpecificRecord.class);
        RustTopicSink sink = new RustTopicSink("tsx.test.clean.v2", "sample", action);
        source.connectTo(0, unsupported, 0);
        unsupported.connectTo(0, sink, 0);
        assertThrows(RuntimeException.class, () -> job(sink).execute());
        assertEquals(0, calls.get());
    }

    @Test
    void differentStageActionsAreRefusedBeforeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        RustStageAction action = () -> {
            calls.incrementAndGet();
            return JSON.createObjectNode();
        };
        RustTopicSource source = new RustTopicSource("tsx.test.first.v2", List.of("decode"), action);
        RustSeriesChain chain = new RustSeriesChain(nodes(), () -> JSON.createObjectNode());
        RustTopicSink sink = new RustTopicSink("tsx.test.clean.v2", "sample", action);
        source.connectTo(0, chain, 0);
        chain.connectTo(0, sink, 0);
        assertThrows(RuntimeException.class, () -> job(sink).execute());
        assertEquals(0, calls.get());
    }

    @Test
    void actionFailureDoesNotCompleteTheStage() {
        AtomicInteger calls = new AtomicInteger();
        Job job = job(graph(List.of("tsx.test.first.v2"), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("worker prepare failed");
        }));
        assertThrows(RuntimeException.class, job::execute);
        assertEquals(1, calls.get());
        assertTrue(job.getCrossPlatformExecutor().getCompletedStages().isEmpty());
    }

    @Test
    void unsupportedChainOperationsAreRefusedBeforePlanning() {
        assertThrows(IllegalArgumentException.class, () -> new RustSeriesChain(
                List.of(JSON.createObjectNode().put("id", "lag").put("op", "feature.lag")), () -> JSON.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> new RustSeriesChain(
                List.of(nodes().get(2), nodes().get(1)), () -> JSON.createObjectNode()));
    }

    @Test
    void topologyChannelsCannotMaterializeRawRecords() {
        RustPlanChannel channel = new RustPlanChannel(RustPlanChannel.DESCRIPTOR, null);
        assertThrows(UnsupportedOperationException.class, () -> channel.createInstance(null, null, 0));
        assertTrue(!channel.isSuitableForBreakpoint());
    }
}
