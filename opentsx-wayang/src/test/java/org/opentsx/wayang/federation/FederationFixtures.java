package org.opentsx.wayang.federation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsAccumulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class FederationFixtures {

    static final String GROUP = "0123456789abcdef";
    static final String TOPIC = "tsx.wind.aggregates.v2";
    static final String SOURCE = "{\"source\":{},\"nodes\":[{\"id\":\"stats\"}],\"sinks\":[{\"input\":\"stats\"}]}";
    static final String SPEC = ExecutionAssignments.sha256(SOURCE);

    private FederationFixtures() { }

    static List<String> assignments() {
        List<String> result = new ArrayList<>();
        for (String site : List.of("s1", "s2", "s3")) {
            ObjectNode a = AggregateRecord.JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1")
                    .put("deployment", "wind").put("generation", "1").put("assignment", "a-" + site)
                    .put("attempt", "attempt-1").put("fence", "1").put("fragment", "fragment-" + site)
                    .put("site", site).put("node", "node-" + site).put("runtime", "java").put("catalog", "tsx-catalog/1")
                    .put("artifactDigest", "sha256:" + "a".repeat(64)).put("specHash", SPEC).put("sourceDagText", SOURCE)
                    .put("fragmentHash", SPEC).put("fragmentText", SOURCE).put("mode", "bounded");
            a.putArray("inputs").addObject().put("binding", "input-" + site).put("topic", "tsx.wind." + site + ".episodes.v2")
                    .put("partitionCount", "1").putArray("positions").addObject().put("partition", "0").put("from", "0").put("to", "10");
            result.add(a.toString());
        }
        return List.copyOf(result);
    }

    static ArrayNode expected(List<String> assignments) {
        ArrayNode result = AggregateRecord.JSON.createArrayNode();
        for (String text : assignments) {
            var a = AggregateRecord.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), 65_536);
            ObjectNode member = result.addObject();
            for (String field : List.of("assignment", "site", "node", "generation", "fence", "fragmentHash")) member.set(field, a.get(field));
        }
        return result;
    }

    static ArrayNode scopes() {
        ArrayNode result = AggregateRecord.JSON.createArrayNode();
        result.addObject().put("groupId", GROUP).put("windowType", "PT10M").put("startMicros", "0").put("endMicros", "600000000");
        result.addObject().put("groupId", GROUP).put("windowType", "PT1H").put("startMicros", "0").put("endMicros", "3600000000");
        return result;
    }

    static AggregateInventory inventory() {
        List<String> assignments = assignments();
        AggregateInventory inventory = new AggregateInventory("wind", 1, SPEC, expected(assignments), scopes());
        assignments.forEach(inventory::admit);
        return inventory;
    }

    static SeriesStats statistics(String duration, double... values) {
        StatsAccumulator accumulator = new StatsAccumulator(0.01);
        for (double value : values) accumulator.add(1, value);
        long end = java.time.Duration.parse(duration).toNanos() / 1000;
        return accumulator.toSeriesStats(GROUP, duration, 0, end);
    }

    static AggregateRecord partial(AggregateInventory inventory, String site, long revision, double... values) {
        return AggregateRecord.partial(inventory, "a-" + site, revision, statistics("PT10M", values));
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("test clock requires UTC");
            return this;
        }
        @Override public Instant instant() { return now; }
    }
}
