package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsAccumulator;
import org.opentsx.wayang.federation.AggregateInventory;
import org.opentsx.wayang.federation.AggregateRecord;
import org.opentsx.wayang.federation.SiteStats;
import org.opentsx.wayang.site.RunnerPlan;
import org.opentsx.wayang.site.TsxSiteAgent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class TsxBoundedSiteExecution {
    @FunctionalInterface
    public interface Publisher {
        void publish(RunnerPlan plan, List<AggregateRecord> records);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final TsxSiteAgent agent;
    private final Map<String, String> brokers;
    private final Path catalogDirectory;
    private final Publisher publisher;

    public TsxBoundedSiteExecution(TsxSiteAgent agent, Map<String, String> brokers, Path catalogDirectory, Publisher publisher) {
        this.agent = java.util.Objects.requireNonNull(agent);
        this.brokers = Map.copyOf(brokers);
        this.catalogDirectory = java.util.Objects.requireNonNull(catalogDirectory);
        this.publisher = java.util.Objects.requireNonNull(publisher);
    }

    public JsonNode run(RunnerPlan plan, TsxCompileContext context) {
        if (!context.complete() || !context.site().equals(plan.site())) {
            throw new IllegalArgumentException("bounded site execution needs its admitted complete site context");
        }
        JsonNode assignment = plan.assignment();
        JsonNode federation = plan.document().path("federation");
        AggregateInventory inventory = new AggregateInventory(plan.job(), plan.planVersion(),
                assignment.path("specHash").asText(), federation.path("expectedAssignments"), federation.path("scopes"));
        inventory.admit(plan.exactAssignmentText());
        ObjectNode positions = positions(assignment);
        var saved = agent.checkpoint(plan);
        if (saved.isPresent()) {
            JsonNode checkpoint = saved.get();
            if (!positions.equals(checkpoint.path("positions")) || !checkpoint.path("state").isObject()
                    || !checkpoint.path("receipt").isObject()) {
                throw new IllegalStateException("bounded checkpoint does not match its completed immutable cut");
            }
            return checkpoint.path("receipt").deepCopy();
        }
        TsxState state = new TsxState(Map.of());
        Map<String, Operator> sources = new LinkedHashMap<>();
        for (JsonNode input : assignment.path("inputs")) {
            String binding = input.path("binding").asText();
            String bootstrap = brokers.get(binding);
            if (bootstrap == null || bootstrap.isBlank()) throw new IllegalArgumentException("unknown local broker binding " + binding);
            Map<Integer, long[]> ranges = new TreeMap<>();
            for (JsonNode cursor : input.path("positions")) {
                ranges.put(Integer.parseInt(cursor.path("partition").asText()),
                        new long[]{Long.parseLong(cursor.path("from").asText()), Long.parseLong(cursor.path("to").asText())});
            }
            JsonNode contract = inputContract(plan, binding, input.path("topic").asText());
            Set<String> fingerprints = new java.util.HashSet<>();
            contract.path("fingerprints").forEach(value -> fingerprints.add(value.asText()));
            TsxRead read = new TsxRead(bootstrap, input.path("topic").asText(), ranges, fingerprints, plan.job(), plan.planVersion(), true);
            if (sources.putIfAbsent(read.topic(), new TsxTopicSource(read)) != null) {
                throw new IllegalArgumentException("bounded site inputs must have distinct topic names");
            }
        }
        try {
            JsonNode fragment = JSON.readTree(assignment.path("fragmentText").asText());
            if (fragment.path("sinks").size() != 1
                    || !fragment.path("sinks").get(0).path("schema").asText().equals("org.opentsx.model.v2.SeriesStats")) {
                throw new IllegalArgumentException("bounded federated execution needs one SeriesStats sink");
            }
            String terminal = fragment.path("sinks").get(0).path("from").asText();
            JsonNode statistics = null;
            for (JsonNode node : fragment.path("nodes")) if (node.path("id").asText().equals(terminal)) statistics = node;
            if (statistics == null || !statistics.path("op").asText().equals("stats.welford-ddsketch")) {
                throw new IllegalArgumentException("bounded aggregate sink must read stats.welford-ddsketch");
            }
            String topic = fragment.path("sinks").get(0).path("topic").asText();
            String domain = topic.split("\\.")[1];
            DagToWayang compiled = DagToWayang.compile(fragment, new TopicLayout(domain), plan.document().path("specHash").asText(),
                    catalogDirectory, context);
            for (JsonNode node : compiled.dag().path("nodes")) if (node.path("id").asText().equals(terminal)) statistics = node;
            double alpha = statistics.path("params").path("alpha").asDouble();
            Map<String, Operator> outputs = compiled.outputs(sources, state);
            List<Operator> sinks = new ArrayList<>();
            List<SeriesStats> raw = new ArrayList<>();
            LocalCallbackSink<SpecificRecord> statisticsSink = new LocalCallbackSink<>((Consumer<SpecificRecord>) record -> {
                if (!(record instanceof SeriesStats value)) throw new IllegalStateException("aggregate boundary received a non-statistics record");
                raw.add(value);
            }, SpecificRecord.class);
            outputs.get(terminal).connectTo(0, statisticsSink, 0);
            sinks.add(statisticsSink);
            Map<String, String> groups = new TreeMap<>();
            AtomicLong records = new AtomicLong();
            Set<Operator> inspected = Collections.newSetFromMap(new IdentityHashMap<>());
            for (JsonNode node : compiled.dag().path("nodes")) {
                Operator output = outputs.get(node.path("id").asText());
                boolean decoder = node.path("op").asText().equals("decode.avro-soe");
                if (output == null || !(decoder || output instanceof TsxSeriesChain || output instanceof TsxSegment
                        || output instanceof TsxSaxWord || output instanceof TsxPair) || !inspected.add(output)) continue;
                LocalCallbackSink<SpecificRecord> metadata = new LocalCallbackSink<>((Consumer<SpecificRecord>) record -> {
                    if (decoder) records.incrementAndGet();
                    if (!(record instanceof Episode episode)) {
                        throw new IllegalArgumentException("bounded grouped statistics require Episode series metadata");
                    }
                    Map<String, String> tags = new TreeMap<>();
                    for (JsonNode tag : federation.path("groupBy")) {
                        String value = episode.getSeries().getTags().get(tag.asText());
                        if (value != null) tags.put(tag.asText(), value);
                    }
                    String group = SeriesKeys.seriesId(episode.getSeries().getMetric(), tags);
                    String previous = groups.putIfAbsent(episode.getSeries().getSeriesId(), group);
                    if (previous != null && !previous.equals(group)) throw new IllegalStateException("series grouping changed inside one admitted cut");
                }, SpecificRecord.class);
                output.connectTo(0, metadata, 0);
                sinks.add(metadata);
            }
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sinks.toArray(Operator[]::new)));
            Map<AggregateInventory.Scope, SeriesStats> grouped = new LinkedHashMap<>();
            for (SeriesStats value : SiteStats.group(raw, groups, alpha)) {
                inventory.validateScope(value.getSeriesId(), value.getWindowType(), Micros.of(value.getWindowStart()), Micros.of(value.getWindowEnd()));
                AggregateInventory.Scope scope = new AggregateInventory.Scope(value.getSeriesId(), value.getWindowType(),
                        Micros.of(value.getWindowStart()), Micros.of(value.getWindowEnd()));
                if (grouped.putIfAbsent(scope, value) != null) throw new IllegalStateException("duplicate grouped statistics scope");
            }
            List<AggregateRecord> partials = new ArrayList<>();
            StringBuilder hashes = new StringBuilder();
            for (AggregateInventory.Scope scope : inventory.scopes()) {
                SeriesStats value = grouped.get(scope);
                if (value == null) value = new StatsAccumulator(alpha).toSeriesStats(scope.groupId(), scope.windowType(),
                        scope.startMicros(), scope.endMicros());
                AggregateRecord partial = AggregateRecord.partial(inventory, assignment.path("assignment").asText(), 0, value);
                partials.add(partial);
                hashes.append(partial.key()).append('|').append(ExecutionAssignments.sha256(
                        java.util.Base64.getEncoder().encodeToString(partial.value()))).append('\n');
            }
            publisher.publish(plan, List.copyOf(partials));
            ObjectNode receipt = JSON.createObjectNode().put("schema", "opentsx.site-result.v1")
                    .put("assignment", assignment.path("assignment").asText()).put("inputRecords", records.get())
                    .put("records", partials.size()).put("resultHash", ExecutionAssignments.sha256(hashes.toString()));
            ObjectNode operatorState = JSON.createObjectNode();
            state.snapshot().forEach(operatorState::set);
            agent.checkpoint(plan, positions, operatorState, receipt);
            return receipt;
        } catch (IOException error) {
            throw new UncheckedIOException("cannot compile admitted site fragment", error);
        }
    }

    private static JsonNode inputContract(RunnerPlan plan, String binding, String topic) {
        for (JsonNode contract : plan.document().path("inputContracts")) {
            if (contract.path("binding").asText().equals(binding) && contract.path("topic").asText().equals(topic)) return contract;
        }
        throw new IllegalArgumentException("input has no admitted fingerprint contract");
    }

    private static ObjectNode positions(JsonNode assignment) {
        ObjectNode positions = JSON.createObjectNode();
        for (JsonNode input : assignment.path("inputs")) {
            ObjectNode binding = positions.with(input.path("binding").asText());
            ObjectNode topic = binding.putObject(input.path("topic").asText());
            for (JsonNode cursor : input.path("positions")) topic.put(cursor.path("partition").asText(), cursor.path("to").asText());
        }
        return positions;
    }
}
