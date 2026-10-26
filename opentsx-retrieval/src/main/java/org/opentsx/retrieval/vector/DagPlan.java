package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentsx.kafka.TopicLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record DagPlan(List<VectorJob> vectors, List<SeriesJob> series, List<String> skipped) {

    public static DagPlan of(JsonNode dag, TopicLayout layout, String specHash) {
        if (dag == null || !dag.isObject() || !dag.path("nodes").isArray()) {
            throw new IllegalArgumentException("the document is no opentsx.dag DAG (no nodes)");
        }
        Map<String, JsonNode> nodes = validateNodes(dag.path("nodes"));
        if (!dag.path("sinks").isArray() || dag.path("sinks").isEmpty()) {
            throw new IllegalArgumentException("sinks must be a non-empty array");
        }
        boolean vectors = false;
        for (JsonNode sink : dag.path("sinks")) {
            String schema = sink.path("schema").asText();
            if (!VectorJob.SCHEMA.equals(schema) && !SeriesJob.SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported sink " + sink.path("topic").asText()
                        + ": schema must be " + VectorJob.SCHEMA + " or " + SeriesJob.SCHEMA);
            }
            if (VectorJob.SCHEMA.equals(schema)) {
                JsonNode from = nodes.get(sink.path("from").asText());
                if (from == null || !VectorJob.OP.equals(from.path("op").asText())) {
                    throw new IllegalArgumentException("unsupported sink " + sink.path("topic").asText()
                            + ": a vector sink must read align.vector");
                }
                vectors = true;
            }
        }
        List<SeriesJob> series = SeriesJob.of(dag, layout.episodes(), layout.clean(), layout.features(), layout.synthetic(), specHash);
        List<VectorJob> jobs = List.of();
        if (vectors) {
            jobs = VectorJob.of(dag, layout.domain(), layout.episodes()).jobs();
            for (VectorJob job : jobs) {
                if (job.seriesIds().isEmpty()) {
                    throw new IllegalArgumentException("a vector that keeps running needs the ids of its series (source.series)."
                            + " Load the series from the TS inventory, then build the vector.");
                }
            }
        }
        Set<String> names = new HashSet<>();
        List<String> sinkNames = new ArrayList<>();
        jobs.forEach(j -> sinkNames.add(j.name()));
        series.forEach(j -> sinkNames.add(j.name()));
        for (String name : sinkNames) {
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate sink identity " + name);
            }
        }
        return new DagPlan(jobs, series, List.of());
    }

    private static Map<String, JsonNode> validateNodes(JsonNode document) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        Map<String, Integer> incoming = new LinkedHashMap<>();
        Map<String, List<String>> next = new HashMap<>();
        for (JsonNode node : document) {
            String id = node.path("id").asText();
            if (!node.path("id").isTextual() || id.isBlank()) {
                throw new IllegalArgumentException("every node needs a non-empty string id");
            }
            if (nodes.putIfAbsent(id, node) != null) {
                throw new IllegalArgumentException("duplicate node id " + id);
            }
            String op = node.path("op").asText();
            if (op.startsWith("decode.") && !op.equals("decode.avro-soe")) {
                throw new IllegalArgumentException("node " + id + ": unsupported decoder " + op);
            }
            if (op.equals("decode.avro-soe")) {
                if (node.has("in") && (!node.path("in").isArray() || !node.path("in").isEmpty())) {
                    throw new IllegalArgumentException("decoder " + id + " must read the source without node inputs");
                }
            } else if (op.isBlank() || !node.path("in").isArray() || node.path("in").isEmpty()) {
                throw new IllegalArgumentException("node " + id + " needs an operator and a non-empty input array");
            }
            incoming.put(id, node.path("in").size());
        }
        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            for (JsonNode input : entry.getValue().path("in")) {
                if (!input.isTextual() || !nodes.containsKey(input.asText())) {
                    throw new IllegalArgumentException("node " + entry.getKey() + ": missing input " + input.asText());
                }
                next.computeIfAbsent(input.asText(), ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        incoming.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited++;
            for (String child : next.getOrDefault(id, List.of())) {
                if (incoming.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (visited != nodes.size()) {
            throw new IllegalArgumentException("the DAG contains a cycle");
        }
        return nodes;
    }
}
