package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.retrieval.system.SystemDoc.Flow;
import org.opentsx.retrieval.system.SystemDoc.Signal;
import org.opentsx.retrieval.system.SystemDoc.Source;
import org.opentsx.twin.Origin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The systems a server knows, and the state of their signals: the declaration of a {@link SystemDoc} put next to
 * what the TS inventory ({@code opentsx.ts-inventory.v1}) holds. The state is computed on every call, never stored.
 */
public final class Systems {

    public static final String STATUS_SCHEMA = "opentsx.signal-status.v1";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Map<String, SystemDoc> docs = new LinkedHashMap<>();

    public Systems(List<SystemDoc> docs) {
        docs.forEach(d -> this.docs.put(d.system(), d));
    }

    /**
     * Reads every {@code *.system.yaml} of a directory.
     *
     * @param root directory the references in the documents are relative to
     */
    public static Systems load(Path dir, Path root) {
        try (Stream<Path> files = Files.list(dir)) {
            return new Systems(files.filter(f -> f.getFileName().toString().endsWith(".system.yaml")).sorted()
                    .map(f -> SystemDoc.load(f, root)).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(dir + " cannot be read", e);
        }
    }

    public boolean has(String system) {
        return docs.containsKey(system);
    }

    public List<SystemDoc> docs() {
        return List.copyOf(docs.values());
    }

    /** The systems with their counts, for a list to choose from. */
    public JsonNode list(JsonNode inventory) {
        ObjectNode out = JSON.objectNode();
        ArrayNode list = out.putArray("systems");
        for (SystemDoc d : docs.values()) {
            ObjectNode full = (ObjectNode) signals(d.system(), inventory);
            ObjectNode o = list.addObject();
            for (String k : List.of("system", "title", "version", "domain", "example", "hash", "counts", "states")) {
                o.set(k, full.get(k));
            }
            o.put("problems", d.problems().size());
        }
        return out;
    }

    /** Every signal of the system with its expectation, its sources, what the inventory holds for it and its state. */
    public JsonNode signals(String system, JsonNode inventory) {
        SystemDoc d = docs.get(system);
        ObjectNode out = JSON.objectNode();
        out.put("schema", STATUS_SCHEMA);
        out.put("system", d.system());
        out.put("title", d.title());
        out.put("version", d.version());
        out.put("domain", d.domain());
        out.put("example", d.example());
        out.put("hash", d.hash());
        out.put("generatedAt", Instant.now().toString());
        ArrayNode problems = out.putArray("problems");
        d.problems().forEach(problems::add);
        ArrayNode notes = out.putArray("notes");
        d.notes().forEach(notes::add);

        Map<String, Signal> byId = new TreeMap<>();
        d.signals().forEach(s -> byId.put(s.id(), s));
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> states = new TreeMap<>();
        ArrayNode list = JSON.arrayNode();
        for (Signal s : d.signals()) {
            ObjectNode o = list.addObject();
            o.put("id", s.id());
            o.put("atom", s.atom());
            int cut = s.isPort() ? s.id().indexOf('.') : s.id().lastIndexOf('/');
            o.put("group", s.isPort() ? "" : s.id().substring(s.atom().length() + 1, cut));
            o.put("name", s.id().substring(cut + 1));
            o.put("title", s.title());
            o.put("port", s.isPort());
            if (s.channel() != null) {
                o.put("channel", s.channel());
            }
            ObjectNode e = o.putObject("expect");
            put(e, "unit", s.expect().unit());
            put(e, "min", s.expect().min());
            put(e, "max", s.expect().max());
            put(e, "interval", s.expect().interval());
            put(e, "kind", s.expect().kind());
            put(e, "origin", s.expect().origin());

            ObjectNode grades = o.putObject("grades");
            Map<String, JsonNode> data = new TreeMap<>();
            for (String grade : SystemDoc.GRADES) {
                Source src = s.source(grade);
                if (src == null) {
                    continue;
                }
                ObjectNode g = grades.putObject(grade);
                g.set("source", source(src));
                if (grade.equals("features")) {
                    ArrayNode names = g.putArray("names");
                    s.featureNames().forEach(names::add);
                }
                JsonNode found = find(d, s, grade, src, inventory);
                if (found != null) {
                    g.set("data", found);
                    data.put(grade, found);
                }
            }
            String via = via(s);
            counts.merge(via, 1, Integer::sum);
            o.put("via", via);
            Origin origin = origin(s, byId, d.flows(), new HashSet<>());
            if (origin != null) {
                o.put("origin", origin.name());
            }
            String state = state(s, data);
            states.merge(state, 1, Integer::sum);
            o.put("state", state);
            JsonNode seen = data.containsKey("clean") ? data.get("clean") : data.get("raw");
            if (seen != null && seen.has("min") && outside(s, seen)) {
                ObjectNode r = o.putObject("outOfRange");
                r.put("min", seen.path("min").asDouble());
                r.put("max", seen.path("max").asDouble());
            }
            ArrayNode next = o.putArray("next");
            switch (state) {
                case "open" -> next.add("bind").add("simulate");
                case "bound-no-data" -> next.add("generator".equals(s.raw().kind()) ? "generate" : "check-source");
                case "raw-only" -> next.add("prepare");
                case "clean-not-running" -> next.add("start");
                default -> {
                }
            }
            ArrayNode used = o.putArray("usedBy");
            for (Flow f : d.flows().values()) {
                if (f.reads().stream().anyMatch(r -> r.startsWith(s.id() + "@"))) {
                    used.add(f.id());
                }
            }
            if (s.note() != null) {
                o.put("note", s.note());
            }
        }
        ObjectNode c = out.putObject("counts");
        c.put("signals", d.signals().size());
        counts.forEach(c::put);
        ObjectNode st = out.putObject("states");
        states.forEach(st::put);
        out.set("signals", list);
        return out;
    }

    /**
     * The plan of the system for the place "System": every atom with its ports. An input says where its value comes
     * from: the output it is wired to, the signal declared for it, or nothing.
     */
    public JsonNode plan(String system, JsonNode inventory) {
        SystemDoc d = docs.get(system);
        Map<String, JsonNode> signals = new TreeMap<>();
        signals(system, inventory).path("signals").forEach(s -> signals.put(s.path("id").asText(), s));
        ObjectNode out = JSON.objectNode();
        out.put("schema", "opentsx.system-plan.v1");
        out.put("system", d.system());
        out.put("title", d.title());
        ArrayNode atoms = out.putArray("atoms");
        int outside = 0;
        int uncovered = 0;
        for (SystemDoc.PlanAtom a : d.atoms()) {
            ObjectNode o = atoms.addObject();
            o.put("id", a.id());
            o.put("type", a.type());
            ArrayNode ins = o.putArray("inputs");
            for (SystemDoc.Port p : a.inputs()) {
                ObjectNode n = port(ins.addObject(), p);
                JsonNode sig = signals.get(a.id() + "." + p.name());
                if (p.from() != null) {
                    n.put("from", p.from());
                } else {
                    outside++;
                    if (sig == null) {
                        uncovered++;
                    }
                }
                if (sig != null) {
                    n.put("signal", sig.path("id").asText());
                    n.put("state", sig.path("state").asText());
                    put(n, "origin", sig.path("origin").asText(null));
                    n.put("via", sig.path("via").asText());
                }
            }
            ArrayNode outs = o.putArray("outputs");
            a.outputs().forEach(p -> port(outs.addObject(), p));
            long below = d.signals().stream().filter(s -> !s.isPort() && s.atom().equals(a.id())).count();
            o.put("channels", below);
        }
        ObjectNode c = out.putObject("counts");
        c.put("atoms", d.atoms().size());
        c.put("inputsFromOutside", outside);
        c.put("inputsWithoutSignal", uncovered);
        return out;
    }

    private static ObjectNode port(ObjectNode n, SystemDoc.Port p) {
        n.put("name", p.name());
        put(n, "unit", p.unit());
        n.put("kind", p.kind());
        return n;
    }

    /** How a person would say where the signal comes from. */
    private static String via(Signal s) {
        Source first = s.raw() != null ? s.raw() : s.clean();
        if (first == null) {
            return "open";
        }
        return switch (first.kind()) {
            case "generator" -> "simulated";
            case "flow" -> "derived";
            default -> first.kind();
        };
    }

    /** The origin of the data: declared at a topic or a manual value, synthetic for a generator, the worst input for a flow. */
    private static Origin origin(Signal s, Map<String, Signal> byId, Map<String, Flow> flows, Set<String> visiting) {
        if (s.raw() != null && s.raw().origin() != null) {
            return parse(s.raw().origin());
        }
        if (s.clean() == null || !"flow".equals(s.clean().kind()) || !visiting.add(s.id())) {
            return null;
        }
        Origin worst = null;
        Flow f = flows.get(s.clean().flow());
        for (String r : f == null ? List.<String>of() : f.reads()) {
            Signal in = byId.get(r.split("@", 2)[0]);
            if (in != null && in != s) {
                worst = Origin.worst(worst, origin(in, byId, flows, visiting));
            }
        }
        return worst;
    }

    private static Origin parse(String name) {
        try {
            return Origin.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String state(Signal s, Map<String, JsonNode> data) {
        if (s.raw() == null && s.clean() == null) {
            return "open";
        }
        if (data.containsKey("clean")) {
            return "clean";
        }
        if (s.clean() != null) {
            return "clean-not-running";
        }
        if ("manual".equals(s.raw().kind())) {
            return "constant";
        }
        return data.containsKey("raw") ? "raw-only" : "bound-no-data";
    }

    private static boolean outside(Signal s, JsonNode seen) {
        Double lo = s.expect().min();
        Double hi = s.expect().max();
        return (lo != null && seen.path("min").asDouble() < lo) || (hi != null && seen.path("max").asDouble() > hi);
    }

    private static ObjectNode source(Source src) {
        ObjectNode o = JSON.objectNode();
        o.put("kind", src.kind());
        put(o, "origin", src.origin());
        put(o, "topic", src.topic());
        put(o, "metric", src.metric());
        if (!src.tags().isEmpty()) {
            ObjectNode t = o.putObject("tags");
            src.tags().forEach(t::put);
        }
        put(o, "processor", src.processor());
        if (src.seed() != null) {
            o.put("seed", src.seed());
        }
        if (src.params() != null) {
            o.set("params", src.params());
        }
        put(o, "flow", src.flow());
        put(o, "output", src.output());
        put(o, "value", src.value());
        put(o, "table", src.table());
        return o;
    }

    /**
     * What the inventory holds for one grade of a signal, or null when it holds nothing. A topic source is matched by
     * metric and tags; a generator or a flow writes by rule (concept, section 6.1) with the tags system and signal.
     */
    private static JsonNode find(SystemDoc d, Signal s, String grade, Source src, JsonNode inventory) {
        if (inventory == null || "manual".equals(src.kind())) {
            return null;
        }
        if ("topic".equals(src.kind()) && !reads(inventory, src.topic())) {
            return null;
        }
        List<JsonNode> hits = new ArrayList<>();
        for (JsonNode e : inventory.path("series")) {
            JsonNode tags = e.path("tags");
            boolean hit;
            if ("topic".equals(src.kind())) {
                // series written by rule for a signal keep the tags of the raw series; they are not the raw data
                hit = tags.has("signal") ? false : src.series().isEmpty()
                        ? (src.metric() == null || src.metric().equals(e.path("metric").asText()))
                        && src.tags().entrySet().stream().allMatch(t -> t.getValue().equals(tags.path(t.getKey()).asText(null)))
                        : src.series().contains(e.path("seriesId").asText());
            } else {
                JsonNode g = e.path("labels").path("grade");
                boolean ofGrade = g.isMissingNode() || contains(g, grade);
                hit = d.system().equals(tags.path("system").asText(null)) && s.id().equals(tags.path("signal").asText(null))
                        && tags.has("feature") == grade.equals("features") && ofGrade;
            }
            if (hit) {
                hits.add(e);
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        ObjectNode o = JSON.objectNode();
        ArrayNode ids = o.putArray("series");
        long samples = 0;
        long missing = 0;
        String from = null;
        String to = null;
        Double min = null;
        Double max = null;
        Long interval = null;
        for (JsonNode e : hits) {
            ids.add(e.path("seriesId").asText());
            samples += e.path("samples").asLong();
            missing += e.path("missing").asLong();
            String f = e.path("from").asText(null);
            String t = e.path("to").asText(null);
            from = f != null && (from == null || f.compareTo(from) < 0) ? f : from;
            to = t != null && (to == null || t.compareTo(to) > 0) ? t : to;
            if (e.has("min")) {
                min = min == null ? e.path("min").asDouble() : Math.min(min, e.path("min").asDouble());
                max = max == null ? e.path("max").asDouble() : Math.max(max, e.path("max").asDouble());
            }
            if (e.has("intervalMicros")) {
                interval = e.path("intervalMicros").asLong();
            }
        }
        o.put("samples", samples);
        o.put("missing", missing);
        put(o, "from", from);
        put(o, "to", to);
        put(o, "min", min);
        put(o, "max", max);
        if (interval != null) {
            o.put("intervalMicros", interval);
        }
        return o;
    }

    /** True when the inventory was built from that topic; an inventory without topics (a store) counts as reading all. */
    private static boolean reads(JsonNode inventory, String topic) {
        JsonNode topics = inventory.path("source").path("topics");
        if (!topics.isObject() || topics.isEmpty()) {
            return true;
        }
        for (JsonNode t : topics) {
            if (t.asText().equals(topic)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private static void put(ObjectNode o, String key, String value) {
        if (value != null) {
            o.put(key, value);
        }
    }

    private static void put(ObjectNode o, String key, Double value) {
        if (value != null) {
            o.put(key, value);
        }
    }
}
