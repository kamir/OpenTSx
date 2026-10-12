package org.opentsx.twin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opentsx.model.Micros;
import org.opentsx.model.XxHash64;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The wiring plan ("Schaltplan") of a twin: atoms from the catalog, their parameters and policies, and wires
 * {@code "fromAtom.port -> toAtom.port"}. Standard parts, individual wiring per site.
 */
public record TwinPlan(String twin, Instant start, Duration baseStep, int steps, List<AtomSpec> atoms,
                       List<String> wiring) {

    public TwinPlan {
        if (twin == null || start == null || atoms == null || atoms.isEmpty()) {
            throw new IllegalArgumentException("plan needs twin, start and atoms");
        }
        baseStep = baseStep == null ? Duration.ofMinutes(1) : baseStep;
        if (baseStep.isZero() || baseStep.isNegative() || baseStep.toNanos() % 1000 != 0) {
            throw new IllegalArgumentException("baseStep must be a positive whole number of microseconds");
        }
        atoms = List.copyOf(atoms);
        wiring = wiring == null ? List.of() : List.copyOf(wiring);
    }

    /** One atom of the plan. {@code policy} may be null (atom default). */
    public record AtomSpec(String id, String type, Map<String, Object> params, String policy) {
        public AtomSpec {
            if (id == null || type == null || id.contains(".")) {
                throw new IllegalArgumentException("atom needs an id without '.' and a type: " + id);
            }
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** A resolved wire; {@code delayed} wires deliver the previous superstep's value. */
    public record Wire(String fromAtom, String fromPort, String toAtom, String toPort, boolean delayed) {
    }

    @JsonIgnore
    public long baseStepMicros() {
        return baseStep.toNanos() / 1000;
    }

    @JsonIgnore
    public long startMicros() {
        return Micros.of(start);
    }

    /** Instantiates the atoms (fresh, in plan order). */
    public List<Atom> instantiate(AtomCatalog catalog) {
        List<Atom> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (AtomSpec s : atoms) {
            if (!ids.add(s.id())) {
                throw new IllegalArgumentException("duplicate atom id " + s.id());
            }
            out.add(catalog.create(s.type(), s.id(), new Params(s.params())));
        }
        return out;
    }

    /** Resolves and checks the wires: ports exist, kind and unit match, every input has at most one source. */
    public List<Wire> resolve(List<Atom> instances) {
        Map<String, Atom> byId = new HashMap<>();
        instances.forEach(a -> byId.put(a.id(), a));
        List<Wire> out = new ArrayList<>();
        Set<String> wiredInputs = new HashSet<>();
        for (String w : wiring) {
            String[] sides = w.split("->");
            if (sides.length != 2) {
                throw new IllegalArgumentException("wire must look like 'a.port -> b.port': " + w);
            }
            String[] from = split(sides[0].trim(), w);
            String[] to = split(sides[1].trim(), w);
            Atom a = byId.get(from[0]);
            Atom b = byId.get(to[0]);
            if (a == null || b == null) {
                throw new IllegalArgumentException("unknown atom in wire " + w);
            }
            PortSpec out1 = port(a.outputs(), from[1], a, "output", w);
            PortSpec in1 = port(b.inputs(), to[1], b, "input", w);
            if (out1.kind() != in1.kind() || !out1.unit().equals(in1.unit())) {
                throw new IllegalArgumentException("wire " + w + " connects " + out1 + " to " + in1);
            }
            if (!wiredInputs.add(to[0] + "." + to[1])) {
                throw new IllegalArgumentException("input " + to[0] + "." + to[1] + " has more than one wire");
            }
            boolean delayed = a.phase().ordinal() >= b.phase().ordinal();
            out.add(new Wire(from[0], from[1], to[0], to[1], delayed));
        }
        return out;
    }

    /** Stable hash of the plan, part of every run id. */
    public String hash() {
        try {
            ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
            return XxHash64.hex(XxHash64.hash(m.writeValueAsString(this).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TwinPlan load(Path yaml) {
        try (InputStream in = Files.newInputStream(yaml)) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TwinPlan load(InputStream yaml) throws IOException {
        ObjectMapper m = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return m.readValue(yaml, TwinPlan.class);
    }

    /** Loads a plan from the classpath, e.g. {@code twin/h2-chain.yaml}. */
    public static TwinPlan resource(String name) {
        try (InputStream in = TwinPlan.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no plan " + name);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String[] split(String ref, String wire) {
        int dot = ref.indexOf('.');
        if (dot <= 0 || dot == ref.length() - 1) {
            throw new IllegalArgumentException("port reference must be 'atom.port' in wire " + wire);
        }
        return new String[]{ref.substring(0, dot), ref.substring(dot + 1)};
    }

    private static PortSpec port(List<PortSpec> ports, String name, Atom atom, String what, String wire) {
        return ports.stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(atom.id() + " (" + atom.type() + ") has no " + what
                        + " port '" + name + "' in wire " + wire));
    }
}
