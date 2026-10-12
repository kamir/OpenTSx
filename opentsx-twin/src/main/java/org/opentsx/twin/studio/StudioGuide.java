package org.opentsx.twin.studio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.opentsx.twin.Atom;
import org.opentsx.twin.AtomCatalog;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.TwinPlan;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The prose that makes a wiring plan readable in the Studio: what the example is about, what every atom, port,
 * parameter, control and wire means, where each value should come from, and references to compare against. A guide
 * lives next to its plan ({@code twin/<plan>.guide.yaml}) and is exported as it is; the Studio falls back to generic
 * sentences where a scenario has none.
 * <p>
 * {@link #problems(TwinPlan)} checks a guide against its plan in both directions: nothing in the plan is left
 * unexplained and nothing is explained that the plan does not have. Wire keys use the plan syntax
 * {@code "fromAtom.port -> toAtom.port"}; a control key ending in {@code ".*"} covers a family of controls.
 */
public record StudioGuide(Map<String, Object> content) {

    public static final StudioGuide EMPTY = new StudioGuide(Map.of());

    public StudioGuide {
        content = content == null ? Map.of() : content;
    }

    /** Loads a guide from the classpath, e.g. {@code twin/haus-erzgebirge.guide.yaml}. */
    public static StudioGuide resource(String name) {
        try (InputStream in = StudioGuide.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no guide " + name);
            }
            return new StudioGuide(new ObjectMapper(new YAMLFactory()).readValue(in,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

    /** The key of a wire in the guide. */
    public static String wireKey(TwinPlan.Wire w) {
        return w.fromAtom() + "." + w.fromPort() + " -> " + w.toAtom() + "." + w.toPort();
    }

    /** Everything the guide and the plan disagree on; empty if the guide explains the plan completely. */
    public List<String> problems(TwinPlan plan) {
        List<String> out = new ArrayList<>();
        if (!plan.twin().equals(content.get("twin"))) {
            out.add("guide is for twin '" + content.get("twin") + "', plan is '" + plan.twin() + "'");
        }
        Set<String> refs = new HashSet<>();
        for (Object r : list(content.get("references"))) {
            Map<String, Object> ref = map(r);
            if (blank(ref.get("id")) || blank(ref.get("title")) || blank(ref.get("url")) || blank(ref.get("note"))) {
                out.add("reference needs id, title, url and note: " + ref);
            } else if (!refs.add(ref.get("id").toString())) {
                out.add("duplicate reference " + ref.get("id"));
            }
        }
        Set<String> usedRefs = new HashSet<>();

        List<Atom> atoms = plan.instantiate(AtomCatalog.standard());
        Map<String, Object> atomDocs = map(content.get("atoms"));
        Set<String> ids = new LinkedHashSet<>();
        for (Atom at : atoms) {
            ids.add(at.id());
            Map<String, Object> doc = map(atomDocs.get(at.id()));
            if (blank(doc.get("title")) || blank(doc.get("text")) || blank(doc.get("capture"))) {
                out.add("atom " + at.id() + " needs title, text and capture");
            }
            Set<String> ports = new LinkedHashSet<>();
            at.inputs().stream().map(PortSpec::name).forEach(ports::add);
            at.outputs().stream().map(PortSpec::name).forEach(ports::add);
            cover(out, at.id() + " port", ports, map(doc.get("ports")), false);
            cover(out, at.id() + " control", at.controlDefaults().keySet(), map(doc.get("controls")), true);

            TwinPlan.AtomSpec spec = plan.atoms().stream().filter(s -> s.id().equals(at.id())).findFirst().orElseThrow();
            Map<String, Object> params = map(doc.get("params"));
            cover(out, at.id() + " parameter", spec.params().keySet(), params, false);
            params.forEach((name, v) -> {
                Map<String, Object> p = map(v);
                if (blank(p.get("text")) || blank(p.get("source")) || blank(p.get("compare"))) {
                    out.add("parameter " + at.id() + "." + name + " needs text, source and compare");
                }
                list(p.get("refs")).forEach(r -> usedRefs.add(r.toString()));
            });
        }
        atomDocs.keySet().stream().filter(k -> !ids.contains(k)).forEach(k -> out.add("guide explains unknown atom " + k));

        Set<String> wires = new LinkedHashSet<>();
        plan.resolve(atoms).forEach(w -> wires.add(wireKey(w)));
        cover(out, "wire", wires, map(content.get("wires")), false);

        usedRefs.stream().filter(r -> !refs.contains(r)).sorted().forEach(r -> out.add("unknown reference " + r));
        return out;
    }

    private static void cover(List<String> out, String what, Set<String> expected, Map<String, Object> docs,
                              boolean families) {
        for (String e : expected) {
            boolean family = families && docs.keySet().stream()
                    .anyMatch(k -> k.endsWith(".*") && e.startsWith(k.substring(0, k.length() - 1)));
            if (!family && blank(docs.get(e))) {
                out.add(what + " " + e + " is not explained");
            }
        }
        for (String k : docs.keySet()) {
            boolean family = families && k.endsWith(".*")
                    && expected.stream().anyMatch(e -> e.startsWith(k.substring(0, k.length() - 1)));
            if (!family && !expected.contains(k)) {
                out.add("guide explains unknown " + what + " " + k);
            }
        }
    }

    private static boolean blank(Object o) {
        return o == null || o.toString().isBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }
}
