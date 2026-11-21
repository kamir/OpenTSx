package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.contracts.PhysicalDeployments;

import java.util.ArrayList;
import java.util.List;

final class RustPhysicalDeployment {
    static final String REQUEST_SCHEMA = "opentsx.local-physical-bounded-run.v1";

    private RustPhysicalDeployment() { }

    static ObjectNode skeleton(JsonNode legacy, JsonNode effective) {
        ObjectNode result = RustRollout.json("{}").deepCopy();
        result.put("schema", PhysicalDeployments.SCHEMA).put("deployment", legacy.path("deployment").asText())
                .put("generation", "1").put("sourceDagText", legacy.path("sourceDagText").asText())
                .put("specHash", legacy.path("specHash").asText()).put("createdAtMicros", legacy.path("createdAtMicros").asText());
        String computation = PhysicalDeployments.encodeComputation(effective);
        result.put("computationText", computation).put("computationHash", ExecutionAssignments.sha256(computation));
        ArrayNode assignments = result.putArray("assignmentTexts");
        for (JsonNode unit : legacy.path("assignments")) assignments.add(RustRollout.json(unit.path("requestText").asText()).path("assignmentText").asText());
        return result;
    }

    static ObjectNode finish(ObjectNode plan, JsonNode facts) {
        require(facts.path("validUntilMicros").isTextual(), "facts", null, "/facts/validUntilMicros");
        plan.put("expiresAtMicros", facts.path("validUntilMicros").asText());
        plan.set("capabilities", facts.path("capabilities").deepCopy());
        ArrayNode placements = plan.putArray("placements"), owners = plan.putArray("ownership"), channels = plan.putArray("channels");
        JsonNode logical = PhysicalDeployments.decodeComputation(plan.path("computationText").asText());
        List<String> topics = new ArrayList<>(); logical.path("source").path("topics").forEach(topic -> topics.add(topic.asText()));
        for (JsonNode raw : plan.path("assignmentTexts")) {
            JsonNode assignment = RustRollout.json(raw.asText());
            String name = assignment.path("assignment").asText();
            JsonNode fragment = RustRollout.json(assignment.path("fragmentText").asText());
            JsonNode capability = named(facts.path("capabilities"), name, "assignment", "capability");
            JsonNode report = RustRollout.json(capability.path("reportText").asText());
            String buffer = report.path("limits").path("records").asText();
            JsonNode residency = named(facts.path("residency"), name, "assignment", "facts").path("sites");
            ArrayNode placed = placements.addObject().put("assignment", name).putArray("nodes");
            fragment.path("nodes").forEach(node -> placed.add(node.path("id").asText()));
            for (JsonNode input : assignment.path("inputs")) {
                JsonNode topic = topic(facts, input.path("binding").asText(), input.path("topic").asText());
                ObjectNode owner = owners.addObject().put("assignment", name);
                topic.fields().forEachRemaining(field -> owner.set(field.getKey(), field.getValue().deepCopy()));
            }
            for (JsonNode node : fragment.path("nodes")) {
                String to = node.path("id").asText();
                for (int port = 0; port < node.path("in").size(); port++) channels.add(channel(name, node.path("in").get(port).asText(), "0", to,
                        Integer.toString(port), null, residency, buffer));
                if (node.path("op").asText().equals("decode.avro-soe")) for (JsonNode input : assignment.path("inputs"))
                    channels.add(channel(name, "$source", Integer.toString(topics.indexOf(input.path("topic").asText())), to, "0",
                            topic(facts, input.path("binding").asText(), input.path("topic").asText()), residency, buffer));
            }
            for (int port = 0; port < logical.path("sinks").size(); port++) {
                JsonNode sink = logical.path("sinks").get(port);
                channels.add(channel(name, sink.path("from").asText(), "0", "$sink", Integer.toString(port),
                        topic(facts, "local", sink.path("topic").asText()), residency, buffer));
            }
        }
        plan.put("planHash", PhysicalDeployments.planHash(plan));
        return plan;
    }

    static ObjectNode local(JsonNode physical, String bootstrap, List<RustRollout.Worker> workers) {
        ObjectNode result = RustRollout.json("{}").deepCopy();
        result.put("schema", RustRollout.SCHEMA).put("deployment", physical.path("deployment").asText())
                .put("sourceDagText", physical.path("sourceDagText").asText()).put("specHash", physical.path("specHash").asText())
                .put("createdAtMicros", physical.path("createdAtMicros").asText());
        JsonNode logical = PhysicalDeployments.decodeComputation(physical.path("computationText").asText());
        JsonNode sink = logical.path("sinks").get(0);
        JsonNode sinkChannel = null;
        for (JsonNode channel : physical.path("channels")) if (channel.path("to").asText().equals("$sink")) sinkChannel = channel;
        require(sinkChannel != null, "channel", sink.path("from").asText(), "/channels");
        String output = sink.path("topic").asText();
        result.putObject("output").put("topic", output).put("partitionCount", sinkChannel.path("partitionCount").asText()).put("create", false);
        ArrayNode units = result.putArray("assignments");
        for (JsonNode raw : physical.path("assignmentTexts")) {
            JsonNode assignment = RustRollout.json(raw.asText());
            require(assignment.path("mode").asText().equals("bounded"), "assignment", null, "/assignmentTexts/mode");
            String name = assignment.path("assignment").asText();
            RustRollout.Worker worker = workers.stream().filter(w -> w.node().equals(assignment.path("node").asText())).findFirst()
                    .orElseThrow(() -> new PhysicalDeployments.Refused("deployment.capability", null, "/assignmentTexts/node"));
            ObjectNode request = RustRollout.json("{}").deepCopy();
            request.put("schema", "opentsx.local-bounded-run.v1").put("assignmentText", raw.asText())
                    .put("createdAtMicros", physical.path("createdAtMicros").asText());
            String input = assignment.path("inputs").get(0).path("topic").asText();
            ObjectNode binding = request.putObject("bindings").putObject("local").put("bootstrap", bootstrap);
            binding.putArray("inputs").add(input); binding.putArray("outputs").add(output);
            String sinkName = RustRollout.sinkName(logical);
            request.putObject("sink").put("name", sinkName).put("binding", "local").put("topic", output)
                    .put("partitionCount", sinkChannel.path("partitionCount").asText());
            JsonNode report = RustRollout.json(named(physical.path("capabilities"), name, "assignment", "capability").path("reportText").asText());
            request.set("limits", limits(report, physical, name));
            ObjectNode wrapper = RustRollout.json("{}").deepCopy();
            wrapper.put("schema", REQUEST_SCHEMA).put("physicalText", PhysicalDeployments.canonical(physical)).put("requestText", RustRollout.stringify(request));
            units.addObject().put("node", worker.node()).put("endpoint", worker.endpoint()).put("inputTopic", input).put("requestText", RustRollout.stringify(wrapper));
        }
        result.put("planHash", RustRollout.hash(result));
        return result;
    }

    static ObjectNode limits(JsonNode report, JsonNode physical, String assignment) {
        ObjectNode limits = RustRollout.json("{}").deepCopy();
        for (String key : List.of("records", "payloadBytes", "inputBytes", "samples", "outputBytes", "series", "stateBytes"))
            limits.set(key, report.path("limits").path(key).deepCopy());
        long buffer = Long.MAX_VALUE;
        for (JsonNode channel : physical.path("channels")) if (channel.path("assignment").asText().equals(assignment))
            buffer = Math.min(buffer, Long.parseLong(channel.path("bufferRecords").asText()));
        limits.put("records", Math.toIntExact(Math.min(limits.path("records").asLong(), buffer)));
        limits.put("samples", Math.toIntExact(Math.min(limits.path("samples").asLong(), buffer)));
        return limits;
    }

    static JsonNode request(JsonNode unit) {
        JsonNode request = RustRollout.json(unit.path("requestText").asText());
        return request.path("schema").asText().equals(REQUEST_SCHEMA) ? RustRollout.json(request.path("requestText").asText()) : request;
    }

    private static ObjectNode channel(String assignment, String from, String fromPort, String to, String toPort,
                                       JsonNode topic, JsonNode residency, String buffer) {
        ObjectNode result = RustRollout.json("{}").deepCopy();
        result.put("assignment", assignment).put("from", from).put("fromPort", fromPort).put("to", to).put("toPort", toPort)
                .put("dataType", topic == null ? "series" : "episode").put("transport", topic == null ? "memory" : "topic");
        if (topic == null) {
            for (String key : List.of("binding", "cluster", "topic", "incarnation")) result.putNull(key);
            result.put("partitionCount", "0");
        } else topic.fields().forEachRemaining(field -> result.set(field.getKey(), field.getValue().deepCopy()));
        result.set("residency", residency.deepCopy());
        result.put("schema", topic == null ? "opentsx.series.v1" : "opentsx.episode.v2").put("key", "seriesId")
                .put("partitioner", topic == null ? "none" : "murmur2").put("bufferRecords", buffer)
                .put("backpressure", "block").put("completion", "closed-cut");
        return result;
    }

    private static JsonNode named(JsonNode entries, String name, String key, String code) {
        require(entries.isArray(), code, null, "/facts/" + code);
        JsonNode found = null;
        for (JsonNode entry : entries) if (entry.path(key).asText().equals(name)) {
            require(found == null, code, null, "/facts/" + code); found = entry;
        }
        require(found != null, code, null, "/facts/" + code);
        return found;
    }

    private static JsonNode topic(JsonNode facts, String binding, String topic) {
        require(facts.path("topics").isArray(), "incarnation", null, "/facts/topics");
        JsonNode found = null;
        for (JsonNode entry : facts.path("topics")) if (entry.path("binding").asText().equals(binding) && entry.path("topic").asText().equals(topic)) {
            require(found == null, "facts", null, "/facts/topics"); found = entry;
        }
        require(found != null, "incarnation", null, "/facts/topics");
        return found;
    }

    private static void require(boolean condition, String code, String node, String field) {
        if (!condition) throw new PhysicalDeployments.Refused("deployment." + code, node, field);
    }
}
