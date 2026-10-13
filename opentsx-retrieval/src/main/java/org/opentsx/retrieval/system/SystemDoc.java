package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.opentsx.model.XxHash64;
import org.opentsx.twin.Atom;
import org.opentsx.twin.AtomCatalog;
import org.opentsx.twin.Origin;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.TwinPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A system document ({@code opentsx.system.v1}, docs/datamodel-2026/14-ENGINEERING-FLOW.md): one system with its
 * signals and where the raw, clean and feature data of each signal comes from. The document refers to the twin plan,
 * the channel catalogs and the inventory; {@link #load} reads them too and checks every reference. Problems are
 * collected, not thrown, so a document with a mistake can still be shown with what is wrong.
 */
public final class SystemDoc {

    public static final String SCHEMA = "opentsx.system.v1";
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final Pattern PORT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*\\.[a-z0-9_]+");
    private static final Pattern CHANNEL_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*(/[A-Za-z0-9_-]+)+/[A-Za-z0-9_.-]+");
    private static final Pattern DURATION = Pattern.compile("P(\\d+D(T(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?|T(\\d+H(\\d+M)?(\\d+(\\.\\d+)?S)?|\\d+M(\\d+(\\.\\d+)?S)?|\\d+(\\.\\d+)?S))");
    private static final Set<String> KINDS = Set.of("ANALOG", "STATUS", "COUNTER");
    public static final List<String> GRADES = List.of("raw", "clean", "features");

    /** What a signal should look like. Null fields are not stated. */
    public record Expect(String unit, Double min, Double max, String interval, String kind, String origin) {
    }

    /** Where the data of one grade comes from; {@code kind} is topic, generator, flow or manual. */
    public record Source(String kind, String topic, String metric, Map<String, String> tags, List<String> series,
                         String origin, String processor, JsonNode params, Long seed, String flow, String output,
                         Double value, String table) {
    }

    public record Signal(String id, String title, String channel, Expect expect, Source raw, Source clean, Source features,
                         List<String> featureNames, String note) {

        /** True for a port of the plan ({@code atom.port}), false for a sensor channel below an atom. */
        public boolean isPort() {
            return !id.contains("/");
        }

        public String atom() {
            return id.split("[./]", 2)[0];
        }

        public Source source(String grade) {
            return switch (grade) {
                case "raw" -> raw;
                case "clean" -> clean;
                default -> features;
            };
        }
    }

    /** One port of an atom of the plan; {@code from} is the output it is wired to, null for an input from outside. */
    public record Port(String name, String unit, String kind, String from) {
    }

    /** An atom of the plan with its ports, as the place "System" shows it. */
    public record PlanAtom(String id, String type, List<Port> inputs, List<Port> outputs) {
    }

    /** A flow in the form a person writes ({@code opentsx.flow.v1}): it names signal grades it reads and writes. */
    public record Flow(String id, String ref, List<String> reads, Map<String, List<String>> writes) {
    }

    private final String system;
    private final int version;
    private final String title;
    private final String domain;
    private final boolean example;
    private final List<Signal> signals = new ArrayList<>();
    private final Map<String, Flow> flows = new LinkedHashMap<>();
    private final List<PlanAtom> atoms = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final StringBuilder hashed = new StringBuilder();

    private SystemDoc(JsonNode doc) {
        this.system = doc.path("system").asText("");
        this.version = doc.path("version").asInt(0);
        this.title = doc.path("title").asText(system);
        this.domain = doc.path("domain").asText("");
        this.example = doc.path("example").asBoolean(false);
    }

    public String system() {
        return system;
    }

    public int version() {
        return version;
    }

    public String title() {
        return title;
    }

    public String domain() {
        return domain;
    }

    public boolean example() {
        return example;
    }

    public List<Signal> signals() {
        return signals;
    }

    public Map<String, Flow> flows() {
        return flows;
    }

    /** The atoms of the plan in its order; empty when the document names no plan or the plan cannot be read. */
    public List<PlanAtom> atoms() {
        return atoms;
    }

    /** What makes the document wrong; empty for a valid one. */
    public List<String> problems() {
        return problems;
    }

    /** What a person should know but does not make the document wrong. */
    public List<String> notes() {
        return notes;
    }

    /** Hash over the document and the content of every file it refers to. */
    public String hash() {
        return XxHash64.hex(XxHash64.hash(hashed.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * @param file the system document
     * @param root directory its {@code ref} paths are relative to (the repository root)
     */
    public static SystemDoc load(Path file, Path root) {
        JsonNode doc = read(file);
        SystemDoc s = new SystemDoc(doc);
        s.hashed.append(text(file));
        if (!SCHEMA.equals(doc.path("schema").asText())) {
            s.problems.add("schema must be " + SCHEMA);
        }
        if (s.system.isBlank() || s.domain.isBlank() || s.version < 1) {
            s.problems.add("system, domain and a version of 1 or more are required");
        }
        Map<String, Map<String, PortSpec>> ports = s.ports(doc, root);
        Map<String, Map<String, JsonNode>> catalogs = s.catalogs(doc, root);
        Map<String, JsonNode> assets = s.assets(doc, root);
        for (JsonNode f : doc.path("flows")) {
            s.flow(f, root);
        }
        Set<String> seen = new TreeSet<>();
        for (JsonNode n : doc.path("signals")) {
            Signal sig = s.signal(n, catalogs);
            if (sig == null) {
                continue;
            }
            if (!seen.add(sig.id())) {
                s.problems.add(sig.id() + ": the id is used twice");
                continue;
            }
            s.signals.add(sig);
            s.checkPlace(sig, ports, catalogs, assets);
        }
        s.checkFlows();
        return s;
    }

    // ------------------------------------------------------------------ referenced documents

    private Map<String, Map<String, PortSpec>> ports(JsonNode doc, Path root) {
        Map<String, Map<String, PortSpec>> out = new TreeMap<>();
        Path file = ref(doc.path("plan"), root, "plan");
        if (file == null) {
            return out;
        }
        try {
            TwinPlan plan = TwinPlan.load(file);
            List<Atom> instances = plan.instantiate(AtomCatalog.standard());
            Map<String, String> wiredFrom = new TreeMap<>();
            plan.resolve(instances).forEach(w -> wiredFrom.put(w.toAtom() + "." + w.toPort(), w.fromAtom() + "." + w.fromPort()));
            for (Atom a : instances) {
                Map<String, PortSpec> of = out.computeIfAbsent(a.id(), k -> new TreeMap<>());
                a.inputs().forEach(p -> of.put(p.name(), p));
                a.outputs().forEach(p -> of.putIfAbsent(p.name(), p));
                atoms.add(new PlanAtom(a.id(), a.type(),
                        a.inputs().stream().map(p -> new Port(p.name(), p.unit(), p.kind().name(), wiredFrom.get(a.id() + "." + p.name()))).toList(),
                        a.outputs().stream().map(p -> new Port(p.name(), p.unit(), p.kind().name(), null)).toList()));
            }
        } catch (RuntimeException e) {
            problems.add("plan " + file + " cannot be read: " + e.getMessage());
        }
        return out;
    }

    private Map<String, Map<String, JsonNode>> catalogs(JsonNode doc, Path root) {
        Map<String, Map<String, JsonNode>> out = new TreeMap<>();
        for (JsonNode c : doc.path("catalogs")) {
            Path file = ref(c, root, "catalog " + c.path("id").asText());
            if (file == null) {
                continue;
            }
            JsonNode cat = read(file);
            String pinned = c.path("version").asText("");
            if (!pinned.isEmpty() && !pinned.equals(cat.path("version").asText())) {
                problems.add("catalog " + c.path("id").asText() + ": version " + cat.path("version").asText() + " found, " + pinned + " expected");
            }
            Map<String, JsonNode> channels = new TreeMap<>();
            cat.path("channels").forEach(ch -> channels.put(ch.path("id").asText(), ch));
            out.put(c.path("id").asText(), channels);
        }
        return out;
    }

    private Map<String, JsonNode> assets(JsonNode doc, Path root) {
        Map<String, JsonNode> out = new TreeMap<>();
        JsonNode inv = doc.path("inventory");
        if (inv.isMissingNode()) {
            return out;
        }
        Path file = ref(inv, root, "inventory");
        if (file == null) {
            return out;
        }
        String of = inv.path("system").asText(system);
        for (JsonNode sys : read(file).path("systems")) {
            if (of.equals(sys.path("system").asText())) {
                sys.path("assets").forEach(a -> out.put(a.path("id").asText(), a));
            }
        }
        if (out.isEmpty()) {
            problems.add("inventory " + file + " has no assets for the system " + of);
        }
        return out;
    }

    private void flow(JsonNode f, Path root) {
        String id = f.path("id").asText("");
        Path file = ref(f, root, "flow " + id);
        if (file == null) {
            return;
        }
        JsonNode doc = read(file);
        List<String> reads = new ArrayList<>();
        doc.path("reads").forEach(r -> reads.add(r.asText()));
        Map<String, List<String>> writes = new TreeMap<>();
        doc.path("writes").forEach(w -> writes.computeIfAbsent(w.path("signal").asText(), k -> new ArrayList<>()).add(w.path("from").asText()));
        Set<String> nodes = new TreeSet<>();
        doc.path("nodes").forEach(n -> nodes.add(n.path("id").asText()));
        writes.values().forEach(from -> from.stream().filter(n -> !nodes.contains(n))
                .forEach(n -> problems.add("flow " + id + ": writes from " + n + ", which is no node of it")));
        if (!id.equals(doc.path("flow").asText())) {
            problems.add("flow " + id + ": the file names the flow " + doc.path("flow").asText());
        }
        flows.put(id, new Flow(id, f.path("ref").asText(), reads, writes));
    }

    private Path ref(JsonNode holder, Path root, String what) {
        String ref = holder.path("ref").asText("");
        if (ref.isEmpty()) {
            if (!holder.isMissingNode()) {
                problems.add(what + ": ref is missing");
            }
            return null;
        }
        Path file = root.resolve(ref).normalize();
        if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
            problems.add(what + ": " + ref + " does not exist below " + root);
            return null;
        }
        hashed.append('\n').append(ref).append('\n').append(text(file));
        return file;
    }

    // ------------------------------------------------------------------ signals

    private Signal signal(JsonNode n, Map<String, Map<String, JsonNode>> catalogs) {
        String id = n.path("id").asText("");
        if (!PORT_ID.matcher(id).matches() && !CHANNEL_ID.matcher(id).matches()) {
            problems.add("signal id \"" + id + "\" must be <atom>.<port> or <atom>/<asset>/<channel>");
            return null;
        }
        String channel = n.path("channel").asText(null);
        JsonNode own = n.path("expect");
        JsonNode cat = null;
        if (channel != null) {
            String[] c = channel.split("/", 2);
            cat = c.length == 2 ? catalogs.getOrDefault(c[0], Map.of()).get(c[1]) : null;
            if (cat == null) {
                problems.add(id + ": channel " + channel + " is not in a catalog of the system");
            } else if (!id.endsWith("/" + c[1])) {
                problems.add(id + ": the id must end on the channel " + c[1]);
            }
        } else if (own.isMissingNode() || own.isEmpty()) {
            problems.add(id + ": needs channel or expect");
        }
        Expect expect = new Expect(
                first(own.path("unit"), cat == null ? null : cat.path("unit")),
                number(own.path("min"), cat == null ? null : cat.path("min")),
                number(own.path("max"), cat == null ? null : cat.path("max")),
                first(own.path("interval"), cat == null ? null : cat.path("sampling")),
                first(own.path("kind"), cat == null ? null : cat.path("kind")),
                first(own.path("origin"), null));
        if (expect.interval() != null && !DURATION.matcher(expect.interval()).matches()) {
            problems.add(id + ": interval " + expect.interval() + " is no duration like PT1M");
        }
        if (expect.kind() != null && !KINDS.contains(expect.kind())) {
            problems.add(id + ": kind must be one of " + new TreeSet<>(KINDS));
        }
        if (expect.min() != null && expect.max() != null && expect.min() > expect.max()) {
            problems.add(id + ": min is above max");
        }
        origin(id, "expect", expect.origin());
        Source raw = source(id, "raw", n.path("raw"));
        Source clean = source(id, "clean", n.path("clean"));
        Source features = source(id, "features", n.path("features"));
        List<String> names = new ArrayList<>();
        n.path("features").path("names").forEach(x -> names.add(x.asText()));
        if (features != null && (!"flow".equals(features.kind()) || names.isEmpty())) {
            problems.add(id + "@features: needs a flow as source and the names of the features");
        }
        if (clean != null && "generator".equals(clean.kind())) {
            problems.add(id + "@clean: a generator gives raw data");
        }
        if (raw != null && "flow".equals(raw.kind())) {
            problems.add(id + "@raw: a flow gives clean data or features");
        }
        return new Signal(id, n.path("title").asText(id), channel, expect, raw, clean, features, names, n.path("note").asText(null));
    }

    private Source source(String id, String grade, JsonNode stage) {
        JsonNode s = stage.path("source");
        if (s.isMissingNode()) {
            if (!stage.isMissingNode()) {
                problems.add(id + "@" + grade + ": source is missing");
            }
            return null;
        }
        String kind = s.path("kind").asText("");
        String at = id + "@" + grade;
        Map<String, String> tags = new TreeMap<>();
        s.path("select").path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        List<String> series = new ArrayList<>();
        s.path("select").path("series").forEach(x -> series.add(x.asText()));
        String metric = s.path("select").path("metric").asText(null);
        String origin = s.path("origin").asText(null);
        switch (kind) {
            case "topic" -> {
                if (s.path("topic").asText("").isBlank() || (metric == null && tags.isEmpty() && series.isEmpty()) || origin == null) {
                    problems.add(at + ": a topic source needs topic, select and origin");
                }
            }
            case "generator" -> {
                if (s.path("processor").asText("").isBlank()) {
                    problems.add(at + ": a generator source needs processor");
                }
                origin = Origin.SYNTHETIC.name();
            }
            case "flow" -> {
                if (!flows.containsKey(s.path("flow").asText())) {
                    problems.add(at + ": flow " + s.path("flow").asText() + " is not in the list flows");
                }
            }
            case "manual" -> {
                if (s.has("value") == s.has("table")) {
                    problems.add(at + ": a manual source needs value or table");
                }
                origin = origin == null ? Origin.REPORTED.name() : origin;
            }
            default -> {
                problems.add(at + ": source kind must be topic, generator, flow or manual");
                return null;
            }
        }
        origin(id, grade, origin);
        return new Source(kind, s.path("topic").asText(null), metric, tags, series, origin, s.path("processor").asText(null),
                s.path("params").isMissingNode() ? null : s.path("params"), s.has("seed") ? s.path("seed").asLong() : null,
                s.path("flow").asText(null), s.path("output").asText(null), s.has("value") ? s.path("value").asDouble() : null,
                s.path("table").asText(null));
    }

    private void origin(String id, String where, String origin) {
        if (origin == null) {
            return;
        }
        try {
            Origin.valueOf(origin);
        } catch (IllegalArgumentException e) {
            problems.add(id + ": origin " + origin + " (" + where + ") is none of " + List.of(Origin.values()));
        }
    }

    private void checkPlace(Signal s, Map<String, Map<String, PortSpec>> ports, Map<String, Map<String, JsonNode>> catalogs,
                            Map<String, JsonNode> assets) {
        if (!ports.isEmpty() && !ports.containsKey(s.atom())) {
            problems.add(s.id() + ": the plan has no atom " + s.atom());
            return;
        }
        if (s.isPort()) {
            if (ports.isEmpty()) {
                return;
            }
            PortSpec port = ports.get(s.atom()).get(s.id().substring(s.id().indexOf('.') + 1));
            if (port == null) {
                problems.add(s.id() + ": the atom " + s.atom() + " has no such port; it has " + ports.get(s.atom()).keySet());
            } else if (s.expect().unit() != null && !s.expect().unit().equals(port.unit())) {
                problems.add(s.id() + ": the plan gives the unit " + port.unit() + ", the expectation " + s.expect().unit());
            }
            return;
        }
        if (s.channel() == null) {
            problems.add(s.id() + ": a sensor channel needs channel");
            return;
        }
        String asset = system + "/" + s.id().substring(0, s.id().lastIndexOf('/'));
        JsonNode a = assets.get(asset);
        if (a == null) {
            problems.add(s.id() + ": the inventory has no asset " + asset);
            return;
        }
        if (!s.atom().equals(a.path("atom").asText(s.atom()))) {
            problems.add(s.id() + ": the inventory puts " + asset + " below the atom " + a.path("atom").asText());
        }
        // D47: the binding lives here; as long as the inventory lists series too, the two must agree
        if (s.raw() != null && "topic".equals(s.raw().kind()) && a.path("series").isArray()) {
            boolean listed = false;
            for (JsonNode e : a.path("series")) {
                Map<String, String> tags = new TreeMap<>();
                e.path("tags").fields().forEachRemaining(t -> tags.put(t.getKey(), t.getValue().asText()));
                listed |= e.path("metric").asText("").equals(s.raw().metric()) && tags.equals(s.raw().tags());
            }
            if (!listed) {
                notes.add(s.id() + ": the inventory does not list this series for " + asset);
            }
        }
    }

    /** A flow that is the source of a grade must write it, and may only read grades the system provides. */
    private void checkFlows() {
        Map<String, Signal> byId = new TreeMap<>();
        signals.forEach(s -> byId.put(s.id(), s));
        for (Signal s : signals) {
            for (String grade : GRADES) {
                Source src = s.source(grade);
                if (src == null || !"flow".equals(src.kind()) || !flows.containsKey(src.flow())) {
                    continue;
                }
                Flow f = flows.get(src.flow());
                String at = s.id() + "@" + grade;
                List<String> from = f.writes().getOrDefault(at, List.of());
                List<String> wanted = grade.equals("features") ? s.featureNames() : src.output() == null ? List.of() : List.of(src.output());
                if (from.isEmpty()) {
                    problems.add(at + ": flow " + f.id() + " does not write it");
                }
                wanted.stream().filter(w -> !from.contains(w))
                        .forEach(w -> problems.add(at + ": flow " + f.id() + " does not write it from " + w));
                for (String r : f.reads()) {
                    String[] p = r.split("@", 2);
                    Signal in = byId.get(p[0]);
                    if (in == null || p.length < 2 || !GRADES.contains(p[1]) || in.source(p[1]) == null) {
                        problems.add("flow " + f.id() + ": reads " + r + ", which no signal of the system provides");
                    } else if (grade.equals("features") && !p[1].equals("clean")) {
                        problems.add("flow " + f.id() + ": features are computed from clean grades, it reads " + r);
                    } else if (grade.equals("clean") && p[1].equals("raw") && !p[0].equals(s.id())) {
                        problems.add("flow " + f.id() + ": reads the raw grade of another signal (" + r + ")");
                    } else if (r.equals(at)) {
                        problems.add("flow " + f.id() + ": reads what it writes (" + r + ")");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String first(JsonNode a, JsonNode b) {
        if (a != null && !a.isMissingNode() && !a.isNull()) {
            return a.asText();
        }
        return b == null || b.isMissingNode() || b.isNull() ? null : b.asText();
    }

    private static Double number(JsonNode a, JsonNode b) {
        if (a != null && a.isNumber()) {
            return a.asDouble();
        }
        return b != null && b.isNumber() ? b.asDouble() : null;
    }

    static JsonNode read(Path file) {
        try {
            return YAML.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(file + " cannot be read", e);
        }
    }

    private static String text(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
